package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControlHolder;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStage;
import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStatusDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

/** Local request, worker and distributed lease lifecycle for interactive operations. */
abstract class InteractiveExecutionService {

    private static final Logger RECOMMENDATION_LOG = LoggerFactory.getLogger(InteractiveAiExecutionService.class);

    enum Kind {
        RECOMMENDATION("recommendation", "AI recommendation", "AI recommendation request",
                "AI recommendation capacity is currently full. Try again shortly."),
        FIX("fix", "Automatic-fix search", "automatic-fix request",
                "Automatic-fix capacity is currently full.");

        private final String leaseKind;
        private final String operationName;
        private final String resourceName;
        private final String capacityMessage;

        Kind(String leaseKind, String operationName, String resourceName, String capacityMessage) {
            this.leaseKind = leaseKind;
            this.operationName = operationName;
            this.resourceName = resourceName;
            this.capacityMessage = capacityMessage;
        }
    }

    private static final long COMPLETED_STATUS_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);

    private final Kind kind;
    private final ThreadPoolTaskExecutor executor;
    private final DistributedInteractiveExecutionStore distributedStore;
    private final Map<RequestKey, ActiveExecution<?>> active = new ConcurrentHashMap<>();
    private final Map<Long, String> activeByUser;
    private final Map<RequestKey, RecentStatus> recentlyCompleted = new ConcurrentHashMap<>();

    InteractiveExecutionService(Kind kind, ThreadPoolTaskExecutor executor,
                                DistributedInteractiveExecutionStore distributedStore) {
        this.kind = kind;
        this.executor = executor;
        this.distributedStore = Objects.requireNonNull(distributedStore, "distributedStore");
        this.activeByUser = kind == Kind.RECOMMENDATION ? new ConcurrentHashMap<>() : null;
    }

    public <T> T execute(Long userId, String requestId, Callable<T> operation) {
        purgeExpiredStatuses();
        String id = validateRequestId(requestId);
        RequestKey key = new RequestKey(userId, id);
        if (activeByUser != null && activeByUser.putIfAbsent(userId, id) != null) {
            throw busy(DistributedInteractiveExecutionStore.BusyScope.USER);
        }

        ActiveExecution<T> execution = new ActiveExecution<>(key, operation);
        if (active.putIfAbsent(key, execution) != null) {
            releaseUserAdmission(key);
            throw busy(DistributedInteractiveExecutionStore.BusyScope.REQUEST);
        }
        boolean dispatched = false;
        try {
            // A stop must find the waiting request even while Redis confirms its ownership.
            execution.registerDistributedLease(
                    distributedStore.acquire(kind.leaseKind, userId, id, activeByUser != null));
            executor.execute(execution.task);
            dispatched = true;
            return execution.task.get();
        } catch (DistributedInteractiveExecutionStore.BusyException e) {
            throw busy(e.getScope());
        } catch (RejectedExecutionException e) {
            execution.cancel();
            throw new ServiceUnavailableException(kind.capacityMessage, e);
        } catch (CancellationException e) {
            throw new ServiceUnavailableException(kind.operationName + " was cancelled.", e);
        } catch (InterruptedException e) {
            execution.cancel();
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException(kind.operationName + " was interrupted.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (kind == Kind.FIX) throw new ServiceUnavailableException("Automatic-fix search failed.", cause);
            RECOMMENDATION_LOG.error("AI recommendation worker failed: userId={}, requestId={}, stage={}",
                    userId, id, execution.stage.get(), cause);
            if (cause instanceof Error error) throw error;
            throw new InternalServerException("AI recommendation execution failed.", cause);
        } finally {
            if (!dispatched) execution.finishBeforeStart();
            if (executor.getThreadPoolExecutor() != null) executor.getThreadPoolExecutor().purge();
        }
    }

    public boolean cancel(Long userId, String requestId) {
        String id = validateRequestId(requestId);
        ActiveExecution<?> execution = active.get(new RequestKey(userId, id));
        boolean remotelySignalled = distributedStore.requestCancellation(kind.leaseKind, userId, id);
        boolean cancelled = execution != null && execution.cancel(false);
        if (kind == Kind.RECOMMENDATION) {
            RECOMMENDATION_LOG.info("AI recommendation cancellation: userId={}, requestId={}, cancelled={}",
                    userId, id, cancelled || remotelySignalled);
        }
        return cancelled || remotelySignalled;
    }

    public void requestUserExecutionStop(Long userId) {
        distributedStore.requestUserCancellation(kind.leaseKind, userId);
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
                && execution.distributedLease != null) return execution.status();
        var distributed = distributedStore.getStatus(kind.leaseKind, userId, id);
        if (distributed.isPresent()) return distributed.get();
        if (execution != null) return execution.status();
        RecentStatus recent = recentlyCompleted.get(key);
        if (recent != null) return recent.status();
        throw new ResourceNotFoundException(kind.resourceName, id);
    }

    public void markStage(Long userId, String requestId, InteractiveOperationStage stage) {
        ActiveExecution<?> execution = active.get(new RequestKey(userId, validateRequestId(requestId)));
        if (execution != null && !execution.task.isCancelled()
                && execution.state.get() != ExecutionState.FINISHED) {
            InteractiveOperationStage next = Objects.requireNonNull(stage);
            InteractiveOperationStage effective = execution.stage.updateAndGet(current ->
                    current == InteractiveOperationStage.CANCELLING ? current : next);
            distributedStore.update(execution.distributedLease, execution.state.get().name(), effective);
        }
    }

    public void pollDistributedCancellation() {
        active.values().forEach(ActiveExecution::pollDistributedCancellation);
    }

    private RuntimeException busy(DistributedInteractiveExecutionStore.BusyScope scope) {
        if (kind == Kind.FIX) return new BadRequestException("A fix request with this requestId is already active.");
        return new ServiceUnavailableException(scope == DistributedInteractiveExecutionStore.BusyScope.USER
                ? "Another AI recommendation is already running for this user. Stop it before starting a new one."
                : "An AI recommendation with this requestId is already running.");
    }

    private void releaseUserAdmission(RequestKey key) {
        if (activeByUser != null) activeByUser.remove(key.userId(), key.requestId());
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

    /**
     * FutureTask can report cancellation before an interrupt-ignoring callable exits. Keep the
     * request registered until it returns so a retry cannot reuse its admission while it is running.
     */
    private final class ActiveExecution<T> {
        private final RequestKey key;
        private final AtomicReference<ExecutionState> state =
                new AtomicReference<>(ExecutionState.WAITING);
        private final AtomicBoolean cleaned = new AtomicBoolean(false);
        private final AtomicBoolean distributedCompletionPublished = new AtomicBoolean(false);
        private final AtomicReference<InteractiveOperationStage> stage =
                new AtomicReference<>(InteractiveOperationStage.QUEUED);
        private final LlmRequestControl requestControl = kind == Kind.RECOMMENDATION ? new LlmRequestControl() : null;
        private volatile DistributedInteractiveExecutionStore.Lease distributedLease;
        private final long createdAtNanos = System.nanoTime();
        private final FutureTask<T> task;

        private ActiveExecution(RequestKey key, Callable<T> operation) {
            this.key = key;
            this.task = new FutureTask<>(() -> {
                if (!state.compareAndSet(ExecutionState.WAITING, ExecutionState.RUNNING)) {
                    throw new CancellationException(kind.operationName + " was cancelled before it started.");
                }
                stage.compareAndSet(InteractiveOperationStage.QUEUED, InteractiveOperationStage.RUNNING);
                distributedStore.update(distributedLease, ExecutionState.RUNNING.name(), stage.get());
                if (requestControl != null) LlmRequestControlHolder.set(requestControl);
                try {
                    // Status publication can outlast the interrupt from an accepted stop.
                    ensureNotCancelled("before its operation started");
                    T result = operation.call();
                    completeSuccessfully();
                    return result;
                } finally {
                    if (requestControl != null) LlmRequestControlHolder.clear();
                    state.set(ExecutionState.FINISHED);
                    cleanup();
                }
            });
        }

        private synchronized void registerDistributedLease(DistributedInteractiveExecutionStore.Lease lease) {
            if (state.get() == ExecutionState.FINISHED || task.isCancelled()) {
                distributedStore.abandon(lease);
                throw new CancellationException(kind.operationName + " was cancelled during registration.");
            }
            distributedLease = lease;
        }

        private void finishBeforeStart() {
            if (state.compareAndSet(ExecutionState.WAITING, ExecutionState.FINISHED)) {
                task.cancel(false);
                cleanup();
            }
        }

        // A successful completion releases Redis ownership; that release is not a lost lease.
        private synchronized void completeSuccessfully() {
            ensureNotCancelled("before completion");
            distributedStore.completeSuccessfully(distributedLease, stage.get());
            distributedCompletionPublished.set(true);
            state.set(ExecutionState.FINISHED);
        }

        private void ensureNotCancelled(String point) {
            if (task.isCancelled()) {
                throw new CancellationException(kind.operationName + " was cancelled " + point + ".");
            }
        }

        private synchronized void pollDistributedCancellation() {
            if (state.get() != ExecutionState.FINISHED
                    && distributedStore.shouldStop(distributedLease)) cancel(false);
        }

        private boolean cancel() {
            return cancel(true);
        }

        private synchronized boolean cancel(boolean publishDistributed) {
            if (state.get() == ExecutionState.FINISHED) return false;
            stage.set(InteractiveOperationStage.CANCELLING);
            if (requestControl != null) requestControl.cancel();
            if (publishDistributed) {
                distributedStore.requestCancellation(kind.leaseKind, key.userId(), key.requestId());
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
            if (!distributedCompletionPublished.get()) {
                distributedStore.finish(distributedLease, stage.get());
            }
            active.remove(key, this);
            releaseUserAdmission(key);
        }
    }
}
