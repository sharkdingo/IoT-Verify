package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class CancelSimulateTaskTool extends AbstractAiTool {

    private final SimulationService simulationService;

    public CancelSimulateTaskTool(SimulationService simulationService,
                                   ObjectMapper objectMapper) {
        super(objectMapper);
        this.simulationService = simulationService;
    }

    @Override
    public String getName() {
        return "cancel_simulate_task";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(), "Cancel an async simulation task by taskId.",
                AiTaskToolSupport.cancelSchema("Simulation task ID."));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return AiTaskToolSupport.cancel(this, argsJson, "simulation", "simulator", "result",
                taskId -> simulationService.cancelTask(userId, taskId));
    }
}
