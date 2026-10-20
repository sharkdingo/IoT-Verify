package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Returns formal run semantics without flooding the model with raw NuSMV stdout. */
@Slf4j
@Component
public class GetVerificationRunTool extends AbstractAiTool {

    private final VerificationService verificationService;

    public GetVerificationRunTool(VerificationService verificationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
    }

    @Override
    public String getName() {
        return "get_verification_run";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(),
                "Read one completed formal-verification run with its per-spec conclusions, completeness, model snapshot, generation issues, and exact counterexample references. Use an available counterexamples[].traceId with get_trace or fix_violation; runId is not traceId. Raw NuSMV stdout is intentionally omitted.",
                new FunctionParameterSchema("object", Map.of(
                        "runId", Map.of("type", "integer", "description", "Run ID from list_verification_runs.")),
                        List.of("runId")));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("runId"));
            long runId = positiveLongArg(args, "runId");
            VerificationRunDto run = verificationService.getRun(userId, runId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Formal-verification run loaded. Interpret the conclusion together with modelComplete and generationIssues.");
            response.put("runId", run.getId());
            response.put("initiator", run.getInitiator());
            response.put("createdAt", run.getCreatedAt());
            response.put("completedAt", run.getCompletedAt());
            response.put("processingTimeMs", run.getProcessingTimeMs());
            response.put("outcome", run.getOutcome());
            response.put("modelComplete", run.getModelComplete());
            response.put("violatedSpecCount", run.getViolatedSpecCount());
            response.put("counterexampleCount", run.getCounterexampleCount());
            response.put("counterexamples", safeList(run.getCounterexamples()).stream()
                    .map(trace -> VerificationToolPresenter.traceSummary(trace, run.getId(), true)).toList());
            response.put("disabledRuleCount", run.getDisabledRuleCount());
            response.put("skippedSpecCount", run.getSkippedSpecCount());
            response.put("modelSemantics", run.getModelSemantics());
            response.put("modelSnapshot", run.getModelSnapshot());
            response.put("generationIssues", safeList(run.getGenerationIssues()));
            response.put("specResults", VerificationToolPresenter.specResults(run.getSpecResults()));
            response.put("checkLogCount", safeList(run.getCheckLogs()).size());
            return readOnlySuccessJson(response, "Verification run loaded.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("get_verification_run failed", e);
            return errorJson("Failed to load verification run.", "INTERNAL_ERROR", 500);
        }
    }
}
