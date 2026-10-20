package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.ModelTraceToolPresenter;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationRequestDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationResultDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.SimulationExecutionException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SimulateModelTool extends AbstractAiTool {

    private final SimulationService simulationService;

    public SimulateModelTool(SimulationService simulationService,
                             ObjectMapper objectMapper) {
        super(objectMapper);
        this.simulationService = simulationService;
    }

    @Override
    public String getName() {
        return "simulate_model";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();

        props.put("steps", Map.of(
                "type", "integer",
                "description", "Number of simulation steps (1-100, rejected if outside range). Default 10."
        ));
        props.put("attackMode", Map.of(
                "type", "string", "enum", List.of("none", "exact"),
                "description", "Per-run attack selection. Simulation requires explicit points and never chooses attack points randomly. Default none."));
        props.put("attackPoints", attackPointsSchema(false));
        props.put("enablePrivacy", Map.of(
                "type", "boolean",
                "description", "Track public/private sensitivity labels through automation chains. This models label propagation, not access control or encryption. Default false."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, Collections.emptyList()
        );

        return LlmToolSpec.of(getName(), "Run NuSMV random simulation on the current board. " +
                "Atomically snapshots all devices, environment values, rules, and referenced templates from the board. " +
                "Returns result counts plus compact initial/final state previews. This synchronous preview is not saved; " +
                "use simulate_model_async and get_simulation_trace when a saved pageable state sequence is needed. " +
                "Requires at least one device on the board.", schema);
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

            SimulationResultDto result = simulationService.simulate(userId, request);
            if (result == null || result.getStates() == null || result.getStates().isEmpty()) {
                throw SimulationExecutionException.fromResult(result);
            }

            // Build a compact summary for chat output.
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("modelComplete", result.isModelComplete());
            summary.put("disabledRuleCount", result.getDisabledRuleCount());
            summary.put("generationIssues", result.getGenerationIssues());
            summary.put("requestedSteps", result.getRequestedSteps());
            summary.put("actualSteps", result.getSteps());
            summary.put("stateCount", result.getStates() != null ? result.getStates().size() : 0);
            summary.put("isAttack", Boolean.TRUE.equals(result.getIsAttack()));
            summary.put("attackBudget", result.getAttackBudget());
            summary.put("attackScenario", attackScenario);
            summary.put("enablePrivacy", result.isEnablePrivacy());
            summary.put("modelSemantics", result.getModelSemantics());
            summary.put("modelSnapshot", result.getModelSnapshot());
            summary.put("historyPersistence", result.getHistoryPersistence());

            List<TraceStateDto> states = result.getStates();
            List<TraceStateDto> boundaryStates = states.size() == 1
                    ? List.of(states.get(0))
                    : List.of(states.get(0), states.get(states.size() - 1));
            summary.put("statePreviewKind", "INITIAL_AND_FINAL");
            summary.put("previewedStateCount", boundaryStates.size());
            summary.put("statePreview", ModelTraceToolPresenter.states(boundaryStates));

            String message = result.isModelComplete()
                    ? "Model-trace simulation completed. This is model behavior, not a prediction of the physical home."
                    : "Model-trace simulation completed with disabled rules; the trace represents an incomplete generated model.";
            message += " This preview was not added to run history.";
            message += " It includes only the initial/final state preview; use simulate_model_async "
                    + "and get_simulation_trace for saved pageable state detail.";
            summary.put("message", message);
            return readOnlySuccessJson(summary, message);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (SimulationExecutionException e) {
            log.warn("simulate_model produced no trace [{}]: {}", e.getReasonCode(), e.getMessage());
            return errorJson(e.getMessage(), "SIMULATION_FAILED", e.getCode(), Map.of(
                    "reasonCode", e.getReasonCode(),
                    "logs", e.getLogs()
            ));
        } catch (ServiceUnavailableException e) {
            log.warn("simulate_model busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (SmvGenerationException e) {
            log.warn("simulate_model generation failed [{}]: {}", e.getErrorCategory(), e.getMessage());
            return errorJson(e.getMessage(),
                    "SMV_GENERATION_ERROR",
                    500,
                    Map.of("errorCategory", e.getErrorCategory()));
        } catch (BaseException e) {
            log.warn("simulate_model business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("simulate_model failed", e);
            return errorJson("Simulation failed.", "INTERNAL_ERROR", 500);
        }
    }

}
