package cn.edu.nju.Iot_Verify.component.nusmv.generator.data;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.PropertyDimension;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.util.EnvironmentDomainUtils;
import cn.edu.nju.Iot_Verify.util.SmvConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Names and enum constants that can be declared by a template's NuSMV module. */
public final class DeviceSmvNamespace {

    private DeviceSmvNamespace() {}

    public static void validate(DeviceManifest manifest, Function<String, ? extends RuntimeException> invalid) {
        validateIdentifiers(manifest, constants(manifest, false), false, false, invalid);
    }

    public static void validateScene(Map<String, DeviceManifest> manifests, int ruleCount,
                                     BiFunction<String, String, ? extends RuntimeException> invalid) {
        validateScene(manifests, ruleCount, false, false, Set.of(), invalid);
    }

    public static void validateScene(Map<String, DeviceManifest> manifests, int ruleCount,
                                     boolean enablePrivacy, boolean enableAttack,
                                     Set<String> devicesWithAttackFlag,
                                     BiFunction<String, String, ? extends RuntimeException> invalid) {
        // NuSMV interns enumeration constants globally, but identifiers remain qualified by module.
        Map<String, String> constants = sceneConstants(manifests, enablePrivacy);
        manifests.forEach((device, manifest) -> validateIdentifiers(manifest, constants, enablePrivacy,
                devicesWithAttackFlag.contains(device), reason -> invalid.apply(device, reason)));
        validateSharedPoolNames(manifests, constants, invalid);
        validateMainControlNames(constants, ruleCount, enableAttack, reason -> invalid.apply(null, reason));
    }

    public static Map<String, String> sceneConstants(Map<String, DeviceManifest> manifests, boolean enablePrivacy) {
        Map<String, String> constants = new LinkedHashMap<>();
        manifests.forEach((device, manifest) -> constants(manifest, enablePrivacy).forEach((token, source) ->
                constants.putIfAbsent(token, source + " on device '" + device + "'")));
        return constants;
    }

    private static Map<String, String> constants(DeviceManifest manifest, boolean enablePrivacy) {
        Map<String, List<String>> modeStates = DeviceManifestModes.modeStates(manifest);
        List<DeviceManifest.InternalVariable> variables = manifest.getInternalVariables() == null
                ? List.of() : manifest.getInternalVariables();
        List<DeviceManifest.Content> contents = manifest.getContents() == null ? List.of() : manifest.getContents();

        // Unlike names of fields, enum literals are case-sensitive and may appear in several domains.
        Map<String, String> constants = new LinkedHashMap<>();
        modeStates.forEach((mode, states) -> states.forEach(value ->
                constants.putIfAbsent(value, "working-state value of mode '" + mode + "'")));
        for (DeviceManifest.InternalVariable variable : variables) {
            if (variable == null || variable.getValues() == null) continue;
            for (String value : variable.getValues()) {
                if (value != null) constants.putIfAbsent(value.replace(" ", ""),
                        "enum value of InternalVariable '" + variable.getName() + "'");
            }
        }
        boolean hasLabeledState = !modeStates.isEmpty() || !variables.isEmpty();
        if (hasLabeledState) {
            addLabelConstants(constants, PropertyDimension.TRUST);
        }
        if (enablePrivacy && (hasLabeledState || !contents.isEmpty())) {
            addLabelConstants(constants, PropertyDimension.PRIVACY);
        }
        return constants;
    }

    private static void validateIdentifiers(DeviceManifest manifest, Map<String, String> constants,
                                            boolean enablePrivacy, boolean hasAttackFlag,
                                            Function<String, ? extends RuntimeException> invalid) {
        DeviceManifestModes.authoredStatesByMode(manifest, invalid);
        Map<String, List<String>> modeStates = DeviceManifestModes.modeStates(manifest);
        List<DeviceManifest.InternalVariable> variables = manifest.getInternalVariables() == null
                ? List.of() : manifest.getInternalVariables();
        List<DeviceManifest.Content> contents = manifest.getContents() == null ? List.of() : manifest.getContents();
        Map<String, String> identifiers = new LinkedHashMap<>();
        if (hasAttackFlag) {
            register(identifiers, constants, SmvConstants.DEVICE_ATTACK_FLAG, "generated attack flag", invalid);
        }
        for (String mode : DeviceManifestModes.modeNames(manifest)) {
            String emitted = DeviceSmvDataFactory.sanitizeSmvToken(mode);
            requireTraceIdentifier(emitted, "mode '" + mode + "'", invalid);
            register(identifiers, constants, emitted, "mode '" + mode + "'", invalid);
            for (String state : modeStates.getOrDefault(mode, List.of())) {
                String suffix = emitted + "_" + state;
                registerLabels(identifiers, constants, suffix, "state '" + suffix + "'", enablePrivacy, invalid);
            }
        }
        for (DeviceManifest.InternalVariable variable : variables) {
            if (variable == null || variable.getName() == null) continue;
            String name = variable.getName();
            String source = "InternalVariable '" + name + "'";
            requireTraceIdentifier(name, source, invalid);
            register(identifiers, constants, name, source, invalid);
            registerLabels(identifiers, constants, name, source, enablePrivacy, invalid);
        }
        if (manifest.getImpactedVariables() != null) {
            for (String name : manifest.getImpactedVariables()) {
                var domain = EnvironmentDomainUtils.resolveImpactDomain(manifest, name);
                if (domain != null && domain.getLowerBound() != null && domain.getUpperBound() != null) {
                    register(identifiers, constants, name + "_rate", "generated rate for ImpactedVariable '" + name + "'", invalid);
                }
            }
        }
        if (manifest.getApis() != null) {
            for (DeviceManifest.API api : manifest.getApis()) {
                if (api != null && Boolean.TRUE.equals(api.getSignal())) {
                    register(identifiers, constants, DeviceSmvDataFactory.formatApiSignalName(api.getName()),
                            "generated signal for API '" + api.getName() + "'", invalid);
                }
            }
        }
        if (enablePrivacy) {
            for (DeviceManifest.Content content : contents) {
                if (content != null && content.getName() != null && !content.getName().isBlank()) {
                    register(identifiers, constants, PropertyDimension.PRIVACY.prefix + content.getName(),
                            "generated privacy for Content '" + content.getName() + "'", invalid);
                }
            }
        }
    }

