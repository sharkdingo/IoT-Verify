package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskToolState;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/** Shared execution and confirmation rules for the assistant's task and run lifecycle tools. */
public final class AiTaskToolSupport {

    private static final String TASK_DISMISSAL_PREVIEW =
            "No changes were made. Explicit user confirmation is required before removing this task and its failure or cancellation diagnostics.";

    private AiTaskToolSupport() {
    }

    @FunctionalInterface
    public interface ArgumentCheck {
        void validate(JsonNode args) throws AbstractAiTool.ArgValidationException;
    }

    @FunctionalInterface
    public interface StatusPresenter<T> {
        void present(Map<String, Object> body, long taskId, T task, int progress);
    }

    public static FunctionParameterSchema cancelSchema(String taskIdDescription) {
        return new FunctionParameterSchema("object",
                Map.of("taskId", Map.of("type", "integer", "description", taskIdDescription)), List.of("taskId"));
    }

    public static String cancel(
            AbstractAiTool tool, String argsJson, String taskType, String runningWorker, String completedArtifact,
            LongFunction<TaskCancellationResultDto> cancelTask) {
        try {
            JsonNode args = tool.parseArgs(argsJson);
            tool.requireOnlyFields(args, "arguments", Set.of("taskId"));
            long taskId = tool.positiveLongArg(args, "taskId");

            TaskCancellationResultDto result = cancelTask.apply(taskId);
            String message = switch (result.getCancellationOutcome()) {
                case ACCEPTED -> result.isExecutionMayStillBeStopping()
                        ? "The task was marked CANCELLED; the running " + runningWorker + " may still be stopping."
                        : "The pending task was marked CANCELLED before execution.";
                case ALREADY_CANCELLED -> "The task was already marked CANCELLED.";
                case ALREADY_COMPLETED -> "The task completed before cancellation; inspect its " + completedArtifact + ".";
                case ALREADY_FAILED -> "The task failed before cancellation; inspect its error.";
                case NO_LONGER_CANCELLABLE -> "The task is no longer in a cancellable state.";
            };
            return tool.successJson(Map.of(
                    "taskId", result.getTaskId(),
                    "cancellationAccepted", result.isCancellationAccepted(),
                    "cancellationOutcome", result.getCancellationOutcome(),
                    "taskStatus", result.getTaskStatus(),
                    "executionMayStillBeStopping", result.isExecutionMayStillBeStopping(),
                    "message", message
            ));
        } catch (AbstractAiTool.ArgValidationException e) {
            return e.getErrorResponse();
        } catch (Exception e) {
            return failure(tool, "Failed to cancel " + taskType + " task.", e);
        }
    }

    public static FunctionParameterSchema statusSchema(String taskIdDescription) {
        return new FunctionParameterSchema("object",
                Map.of("taskId", Map.of("type", "integer", "description", taskIdDescription),
                        "waitMs", AiTaskStatusWaiter.waitMsSchema()), List.of("taskId"));
    }

    public static <T extends TaskToolState> String status(
            AbstractAiTool tool, String argsJson, String taskType, String waitStoppedMessage,
            LongFunction<T> load, StatusPresenter<T> presenter) {
        try {
            JsonNode args = tool.parseArgs(argsJson);
            tool.requireOnlyFields(args, "arguments", Set.of("taskId", "waitMs"));
            long taskId = tool.positiveLongArg(args, "taskId");
            int waitMs = tool.taskStatusWaitMsArg(args);
            T task = AiTaskStatusWaiter.await(() -> load.apply(taskId), TaskToolState::getStatus, waitMs);
            int progress = task.getProgress();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("progress", progress);
            presenter.present(body, taskId, task, progress);
            return tool.readOnlySuccessJson(body);
        } catch (AbstractAiTool.ArgValidationException e) {
            return e.getErrorResponse();
        } catch (CancellationException e) {
            return waitStopped(tool, waitStoppedMessage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return waitStopped(tool, waitStoppedMessage);
        } catch (Exception e) {
            return failure(tool, "Failed to query " + taskType + " task.", e);
        }
    }

    public static FunctionParameterSchema dismissSchema(String taskIdDescription) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("taskId", Map.of("type", "integer", "description", taskIdDescription));
        properties.put("confirmed", Map.of("type", "boolean",
                "description", "Use false to preview the task and diagnostics that would be removed. Use true only in a later turn after explicit user confirmation."));
        properties.put("impactToken", Map.of("type", "string",
                "description", "Required with confirmed=true. Copy the opaque impactToken from the latest preview."));
        return new FunctionParameterSchema("object", properties, List.of("taskId", "confirmed"));
    }

