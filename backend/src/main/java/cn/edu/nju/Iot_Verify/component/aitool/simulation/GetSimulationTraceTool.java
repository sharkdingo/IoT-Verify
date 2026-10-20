package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.ModelTraceToolPresenter;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceDto;
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

@Slf4j
@Component
public class GetSimulationTraceTool extends AbstractAiTool {

    private final SimulationService simulationService;

    public GetSimulationTraceTool(SimulationService simulationService,
                                   ObjectMapper objectMapper) {
        super(objectMapper);
        this.simulationService = simulationService;
    }

    @Override
    public String getName() {
        return "get_simulation_trace";
    }

    @Override
    public LlmToolSpec getDefinition() {
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object",
                Map.of(
                        "simulationId", Map.of("type", "integer", "description", "Simulation trace ID."),
                        "stateOffset", Map.of("type", "integer",
                                "description", "Non-negative zero-based state-sequence offset (default 0)."),
                        "stateLimit", Map.of("type", "integer",
                                "description", "Maximum states to return, 1-10 (default 10). The byte budget may return fewer; continue from nextStateOffset.")
                ),
                List.of("simulationId")
        );

        return LlmToolSpec.of(getName(),
                "Get a saved simulation trace by simulationId with a bounded state window. "
                        + "Page with stateOffset and stateLimit; use stateCount and nextStateOffset to inspect another window. "
                        + "Simulation does not check specifications; modelSnapshot.specificationCount is the run's scope, "
                        + "not the saved Board's specification count.",
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

            requireOnlyFields(args, "arguments", Set.of(
                    "simulationId", "stateOffset", "stateLimit"));
            long simulationId = positiveLongArg(args, "simulationId");
            int stateOffset = intArgInRange(args, "stateOffset", 0, 0,
                    ModelTraceToolPresenter.MAX_STATE_OFFSET);
            int stateLimit = intArgInRange(args, "stateLimit",
                    ModelTraceToolPresenter.DEFAULT_STATE_LIMIT, 1,
                    ModelTraceToolPresenter.MAX_STATE_LIMIT);

            SimulationTraceDto trace = simulationService.getSimulation(userId, simulationId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("simulationId", simulationId);
            body.put("requestedSteps", trace.getRequestedSteps());
            body.put("steps", trace.getSteps());
            body.put("modelComplete", trace.isModelComplete());
            body.put("disabledRuleCount", trace.getDisabledRuleCount());
            body.put("generationIssues", trace.getGenerationIssues());
            body.put("isAttack", trace.getAttack());
            body.put("attackBudget", trace.getAttackBudget());
            body.put("enablePrivacy", trace.getEnablePrivacy());
            body.put("modelSemantics", trace.getModelSemantics());
            body.put("modelSnapshot", trace.getModelSnapshot());
            ModelTraceToolPresenter.putStateWindow(
                    body, trace.getStates(), stateOffset, stateLimit);
            body.put("createdAt", trace.getCreatedAt());
            String message = trace.isModelComplete()
                    ? "Saved model-trace simulation loaded. It is one possible model trajectory, not a physical-home prediction."
                    : "Saved model-trace simulation loaded from an incomplete generated model; inspect generationIssues before interpreting it.";
            message += " Simulation does not check specifications. modelSnapshot.specificationCount describes "
                    + "the simulation model, not whether the saved Board has specifications. Use formal verification for specification outcomes.";
            body.put("message", message);
            return readOnlySuccessJson(body, message);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("get_simulation_trace busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("get_simulation_trace business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("get_simulation_trace failed", e);
            return errorJson("Failed to get simulation trace.", "INTERNAL_ERROR", 500);
        }
    }
}
