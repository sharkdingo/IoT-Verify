package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResponseHelper;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResponseHelper.TracePagePresentation;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceSummaryDto;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ListSimulationTracesTool extends AbstractAiTool {

    private static final TracePagePresentation PRESENTATION = new TracePagePresentation(
            "No simulation traces found. Run and save a simulation first.",
            "model-trace simulation run(s)",
            " Simulation does not check specifications. "
                    + "modelSnapshot.specificationCount describes the simulation model, not whether the saved Board has specifications. "
                    + "These summaries contain no state sequence; read get_simulation_trace before explaining state changes.", List.of("simulationId"),
            "A simulation summary exceeds the chat result limit; read the run by simulationId, then continue at nextOffset.");

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
        FunctionParameterSchema schema = AiToolResponseHelper.traceSummaryPageSchema(
                "Zero-based saved-run summary offset (default 0).");

        return LlmToolSpec.of(getName(), "Page saved simulation run summaries for the current user. Continue from nextOffset while hasMore is true. "
                + "Simulation does not check specifications; modelSnapshot.specificationCount is the run's "
                + "scope, not the saved Board's specification count. Use get_simulation_trace for state changes.", schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            return AiToolResponseHelper.traceSummaryPage(this, argsJson, chatExecutionConfig,
                    () -> simulationService.getUserSimulations(userId), this::traceSummary, PRESENTATION);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (Exception e) {
            return readOnlyToolFailure(log, "list_simulation_traces", e,
                    "Failed to list simulation traces.");
        }
    }

    private Map<String, Object> traceSummary(SimulationTraceSummaryDto trace) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("simulationId", trace.getId());
        boolean available = Boolean.TRUE.equals(trace.getDataAvailable());
        s.put("dataAvailable", available);
        s.put("createdAt", trace.getCreatedAt());
        if (!available) {
            s.put("unavailableReasonCode", trace.getUnavailableReasonCode());
            return s;
        }
        s.put("requestedSteps", trace.getRequestedSteps());
        s.put("steps", trace.getSteps());
        s.put("modelComplete", trace.isModelComplete());
        s.put("disabledRuleCount", trace.getDisabledRuleCount());
        s.put("generationIssues", trace.getGenerationIssues());
        s.put("isAttack", trace.getAttack());
        s.put("attackBudget", trace.getAttackBudget());
        s.put("enablePrivacy", trace.getEnablePrivacy());
        s.put("modelSnapshot", trace.getModelSnapshot());
        return s;
    }
}
