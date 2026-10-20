package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStage;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InteractiveFixExecutionServiceTest {

    private ThreadPoolTaskExecutor executor;
    private InteractiveFixExecutionService service;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        service = new InteractiveFixExecutionService(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void registrationFailureReleasesTheLocalRequestIdWithoutStartingSearch() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease = mock(DistributedInteractiveExecutionStore.Lease.class);
        when(store.acquire("fix", 1L, "request-123", false))
                .thenThrow(new ServiceUnavailableException("Ownership could not be confirmed"))
                .thenReturn(lease);
        InteractiveFixExecutionService instance = new InteractiveFixExecutionService(executor, store);
        AtomicBoolean checkerCalled = new AtomicBoolean(false);

        assertThrows(ServiceUnavailableException.class, () -> instance.execute(1L, "request-123", () -> {
            checkerCalled.set(true);
            return null;
        }));
        assertFalse(checkerCalled.get());
        assertEquals("fix", instance.execute(1L, "request-123", () -> "fix"));
        verify(store, never()).requestCancellation("fix", 1L, "request-123");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingOwnershipHasAnHonestWaitingStatusAndCanBeCancelled(boolean cancel) throws Exception {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease = mock(DistributedInteractiveExecutionStore.Lease.class);
        CountDownLatch acquiring = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        when(store.acquire("fix", 1L, "request-123", false)).thenAnswer(invocation -> {
            acquiring.countDown();
            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            return lease;
        });
        InteractiveFixExecutionService instance = new InteractiveFixExecutionService(executor, store);
        AtomicBoolean checkerCalled = new AtomicBoolean(false);
        CompletableFuture<String> request = CompletableFuture.supplyAsync(() ->
                instance.execute(1L, "request-123", () -> {
                    checkerCalled.set(true);
                    return "fix";
                }));
        try {
            assertTrue(acquiring.await(2, TimeUnit.SECONDS));
            var status = instance.getStatus(1L, "request-123");
            assertEquals("WAITING", status.getState());
            assertEquals(InteractiveOperationStage.QUEUED, status.getStage());
            assertFalse(checkerCalled.get());
            if (cancel) assertTrue(instance.cancel(1L, "request-123"));
            acquired.countDown();
            if (cancel) {
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> request.get(2, TimeUnit.SECONDS));
                assertTrue(error.getCause() instanceof ServiceUnavailableException);
                assertFalse(checkerCalled.get());
                assertEquals("FINISHED", instance.getStatus(1L, "request-123").getState());
                verify(store).abandon(lease);
                verify(store, never()).completeSuccessfully(lease, InteractiveOperationStage.QUEUED);
            } else {
                assertEquals("fix", request.get(2, TimeUnit.SECONDS));
                verify(store).completeSuccessfully(lease, InteractiveOperationStage.RUNNING);
            }
        } finally {
            acquired.countDown();
        }
    }

    @Test
    void cancel_keepsRequestIdReservedUntilInterruptIgnoringSearchActuallyReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseSearch = new CountDownLatch(1);
        CountDownLatch searchReturned = new CountDownLatch(1);
        CompletableFuture<Void> request = CompletableFuture.runAsync(() -> {
            try {
                service.execute(1L, "request-123", () -> {
                    started.countDown();
                    while (releaseSearch.getCount() > 0) {
                        try {
                            releaseSearch.await(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // Simulate an external checker that has not exited after interruption.
                        }
                    }
                    searchReturned.countDown();
                    return null;
                });
            } catch (ServiceUnavailableException expected) {
                // The waiting HTTP request is released as soon as cancellation is accepted.
            }
        });

        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(service.cancel(1L, "request-123"));
            request.get(2, TimeUnit.SECONDS);
            service.markStage(1L, "request-123", InteractiveOperationStage.FINALIZING);
            assertEquals(InteractiveOperationStage.CANCELLING,
                    service.getStatus(1L, "request-123").getStage());

            assertThrows(BadRequestException.class,
                    () -> service.execute(1L, "request-123", () -> null));

            releaseSearch.countDown();
            assertTrue(searchReturned.await(2, TimeUnit.SECONDS));
            awaitRequestIdRelease();
        } finally {
            releaseSearch.countDown();
        }
    }

    @Test
    void accountDeletionStopsLocalWorkAndPublishesDistributedCancellation() throws Exception {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        InteractiveFixExecutionService instance = new InteractiveFixExecutionService(executor, store);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CompletableFuture<Void> request = CompletableFuture.runAsync(() -> {
            try {
                instance.execute(1L, "request-123", () -> {
                    started.countDown();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (ServiceUnavailableException expected) {
                // Account deletion releases the waiting HTTP request.
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        instance.requestUserExecutionStop(1L);
        request.get(2, TimeUnit.SECONDS);

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        verify(store).requestUserCancellation("fix", 1L);
    }

    @Test
    void successfulFixIsSettledBeforeItsResultIsReturned() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease =
                mock(DistributedInteractiveExecutionStore.Lease.class);
        when(store.acquire("fix", 1L, "request-123", false)).thenReturn(lease);
        InteractiveFixExecutionService instance = new InteractiveFixExecutionService(executor, store);

        String result = instance.execute(1L, "request-123", () -> {
            instance.markStage(1L, "request-123", InteractiveOperationStage.FINALIZING);
            return "fix";
        });

        assertEquals("fix", result);
        verify(store).completeSuccessfully(lease, InteractiveOperationStage.FINALIZING);
        verify(store, never()).finish(lease, InteractiveOperationStage.FINALIZING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"poll", "cancel", "account"})
    void successfulCompletionCannotBeCancelledWhileItsResponseIsReturning(String stop) throws Exception {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease =
                mock(DistributedInteractiveExecutionStore.Lease.class);
        when(store.acquire("fix", 1L, "request-123", false)).thenReturn(lease);
        // Redis has released the owner after its successful completion fence.
        when(store.shouldStop(lease)).thenReturn(true);
        InteractiveFixExecutionService instance = new InteractiveFixExecutionService(executor, store);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch returnCompletion = new CountDownLatch(1);
        doAnswer(invocation -> {
            published.countDown();
            assertTrue(returnCompletion.await(2, TimeUnit.SECONDS));
            return null;
        }).when(store).completeSuccessfully(lease, InteractiveOperationStage.FINALIZING);

        CompletableFuture<String> request = CompletableFuture.supplyAsync(() ->
                instance.execute(1L, "request-123", () -> {
                    instance.markStage(1L, "request-123", InteractiveOperationStage.FINALIZING);
                    return "verified fix";
                }));
        FutureTask<Void> lateStop = new FutureTask<>(() -> {
            switch (stop) {
                case "poll" -> instance.pollDistributedCancellation();
                case "cancel" -> assertFalse(instance.cancel(1L, "request-123"));
                case "account" -> instance.requestUserExecutionStop(1L);
                default -> throw new AssertionError("Unknown stop path");
            }
            return null;
        });
        Thread stopping = new Thread(lateStop, "fix-late-stop");
        try {
            assertTrue(published.await(2, TimeUnit.SECONDS));
            stopping.start();
            awaitBlockedOrTerminated(stopping);
            returnCompletion.countDown();

            assertEquals("verified fix", request.get(2, TimeUnit.SECONDS));
            lateStop.get(2, TimeUnit.SECONDS);
            assertEquals(InteractiveOperationStage.FINALIZING,
                    instance.getStatus(1L, "request-123").getStage());
            verify(store, never()).shouldStop(lease);
            verify(store, never()).finish(lease, InteractiveOperationStage.FINALIZING);
        } finally {
            returnCompletion.countDown();
            stopping.join(2_000);
        }
    }

    @Test
    void lostDistributedLeaseStillStopsAnActiveSearch() throws Exception {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease =
                mock(DistributedInteractiveExecutionStore.Lease.class);
        when(store.acquire("fix", 1L, "request-123", false)).thenReturn(lease);
        when(store.shouldStop(lease)).thenReturn(true);
        InteractiveFixExecutionService instance = new InteractiveFixExecutionService(executor, store);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CompletableFuture<String> request = CompletableFuture.supplyAsync(() ->
                instance.execute(1L, "request-123", () -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        throw e;
                    }
                    return "stale fix";
                }));

        assertTrue(started.await(2, TimeUnit.SECONDS));
        instance.pollDistributedCancellation();

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> request.get(2, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof ServiceUnavailableException);
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        verify(store, never()).completeSuccessfully(lease, InteractiveOperationStage.RUNNING);
    }

    @Test
    void acceptedCancellationCannotPublishSuccessWhenAnInterruptIgnoringSearchReturns() throws Exception {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease =
                mock(DistributedInteractiveExecutionStore.Lease.class);
        when(store.acquire("fix", 1L, "request-123", false)).thenReturn(lease);
        InteractiveFixExecutionService instance = new InteractiveFixExecutionService(executor, store);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseSearch = new CountDownLatch(1);
        CompletableFuture<String> request = CompletableFuture.supplyAsync(() ->
                instance.execute(1L, "request-123", () -> {
                    started.countDown();
                    while (releaseSearch.getCount() > 0) {
                        try {
                            releaseSearch.await();
                        } catch (InterruptedException ignored) {
                            // Cancellation owns the outcome even when a checker does not honor interrupts.
                        }
                    }
                    return "cancelled fix";
                }));

        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(instance.cancel(1L, "request-123"));
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> request.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof ServiceUnavailableException);
            instance.markStage(1L, "request-123", InteractiveOperationStage.FINALIZING);
            verify(store, never()).update(lease, "RUNNING", InteractiveOperationStage.CANCELLING);
            releaseSearch.countDown();
            executor.submit(() -> {}).get(2, TimeUnit.SECONDS);

            verify(store, never()).completeSuccessfully(lease, InteractiveOperationStage.CANCELLING);
            verify(store).finish(lease, InteractiveOperationStage.CANCELLING);
        } finally {
            releaseSearch.countDown();
        }
    }

    @Test
    void lostDistributedOwnershipDoesNotDeliverACompletedFix() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        DistributedInteractiveExecutionStore.Lease lease =
                mock(DistributedInteractiveExecutionStore.Lease.class);
        when(store.acquire("fix", 1L, "request-123", false)).thenReturn(lease);
        ServiceUnavailableException ownershipLost =
                new ServiceUnavailableException("Distributed ownership was lost.");
        doThrow(ownershipLost).when(store)
                .completeSuccessfully(lease, InteractiveOperationStage.FINALIZING);
        InteractiveFixExecutionService instance = new InteractiveFixExecutionService(executor, store);

        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> instance.execute(1L, "request-123", () -> {
                    instance.markStage(1L, "request-123", InteractiveOperationStage.FINALIZING);
                    return "stale fix";
                }));

        assertEquals(ownershipLost, error);
        verify(store).finish(lease, InteractiveOperationStage.FINALIZING);
    }

    @Test
    void statusReportsTheServerObservedStageWhileRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseSearch = new CountDownLatch(1);
        CompletableFuture<Void> request = CompletableFuture.runAsync(() ->
                service.execute(1L, "request-123", () -> {
                    started.countDown();
                    releaseSearch.await();
                    return null;
                }));

        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            service.markStage(1L, "request-123", InteractiveOperationStage.SEARCHING_AND_VERIFYING);

            var status = service.getStatus(1L, "request-123");
            assertEquals("RUNNING", status.getState());
            assertEquals(InteractiveOperationStage.SEARCHING_AND_VERIFYING, status.getStage());
            assertTrue(status.getElapsedMs() >= 0);
        } finally {
            releaseSearch.countDown();
            request.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void completedStatusRemainsAvailableForTheFinalPollingTick() {
        service.execute(1L, "request-123", () -> {
            service.markStage(1L, "request-123", InteractiveOperationStage.FINALIZING);
            return null;
        });

        var status = service.getStatus(1L, "request-123");
        assertEquals("FINISHED", status.getState());
        assertEquals(InteractiveOperationStage.FINALIZING, status.getStage());
    }

    @Test
    void acceptsTheSameRequestIdCharactersAsTheControllerContract() {
        assertDoesNotThrow(() -> service.execute(1L, "request.v1:part", () -> null));
    }

    @Test
    void cancel_beforeStartRemovesQueuedWorkWithoutInvokingChecker() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

        AtomicBoolean checkerCalled = new AtomicBoolean(false);
        CompletableFuture<Void> request = CompletableFuture.runAsync(() -> {
            try {
                service.execute(1L, "request-123", () -> {
                    checkerCalled.set(true);
                    return null;
                });
            } catch (ServiceUnavailableException expected) {
                // Cancellation releases the waiting HTTP request.
            }
        });

        try {
            awaitQueuedTask();
            assertTrue(service.cancel(1L, "request-123"));
            request.get(2, TimeUnit.SECONDS);
            releaseWorker.countDown();
            assertDoesNotThrow(() -> service.execute(1L, "request-123", () -> null));
        } finally {
            releaseWorker.countDown();
        }
        assertFalse(checkerCalled.get());
    }

    @Test
    void statusAndCancellationCanBeServedByAnotherInstance() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        InteractiveFixExecutionService otherInstance = new InteractiveFixExecutionService(executor, store);
        var remoteStatus = cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStatusDto.builder()
                .requestId("request-123")
                .state("RUNNING")
                .stage(InteractiveOperationStage.SEARCHING_AND_VERIFYING)
                .elapsedMs(25)
                .build();
        when(store.getStatus("fix", 1L, "request-123"))
                .thenReturn(Optional.of(remoteStatus));
        when(store.requestCancellation("fix", 1L, "request-123"))
                .thenReturn(true);

        assertEquals(remoteStatus, otherInstance.getStatus(1L, "request-123"));
        assertTrue(otherInstance.cancel(1L, "request-123"));
    }

    @Test
    void distributedActiveStatusWinsOverStaleLocalCompletionForReusedRequestId() {
        DistributedInteractiveExecutionStore store = mock(DistributedInteractiveExecutionStore.class);
        InteractiveFixExecutionService instance = new InteractiveFixExecutionService(executor, store);
        instance.execute(1L, "request-123", () -> null);
        var currentStatus = cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStatusDto.builder()
                .requestId("request-123")
                .state("RUNNING")
                .stage(InteractiveOperationStage.SEARCHING_AND_VERIFYING)
                .elapsedMs(5)
                .build();
        when(store.getStatus("fix", 1L, "request-123"))
                .thenReturn(Optional.of(currentStatus));

        assertEquals(currentStatus, instance.getStatus(1L, "request-123"));
    }

    private void awaitQueuedTask() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (executor.getThreadPoolExecutor().getQueue().isEmpty()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for queued fix search");
            }
            Thread.yield();
        }
    }

    private void awaitBlockedOrTerminated(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.TERMINATED) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Stop did not reach the completion fence");
            }
            Thread.onSpinWait();
        }
    }

    private void awaitRequestIdRelease() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try {
                assertDoesNotThrow(() -> service.execute(1L, "request-123", () -> null));
                return;
            } catch (AssertionError error) {
                if (System.nanoTime() >= deadline) throw error;
                Thread.yield();
            }
        }
    }
}
