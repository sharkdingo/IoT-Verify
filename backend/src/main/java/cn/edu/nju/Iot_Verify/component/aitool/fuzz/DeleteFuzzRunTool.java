package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport.RunDeletionPresentation;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deletes a completed counterexample-search run and every finding it produced, after explicit
 * two-turn confirmation. Cascade deletion is irreversible, so it uses the shared impact-token guard.
 */
@Component
public class DeleteFuzzRunTool extends AbstractAiTool {

    private static final RunDeletionPresentation DELETION = new RunDeletionPresentation(
            "Counterexample search",
            "stored findings",
            "wouldRemoveStoredFindingCount",
            "deletedFindingCount",
            "No changes were made. The count includes every stored finding row, even unavailable evidence. Explicit user confirmation is required before deleting this counterexample-search run and all of those rows.",
            "Counterexample-search run and its findings deleted.",
            "Failed to delete counterexample-search run.");

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
        return AiTaskToolSupport.deleteRun(this, argsJson, userId, destructiveActionGuard, DELETION,
                args -> requireOnlyFields(args, "arguments", Set.of("runId", "confirmed", "impactToken")),
                runId -> fuzzService.getRunDeletionImpact(userId, runId),
                (runId, expectedCount) -> fuzzService.deleteRun(userId, runId, expectedCount));
    }
}
