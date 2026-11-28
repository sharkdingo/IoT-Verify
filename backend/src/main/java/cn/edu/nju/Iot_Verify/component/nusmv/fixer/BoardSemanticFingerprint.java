package cn.edu.nju.Iot_Verify.component.nusmv.fixer;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.PropertyDimension;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceReferenceResolver;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.ModeStateInitialLabels;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import cn.edu.nju.Iot_Verify.util.EnvironmentDomainUtils;
import cn.edu.nju.Iot_Verify.util.SpecificationSemanticSignature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a canonical fingerprint of the <em>device instance state</em>,
 * <em>board environment pool</em>, and <em>specifications</em> that feed NuSMV verification, so
 * the fix-apply flow can detect whether the user edited specs, environment values,
 * device variables/privacies or device initial state after verifying — drifts
 * that the server recompute (which replays the trace's stored context) can <b>not</b> catch on its
 * own.
 *
 * <h2>Why fingerprint instead of comparing raw JSON</h2>
 * The trace's stored request is a <em>model-boundary</em> snapshot: device varNames are
 * SMV-safe normalized node ids (for example {@code Node-1} → {@code node_1}), while a fresh device node
 * can store no explicit variable/privacy overrides at all. The current board read back from storage has
 * raw node ids and possibly-empty variable/privacy lists. A byte-equality check therefore misfires on
 * untouched boards.
 *
 * <p>This class sidesteps that by running <b>both</b> sides through the <em>same</em> canonicalization
 * — normalize device names, derive the same effective variable/trust/privacy values as the NuSMV
 * generator, normalize numeric variable values, lowercase trust/privacy — so any imperfection in the
 * transform cancels out. An untouched board and its snapshot canonicalize identically; a genuine edit
 * changes the fingerprint on exactly one side.</p>
 *
 * <p>Pure and deterministic (no NuSMV, no persistence), so it is unit-testable in isolation.</p>
 */
public final class BoardSemanticFingerprint {

    private BoardSemanticFingerprint() {}

    /**
     * Canonical fingerprint of the given devices + specs. {@code deviceSmvMap} supplies each device's
     * resolved manifest (used to derive the same effective values the NuSMV generator consumes).
     */
    public static String of(List<DeviceVerificationDto> devices,
                             List<SpecificationDto> specs,
                             Map<String, DeviceSmvData> deviceSmvMap) {
        return of(devices, specs, List.of(), deviceSmvMap);
    }

    public static String of(List<DeviceVerificationDto> devices,
                             List<SpecificationDto> specs,
                             List<BoardEnvironmentVariableDto> environmentVariables,
                             Map<String, DeviceSmvData> deviceSmvMap) {
        return "devices{" + devicesFingerprint(devices, deviceSmvMap) + "}"
                + "env{" + environmentFingerprint(environmentVariables, deviceSmvMap) + "}"
                + "specs{" + specsFingerprint(specs, deviceSmvMap) + "}";
    }

    // ==================== devices ====================

    private static String devicesFingerprint(List<DeviceVerificationDto> devices,
                                              Map<String, DeviceSmvData> deviceSmvMap) {
        if (devices == null || devices.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (DeviceVerificationDto d : devices) {
            if (d == null || d.getVarName() == null || d.getVarName().isBlank()) continue;
            parts.add(deviceFingerprint(d, deviceSmvMap));
        }
        parts.sort(String::compareTo);
        return String.join(";", parts);
    }

    private static String deviceFingerprint(DeviceVerificationDto d,
                                            Map<String, DeviceSmvData> deviceSmvMap) {
        String varName = DeviceNameNormalizer.normalize(d.getVarName());
        DeviceSmvData smv = deviceModelFor(varName, d.getVarName(), deviceSmvMap);
        DeviceManifest manifest = smv != null ? smv.getManifest() : null;
        boolean hasModes = manifest == null
                || (manifest.getModes() != null && !manifest.getModes().isEmpty()
                && manifest.getWorkingStates() != null && !manifest.getWorkingStates().isEmpty());

        StringBuilder sb = new StringBuilder();
        sb.append(varName)
          .append('|').append(nullSafe(d.getTemplateName()))
          .append('|').append(hasModes ? nullSafe(d.getState()) : "")
          .append("|labels[").append(stateLabelsFingerprint(smv)).append(']');

        // Variables — derive effective model values from manifest + explicit instance overrides.
        sb.append("|vars[").append(variablesFingerprint(d.getVariables(), manifest, d.getState())).append(']');
        // Privacies — manifest default plus explicit instance overrides.
        sb.append("|priv[").append(privaciesFingerprint(d.getPrivacies(), manifest)).append(']');
        return sb.toString();
    }

    private static String stateLabelsFingerprint(DeviceSmvData smv) {
        if (smv == null) return "";
        List<String> labels = new ArrayList<>();
        for (String mode : smv.getModes()) {
            List<String> states = smv.getModeStates().get(mode);
            if (states == null) continue;
            for (String state : states) {
                labels.add(ModeStateInitialLabels.resolve(smv, mode, state, PropertyDimension.TRUST)
                        + "/" + ModeStateInitialLabels.resolve(smv, mode, state, PropertyDimension.PRIVACY));
            }
        }
        return String.join(",", labels);
    }

    private static String variablesFingerprint(List<VariableStateDto> variables, DeviceManifest manifest,
                                               String state) {
        Map<String, VariableFingerprintEntry> entries = new LinkedHashMap<>();
        if (manifest != null && manifest.getInternalVariables() != null) {
            for (DeviceManifest.InternalVariable iv : manifest.getInternalVariables()) {
                if (iv == null || iv.getName() == null) continue;
                if (isEnvironmentVariable(iv)) continue;
                entries.put(iv.getName(), new VariableFingerprintEntry(
                        normalizeModelVariableValue(DeviceManifestModes.localInitialValue(manifest, iv, state), iv),
                        normalizeTrust(iv.getTrust())));
            }
        }
        if (variables != null) {
            for (VariableStateDto v : variables) {
                if (v == null || v.getName() == null) continue;
                DeviceManifest.InternalVariable iv = manifestVariable(manifest, v.getName());
                if (isEnvironmentVariable(iv)) continue;
                VariableFingerprintEntry base = entries.get(v.getName());
                String effective = iv == null ? v.getValue()
                        : DeviceManifestModes.localInitialValue(manifest, iv, state, v.getValue());
                String value = normalizeModelVariableValue(effective, iv);
                String trust = v.getTrust() != null
                        ? normalizeTrust(v.getTrust())
                        : (base != null ? base.trust() : normalizeTrust(null));
                entries.put(v.getName(), new VariableFingerprintEntry(value, trust));
            }
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, VariableFingerprintEntry> entry : entries.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue().value() + "@" + entry.getValue().trust());
        }
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    private static String privaciesFingerprint(List<PrivacyStateDto> privacies, DeviceManifest manifest) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (manifest != null && manifest.getInternalVariables() != null) {
            for (DeviceManifest.InternalVariable iv : manifest.getInternalVariables()) {
                if (iv == null || iv.getName() == null) continue;
                if (isEnvironmentVariable(iv)) continue;
                entries.put(iv.getName(), normalizePrivacy(iv.getPrivacy()));
            }
        }
        if (privacies != null) {
            for (PrivacyStateDto p : privacies) {
                if (p == null || p.getName() == null) continue;
                DeviceManifest.InternalVariable iv = manifestVariable(manifest, p.getName());
                if (isEnvironmentVariable(iv)) continue;
                entries.put(p.getName(), normalizePrivacy(p.getPrivacy()));
            }
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    private record VariableFingerprintEntry(String value, String trust) {}

    // ==================== environment ====================

    private static String environmentFingerprint(List<BoardEnvironmentVariableDto> variables,
                                                 Map<String, DeviceSmvData> deviceSmvMap) {
        Map<String, DeviceManifest.InternalVariable> required = new LinkedHashMap<>();
        if (deviceSmvMap != null) {
            for (DeviceSmvData smv : deviceSmvMap.values()) {
                if (smv == null || smv.getVarName() == null) continue;
                smv.getSharedDeclarations().forEach(required::putIfAbsent);
            }
        }

        Map<String, BoardEnvironmentVariableDto> explicit = new LinkedHashMap<>();
        if (variables != null) {
            for (BoardEnvironmentVariableDto variable : variables) {
                if (variable != null && variable.getName() != null && !variable.getName().isBlank()) {
                    explicit.putIfAbsent(variable.getName().trim(), variable);
                }
            }
        }

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, DeviceManifest.InternalVariable> entry : required.entrySet()) {
            String name = entry.getKey();
            DeviceManifest.InternalVariable definition = entry.getValue();
            BoardEnvironmentVariableDto value = explicit.get(name);
            String rawValue = value != null ? value.getValue() : EnvironmentDomainUtils.rawDefaultValue(definition);
            String trust = value != null ? value.getTrust() : definition.getTrust();
            String privacy = value != null ? value.getPrivacy() : definition.getPrivacy();
            parts.add(name + "=" + normalizeModelVariableValue(rawValue, definition)
                    + "@" + normalizeTrust(trust)
                    + "/" + normalizePrivacy(privacy));
        }
        for (Map.Entry<String, BoardEnvironmentVariableDto> entry : explicit.entrySet()) {
            if (required.containsKey(entry.getKey())) continue;
            BoardEnvironmentVariableDto value = entry.getValue();
            parts.add(entry.getKey() + "=" + normalizeValue(value.getValue())
                    + "@" + normalizeTrust(value.getTrust())
                    + "/" + normalizePrivacy(value.getPrivacy()));
        }
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    // ==================== specs ====================

    private static String specsFingerprint(List<SpecificationDto> specs,
                                           Map<String, DeviceSmvData> deviceSmvMap) {
        if (specs == null || specs.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (SpecificationDto s : specs) {
            if (s == null) continue;
            parts.add(specFingerprint(s, deviceSmvMap));
        }
        parts.sort(String::compareTo);
        return String.join(";", parts);
    }

    private static String specFingerprint(SpecificationDto s,
                                          Map<String, DeviceSmvData> deviceSmvMap) {
        StringBuilder sb = new StringBuilder();
        sb.append(nullSafe(s.getId()))
          .append('|').append(nullSafe(s.getTemplateId()))
          .append("|a(").append(conditionsFingerprint(s.getAConditions(), deviceSmvMap)).append(')')
          .append("|if(").append(conditionsFingerprint(s.getIfConditions(), deviceSmvMap)).append(')')
          .append("|then(").append(conditionsFingerprint(s.getThenConditions(), deviceSmvMap)).append(')');
        return sb.toString();
    }

    private static String conditionsFingerprint(List<SpecConditionDto> conditions,
                                                Map<String, DeviceSmvData> deviceSmvMap) {
        if (conditions == null || conditions.isEmpty()) return "";
        // Condition order within a side is not semantically load-bearing here, so sort for stability.
        List<String> parts = new ArrayList<>();
        for (SpecConditionDto c : conditions) {
            if (c == null) continue;
            String device = DeviceReferenceResolver.canonicalReference(
                    c.getDeviceId(), deviceSmvMap);
            String relation = SmvRelationUtils.normalizeRelation(c.getRelation());
            DeviceSmvData smv = DeviceReferenceResolver.resolve(c.getDeviceId(), deviceSmvMap);
            parts.add(nullSafe(device)
                    + "#" + nullSafe(c.getTargetType())
                    + "#" + nullSafe(c.getPropertyScope())
                    // Load-bearing: `environment` and `reported` compile to different identifiers and ask
                    // different questions of the same key. Omitting it made two specifications with
                    // different meanings fingerprint identically, so a drift check could accept a spec set
                    // whose question had changed under it.
                    + "#" + nullSafe(c.getVariableSource())
                    + "#" + nullSafe(c.getKey())
                    + "#" + nullSafe(relation)
                    + "#" + specConditionValue(c, relation, smv));
        }
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    private static String specConditionValue(SpecConditionDto condition, String relation, DeviceSmvData smv) {
        String type = condition.getTargetType();
        String value = condition.getValue();
        String membership = SmvRelationUtils.canonicalStateMembershipValue(
                value, relation, type, smv == null ? 0 : smv.getModes().size());
        if (membership != null) return membership;
        if ("variable".equals(type)) value = normalizeValue(value);
        return SpecificationSemanticSignature.canonicalConditionValue(value, relation, type);
    }

    // ==================== helpers ====================

    /** Look up the built SMV data using the normalized, then raw, device name. */
    private static DeviceSmvData deviceModelFor(String normalizedVarName, String rawVarName,
                                               Map<String, DeviceSmvData> deviceSmvMap) {
        if (deviceSmvMap == null) return null;
        DeviceSmvData smv = deviceSmvMap.get(normalizedVarName);
        if (smv == null && rawVarName != null) smv = deviceSmvMap.get(rawVarName);
        return smv;
    }

    private static DeviceManifest.InternalVariable manifestVariable(DeviceManifest manifest, String name) {
        if (manifest == null || manifest.getInternalVariables() == null || name == null) return null;
        for (DeviceManifest.InternalVariable iv : manifest.getInternalVariables()) {
            if (iv != null && name.equals(iv.getName())) return iv;
        }
        return null;
    }

    private static boolean isEnvironmentVariable(DeviceManifest.InternalVariable variable) {
        return variable != null && !Boolean.TRUE.equals(variable.getIsInside());
    }

    private static String normalizeModelVariableValue(String value, DeviceManifest.InternalVariable iv) {
        if (value == null) return "";
        if (iv != null && iv.getValues() != null && !iv.getValues().isEmpty()) {
            return cleanEnumLiteral(value);
        }
        return normalizeValue(value);
    }

    private static String cleanEnumLiteral(String value) {
        return value == null ? "" : value.replace(" ", "");
    }

    /**
     * Mirror of the frontend {@code normalizeValue}: strip surrounding quotes from a quoted number
     * (e.g. {@code "30"} → {@code 30}); leave everything else untouched. Idempotent, so applying it to
     * an already-stripped snapshot value is a no-op.
     */
    private static String normalizeValue(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            boolean quoted = (first == '"' && last == '"') || (first == '\'' && last == '\'');
            if (quoted) {
                String inner = value.substring(1, value.length() - 1);
                if (!inner.isEmpty() && inner.chars().allMatch(Character::isDigit)) {
                    return inner;
                }
            }
        }
        return value;
    }

    private static String normalizeTrust(String value) {
        String v = DeviceSmvDataFactory.normalizeTrustPrivacy(value);
        return v != null ? v : "trusted";
    }

    private static String normalizePrivacy(String value) {
        String v = DeviceSmvDataFactory.normalizeTrustPrivacy(value);
        return v != null ? v : "public";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
