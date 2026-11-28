package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class CancelFuzzTaskTool extends AbstractAiTool {

    private final FuzzService fuzzService;

    public CancelFuzzTaskTool(FuzzService fuzzService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.fuzzService = fuzzService;
    }

    @Override
    public String getName() {
        return "cancel_fuzz_task";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(), "Cancel an async counterexample-search task by taskId.",
                AiTaskToolSupport.cancelSchema("Counterexample-search task ID."));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return AiTaskToolSupport.cancel(this, argsJson, "counterexample-search", "search", "run for findings",
                taskId -> fuzzService.cancelTask(userId, taskId));
    }
}
