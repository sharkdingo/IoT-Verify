package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskDto;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Removes a failed or cancelled counterexample-search task after an impact-bound confirmation. */
@Component
public class DismissFuzzTaskTool extends AbstractAiTool {

    private final FuzzService fuzzService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public DismissFuzzTaskTool(FuzzService fuzzService,
                               ObjectMapper objectMapper,
                               AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.fuzzService = fuzzService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "dismiss_fuzz_task";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(),
                "Preview or, after explicit two-turn confirmation, dismiss a failed or cancelled counterexample-search task and its diagnostics. Active tasks must be cancelled first, and completed results are removed with delete_fuzz_run, not here.",
                AiTaskToolSupport.dismissSchema("Failed or cancelled counterexample-search task ID to remove from the task inbox."));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return AiTaskToolSupport.dismiss(this, argsJson, userId, "counterexample-search", destructiveActionGuard,
                taskId -> fuzzService.getTask(userId, taskId),
                taskId -> fuzzService.deleteTask(userId, taskId),
                this::appendDiagnostics);
    }

    private void appendDiagnostics(Map<String, Object> summary, FuzzTaskDto task) {
        summary.put("explorationMode", task.getExplorationMode());
        summary.put("maxIterations", task.getMaxIterations());
        summary.put("pathLength", task.getPathLength());
        summary.put("populationSize", task.getPopulationSize());
        summary.put("seed", task.getSeed());
        summary.put("targetSpecIds", safeList(task.getTargetSpecIds()));
        summary.put("hasModelSnapshot", task.getModelSnapshot() != null);
    }
}
