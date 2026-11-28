package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
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
public class DeleteTraceTool extends AbstractAiTool {

    private final VerificationService verificationService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public DeleteTraceTool(VerificationService verificationService,
                           ObjectMapper objectMapper,
                           AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.verificationService = verificationService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "delete_trace";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("traceId", Map.of("type", "integer", "description", "Verification trace ID."));
        properties.put("confirmed", Map.of(
                "type", "boolean",
                "description", "Use false to preview the trace. Use true only in a later turn after explicit user confirmation."
        ));
        properties.put("impactToken", Map.of(
                "type", "string",
                "description", "Required with confirmed=true. Copy the opaque impactToken from the latest preview."
        ));
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object",
                properties,
                List.of("traceId", "confirmed")
        );

        return LlmToolSpec.of(getName(), "Preview or, after explicit two-turn confirmation, delete a saved verification trace.", schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);

            requireOnlyFields(args, "arguments", Set.of("traceId", "confirmed", "impactToken"));
            long traceId = positiveLongArg(args, "traceId");

            TraceDto trace = verificationService.getTrace(userId, traceId);
            Map<String, Object> previewSummary = previewSummary(traceId, trace);
            String displayTarget = "Formal counterexample: " + trace.getCreatedAt() + " · "
                    + safeList(trace.getStates()).size() + " states";
            boolean confirmed = booleanArg(args, "confirmed", false);
            return AiTaskToolSupport.withDeletionConfirmation(this, args, confirmed, userId, destructiveActionGuard,
                    new AiTaskToolSupport.DeletionPreview(traceId, displayTarget, previewSummary, previewSummary,
                            "No changes were made. Explicit user confirmation is required before deleting this saved verification trace."), () -> {
                verificationService.deleteTrace(userId, traceId);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("traceId", traceId);
                body.put("violatedSpecification", ModelTraceToolPresenter.violatedSpecification(trace));
                body.put("deleted", true);
                body.put("message", "Trace deleted.");
                return successJson(body);
            });
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("delete_trace busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("delete_trace business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("delete_trace failed", e);
            return errorJson("Failed to delete trace.", "INTERNAL_ERROR", 500);
        }
    }

    private Map<String, Object> previewSummary(long traceId, TraceDto trace) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("traceId", traceId);
        preview.put("violatedSpecification", ModelTraceToolPresenter.violatedSpecification(trace));
        preview.put("stateCount", safeList(trace.getStates()).size());
        return preview;
    }
}
