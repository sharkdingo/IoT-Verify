package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lists every formal conclusion, including satisfied runs that have no trace. */
@Slf4j
@Component
public class ListVerificationRunsTool extends AbstractAiTool {

    private static final String INTERPRETATION = "These summaries report formula outcomes only and omit response-trigger reachability. "
            + "SATISFIED does not establish preserved functionality. Use get_verification_run to inspect "
            + "per-specification outcomes and antecedentReachability.";

    private final VerificationService verificationService;

    public ListVerificationRunsTool(VerificationService verificationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
    }

    @Override
    public String getName() {
        return "list_verification_runs";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = Map.of("limit", Map.of(
                "type", "integer", "minimum", 1, "maximum", 100,
                "description", "Maximum completed runs, newest first (default 25)."));
        return LlmToolSpec.of(getName(),
                "List completed formal-verification runs, including satisfied runs with no counterexample. "
                        + INTERPRETATION + " Use list_traces only when counterexample evidence is needed.",
                new FunctionParameterSchema("object", properties, List.of()));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("limit"));
            int limit = intArgInRange(args, "limit", 25, 1, 100);
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (VerificationRunSummaryDto run : safeList(verificationService.getRuns(userId))) {
                if (run == null || summaries.size() >= limit) continue;
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("runId", run.getId());
                summary.put("dataAvailable", run.getDataAvailable());
                summary.put("initiator", run.getInitiator());
                summary.put("createdAt", run.getCreatedAt());
                summary.put("completedAt", run.getCompletedAt());
                if (Boolean.FALSE.equals(run.getDataAvailable())) {
                    summary.put("unavailableReasonCode", run.getUnavailableReasonCode());
                } else {
                    summary.put("outcome", run.getOutcome());
                    summary.put("modelComplete", run.getModelComplete());
                    summary.put("violatedSpecCount", run.getViolatedSpecCount());
                    summary.put("counterexampleCount", run.getCounterexampleCount());
                    summary.put("disabledRuleCount", run.getDisabledRuleCount());
                    summary.put("skippedSpecCount", run.getSkippedSpecCount());
                }
                summaries.add(summary);
            }
            List<Map<String, Object>> runs = List.copyOf(summaries);
            return readOnlySuccessJson(Map.of(
                    "message", runs.isEmpty()
                            ? "No completed formal-verification runs found."
                            : "Found " + runs.size() + " completed formal-verification run(s), newest first.",
                    "count", runs.size(), "runs", runs, "interpretation", INTERPRETATION),
                    "Verification runs loaded.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("list_verification_runs failed", e);
            return errorJson("Failed to list verification runs.", "INTERNAL_ERROR", 500);
        }
    }
}