    public static <T extends TaskToolState> String dismiss(
            AbstractAiTool tool, String argsJson, Long userId, String taskType, AiDestructiveActionGuard guard,
            LongFunction<T> load, LongConsumer delete,
            BiConsumer<Map<String, Object>, T> appendDiagnostics) {
        try {
            JsonNode args = tool.parseArgs(argsJson);
            tool.requireOnlyFields(args, "arguments", Set.of("taskId", "confirmed", "impactToken"));
            long taskId = tool.positiveLongArg(args, "taskId");
            if (!args.hasNonNull("confirmed")) {
                throw new AbstractAiTool.ArgValidationException(
                        tool.errorJson("'confirmed' is required.", "VALIDATION_ERROR", 400));
            }
            boolean confirmed = tool.booleanArg(args, "confirmed", false);

            T task = load.apply(taskId);
            String status = task.getStatus();
            if (!"FAILED".equals(status) && !"CANCELLED".equals(status)) {
                throw new BadRequestException("Only failed or cancelled " + taskType
                        + " tasks can be dismissed; current status is " + (status == null ? "unknown" : status));
            }
            Map<String, Object> summary = diagnosticSummary(taskId, taskType, task);
            appendDiagnostics.accept(summary, task);
            summary.put("diagnosticsWillBeRemoved", true);

            String taskLabel = displayType(taskType) + " task";
            String displayTarget = taskLabel + ": " + status + " · " + task.getCreatedAt();
            return withDeletionConfirmation(tool, args, confirmed, userId, guard,
                    new DeletionPreview(taskId, displayTarget, task, summary, TASK_DISMISSAL_PREVIEW), () -> {
                delete.accept(taskId);
                String message = taskLabel + " dismissed.";
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("taskId", taskId);
                body.put("dismissed", true);
                body.put("message", message);
                return tool.successJson(body);
            });
        } catch (AbstractAiTool.ArgValidationException e) {
            return e.getErrorResponse();
        } catch (Exception e) {
            return failure(tool, "Failed to dismiss " + taskType + " task.", e);
        }
    }

    public record RunDeletionPresentation(
            String displayPrefix, String evidenceLabel, String previewCountField,
            String deletedCountField, String previewMessage, String deletedMessage, String failureMessage) {
    }

    public static String deleteRun(
            AbstractAiTool tool, String argsJson, Long userId, AiDestructiveActionGuard guard,
            RunDeletionPresentation presentation, ArgumentCheck check,
            LongFunction<RunDeletionImpactDto> load, LongBinaryOperator delete) {
        try {
            JsonNode args = tool.parseArgs(argsJson);
            check.validate(args);
            long runId = tool.positiveLongArg(args, "runId");

            RunDeletionImpactDto impact = load.apply(runId);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("runId", impact.getRunId());
            summary.put(presentation.previewCountField(), impact.getEvidenceCount());
            summary.put("countIncludesUnavailableEvidence", true);
            summary.put("createdAt", impact.getCreatedAt());
            summary.put("completedAt", impact.getCompletedAt());
            String displayTarget = presentation.displayPrefix() + ": " + impact.getCompletedAt() + " · "
                    + impact.getEvidenceCount() + " " + presentation.evidenceLabel();
            boolean confirmed = tool.booleanArg(args, "confirmed", false);
            return withDeletionConfirmation(tool, args, confirmed, userId, guard,
                    new DeletionPreview(runId, displayTarget, summary, summary, presentation.previewMessage()), () -> {
                long deletedCount = delete.applyAsLong(runId, impact.getEvidenceCount());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("runId", runId);
                body.put("deleted", true);
                body.put(presentation.deletedCountField(), deletedCount);
                body.put("message", presentation.deletedMessage());
                return tool.successJson(body);
            });
        } catch (AbstractAiTool.ArgValidationException e) {
            return e.getErrorResponse();
        } catch (Exception e) {
            return failure(tool, presentation.failureMessage(), e);
        }
    }

