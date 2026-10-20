package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskStatusWaiter;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
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
import java.util.concurrent.CancellationException;

@Slf4j
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
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object",
                Map.of("taskId", Map.of("type", "integer",
                        "description", "Counterexample-search task ID returned by fuzz_model_async."),
                        "waitMs", AiTaskStatusWaiter.waitMsSchema()),
                List.of("taskId"));
        return LlmToolSpec.of(getName(),
                "Query an async counterexample-search task status and progress by taskId, waiting briefly for active work. PENDING or RUNNING means keep waiting. A completed task exposes its runId for reading findings via get_fuzz_run.",
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
            requireOnlyFields(args, "arguments", Set.of("taskId", "waitMs"));
            long taskId = positiveLongArg(args, "taskId");
            int waitMs = taskStatusWaitMsArg(args);
            FuzzTaskDto task = AiTaskStatusWaiter.await(
                    () -> fuzzService.getTask(userId, taskId), FuzzTaskDto::getStatus, waitMs);
            int progress = task.getProgress();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("progress", progress);
            body.put("task", task);
            return readOnlySuccessJson(body, "Counterexample-search task status retrieved.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (CancellationException e) {
            return errorJson("Counterexample-search status waiting stopped; the search task was not cancelled.",
                    "TASK_STATUS_WAIT_CANCELLED", 499);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorJson("Counterexample-search status waiting stopped; the search task was not cancelled.",
                    "TASK_STATUS_WAIT_CANCELLED", 499);
        } catch (ServiceUnavailableException e) {
            log.warn("fuzz_task_status busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("fuzz_task_status business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("fuzz_task_status failed", e);
            return errorJson("Failed to query counterexample-search task.", "INTERNAL_ERROR", 500);
        }
    }
}
