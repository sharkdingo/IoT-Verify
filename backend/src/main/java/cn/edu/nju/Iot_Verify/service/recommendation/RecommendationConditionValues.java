package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.util.EnumLiteralResolver;

import java.util.ArrayList;
import java.util.List;

/** Canonicalizes generated rule and specification conditions against the same device domains. */
final class RecommendationConditionValues {

    private RecommendationConditionValues() {
    }

    static DeviceInfoHelper.VariableInfo findVariable(DeviceInfoHelper.DeviceInfo device, String rawName) {
        if (device.variables() == null || rawName == null) {
            return null;
        }
        String trimmed = rawName.trim();
        for (DeviceInfoHelper.VariableInfo variable : device.variables()) {
            if (variable.name() != null && variable.name().equalsIgnoreCase(trimmed)) {
                return variable;
            }
        }
        return null;
    }

    static DeviceInfoHelper.ModeInfo findMode(DeviceInfoHelper.DeviceInfo device, String rawName) {
        if (device.modes() == null || rawName == null) {
            return null;
        }
        String trimmed = rawName.trim();
        for (DeviceInfoHelper.ModeInfo mode : device.modes()) {
            if (mode.name() != null && mode.name().equalsIgnoreCase(trimmed)) {
                return mode;
            }
        }
        return null;
    }

    static String canonicalVariableValue(DeviceInfoHelper.VariableInfo variable, String relation, String rawValue) {
        if (variable == null || rawValue == null) {
            return null;
        }
        List<String> candidates = splitValueCandidates(rawValue, relation, false);
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> canonical = new ArrayList<>();
        if (variable.values() != null && !variable.values().isEmpty()) {
            if (!isEnumRelation(relation)) {
                return null;
            }
            for (String candidate : candidates) {
                String value = EnumLiteralResolver.resolveIgnoringSpaces(variable.values(), candidate);
                if (value == null) {
                    return null;
                }
                canonical.add(value);
            }
        } else if (variable.lowerBound() != null && variable.upperBound() != null) {
            for (String candidate : candidates) {
                try {
                    int parsed = Integer.parseInt(candidate.trim());
                    if (parsed < variable.lowerBound() || parsed > variable.upperBound()) {
                        return null;
                    }
                    canonical.add(String.valueOf(parsed));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        } else {
            canonical.addAll(candidates);
        }
        return joinCanonicalValues(canonical, relation);
    }

    static String canonicalModeValues(DeviceInfoHelper.ModeInfo mode, String relation, String rawValue) {
        if (mode == null || mode.values() == null || rawValue == null) {
            return null;
        }
        List<String> candidates = splitValueCandidates(rawValue, relation, false);
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> canonical = new ArrayList<>();
        for (String candidate : candidates) {
            String value = EnumLiteralResolver.resolveIgnoringSpaces(mode.values(), candidate);
            if (value == null) {
                return null;
            }
            canonical.add(value);
        }
        return joinCanonicalValues(canonical, relation);
    }

    static String canonicalStateValues(DeviceInfoHelper.DeviceInfo device, String relation, String rawValue) {
        if (device == null || rawValue == null) {
            return null;
        }
        boolean multiMode = device.modes() != null && device.modes().size() > 1;
        List<String> candidates = splitValueCandidates(rawValue, relation, multiMode);
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> canonical = new ArrayList<>();
        for (String candidate : candidates) {
            String value = canonicalStateValue(device, candidate);
            if (value == null) {
                return null;
            }
            canonical.add(value);
        }
        return joinCanonicalValues(canonical, relation);
    }

    private static String canonicalStateValue(DeviceInfoHelper.DeviceInfo device, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String workingState = EnumLiteralResolver.resolveIgnoringSpaces(device.workingStates(), trimmed);
        if (workingState != null) {
            return workingState;
        }
        if (trimmed.contains(";")) {
            return canonicalStateTuple(device, trimmed);
        }
        return canonicalUniqueModeValue(device, trimmed);
    }

    private static String canonicalStateTuple(DeviceInfoHelper.DeviceInfo device, String rawValue) {
        if (device.modes() == null || device.modes().isEmpty()) {
            return null;
        }
        String[] parts = rawValue.split(";", -1);
        if (parts.length != device.modes().size()) {
            return null;
        }
        List<String> canonicalParts = new ArrayList<>();
        boolean hasConcretePart = false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty() || "_".equals(part)) {
                canonicalParts.add("");
                continue;
            }
            String value = EnumLiteralResolver.resolveIgnoringSpaces(device.modes().get(i).values(), part);
            if (value == null) {
                return null;
            }
            canonicalParts.add(value);
            hasConcretePart = true;
        }
        return hasConcretePart ? String.join(";", canonicalParts) : null;
    }

    private static String canonicalUniqueModeValue(DeviceInfoHelper.DeviceInfo device, String rawValue) {
        if (device.modes() == null) {
            return null;
        }
        String match = null;
        int matches = 0;
        for (DeviceInfoHelper.ModeInfo mode : device.modes()) {
            String candidate = EnumLiteralResolver.resolveIgnoringSpaces(mode.values(), rawValue);
            if (candidate != null) {
                matches++;
                if (matches > 1) {
                    return null;
                }
                match = candidate;
            }
        }
        return match;
    }

    static String normalizeRelation(String relation) {
        String normalized = SmvRelationUtils.normalizeRelation(relation);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return SmvRelationUtils.isSupportedRelation(normalized) ? normalized : null;
    }

    static boolean isEnumRelation(String relation) {
        return "=".equals(relation) || "!=".equals(relation)
                || "in".equals(relation) || "not in".equals(relation);
    }

    static List<String> splitValueCandidates(String rawValue, String relation, boolean preserveSemicolon) {
        if (rawValue == null) {
            return List.of();
        }
        if (!("in".equals(relation) || "not in".equals(relation))) {
            String trimmed = rawValue.trim();
            return trimmed.isEmpty() ? List.of() : List.of(trimmed);
        }
        if (!preserveSemicolon) {
            return SmvRelationUtils.splitRuleValues(rawValue);
        }
        List<String> result = new ArrayList<>();
        for (String part : rawValue.split("[,|]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    static String joinCanonicalValues(List<String> values, String relation) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return ("in".equals(relation) || "not in".equals(relation))
                ? String.join(",", values)
                : values.get(0);
    }

    static String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
