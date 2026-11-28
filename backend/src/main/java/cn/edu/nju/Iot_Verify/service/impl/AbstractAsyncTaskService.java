package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.Result;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationOutcome;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.po.TaskView;
import cn.edu.nju.Iot_Verify.service.AsyncTaskExecutionControl;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 异步任务管理基类。
 *
 * <p>提取 VerificationServiceImpl 与 SimulationServiceImpl 中完全相同的：
 * <ul>
 *   <li>本地进度管理</li>
 *   <li>serializeCheckLogs / readCheckLogs / truncateOutput</li>
 *   <li>updateTaskProgress / resolveTaskProgress / cancelTask</li>
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

    private final ConcurrentHashMap<Long, TaskProgressState> taskProgress = new ConcurrentHashMap<>();

    // Task IDs are scoped to each service's repository.
    private final ConcurrentHashMap<Long, LocalTaskExecution> localExecutions = new ConcurrentHashMap<>();

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

    final void registerLocalExecution(Long taskId, LocalTaskExecution execution, String taskKind) {
        if (localExecutions.putIfAbsent(taskId, execution) != null) {
            throw new IllegalStateException("Duplicate local " + taskKind + " task execution " + taskId);
        }
    }

    final void retireLocalExecution(Long taskId, LocalTaskExecution execution) {
        localExecutions.remove(taskId, execution);
    }

    final LocalTaskExecution findLocalExecution(Long taskId) {
        return localExecutions.get(taskId);
    }

    final List<TaskLeaseMaintenance.LocalLease> localLeases() {
        return localExecutions.values().stream().map(LocalTaskExecution::lease).toList();
    }

    /** Cancels the task's own Future, which fences interruption from pooled worker reuse. */
    protected LocalExecutionStopResult stopLocalExecution(Long taskId) {
        LocalTaskExecution execution = findLocalExecution(taskId);
        return execution == null ? LocalExecutionStopResult.NONE : execution.requestStop();
    }

    /** The task's Future retains a stop request even if the worker cleared its interrupt flag. */
    protected boolean isLocalStopRequested(Long taskId) {
        LocalTaskExecution execution = findLocalExecution(taskId);
        return execution != null && execution.isCancelled();
    }

    // ── 直接搬入的具体方法（无领域差异）─────────────────────────────

    /** Captures history rows together; callers decode the captured rows outside this read. */
    protected static <R> R captureHistoryRows(TransactionTemplate transactionTemplate, Supplier<R> capture) {
        var readTransaction = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readTransaction.setReadOnly(true);
        return readTransaction.execute(status -> capture.get());
    }

    protected static Long requireTaskId(Long taskId) {
        if (taskId == null) {
            throw new ValidationException("taskId", "Task id cannot be null");
        }
        return taskId;
    }

    /** Recheck the owning user's task when the guarded delete has not confirmed its removal. */
    protected static boolean removeUndispatchedTask(
            Long taskId, String taskKind, Logger logger, RuntimeException dispatchError,
            IntSupplier deletePendingTask, BooleanSupplier taskAbsent) {
        try {
            if (deletePendingTask.getAsInt() == 1) return true;
            boolean absent = taskAbsent.getAsBoolean();
            if (!absent) {
                logger.error("Could not remove {} task {} after failure before dispatch", taskKind, taskId);
            }
            return absent;
        } catch (RuntimeException cleanupError) {
            dispatchError.addSuppressed(cleanupError);
            logger.error("Could not remove {} task {} after failure before dispatch",
                    taskKind, taskId, cleanupError);
            return false;
        }
    }

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

    protected String truncateOutput(String output) {
        if (output == null) return null;
        return output.length() > MAX_OUTPUT_LENGTH
                ? output.substring(0, MAX_OUTPUT_LENGTH) + OUTPUT_TRUNCATION_MARKER : output;
    }

    protected void saveRequestJson(File smvFile, String requestJson, Logger logger, String operation) {
        if (smvFile == null || smvFile.getParentFile() == null || requestJson == null || requestJson.isBlank()) return;
        try {
            File jsonFile = new File(smvFile.getParentFile(), "request.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile, objectMapper.readTree(requestJson));
            logger.debug("{} request JSON saved to: {}", operation, jsonFile.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to save {} request JSON: {}", operation.toLowerCase(Locale.ROOT), e.getMessage());
        }
    }

    protected <R> void saveResultJson(File smvFile, R result, Function<R, Result<R>> wrap,
                                      Logger logger, String operation, String failureMessage) {
        if (smvFile == null || smvFile.getParentFile() == null || result == null) return;
        try {
            File jsonFile = new File(smvFile.getParentFile(), "result.json");
            byte[] payload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(wrap.apply(result));
            Files.write(jsonFile.toPath(), payload);
            logger.debug("{} result JSON saved to: {}", operation, jsonFile.getAbsolutePath());
        } catch (IOException e) {
            logger.warn(failureMessage, e.getMessage());
        }
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
        if (next.equals(taskProgress.get(requiredTaskId))) return;
        if (atomicUpdateProgress(requiredTaskId, clamped, requiredStage) == 0) {
            requestLocalExecutionStop(requiredTaskId);
            log.info("{} {} lost its active worker lease while updating progress", taskResourceType, requiredTaskId);
            return;
        }
        // The worker publishes progress only once its fenced database write has committed.
        taskProgress.put(requiredTaskId, next);
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

    protected TaskCancellationResultDto cancelTask(Long userId, Long taskId) {
        // A locking read sees the committed row under MySQL REPEATABLE_READ. A snapshot read
        // could keep reporting RUNNING after a worker or another instance committed cancellation.
        findTaskByIdAndUserIdForUpdate(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(taskResourceType, taskId));
        boolean accepted = atomicCancelTask(taskId, currentTaskTime()) > 0;
        String status = accepted ? "CANCELLED" : findTaskByIdAndUserIdForUpdate(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(taskResourceType, taskId))
                .getTaskStatusName();
        TaskCancellationOutcome outcome = accepted ? TaskCancellationOutcome.ACCEPTED : cancellationOutcomeFor(status);
        boolean stop = accepted || outcome == TaskCancellationOutcome.ALREADY_CANCELLED;
        TaskCancellationResultDto receipt = TaskCancellationResultDto.builder()
                .taskId(taskId)
                .cancellationAccepted(accepted)
                .cancellationOutcome(outcome)
                .taskStatus(status)
                .executionMayStillBeStopping(stop)
                .build();
        if (stop) {
            stopAfterCommit(receipt);
        }
        return receipt;
    }

    private void stopAfterCommit(TaskCancellationResultDto receipt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            stopCommittedExecution(receipt);
            return;
        }
        // The transactional entry point returns the receipt after completion. If local stopping
        // fails, Spring logs the callback error and the committed receipt remains conservative.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    stopCommittedExecution(receipt);
                }
            }
        });
    }

    private void stopCommittedExecution(TaskCancellationResultDto receipt) {
        LocalExecutionStopResult localStop = stopLocalExecution(receipt.getTaskId());
        receipt.setExecutionMayStillBeStopping(receipt.isCancellationAccepted()
                ? localStop != LocalExecutionStopResult.STOPPED_BEFORE_START
                : localStop.mayStillBeStopping);
    }

    private static TaskCancellationOutcome cancellationOutcomeFor(String status) {
        return switch (status) {
            case "CANCELLED" -> TaskCancellationOutcome.ALREADY_CANCELLED;
            case "COMPLETED" -> TaskCancellationOutcome.ALREADY_COMPLETED;
            case "FAILED" -> TaskCancellationOutcome.ALREADY_FAILED;
            default -> TaskCancellationOutcome.NO_LONGER_CANCELLABLE;
        };
    }

    // ── 子类实现的 Repository 委托钩子 ───────────────────────────────

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
