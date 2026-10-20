package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.VerificationService;
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
 * Deletes a completed verification run and every counterexample trace it produced, after explicit
 * two-turn confirmation. Cascade deletion is irreversible, so it uses the shared impact-token guard.
 */
@Slf4j
@Component
public class DeleteVerificationRunTool extends AbstractAiTool {

    private final VerificationService verificationService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public DeleteVerificationRunTool(VerificationService verificationService,
                                     ObjectMapper objectMapper,
                                     AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.verificationService = verificationService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "delete_verification_run";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runId", Map.of("type", "integer",
                "description", "Completed verification run ID (from list_traces runId or the verification history)."));
        props.put("confirmed", Map.of("type", "boolean",
                "description", "Use false to preview the run and the exact number of stored trace rows, including unavailable evidence. Use true only in a later turn after explicit user confirmation."));
        props.put("impactToken", Map.of("type", "string",
                "description", "Required with confirmed=true. Copy the opaque impactToken from the latest preview."));
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("runId", "confirmed"));
        return LlmToolSpec.of(getName(),
                "Preview or, after explicit two-turn confirmation, delete a completed verification run and every counterexample trace it produced. Cascade deletion is irreversible. To remove a single trace instead, use delete_trace.",
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

            RunDeletionImpactDto impact = verificationService.getRunDeletionImpact(userId, runId);
            Map<String, Object> previewSummary = previewSummary(impact);
            String displayTarget = "Verification run: " + impact.getCompletedAt() + " · "
                    + impact.getEvidenceCount() + " stored counterexamples";
            boolean confirmed = booleanArg(args, "confirmed", false);
            if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
                String impactToken = destructiveActionGuard.issue(
                        userId, getName(), Long.toString(runId),
                        displayTarget, previewSummary, null);
                return readOnlySuccessJson(previewResponse(previewSummary, impactToken),
                        "Verification run deletion preview prepared; no changes were made.");
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

            long deletedTraceCount = verificationService.deleteRun(
                    userId, runId, impact.getEvidenceCount());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", runId);
            body.put("deleted", true);
            body.put("deletedTraceCount", deletedTraceCount);
            body.put("message", "Verification run and its counterexample traces deleted.");
            return successJson(body, "Verification run deleted.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("delete_verification_run busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("delete_verification_run business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("delete_verification_run failed", e);
            return errorJson("Failed to delete verification run.", "INTERNAL_ERROR", 500);
        }
    }

    private Map<String, Object> previewSummary(RunDeletionImpactDto impact) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", impact.getRunId());
        summary.put("wouldRemoveStoredTraceCount", impact.getEvidenceCount());
        summary.put("countIncludesUnavailableEvidence", true);
        summary.put("createdAt", impact.getCreatedAt());
        summary.put("completedAt", impact.getCompletedAt());
        return summary;
    }

    private Map<String, Object> previewResponse(Map<String, Object> summary, String impactToken) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("message", "No changes were made. The count includes every stored trace row, even unavailable evidence. Explicit user confirmation is required before deleting this verification run and all of those rows.");
        preview.put("operation", "preview");
        preview.put("requiresUserConfirmation", true);
        preview.putAll(summary);
        preview.put("impactToken", impactToken);
        return preview;
    }
}
