package cn.edu.nju.Iot_Verify.component.template;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.util.SmvIntegerLiteral;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Manifest facts shared by storage, model admission, and recommendation validation. */
public final class ManifestConditionSemantics {
    public static final Set<String> API_SIGNAL_LITERALS = Set.of("TRUE", "FALSE");

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
                    int parsed = SmvIntegerLiteral.parse(candidate);
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

    /** Canonical case for the Boolean literal text of an API specification condition. */
    public static String canonicalApiSignalValue(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isLegalState(String rawCandidate, List<String> modes,
                                       Map<String, List<String>> authoredStatesByMode) {
        return !resolveStatePattern(rawCandidate, modes,
                (mode, state) -> authoredStatesByMode.getOrDefault(mode, List.of()).contains(state) ? state : null)
                .isEmpty();
    }

    public static <M> Map<M, String> resolveStatePattern(String rawCandidate, List<M> modes,
                                                        BiFunction<M, String, String> resolveState) {
        return resolveStatePattern(rawCandidate, modes, resolveState, reason -> Map.of());
    }

    /** Resolve the authored state within each mode before comparing or emitting its solver atom. */
    public static <M> Map<M, String> resolveStatePattern(String rawCandidate, List<M> modes,
                                                        BiFunction<M, String, String> resolveState,
                                                        Function<String, Map<M, String>> invalid) {
        if (!hasText(rawCandidate) || modes == null || modes.isEmpty()) {
            return invalid.apply("state condition requires a value and at least one device mode");
        }
        String candidate = rawCandidate.trim();
        Map<M, String> resolved = new LinkedHashMap<>();
        if (candidate.contains(";")) {
            String[] segments = candidate.split(";", -1);
            if (segments.length != modes.size()) {
                return invalid.apply("state tuple '" + candidate + "' does not match the device mode count");
            }
            for (int i = 0; i < modes.size(); i++) {
                String state = segments[i].trim();
                if (DeviceSmvDataFactory.isWildcardStateSegment(state)) continue;
                M mode = modes.get(i);
                String value = resolveState.apply(mode, state);
                if (value == null) {
                    return invalid.apply("state value '" + state + "' is not declared for mode '" + mode + "'");
                }
                resolved.put(mode, value);
            }
            return resolved.isEmpty() ? invalid.apply("state tuple has no concrete mode segment") : resolved;
        }
        for (M mode : modes) {
            String value = resolveState.apply(mode, candidate);
            if (value == null) continue;
            if (!resolved.isEmpty()) {
                return invalid.apply("state value '" + candidate + "' is ambiguous across modes");
            }
            resolved.put(mode, value);
        }
        return resolved.isEmpty() ? invalid.apply("state value '" + candidate + "' is not declared") : resolved;
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
