package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import cn.edu.nju.Iot_Verify.util.JsonPointerPath;

/** Strict request-boundary parser for the destructive board replacement command. */
@Component
@RequiredArgsConstructor
public class BoardBatchRequestParser {

    private static final Set<String> INTERNAL_TEMPLATE_FIELDS = Set.of("id", "defaultTemplate");

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final DeviceTemplateSchemaValidator templateSchemaValidator;

    public BoardBatchDto parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BadRequestException("Board batch request must be a JSON object; no board data was changed.");
        }
        if (body.has("createdTemplates")) {
            throw new BadRequestException("Field 'createdTemplates' is response-only and cannot be imported; "
                    + "no board data was changed.");
        }

        validateRawTemplateSnapshots(body.path("templateSnapshots"));

        final BoardBatchDto batch;
        try {
            batch = objectMapper.readerFor(BoardBatchDto.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(objectMapper.treeAsTokens(body));
        } catch (UnrecognizedPropertyException exception) {
            String path = JsonPointerPath.of(exception);
            throw new BadRequestException("Unknown field '" + exception.getPropertyName() + "' at '"
                    + path + "'; importing it would lose data, so no board data was changed.",
                    Map.of(path, "Unknown field '" + exception.getPropertyName() + "'"));
        } catch (JsonProcessingException exception) {
            String path = exception instanceof JsonMappingException mappingException
                    ? JsonPointerPath.of(mappingException)
                    : "request";
            throw new BadRequestException("Invalid board batch value at '" + path + "': "
                    + "Value does not match the declared JSON field type. No board data was changed.",
                    Map.of(path, "Value does not match the declared JSON field type"));
        } catch (IOException exception) {
            throw new BadRequestException("Board batch JSON could not be read; no board data was changed.");
        }

        Map<String, String> errors = new LinkedHashMap<>();
        validator.validate(batch).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .forEach(violation -> addViolation(errors, violation));
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return batch;
    }

    private void validateRawTemplateSnapshots(JsonNode snapshots) {
        if (snapshots == null || snapshots.isMissingNode() || snapshots.isNull() || !snapshots.isArray()) {
            return;
        }
        for (int index = 0; index < snapshots.size(); index++) {
            JsonNode snapshot = snapshots.get(index);
            if (snapshot == null || !snapshot.isObject()) {
                continue;
            }
            for (String field : INTERNAL_TEMPLATE_FIELDS) {
                if (snapshot.has(field)) {
                    throw new BadRequestException("Field 'templateSnapshots[" + index + "]." + field
                            + "' is an internal/template-catalog field and is not portable scene semantics; "
                            + "no board data was changed.");
                }
            }
            String name = snapshot.path("name").isTextual() ? snapshot.path("name").asText() : null;
            try {
                templateSchemaValidator.validateRawManifest(name, snapshot.get("manifest"));
            } catch (BadRequestException error) {
                Map<String, String> errors = new LinkedHashMap<>();
                String prefix = "templateSnapshots[" + index + "].";
                if (error.getErrors().isEmpty()) {
                    errors.put(prefix + "manifest", error.getMessage());
                } else {
                    error.getErrors().forEach((field, reason) -> errors.put(prefix + field, reason));
                }
                throw new BadRequestException(error.getMessage(), errors);
            }
        }
    }

    private void addViolation(Map<String, String> errors, ConstraintViolation<BoardBatchDto> violation) {
        String path = violation.getPropertyPath().toString();
        errors.merge(path, violation.getMessage(), (left, right) -> left + "; " + right);
    }

}
