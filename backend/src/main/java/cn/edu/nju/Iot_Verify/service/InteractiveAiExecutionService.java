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
import lombok.extern.slf4j.Slf4j;
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

/** Bounded, user-cancellable execution for synchronous AI recommendation endpoints. */
@Slf4j
@Service
public class InteractiveAiExecutionService {

    private static final long COMPLETED_STATUS_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);

    private final ThreadPoolTaskExecutor executor;
    private final DistributedInteractiveExecutionStore distributedStore;
    private final Map<RequestKey, ActiveExecution<?>> active = new ConcurrentHashMap<>();
    private final Map<Long, String> activeByUser = new ConcurrentHashMap<>();
    private final Map<RequestKey, RecentStatus> recentlyCompleted = new ConcurrentHashMap<>();

    @Autowired
    public InteractiveAiExecutionService(
            @Qualifier("interactiveAiExecutor") ThreadPoolTaskExecutor executor,
            DistributedInteractiveExecutionStore distributedStore) {
        this.executor = executor;
        this.distributedStore = distributedStore;
    }

    InteractiveAiExecutionService(ThreadPoolTaskExecutor executor) {
        this(executor, null);
    }

    public <T> T execute(Long userId, String requestId, Callable<T> operation) {
        purgeExpiredStatuses();
        String id = validateRequestId(requestId);
        RequestKey key = new RequestKey(userId, id);
        String existing = activeByUser.putIfAbsent(userId, id);
        if (existing != null) {
            throw new ServiceUnavailableException(
                    "Another AI recommendation is already running for this user. Stop it before starting a new one.");
        }

        DistributedInteractiveExecutionStore.Lease distributedLease;
        try {
            distributedLease = distributedStore == null ? null
                    : distributedStore.acquire("recommendation", userId, id, true);
        } catch (DistributedInteractiveExecutionStore.BusyException e) {
            activeByUser.remove(userId, id);
            String message = e.getScope() == DistributedInteractiveExecutionStore.BusyScope.USER
                    ? "Another AI recommendation is already running for this user. Stop it before starting a new one."
                    : "An AI recommendation with this requestId is already running.";
            throw new ServiceUnavailableException(message);
        } catch (RuntimeException | Error e) {
            activeByUser.remove(userId, id);
            throw e;
        }

        ActiveExecution<T> execution = new ActiveExecution<>(key, operation, distributedLease);
        if (active.putIfAbsent(key, execution) != null) {
            activeByUser.remove(userId, id);
            if (distributedStore != null) distributedStore.abandon(distributedLease);
            throw new ServiceUnavailableException(
                    "An AI recommendation with this requestId is already running.");
        }
        try {
            executor.execute(execution.task);
            return execution.task.get();
        } catch (RejectedExecutionException e) {
            execution.cancel();
            throw new ServiceUnavailableException(
                    "AI recommendation capacity is currently full. Try again shortly.", e);
        } catch (CancellationException e) {
            throw new ServiceUnavailableException("AI recommendation was cancelled.", e);
        } catch (InterruptedException e) {
            execution.cancel();
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("AI recommendation was interrupted.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            log.error("AI recommendation worker failed: userId={}, requestId={}, stage={}",
                    userId, id, execution.stage.get(), cause);
            if (cause instanceof Error error) throw error;
            throw new InternalServerException("AI recommendation execution failed.", cause);
        } finally {
            if (executor.getThreadPoolExecutor() != null) executor.getThreadPoolExecutor().purge();
        }
    }

    public boolean cancel(Long userId, String requestId) {
        String id = validateRequestId(requestId);
        ActiveExecution<?> execution = active.get(new RequestKey(userId, id));
        boolean remotelySignalled = distributedStore != null
                && distributedStore.requestCancellation("recommendation", userId, id);
        boolean cancelled = execution != null && execution.cancel(false);
        log.info("AI recommendation cancellation: userId={}, requestId={}, cancelled={}",
                userId, id, cancelled || remotelySignalled);
        return cancelled || remotelySignalled;
    }

    public void requestUserExecutionStop(Long userId) {
        if (distributedStore != null) {
            distributedStore.requestUserCancellation("recommendation", userId);
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
        if (execution != null && !execution.usesDistributedLease()) return execution.status();
        if (distributedStore != null) {
            var distributed = distributedStore.getStatus("recommendation", userId, id);
            if (distributed.isPresent()) return distributed.get();
        }
        if (execution != null) return execution.status();
        RecentStatus recent = recentlyCompleted.get(key);
        if (recent != null) return recent.status();
        throw new ResourceNotFoundException("AI recommendation request", id);
    }

    public void markStage(Long userId, String requestId, InteractiveOperationStage stage) {
        ActiveExecution<?> execution = active.get(new RequestKey(userId, validateRequestId(requestId)));
        if (execution != null) {
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
        active.values().forEach(execution -> {
            if (distributedStore.shouldStop(execution.distributedLease)) execution.cancel(false);
        });
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
     * FutureTask reports cancellation before an interrupt-ignoring provider call has returned.
     * Keep the request registered until the callable actually exits so per-user admission remains
     * truthful and repeated stop/retry actions cannot stack hidden model work.
     */
    private final class ActiveExecution<T> {
        private final RequestKey key;
        private final AtomicReference<ExecutionState> state =
                new AtomicReference<>(ExecutionState.WAITING);
        private final AtomicBoolean cleaned = new AtomicBoolean(false);
        private final AtomicBoolean distributedCompletionPublished = new AtomicBoolean(false);
        private final AtomicReference<InteractiveOperationStage> stage =
                new AtomicReference<>(InteractiveOperationStage.QUEUED);
        private final LlmRequestControl requestControl = new LlmRequestControl();
        private final DistributedInteractiveExecutionStore.Lease distributedLease;
        private final long createdAtNanos = System.nanoTime();
        private final FutureTask<T> task;

        private ActiveExecution(RequestKey key, Callable<T> operation,
                                DistributedInteractiveExecutionStore.Lease distributedLease) {
            this.key = key;
            this.distributedLease = distributedLease;
            this.task = new FutureTask<>(() -> {
                if (!state.compareAndSet(ExecutionState.WAITING, ExecutionState.RUNNING)) {
                    throw new CancellationException("AI recommendation was cancelled before it started.");
                }
                stage.compareAndSet(InteractiveOperationStage.QUEUED, InteractiveOperationStage.RUNNING);
                if (distributedStore != null) {
                    distributedStore.update(distributedLease, ExecutionState.RUNNING.name(), stage.get());
                }
                LlmRequestControlHolder.set(requestControl);
                try {
                    T result = operation.call();
                    if (distributedStore != null) {
                        distributedStore.completeSuccessfully(distributedLease, stage.get());
                        distributedCompletionPublished.set(true);
                    }
                    return result;
                } finally {
                    LlmRequestControlHolder.clear();
                    state.set(ExecutionState.FINISHED);
                    cleanup();
                }
            });
        }

        private boolean cancel() {
            return cancel(true);
        }

        private boolean cancel(boolean publishDistributed) {
            stage.set(InteractiveOperationStage.CANCELLING);
            requestControl.cancel();
            if (publishDistributed && distributedStore != null) {
                distributedStore.requestCancellation("recommendation", key.userId(), key.requestId());
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
            activeByUser.remove(key.userId(), key.requestId());
        }
    }
}
