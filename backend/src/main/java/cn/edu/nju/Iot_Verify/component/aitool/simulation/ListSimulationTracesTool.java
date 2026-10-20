package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceSummaryDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
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

@Slf4j
@Component
public class ListSimulationTracesTool extends AbstractAiTool {

    private final SimulationService simulationService;

    public ListSimulationTracesTool(SimulationService simulationService,
                                    ObjectMapper objectMapper) {
        super(objectMapper);
        this.simulationService = simulationService;
    }

    @Override
    public String getName() {
        return "list_simulation_traces";
    }

    @Override
    public LlmToolSpec getDefinition() {
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", Collections.emptyMap(), Collections.emptyList()
        );

        return LlmToolSpec.of(getName(), "List saved simulation run summaries for the current user. "
                + "Simulation does not check specifications; modelSnapshot.specificationCount is the run's "
                + "scope, not the saved Board's specification count. Use get_simulation_trace for state changes.", schema);
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
            requireOnlyFields(args, "arguments", Collections.emptySet());

            List<SimulationTraceSummaryDto> traces = simulationService.getUserSimulations(userId);
            if (traces == null) {
                traces = List.of();
            }
            if (traces.isEmpty()) {
                return readOnlySuccessJson(Map.of(
                        "message", "No simulation traces found. Run and save a simulation first.",
                        "count", 0
                ), "No simulation traces found.");
            }

            List<Map<String, Object>> summaries = traces.stream().map(t -> {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("simulationId", t.getId());
                boolean available = Boolean.TRUE.equals(t.getDataAvailable());
                s.put("dataAvailable", available);
                s.put("createdAt", t.getCreatedAt());
                if (!available) {
                    s.put("unavailableReasonCode", t.getUnavailableReasonCode());
                    return s;
                }
                s.put("requestedSteps", t.getRequestedSteps());
                s.put("steps", t.getSteps());
                s.put("modelComplete", t.isModelComplete());
                s.put("disabledRuleCount", t.getDisabledRuleCount());
                s.put("generationIssues", t.getGenerationIssues());
                s.put("isAttack", t.getAttack());
                s.put("attackBudget", t.getAttackBudget());
                s.put("enablePrivacy", t.getEnablePrivacy());
                s.put("modelSnapshot", t.getModelSnapshot());
                return s;
            }).toList();
            long unavailableCount = traces.stream()
                    .filter(trace -> !Boolean.TRUE.equals(trace.getDataAvailable()))
                    .count();

            return readOnlySuccessJson(Map.of(
                    "message", "Found " + traces.size() + " saved model-trace simulation run(s); "
                            + unavailableCount + " unavailable due to stored-data errors. "
                            + "Simulation does not check specifications. modelSnapshot.specificationCount "
                            + "describes the simulation model, not whether the saved Board has specifications. "
                            + "These summaries contain no state sequence; read get_simulation_trace before explaining state changes.",
                    "count", traces.size(),
                    "availableCount", traces.size() - unavailableCount,
                    "unavailableCount", unavailableCount,
                    "traces", summaries
            ), "Saved simulation runs loaded.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("list_simulation_traces busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("list_simulation_traces business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("list_simulation_traces failed", e);
            return errorJson("Failed to list simulation traces.", "INTERNAL_ERROR", 500);
        }
    }
}
