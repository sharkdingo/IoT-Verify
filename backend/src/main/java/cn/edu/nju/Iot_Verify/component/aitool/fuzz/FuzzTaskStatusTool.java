package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class FuzzTaskStatusTool extends AbstractAiTool {

    private final FuzzService fuzzService;

    public FuzzTaskStatusTool(FuzzService fuzzService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.fuzzService = fuzzService;
    }

    @Override
    public String getName() {
        return "fuzz_task_status";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(),
                "Query an async counterexample-search task status and progress by taskId, waiting briefly for active work. PENDING or RUNNING means keep waiting. A completed task exposes its runId for reading findings via get_fuzz_run.",
                AiTaskToolSupport.statusSchema("Counterexample-search task ID returned by fuzz_model_async."));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return AiTaskToolSupport.status(this, argsJson, "counterexample-search",
                "Counterexample-search status waiting stopped; the search task was not cancelled.",
                taskId -> fuzzService.getTask(userId, taskId),
                (body, taskId, task, progress) -> body.put("task", task));
    }
}
