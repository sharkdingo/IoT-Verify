package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationOutcome;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.po.TaskView;
import cn.edu.nju.Iot_Verify.service.AsyncTaskExecutionControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractAsyncTaskServiceCancelTest {

    private static class TestTask implements TaskView {
        private final Long id;
        private final Long userId;
        private boolean cancelled;
        private boolean terminal;
        private Integer progress;
        private String checkLogsJson;
        private String workerId;
        private LocalDateTime leaseExpiresAt;

        private TestTask(Long id, Long userId) {
            this.id = id;
            this.userId = userId;
        }

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public Integer getProgress() {
            return progress;
        }

        @Override
        public boolean isTerminalStatus() {
            return terminal || cancelled;
        }

        @Override
        public boolean isCancelledStatus() {
            return cancelled;
        }

        @Override
        public String getTaskStatusName() {
            if (cancelled) return "CANCELLED";
            if (terminal) return "COMPLETED";
            return "PENDING";
        }

        @Override
        public String getCheckLogsJson() {
            return checkLogsJson;
        }

        @Override
        public void setCheckLogsJson(String json) {
            this.checkLogsJson = json;
        }

        @Override
        public String getWorkerId() {
            return workerId;
        }

        @Override
        public LocalDateTime getLeaseExpiresAt() {
            return leaseExpiresAt;
        }

        @Override
        public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) {
            this.leaseExpiresAt = leaseExpiresAt;
        }
    }

    static class TestAsyncTaskService extends AbstractAsyncTaskService<TestTask> {
        private final TestTask task = new TestTask(7L, 1L);
        private int cancelUpdateResult = 1;
        private boolean cancelUpdateThrows;
        private boolean setCancelledDuringAtomicCancel;
        private boolean markerVisibleDuringAtomicCancel;
        private int progressWriteCount;
        private LocalExecutionStopResult additionalStop = LocalExecutionStopResult.NONE;
        private int additionalStopRequests;
        private boolean rowLocked;

        TestAsyncTaskService() {
            super(new ObjectMapper(), "TestTask");
        }

        private TaskCancellationResultDto cancel(Long userId, Long taskId) {
            return cancelTask(userId, taskId);
        }

        private boolean hasCancelMarker(Long taskId) {
            return isTaskCancelled(taskId);
        }

        private void reportProgress(int progress) {
            updateTaskProgress(task.id, progress, TaskProgressStage.STARTING);
        }

        private int readProgress(Integer persisted, boolean terminal) {
            return resolveTaskProgress(task.id, persisted, terminal);
        }

        private String retainedDiagnosticOutput(String output) {
            return truncateOutput(output);
        }

        /** Mirrors a worker's finally block: record the cancellation, then clean up worker state. */
        private void runWorkerFinally() {
            handleCancellation(task);
            removeRunningTask(task.id);
            removeTaskProgress(task.id);
        }

        private void registerWorker(Thread thread) {
            registerRunningTask(task.id, thread);
        }

        private boolean cancelWouldStopAWorker() {
            return requestLocalExecutionStop(task.id);
        }

        @Override
        protected Optional<TestTask> findTaskByIdAndUserId(Long id, Long userId) {
            return task.id.equals(id) && task.userId.equals(userId)
                    ? Optional.of(task)
                    : Optional.empty();
        }

        @Override
        protected Optional<TestTask> findTaskByIdAndUserIdForUpdate(Long id, Long userId) {
            rowLocked = true;
            return findTaskByIdAndUserId(id, userId);
        }

        @Override
        protected int atomicCancelTask(Long taskId, LocalDateTime completedAt) {
            if (cancelUpdateThrows) {
                throw new IllegalStateException("simulated database failure");
            }
            markerVisibleDuringAtomicCancel = isTaskCancelled(taskId);
            if (setCancelledDuringAtomicCancel || cancelUpdateResult == 1) {
                task.cancelled = true;
                task.terminal = true;
            }
            return cancelUpdateResult;
        }

        @Override
        protected int atomicUpdateProgress(Long taskId, int progress, TaskProgressStage stage) {
            progressWriteCount++;
            task.progress = progress;
            return 1;
        }

        @Override
        protected LocalExecutionStopResult stopAdditionalLocalExecution(Long taskId) {
            additionalStopRequests++;
            return additionalStop;
        }
    }

    @Test
    void cancelTask_marksInMemoryAndConservativelyReportsUnknownRemoteExecution() {
        TestAsyncTaskService service = new TestAsyncTaskService();

        TaskCancellationResultDto result = service.cancel(1L, 7L);

        assertTrue(result.isCancellationAccepted());
        assertEquals(TaskCancellationOutcome.ACCEPTED, result.getCancellationOutcome());
        assertEquals("CANCELLED", result.getTaskStatus());
        assertTrue(result.isExecutionMayStillBeStopping());
        assertTrue(service.markerVisibleDuringAtomicCancel);
        assertTrue(service.rowLocked, "cancellation must read the committed status under a row lock");
        assertFalse(service.hasCancelMarker(7L), "marker should be cleared when no local worker owns the task");
    }

    @Test
    void cancelTask_stopsQueuedDomainExecutionWithoutReportingARunningWorker() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.additionalStop =
                AbstractAsyncTaskService.LocalExecutionStopResult.STOPPED_BEFORE_START;

        TaskCancellationResultDto result = service.cancel(1L, 7L);

        assertTrue(result.isCancellationAccepted());
        assertFalse(result.isExecutionMayStillBeStopping());
        assertEquals(1, service.additionalStopRequests);
        assertFalse(service.hasCancelMarker(7L));
    }

    @Test
    void requestLocalExecutionStop_delegatesToDomainQueuedExecutionHook() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.additionalStop =
                AbstractAsyncTaskService.LocalExecutionStopResult.STOPPED_BEFORE_START;

        assertTrue(service.requestLocalExecutionStop(7L));
        assertEquals(1, service.additionalStopRequests);
    }

    @Test
    void requestLocalExecutionStop_worksThroughTheCglibProxyUsedByTransactionalServices() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.additionalStop =
                AbstractAsyncTaskService.LocalExecutionStopResult.STOPPED_BEFORE_START;
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        AsyncTaskExecutionControl proxy = (AsyncTaskExecutionControl) proxyFactory.getProxy();

        assertTrue(proxy.requestLocalExecutionStop(7L));
        assertEquals(1, service.additionalStopRequests);
    }

    @Test
    void cancelTask_returnsTrueWhenWorkerAlreadyCancelledTask() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.cancelUpdateResult = 0;
        service.setCancelledDuringAtomicCancel = true;

        TaskCancellationResultDto result = service.cancel(1L, 7L);

        assertFalse(result.isCancellationAccepted());
        assertEquals(TaskCancellationOutcome.ALREADY_CANCELLED, result.getCancellationOutcome());
        assertTrue(service.markerVisibleDuringAtomicCancel);
        assertFalse(service.hasCancelMarker(7L), "terminal cancelled task should not leave a stale marker");
    }

    @Test
    void cancelTask_returnsFalseAndClearsMarkerWhenTaskAlreadyCompleted() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.cancelUpdateResult = 0;
        service.task.terminal = true;

        TaskCancellationResultDto result = service.cancel(1L, 7L);

        assertFalse(result.isCancellationAccepted());
        assertEquals(TaskCancellationOutcome.ALREADY_COMPLETED, result.getCancellationOutcome());
        assertTrue(service.markerVisibleDuringAtomicCancel);
        assertFalse(service.hasCancelMarker(7L), "completed task should not leave a stale cancel marker");
    }

    @Test
    void cancelTask_unknownOrForeignTask_isNotReportedAsGenericFalse() {
        TestAsyncTaskService service = new TestAsyncTaskService();

        assertThrows(ResourceNotFoundException.class, () -> service.cancel(2L, 7L));
    }

    @Test
    void updateTaskProgress_persistsOnlyWhenTheVisiblePercentageChanges() {
        TestAsyncTaskService service = new TestAsyncTaskService();

        service.reportProgress(12);
        service.reportProgress(12);
        service.reportProgress(13);

        assertEquals(2, service.progressWriteCount);
        assertEquals(13, service.task.progress);
    }

    @Test
    void progressResolutionKeepsLocalThenPersistedThenTerminalPrecedence() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        assertEquals(0, service.readProgress(null, false));
        assertEquals(100, service.readProgress(null, true));
        assertEquals(37, service.readProgress(37, true));
        service.reportProgress(12);
        assertEquals(12, service.readProgress(37, true));
    }

    @Test
    void diagnosticOutput_namesTheStorageAndDisplayLimitWhenTruncated() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        String boundaryOutput = "x".repeat(AbstractAsyncTaskService.MAX_OUTPUT_LENGTH);

        assertEquals(boundaryOutput, service.retainedDiagnosticOutput(boundaryOutput));
        assertEquals(boundaryOutput
                        + "\n... (diagnostic output truncated to 10000 characters for storage/display)",
                service.retainedDiagnosticOutput(boundaryOutput + "y"));
    }

    @Test
    void handleCancellation_databaseFailureStillLetsTheWorkerReleaseItsRegistration() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        Thread worker = Thread.currentThread();
        service.registerWorker(worker);
        service.reportProgress(10);
        service.cancelUpdateThrows = true;

        // A worker's finally block records the cancellation before releasing its own registration.
        // If recording throws, the cleanup must still run: otherwise this thread stays registered
        // against a finished task, and a later cancel interrupts whatever the pooled thread picked
        // up next.
        assertDoesNotThrow(service::runWorkerFinally);
        assertFalse(service.cancelWouldStopAWorker(),
                "a finished task must not still resolve to a live worker thread");
    }
}
