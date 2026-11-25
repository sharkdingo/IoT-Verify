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
        private int progressWriteCount;
        private boolean progressWriteThrows;
        private int progressVisibleDuringWrite;
        private LocalExecutionStopResult localStop = LocalExecutionStopResult.NONE;
        private int localStopRequests;
        private boolean rowLocked;

        TestAsyncTaskService() {
            super(new ObjectMapper(), "TestTask");
        }

        private TaskCancellationResultDto cancel(Long userId, Long taskId) {
            return cancelTask(userId, taskId);
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
            if (cancelUpdateResult == 1) {
                task.cancelled = true;
                task.terminal = true;
            }
            return cancelUpdateResult;
        }

        @Override
        protected int atomicUpdateProgress(Long taskId, int progress, TaskProgressStage stage) {
            progressWriteCount++;
            progressVisibleDuringWrite = resolveTaskProgress(taskId, task.progress, false);
            if (progressWriteThrows) {
                throw new IllegalStateException("simulated progress write failure");
            }
            task.progress = progress;
            return 1;
        }

        @Override
        protected LocalExecutionStopResult stopLocalExecution(Long taskId) {
            localStopRequests++;
            return localStop;
        }

        @Override
        protected boolean isLocalStopRequested(Long taskId) {
            return localStopRequests > 0 && localStop != LocalExecutionStopResult.NONE;
        }
    }

    @Test
    void cancelTask_conservativelyReportsUnknownRemoteExecution() {
        TestAsyncTaskService service = new TestAsyncTaskService();

        TaskCancellationResultDto result = service.cancel(1L, 7L);

        assertTrue(result.isCancellationAccepted());
        assertEquals(TaskCancellationOutcome.ACCEPTED, result.getCancellationOutcome());
        assertEquals("CANCELLED", result.getTaskStatus());
        assertTrue(result.isExecutionMayStillBeStopping());
        assertTrue(service.rowLocked, "cancellation must read the committed status under a row lock");
    }

    @Test
    void cancelTask_stopsQueuedDomainExecutionWithoutReportingARunningWorker() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.localStop =
                AbstractAsyncTaskService.LocalExecutionStopResult.STOPPED_BEFORE_START;

        TaskCancellationResultDto result = service.cancel(1L, 7L);

        assertTrue(result.isCancellationAccepted());
        assertFalse(result.isExecutionMayStillBeStopping());
        assertEquals(1, service.localStopRequests);
    }

    @Test
    void requestLocalExecutionStop_delegatesToDomainQueuedExecutionHook() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.localStop =
                AbstractAsyncTaskService.LocalExecutionStopResult.STOPPED_BEFORE_START;

        assertTrue(service.requestLocalExecutionStop(7L));
        assertEquals(1, service.localStopRequests);
    }

    @Test
    void requestLocalExecutionStop_worksThroughTheCglibProxyUsedByTransactionalServices() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.localStop =
                AbstractAsyncTaskService.LocalExecutionStopResult.STOPPED_BEFORE_START;
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        AsyncTaskExecutionControl proxy = (AsyncTaskExecutionControl) proxyFactory.getProxy();

        assertTrue(proxy.requestLocalExecutionStop(7L));
        assertEquals(1, service.localStopRequests);
    }

    @Test
    void cancelTask_reportsAlreadyCancelledAndChecksForLocalExecution() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.cancelUpdateResult = 0;
        service.task.cancelled = true;

        TaskCancellationResultDto result = service.cancel(1L, 7L);

        assertFalse(result.isCancellationAccepted());
        assertEquals(TaskCancellationOutcome.ALREADY_CANCELLED, result.getCancellationOutcome());
        assertEquals(1, service.localStopRequests);
        assertFalse(result.isExecutionMayStillBeStopping());
    }

    @Test
    void cancelTask_leavesCompletedExecutionAlone() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.cancelUpdateResult = 0;
        service.task.terminal = true;

        TaskCancellationResultDto result = service.cancel(1L, 7L);

        assertFalse(result.isCancellationAccepted());
        assertEquals(TaskCancellationOutcome.ALREADY_COMPLETED, result.getCancellationOutcome());
        assertEquals(0, service.localStopRequests);
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
    void failedProgressWriteKeepsLastCommittedValueAndCanBeRetried() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.reportProgress(12);
        service.progressWriteThrows = true;

        assertThrows(IllegalStateException.class, () -> service.reportProgress(13));
        assertEquals(12, service.progressVisibleDuringWrite);
        assertEquals(12, service.readProgress(null, false));

        service.progressWriteThrows = false;
        service.reportProgress(13);

        assertEquals(13, service.task.progress);
        assertEquals(13, service.readProgress(null, false));
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
    void cancelTask_propagatesDatabaseFailureWithoutStoppingLocalExecution() {
        TestAsyncTaskService service = new TestAsyncTaskService();
        service.cancelUpdateThrows = true;

        assertThrows(IllegalStateException.class, () -> service.cancel(1L, 7L));
        assertEquals(0, service.localStopRequests);
    }
}
