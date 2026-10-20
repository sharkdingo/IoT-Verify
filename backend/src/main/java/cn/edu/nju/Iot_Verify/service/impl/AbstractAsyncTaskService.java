package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationOutcome;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.po.TaskView;
import cn.edu.nju.Iot_Verify.service.AsyncTaskExecutionControl;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步任务管理基类。
 *
 * <p>提取 VerificationServiceImpl 与 SimulationServiceImpl 中完全相同的：
 * <ul>
 *   <li>3 个并发容器（runningTasks / taskProgress / cancelledTasks）+ 语义操作方法</li>
 *   <li>serializeCheckLogs / readCheckLogs / writeCheckLogs / truncateOutput</li>
 *   <li>updateTaskProgress / resolveTaskProgress / cancelTask / handleCancellation</li>
 * </ul>
 *
 * <p>子类通过实现 Repository 委托钩子来适配具体实体类型。
 *
 * @param <T> 任务 PO 类型，必须实现 {@link TaskView}
 */
@Slf4j
public abstract class AbstractAsyncTaskService<T extends TaskView>
        implements AsyncTaskExecutionControl {

    protected static final int MAX_OUTPUT_LENGTH = 10_000;
    /** MySQL {@code TEXT} holds 65,535 bytes; the rest is headroom for the row itself. */
    static final int MAX_SMV_MODEL_BYTES = 65_000;
    private static final String OUTPUT_TRUNCATION_MARKER =
            "\n... (diagnostic output truncated to 10000 characters for storage/display)";
    private static final String DIAGNOSTIC_LOSS_MESSAGE =
            "[diagnostic-loss] Task diagnostic logs could not be serialized or parsed.";
    private static final String DIAGNOSTIC_LOSS_JSON =
            "[\"[diagnostic-loss] Task diagnostic logs could not be serialized or parsed.\"]";
    protected final ObjectMapper objectMapper;

    // ── 并发容器（private，通过语义操作方法暴露）──────────────────────

    private final ConcurrentHashMap<Long, Thread> runningTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TaskProgressState> taskProgress = new ConcurrentHashMap<>();
    private final Set<Long> cancelledTasks = ConcurrentHashMap.newKeySet();

    protected AbstractAsyncTaskService(ObjectMapper objectMapper,
                                       String taskResourceType) {
        this.objectMapper = objectMapper;
        this.modelFingerprint = new ModelFingerprint(objectMapper);
        this.taskResourceType = taskResourceType;
    }

    protected final ModelFingerprint modelFingerprint;

    /** 用于 ResourceNotFoundException 和日志输出的资源类型名称 */
    private final String taskResourceType;

    // ── 容器语义操作方法（protected，子类异步编排使用）──────────────────

    protected void registerRunningTask(Long taskId, Thread thread) {
        runningTasks.put(taskId, thread);
    }

    protected boolean isTaskCancelled(Long taskId) {
        return cancelledTasks.contains(taskId);
    }

    /**
     * 从取消标记集合中移除任务。
     * @return true 如果任务确实在已取消集合中（即需要执行取消收尾）
     */
    protected boolean removeCancelledMark(Long taskId) {
        return cancelledTasks.remove(taskId);
    }

    protected void removeRunningTask(Long taskId) {
        runningTasks.remove(taskId);
    }

    protected void removeTaskProgress(Long taskId) {
        taskProgress.remove(taskId);
    }

    protected enum LocalExecutionStopResult {
        NONE(false, false),
        STOPPED_BEFORE_START(true, false),
        STOP_REQUESTED(true, true);

        private final boolean requested;
        private final boolean mayStillBeStopping;

        LocalExecutionStopResult(boolean requested, boolean mayStillBeStopping) {
            this.requested = requested;
            this.mayStillBeStopping = mayStillBeStopping;
        }
    }

    @Override
    public boolean requestLocalExecutionStop(Long taskId) {
        if (taskId == null) return false;
        return stopLocalExecution(taskId).requested;
    }

    private LocalExecutionStopResult stopLocalExecution(Long taskId) {
        LocalExecutionStopResult additionalStop = Objects.requireNonNull(
                stopAdditionalLocalExecution(taskId),
                "stopAdditionalLocalExecution must not return null");
        Thread taskThread = runningTasks.get(taskId);
        if (taskThread != null && taskThread.isAlive()) {
            taskThread.interrupt();
            return LocalExecutionStopResult.STOP_REQUESTED;
        }
        return additionalStop;
    }

    /** Lets a domain service stop work that is accepted locally but has no runner thread yet. */
    protected LocalExecutionStopResult stopAdditionalLocalExecution(Long taskId) {
        return LocalExecutionStopResult.NONE;
    }

    // ── 直接搬入的具体方法（无领域差异）─────────────────────────────

    protected String serializeCheckLogs(List<String> logs) {
        try {
            return objectMapper.writeValueAsString(logs == null ? List.of() : logs);
        } catch (Exception e) {
            log.warn("Failed to serialize check logs", e);
            return DIAGNOSTIC_LOSS_JSON;
        }
    }

    protected List<String> readCheckLogs(T task) {
        if (task == null || task.getCheckLogsJson() == null || task.getCheckLogsJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(task.getCheckLogsJson(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse checkLogsJson for {} {}", taskResourceType, task.getId(), e);
            return new ArrayList<>(List.of(DIAGNOSTIC_LOSS_MESSAGE));
        }
    }

    protected void writeCheckLogs(T task, List<String> logs) {
        if (task == null) return;
        try {
            task.setCheckLogsJson(objectMapper.writeValueAsString(logs == null ? List.of() : logs));
        } catch (Exception e) {
            log.warn("Failed to serialize check logs for {} {}", taskResourceType, task.getId(), e);
            task.setCheckLogsJson(DIAGNOSTIC_LOSS_JSON);
        }
    }

    protected String truncateOutput(String output) {
        if (output == null) return null;
        return output.length() > MAX_OUTPUT_LENGTH
                ? output.substring(0, MAX_OUTPUT_LENGTH) + OUTPUT_TRUNCATION_MARKER : output;
    }

    /**
     * Read the generated SMV model so the run can persist the exact model it checked.
     *
     * <p>Bounded in <em>bytes</em>, not characters: the column is MySQL {@code TEXT} (65,535 bytes)
     * and the model embeds user-authored rule text as comments, which is routinely non-ASCII here.
     * A character-based cap therefore passes models three times over the column bound, and MySQL
     * rejects the insert — losing the whole run result, not just its model. The marker is included
     * in the budget and truncation lands on a character boundary, so the file stays decodable.
     *
     * @param smvFile Generated SMV model file
     * @return File content, or null if read fails
     */
    protected String readSmvModelContent(java.io.File smvFile) {
        if (smvFile == null || !smvFile.exists()) {
            log.warn("SMV file does not exist or is null");
            return null;
        }

        try {
            String content = java.nio.file.Files.readString(smvFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            return truncateSmvModelContent(content);
        } catch (java.io.IOException e) {
            log.error("Failed to read SMV file: {}", smvFile.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * Cap the model at {@link #MAX_SMV_MODEL_BYTES} UTF-8 bytes, appending a marker naming the
     * original size so a truncated download cannot pass as the complete model.
     */
    static String truncateSmvModelContent(String content) {
        byte[] utf8 = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (utf8.length <= MAX_SMV_MODEL_BYTES) {
            return content;
        }

        String marker = "\n\n-- [TRUNCATED: Original size " + utf8.length + " bytes]";
        int budget = MAX_SMV_MODEL_BYTES - marker.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        // Cut on a character boundary: CharsetEncoder stops at the last whole character that fits,
        // so the stored text never ends in half of a multi-byte sequence.
        java.nio.CharBuffer in = java.nio.CharBuffer.wrap(content);
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(budget);
        java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode(in, out, true);
        String head = content.substring(0, in.position());

        log.warn("SMV model content truncated: {} bytes -> {} bytes", utf8.length, MAX_SMV_MODEL_BYTES);
        return head + marker;
    }

    // ── 可提取且保留状态机差异钩子的方法 ──────────────────────────────

    /**
     * 更新任务进度。
     * 逻辑来源：VerificationServiceImpl:L532-539, SimulationServiceImpl:L528-535
     */
    protected void updateTaskProgress(Long taskId, int progress, TaskProgressStage stage) {
        Long requiredTaskId = Objects.requireNonNull(taskId, "taskId must not be null");
        TaskProgressStage requiredStage = Objects.requireNonNull(stage, "stage must not be null");
        int clamped = Math.min(100, Math.max(0, progress));
        TaskProgressState next = new TaskProgressState(clamped, requiredStage);
        TaskProgressState previous = taskProgress.put(requiredTaskId, next);
        if (!Objects.equals(previous, next)) {
            int updated = atomicUpdateProgress(requiredTaskId, clamped, requiredStage);
            if (updated == 0) {
                if (previous == null) {
                    taskProgress.remove(requiredTaskId, next);
                } else {
                    taskProgress.replace(requiredTaskId, next, previous);
                }
                requestLocalExecutionStop(requiredTaskId);
                log.info("{} {} lost its active worker lease while updating progress", taskResourceType,
                        requiredTaskId);
                return;
            }
        }
        log.debug("{} {} progress: {}% - {}", taskResourceType, requiredTaskId, progress, requiredStage);
    }

    /**
     * 调用者先用用户范围内的标量查询确认归属，再按内存值、持久化值、终态依次解析进度。
     */
    protected int resolveTaskProgress(Long taskId, Integer persistedProgress, boolean terminal) {
        // 优先使用内存值（当前实例上的活跃任务）
        TaskProgressState progress = taskProgress.get(taskId);
        if (progress != null) {
            return progress.progress();
        }
        // 退回 DB 列（任务在其他实例上或重启后）
        if (persistedProgress != null) {
            return persistedProgress;
        }
        // 终态推断
        if (terminal) {
            return 100;
        }
        return 0;
    }

    /**
     * 取消任务。
     *
     * <p><b>语义保真</b>：先归属校验 → 原子 DB cancel → 返回实际取消结果和终态。
     *
     * <p>逻辑来源：VerificationServiceImpl:L504-529, SimulationServiceImpl:L298-321
     */
    protected TaskCancellationResultDto cancelTask(Long userId, Long taskId) {
        // A locking read sees the committed row under MySQL REPEATABLE_READ. A snapshot read
        // could keep reporting RUNNING after a worker or another instance committed cancellation.
        T task = findTaskByIdAndUserIdForUpdate(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(taskResourceType, taskId));
        boolean markerWasPresent = cancelledTasks.contains(taskId);
        cancelledTasks.add(taskId);
        try {
            int updated = atomicCancelTask(taskId, currentTaskTime());
            if (updated == 0) {
                T currentTask = findTaskByIdAndUserIdForUpdate(taskId, userId)
                        .orElseThrow(() -> new ResourceNotFoundException(taskResourceType, taskId));
                String currentStatus = currentTask.getTaskStatusName();
                boolean alreadyCancelled = currentTask.isCancelledStatus();
                LocalExecutionStopResult localStop = alreadyCancelled
                        ? stopLocalExecution(taskId)
                        : LocalExecutionStopResult.NONE;
                if (!markerWasPresent
                        && localStop != LocalExecutionStopResult.STOP_REQUESTED) {
                    cancelledTasks.remove(taskId);
                }
                return TaskCancellationResultDto.builder()
                        .taskId(taskId)
                        .cancellationAccepted(false)
                        .cancellationOutcome(cancellationOutcomeFor(currentStatus))
                        .taskStatus(currentStatus)
                        .executionMayStillBeStopping(localStop.mayStillBeStopping)
                        .build();
            }

            LocalExecutionStopResult localStop = stopLocalExecution(taskId);
            boolean runningExecutionMayStillBeStopping =
                    localStop != LocalExecutionStopResult.STOPPED_BEFORE_START;
            if (!markerWasPresent
                    && localStop != LocalExecutionStopResult.STOP_REQUESTED) {
                cancelledTasks.remove(taskId);
            }
            return TaskCancellationResultDto.builder()
                    .taskId(taskId)
                    .cancellationAccepted(true)
                    .cancellationOutcome(TaskCancellationOutcome.ACCEPTED)
                    .taskStatus("CANCELLED")
                    .executionMayStillBeStopping(runningExecutionMayStillBeStopping)
                    .build();
        } catch (RuntimeException e) {
            if (!markerWasPresent) {
                cancelledTasks.remove(taskId);
            }
            throw e;
        }
    }

    private static TaskCancellationOutcome cancellationOutcomeFor(String status) {
        return switch (status) {
            case "CANCELLED" -> TaskCancellationOutcome.ALREADY_CANCELLED;
            case "COMPLETED" -> TaskCancellationOutcome.ALREADY_COMPLETED;
            case "FAILED" -> TaskCancellationOutcome.ALREADY_FAILED;
            default -> TaskCancellationOutcome.NO_LONGER_CANCELLABLE;
        };
    }

    /**
     * Records the cancellation of a task whose worker has stopped.
     *
     * <p>Called from every worker's {@code finally} block, ahead of the worker-state cleanup, so it
     * must not propagate: {@link #currentTaskTime()} and the cancel update both touch the database.
     * A failure here used to skip {@code removeRunningTask} / {@code removeTaskProgress}, leaving
     * this thread registered against the finished task — a later cancel would then interrupt
     * whatever unrelated task the pooled thread had moved on to.
     *
     * <p>Swallowing is safe only because the database row remains authoritative: the user's cancel
     * already committed the CANCELLED status, or the lease expires and the recovery sweep resolves
     * the row. Nothing here reports success to a caller.
     */
    protected void handleCancellation(T task) {
        log.info("Handling cancellation for {}: {}", taskResourceType, task.getId());
        try {
            int updated = atomicCancelTask(task.getId(), currentTaskTime());
            if (updated == 0) {
                log.info("{} {} already finished (COMPLETED/FAILED), skipping cancel", taskResourceType, task.getId());
            }
        } catch (Exception e) {
            // The row is left for the user's own cancel to settle, or failing that the expired-lease
            // sweep — which writes FAILED, not CANCELLED. So in this residual case the user can see a
            // run they cancelled reported as failed. That is honest about an unknown outcome; what it
            // must never do is propagate and skip the removeRunningTask cleanup in the caller.
            log.warn("Failed to record cancellation for {} {}; leaving the row to the recovery sweep",
                    taskResourceType, task.getId(), e);
        }
    }

    // ── 子类实现的 Repository 委托钩子 ───────────────────────────────

    /**
     * 根据 ID 和用户 ID 查找任务。
     */
    protected abstract Optional<T> findTaskByIdAndUserId(Long id, Long userId);

    /** Reads an owned task's current status and holds its row lock through cancellation. */
    protected abstract Optional<T> findTaskByIdAndUserIdForUpdate(Long id, Long userId);

    /**
     * 原子取消任务（仅当任务处于 PENDING 或 RUNNING 状态时）。
     * @return 影响行数（0 表示任务已不在可取消状态）
     */
    protected abstract int atomicCancelTask(Long taskId, LocalDateTime completedAt);

    /** Shared persisted clock for terminal task timestamps. */
    protected LocalDateTime currentTaskTime() {
        return LocalDateTime.now();
    }

    /**
     * 原子更新任务进度（仅当任务仍在活跃状态时）。
     */
    protected abstract int atomicUpdateProgress(Long taskId, int progress, TaskProgressStage stage);

    private record TaskProgressState(int progress, TaskProgressStage stage) {
    }
}
