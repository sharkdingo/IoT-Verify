package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStage;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.testutil.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;
import java.io.IOException;

import static cn.edu.nju.Iot_Verify.testutil.InteractiveExecutionFixtures.awaitQueuedTask;
import static cn.edu.nju.Iot_Verify.testutil.InteractiveExecutionFixtures.occupyExecutorWorker;
import static cn.edu.nju.Iot_Verify.testutil.InteractiveExecutionFixtures.runCancellableRequest;
import static cn.edu.nju.Iot_Verify.testutil.InteractiveExecutionFixtures.singleWorkerExecutor;
import static cn.edu.nju.Iot_Verify.testutil.InteractiveExecutionFixtures.startInterruptibleRequest;
import static cn.edu.nju.Iot_Verify.testutil.InteractiveExecutionFixtures.unavailableRedisStore;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InteractiveAiExecutionServiceTest {

    private ThreadPoolTaskExecutor executor;
    private InteractiveAiExecutionService service;

    @BeforeEach
    void setUp() {
        executor = singleWorkerExecutor();
        service = new InteractiveAiExecutionService(executor, unavailableRedisStore());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void workerErrorRetainsItsTypeAndLogsTheRequestAndStage() {
        InternalError failure = new InternalError("CodeCache diagnostic");
        try (LogCapture logs = LogCapture.forClass(InteractiveAiExecutionService.class)) {
            assertSame(failure, assertThrows(InternalError.class,
                    () -> service.execute(1L, "request-error", () -> {
                        service.markStage(1L, "request-error", InteractiveOperationStage.VALIDATING_RESULT);
                        throw failure;
                    })));
            var event = logs.events().stream().filter(entry -> entry.getThrowableProxy() != null)
                    .findFirst().orElseThrow();
            assertTrue(event.getFormattedMessage().contains("requestId=request-error"));
            assertTrue(event.getFormattedMessage().contains("stage=VALIDATING_RESULT"));
            assertEquals(InternalError.class.getName(), event.getThrowableProxy().getClassName());
        }
        assertDoesNotThrow(() -> service.execute(1L, "request-after-error", () -> null));
    }

    @Test
    void unexpectedCheckedFailureIsAnInternalErrorWithItsOriginalCause() {
        IOException failure = new IOException("Worker diagnostic");
        try (LogCapture logs = LogCapture.forClass(InteractiveAiExecutionService.class)) {
            InternalServerException error = assertThrows(InternalServerException.class,
                    () -> service.execute(1L, "request-checked", () -> { throw failure; }));
            assertSame(failure, error.getCause());
            var event = logs.events().stream().filter(entry -> entry.getThrowableProxy() != null)
                    .findFirst().orElseThrow();
            assertEquals(IOException.class.getName(), event.getThrowableProxy().getClassName());
        }
        assertDoesNotThrow(() -> service.execute(1L, "request-after-checked", () -> null));
    }

    @Test
    void cancel_interruptsTheTrackedServerTask() throws Exception {
        var request = startInterruptibleRequest(operation -> service.execute(1L, "request-123", operation));

        request.awaitStarted();
        assertTrue(service.cancel(1L, "request-123"));
        request.awaitResponseAndInterrupt();
    }

    @Test
    void accountDeletionStopsLocalWorkAndPublishesDistributedCancellation() throws Exception {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        InteractiveAiExecutionService instance = new InteractiveAiExecutionService(executor, store);
        var request = startInterruptibleRequest(operation -> instance.execute(1L, "request-123", operation));

        request.awaitStarted();
        instance.requestUserExecutionStop(1L);
        request.awaitResponseAndInterrupt();
        verify(store).requestUserCancellation("recommendation", 1L);
    }

    @Test
    void distributedAcquisitionFailureReleasesLocalUserAdmission() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        ServiceUnavailableException unavailable = new ServiceUnavailableException("Redis ownership is uncertain.");
        when(store.acquire("recommendation", 1L, "request-123", true))
                .thenThrow(unavailable)
                .thenReturn(null);
        InteractiveAiExecutionService instance = new InteractiveAiExecutionService(executor, store);

        assertEquals(unavailable, assertThrows(ServiceUnavailableException.class,
                () -> instance.execute(1L, "request-123", () -> null)));
        assertDoesNotThrow(() -> instance.execute(1L, "request-123", () -> null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "account"})
    void pendingOwnershipCanBeCancelledBeforeRecommendationStarts(String stop) throws Exception {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease = mock(DistributedInteractiveExecutionStore.Lease.class);
        CountDownLatch acquiring = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        when(store.acquire("recommendation", 1L, "request-123", true)).thenAnswer(invocation -> {
            acquiring.countDown();
            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            return lease;
        });
        InteractiveAiExecutionService instance = new InteractiveAiExecutionService(executor, store);
        AtomicBoolean providerCalled = new AtomicBoolean(false);
        CompletableFuture<String> request = CompletableFuture.supplyAsync(() ->
                instance.execute(1L, "request-123", () -> {
                    providerCalled.set(true);
                    return "recommendation";
                }));
        try {
            assertTrue(acquiring.await(2, TimeUnit.SECONDS));
            if ("request".equals(stop)) {
                assertTrue(instance.cancel(1L, "request-123"));
            } else {
                instance.requestUserExecutionStop(1L);
            }
            acquired.countDown();

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> request.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof ServiceUnavailableException);
            assertFalse(providerCalled.get());
            assertEquals("FINISHED", instance.getStatus(1L, "request-123").getState());
            verify(store).abandon(lease);
            assertDoesNotThrow(() -> instance.execute(1L, "request-next", () -> null));
        } finally {
            acquired.countDown();
        }
    }

    @Test
    void successfulRecommendationIsSettledBeforeItsResultIsReturned() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease =
                mock(DistributedInteractiveExecutionStore.Lease.class);
        when(store.acquire("recommendation", 1L, "request-123", true)).thenReturn(lease);
        InteractiveAiExecutionService instance = new InteractiveAiExecutionService(executor, store);

        String result = instance.execute(1L, "request-123", () -> {
            instance.markStage(1L, "request-123", InteractiveOperationStage.VALIDATING_RESULT);
            return "recommendation";
        });

        assertEquals("recommendation", result);
        verify(store).completeSuccessfully(lease, InteractiveOperationStage.VALIDATING_RESULT);
        verify(store, never()).finish(lease, InteractiveOperationStage.VALIDATING_RESULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"poll", "cancel", "account"})
    void successfulRecommendationCannotBeCancelledAfterItsCompletionFence(String stop) throws Exception {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease =
                mock(DistributedInteractiveExecutionStore.Lease.class);
        when(store.acquire("recommendation", 1L, "request-123", true)).thenReturn(lease);
        // The successful Redis fence removes the owner before its response reaches this worker.
        when(store.shouldStop(lease)).thenReturn(true);
        InteractiveAiExecutionService instance = new InteractiveAiExecutionService(executor, store);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch returnCompletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            published.countDown();
            assertTrue(returnCompletion.await(2, TimeUnit.SECONDS));
            return null;
        }).when(store).completeSuccessfully(lease, InteractiveOperationStage.VALIDATING_RESULT);

        CompletableFuture<String> request = CompletableFuture.supplyAsync(() ->
                instance.execute(1L, "request-123", () -> {
                    instance.markStage(1L, "request-123", InteractiveOperationStage.VALIDATING_RESULT);
                    return "validated recommendation";
                }));
        FutureTask<Void> latePoll = new FutureTask<>(() -> {
            switch (stop) {
                case "poll" -> instance.pollDistributedCancellation();
                case "cancel" -> assertFalse(instance.cancel(1L, "request-123"));
                case "account" -> instance.requestUserExecutionStop(1L);
                default -> throw new AssertionError("Unknown stop path");
            }
            return null;
        });
        Thread polling = new Thread(latePoll, "recommendation-late-poll");
        try {
            assertTrue(published.await(2, TimeUnit.SECONDS));
            polling.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (polling.getState() != Thread.State.BLOCKED && polling.getState() != Thread.State.TERMINATED) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Poll did not reach the completion fence");
                Thread.onSpinWait();
            }
            returnCompletion.countDown();

            assertEquals("validated recommendation", assertDoesNotThrow(() -> request.get(2, TimeUnit.SECONDS)));
            latePoll.get(2, TimeUnit.SECONDS);
            assertEquals(InteractiveOperationStage.VALIDATING_RESULT,
                    instance.getStatus(1L, "request-123").getStage());
        } finally {
            returnCompletion.countDown();
            polling.join(2_000);
        }
    }

    @Test
    void lostDistributedOwnershipDoesNotDeliverACompletedRecommendation() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease =
                mock(DistributedInteractiveExecutionStore.Lease.class);
        when(store.acquire("recommendation", 1L, "request-123", true)).thenReturn(lease);
        ServiceUnavailableException ownershipLost =
                new ServiceUnavailableException("Distributed ownership was lost.");
        doThrow(ownershipLost).when(store)
                .completeSuccessfully(lease, InteractiveOperationStage.VALIDATING_RESULT);
        InteractiveAiExecutionService instance = new InteractiveAiExecutionService(executor, store);

        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> instance.execute(1L, "request-123", () -> {
                    instance.markStage(1L, "request-123", InteractiveOperationStage.VALIDATING_RESULT);
                    return "stale recommendation";
                }));

        assertEquals(ownershipLost, error);
        verify(store).finish(lease, InteractiveOperationStage.VALIDATING_RESULT);
    }

    @Test
    void statusReportsTheServerObservedStageWhileRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CompletableFuture<Void> request = CompletableFuture.runAsync(() ->
                service.execute(1L, "request-123", () -> {
                    started.countDown();
                    releaseProvider.await();
                    return null;
                }));

        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            service.markStage(1L, "request-123", InteractiveOperationStage.REQUESTING_MODEL);

            var status = service.getStatus(1L, "request-123");
            assertEquals("RUNNING", status.getState());
            assertEquals(InteractiveOperationStage.REQUESTING_MODEL, status.getStage());
            assertTrue(status.getElapsedMs() >= 0);
        } finally {
            releaseProvider.countDown();
            request.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void completedStatusRemainsAvailableForTheFinalPollingTick() {
        service.execute(1L, "request-123", () -> {
            service.markStage(1L, "request-123", InteractiveOperationStage.VALIDATING_RESULT);
            return null;
        });

        var status = service.getStatus(1L, "request-123");
        assertEquals("FINISHED", status.getState());
        assertEquals(InteractiveOperationStage.VALIDATING_RESULT, status.getStage());
    }

    @Test
    void acceptsTheSameRequestIdCharactersAsTheControllerContract() {
        assertDoesNotThrow(() -> service.execute(1L, "request.v1:part", () -> null));
    }

    @Test
    void cancel_keepsUserAdmissionUntilInterruptIgnoringProviderActuallyReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch providerReturned = new CountDownLatch(1);
        CompletableFuture<Void> request = runCancellableRequest(() ->
                service.execute(1L, "request-123", () -> {
                    started.countDown();
                    while (releaseProvider.getCount() > 0) {
                        try {
                            releaseProvider.await(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // Simulate a provider call that does not stop when its Java thread is interrupted.
                        }
                    }
                    providerReturned.countDown();
                    return null;
                }));

        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(service.cancel(1L, "request-123"));
            request.get(2, TimeUnit.SECONDS);
            service.markStage(1L, "request-123", InteractiveOperationStage.FINALIZING);
            assertEquals(InteractiveOperationStage.CANCELLING,
                    service.getStatus(1L, "request-123").getStage());

            assertThrows(ServiceUnavailableException.class,
                    () -> service.execute(1L, "request-456", () -> null));

            releaseProvider.countDown();
            assertTrue(providerReturned.await(2, TimeUnit.SECONDS));
            awaitUserAdmissionRelease();
        } finally {
            releaseProvider.countDown();
        }
    }

    @Test
    void cancel_beforeStartRemovesQueuedWorkWithoutInvokingProvider() throws Exception {
        CountDownLatch releaseWorker = occupyExecutorWorker(executor);

        AtomicBoolean providerCalled = new AtomicBoolean(false);
        CompletableFuture<Void> request = runCancellableRequest(() ->
                service.execute(1L, "request-123", () -> {
                    providerCalled.set(true);
                    return null;
                }));

        try {
            awaitQueuedTask(executor, "recommendation");
            assertTrue(service.cancel(1L, "request-123"));
            request.get(2, TimeUnit.SECONDS);
            releaseWorker.countDown();
            assertDoesNotThrow(() -> service.execute(1L, "request-456", () -> null));
        } finally {
            releaseWorker.countDown();
        }
        assertFalse(providerCalled.get());
    }

    @Test
    void statusAndCancellationCanBeServedByAnotherInstance() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        InteractiveAiExecutionService otherInstance = new InteractiveAiExecutionService(executor, store);
        var remoteStatus = cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStatusDto.builder()
                .requestId("request-123")
                .state("RUNNING")
                .stage(InteractiveOperationStage.REQUESTING_MODEL)
                .elapsedMs(25)
                .build();
        when(store.getStatus("recommendation", 1L, "request-123"))
                .thenReturn(Optional.of(remoteStatus));
        when(store.requestCancellation("recommendation", 1L, "request-123"))
                .thenReturn(true);

        assertEquals(remoteStatus, otherInstance.getStatus(1L, "request-123"));
        assertTrue(otherInstance.cancel(1L, "request-123"));
    }

    @Test
    void distributedActiveStatusWinsOverStaleLocalCompletionForReusedRequestId() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        InteractiveAiExecutionService instance = new InteractiveAiExecutionService(executor, store);
        instance.execute(1L, "request-123", () -> null);
        var currentStatus = cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStatusDto.builder()
                .requestId("request-123")
                .state("RUNNING")
                .stage(InteractiveOperationStage.REQUESTING_MODEL)
                .elapsedMs(5)
                .build();
        when(store.getStatus("recommendation", 1L, "request-123"))
                .thenReturn(Optional.of(currentStatus));

        assertEquals(currentStatus, instance.getStatus(1L, "request-123"));
    }

    private void awaitUserAdmissionRelease() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try {
                assertDoesNotThrow(() -> service.execute(1L, "request-456", () -> null));
                return;
            } catch (AssertionError error) {
                if (System.nanoTime() >= deadline) throw error;
                Thread.yield();
            }
        }
    }
}
