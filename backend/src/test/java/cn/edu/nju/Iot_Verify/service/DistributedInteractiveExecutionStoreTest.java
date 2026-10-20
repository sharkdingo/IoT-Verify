package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStage;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DistributedInteractiveExecutionStoreTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> values;

    private DistributedInteractiveExecutionStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(values);
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        store = new DistributedInteractiveExecutionStore(redisTemplate);
    }

    @Test
    void remoteInstanceCanReadStatusAndPublishCancellation() {
        when(redisTemplate.execute(any(RedisScript.class), anyList()))
                .thenReturn("RUNNING\nREQUESTING_MODEL\n" + (System.currentTimeMillis() - 20));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        var status = store.getStatus("recommendation", 7L, "request-123").orElseThrow();
        boolean cancelled = store.requestCancellation("recommendation", 7L, "request-123");

        assertEquals("RUNNING", status.getState());
        assertEquals(InteractiveOperationStage.REQUESTING_MODEL, status.getStage());
        assertTrue(cancelled);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of(
                        "iot-verify:interactive:recommendation:7:request-123:owner",
                        "iot-verify:interactive:recommendation:7:request-123:cancel",
                        "iot-verify:interactive:recommendation:7:request-123:status")),
                eq("30000"), eq("CANCELLING"));
    }

    @Test
    void accountDeletionPublishesABoundedUserCancellationFence() {
        assertTrue(store.requestUserCancellation("recommendation", 7L));

        verify(values).set(
                eq("iot-verify:interactive:recommendation:7:cancel-all"),
                anyString(),
                eq(Duration.ofMinutes(10)));
    }

    @Test
    void interruptedWorkerPublishesItsTerminalStatusAndKeepsItsInterrupt() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        AtomicBoolean finished = new AtomicBoolean();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new RedisConnectionFailureException("Redis command interrupted");
                    }
                    finished.set(true);
                    return 1L;
                });
        var lease = store.acquire("fix", 7L, "request-123", false);

        Thread.currentThread().interrupt();
        try {
            store.finish(lease, InteractiveOperationStage.CANCELLING);
            assertTrue(Thread.currentThread().isInterrupted(), "Cleanup must preserve the cancellation signal");
            assertTrue(finished.get(), "Terminal publication must not fail on the worker's own cancellation flag");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void duplicateRequestOwnershipIsRejectedAcrossInstances() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(-1L);

        DistributedInteractiveExecutionStore.BusyException error = assertThrows(
                DistributedInteractiveExecutionStore.BusyException.class,
                () -> store.acquire("fix", 7L, "request-123", false));

        assertEquals(DistributedInteractiveExecutionStore.BusyScope.REQUEST, error.getScope());
    }

    @Test
    void acquisitionAtomicallyRegistersOwnerUserAndInitialStatus() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(1L);

        store.acquire("recommendation", 7L, "request-123", true);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of(
                        "iot-verify:interactive:recommendation:7:request-123:owner",
                        "iot-verify:interactive:recommendation:7:active",
                        "iot-verify:interactive:recommendation:7:request-123:status")),
                anyString(), eq("30000"), eq("1"), anyString());
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void staleActiveStatusIsIgnoredAfterItsOwnerLeaseDisappears() {
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(null);

        assertTrue(store.getStatus("fix", 7L, "request-123").isEmpty());
    }

    @Test
    void initialRedisConnectionFailureFallsBackBeforeAnyDistributedWrite() {
        when(redisTemplate.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        var lease = store.acquire("fix", 7L, "request-123", false);

        assertFalse(store.shouldStop(lease));
        assertDoesNotThrow(() -> store.completeSuccessfully(
                lease, InteractiveOperationStage.FINALIZING));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any());
    }

    @Test
    void uncertainAtomicAcquisitionFailsClosedAndAttemptsTokenFencedCleanup() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("response lost"));

        ServiceUnavailableException error = assertThrows(
                ServiceUnavailableException.class,
                () -> store.acquire("fix", 7L, "request-123", false));

        assertTrue(error.getMessage().contains("could not be confirmed"));
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of(
                        "iot-verify:interactive:fix:7:request-123:owner",
                        "iot-verify:interactive:fix:7:active",
                        "iot-verify:interactive:fix:7:request-123:status",
                        "iot-verify:interactive:fix:7:request-123:cancel")),
                anyString(), eq("0"));
    }

    @Test
    void activeLeasePollUsesTokenFencedRenewalAndContinuesWithoutCancellation() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any())).thenReturn(0L);

        var lease = store.acquire("fix", 7L, "request-123", false);

        assertFalse(store.shouldStop(lease));
        verify(redisTemplate).execute(any(RedisScript.class),
                eq(java.util.List.of(
                        "iot-verify:interactive:fix:7:request-123:owner",
                        "iot-verify:interactive:fix:7:active",
                        "iot-verify:interactive:fix:7:request-123:status",
                        "iot-verify:interactive:fix:7:request-123:cancel",
                        "iot-verify:interactive:fix:7:cancel-all")),
                any(), eq("30000"), eq("0"));
    }

    @Test
    void lateSuccessfulPollDoesNotRenewAnAlreadyExpiredLease() {
        AtomicLong monotonicNanos = new AtomicLong(1L);
        store = new DistributedInteractiveExecutionStore(redisTemplate, monotonicNanos::get);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any())).thenAnswer(invocation -> {
                    monotonicNanos.addAndGet(Duration.ofSeconds(30).toNanos());
                    return 0L;
                });
        var lease = store.acquire("fix", 7L, "request-123", false);

        assertTrue(store.shouldStop(lease));
    }

    @Test
    void lateSuccessfulAcquisitionFailsClosedAndReleasesItsToken() {
        AtomicLong monotonicNanos = new AtomicLong(1L);
        store = new DistributedInteractiveExecutionStore(redisTemplate, monotonicNanos::get);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenAnswer(invocation -> {
                    monotonicNanos.addAndGet(Duration.ofSeconds(30).toNanos());
                    return 1L;
                });

        ServiceUnavailableException error = assertThrows(
                ServiceUnavailableException.class,
                () -> store.acquire("fix", 7L, "request-123", false));

        assertTrue(error.getMessage().contains("expired before acquisition was confirmed"));
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of(
                        "iot-verify:interactive:fix:7:request-123:owner",
                        "iot-verify:interactive:fix:7:active",
                        "iot-verify:interactive:fix:7:request-123:status",
                        "iot-verify:interactive:fix:7:request-123:cancel")),
                anyString(), eq("0"));
    }

    @Test
    void cancellationObservationStillRenewsOwnershipUntilTheCallableExits() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any())).thenReturn(1L);
        var lease = store.acquire("fix", 7L, "request-123", false);

        assertTrue(store.shouldStop(lease));
        assertTrue(store.shouldStop(lease));

        verify(redisTemplate, times(2)).execute(any(RedisScript.class),
                eq(java.util.List.of(
                        "iot-verify:interactive:fix:7:request-123:owner",
                        "iot-verify:interactive:fix:7:active",
                        "iot-verify:interactive:fix:7:request-123:status",
                        "iot-verify:interactive:fix:7:request-123:cancel",
                        "iot-verify:interactive:fix:7:cancel-all")),
                any(), eq("30000"), eq("0"));
    }

    @Test
    void successfulCompletionAtomicallyChecksStopFencesAndReleasesOwnership() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);
        var lease = store.acquire("recommendation", 7L, "request-123", true);

        assertDoesNotThrow(() -> store.completeSuccessfully(
                lease, InteractiveOperationStage.VALIDATING_RESULT));

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of(
                        "iot-verify:interactive:recommendation:7:request-123:owner",
                        "iot-verify:interactive:recommendation:7:active",
                        "iot-verify:interactive:recommendation:7:request-123:status",
                        "iot-verify:interactive:recommendation:7:request-123:cancel",
                        "iot-verify:interactive:recommendation:7:cancel-all")),
                anyString(), eq("VALIDATING_RESULT"), anyString(), eq("15000"), eq("1"));
    }

    @Test
    void expiredOrReplacedOwnerFailsClosedAtCompletion() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenReturn(-1L);
        var lease = store.acquire("fix", 7L, "request-123", false);

        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> store.completeSuccessfully(lease, InteractiveOperationStage.FINALIZING));

        assertTrue(error.getMessage().contains("ownership or stop state changed"));
    }

    @Test
    void observedStopFailsClosedAtCompletion() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenReturn(0L);
        var lease = store.acquire("fix", 7L, "request-123", false);

        assertThrows(ServiceUnavailableException.class,
                () -> store.completeSuccessfully(lease, InteractiveOperationStage.FINALIZING));
    }

    @Test
    void uncertainCompletionResponseFailsClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("response lost"));
        var lease = store.acquire("fix", 7L, "request-123", false);

        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> store.completeSuccessfully(lease, InteractiveOperationStage.FINALIZING));

        assertTrue(error.getMessage().contains("completion could not be confirmed"));
    }
}
