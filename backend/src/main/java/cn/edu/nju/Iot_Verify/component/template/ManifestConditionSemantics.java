package cn.edu.nju.Iot_Verify.component.template;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Manifest facts shared by storage, model admission, and recommendation validation. */
public final class ManifestConditionSemantics {
    private ManifestConditionSemantics() {
    }

    public enum ValueError { UNKNOWN_ENUM, NOT_INTEGER, OUT_OF_RANGE }

    public record ValueIssue(ValueError error, String candidate) {
    }

    public static ValueIssue firstInvalidVariableValue(DeviceManifest.InternalVariable variable,
                                                       List<String> candidates) {
        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            Set<String> allowed = new HashSet<>();
            for (String value : variable.getValues()) {
                if (hasText(value)) allowed.add(cleanEnumLiteral(value));
            }
            for (String candidate : candidates) {
                if (!allowed.contains(cleanEnumLiteral(candidate))) {
                    return new ValueIssue(ValueError.UNKNOWN_ENUM, candidate);
                }
            }
        } else if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
            for (String candidate : candidates) {
                try {
                    int parsed = Integer.parseInt(candidate.trim());
                    if (parsed < variable.getLowerBound() || parsed > variable.getUpperBound()) {
                        return new ValueIssue(ValueError.OUT_OF_RANGE, candidate);
                    }
                } catch (NumberFormatException e) {
                    return new ValueIssue(ValueError.NOT_INTEGER, candidate);
                }
            }
        }
        return null;
    }

    public static String cleanEnumLiteral(String value) {
        return value == null ? "" : value.trim().replace(" ", "");
    }

    public static boolean isLegalState(String rawCandidate, List<String> modes,
                                       Map<String, List<String>> statesByMode) {
        if (!hasText(rawCandidate) || modes == null || modes.isEmpty()
                || statesByMode == null || statesByMode.isEmpty()) return false;
        String candidate = rawCandidate.trim();
        if (candidate.contains(";")) {
            String[] segments = candidate.split(";", -1);
            if (segments.length != modes.size()) return false;
            boolean hasConcreteSegment = false;
            for (int i = 0; i < modes.size(); i++) {
                String cleaned = DeviceSmvDataFactory.cleanStateName(segments[i]);
                if (!hasText(cleaned) || "_".equals(cleaned)) continue;
                List<String> legalStates = statesByMode.get(modes.get(i));
                if (legalStates == null || !legalStates.contains(cleaned)) return false;
                hasConcreteSegment = true;
            }
            return hasConcreteSegment;
        }
        String cleaned = DeviceSmvDataFactory.cleanStateName(candidate);
        if (!hasText(cleaned)) return false;
        int matches = 0;
        for (String mode : modes) {
            List<String> states = statesByMode.get(mode);
            if (states != null && states.contains(cleaned)) matches++;
        }
        return matches == 1;
    }

    public static DeviceManifest.API findApi(DeviceManifest manifest, String apiName) {
        if (manifest == null || manifest.getApis() == null || !hasText(apiName)) return null;
        String target = apiName.trim();
        return manifest.getApis().stream()
                .filter(api -> api != null && target.equals(api.getName()))
                .findFirst().orElse(null);
    }

    public static DeviceManifest.API findSignalApi(DeviceManifest manifest, String apiName) {
        if (manifest == null || manifest.getApis() == null || !hasText(apiName)) return null;
        String target = apiName.trim();
        return manifest.getApis().stream()
                .filter(api -> api != null && target.equals(api.getName()) && Boolean.TRUE.equals(api.getSignal()))
                .findFirst().orElse(null);
    }

    public static boolean hasSignalApi(DeviceManifest manifest, String apiName) {
        return findSignalApi(manifest, apiName) != null;
    }

    public static DeviceManifest.InternalVariable findDeclaredVariable(DeviceManifest manifest, String name) {
        if (manifest == null || manifest.getInternalVariables() == null || !hasText(name)) return null;
        String target = name.trim();
        return manifest.getInternalVariables().stream()
                .filter(variable -> variable != null && target.equals(variable.getName()))
                .findFirst().orElse(null);
    }

    /** Affect-only shared declarations have labels, but no device reading usable by value conditions. */
    public static DeviceManifest.InternalVariable findConditionVariable(DeviceManifest manifest, String name) {
        DeviceManifest.InternalVariable variable = findDeclaredVariable(manifest, name);
        if (variable == null) return null;
        boolean affectOnlyShared = !Boolean.TRUE.equals(variable.getIsInside())
                && Boolean.FALSE.equals(variable.getReads());
        return affectOnlyShared ? null : variable;
    }

    public static boolean matchesLiteral(String candidate, Set<String> allowed) {
        return allowed.stream().anyMatch(value -> value.equalsIgnoreCase(candidate.trim()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
