package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.AttackSurface;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import cn.edu.nju.Iot_Verify.util.SpecConditionNormalization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class NusmvRequestValidator {

    private NusmvRequestValidator() {
    }

    private record MainIdentifier(String field, String source) {
    }

    static void validateDevices(List<DeviceVerificationDto> devices, Map<String, String> errors) {
        for (int i = 0; i < devices.size(); i++) {
            DeviceVerificationDto device = devices.get(i);
            String prefix = "devices[" + i + "]";
            requireText(errors, prefix + ".varName", device.getVarName(), "Device varName is required");
            if (hasText(device.getVarName())) {
                String normalized = DeviceNameNormalizer.normalize(device.getVarName());
                if (!device.getVarName().equals(normalized)) {
                    putError(errors, prefix + ".varName",
                            "Device varName must be the NuSMV-safe normalized node id");
                }
            }
            requireText(errors, prefix + ".templateName", device.getTemplateName(), "Template name is required");
            rejectNullItems(errors, prefix + ".variables", device.getVariables(), "Variable item cannot be null");
            rejectNullItems(errors, prefix + ".privacies", device.getPrivacies(), "Privacy item cannot be null");
        }
    }

    static void validateDeviceSemantics(List<DeviceVerificationDto> devices,
                                        Map<String, DeviceSmvData> deviceSmvMap,
                                        Map<String, String> errors) {
        if (devices == null || devices.isEmpty() || deviceSmvMap == null || deviceSmvMap.isEmpty()) {
            return;
        }
        for (int i = 0; i < devices.size(); i++) {
            DeviceVerificationDto device = devices.get(i);
            if (device == null) {
                continue;
            }
            DeviceSmvData smv = smvForDevice(device.getVarName(), deviceSmvMap);
            if (smv == null) {
                continue;
            }
            boolean hasModes = smv.getModes() != null && !smv.getModes().isEmpty();
            String prefix = "devices[" + i + "]";
            if (hasModes) {
                if (hasText(device.getState())) {
                    validateStateValues(errors, prefix + ".state", smv, "=", device.getState());
                }
                if (device.getCurrentStateTrust() != null) {
                    validateLiteralValues(errors, prefix + ".currentStateTrust", "=",
                            device.getCurrentStateTrust(), Set.of("trusted", "untrusted"),
                            "trusted or untrusted");
                }
                if (device.getCurrentStatePrivacy() != null) {
                    validateLiteralValues(errors, prefix + ".currentStatePrivacy", "=",
                            device.getCurrentStatePrivacy(), Set.of("public", "private"),
                            "public or private");
                }
            } else {
                if (hasText(device.getState())) {
                    putError(errors, prefix + ".state",
                            "No-mode devices must omit state in model requests");
                }
                if (device.getCurrentStateTrust() != null) {
                    putError(errors, prefix + ".currentStateTrust",
                            "currentStateTrust is only valid for device templates with modes");
                }
                if (device.getCurrentStatePrivacy() != null) {
                    putError(errors, prefix + ".currentStatePrivacy",
                            "currentStatePrivacy is only valid for device templates with modes");
                }
            }
            validateDeviceVariables(device.getVariables(), smv, prefix + ".variables", errors);
            validateDevicePrivacies(device.getPrivacies(), smv, prefix + ".privacies", errors);
        }
    }

    static void validateMainNamespace(List<DeviceVerificationDto> devices,
                                      List<RuleDto> rules,
                                      Map<String, DeviceSmvData> deviceSmvMap,
                                      boolean isAttack,
                                      Map<String, String> errors) {
        if (devices == null || devices.isEmpty() || deviceSmvMap == null || deviceSmvMap.isEmpty()) {
            return;
        }

        Map<String, MainIdentifier> identifiers = new LinkedHashMap<>();
        if (isAttack) {
            registerMainIdentifier(errors, identifiers, SmvConstants.NUSMV_COMPROMISED_POINT_COUNT,
                    "isAttack", "generated compromised-point counter");
        }

        for (int i = 0; i < devices.size(); i++) {
            DeviceVerificationDto device = devices.get(i);
            if (device == null || !hasText(device.getVarName())) {
                continue;
            }
            DeviceSmvData smv = smvForDevice(device.getVarName(), deviceSmvMap);
            String varName = smv != null && hasText(smv.getVarName()) ? smv.getVarName() : device.getVarName();
            registerMainIdentifier(errors, identifiers, varName, "devices[" + i + "].varName",
                    "device instance '" + varName + "'");
            rejectFixGeneratedPrefix(errors, varName, "devices[" + i + "].varName");
        }

        Map<String, DeviceManifest.InternalVariable> requiredEnvironmentVariables =
                NusmvEnvironmentPool.collectRequired(deviceSmvMap);
        for (String envName : requiredEnvironmentVariables.keySet()) {
            registerMainIdentifier(errors, identifiers, "a_" + envName, "environmentVariables",
                    "generated environment variable for '" + envName + "'");
        }
        if (rules != null) {
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                registerMainIdentifier(errors, identifiers,
                        SmvConstants.RULE_EXECUTION_PROBE_PREFIX + ruleIndex,
                        "rules[" + ruleIndex + "]",
                        "generated rule execution marker");
                if (isAttack) {
                    registerMainIdentifier(errors, identifiers,
                            SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX + ruleIndex,
                            "rules[" + ruleIndex + "]",
                            "generated automation-link attack choice");
                }
            }
        }
    }

    static void validateAttackHasModeledEffect(boolean isAttack,
                                               List<RuleDto> rules,
                                               Map<String, DeviceSmvData> deviceSmvMap,
                                               Map<String, String> errors) {
        if (!isAttack) {
            return;
        }
        if (AttackSurface.analyze(rules, deviceSmvMap).totalCount() > 0) {
            return;
        }
        putError(errors, "attackScenario",
                "Attack modeling would not change this model: the scene has no automation command-delivery "
                        + "links and no device reading marked FalsifiableWhenCompromised. Disable attack modeling, "
                        + "or add an applicable rule or explicitly falsifiable sensor/received-data variable.");
    }

    /*
     * A device may not take a name the automatic fixer will mint.
     *
     * The other generated identifiers are namespaced (`iot_verify_…`) and registered below by their concrete
     * names. Condition adjustment's guard probes are namespaced too, but their names are only known once a fix
     * runs, so they are reserved by prefix here. The fix strategies' `param_` / `lambda_` / `condition_value_`
     * are not namespaced at all, and `param_` cannot be renamed — it is the
     * wire format for `PreferredRangeSelection.targetId`, validated by a `@Pattern` on the DTO and by
     * `^param_[A-Za-z0-9_-]{24}$` in the frontend's `fixResponse.ts`.
     *
     * So the collision was real and reachable: a device named `condition_value_r0_c1` is accepted by
     * `/api/board/nodes` (verified against the running API), saves, and verifies — then `SmvMainModuleBuilder`
     * throws when the user asks for a fix, which is correct but arrives long after the mistake was made and
     * blames a surface the user was not editing. Rejecting the name here says it at the moment it is chosen.
     */
    private static void rejectFixGeneratedPrefix(Map<String, String> errors, String varName, String field) {
        if (!hasText(varName)) {
            return;
        }
        String normalized = varName.trim().toLowerCase(Locale.ROOT);
        for (String prefix : SmvConstants.FIX_GENERATED_NAME_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                putError(errors, field,
                        "Device name '" + varName + "' starts with '" + prefix + "', which the automatic-fix "
                                + "generator reserves for its own variables. Rename the device.");
                return;
            }
        }
    }

    private static void registerMainIdentifier(Map<String, String> errors,
                                               Map<String, MainIdentifier> identifiers,
                                               String identifier,
                                               String field,
                                               String source) {
        if (!hasText(identifier)) {
            return;
        }
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        MainIdentifier previous = identifiers.putIfAbsent(normalized, new MainIdentifier(field, source));
        if (previous != null) {
            putError(errors, field,
                    "NuSMV main namespace identifier '" + identifier + "' from " + source
                            + " collides with " + previous.source());
        }
    }

    static void validateEnvironmentVariables(List<BoardEnvironmentVariableDto> environmentVariables,
                                             Map<String, DeviceSmvData> deviceSmvMap,
                                             Map<String, String> errors) {
        Map<String, DeviceManifest.InternalVariable> required = NusmvEnvironmentPool.collectRequired(deviceSmvMap);
        Set<String> seen = new HashSet<>();
        Set<String> provided = new HashSet<>();
        List<BoardEnvironmentVariableDto> values = environmentVariables == null ? List.of() : environmentVariables;

        for (int i = 0; i < values.size(); i++) {
            BoardEnvironmentVariableDto variable = values.get(i);
            String prefix = "environmentVariables[" + i + "]";
            if (variable == null) {
                putError(errors, prefix, "Environment variable item cannot be null");
                continue;
            }
            String name = trimToNull(variable.getName());
            if (name == null) {
                putError(errors, prefix + ".name", "Environment variable name is required");
                continue;
            }
            if (!seen.add(name)) {
                putError(errors, prefix + ".name", "Duplicate environment variable: " + name);
            }
            DeviceManifest.InternalVariable definition = required.get(name);
            if (definition == null) {
                putError(errors, prefix + ".name",
                        "Environment variable is not required by the current board: " + name);
                continue;
            }
            provided.add(name);
            if (variable.getTrust() != null) {
                validateLiteralValues(errors, prefix + ".trust", "=", variable.getTrust(),
                        Set.of("trusted", "untrusted"), "trusted or untrusted");
            }
            if (variable.getPrivacy() != null) {
                validateLiteralValues(errors, prefix + ".privacy", "=", variable.getPrivacy(),
                        Set.of("public", "private"), "public or private");
            }
            String value = trimToNull(variable.getValue());
            if (hasValueDomain(definition) && value == null) {
                putError(errors, prefix + ".value", "Environment variable value is required for " + name);
            } else if (value != null) {
                validateVariableValues(errors, prefix + ".value", definition, "=", value);
            }
        }

        for (String requiredName : required.keySet()) {
            if (!provided.contains(requiredName)) {
                putError(errors, "environmentVariables",
                        "Missing environment variable required by the board: " + requiredName);
            }
        }
    }

    /** Validate caller-authored overrides before default merging can collapse or replace them. */
    static void validateEnvironmentVariableOverrides(
            List<BoardEnvironmentVariableDto> environmentVariables,
            Map<String, DeviceSmvData> deviceSmvMap,
            Map<String, String> errors) {
        Map<String, DeviceManifest.InternalVariable> required = NusmvEnvironmentPool.collectRequired(deviceSmvMap);
        Set<String> seen = new HashSet<>();
        List<BoardEnvironmentVariableDto> values = environmentVariables == null
                ? List.of() : environmentVariables;
        for (int i = 0; i < values.size(); i++) {
            BoardEnvironmentVariableDto variable = values.get(i);
            String prefix = "environmentVariables[" + i + "]";
            if (variable == null) {
                putError(errors, prefix, "Environment variable item cannot be null");
                continue;
            }
            String name = trimToNull(variable.getName());
            if (name == null) {
                putError(errors, prefix + ".name", "Environment variable name is required");
                continue;
            }
            if (!seen.add(name)) {
                putError(errors, prefix + ".name", "Duplicate environment variable: " + name);
            }
            DeviceManifest.InternalVariable definition = required.get(name);
            if (definition == null) {
                putError(errors, prefix + ".name",
                        "Environment variable is not required by the current board: " + name);
                continue;
            }
            if (variable.getTrust() != null) {
                validateLiteralValues(errors, prefix + ".trust", "=", variable.getTrust(),
                        Set.of("trusted", "untrusted"), "trusted or untrusted");
            }
            if (variable.getPrivacy() != null) {
                validateLiteralValues(errors, prefix + ".privacy", "=", variable.getPrivacy(),
                        Set.of("public", "private"), "public or private");
            }
            if (variable.getValue() != null && !hasText(variable.getValue())) {
                putError(errors, prefix + ".value", "Environment variable value cannot be blank when provided");
            } else if (hasText(variable.getValue())) {
                validateVariableValues(errors, prefix + ".value", definition, "=", variable.getValue());
            }
        }
    }


    private static void validateDeviceVariables(List<VariableStateDto> variables,
                                                DeviceSmvData smv,
                                                String field,
                                                Map<String, String> errors) {
        if (variables == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < variables.size(); i++) {
            VariableStateDto variable = variables.get(i);
            String prefix = field + "[" + i + "]";
            if (variable == null) {
                putError(errors, prefix, "Variable item cannot be null");
                continue;
            }
            String name = trimToNull(variable.getName());
            String value = trimToNull(variable.getValue());
            if (name == null) {
                putError(errors, prefix + ".name", "Variable name is required");
                continue;
            }
            if (!seen.add(name)) {
                putError(errors, prefix + ".name", "Duplicate variable override: " + name);
            }
            if (value == null) {
                putError(errors, prefix + ".value", "Variable value is required");
            }
            if (variable.getTrust() != null) {
                validateLiteralValues(errors, prefix + ".trust", "=", variable.getTrust(),
                        Set.of("trusted", "untrusted"), "trusted or untrusted");
            }

            DeviceManifest.InternalVariable manifestVariable = internalVariable(smv, name);
            if (manifestVariable == null) {
                putError(errors, prefix + ".name", "Unknown runtime variable for device template: " + name);
            } else if (isEnvironmentVariable(manifestVariable)) {
                putError(errors, prefix + ".name",
                        "Environment variable must be supplied through environmentVariables: " + name);
            } else if (value != null) {
                validateVariableValues(errors, prefix + ".value", manifestVariable, "=", value);
            }
        }
    }

    private static void validateDevicePrivacies(List<PrivacyStateDto> privacies,
                                                DeviceSmvData smv,
                                                String field,
                                                Map<String, String> errors) {
        if (privacies == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < privacies.size(); i++) {
            PrivacyStateDto privacy = privacies.get(i);
            String prefix = field + "[" + i + "]";
            if (privacy == null) {
                putError(errors, prefix, "Privacy item cannot be null");
                continue;
            }
            String name = trimToNull(privacy.getName());
            String value = trimToNull(privacy.getPrivacy());
            if (name == null) {
                putError(errors, prefix + ".name", "Privacy target name is required");
                continue;
            }
            if (!seen.add(name)) {
                putError(errors, prefix + ".name", "Duplicate privacy override: " + name);
            }
            if (value == null) {
                putError(errors, prefix + ".privacy", "Privacy value is required");
            } else {
                validateLiteralValues(errors, prefix + ".privacy", "=", value,
                        Set.of("public", "private"), "public or private");
            }
            DeviceManifest.InternalVariable manifestVariable = internalVariable(smv, name);
            if (manifestVariable == null) {
                putError(errors, prefix + ".name",
                        "Unknown device-local variable privacy target: " + name);
            } else if (isEnvironmentVariable(manifestVariable)) {
                putError(errors, prefix + ".name",
                        "Environment variable privacy must be supplied through environmentVariables: " + manifestVariable.getName());
            }
        }
    }

    static void validateRules(List<RuleDto> rules,
                              List<DeviceVerificationDto> devices,
                              Map<String, String> errors) {
        Set<String> deviceRefs = deviceRefs(devices);
        Set<Long> persistedRuleIds = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            RuleDto rule = rules.get(i);
            String prefix = "rules[" + i + "]";
            Long ruleId = rule.getId();
            if (ruleId != null) {
                if (ruleId <= 0) {
                    putError(errors, prefix + ".id", "Rule id must be positive when provided");
                } else if (!persistedRuleIds.add(ruleId)) {
                    putError(errors, prefix + ".id", "Rule id must be unique within one model request");
                }
            }
            if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
                putError(errors, prefix + ".conditions", "Conditions cannot be empty");
            } else {
                rejectNullItems(errors, prefix + ".conditions", rule.getConditions(),
                        "Condition item cannot be null");
                for (int j = 0; j < rule.getConditions().size(); j++) {
                    RuleDto.Condition condition = rule.getConditions().get(j);
                    if (condition == null) {
                        continue;
                    }
                    validateRuleCondition(condition, prefix + ".conditions[" + j + "]", deviceRefs, errors);
                }
            }
            RuleDto.Command command = rule.getCommand();
            if (command == null) {
                putError(errors, prefix + ".command", "Command cannot be null");
                continue;
            }
            requireText(errors, prefix + ".command.deviceName",
                    command.getDeviceName(), "Command device name is required");
            requireKnownDevice(errors, prefix + ".command.deviceName",
                    command.getDeviceName(), deviceRefs, "Unknown command device");
            requireText(errors, prefix + ".command.action",
                    command.getAction(), "Command action is required");
            if (hasText(command.getContentDevice()) != hasText(command.getContent())) {
                putError(errors, prefix + ".command.content",
                        "contentDevice and content must be provided together");
            }
            if (hasText(command.getContentDevice())) {
                requireKnownDevice(errors, prefix + ".command.contentDevice",
                        command.getContentDevice(), deviceRefs, "Unknown content device");
            }
        }
    }

    static void validateSpecifications(List<SpecificationDto> specs,
                                       List<DeviceVerificationDto> devices,
                                       Map<String, String> errors) {
        Set<String> deviceRefs = deviceRefs(devices);
        for (int i = 0; i < specs.size(); i++) {
            SpecificationDto spec = specs.get(i);
            String prefix = "specs[" + i + "]";
            requireText(errors, prefix + ".id", spec.getId(), "Specification ID is required");
            validateTemplateId(spec.getTemplateId(), prefix + ".templateId", errors);
            validateSpecTemplateShape(spec, prefix, errors);
            validateConditionList(spec.getAConditions(), prefix + ".aConditions", "a", deviceRefs, errors);
            validateConditionList(spec.getIfConditions(), prefix + ".ifConditions", "if", deviceRefs, errors);
            validateConditionList(spec.getThenConditions(), prefix + ".thenConditions", "then", deviceRefs, errors);
            validateSafetyTemplateConditions(spec, prefix, errors);
            validateSpecDeviceRefs(spec.getDevices(), prefix + ".devices", deviceRefs, errors);
        }
    }

    private static void validateSafetyTemplateConditions(SpecificationDto spec,
                                                         String prefix,
                                                         Map<String, String> errors) {
        if (spec == null || !"7".equals(spec.getTemplateId()) || spec.getAConditions() == null) {
            return;
        }
        for (int i = 0; i < spec.getAConditions().size(); i++) {
            SpecConditionDto condition = spec.getAConditions().get(i);
            if (condition == null) {
                continue;
            }
            String conditionPrefix = prefix + ".aConditions[" + i + "]";
            String targetType = normalizeSpecTargetType(condition.getTargetType());
            if ("trust".equals(targetType) || "privacy".equals(targetType)) {
                putError(errors, conditionPrefix + ".targetType",
                        "Safety conditions must describe the event or value to protect; "
                                + "the MEDIC control-source label is derived automatically. Use Never for explicit trust/privacy predicates.");
                continue;
            }
            String relation = normalizeKnownRelation(condition.getRelation());
            if (("state".equals(targetType) || "mode".equals(targetType)) && !"=".equals(relation)) {
                putError(errors, conditionPrefix + ".relation",
                        "Safety state and mode conditions require '=' so the matching source label is unambiguous");
            }
            if ("api".equals(targetType)
                    && (!"=".equals(relation) || !"TRUE".equalsIgnoreCase(trimToNull(condition.getValue())))) {
                putError(errors, conditionPrefix,
                        "Safety API conditions must use '= TRUE' to identify the protected event");
            }
        }
    }

    static void validateRuleSemantics(List<RuleDto> rules,
                                      Map<String, DeviceSmvData> deviceSmvMap,
                                      Map<String, String> errors) {
        if (rules == null || rules.isEmpty() || deviceSmvMap == null || deviceSmvMap.isEmpty()) {
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            RuleDto rule = rules.get(i);
            if (rule == null) {
                continue;
            }
            String prefix = "rules[" + i + "]";
            RuleDto.Command command = rule.getCommand();
            if (command != null) {
                DeviceSmvData target = smvForDevice(command.getDeviceName(), deviceSmvMap);
                DeviceManifest.API actionApi = target != null
                        ? findApi(target.getManifest(), command.getAction()) : null;
                if (target != null && hasText(command.getAction()) && actionApi == null) {
                    putError(errors, prefix + ".command.action",
                            "Unknown command API for device template: " + command.getAction());
                }
                if (hasText(command.getContentDevice()) != hasText(command.getContent())) {
                    putError(errors, prefix + ".command.content",
                            "contentDevice and content must be provided together");
                } else if (hasText(command.getContent())) {
                    if (actionApi != null && !Boolean.TRUE.equals(actionApi.getAcceptsContent())) {
                        putError(errors, prefix + ".command.content",
                                "Command API '" + actionApi.getName()
                                        + "' does not accept a content-sensitivity input");
                    }
                    DeviceSmvData contentDevice = smvForDevice(command.getContentDevice(), deviceSmvMap);
                    if (contentDevice != null && !hasContent(contentDevice.getManifest(), command.getContent())) {
                        putError(errors, prefix + ".command.content",
                                "Unknown content for device template: " + command.getContent());
                    }
                }
            }

            if (rule.getConditions() == null) {
                continue;
            }
            for (int j = 0; j < rule.getConditions().size(); j++) {
                validateRuleConditionSemantics(rule.getConditions().get(j),
                        prefix + ".conditions[" + j + "]", deviceSmvMap, errors);
            }
        }
    }

    static void validateSpecificationSemantics(List<SpecificationDto> specs,
                                               Map<String, DeviceSmvData> deviceSmvMap,
                                               Map<String, String> errors) {
        if (specs == null || specs.isEmpty() || deviceSmvMap == null || deviceSmvMap.isEmpty()) {
            return;
        }
        for (int i = 0; i < specs.size(); i++) {
            SpecificationDto spec = specs.get(i);
            if (spec == null) {
                continue;
            }
            validateSpecConditionSemantics(spec.getAConditions(), "specs[" + i + "].aConditions",
                    deviceSmvMap, errors);
            validateSpecConditionSemantics(spec.getIfConditions(), "specs[" + i + "].ifConditions",
                    deviceSmvMap, errors);
            validateSpecConditionSemantics(spec.getThenConditions(), "specs[" + i + "].thenConditions",
                    deviceSmvMap, errors);
            validateSafetySourceCapabilities(spec, "specs[" + i + "]", deviceSmvMap, errors);
        }
    }

    private static void validateSafetySourceCapabilities(SpecificationDto spec,
                                                         String prefix,
                                                         Map<String, DeviceSmvData> deviceSmvMap,
                                                         Map<String, String> errors) {
        if (spec == null || !"7".equals(spec.getTemplateId()) || spec.getAConditions() == null) {
            return;
        }
        for (int i = 0; i < spec.getAConditions().size(); i++) {
            SpecConditionDto condition = spec.getAConditions().get(i);
            if (condition == null || !"api".equals(normalizeSpecTargetType(condition.getTargetType()))) {
                continue;
            }
            DeviceSmvData smv = smvForDevice(condition.getDeviceId(), deviceSmvMap);
            DeviceManifest.API api = signalApi(smv == null ? null : smv.getManifest(), condition.getKey());
            if (api != null && !hasText(api.getEndState())) {
                putError(errors, prefix + ".aConditions[" + i + "].key",
                        "Template 7 cannot derive a MEDIC control-source label for API '"
                                + condition.getKey() + "' because its device template has no EndState");
            }
        }
    }

    static Map<String, String> newErrors() {
        return new LinkedHashMap<>();
    }

    static void throwIfErrors(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private static void validateRuleConditionSemantics(RuleDto.Condition condition,
                                                       String prefix,
                                                       Map<String, DeviceSmvData> deviceSmvMap,
                                                       Map<String, String> errors) {
        if (condition == null) {
            return;
        }
        DeviceSmvData smv = smvForDevice(condition.getDeviceName(), deviceSmvMap);
        if (smv == null || smv.getManifest() == null) {
            return;
        }
        String targetType = normalizeRuleTargetType(condition.getTargetType());
        String attribute = trimToNull(condition.getAttribute());
        if (targetType == null || attribute == null) {
            return;
        }

        if ("api".equals(targetType)) {
            if (hasText(condition.getRelation()) || hasText(condition.getValue())) {
                putError(errors, prefix, "API signal conditions must not include relation or value");
            }
            if (!hasSignalApi(smv.getManifest(), attribute)) {
                putError(errors, prefix + ".attribute",
                        "Unknown or non-signal API for rule condition: " + attribute);
            }
            return;
        }

        String relation = normalizeKnownRelation(condition.getRelation());
        if (relation == null || !hasText(condition.getValue())) {
            return;
        }
        switch (targetType) {
            case "variable" -> {
                DeviceManifest.InternalVariable variable = internalVariable(smv, attribute);
                if (variable == null) {
                    putError(errors, prefix + ".attribute",
                            "Unknown internal variable for rule condition: " + attribute);
                } else {
                    validateVariableValues(errors, prefix + ".value", variable, relation, condition.getValue());
                }
            }
            case "mode" -> {
                String mode = resolveModeName(smv, attribute);
                if (mode == null) {
                    putError(errors, prefix + ".attribute", "Unknown mode for rule condition: " + attribute);
                } else {
                    validateEnumRelation(errors, prefix + ".relation", relation, "mode");
                    validateModeValues(errors, prefix + ".value", smv, mode, relation, condition.getValue());
                }
            }
            case "state" -> {
                if (!"state".equalsIgnoreCase(attribute)) {
                    putError(errors, prefix + ".attribute", "State rule conditions must use attribute 'state'");
                } else {
                    validateEnumRelation(errors, prefix + ".relation", relation, "state");
                    validateStateValues(errors, prefix + ".value", smv, relation, condition.getValue());
                }
            }
            default -> {
                // Structural validation reports unsupported targetType.
            }
        }
    }

    private static void validateSpecConditionSemantics(List<SpecConditionDto> conditions,
                                                       String field,
                                                       Map<String, DeviceSmvData> deviceSmvMap,
                                                       Map<String, String> errors) {
        if (conditions == null) {
            return;
        }
        for (int i = 0; i < conditions.size(); i++) {
            SpecConditionDto condition = conditions.get(i);
            String prefix = field + "[" + i + "]";
            if (condition == null) {
                continue;
            }
            DeviceSmvData smv = smvForDevice(condition.getDeviceId(), deviceSmvMap);
            if (smv == null || smv.getManifest() == null) {
                continue;
            }
            String targetType = normalizeSpecTargetType(condition.getTargetType());
            String key = trimToNull(condition.getKey());
            String relation = normalizeKnownRelation(condition.getRelation());
            if (targetType == null || key == null || relation == null || !hasText(condition.getValue())) {
                continue;
            }

            switch (targetType) {
                case "api" -> {
                    validateEnumRelation(errors, prefix + ".relation", relation, "API signal");
                    if (!hasSignalApi(smv.getManifest(), key)) {
                        putError(errors, prefix + ".key", "Unknown or non-signal API for specification: " + key);
                    }
                    validateLiteralValues(errors, prefix + ".value", relation, condition.getValue(),
                            Set.of("TRUE", "FALSE"), "TRUE or FALSE");
                }
                case "state" -> {
                    validateEnumRelation(errors, prefix + ".relation", relation, "state");
                    if (!"state".equalsIgnoreCase(key)) {
                        putError(errors, prefix + ".key", "State specification conditions must use key 'state'");
                    }
                    validateStateValues(errors, prefix + ".value", smv, relation, condition.getValue());
                }
                case "mode" -> {
                    validateEnumRelation(errors, prefix + ".relation", relation, "mode");
                    String mode = resolveModeName(smv, key);
                    if (mode == null) {
                        putError(errors, prefix + ".key", "Unknown mode for specification: " + key);
                    } else {
                        validateModeValues(errors, prefix + ".value", smv, mode, relation, condition.getValue());
                    }
                }
                case "variable" -> {
                    DeviceManifest.InternalVariable variable = variableOrEnvKey(smv, key);
                    if (variable == null) {
                        putError(errors, prefix + ".key",
                                "Unknown internal or environment variable for specification: " + key);
                    } else {
                        validateVariableValues(errors, prefix + ".value", variable, relation, condition.getValue());
                        // Stricter than the storage side's *shared* semantic validation, which deliberately
                        // tolerates an absent reading because it also runs over already-stored
                        // specifications to guard unrelated device and rule writes. A verify request is
                        // authoring a run, so it must have one: otherwise a condition that never chose its
                        // question reaches the generator and is reported as a skipped specification — a run
                        // that silently answers less than it was asked. (Board storage demands the choice on
                        // its own authoring paths, `addSpec` and `/board/batch`.) `environment` additionally
                        // needs a pool value to read, which a device-local declaration does not have.
                        String variableSource = normalizeVariableSource(condition.getVariableSource());
                        if (variableSource == null) {
                            putError(errors, prefix + ".variableSource",
                                    "variableSource is required for a variable condition and must be environment "
                                            + "(the value in the home) or reported (what this device said)");
                        } else if ("environment".equals(variableSource) && !isEnvironmentVariable(variable)) {
                            putError(errors, prefix + ".variableSource",
                                    "variableSource=environment needs a shared variable, but '" + key
                                            + "' exists only inside this device. Ask reported instead");
                        }
                    }
                }
                case "trust" -> {
                    validateEnumRelation(errors, prefix + ".relation", relation, "trust");
                    validatePropertyReference(errors, prefix, condition, smv);
                    validateLiteralValues(errors, prefix + ".value", relation, condition.getValue(),
                            Set.of("trusted", "untrusted"), "trusted or untrusted");
                }
                case "privacy" -> {
                    validateEnumRelation(errors, prefix + ".relation", relation, "privacy");
                    validatePropertyReference(errors, prefix, condition, smv);
                    validateLiteralValues(errors, prefix + ".value", relation, condition.getValue(),
                            Set.of("public", "private"), "public or private");
                }
                default -> {
                    // Structural validation reports unsupported targetType.
                }
            }
        }
    }

    private static void validateTemplateId(String templateId, String field, Map<String, String> errors) {
        if (!hasText(templateId)) {
            putError(errors, field, "Template ID is required");
            return;
        }
        if (!templateId.matches("^[1-7]$")) {
            putError(errors, field, "Template ID must be one of 1, 2, 3, 4, 5, 6, 7");
        }
    }

    private static void validateSpecTemplateShape(SpecificationDto spec, String prefix,
                                                  Map<String, String> errors) {
        String templateId = spec.getTemplateId();
        if (!hasText(templateId) || !templateId.matches("^[1-7]$")) {
            return;
        }
        int aCount = sizeOf(spec.getAConditions());
        int ifCount = sizeOf(spec.getIfConditions());
        int thenCount = sizeOf(spec.getThenConditions());
        boolean singleSide = Set.of("1", "2", "3", "7").contains(templateId);
        if (singleSide) {
            if (aCount == 0) {
                putError(errors, prefix + ".aConditions",
                        "Template " + templateId + " requires at least one A condition");
            }
            if (ifCount > 0 || thenCount > 0) {
                putError(errors, prefix + ".templateId",
                        "Template " + templateId + " uses A conditions only");
            }
            return;
        }
        if (aCount > 0) {
            putError(errors, prefix + ".aConditions",
                    "Template " + templateId + " uses IF/THEN conditions only");
        }
        if (ifCount == 0 || thenCount == 0) {
            putError(errors, prefix + ".ifConditions",
                    "Template " + templateId + " requires both IF and THEN conditions");
        }
    }

    private static int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static void validateConditionList(List<SpecConditionDto> conditions,
                                              String field,
                                              String expectedSide,
                                              Set<String> deviceRefs,
                                              Map<String, String> errors) {
        if (conditions == null) {
            putError(errors, field, conditionListNullMessage(expectedSide));
            return;
        }
        for (int i = 0; i < conditions.size(); i++) {
            SpecConditionDto condition = conditions.get(i);
            String prefix = field + "[" + i + "]";
            if (condition == null) {
                putError(errors, prefix, conditionItemNullMessage(expectedSide));
                continue;
            }
            requireText(errors, prefix + ".deviceId",
                    condition.getDeviceId(), "Device ID is required for spec condition");
            requireKnownDevice(errors, prefix + ".deviceId",
                    condition.getDeviceId(), deviceRefs, "Unknown spec condition device");
            validateTargetType(condition.getTargetType(), prefix + ".targetType", errors);
            requireText(errors, prefix + ".key", condition.getKey(), "Key is required for spec condition");
            String targetType = normalizeSpecTargetType(condition.getTargetType());
            String propertyScope = normalizePropertyScope(condition.getPropertyScope());
            if ("trust".equals(targetType) || "privacy".equals(targetType)) {
                if (propertyScope == null) {
                    putError(errors, prefix + ".propertyScope",
                            "propertyScope is required for trust/privacy and must be state or variable");
                }
            } else if (hasText(condition.getPropertyScope())) {
                putError(errors, prefix + ".propertyScope",
                        "propertyScope is only valid for trust/privacy conditions");
            }
            validateRelation(condition.getRelation(), prefix + ".relation", errors);
            requireText(errors, prefix + ".value", condition.getValue(), "Value is required for spec condition");
        }
    }

    private static void validateRuleCondition(RuleDto.Condition condition,
                                              String prefix,
                                              Set<String> deviceRefs,
                                              Map<String, String> errors) {
        requireText(errors, prefix + ".deviceName",
                condition.getDeviceName(), "Condition device name is required");
        requireKnownDevice(errors, prefix + ".deviceName",
                condition.getDeviceName(), deviceRefs, "Unknown condition device");
        requireText(errors, prefix + ".attribute",
                condition.getAttribute(), "Condition attribute is required");
        String targetType = condition.getTargetType();
        validateRuleTargetType(targetType, prefix + ".targetType", errors);
        boolean api = hasText(targetType) && "api".equalsIgnoreCase(targetType.trim());
        if (api) {
            if (hasText(condition.getRelation()) || hasText(condition.getValue())) {
                putError(errors, prefix, "API signal conditions must not include relation or value");
            }
            return;
        }
        validateRelation(condition.getRelation(), prefix + ".relation", errors);
        requireText(errors, prefix + ".value", condition.getValue(),
                "Value-based conditions require relation and value");
    }

    private static void validateRuleTargetType(String targetType, String field, Map<String, String> errors) {
        if (!hasText(targetType)) {
            putError(errors, field, "Condition targetType is required");
            return;
        }
        // `Locale.ROOT`, per the rule documented on `SmvSpecificationBuilder.normalizeSpecTargetType`: a Turkish
        // default locale maps `I` to `ı`, so `"API"` would lowercase to `"apı"` and match no keyword here — a
        // valid condition rejected as invalid, depending on the server's locale.
        String normalized = targetType.trim().toLowerCase(Locale.ROOT);
        if (!List.of("api", "variable", "mode", "state").contains(normalized)) {
            putError(errors, field, "Condition targetType must be one of api, variable, mode, state");
        }
    }

    private static void validateSpecDeviceRefs(List<SpecificationDto.DeviceRefDto> refs,
                                               String field,
                                               Set<String> deviceRefs,
                                               Map<String, String> errors) {
        if (refs == null) {
            putError(errors, field, "Specification device refs cannot be null");
            return;
        }
        for (int i = 0; i < refs.size(); i++) {
            SpecificationDto.DeviceRefDto ref = refs.get(i);
            String prefix = field + "[" + i + "]";
            if (ref == null) {
                putError(errors, prefix, "Specification device item cannot be null");
                continue;
            }
            requireText(errors, prefix + ".deviceId", ref.getDeviceId(), "Specification device ID is required");
            requireKnownDevice(errors, prefix + ".deviceId",
                    ref.getDeviceId(), deviceRefs, "Unknown specification device");
            rejectNullItems(errors, prefix + ".selectedApis", ref.getSelectedApis(),
                    "Selected API name cannot be null");
        }
    }

    private static void validateTargetType(String targetType, String field, Map<String, String> errors) {
        if (!hasText(targetType)) {
            putError(errors, field, "Target type is required for spec condition");
            return;
        }
        // `Locale.ROOT`, per the rule documented on `SmvSpecificationBuilder.normalizeSpecTargetType`: a Turkish
        // default locale maps `I` to `ı`, so `"API"` would lowercase to `"apı"` and match no keyword here — a
        // valid condition rejected as invalid, depending on the server's locale.
        String normalized = targetType.trim().toLowerCase(Locale.ROOT);
        if (!List.of("state", "mode", "variable", "api", "trust", "privacy").contains(normalized)) {
            putError(errors, field, "targetType must be one of: state, mode, variable, api, trust, privacy");
        }
    }

    private static void validateRelation(String relation, String field, Map<String, String> errors) {
        if (!hasText(relation)) {
            putError(errors, field, "Relation is required for spec condition");
            return;
        }
        String normalized = SmvRelationUtils.normalizeRelation(relation);
        if (!SmvRelationUtils.isSupportedRelation(normalized)) {
            putError(errors, field,
                    "Relation must be one of =, !=, >, >=, <, <=, in, not_in, not in, "
                            + "or aliases EQ, NEQ, GT, GTE, LT, LTE");
        }
    }

    private static void validateEnumRelation(Map<String, String> errors,
                                             String field,
                                             String relation,
                                             String label) {
        if (!"=".equals(relation) && !"!=".equals(relation)
                && !"in".equals(relation) && !"not in".equals(relation)) {
            putError(errors, field, label + " conditions support only =, !=, IN, and NOT_IN");
        }
    }

    private static void validateStateValues(Map<String, String> errors,
                                            String field,
                                            DeviceSmvData smv,
                                            String relation,
                                            String rawValue) {
        if (smv.getModes() == null || smv.getModes().isEmpty()
                || smv.getModeStates() == null || smv.getModeStates().isEmpty()) {
            putError(errors, field, "Device template has no legal states");
            return;
        }
        List<String> candidates = splitStateCandidates(rawValue, relation, smv);
        if (candidates.isEmpty()) {
            putError(errors, field, "State condition value cannot be empty");
            return;
        }
        if (("=".equals(relation) || "!=".equals(relation)) && candidates.size() != 1) {
            putError(errors, field, "State relation '" + relation + "' requires exactly one value");
            return;
        }
        for (String candidate : candidates) {
            if (!isLegalStateCandidate(smv, candidate)) {
                putError(errors, field, "Illegal state value for device template: " + candidate);
                return;
            }
        }
    }

    private static void validateModeValues(Map<String, String> errors,
                                           String field,
                                           DeviceSmvData smv,
                                           String mode,
                                           String relation,
                                           String rawValue) {
        List<String> legalValues = smv.getModeStates() != null ? smv.getModeStates().get(mode) : null;
        if (legalValues == null || legalValues.isEmpty()) {
            putError(errors, field, "Mode has no legal values: " + mode);
            return;
        }
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitRuleValues(rawValue)
                : List.of(rawValue.trim());
        if (candidates.isEmpty()) {
            putError(errors, field, "Mode condition value cannot be empty");
            return;
        }
        if (("=".equals(relation) || "!=".equals(relation)) && candidates.size() != 1) {
            putError(errors, field, "Mode relation '" + relation + "' requires exactly one value");
            return;
        }
        for (String candidate : candidates) {
            String cleaned = DeviceSmvDataFactory.cleanStateName(candidate);
            if (!hasText(cleaned) || !legalValues.contains(cleaned)) {
                putError(errors, field, "Illegal mode value '" + candidate + "' for mode '" + mode + "'");
                return;
            }
        }
    }

    private static void validateLiteralValues(Map<String, String> errors,
                                              String field,
                                              String relation,
                                              String rawValue,
                                              Set<String> allowed,
                                              String label) {
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitRuleValues(rawValue)
                : List.of(rawValue.trim());
        if (candidates.isEmpty()) {
            putError(errors, field, "Value must be " + label);
            return;
        }
        for (String candidate : candidates) {
            String normalized = candidate.trim();
            boolean found = allowed.stream().anyMatch(v -> v.equalsIgnoreCase(normalized));
            if (!found) {
                putError(errors, field, "Value must be " + label + ": " + candidate);
                return;
            }
        }
    }

    private static void validateVariableValues(Map<String, String> errors,
                                               String field,
                                               DeviceManifest.InternalVariable variable,
                                               String relation,
                                               String rawValue) {
        if (variable == null || !hasText(rawValue)) {
            return;
        }
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitRuleValues(rawValue)
                : List.of(rawValue.trim());
        if (candidates.isEmpty()) {
            putError(errors, field, "Variable condition value cannot be empty");
            return;
        }
        if (("=".equals(relation) || "!=".equals(relation)) && candidates.size() != 1) {
            putError(errors, field, "Variable relation '" + relation + "' requires exactly one value");
            return;
        }

        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            validateEnumRelation(errors, field, relation, "enum variable");
            Set<String> allowed = new HashSet<>();
            for (String value : variable.getValues()) {
                if (hasText(value)) {
                    allowed.add(cleanEnumLiteral(value));
                }
            }
            for (String candidate : candidates) {
                if (!allowed.contains(cleanEnumLiteral(candidate))) {
                    putError(errors, field, "Illegal enum value for variable '" + variable.getName() + "': " + candidate);
                    return;
                }
            }
            return;
        }

        if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
            int lower = variable.getLowerBound();
            int upper = variable.getUpperBound();
            for (String candidate : candidates) {
                try {
                    int parsed = Integer.parseInt(candidate.trim());
                    if (parsed < lower || parsed > upper) {
                        putError(errors, field, "Variable value out of range for '" + variable.getName()
                                + "': " + candidate + " (allowed " + lower + ".." + upper + ")");
                        return;
                    }
                } catch (NumberFormatException e) {
                    putError(errors, field, "Variable value must be an integer for '" + variable.getName()
                            + "': " + candidate);
                    return;
                }
            }
        }
    }

    private static <T> void rejectNullItems(Map<String, String> errors,
                                            String field,
                                            List<T> values,
                                            String message) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null) {
                putError(errors, field + "[" + i + "]", message);
            }
        }
    }

    private static void requireText(Map<String, String> errors, String field, String value, String message) {
        if (!hasText(value)) {
            putError(errors, field, message);
        }
    }

    private static void requireKnownDevice(Map<String, String> errors,
                                           String field,
                                           String value,
                                           Set<String> deviceRefs,
                                           String message) {
        if (!hasText(value) || deviceRefs.contains(value.trim())) {
            return;
        }
        putError(errors, field, message + ": " + value);
    }

    private static void putError(Map<String, String> errors, String field, String message) {
        errors.putIfAbsent(field, message);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static DeviceSmvData smvForDevice(String deviceRef, Map<String, DeviceSmvData> deviceSmvMap) {
        if (!hasText(deviceRef) || deviceSmvMap == null) {
            return null;
        }
        return deviceSmvMap.get(deviceRef.trim());
    }

    private static boolean hasApi(DeviceManifest manifest, String apiName) {
        return findApi(manifest, apiName) != null;
    }

    private static DeviceManifest.API findApi(DeviceManifest manifest, String apiName) {
        if (manifest == null || manifest.getApis() == null || !hasText(apiName)) {
            return null;
        }
        String trimmed = apiName.trim();
        for (DeviceManifest.API api : manifest.getApis()) {
            if (api != null && trimmed.equals(api.getName())) {
                return api;
            }
        }
        return null;
    }

    private static boolean hasSignalApi(DeviceManifest manifest, String apiName) {
        if (manifest == null || manifest.getApis() == null || !hasText(apiName)) {
            return false;
        }
        String trimmed = apiName.trim();
        for (DeviceManifest.API api : manifest.getApis()) {
            if (api != null && trimmed.equals(api.getName()) && Boolean.TRUE.equals(api.getSignal())) {
                return true;
            }
        }
        return false;
    }

    private static DeviceManifest.API signalApi(DeviceManifest manifest, String apiName) {
        if (manifest == null || manifest.getApis() == null || !hasText(apiName)) return null;
        String trimmed = apiName.trim();
        return manifest.getApis().stream()
                .filter(api -> api != null && trimmed.equals(api.getName())
                        && Boolean.TRUE.equals(api.getSignal()))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasContent(DeviceManifest manifest, String contentName) {
        if (manifest == null || manifest.getContents() == null || !hasText(contentName)) {
            return false;
        }
        String trimmed = contentName.trim();
        for (DeviceManifest.Content content : manifest.getContents()) {
            if (content != null && trimmed.equals(content.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInternalVariable(DeviceSmvData smv, String name) {
        return internalVariable(smv, name) != null;
    }

    private static boolean isEnvironmentVariable(DeviceManifest.InternalVariable variable) {
        return variable != null && !Boolean.TRUE.equals(variable.getIsInside());
    }

    private static boolean hasValueDomain(DeviceManifest.InternalVariable variable) {
        return variable != null
                && ((variable.getValues() != null && !variable.getValues().isEmpty())
                || (variable.getLowerBound() != null && variable.getUpperBound() != null));
    }

    /**
     * Resolves a variable a rule or specification may use as a condition source.
     *
     * <p>Excludes an affect-only shared declaration ({@code Reads=false}): the generator emits no
     * {@code device.name := a_name} mirror for it, so a condition on it would reference a value the
     * device never observes. Returning it here accepted the request and then produced a condition with
     * nothing behind it, which the user could not distinguish from a working rule. The frontend hides
     * these too; this is the boundary that also covers API and AI-tool callers.
     */
    private static DeviceManifest.InternalVariable internalVariable(DeviceSmvData smv, String name) {
        if (smv == null || smv.getVariables() == null || !hasText(name)) {
            return null;
        }
        String trimmed = name.trim();
        for (DeviceManifest.InternalVariable variable : smv.getVariables()) {
            if (variable != null && trimmed.equals(variable.getName())) {
                boolean affectOnlyShared = !Boolean.TRUE.equals(variable.getIsInside())
                        && Boolean.FALSE.equals(variable.getReads());
                return affectOnlyShared ? null : variable;
            }
        }
        return null;
    }

    private static boolean hasVariableOrEnvKey(DeviceSmvData smv, String key) {
        return variableOrEnvKey(smv, key) != null;
    }

    /**
     * Any declared variable, regardless of read capability — for questions about a variable's
     * <em>existence</em> rather than its use as a condition source. Kept separate from
     * {@link #internalVariable} so the narrowing there cannot leak into a check that must stay as
     * permissive as the generated model.
     */
    private static DeviceManifest.InternalVariable declaredVariable(DeviceSmvData smv, String name) {
        if (smv == null || smv.getVariables() == null || !hasText(name)) {
            return null;
        }
        String trimmed = name.trim();
        for (DeviceManifest.InternalVariable variable : smv.getVariables()) {
            if (variable != null && trimmed.equals(variable.getName())) {
                return variable;
            }
        }
        return null;
    }

    private static DeviceManifest.InternalVariable variableOrEnvKey(DeviceSmvData smv, String key) {
        if (!hasText(key)) {
            return null;
        }
        String normalized = key.trim();
        DeviceManifest.InternalVariable internal = internalVariable(smv, normalized);
        if (internal != null) {
            return internal;
        }
        return smv.getEnvVariables() != null ? smv.getEnvVariables().get(normalized) : null;
    }

    private static String resolveModeName(DeviceSmvData smv, String rawMode) {
        if (smv == null || smv.getModes() == null || !hasText(rawMode)) {
            return null;
        }
        String trimmed = rawMode.trim();
        String cleaned = DeviceSmvDataFactory.cleanStateName(trimmed);
        for (String mode : smv.getModes()) {
            if (mode != null && (mode.equals(trimmed)
                    || mode.equalsIgnoreCase(trimmed)
                    || mode.equals(cleaned))) {
                return mode;
            }
        }
        return null;
    }

    private static void validatePropertyReference(Map<String, String> errors,
                                                  String prefix,
                                                  SpecConditionDto condition,
                                                  DeviceSmvData smv) {
        String scope = normalizePropertyScope(condition.getPropertyScope());
        String key = trimToNull(condition.getKey());
        if (scope == null || key == null) {
            return;
        }
        if ("variable".equals(scope)) {
            // Capability-blind on purpose: the device module declares trust_<name>/privacy_<name> for
            // every declared variable, so a property reference to an affect-only value is something the
            // generated model really permits. Asking about a label is not reading the value, and a check
            // stricter than the model rejects a specification NuSMV would have decided.
            if (declaredVariable(smv, key) == null) {
                putError(errors, prefix + ".key", "Unknown property variable for specification: " + key);
            }
            return;
        }
        if (resolveModeName(smv, key) == null) {
            putError(errors, prefix + ".key", "Unknown state-label mode for specification: " + key);
        }
    }

    private static String normalizePropertyScope(String value) {
        return SpecConditionNormalization.propertyScope(value);
    }

    // Delegated so this boundary and BoardStorageServiceImpl cannot fold a value differently.
    private static String normalizeVariableSource(String value) {
        return SpecConditionNormalization.variableSource(value);
    }

    private static List<String> splitStateCandidates(String rawValue, String relation, DeviceSmvData smv) {
        if (!hasText(rawValue)) {
            return List.of();
        }
        if (!"in".equals(relation) && !"not in".equals(relation)) {
            return List.of(rawValue.trim());
        }
        String splitRegex = (smv.getModes() != null && smv.getModes().size() > 1) ? "[,|]" : "[,;|]";
        String[] parts = rawValue.split(splitRegex);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (hasText(part)) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private static boolean isLegalStateCandidate(DeviceSmvData smv, String rawCandidate) {
        if (smv == null || !hasText(rawCandidate)
                || smv.getModes() == null || smv.getModes().isEmpty()
                || smv.getModeStates() == null || smv.getModeStates().isEmpty()) {
            return false;
        }
        String candidate = rawCandidate.trim();
        if (candidate.contains(";")) {
            String[] segments = candidate.split(";", -1);
            if (segments.length != smv.getModes().size()) {
                return false;
            }
            boolean hasConcreteSegment = false;
            for (int i = 0; i < smv.getModes().size(); i++) {
                String cleaned = DeviceSmvDataFactory.cleanStateName(segments[i]);
                if (!hasText(cleaned) || "_".equals(cleaned)) {
                    continue;
                }
                String mode = smv.getModes().get(i);
                List<String> legalStates = smv.getModeStates().get(mode);
                if (legalStates == null || !legalStates.contains(cleaned)) {
                    return false;
                }
                hasConcreteSegment = true;
            }
            return hasConcreteSegment;
        }
        String cleaned = DeviceSmvDataFactory.cleanStateName(candidate);
        int matches = 0;
        for (String mode : smv.getModes()) {
            List<String> states = smv.getModeStates().get(mode);
            if (states != null && states.contains(cleaned)) {
                matches++;
            }
        }
        return matches == 1;
    }

    private static String normalizeKnownRelation(String relation) {
        if (!hasText(relation)) {
            return null;
        }
        String normalized = SmvRelationUtils.normalizeRelation(relation);
        return SmvRelationUtils.isSupportedRelation(normalized) ? normalized : null;
    }

    private static String normalizeRuleTargetType(String targetType) {
        if (!hasText(targetType)) {
            return null;
        }
        String normalized = targetType.trim().toLowerCase(Locale.ROOT);
        return Set.of("api", "variable", "mode", "state").contains(normalized) ? normalized : null;
    }

    private static String normalizeSpecTargetType(String targetType) {
        return SpecConditionNormalization.knownSpecTargetType(targetType);
    }

    private static String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String cleanEnumLiteral(String value) {
        return value == null ? "" : value.trim().replace(" ", "");
    }

    private static Set<String> deviceRefs(List<DeviceVerificationDto> devices) {
        Set<String> refs = new HashSet<>();
        if (devices == null) {
            return refs;
        }
        for (DeviceVerificationDto device : devices) {
            if (device == null || !hasText(device.getVarName())) {
                continue;
            }
            refs.add(device.getVarName().trim());
        }
        return refs;
    }

    private static String conditionListNullMessage(String side) {
        return switch (side) {
            case "a" -> "A-conditions cannot be null";
            case "if" -> "If-conditions cannot be null";
            case "then" -> "Then-conditions cannot be null";
            default -> "Conditions cannot be null";
        };
    }

    private static String conditionItemNullMessage(String side) {
        return switch (side) {
            case "a" -> "A-condition item cannot be null";
            case "if" -> "If-condition item cannot be null";
            case "then" -> "Then-condition item cannot be null";
            default -> "Condition item cannot be null";
        };
    }
}
