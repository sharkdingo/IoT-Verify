package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.PortableSceneDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.util.JsonPointerPath;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * The one converter from the portable scene format to the internal board write contract.
 *
 * <p>Every producer of a portable scene shares it: an uploaded scene file
 * ({@code POST /api/board/scene}) and a chat-generated draft ({@code apply_scenario}). It was
 * previously implemented twice — once here for the chat tool and once in the frontend's
 * {@code api/board.ts} for file import — so the same scene could be admitted differently
 * depending on which button the user pressed. The {@code variableSource} field was dropped by
 * each copy independently, producing three separate user-visible failures before all three copies
 * agreed.</p>
 *
 * <p>{@code specIdPrefix} is the caller's provenance marker for the generated specification ids.
 * Portable scenes carry no database ids by design, so ids are minted here; the prefix records
 * which entry point minted them and keeps the two sources distinguishable in stored data.</p>
 */
@Component
public class PortableSceneBatchMapper {

    private final ObjectMapper objectMapper;
    private final BoardBatchRequestParser boardBatchRequestParser;
    private final ObjectReader portableSceneReader;

    public PortableSceneBatchMapper(ObjectMapper objectMapper, BoardBatchRequestParser boardBatchRequestParser) {
        this.objectMapper = objectMapper;
        this.boardBatchRequestParser = boardBatchRequestParser;
        ObjectMapper portableMapper = objectMapper.copy();
        portableMapper.setConfig(portableMapper.getDeserializationConfig()
                .without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        portableMapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        portableMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        portableSceneReader = portableMapper.readerFor(PortableSceneDto.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Converts a portable scene into a validated batch command.
     *
     * @param scene        the portable scene object; its {@code schema}/{@code version} must already
     *                     have been admitted by the caller that knows where it came from
     * @param impactToken  the token from the replacement preview the user confirmed
     * @param specIdPrefix provenance prefix for minted specification ids
     */
    public BoardBatchDto toBatch(JsonNode scene, String impactToken, String specIdPrefix) {
        if (scene == null || !scene.isObject()) {
            throw new BadRequestException("The scene is invalid; no board data was changed.");
        }
        validatePortableShape(scene);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("impactToken", requiredText(impactToken, "impactToken"));
        body.set("nodes", requiredArray(scene, "devices"));
        body.set("environmentVariables", requiredArray(scene, "environmentVariables"));
        body.set("templateSnapshots", requiredArray(scene, "templates"));
        body.set("rules", mapRules(requiredArray(scene, "rules")));
        body.set("specs", mapSpecs(requiredArray(scene, "specs"), specIdPrefix));
        try {
            return boardBatchRequestParser.parse(body);
        } catch (ValidationException error) {
            throw PortableSceneDiagnostics.portable(error);
        } catch (BadRequestException error) {
            throw PortableSceneDiagnostics.portable(error);
        }
    }

    private void validatePortableShape(JsonNode scene) {
        // Read the original tree before mapping can erase unknown fields or invalid scalar values.
        try {
            portableSceneReader.readValue(objectMapper.treeAsTokens(scene));
        } catch (UnrecognizedPropertyException exception) {
            String path = JsonPointerPath.of(exception);
            throw new BadRequestException("Unknown field '" + exception.getPropertyName() + "' at '"
                    + path + "'; importing it would lose data, so no board data was changed.",
                    Map.of(path, "Unknown field '" + exception.getPropertyName()
                            + "'; use the current portable scene format or export the scene again."));
        } catch (JsonProcessingException exception) {
            String path = exception instanceof JsonMappingException mappingException
                    ? JsonPointerPath.of(mappingException) : "scene";
            String reason = "Value does not match the declared JSON field type";
            if (exception instanceof MismatchedInputException mismatch) {
                Class<?> type = mismatch.getTargetType();
                if (type == String.class) reason = "A JSON string is required";
                else if (type == Integer.class) reason = "A JSON integer number is required";
                else if (type == Double.class) reason = "A JSON number is required";
                else if (type != null && java.util.Collection.class.isAssignableFrom(type)) reason = "A JSON array is required";
            }
            throw new BadRequestException("Invalid portable scene value at '" + path + "': "
                    + reason + ". No board data was changed.", Map.of(path, reason));
        } catch (IOException exception) {
            throw new BadRequestException("Scene JSON could not be read; no board data was changed.");
        }
    }

    private ArrayNode mapRules(JsonNode portableRules) {
        ArrayNode rules = objectMapper.createArrayNode();
        for (int index = 0; index < portableRules.size(); index++) {
            JsonNode portable = portableRules.get(index);
            String path = "rules[" + index + "]";
            if (portable == null || !portable.isObject()) {
                throw invalidField(path, "Rule must be a JSON object");
            }
            ObjectNode rule = objectMapper.createObjectNode();
            rule.set("conditions", mapRuleSources(requiredArray(portable, "sources", path + ".sources"), path));
            ObjectNode command = objectMapper.createObjectNode();
            command.put("deviceName", requiredText(portable, "toId", path + ".toId"));
            command.put("action", requiredText(portable, "toApi", path + ".toApi"));
            copyOptionalText(portable, command, "contentDevice");
            copyOptionalText(portable, command, "content");
            rule.set("command", command);
            rule.put("ruleString", portable.path("name").isTextual()
                    ? portable.path("name").asText() : "");
            rules.add(rule);
        }
        return rules;
    }

    private ArrayNode mapRuleSources(JsonNode sources, String rulePath) {
        ArrayNode conditions = objectMapper.createArrayNode();
        for (int index = 0; index < sources.size(); index++) {
            JsonNode source = sources.get(index);
            String path = rulePath + ".sources[" + index + "]";
            if (source == null || !source.isObject()) {
                throw invalidField(path, "Rule source must be a JSON object");
            }
            ObjectNode condition = objectMapper.createObjectNode();
            condition.put("deviceName", requiredText(source, "fromId", path + ".fromId"));
            String targetType = requiredText(source, "itemType", path + ".itemType");
            condition.put("targetType", targetType);
            condition.put("attribute", requiredText(source, "fromApi", path + ".fromApi"));
            if ("api".equalsIgnoreCase(targetType)) {
                // Preserve supplied comparisons so RuleDto rejects them instead of changing the event.
                copyOptionalText(source, condition, "relation");
                copyOptionalText(source, condition, "value");
            } else {
                condition.put("relation", requiredText(source, "relation", path + ".relation"));
                condition.put("value", requiredText(source, "value", path + ".value"));
            }
            conditions.add(condition);
        }
        return conditions;
    }

    private ArrayNode mapSpecs(JsonNode portableSpecs, String specIdPrefix) {
        String prefix = requiredText(specIdPrefix, "specIdPrefix");
        ArrayNode specs = objectMapper.createArrayNode();
        int index = 1;
        for (JsonNode portable : portableSpecs) {
            String path = "specs[" + (index - 1) + "]";
            if (portable == null || !portable.isObject()) {
                throw invalidField(path, "Specification must be a JSON object");
            }
            ObjectNode spec = objectMapper.createObjectNode();
            spec.put("id", prefix + index++);
            spec.put("templateId", requiredText(portable, "templateId", path + ".templateId"));
            for (String side : new String[]{"aConditions", "ifConditions", "thenConditions"}) {
                spec.set(side, mapConditions(requiredArray(portable, side, path + "." + side), path + "." + side));
            }
            specs.add(spec);
        }
        return specs;
    }

    private ArrayNode mapConditions(JsonNode portableConditions, String collectionPath) {
        ArrayNode conditions = objectMapper.createArrayNode();
        for (int index = 0; index < portableConditions.size(); index++) {
            JsonNode portable = portableConditions.get(index);
            String path = collectionPath + "[" + index + "]";
            if (portable == null || !portable.isObject()) {
                throw invalidField(path, "Specification condition must be a JSON object");
            }
            ObjectNode condition = objectMapper.createObjectNode();
            condition.put("deviceId", requiredText(portable, "deviceId", path + ".deviceId"));
            condition.put("targetType", requiredText(portable, "targetType", path + ".targetType"));
            condition.put("key", requiredText(portable, "key", path + ".key"));
            copyOptionalText(portable, condition, "propertyScope");
            // Carried, not dropped: a `variable` condition is required to state which question it asks,
            // and stripping it here made the scene fail admission on a field the scene actually had —
            // an error naming a field the user never wrote and cannot fix.
            copyOptionalText(portable, condition, "variableSource");
            condition.put("relation", requiredText(portable, "relation", path + ".relation"));
            condition.put("value", requiredText(portable, "value", path + ".value"));
            conditions.add(condition);
        }
        return conditions;
    }

    private ArrayNode requiredArray(JsonNode object, String field) {
        return requiredArray(object, field, field);
    }

    private ArrayNode requiredArray(JsonNode object, String field, String path) {
        JsonNode value = object.path(field);
        if (!value.isArray()) {
            throw invalidField(path, "A JSON array is required; use [] for an empty collection");
        }
        return (ArrayNode) value.deepCopy();
    }

    private String requiredText(JsonNode object, String field, String path) {
        return requiredText(object.path(field).isTextual() ? object.path(field).asText() : null, path);
    }

    private String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidField(field, "A non-blank JSON string is required");
        }
        return value.trim();
    }

    private BadRequestException invalidField(String path, String reason) {
        return new BadRequestException(path + ": " + reason + "; no board data was changed.", Map.of(path, reason));
    }

    private void copyOptionalText(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.path(field);
        if (value.isTextual() && !value.asText().isBlank()) {
            target.put(field, value.asText());
        }
    }
}
