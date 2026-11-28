package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

final class ManifestConditionValidation {

    private ManifestConditionValidation() {
    }

    static void validateRuntimeVariables(Map<String, String> errors,
                                         String field,
                                         List<VariableStateDto> variables,
                                         Function<String, DeviceManifest.InternalVariable> findVariable,
                                         BiConsumer<String, String> validateTrust,
                                         String environmentErrorPrefix) {
        if (variables == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < variables.size(); i++) {
            VariableStateDto variable = variables.get(i);
            String prefix = field + "[" + i + "]";
            if (variable == null) {
                errors.putIfAbsent(prefix, "Variable item cannot be null");
                continue;
            }
            String name = trimToNull(variable.getName());
            String value = trimToNull(variable.getValue());
            if (name == null) {
                errors.putIfAbsent(prefix + ".name", "Variable name is required");
                continue;
            }
            if (!seen.add(name)) {
                errors.putIfAbsent(prefix + ".name", "Duplicate variable override: " + name);
            }
            if (value == null) {
                errors.putIfAbsent(prefix + ".value", "Variable value is required");
            }
            String trust = variable.getTrust();
            if (trust != null) {
                validateTrust.accept(prefix + ".trust", trust);
            }

            DeviceManifest.InternalVariable manifestVariable = findVariable.apply(name);
            if (manifestVariable == null) {
                errors.putIfAbsent(prefix + ".name", "Unknown runtime variable for device template: " + name);
            } else if (isEnvironmentVariable(manifestVariable)) {
                errors.putIfAbsent(prefix + ".name", environmentErrorPrefix + name);
            } else if (value != null) {
                validateVariableValues(errors, prefix + ".value", manifestVariable, "=", value);
            }
        }
    }

    static void validateRuntimePrivacies(Map<String, String> errors,
                                         String field,
                                         List<PrivacyStateDto> privacies,
                                         Function<String, DeviceManifest.InternalVariable> findVariable,
                                         BiFunction<String, DeviceManifest.InternalVariable, String> environmentError) {
        if (privacies == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < privacies.size(); i++) {
            PrivacyStateDto privacy = privacies.get(i);
            String prefix = field + "[" + i + "]";
            if (privacy == null) {
                errors.putIfAbsent(prefix, "Privacy item cannot be null");
                continue;
            }
            String name = trimToNull(privacy.getName());
            String value = trimToNull(privacy.getPrivacy());
            if (name == null) {
                errors.putIfAbsent(prefix + ".name", "Privacy target name is required");
                continue;
            }
            if (!seen.add(name)) {
                errors.putIfAbsent(prefix + ".name", "Duplicate privacy override: " + name);
            }
            if (value == null) {
                errors.putIfAbsent(prefix + ".privacy", "Privacy value is required");
            } else if (!ManifestConditionSemantics.matchesLiteral(value, Set.of("public", "private"))) {
                errors.putIfAbsent(prefix + ".privacy", "Value must be public or private: " + value);
            }
            DeviceManifest.InternalVariable manifestVariable = findVariable.apply(name);
            if (manifestVariable == null) {
                errors.putIfAbsent(prefix + ".name",
                        "Unknown device-local variable privacy target: " + name);
            } else if (isEnvironmentVariable(manifestVariable)) {
                errors.putIfAbsent(prefix + ".name", environmentError.apply(name, manifestVariable));
            }
        }
    }

    static boolean isEnvironmentVariable(DeviceManifest.InternalVariable variable) {
        return variable != null && !Boolean.TRUE.equals(variable.getIsInside());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static void validateVariableValues(Map<String, String> errors,
                                       String field,
                                       DeviceManifest.InternalVariable variable,
                                       String relation,
                                       String rawValue) {
        if (variable == null || rawValue == null || rawValue.isBlank()) {
            return;
        }
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitRuleValues(rawValue)
                : List.of(rawValue.trim());
        if (candidates.isEmpty()) {
            errors.putIfAbsent(field, "Variable condition value cannot be empty");
            return;
        }
        if (("=".equals(relation) || "!=".equals(relation)) && candidates.size() != 1) {
            errors.putIfAbsent(field, "Variable relation '" + relation + "' requires exactly one value");
            return;
        }

        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            validateEnumRelation(errors, field, relation, "enum variable");
        }
        ManifestConditionSemantics.ValueIssue issue =
                ManifestConditionSemantics.firstInvalidVariableValue(variable, candidates);
        if (issue == null) return;
        String message = switch (issue.error()) {
            case UNKNOWN_ENUM -> "Illegal enum value for variable '" + variable.getName() + "': " + issue.candidate();
            case OUT_OF_RANGE -> "Variable value out of range for '" + variable.getName() + "': " + issue.candidate()
                    + " (allowed " + variable.getLowerBound() + ".." + variable.getUpperBound() + ")";
            case NOT_INTEGER -> "Variable value must be an integer for '" + variable.getName() + "': " + issue.candidate();
        };
        errors.putIfAbsent(field, message);
    }

    static void validateEnumRelation(Map<String, String> errors,
                                     String field,
                                     String relation,
                                     String label) {
        if (!SmvRelationUtils.isEnumRelation(relation)) {
            errors.putIfAbsent(field, label + " conditions support only =, !=, IN, and NOT_IN");
        }
    }
}
