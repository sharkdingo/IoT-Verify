package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskDto;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SimulateTaskStatusTool extends AbstractAiTool {

    private final SimulationService simulationService;

    public SimulateTaskStatusTool(SimulationService simulationService,
                                  ObjectMapper objectMapper) {
        super(objectMapper);
        this.simulationService = simulationService;
    }

    @Override
    public String getName() {
        return "simulate_task_status";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(),
                "Query async simulation task status and progress by taskId, waiting briefly for active work. PENDING or RUNNING means keep waiting. A completed saved trajectory exposes its simulationId for get_simulation_trace. Execution logs are intentionally omitted.",
                AiTaskToolSupport.statusSchema("Simulation task ID returned by simulate_model_async."));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return AiTaskToolSupport.status(this, argsJson, "simulation",
                "Simulation status waiting stopped; the simulation task was not cancelled.",
                taskId -> simulationService.getTask(userId, taskId),
                this::presentTask);
    }

    private void presentTask(Map<String, Object> body, long taskId, SimulationTaskDto task, int progress) {
        body.put("task", taskProjection(task, progress));
        if (task.getSimulationTraceId() != null && task.getSimulationTraceId() > 0) {
            body.put("simulationId", task.getSimulationTraceId());
            body.put("nextTool", "get_simulation_trace");
        }
    }

    private Map<String, Object> taskProjection(SimulationTaskDto task, int progress) {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("id", task.getId());
        projected.put("initiator", task.getInitiator());
        projected.put("status", task.getStatus());
        projected.put("progress", progress);
        projected.put("progressStage", task.getProgressStage());
        projected.put("createdAt", task.getCreatedAt());
        projected.put("startedAt", task.getStartedAt());
        projected.put("completedAt", task.getCompletedAt());
        projected.put("processingTimeMs", task.getProcessingTimeMs());
        projected.put("isAttack", task.getIsAttack());
        projected.put("attackBudget", task.getAttackBudget());
        projected.put("enablePrivacy", task.getEnablePrivacy());
        projected.put("modelSemantics", task.getModelSemantics());
        projected.put("modelSnapshot", task.getModelSnapshot());
        projected.put("requestedSteps", task.getRequestedSteps());
        projected.put("steps", task.getSteps());
        projected.put("modelComplete", task.getModelComplete());
        projected.put("disabledRuleCount", task.getDisabledRuleCount());
        projected.put("generationIssues", safeList(task.getGenerationIssues()));
        projected.put("simulationTraceId", task.getSimulationTraceId());
        projected.put("checkLogCount", safeList(task.getCheckLogs()).size());
        projected.put("errorMessage", task.getErrorMessage());
        return projected;
    }
}
