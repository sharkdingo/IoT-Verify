package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RuleCandidateArgument {

    private static final Set<String> RULE_FIELDS = Set.of("id", "conditions", "command", "ruleString");
    private static final Set<String> CONDITION_FIELDS =
            Set.of("deviceName", "attribute", "targetType", "relation", "value");
    private static final Set<String> COMMAND_FIELDS =
            Set.of("deviceName", "action", "contentDevice", "content");

    private RuleCandidateArgument() {
    }

    static RuleDto parse(JsonNode node) throws ValidationException {
        requireObject(node, "newRule");
        requireOnlyFields(node, "newRule", RULE_FIELDS);

        JsonNode conditionsNode = node.get("conditions");
        if (conditionsNode == null || !conditionsNode.isArray() || conditionsNode.isEmpty()) {
            throw invalid("newRule.conditions must be a non-empty array.");
        }

        List<RuleDto.Condition> conditions = new ArrayList<>();
        int number = 1;
        for (JsonNode conditionNode : conditionsNode) {
            String path = "newRule condition " + number;
            requireObject(conditionNode, path);
            requireOnlyFields(conditionNode, path, CONDITION_FIELDS);

            String deviceName = requiredText(conditionNode, "deviceName", path);
            String attribute = requiredText(conditionNode, "attribute", path);
            String targetType = requiredText(conditionNode, "targetType", path);
            String relation = optionalText(conditionNode, "relation", path);
            String value = optionalText(conditionNode, "value", path);

            conditions.add(RuleDto.Condition.builder()
                    .deviceName(deviceName)
                    .attribute(attribute)
                    .targetType(targetType)
                    .relation(relation)
                    .value(value)
                    .build());
            number++;
        }

        JsonNode commandNode = node.get("command");
        requireObject(commandNode, "newRule.command");
        requireOnlyFields(commandNode, "newRule.command", COMMAND_FIELDS);
        String commandDevice = requiredText(commandNode, "deviceName", "newRule.command");
        String action = requiredText(commandNode, "action", "newRule.command");
        String contentDevice = optionalText(commandNode, "contentDevice", "newRule.command");
        String content = optionalText(commandNode, "content", "newRule.command");
        return RuleDto.builder()
                .id(optionalId(node))
                .conditions(conditions)
                .command(RuleDto.Command.builder()
                        .deviceName(commandDevice)
                        .action(action)
                        .contentDevice(contentDevice)
                        .content(content)
                        .build())
                .ruleString(optionalText(node, "ruleString", "newRule"))
                .build();
    }

    static Map<String, Object> schema() {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("type", "object");
        condition.put("properties", Map.of(
                "deviceName", Map.of("type", "string", "description", "Canonical board device instance id."),
                "attribute", Map.of("type", "string"),
                "targetType", Map.of("type", "string", "enum", List.of("api", "variable", "mode", "state")),
                "relation", Map.of("type", "string"),
                "value", Map.of("type", "string")
        ));
        condition.put("required", List.of("deviceName", "attribute", "targetType"));
        condition.put("additionalProperties", false);

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "object");
        command.put("properties", Map.of(
                "deviceName", Map.of("type", "string", "description", "Canonical target device instance id."),
                "action", Map.of("type", "string"),
                "contentDevice", Map.of("type", "string", "description", "Optional canonical source device id; requires content."),
                "content", Map.of("type", "string", "description", "Optional content label; requires contentDevice.")
        ));
        command.put("required", List.of("deviceName", "action"));
        command.put("additionalProperties", false);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "object");
        rule.put("properties", Map.of(
                "id", Map.of("type", "integer", "minimum", 1,
                        "description", "Persisted rule id when checking an edit; omit for a new rule."),
                "conditions", Map.of("type", "array", "minItems", 1, "items", condition),
                "command", command,
                "ruleString", Map.of("type", "string", "description", "Optional user-facing rule name.")
        ));
        rule.put("required", List.of("conditions", "command"));
        rule.put("additionalProperties", false);
        return Collections.unmodifiableMap(rule);
    }

    private static Long optionalId(JsonNode node) throws ValidationException {
        JsonNode value = node.get("id");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw invalid("newRule.id must be a positive integer when provided.");
        }
        BigInteger id = value.bigIntegerValue();
        if (id.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                || id.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw invalid("newRule.id must be a positive integer when provided.");
        }
        return id.longValue();
    }

    private static String requiredText(JsonNode object, String field, String path) throws ValidationException {
        String value = optionalText(object, field, path);
        if (value == null) {
            throw invalid(path + "." + field + " is required and must be a non-empty string.");
        }
        return value;
    }

    private static String optionalText(JsonNode object, String field, String path) throws ValidationException {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(path + "." + field + " must be a string when provided.");
        }
        return value.textValue();
    }

    private static void requireObject(JsonNode value, String path) throws ValidationException {
        if (value == null || !value.isObject()) {
            throw invalid(path + " must be a JSON object.");
        }
    }

    private static void requireOnlyFields(JsonNode object,
                                          String path,
                                          Set<String> allowed) throws ValidationException {
        List<String> unknown = new ArrayList<>();
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            Collections.sort(unknown);
            throw invalid(path + " contains unsupported field(s): " + String.join(", ", unknown) + ".");
        }
    }

    private static ValidationException invalid(String message) {
        return new ValidationException(message);
    }

    static final class ValidationException extends Exception {
        ValidationException(String message) {
            super(message);
        }
    }
}
