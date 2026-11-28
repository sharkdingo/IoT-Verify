package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport.RunDeletionPresentation;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deletes a completed verification run and every counterexample trace it produced, after explicit
 * two-turn confirmation. Cascade deletion is irreversible, so it uses the shared impact-token guard.
 */
@Component
public class DeleteVerificationRunTool extends AbstractAiTool {

    private static final RunDeletionPresentation DELETION = new RunDeletionPresentation(
            "Verification run",
            "stored counterexamples",
            "wouldRemoveStoredTraceCount",
            "deletedTraceCount",
            "No changes were made. The count includes every stored trace row, even unavailable evidence. Explicit user confirmation is required before deleting this verification run and all of those rows.",
            "Verification run and its counterexample traces deleted.",
            "Failed to delete verification run.");

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
        return AiTaskToolSupport.deleteRun(this, argsJson, userId, destructiveActionGuard, DELETION,
                args -> requireOnlyFields(args, "arguments", Set.of("runId", "confirmed", "impactToken")),
                runId -> verificationService.getRunDeletionImpact(userId, runId),
                (runId, expectedCount) -> verificationService.deleteRun(userId, runId, expectedCount));
    }
}
