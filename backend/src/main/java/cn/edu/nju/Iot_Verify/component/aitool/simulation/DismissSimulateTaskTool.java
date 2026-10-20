package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Removes a failed or cancelled simulation task after an impact-bound confirmation. */
@Slf4j
@Component
public class DismissSimulateTaskTool extends AbstractAiTool {


    private final SimulationService simulationService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public DismissSimulateTaskTool(SimulationService simulationService,
                                   ObjectMapper objectMapper,
                                   AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.simulationService = simulationService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "dismiss_simulate_task";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("taskId", Map.of("type", "integer",
                "description", "Failed or cancelled simulation task ID to remove from the task inbox."));
        properties.put("confirmed", Map.of("type", "boolean",
                "description", "Use false to preview the task and diagnostics that would be removed. Use true only in a later turn after explicit user confirmation."));
        properties.put("impactToken", Map.of("type", "string",
                "description", "Required with confirmed=true. Copy the opaque impactToken from the latest preview."));
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", properties, List.of("taskId", "confirmed"));
        return LlmToolSpec.of(getName(),
                "Preview or, after explicit two-turn confirmation, dismiss a failed or cancelled simulation task and its diagnostics. Active tasks must be cancelled first; saved simulation traces are removed with delete_simulation_trace, not here.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of("taskId", "confirmed", "impactToken"));
            long taskId = positiveLongArg(args, "taskId");
            if (!args.hasNonNull("confirmed")) {
                throw new ArgValidationException(errorJson(
                        "'confirmed' is required.", "VALIDATION_ERROR", 400));
            }
            boolean confirmed = booleanArg(args, "confirmed", false);

            SimulationTaskDto task = simulationService.getTask(userId, taskId);
            ensureDismissible(task.getStatus());
            Map<String, Object> previewSummary = previewSummary(taskId, task);
            String displayTarget = "Simulation task: " + task.getStatus() + " · " + task.getCreatedAt();
            if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
                String impactToken = destructiveActionGuard.issue(
                        userId, getName(), Long.toString(taskId),
                        displayTarget, task, null);
                return readOnlySuccessJson(previewResponse(previewSummary, impactToken),
                        "Simulation task dismissal preview prepared; no changes were made.");
            }

            String impactToken = requiredTextField(args, "impactToken", "arguments");
            AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consume(
                    userId, getName(), Long.toString(taskId), impactToken, task);
            if (!confirmation.approved()) {
                String freshToken = destructiveActionGuard.issue(
                        userId, getName(), Long.toString(taskId),
                        displayTarget, task, null);
                return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                        "requiresUserConfirmation", true,
                        "currentPreview", previewResponse(previewSummary, freshToken)));
            }

            simulationService.deleteTask(userId, taskId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("dismissed", true);
            body.put("message", "Simulation task dismissed.");
            return successJson(body, "Simulation task dismissed.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("dismiss_simulate_task busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("dismiss_simulate_task business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("dismiss_simulate_task failed", e);
            return errorJson("Failed to dismiss simulation task.", "INTERNAL_ERROR", 500);
        }
    }

    private void ensureDismissible(String status) {
        if (!"FAILED".equals(status) && !"CANCELLED".equals(status)) {
            throw new BadRequestException(
                    "Only failed or cancelled simulation tasks can be dismissed; current status is "
                            + (status == null ? "unknown" : status));
        }
    }

    private Map<String, Object> previewSummary(long taskId, SimulationTaskDto task) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskId", taskId);
        summary.put("taskType", "simulation");
        summary.put("status", task.getStatus());
        summary.put("createdAt", task.getCreatedAt());
        summary.put("startedAt", task.getStartedAt());
        summary.put("completedAt", task.getCompletedAt());
        summary.put("processingTimeMs", task.getProcessingTimeMs());
        summary.put("progress", task.getProgress());
        summary.put("progressStage", task.getProgressStage());
        summary.put("errorMessage", errorPreview(task.getErrorMessage()));
        summary.put("errorMessageLength",
                task.getErrorMessage() == null ? 0 : task.getErrorMessage().length());
        summary.put("errorMessageTruncated",
                task.getErrorMessage() != null
                        && task.getErrorMessage().length() > ERROR_PREVIEW_LIMIT);
        summary.put("requestedSteps", task.getRequestedSteps());
        summary.put("checkLogCount", safeList(task.getCheckLogs()).size());
        summary.put("generationIssueCount", safeList(task.getGenerationIssues()).size());
        summary.put("hasModelSnapshot", task.getModelSnapshot() != null);
        summary.put("diagnosticsWillBeRemoved", true);
        return summary;
    }


    private Map<String, Object> previewResponse(Map<String, Object> summary, String impactToken) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("message", "No changes were made. Explicit user confirmation is required before removing this task and its failure or cancellation diagnostics.");
        preview.put("operation", "preview");
        preview.put("requiresUserConfirmation", true);
        preview.putAll(summary);
        preview.put("impactToken", impactToken);
        return preview;
    }
}
