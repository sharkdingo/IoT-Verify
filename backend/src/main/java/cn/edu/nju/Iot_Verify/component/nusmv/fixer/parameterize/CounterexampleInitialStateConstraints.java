package cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.AttackSurface;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTriggeredRuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTrustPrivacyDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.util.SmvConstants;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/** Builds validated candidate-only INIT constraints from a persisted counterexample snapshot. */
public final class CounterexampleInitialStateConstraints {

    private CounterexampleInitialStateConstraints() {
    }

    public static List<String> build(TraceStateDto initialState,
                                     List<RuleDto> rules,
                                     Map<String, DeviceSmvData> deviceSmvMap,
                                     AttackScenarioDto attackScenario,
                                     boolean enablePrivacy) {
        AttackScenarioDto requiredScenario = Objects.requireNonNull(
                attackScenario, "attackScenario is required");
        if (initialState == null) {
            return List.of();
        }
        if (deviceSmvMap == null) {
            throw invalid("the counterexample has no resolved device model");
        }

        Set<String> constraints = new LinkedHashSet<>();
        Map<String, TraceDeviceDto> traceDevices = new LinkedHashMap<>();
        if (initialState.getDevices() != null) {
            for (TraceDeviceDto device : initialState.getDevices()) {
                if (device == null || device.getDeviceId() == null || device.getDeviceId().isBlank()) {
                    throw invalid("the trace contains a device without an id");
                }
                if (!deviceSmvMap.containsKey(device.getDeviceId())) {
                    throw invalid("trace device '" + device.getDeviceId() + "' is not in the resolved model");
                }
                if (traceDevices.putIfAbsent(device.getDeviceId(), device) != null) {
                    throw invalid("trace device '" + device.getDeviceId() + "' appears more than once");
                }
            }
        }
        AttackSurface attackSurface = AttackSurface.analyze(rules, deviceSmvMap);
        for (DeviceSmvData smv : deviceSmvMap.values()) {
            if (smv == null || smv.getVarName() == null) continue;
            boolean requireCompromise = requiredScenario.isEnabled()
                    && attackSurface.includesDevice(smv.getVarName());
            boolean requiresSnapshot = hasItems(smv.getModes()) || hasItems(smv.getVariables())
                    || (enablePrivacy && hasItems(smv.getContents())) || requireCompromise;
            TraceDeviceDto trace = traceDevices.get(smv.getVarName());
            if (requiresSnapshot && trace == null) {
                throw invalid("modeled device '" + smv.getVarName() + "' is missing from the first trace state");
            }
            if (trace != null) {
                appendDeviceConstraints(constraints, trace, smv, enablePrivacy, requireCompromise);
            }
        }
        appendEnvironmentConstraints(constraints, initialState.getEnvVariables(), deviceSmvMap);
        if (requiredScenario.isEnabled()) {
            appendAutomationLinkConstraints(
                    constraints, initialState.getCompromisedAutomationLinks(), rules);
        }
        return List.copyOf(constraints);
    }

    private static void appendDeviceConstraints(Set<String> constraints,
                                                TraceDeviceDto trace,
                                                DeviceSmvData smv,
                                                boolean enablePrivacy,
                                                boolean requireCompromise) {
        String prefix = smv.getVarName() + ".";
        appendModeConstraints(constraints, prefix, trace.getState(), smv);

        Map<String, DeviceManifest.InternalVariable> variables = new HashMap<>();
        if (smv.getVariables() != null) {
            for (DeviceManifest.InternalVariable variable : smv.getVariables()) {
                if (variable != null && variable.getName() != null) {
                    variables.put(variable.getName(), variable);
                }
            }
        }
        Map<String, TraceVariableDto> traceVariables = new LinkedHashMap<>();
        if (trace.getVariables() != null) {
            for (TraceVariableDto variable : trace.getVariables()) {
                if (variable == null || variable.getName() == null) continue;
                if (traceVariables.putIfAbsent(variable.getName(), variable) != null) {
                    throw invalid("trace variable '" + smv.getVarName() + "." + variable.getName()
                            + "' appears more than once");
                }
            }
        }
        for (DeviceManifest.InternalVariable definition : variables.values()) {
            TraceVariableDto variable = traceVariables.get(definition.getName());
            if (variable == null) {
                throw invalid("trace variable '" + smv.getVarName() + "." + definition.getName()
                        + "' is missing from the first state");
            }
            if (Boolean.TRUE.equals(definition.getIsInside())) {
                if (variable.getValue() == null) {
                    throw invalid("initial value for '" + smv.getVarName() + "." + definition.getName()
                            + "' is missing");
                }
                constraints.add(prefix + definition.getName() + " = "
                        + validatedVariableValue(variable.getValue(), definition, definition.getName()));
            }
            if (variable.getTrust() == null || variable.getTrust().isBlank()) {
                throw invalid("initial trust for '" + smv.getVarName() + "." + definition.getName()
                        + "' is missing");
            }
            constraints.add(prefix + "trust_" + definition.getName() + " = "
                    + trustLiteral(variable.getTrust()));
        }
        appendStateTrustConstraints(constraints, prefix, trace.getTrustPrivacy(), smv);
        if (enablePrivacy) {
            appendPrivacyProperties(constraints, prefix, trace.getPrivacies(), smv, variables);
        }
        if (requireCompromise) {
            if (trace.getCompromised() == null) {
                throw invalid("attack choice for device '" + smv.getVarName() + "' is missing");
            }
            constraints.add(prefix + SmvConstants.DEVICE_ATTACK_FLAG + " = " + booleanLiteral(trace.getCompromised()));
        }
    }