    private static void addLabelConstants(Map<String, String> constants, PropertyDimension dimension) {
        String source = "generated " + dimension.name().toLowerCase(Locale.ROOT) + " label value";
        constants.putIfAbsent(dimension.activeValue, source);
        constants.putIfAbsent(dimension.inactiveValue, source);
    }

    private static void registerLabels(Map<String, String> identifiers, Map<String, String> constants,
                                       String name, String source, boolean enablePrivacy,
                                       Function<String, ? extends RuntimeException> invalid) {
        register(identifiers, constants, PropertyDimension.TRUST.prefix + name, "generated trust for " + source, invalid);
        if (enablePrivacy) {
            register(identifiers, constants, PropertyDimension.PRIVACY.prefix + name,
                    "generated privacy for " + source, invalid);
        }
    }

    private static void register(Map<String, String> identifiers, Map<String, String> constants,
                                 String identifier, String source, Function<String, ? extends RuntimeException> invalid) {
        if (identifier == null || identifier.isBlank()) return;
        String folded = identifier.toLowerCase(Locale.ROOT);
        String previous = identifiers.putIfAbsent(folded, source);
        if (previous == null) previous = constants.get(identifier);
        if (previous != null) {
            throw invalid.apply("generated NuSMV identifier '" + identifier + "' from " + source
                    + " collides with " + previous + ". Rename the user-authored item to give it a distinct symbol.");
        }
    }

    private static void requireTraceIdentifier(String identifier, String source,
                                               Function<String, ? extends RuntimeException> invalid) {
        if (identifier.startsWith("__")) {
            throw invalid.apply("NuSMV hides " + source + " ('" + identifier + "') from traces because its name "
                    + "starts with '__'. Use a name without that prefix.");
        }
    }

    private static void validateSharedPoolNames(Map<String, DeviceManifest> manifests, Map<String, String> constants,
                                                BiFunction<String, String, ? extends RuntimeException> invalid) {
        manifests.forEach((device, manifest) -> {
            if (manifest.getInternalVariables() == null) return;
            for (DeviceManifest.InternalVariable variable : manifest.getInternalVariables()) {
                if (variable == null || variable.getName() == null || Boolean.TRUE.equals(variable.getIsInside())) {
                    continue;
                }
                String name = variable.getName();
                String alias = SmvConstants.ENVIRONMENT_POOL_PREFIX + name;
                String constant = constants.get(alias);
                if (constant != null) {
                    throw invalid.apply(device, "generated NuSMV identifier '" + alias + "' for shared environment "
                            + "value '" + name + "' collides with " + constant
                            + ". Rename the shared variable or use a distinct enum value.");
                }
            }
        });
    }

    private static void validateMainControlNames(Map<String, String> constants, int ruleCount, boolean enableAttack,
                                                 Function<String, ? extends RuntimeException> invalid) {
        Map<String, String> identifiers = new LinkedHashMap<>();
        if (enableAttack) {
            register(identifiers, constants, SmvConstants.NUSMV_COMPROMISED_POINT_COUNT,
                    "the compromised-point counter", invalid);
        }
        for (int index = 0; index < ruleCount; index++) {
            register(identifiers, constants, SmvConstants.RULE_EXECUTION_PROBE_PREFIX + index,
                    "rule playback tracking", invalid);
            if (enableAttack) {
                register(identifiers, constants, SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX + index,
                        "the automation-link attack flag", invalid);
            }
        }
    }
}
