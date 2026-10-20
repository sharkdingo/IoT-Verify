package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStage;
import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStatusDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Request-scoped cancellation for bounded automatic-fix searches. */
@Service
public class InteractiveFixExecutionService {

    private static final long COMPLETED_STATUS_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);

    private final ThreadPoolTaskExecutor executor;
    private final DistributedInteractiveExecutionStore distributedStore;
    private final Map<RequestKey, ActiveExecution<?>> active = new ConcurrentHashMap<>();
    private final Map<RequestKey, RecentStatus> recentlyCompleted = new ConcurrentHashMap<>();

    @Autowired
    public InteractiveFixExecutionService(
            @Qualifier("syncVerificationExecutor") ThreadPoolTaskExecutor executor,
            DistributedInteractiveExecutionStore distributedStore) {
        this.executor = executor;
        this.distributedStore = distributedStore;
    }

    InteractiveFixExecutionService(ThreadPoolTaskExecutor executor) {
        this(executor, null);
    }

    public <T> T execute(Long userId, String requestId, Callable<T> operation) {
        purgeExpiredStatuses();
        String id = validateRequestId(requestId);
        RequestKey key = new RequestKey(userId, id);
        ActiveExecution<T> execution = new ActiveExecution<>(key, operation);
        if (active.putIfAbsent(key, execution) != null) {
            throw new BadRequestException("A fix request with this requestId is already active.");
        }
        boolean dispatched = false;
        try {
            // Registration is observable as WAITING while Redis confirms ownership. The checker
            // cannot run until confirmation, and a stop in this window must release a late lease.
            execution.registerDistributedLease(distributedStore == null ? null
                    : distributedStore.acquire("fix", userId, id, false));
            executor.execute(execution.task);
            dispatched = true;
            return execution.task.get();
        } catch (DistributedInteractiveExecutionStore.BusyException e) {
            throw new BadRequestException("A fix request with this requestId is already active.");
        } catch (RejectedExecutionException e) {
            execution.cancel();
            throw new ServiceUnavailableException("Automatic-fix capacity is currently full.", e);
        } catch (CancellationException e) {
            throw new ServiceUnavailableException("Automatic-fix search was cancelled.", e);
        } catch (InterruptedException e) {
            execution.cancel();
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Automatic-fix search was interrupted.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new ServiceUnavailableException("Automatic-fix search failed.", cause);
        } finally {
            if (!dispatched) execution.finishBeforeStart();
            if (executor.getThreadPoolExecutor() != null) executor.getThreadPoolExecutor().purge();
        }
    }

    public boolean cancel(Long userId, String requestId) {
        String id = validateRequestId(requestId);
        ActiveExecution<?> execution = active.get(new RequestKey(userId, id));
        boolean remotelySignalled = distributedStore != null
                && distributedStore.requestCancellation("fix", userId, id);
        boolean cancelled = execution != null && execution.cancel(false);
        return cancelled || remotelySignalled;
    }

    public void requestUserExecutionStop(Long userId) {
        if (distributedStore != null) {
            distributedStore.requestUserCancellation("fix", userId);
        }
        active.forEach((key, execution) -> {
            if (Objects.equals(key.userId(), userId)) execution.cancel(false);
        });
    }

    public InteractiveOperationStatusDto getStatus(Long userId, String requestId) {
        purgeExpiredStatuses();
        String id = validateRequestId(requestId);
        RequestKey key = new RequestKey(userId, id);
        ActiveExecution<?> execution = active.get(key);
        if (execution != null && !execution.usesDistributedLease()
                && (distributedStore == null || execution.distributedLease != null)) return execution.status();
        if (distributedStore != null) {
            var distributed = distributedStore.getStatus("fix", userId, id);
            if (distributed.isPresent()) return distributed.get();
        }
        if (execution != null) return execution.status();
        RecentStatus recent = recentlyCompleted.get(key);
        if (recent != null) return recent.status();
        throw new ResourceNotFoundException("automatic-fix request", id);
    }

    public void markStage(Long userId, String requestId, InteractiveOperationStage stage) {
        ActiveExecution<?> execution = active.get(new RequestKey(userId, validateRequestId(requestId)));
        if (execution != null && !execution.task.isCancelled()
                && execution.state.get() != ExecutionState.FINISHED) {
            InteractiveOperationStage next = Objects.requireNonNull(stage);
            InteractiveOperationStage effective = execution.stage.updateAndGet(current ->
                    current == InteractiveOperationStage.CANCELLING ? current : next);
            if (distributedStore != null) {
                distributedStore.update(execution.distributedLease, execution.state.get().name(), effective);
            }
        }
    }

    @Scheduled(fixedDelay = 1000L)
    public void pollDistributedCancellation() {
        if (distributedStore == null) return;
        active.values().forEach(ActiveExecution::pollDistributedCancellation);
    }

    private String validateRequestId(String requestId) {
        String value = requestId == null ? "" : requestId.trim();
        if (value.length() < RequestLimits.MIN_REQUEST_ID_LENGTH
                || value.length() > RequestLimits.MAX_REQUEST_ID_LENGTH
                || !value.matches(RequestLimits.REQUEST_ID_PATTERN)) {
            throw new BadRequestException("requestId must contain 8-80 characters and use only letters, digits, ., _, :, or -.");
        }
        return value;
    }

    private record RequestKey(Long userId, String requestId) {
    }

    private record RecentStatus(InteractiveOperationStatusDto status, long expiresAtNanos) {
    }

    private void purgeExpiredStatuses() {
        long now = System.nanoTime();
        recentlyCompleted.forEach((key, recent) -> {
            if (recent.expiresAtNanos() <= now) recentlyCompleted.remove(key, recent);
        });
    }

    private enum ExecutionState {
        WAITING,
        RUNNING,
        FINISHED
    }

    private final class ActiveExecution<T> {
        private final RequestKey key;
        private final AtomicReference<ExecutionState> state =
                new AtomicReference<>(ExecutionState.WAITING);
        private final AtomicBoolean cleaned = new AtomicBoolean(false);
        private final AtomicBoolean distributedCompletionPublished = new AtomicBoolean(false);
        private final AtomicReference<InteractiveOperationStage> stage =
                new AtomicReference<>(InteractiveOperationStage.QUEUED);
        private volatile DistributedInteractiveExecutionStore.Lease distributedLease;
        private final long createdAtNanos = System.nanoTime();
        private final FutureTask<T> task;

        private ActiveExecution(RequestKey key, Callable<T> operation) {
            this.key = key;
            this.task = new FutureTask<>(() -> {
                if (!state.compareAndSet(ExecutionState.WAITING, ExecutionState.RUNNING)) {
                    throw new CancellationException("Automatic-fix search was cancelled before it started.");
                }
                stage.compareAndSet(InteractiveOperationStage.QUEUED, InteractiveOperationStage.RUNNING);
                if (distributedStore != null) {
                    distributedStore.update(distributedLease, ExecutionState.RUNNING.name(), stage.get());
                }
                try {
                    T result = operation.call();
                    completeSuccessfully();
                    return result;
                } finally {
                    state.set(ExecutionState.FINISHED);
                    cleanup();
                }
            });
        }

        private synchronized void registerDistributedLease(DistributedInteractiveExecutionStore.Lease lease) {
            if (state.get() == ExecutionState.FINISHED || task.isCancelled()) {
                if (distributedStore != null) distributedStore.abandon(lease);
                throw new CancellationException("Automatic-fix search was cancelled during registration.");
            }
            distributedLease = lease;
        }

        private void finishBeforeStart() {
            if (state.compareAndSet(ExecutionState.WAITING, ExecutionState.FINISHED)) {
                task.cancel(false);
                cleanup();
            }
        }

        private boolean cancel() {
            return cancel(true);
        }

        // Polling and local cancellation must not mistake a successful fence's released owner for lease loss.
        private synchronized void completeSuccessfully() {
            if (task.isCancelled()) {
                throw new CancellationException("Automatic-fix search was cancelled before completion.");
            }
            if (distributedStore != null) {
                distributedStore.completeSuccessfully(distributedLease, stage.get());
                distributedCompletionPublished.set(true);
            }
            state.set(ExecutionState.FINISHED);
        }

        private synchronized void pollDistributedCancellation() {
            if (state.get() != ExecutionState.FINISHED
                    && distributedStore.shouldStop(distributedLease)) cancel(false);
        }

        private synchronized boolean cancel(boolean publishDistributed) {
            if (state.get() == ExecutionState.FINISHED) return false;
            stage.set(InteractiveOperationStage.CANCELLING);
            if (publishDistributed && distributedStore != null) {
                distributedStore.requestCancellation("fix", key.userId(), key.requestId());
            }
            boolean cancelled = task.cancel(true);
            if (cancelled && state.compareAndSet(ExecutionState.WAITING, ExecutionState.FINISHED)) {
                cleanup();
            }
            return cancelled;
        }

        private InteractiveOperationStatusDto status() {
            return InteractiveOperationStatusDto.builder()
                    .requestId(key.requestId())
                    .state(state.get().name())
                    .stage(stage.get())
                    .elapsedMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdAtNanos))
                    .build();
        }

        private boolean usesDistributedLease() {
            return distributedLease != null && distributedLease.isRedisBacked();
        }

        private void cleanup() {
            if (!cleaned.compareAndSet(false, true)) return;
            recentlyCompleted.put(key, new RecentStatus(
                    status(), System.nanoTime() + COMPLETED_STATUS_TTL_NANOS));
            if (distributedStore != null && !distributedCompletionPublished.get()) {
                distributedStore.finish(distributedLease, stage.get());
            }
            active.remove(key, this);
        }
    }
}