    private static void appendModeConstraints(Set<String> constraints,
                                              String prefix,
                                              String traceState,
                                              DeviceSmvData smv) {
        List<String> modes = smv.getModes();
        if (modes == null || modes.isEmpty()) return;
        if (traceState == null || traceState.isBlank()) {
            throw invalid("trace device '" + smv.getVarName() + "' has no initial mode state");
        }
        String[] states = modes.size() == 1
                ? new String[]{traceState.trim()} : traceState.split(";", -1);
        if (states.length != modes.size()) {
            throw invalid("trace device '" + smv.getVarName() + "' has an incomplete initial mode tuple");
        }
        for (int i = 0; i < modes.size(); i++) {
            String authored = states[i].trim();
            String mode = modes.get(i);
            String value = smv.resolveAuthoredState(mode, authored);
            if (value == null) {
                throw invalid("initial state '" + authored + "' is outside mode '" + smv.authoredModeName(mode) + "'");
            }
            constraints.add(prefix + mode + " = " + value);
        }
    }

    private static void appendStateTrustConstraints(Set<String> constraints,
                                                    String prefix,
                                                    List<TraceTrustPrivacyDto> properties,
                                                    DeviceSmvData smv) {
        Map<String, TraceTrustPrivacyDto> indexed = indexProperties(properties, "trust", smv.getVarName());
        for (String mode : smv.getModes()) {
            String authoredMode = smv.authoredModeName(mode);
            for (String state : smv.getModeStates().getOrDefault(mode, List.of())) {
                TraceTrustPrivacyDto property =
                        indexed.get(propertyKey("state", authoredMode, smv.authoredStateName(mode, state)));
                if (property == null || property.getTrust() == null) {
                    throw invalid("initial trust for state '" + smv.getVarName() + "." + mode
                            + "." + state + "' is missing");
                }
                constraints.add(prefix + "trust_" + mode + "_" + state
                        + " = " + (property.getTrust() ? "trusted" : "untrusted"));
            }
        }
    }

    private static void appendPrivacyProperties(Set<String> constraints,
                                                String prefix,
                                                List<TraceTrustPrivacyDto> properties,
                                                DeviceSmvData smv,
                                                Map<String, DeviceManifest.InternalVariable> variables) {
        Map<String, TraceTrustPrivacyDto> indexed = indexProperties(properties, "privacy", smv.getVarName());
        Set<String> contents = new LinkedHashSet<>();
        if (smv.getContents() != null) {
            smv.getContents().stream().filter(Objects::nonNull)
                    .map(DeviceSmvData.ContentInfo::getName).filter(Objects::nonNull)
                    .forEach(contents::add);
        }
        for (String mode : smv.getModes()) {
            String authoredMode = smv.authoredModeName(mode);
            for (String state : smv.getModeStates().getOrDefault(mode, List.of())) {
                appendRequiredPrivacy(constraints, prefix, indexed, "state", authoredMode,
                        smv.authoredStateName(mode, state),
                        mode + "_" + state, smv.getVarName());
            }
        }
        for (String variable : variables.keySet()) {
            appendRequiredPrivacy(constraints, prefix, indexed, "variable", null, variable,
                    variable, smv.getVarName());
        }
        for (String content : contents) {
            appendRequiredPrivacy(constraints, prefix, indexed, "content", null, content,
                    content, smv.getVarName());
        }
    }

    private static void appendRequiredPrivacy(Set<String> constraints,
                                              String prefix,
                                              Map<String, TraceTrustPrivacyDto> indexed,
                                              String scope,
                                              String mode,
                                              String name,
                                              String suffix,
                                              String deviceName) {
        TraceTrustPrivacyDto property = indexed.get(propertyKey(scope, mode, name));
        if (property == null || property.getPrivacy() == null || property.getPrivacy().isBlank()) {
            throw invalid("initial privacy for '" + deviceName + "." + suffix + "' is missing");
        }
        constraints.add(prefix + "privacy_" + suffix + " = " + privacyLiteral(property.getPrivacy()));
    }

