package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationRequestDto;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SimulateModelAsyncTool extends AbstractAiTool {

    private final SimulationService simulationService;

    public SimulateModelAsyncTool(SimulationService simulationService,
                                  ObjectMapper objectMapper) {
        super(objectMapper);
        this.simulationService = simulationService;
    }

    @Override
    public String getName() {
        return "simulate_model_async";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("steps", Map.of("type", "integer", "description", "Number of simulation steps (1-100, rejected if outside range). Default 10."));
        props.put("attackMode", Map.of("type", "string", "enum", List.of("none", "exact"), "description", "Per-run attack selection. Simulation never chooses attack points randomly. Default none."));
        props.put("attackPoints", attackPointsSchema(false));
        props.put("enablePrivacy", Map.of("type", "boolean", "description", "Track public/private sensitivity labels through automation chains. This is not access control or encryption. Default false."));

        FunctionParameterSchema schema = new FunctionParameterSchema("object", props, Collections.emptyList());

        return LlmToolSpec.of(getName(), "Submit an async NuSMV simulation task. Returns its authoritative current task status, progress, frozen model snapshot, and taskId for later polling; acceptance does not mean simulation completed.", schema);
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
            requireOnlyFields(args, "arguments", Set.of(
                    "steps", "attackMode", "attackPoints", "enablePrivacy"));
            int steps = intArgInRange(args, "steps", 10, 1, 100);
            AttackScenarioDto attackScenario = attackScenarioArg(args, false);
            boolean enablePrivacy = booleanArg(args, "enablePrivacy", false);

            SimulationRequestDto request = new SimulationRequestDto();
            request.setSteps(steps);
            request.setAttackScenario(attackScenario);
            request.setEnablePrivacy(enablePrivacy);
            Long taskId = simulationService.submitSimulation(userId, request);
            if (taskId == null || taskId <= 0) {
                throw new IllegalStateException("Simulation submission returned no usable task id");
            }
            try {
                var task = simulationService.getTask(userId, taskId);
                if (task == null) {
                    throw new IllegalStateException("Accepted simulation task was not readable");
                }

                Map<String, Object> response = new java.util.LinkedHashMap<>();
                response.put("message", "Simulation task accepted. Its current status is authoritative; completion is not implied.");
                response.put("taskAccepted", true);
                response.put("taskId", taskId);
                response.put("taskStatus", task.getStatus());
                response.put("progress", task.getProgress());
                response.put("requestedSteps", task.getRequestedSteps());
                response.put("isAttack", task.getIsAttack());
                response.put("attackBudget", task.getAttackBudget());
                response.put("attackScenario", attackScenario);
                response.put("enablePrivacy", task.getEnablePrivacy());
                response.put("modelSnapshot", task.getModelSnapshot());
                response.put("modelSemantics", task.getModelSemantics());
                return acceptedAsyncTaskJson(
                        response, taskId, "simulate_task_status");
            } catch (Exception e) {
                log.error("Simulation task {} was accepted, but its status response is unavailable",
                        taskId, e);
                return acceptedAsyncTaskResultUnavailable(taskId, "simulate_task_status");
            }
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (AsyncTaskDispatchOutcomeUnknownException e) {
            log.error("Simulation task {} dispatch outcome is unknown", e.getTaskId(), e);
            return asyncTaskDispatchOutcomeUnknown(e.getTaskId(), "simulate_task_status");
        } catch (ServiceUnavailableException e) {
            log.warn("simulate_model_async busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (SmvGenerationException e) {
            log.warn("simulate_model_async generation failed [{}]: {}", e.getErrorCategory(), e.getMessage());
            return errorJson(e.getMessage(),
                    "SMV_GENERATION_ERROR",
                    500,
                    Map.of("errorCategory", e.getErrorCategory()));
        } catch (BaseException e) {
            log.warn("simulate_model_async business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("simulate_model_async failed", e);
            return errorJson("Failed to start simulation task.",
                    "INTERNAL_ERROR", 500);
        }
    }

}
