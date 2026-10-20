package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.SimulationService;
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

/** Reads the exact saved model through the same ownership checks as the download endpoints. */
@Slf4j
@Component
public class ReadRunModelTool extends AbstractAiTool {

    private static final int DEFAULT_LIMIT = 4000;
    private static final int MAX_LIMIT = 8000;
    private final VerificationService verificationService;
    private final SimulationService simulationService;

    public ReadRunModelTool(VerificationService verificationService, SimulationService simulationService,
                            ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
        this.simulationService = simulationService;
    }

    @Override
    public String getName() {
        return "read_run_model";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(),
                "Read an exact, bounded excerpt of the NuSMV model saved with a completed verification or simulation run. "
                        + "It is historical, not the current Board; this read neither generates nor verifies a model. "
                        + "Use the matching run result for its snapshot, completeness and verdict. Continue from nextOffset "
                        + "while hasMore=true before claiming the whole model was inspected.",
                new FunctionParameterSchema("object", Map.of(
                        "kind", Map.of("type", "string", "enum", List.of("verification", "simulation")),
                        "id", Map.of("type", "integer", "minimum", 1,
                                "description", "runId for verification; simulationId for simulation, from saved run results."),
                        "offset", Map.of("type", "integer", "minimum", 0,
                                "description", "Zero-based Unicode character offset, default 0."),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", MAX_LIMIT,
                                "description", "Maximum Unicode characters to return, default 4000, maximum 8000.")),
                        List.of("kind", "id")));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("kind", "id", "offset", "limit"));
            String kind = optionalEnumArg(args, "kind", "", Set.of("verification", "simulation"));
            long id = positiveLongArg(args, "id");
            int offset = intArgInRange(args, "offset", 0, 0, Integer.MAX_VALUE);
            int limit = intArgInRange(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
            String model = "verification".equals(kind)
                    ? verificationService.getRunSmvModel(userId, id)
                    : simulationService.getSimulation(userId, id).getSmvModelContent();
            if (model == null || model.isBlank()) {
                return errorJson("This saved run has no stored NuSMV model. No replacement model was generated.",
                        "NOT_FOUND", 404);
            }
            int total = model.codePointCount(0, model.length());
            if (offset > total) {
                return errorJson("offset exceeds the stored model's totalCharacters.", "VALIDATION_ERROR", 400);
            }
            int end = (int) Math.min(total, (long) offset + limit);
            int startIndex = model.offsetByCodePoints(0, offset);
            int endIndex = model.offsetByCodePoints(startIndex, end - offset);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("kind", kind);
            body.put("id", id);
            body.put("modelSource", "SAVED_RUN");
            body.put("totalCharacters", total);
            body.put("offset", offset);
            body.put("returnedCharacters", end - offset);
            body.put("hasMore", end < total);
            if (end < total) body.put("nextOffset", end);
            body.put("modelText", model.substring(startIndex, endIndex));
            body.put("message", "Exact excerpt of this saved run's generated model. It does not establish a "
                    + "current-Board or safety conclusion; read the matching run result and remaining excerpts.");
            return readOnlySuccessJson(body, "Saved model excerpt unavailable.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ResourceNotFoundException e) {
            return errorJson(e.getMessage(), "NOT_FOUND", 404);
        } catch (ServiceUnavailableException e) {
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("read_run_model failed", e);
            return errorJson("Failed to read the saved NuSMV model.", "INTERNAL_ERROR", 500);
        }
    }
}