    private static Map<String, TraceTrustPrivacyDto> indexProperties(
            List<TraceTrustPrivacyDto> properties, String dimension, String deviceName) {
        Map<String, TraceTrustPrivacyDto> indexed = new LinkedHashMap<>();
        if (properties == null) return indexed;
        for (TraceTrustPrivacyDto property : properties) {
            if (property == null || property.getName() == null || property.getPropertyScope() == null) continue;
            String key = propertyKey(property.getPropertyScope(), property.getMode(), property.getName());
            if (indexed.putIfAbsent(key, property) != null) {
                throw invalid("trace " + dimension + " property '" + deviceName + "."
                        + property.getName() + "' appears more than once");
            }
        }
        return indexed;
    }

    private static String propertyKey(String scope, String mode, String name) {
        return String.join("\u0000", Objects.toString(scope, ""),
                Objects.toString(mode, ""), Objects.toString(name, ""));
    }

    private static void appendEnvironmentConstraints(Set<String> constraints,
                                                     List<TraceVariableDto> environment,
                                                     Map<String, DeviceSmvData> deviceSmvMap) {
        Map<String, DeviceManifest.InternalVariable> domains = new LinkedHashMap<>();
        for (DeviceSmvData smv : deviceSmvMap.values()) {
            if (smv == null) continue;
            smv.getSharedDeclarations().forEach(domains::putIfAbsent);
        }
        Map<String, TraceVariableDto> traceEnvironment = new LinkedHashMap<>();
        for (TraceVariableDto variable : environment == null ? List.<TraceVariableDto>of() : environment) {
            if (variable == null || variable.getName() == null || variable.getValue() == null) continue;
            if (!domains.containsKey(variable.getName())) {
                throw invalid("trace environment variable '" + variable.getName() + "' is not in the resolved model");
            }
            if (traceEnvironment.putIfAbsent(variable.getName(), variable) != null) {
                throw invalid("trace environment variable '" + variable.getName() + "' appears more than once");
            }
        }
        for (Map.Entry<String, DeviceManifest.InternalVariable> domain : domains.entrySet()) {
            TraceVariableDto variable = traceEnvironment.get(domain.getKey());
            if (variable == null || variable.getValue() == null) {
                throw invalid("trace environment variable '" + domain.getKey() + "' is missing from the first state");
            }
            constraints.add(SmvConstants.ENVIRONMENT_POOL_PREFIX + domain.getKey() + " = "
                    + validatedVariableValue(variable.getValue(), domain.getValue(), domain.getKey()));
        }
    }

    private static void appendAutomationLinkConstraints(Set<String> constraints,
                                                        List<TraceTriggeredRuleDto> compromisedLinks,
                                                        List<RuleDto> rules) {
        if (compromisedLinks == null) {
            throw invalid("the attack trace does not record its automation-link choices");
        }
        List<RuleDto> safeRules = rules == null ? List.of() : rules;
        Set<Integer> compromisedIndices = new LinkedHashSet<>();
        for (TraceTriggeredRuleDto link : compromisedLinks) {
            Integer ruleIndex = link == null ? null : link.getRuleIndex();
            if (ruleIndex == null || ruleIndex < 0 || ruleIndex >= safeRules.size()
                    || safeRules.get(ruleIndex) == null) {
                throw invalid("a compromised automation link has an invalid frozen rule index");
            }
            if (!compromisedIndices.add(ruleIndex)) {
                throw invalid("a compromised automation link appears more than once");
            }
        }
        for (int i = 0; i < safeRules.size(); i++) {
            constraints.add(SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX + i + " = "
                    + booleanLiteral(compromisedIndices.contains(i)));
        }
    }

    private static String validatedVariableValue(String raw,
                                                 DeviceManifest.InternalVariable definition,
                                                 String name) {
        String value = raw.trim();
        if (definition.getValues() != null && !definition.getValues().isEmpty()) {
            boolean allowed = definition.getValues().stream()
                    .filter(Objects::nonNull)
                    .map(item -> item.replace(" ", ""))
                    .anyMatch(value::equals);
            if (!allowed) throw invalid("initial value for '" + name + "' is outside its domain");
            return value;
        }
        try {
            int numeric = Integer.parseInt(value);
            if (definition.getLowerBound() == null || definition.getUpperBound() == null
                    || numeric < definition.getLowerBound() || numeric > definition.getUpperBound()) {
                throw invalid("initial value for '" + name + "' is outside its range");
            }
            return value;
        } catch (NumberFormatException e) {
            throw invalid("initial value for '" + name + "' is not an integer");
        }
    }

    private static String trustLiteral(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!"trusted".equals(value) && !"untrusted".equals(value)) {
            throw invalid("trace trust value is invalid");
        }
        return value;
    }

    private static String privacyLiteral(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!"public".equals(value) && !"private".equals(value)) {
            throw invalid("trace privacy value is invalid");
        }
        return value;
    }

    private static String booleanLiteral(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    private static boolean hasItems(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private static SmvGenerationException invalid(String reason) {
        return SmvGenerationException.smvGenerationError(
                "Cannot reproduce the counterexample initial state: " + reason);
    }
}
