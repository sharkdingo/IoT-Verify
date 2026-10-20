package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deletes a completed counterexample-search run and every finding it produced, after explicit
 * two-turn confirmation. Cascade deletion is irreversible, so it uses the shared impact-token guard.
 */
@Slf4j
@Component
public class DeleteFuzzRunTool extends AbstractAiTool {

    private final FuzzService fuzzService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public DeleteFuzzRunTool(FuzzService fuzzService,
                             ObjectMapper objectMapper,
                             AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.fuzzService = fuzzService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "delete_fuzz_run";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runId", Map.of("type", "integer",
                "description", "Completed counterexample-search run ID."));
        props.put("confirmed", Map.of("type", "boolean",
                "description", "Use false to preview the run and the exact number of stored finding rows, including unavailable evidence. Use true only in a later turn after explicit user confirmation."));
        props.put("impactToken", Map.of("type", "string",
                "description", "Required with confirmed=true. Copy the opaque impactToken from the latest preview."));
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("runId", "confirmed"));
        return LlmToolSpec.of(getName(),
                "Preview or, after explicit two-turn confirmation, delete a completed counterexample-search run and every finding it produced. Cascade deletion is irreversible.",
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
            requireOnlyFields(args, "arguments", Set.of("runId", "confirmed", "impactToken"));
            long runId = positiveLongArg(args, "runId");

            RunDeletionImpactDto impact = fuzzService.getRunDeletionImpact(userId, runId);
            Map<String, Object> previewSummary = previewSummary(impact);
            String displayTarget = "Counterexample search: " + impact.getCompletedAt() + " · "
                    + impact.getEvidenceCount() + " stored findings";
            boolean confirmed = booleanArg(args, "confirmed", false);
            if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
                String impactToken = destructiveActionGuard.issue(
                        userId, getName(), Long.toString(runId),
                        displayTarget, previewSummary, null);
                return readOnlySuccessJson(previewResponse(previewSummary, impactToken),
                        "Counterexample-search run deletion preview prepared; no changes were made.");
            }

            String impactToken = requiredTextField(args, "impactToken", "arguments");
            AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consume(
                    userId, getName(), Long.toString(runId), impactToken, previewSummary);
            if (!confirmation.approved()) {
                String freshToken = destructiveActionGuard.issue(
                        userId, getName(), Long.toString(runId),
                        displayTarget, previewSummary, null);
                return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                        "requiresUserConfirmation", true,
                        "currentPreview", previewResponse(previewSummary, freshToken)));
            }

            long deletedFindingCount = fuzzService.deleteRun(
                    userId, runId, impact.getEvidenceCount());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", runId);
            body.put("deleted", true);
            body.put("deletedFindingCount", deletedFindingCount);
            body.put("message", "Counterexample-search run and its findings deleted.");
            return successJson(body, "Counterexample-search run deleted.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("delete_fuzz_run busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("delete_fuzz_run business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("delete_fuzz_run failed", e);
            return errorJson("Failed to delete counterexample-search run.", "INTERNAL_ERROR", 500);
        }
    }

    private Map<String, Object> previewSummary(RunDeletionImpactDto impact) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", impact.getRunId());
        summary.put("wouldRemoveStoredFindingCount", impact.getEvidenceCount());
        summary.put("countIncludesUnavailableEvidence", true);
        summary.put("createdAt", impact.getCreatedAt());
        summary.put("completedAt", impact.getCompletedAt());
        return summary;
    }

    private Map<String, Object> previewResponse(Map<String, Object> summary, String impactToken) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("message", "No changes were made. The count includes every stored finding row, even unavailable evidence. Explicit user confirmation is required before deleting this counterexample-search run and all of those rows.");
        preview.put("operation", "preview");
        preview.put("requiresUserConfirmation", true);
        preview.putAll(summary);
        preview.put("impactToken", impactToken);
        return preview;
    }
}