    /** The confirmation snapshot can include evidence omitted from the displayed summary. */
    public record DeletionPreview(
            long targetId, String displayTarget, Object snapshot, Map<String, Object> summary,
            String previewMessage) {
    }

    public static String withDeletionConfirmation(
            AbstractAiTool tool, JsonNode args, boolean confirmed, Long userId, AiDestructiveActionGuard guard,
            DeletionPreview current, Supplier<String> onApproval) throws AbstractAiTool.ArgValidationException {
        String toolName = tool.getName();
        String target = Long.toString(current.targetId());
        if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
            String token = guard.issue(userId, toolName, target, current.displayTarget(), current.snapshot(), null);
            return tool.readOnlySuccessJson(preview(current.summary(), token, current.previewMessage()));
        }

        String token = tool.requiredTextField(args, "impactToken", "arguments");
        var confirmation = guard.consume(userId, toolName, target, token, current.snapshot());
        if (!confirmation.approved()) {
            String freshToken = guard.issue(userId, toolName, target, current.displayTarget(), current.snapshot(), null);
            return tool.errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                    "requiresUserConfirmation", true,
                    "currentPreview", preview(current.summary(), freshToken, current.previewMessage())));
        }

        return onApproval.get();
    }

    private static Map<String, Object> diagnosticSummary(long taskId, String taskType, TaskToolState task) {
        String error = task.getErrorMessage();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskId", taskId);
        summary.put("taskType", taskType);
        summary.put("status", task.getStatus());
        summary.put("createdAt", task.getCreatedAt());
        summary.put("startedAt", task.getStartedAt());
        summary.put("completedAt", task.getCompletedAt());
        summary.put("processingTimeMs", task.getProcessingTimeMs());
        summary.put("progress", task.getProgress());
        summary.put("progressStage", task.getProgressStage());
        summary.put("errorMessage", AbstractAiTool.errorPreview(error));
        summary.put("errorMessageLength", error == null ? 0 : error.length());
        summary.put("errorMessageTruncated", error != null && error.length() > AbstractAiTool.ERROR_PREVIEW_LIMIT);
        return summary;
    }

    private static Map<String, Object> preview(Map<String, Object> summary, String impactToken, String message) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("message", message);
        preview.put("operation", "preview");
        preview.put("requiresUserConfirmation", true);
        preview.putAll(summary);
        preview.put("impactToken", impactToken);
        return preview;
    }

    private static String waitStopped(AbstractAiTool tool, String message) {
        return tool.errorJson(message, "TASK_STATUS_WAIT_CANCELLED", 499);
    }

    private static String failure(AbstractAiTool tool, String fallback, Exception failure) {
        Logger logger = LoggerFactory.getLogger(tool.getClass());
        if (failure instanceof ServiceUnavailableException busy) {
            logger.warn("{} busy: {}", tool.getName(), busy.getMessage());
            return tool.errorJson(busy.getMessage(), "SERVICE_UNAVAILABLE", 503);
        }
        if (failure instanceof BaseException business) {
            logger.warn("{} business error [{}]: {}", tool.getName(), business.getCode(), business.getMessage());
            return tool.errorJson(business.getMessage(), "BUSINESS_ERROR", business.getCode());
        }
        logger.error("{} failed", tool.getName(), failure);
        return tool.errorJson(fallback, "INTERNAL_ERROR", 500);
    }

    private static String displayType(String taskType) {
        return Character.toUpperCase(taskType.charAt(0)) + taskType.substring(1);
    }
}
