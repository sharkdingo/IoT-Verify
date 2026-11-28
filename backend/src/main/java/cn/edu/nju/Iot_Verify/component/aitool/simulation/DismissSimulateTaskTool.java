package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskDto;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Removes a failed or cancelled simulation task after an impact-bound confirmation. */
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
        return LlmToolSpec.of(getName(),
                "Preview or, after explicit two-turn confirmation, dismiss a failed or cancelled simulation task and its diagnostics. Active tasks must be cancelled first; saved simulation traces are removed with delete_simulation_trace, not here.",
                AiTaskToolSupport.dismissSchema("Failed or cancelled simulation task ID to remove from the task inbox."));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return AiTaskToolSupport.dismiss(this, argsJson, userId, "simulation", destructiveActionGuard,
                taskId -> simulationService.getTask(userId, taskId),
                taskId -> simulationService.deleteTask(userId, taskId),
                this::appendDiagnostics);
    }

    private void appendDiagnostics(Map<String, Object> summary, SimulationTaskDto task) {
        summary.put("requestedSteps", task.getRequestedSteps());
        summary.put("checkLogCount", safeList(task.getCheckLogs()).size());
        summary.put("generationIssueCount", safeList(task.getGenerationIssues()).size());
        summary.put("hasModelSnapshot", task.getModelSnapshot() != null);
    }
}
