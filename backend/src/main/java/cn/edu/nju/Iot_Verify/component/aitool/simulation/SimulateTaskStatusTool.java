package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskStatusWaiter;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.SimulationService;
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
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object",
                Map.of("taskId", Map.of("type", "integer", "description", "Simulation task ID returned by simulate_model_async."),
                        "waitMs", AiTaskStatusWaiter.waitMsSchema()),
                List.of("taskId")
        );

        return LlmToolSpec.of(getName(),
                "Query async simulation task status and progress by taskId, waiting briefly for active work. PENDING or RUNNING means keep waiting. A completed saved trajectory exposes its simulationId for get_simulation_trace. Execution logs are intentionally omitted.",
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
            SimulationTaskDto task = AiTaskStatusWaiter.await(
                    () -> simulationService.getTask(userId, taskId), SimulationTaskDto::getStatus, waitMs);
            int progress = task.getProgress();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("progress", progress);
            body.put("task", taskProjection(task, progress));
            if (task.getSimulationTraceId() != null && task.getSimulationTraceId() > 0) {
                body.put("simulationId", task.getSimulationTraceId());
                body.put("nextTool", "get_simulation_trace");
            }
            return readOnlySuccessJson(body, "Simulation task status retrieved.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (CancellationException e) {
            return errorJson("Simulation status waiting stopped; the simulation task was not cancelled.",
                    "TASK_STATUS_WAIT_CANCELLED", 499);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorJson("Simulation status waiting stopped; the simulation task was not cancelled.",
                    "TASK_STATUS_WAIT_CANCELLED", 499);
        } catch (ServiceUnavailableException e) {
            log.warn("simulate_task_status busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("simulate_task_status business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("simulate_task_status failed", e);
            return errorJson("Failed to query simulation task.",
                    "INTERNAL_ERROR", 500);
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
