package cn.edu.nju.Iot_Verify.component.model;

import cn.edu.nju.Iot_Verify.dto.simulation.SimulationRequestDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import cn.edu.nju.Iot_Verify.util.JsonPointerPath;
/** Strict JSON boundary for requests whose fields define the checked model. */
@Component
@RequiredArgsConstructor
public class ModelRequestParser {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public VerificationRequestDto parseVerification(JsonNode body) {
        return parse(body, VerificationRequestDto.class, "Verification");
    }

    public SimulationRequestDto parseSimulation(JsonNode body) {
        return parse(body, SimulationRequestDto.class, "Simulation");
    }

    /**
     * Fields that describe the scene rather than the run. They are serialized on the way out (the
     * persisted run snapshot and the fix path read them back) but the server owns them: it reads the
     * scene from the caller's board. Jackson would silently ignore them on input, and a silently
     * different run is exactly what this parser exists to prevent, so a stale client that still
     * sends a scene gets told rather than quietly given a run it did not describe.
     */
    private static final List<String> SERVER_OWNED_SCENE_FIELDS = List.of(
            "devices", "rules", "specs", "environmentVariables", "playbackNodes");

    private <T> T parse(JsonNode body, Class<T> type, String requestKind) {
        if (body == null || !body.isObject()) {
            throw new BadRequestException(requestKind + " request must be a JSON object.");
        }
        for (String sceneField : SERVER_OWNED_SCENE_FIELDS) {
            if (body.has(sceneField)) {
                throw new BadRequestException("Field '" + sceneField + "' is not accepted: the "
                        + requestKind.toLowerCase() + " scene is read from your saved board, so a run "
                        + "always describes the board you saved. Send only run parameters.");
            }
        }

        final T request;
        try {
            request = objectMapper.readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
                    .readValue(objectMapper.treeAsTokens(body));
        } catch (UnrecognizedPropertyException exception) {
            throw new BadRequestException("Unknown field '" + exception.getPropertyName() + "' at '"
                    + JsonPointerPath.of(exception) + "'. The request was rejected because ignoring it could change "
                    + "what the model checks.");
        } catch (JsonProcessingException exception) {
            String path = exception instanceof JsonMappingException mappingException
                    ? JsonPointerPath.of(mappingException)
                    : "request";
            throw new BadRequestException("Invalid " + requestKind.toLowerCase() + " value at '" + path
                    + "': " + exception.getOriginalMessage() + ".");
        } catch (IOException exception) {
            throw new BadRequestException(requestKind + " request JSON could not be read.");
        }

        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            // Preserve the REST contract: DTO/shape violations are HTTP 400, while
            // model-semantic validation performed by the services remains HTTP 422.
            throw new ConstraintViolationException(violations);
        }
        return request;
    }

}
