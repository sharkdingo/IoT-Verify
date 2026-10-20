package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.ModelTraceToolPresenter;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
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

@Slf4j
@Component
public class GetTraceTool extends AbstractAiTool {

    private final VerificationService verificationService;

    public GetTraceTool(VerificationService verificationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
    }

    @Override
    public String getName() {
        return "get_trace";
    }

    @Override
    public LlmToolSpec getDefinition() {
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object",
                Map.of(
                        "traceId", Map.of("type", "integer", "minimum", 1, "description", "Exact traceId from get_verification_run counterexamples or list_traces. Never copy runId or taskId here."),
                        "stateOffset", Map.of("type", "integer",
                                "description", "Non-negative zero-based state-sequence offset (default 0)."),
                        "stateLimit", Map.of("type", "integer",
                                "description", "Maximum states to return, 1-10 (default 10). The byte budget may return fewer; continue from nextStateOffset."),
                        "includePropertyLabels", Map.of("type", List.of("boolean", "null"), "description",
                                "Include state/content trust and privacy labels (omitted/null defaults to false). Set true when interpreting label-based properties or attacks; state, variable and environment values are always included.")),
                List.of("traceId")
        );

        return LlmToolSpec.of(getName(),
                "Get a saved verification trace by traceId with a bounded state window. The default value view includes every device state, variable, environment value, compromised flag and loop boundary; use it for trigger timing and liveness analysis. Each state begins with valueSnapshot, a small same-state view of device states and shared environment values. Read it with stateIndex and make a compact table before explaining temporal causes; never merge changes from different indices or infer duration from one snapshot. Full variables, reported readings and labels remain in the detailed fields. Set includePropertyLabels=true only when inspecting state/content source or sensitivity labels, not merely a state/value prohibition; propertyLabelsIncluded identifies the view. In the label view, propertyLabelsFromStateIndex reuses the identical trust/privacy lists for the same device and mode in that earlier state of this returned page. Changed labels and every page's first state carry full lists; resolve references before interpreting labels. changesFromPreviousState gives exact observed changes from the named predecessor. When hasMoreStates is true, read nextStateOffset before claiming a full trajectory or explaining a liveness cycle. loopStart and loopBack mark an infinite cycle; for liveness specifications (templateId 2, 5, 6) the cycle itself is the violation. loopBack repeats loopStart, not necessarily its preceding state. Rely on the flags rather than value comparison; neither a one-state nor a longer cycle is a truncated path.",
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

            requireOnlyFields(args, "arguments", Set.of("traceId", "stateOffset", "stateLimit", "includePropertyLabels"));
            long traceId = positiveLongArg(args, "traceId");
            int stateOffset = intArgInRange(args, "stateOffset", 0, 0,
                    ModelTraceToolPresenter.MAX_STATE_OFFSET);
            int stateLimit = intArgInRange(args, "stateLimit",
                    ModelTraceToolPresenter.DEFAULT_STATE_LIMIT, 1,
                    ModelTraceToolPresenter.MAX_STATE_LIMIT);
            boolean includePropertyLabels = booleanArg(args, "includePropertyLabels", false);

            TraceDto trace = verificationService.getTrace(userId, traceId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("traceId", traceId);
            body.put("violatedSpecification", ModelTraceToolPresenter.violatedSpecification(trace));
            body.put("modelComplete", trace.getModelComplete());
            body.put("disabledRuleCount", trace.getDisabledRuleCount());
            body.put("skippedSpecCount", trace.getSkippedSpecCount());
            body.put("generationIssues", trace.getGenerationIssues());
            body.put("propertyLabelsIncluded", includePropertyLabels);
            ModelTraceToolPresenter.putStateWindow(
                    body, trace.getStates(), stateOffset, stateLimit, includePropertyLabels);
            body.put("isAttack", trace.getAttack());
            body.put("attackBudget", trace.getAttackBudget());
            body.put("enablePrivacy", trace.getEnablePrivacy());
            body.put("modelSemantics", trace.getModelSemantics());
            body.put("modelSnapshot", trace.getModelSnapshot());
            body.put("createdAt", trace.getCreatedAt());
            String message = Boolean.FALSE.equals(trace.getModelComplete())
                    ? "Counterexample trace loaded, but its source verification used an incomplete generated model."
                    : "Counterexample trace loaded from its saved model snapshot.";
            body.put("message", message);
            return readOnlySuccessJson(body, message);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("get_trace busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("get_trace business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("get_trace failed", e);
            return errorJson("Failed to get trace.", "INTERNAL_ERROR", 500);
        }
    }
}
