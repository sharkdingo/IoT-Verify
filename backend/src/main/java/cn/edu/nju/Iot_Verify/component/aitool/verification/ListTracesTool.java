package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ListTracesTool extends AbstractAiTool {

    private final VerificationService verificationService;

    public ListTracesTool(VerificationService verificationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
    }

    @Override
    public String getName() {
        return "list_traces";
    }

    @Override
    public LlmToolSpec getDefinition() {
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", Collections.emptyMap(), Collections.emptyList()
        );

        return LlmToolSpec.of(getName(), "List all saved verification counterexample traces. " +
                "Traces are automatically saved when verification finds property violations. " +
                "Each trace shows a sequence of states leading to the violation.", schema);
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

            List<VerificationRunSummaryDto> runs = verificationService.getRuns(userId);
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (VerificationRunSummaryDto run : runs != null ? runs : List.<VerificationRunSummaryDto>of()) {
                if (run == null || run.getCounterexamples() == null) continue;
                for (TraceSummaryDto trace : run.getCounterexamples()) {
                    if (trace == null) continue;
                    boolean available = Boolean.TRUE.equals(run.getDataAvailable())
                            && Boolean.TRUE.equals(trace.getDataAvailable());
                    Map<String, Object> summary = VerificationToolPresenter.traceSummary(
                            trace, run.getId(), Boolean.TRUE.equals(run.getDataAvailable()));
                    if (available) {
                        summary.put("modelComplete", run.getModelComplete());
                        summary.put("disabledRuleCount", run.getDisabledRuleCount());
                        summary.put("skippedSpecCount", run.getSkippedSpecCount());
                        summary.put("generationIssues", run.getGenerationIssues());
                    }
                    summaries.add(summary);
                }
            }
            List<Map<String, Object>> traces = List.copyOf(summaries);
            if (traces.isEmpty()) {
                return readOnlySuccessJson(Map.of(
                        "message", "No verification traces found. Traces are created when verification detects property violations.",
                        "count", 0
                ), "No verification traces found.");
            }

            long unavailableCount = traces.stream()
                    .filter(trace -> !Boolean.TRUE.equals(trace.get("dataAvailable")))
                    .count();

            return readOnlySuccessJson(Map.of(
                    "message", "Found " + traces.size() + " saved counterexample trace(s); "
                            + unavailableCount + " unavailable due to stored-data errors.",
                    "count", traces.size(),
                    "availableCount", traces.size() - unavailableCount,
                    "unavailableCount", unavailableCount,
                    "traces", traces
            ), "Saved counterexample traces loaded.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("list_traces busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("list_traces business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("list_traces failed", e);
            return errorJson("Failed to list traces.", "INTERNAL_ERROR", 500);
        }
    }
}
