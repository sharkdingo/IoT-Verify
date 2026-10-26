package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceSummaryDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ListSimulationTracesTool extends AbstractAiTool {

    private final SimulationService simulationService;
    private final ChatExecutionConfig chatExecutionConfig;

    public ListSimulationTracesTool(SimulationService simulationService,
                                    ObjectMapper objectMapper, ChatExecutionConfig chatExecutionConfig) {
        super(objectMapper);
        this.simulationService = simulationService;
        this.chatExecutionConfig = chatExecutionConfig;
    }

    @Override
    public String getName() {
        return "list_simulation_traces";
    }

    @Override
    public LlmToolSpec getDefinition() {
        FunctionParameterSchema schema = new FunctionParameterSchema("object", Map.of(
                "offset", Map.of("type", "integer", "minimum", 0,
                        "description", "Zero-based saved-run summary offset (default 0)."),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                        "description", "Maximum summaries to return (default 20). Continue from nextOffset while hasMore is true.")),
                Collections.emptyList());

        return LlmToolSpec.of(getName(), "Page saved simulation run summaries for the current user. Continue from nextOffset while hasMore is true. "
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
            requireOnlyFields(args, "arguments", Set.of("offset", "limit"));
            int offset = intArgInRange(args, "offset", 0, 0, Integer.MAX_VALUE);
            int limit = intArgInRange(args, "limit", 20, 1, 100);

            List<SimulationTraceSummaryDto> traces = simulationService.getUserSimulations(userId);
            if (traces == null) {
                traces = List.of();
            }
            int totalCount = traces.size();
            int start = Math.min(offset, totalCount);
            int end = (int) Math.min(totalCount, (long) start + limit);
            List<Map<String, Object>> summaries = new ArrayList<>(traces.subList(start, end).stream().map(t -> {
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
            }).toList());
            while (true) {
                int returned = summaries.size();
                long unavailable = summaries.stream()
                        .filter(trace -> !Boolean.TRUE.equals(trace.get("dataAvailable"))).count();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("message", totalCount == 0
                        ? "No simulation traces found. Run and save a simulation first."
                        : "Found " + totalCount + " saved model-trace simulation run(s); showing " + returned
                                + " summary row(s). Simulation does not check specifications. "
                                + "modelSnapshot.specificationCount describes the simulation model, not whether the saved Board has specifications. "
                                + "These summaries contain no state sequence; read get_simulation_trace before explaining state changes.");
                body.put("count", returned);
                body.put("totalCount", totalCount);
                body.put("availableCount", returned - unavailable);
                body.put("unavailableCount", unavailable);
                body.put("offset", offset);
                body.put("hasMore", (long) start + returned < totalCount);
                if ((long) start + returned < totalCount) body.put("nextOffset", start + returned);
                body.put("traces", List.copyOf(summaries));
                body.put("resultAvailable", true);
                body.put("resultStatus", "SUCCESS");
                String response = readOnlySuccessJson(body, "Saved simulation runs loaded.");
                if (response.getBytes(StandardCharsets.UTF_8).length <= chatExecutionConfig.getMaxToolResultBytes()) {
                    return response;
                }
                if (summaries.size() <= 1) {
                    Map<String, Object> unavailableResult = new LinkedHashMap<>();
                    unavailableResult.put("resultStatus", "RESULT_UNAVAILABLE");
                    unavailableResult.put("resultAvailable", false);
                    unavailableResult.put("mutationMayHaveCommitted", false);
                    unavailableResult.put("errorCode", "TOOL_RESULT_TOO_LARGE");
                    unavailableResult.put("message", "A simulation summary exceeds the chat result limit; read the run by simulationId, then continue at nextOffset.");
                    unavailableResult.put("simulationId", summaries.get(0).get("simulationId"));
                    unavailableResult.put("totalCount", totalCount);
                    unavailableResult.put("offset", offset);
                    unavailableResult.put("nextOffset", start + 1);
                    unavailableResult.put("hasMore", (long) start + 1 < totalCount);
                    return readOnlySuccessJson(unavailableResult, "Simulation summary too large.");
                }
                summaries.remove(summaries.size() - 1);
            }
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
