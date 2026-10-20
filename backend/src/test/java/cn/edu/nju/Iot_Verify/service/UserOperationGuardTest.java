package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.exception.UserOperationBusyException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.configure.OperationAdmissionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyList;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class UserOperationGuardTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void redisSlotPreventsSameUserAdmissionAcrossGuardInstances() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true, false);
        UserOperationGuard firstInstance = new UserOperationGuard(redisTemplate);
        UserOperationGuard secondInstance = new UserOperationGuard(redisTemplate);

        UserOperationGuard.Lease lease = firstInstance.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1));
        assertThrows(UserOperationBusyException.class, () -> secondInstance.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1)));

        lease.close();
    }

    @Test
    void redisFailureFallsBackToReleasableProcessLocalSlot() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);

        UserOperationGuard.Lease lease = guard.acquire(
                7L, UserOperationGuard.Kind.CHAT, 1, Duration.ofMinutes(1));
        assertThrows(UserOperationBusyException.class, () -> guard.acquire(
                7L, UserOperationGuard.Kind.CHAT, 1, Duration.ofMinutes(1)));
        lease.close();

        UserOperationGuard.Lease next = assertDoesNotThrow(() -> guard.acquire(
                7L, UserOperationGuard.Kind.CHAT, 1, Duration.ofMinutes(1)));
        next.close();
    }

    @Test
    void activeRedisLeaseIsRenewedAndClosedLeaseStopsRenewing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);

        UserOperationGuard.Lease lease = guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1));
        guard.renewActiveLeases();

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());
        lease.close();
        guard.renewActiveLeases();
        verify(redisTemplate, times(1)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void leaseOwnershipMismatchStopsTheOldOperation() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);
        UserOperationGuard.Lease lease = guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1));

        guard.renewActiveLeases();

        assertFalse(lease.isActive());
        assertThrows(ServiceUnavailableException.class, lease::requireActive);
        lease.close();
    }

    @Test
    void elapsedRedisLeaseCannotRemainActiveWhenHeartbeatDidNotRun() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);
        UserOperationGuard.Lease lease = guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1));
        Field lastConfirmed = lease.getClass().getDeclaredField("lastConfirmedNanos");
        lastConfirmed.setAccessible(true);
        lastConfirmed.setLong(lease, System.nanoTime() - Duration.ofMinutes(2).toNanos());

        assertFalse(lease.isActive());
        assertThrows(ServiceUnavailableException.class, lease::requireActive);
        lease.close();
    }

    @Test
    void acquisitionConfirmationIsAnchoredBeforeTheRedisRoundTrip() throws Exception {
        AtomicLong responseStartedNanos = new AtomicLong();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    responseStartedNanos.set(System.nanoTime());
                    LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                    return true;
                });
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);

        UserOperationGuard.Lease lease = guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1));

        assertTrue(readLastConfirmedNanos(lease) <= responseStartedNanos.get());
        lease.close();
    }

    @Test
    void acquisitionResponseAfterItsTtlFailsClosedWithoutLeakingTheLocalSlot() {
        AtomicInteger attempts = new AtomicInteger();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    if (attempts.getAndIncrement() == 0) {
                        delayAtLeast(Duration.ofMillis(5));
                    }
                    return true;
                });
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);

        assertThrows(ServiceUnavailableException.class, () -> guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMillis(1)));

        UserOperationGuard.Lease next = assertDoesNotThrow(() -> guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1)));
        next.close();
        verify(valueOperations, times(2))
                .setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void renewalConfirmationDoesNotExtendTheLeaseByResponseDelay() throws Exception {
        AtomicLong responseStartedNanos = new AtomicLong();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    responseStartedNanos.set(System.nanoTime());
                    LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                    return 1L;
                });
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);
        UserOperationGuard.Lease lease = guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1));

        guard.renewActiveLeases();

        assertTrue(readLastConfirmedNanos(lease) <= responseStartedNanos.get());
        lease.close();
    }

    private long readLastConfirmedNanos(UserOperationGuard.Lease lease) throws Exception {
        Field lastConfirmed = lease.getClass().getDeclaredField("lastConfirmedNanos");
        lastConfirmed.setAccessible(true);
        return lastConfirmed.getLong(lease);
    }

    private void delayAtLeast(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(deadline - System.nanoTime());
        }
    }

    @Test
    void longRequestedTtlIsCappedForRenewableLeaseRecovery() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);

        UserOperationGuard.Lease lease = guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofHours(2));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).setIfAbsent(anyString(), anyString(), ttl.capture());
        org.junit.jupiter.api.Assertions.assertEquals(Duration.ofMinutes(2), ttl.getValue());
        lease.close();
    }

    @Test
    void renewableTtlAlwaysSpansAtLeastFourConfiguredHeartbeats() {
        OperationAdmissionConfig config = new OperationAdmissionConfig();
        config.setLeaseHeartbeatMs(Duration.ofMinutes(3).toMillis());
        UserOperationGuard guard = new UserOperationGuard(redisTemplate, config);

        org.junit.jupiter.api.Assertions.assertEquals(Duration.ofMinutes(12), guard.renewableLeaseTtl());
    }

    @Test
    void failedReleaseIsRetriedByScheduledMaintenance() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(1L);
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);
        UserOperationGuard.Lease lease = guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1));

        lease.close();
        guard.renewActiveLeases();

        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void interruptedWorkerReleasesItsSlotWithoutWaitingForMaintenanceAndKeepsItsInterrupt() {
        AtomicBoolean occupied = new AtomicBoolean();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> occupied.compareAndSet(false, true));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(invocation -> {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new RedisConnectionFailureException("Redis command interrupted");
                    }
                    occupied.set(false);
                    return 1L;
                });
        UserOperationGuard guard = new UserOperationGuard(redisTemplate);
        UserOperationGuard.Lease lease = guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1));

        Thread.currentThread().interrupt();
        try {
            lease.close();
            assertTrue(Thread.currentThread().isInterrupted(), "Cleanup must preserve the cancellation signal");
            assertFalse(occupied.get(), "An interrupted worker must not leave a Redis slot until the next heartbeat");
        } finally {
            Thread.interrupted();
        }

        UserOperationGuard.Lease next = assertDoesNotThrow(() -> guard.acquire(
                7L, UserOperationGuard.Kind.FORMAL, 1, Duration.ofMinutes(1)));
        next.close();
    }
}
