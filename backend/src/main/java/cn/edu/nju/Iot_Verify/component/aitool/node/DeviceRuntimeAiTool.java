package cn.edu.nju.Iot_Verify.component.aitool.node;

import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

abstract class DeviceRuntimeAiTool extends AbstractAiTool {

    DeviceRuntimeAiTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    final List<VariableStateDto> initialVariables(JsonNode node) throws ArgValidationException {
        return parseVariables(node, null, true);
    }

    final List<VariableStateDto> replacementVariables(JsonNode node, List<VariableStateDto> current)
            throws ArgValidationException {
        return parseVariables(node, current, false);
    }

    private List<VariableStateDto> parseVariables(JsonNode node, List<VariableStateDto> current, boolean allowNullTrust)
            throws ArgValidationException {
        if (!node.isArray()) throw validation("variables must be a JSON array.");
        List<VariableStateDto> variables = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            String field = "variables[" + index++ + "]";
            String itemPath = "arguments." + field;
            requireOnlyFields(item, itemPath, Set.of("name", "value", "trust"));
            String name = requiredTextField(item, "name", itemPath);
            String value = requiredTextField(item, "value", itemPath);
            JsonNode trustValue = item.get("trust");
            String trust = trustValue != null && (!trustValue.isNull() || !allowNullTrust)
                    ? requiredEnum(trustValue, field + ".trust", List.of("trusted", "untrusted"))
                    : existingVariableTrust(current, name);
            variables.add(new VariableStateDto(name, value, trust));
        }
        return variables;
    }

    private static String existingVariableTrust(List<VariableStateDto> current, String name) {
        if (current == null) return null;
        return current.stream()
                .filter(Objects::nonNull)
                .filter(variable -> Objects.equals(variable.getName(), name))
                .findFirst()
                .map(VariableStateDto::getTrust)
                .orElse(null);
    }

    final List<PrivacyStateDto> parsePrivacies(JsonNode node) throws ArgValidationException {
        if (!node.isArray()) throw validation("privacies must be a JSON array.");
        List<PrivacyStateDto> privacies = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            String field = "privacies[" + index++ + "]";
            String itemPath = "arguments." + field;
            requireOnlyFields(item, itemPath, Set.of("name", "privacy"));
            String name = requiredTextField(item, "name", itemPath);
            String privacy = requiredEnum(item.path("privacy"), field + ".privacy", List.of("public", "private"));
            privacies.add(new PrivacyStateDto(name, privacy));
        }
        return privacies;
    }

    final String requiredEnum(JsonNode node, String field, List<String> allowed) throws ArgValidationException {
        String value = node != null && node.isTextual() ? trimToNull(node.textValue()) : null;
        String normalized = value == null ? null : value.toLowerCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw validation(field + " must be one of: " + String.join(", ", allowed) + ".");
        }
        return normalized;
    }

    final ArgValidationException validation(String message) {
        return new ArgValidationException(errorJson(message, "VALIDATION_ERROR", 400));
    }
}
