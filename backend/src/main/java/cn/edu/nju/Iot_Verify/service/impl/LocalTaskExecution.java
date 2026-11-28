package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.service.impl.AbstractAsyncTaskService.LocalExecutionStopResult;
import org.slf4j.Logger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns the queue-to-worker transition and stop signal for one local task. */
final class LocalTaskExecution {

    private enum State { QUEUED, RUNNING, CANCELLED_BEFORE_START, FINISHED }

    private final Long taskId;
    private final Runnable purgeCancelled;
    private final Consumer<LocalTaskExecution> retire;
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private final LeaseConfirmation leaseConfirmation = new LeaseConfirmation();
    private final FutureTask<Void> futureTask;

    LocalTaskExecution(Long taskId, Runnable work, Runnable purgeCancelled, Consumer<LocalTaskExecution> retire) {
        this.taskId = taskId;
        this.purgeCancelled = purgeCancelled;
        this.retire = retire;
        this.futureTask = new FutureTask<>(() -> run(work), null);
    }

    private void run(Runnable work) {
        if (!state.compareAndSet(State.QUEUED, State.RUNNING)) {
            return;
        }
        try {
            work.run();
        } finally {
            state.set(State.FINISHED);
            retire.accept(this);
        }
    }

    LocalExecutionStopResult requestStop() {
        while (true) {
            State current = state.get();
            if (current == State.QUEUED) {
                if (!state.compareAndSet(State.QUEUED, State.CANCELLED_BEFORE_START)) {
                    continue;
                }
                futureTask.cancel(false);
                purgeCancelled.run();
                retire.accept(this);
                return LocalExecutionStopResult.STOPPED_BEFORE_START;
            }
            if (current == State.RUNNING) {
                futureTask.cancel(true);
                return LocalExecutionStopResult.STOP_REQUESTED;
            }
            return LocalExecutionStopResult.NONE;
        }
    }

    boolean isQueued() {
        return state.get() == State.QUEUED;
    }

    boolean isCancelled() {
        return futureTask.isCancelled();
    }

    FutureTask<Void> futureTask() {
        return futureTask;
    }

    void dispatchIfPending(Executor executor, BooleanSupplier taskPending) {
        try {
            if (!taskPending.getAsBoolean() || isCancelled()) {
                requestStop();
                return;
            }
            executor.execute(futureTask);
            purgeIfCancelledAfterDispatch();
        } catch (RuntimeException e) {
            requestStop();
            throw e;
        }
    }

    static void purgeCancelledTasks(ThreadPoolTaskExecutor executor, Logger logger, String taskKind) {
        try {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            if (pool != null) pool.purge();
        } catch (RuntimeException e) {
            logger.warn("Could not purge cancelled {} tasks from the local executor queue", taskKind, e);
        }
    }

    void purgeIfCancelledAfterDispatch() {
        // Cancellation can purge the queue before the executor has actually added this Future.
        if (isCancelled()) purgeCancelled.run();
    }

    void confirmLeaseAt(long nanos) {
        leaseConfirmation.confirmAt(nanos);
    }

    TaskLeaseMaintenance.LocalLease lease() {
        return new TaskLeaseMaintenance.LocalLease(taskId, leaseConfirmation, this::requestStop);
    }
}
