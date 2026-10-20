package cn.edu.nju.Iot_Verify.component.nusmv.parser;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTrustPrivacyDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTriggeredRuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMV Trace parser.
 * Parse NuSMV counterexample output into TraceStateDto list.
 */
@Slf4j
@Component
public class SmvTraceParser {

    // Compatible with "State 1.1:" and "-> State: 1.1 <-"
    // Anchored to line start (^\s*) to prevent mid-line false matches
    private static final Pattern STATE_PATTERN =
            Pattern.compile("^\\s*(?:->\\s*)?State[:\\s]\\s*\\d+\\.(\\d+)(?:\\s*<-|:)?\\s*(\\w+)?");
    private static final Pattern STATE_LINE_PATTERN =
            Pattern.compile("^\\s*(?:->\\s*)?State[:\\s]\\s*\\d+\\.(\\d+)");
    /**
     * NuSMV's lasso marker, printed on its own line immediately before the state that begins the cycle.
     * A liveness counterexample (negated template 2/5/6 — {@code EG}/{@code GF}) is an infinite path, so
     * this line carries the violation itself and must survive into the trace. See TraceStateDto#loopStart,
     * which records why template 2 is in that set.
     */
    private static final Pattern LOOP_START_PATTERN =
            Pattern.compile("^\\s*--\\s*Loop starts here\\s*$");
    private static final Pattern VAR_PATTERN =
            Pattern.compile("(\\w+)\\.(\\w+)\\s*=\\s*(\\S+)");
    private static final Pattern ENV_VAR_PATTERN =
            Pattern.compile("^(\\w+)\\s*=\\s*(\\S+)$");
    private static final Pattern RULE_EXECUTION_PROBE_PATTERN = Pattern.compile(
            "^" + Pattern.quote(SmvConstants.RULE_EXECUTION_PROBE_PREFIX) + "(\\d+)$");
    private static final Pattern AUTOMATION_LINK_ATTACK_PATTERN = Pattern.compile(
            "^" + Pattern.quote(SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX) + "(\\d+)$");

    public List<TraceStateDto> parseCounterexampleStates(String counterexample,
                                                         Map<String, DeviceSmvData> deviceSmvMap) {
        return parseCounterexampleStates(counterexample, deviceSmvMap, List.of());
    }

    public List<TraceStateDto> parseCounterexampleStates(String counterexample,
                                                         Map<String, DeviceSmvData> deviceSmvMap,
                                                         List<RuleDto> rules) {
        List<TraceStateDto> states = new ArrayList<>();
        if (counterexample == null || counterexample.isEmpty()) {
            return states;
        }

        String[] lines = counterexample.split("\n");
        TraceStateDto currentState = null;
        Map<String, TraceDeviceDto> currentDevicesById = new HashMap<>();
        String pendingStateName = null;
        Map<String, Map<String, String>> previousModeValuesByDevice = new HashMap<>();
        Map<Integer, Boolean> ruleExecutionValues = new HashMap<>();
        Map<Integer, Boolean> automationLinkAttackValues = new HashMap<>();
        TraceStateDto previousCompleteState = null;
        boolean nextStateStartsLoop = false;
        TraceStateDto loopStartState = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("--") && LOOP_START_PATTERN.matcher(line).matches()) {
                nextStateStartsLoop = true;
                continue;
            }

            Matcher stateMatcher = (line.startsWith("State") || line.startsWith("->"))
                    ? STATE_LINE_PATTERN.matcher(line) : null;
            if (stateMatcher != null && stateMatcher.find()) {
                int stateIdx = Integer.parseInt(stateMatcher.group(1));
                if (currentState != null) {
                    finalizeModeStates(currentState, deviceSmvMap, previousModeValuesByDevice);
                    materializeCompleteState(currentState, previousCompleteState);
                    finalizeTriggeredRules(currentState, ruleExecutionValues, rules);
                    finalizeCompromisedAutomationLinks(currentState, automationLinkAttackValues, rules);
                    states.add(currentState);
                    previousCompleteState = currentState;
                }

                currentState = new TraceStateDto();
                currentDevicesById.clear();
                currentState.setStateIndex(stateIdx);
                currentState.setDevices(new ArrayList<>());
                currentState.setTriggeredRules(new ArrayList<>());
                currentState.setCompromisedAutomationLinks(new ArrayList<>());
                if (nextStateStartsLoop) {
                    currentState.setLoopStart(true);
                    loopStartState = currentState;
                    nextStateStartsLoop = false;
                }

                Matcher stateNameMatcher = STATE_PATTERN.matcher(line);
                if (stateNameMatcher.find() && stateNameMatcher.group(2) != null) {
                    pendingStateName = stateNameMatcher.group(2);
                } else {
                    pendingStateName = null;
                }

                parseLineVariables(currentState, currentDevicesById, line, deviceSmvMap, pendingStateName,
                        ruleExecutionValues, automationLinkAttackValues);
                continue;
            }

            if (currentState != null) {
                parseLineVariables(currentState, currentDevicesById, line, deviceSmvMap, pendingStateName,
                        ruleExecutionValues, automationLinkAttackValues);
            }
        }

        if (currentState != null) {
            finalizeModeStates(currentState, deviceSmvMap, previousModeValuesByDevice);
            materializeCompleteState(currentState, previousCompleteState);
            finalizeTriggeredRules(currentState, ruleExecutionValues, rules);
            finalizeCompromisedAutomationLinks(currentState, automationLinkAttackValues, rules);
            states.add(currentState);
        }
        markLoopBackState(states, loopStartState);
        return states;
    }

    /**
     * Marks the trailing state that closes a lasso counterexample.
     *
     * <p>NuSMV terminates an infinite path by re-printing its loop-entry state, so the merged closing state
     * always equals the entry. How it looks in playback depends on the cycle length (measured on NuSMV
     * 2.7.1): a one-state cycle prints no variable lines, so {@code materializeCompleteState} reproduces the
     * previous state exactly and the final step shows nothing moving — a viewer reads that as a broken
     * animation rather than "the cycle repeats from here forever". A longer cycle prints the deltas that
     * return to the entry, so the step looks ordinary and the repetition is invisible instead. Both need the
     * flag; see TraceStateDto#loopBack.
     *
     * <p>Only the last state is considered, and only when a loop marker was seen and the state it points at
     * is not itself that last state — flagging a state as its own return would claim a step the trace does
     * not contain. NuSMV 2.7.1 never actually emits that shape: a self-loop at {@code light=off} refuting
     * {@code AF(light=on)} prints two states, the entry and its repeat, which is also why the
     * {@code states.size() < 2} guard above is a real boundary rather than defensive padding.
     */
    private void markLoopBackState(List<TraceStateDto> states, TraceStateDto loopStartState) {
        if (loopStartState == null || states.size() < 2) {
            return;
        }
        TraceStateDto last = states.get(states.size() - 1);
        if (last == loopStartState) {
            return;
        }
        last.setLoopBack(true);
    }

    private void parseLineVariables(TraceStateDto currentState,
                                    Map<String, TraceDeviceDto> currentDevicesById,
                                    String line,
                                    Map<String, DeviceSmvData> deviceSmvMap,
                                    String pendingStateName,
                                    Map<Integer, Boolean> ruleExecutionValues,
                                    Map<Integer, Boolean> automationLinkAttackValues) {
        int assignmentIndex = line.indexOf('=');
        if (assignmentIndex < 0) {
            return;
        }
        int qualifierIndex = line.indexOf('.');
        if (qualifierIndex >= 0) {
            Matcher varMatcher = VAR_PATTERN.matcher(line);
            while (varMatcher.find()) {
                processVariable(currentState, currentDevicesById,
                        varMatcher.group(1),
                        varMatcher.group(2),
                        varMatcher.group(3),
                        deviceSmvMap,
                        pendingStateName);
            }
        }

        // Environment assignments have an unqualified left-hand side; dots in values remain valid.
        if (qualifierIndex >= 0 && qualifierIndex < assignmentIndex) {
            return;
        }
        Matcher envMatcher = ENV_VAR_PATTERN.matcher(line);
        while (envMatcher.find()) {
            processEnvVariable(currentState, envMatcher.group(1), envMatcher.group(2),
                    deviceSmvMap, ruleExecutionValues, automationLinkAttackValues);
        }
    }

    private void processVariable(TraceStateDto state,
                                 Map<String, TraceDeviceDto> currentDevicesById,
                                 String deviceId,
                                 String attr,
                                 String value,
                                 Map<String, DeviceSmvData> deviceSmvMap,
                                 String stateName) {
        if (deviceId == null || attr == null || value == null) {
            return;
        }

        String cleanValue = value.replace(";", "").trim();
        DeviceSmvData smv = findDeviceById(deviceSmvMap, deviceId);
        if (smv == null) {
            log.debug("Unmapped device variable ignored: {}.{} = {}", deviceId, attr, value);
            return;
        }

        TraceDeviceDto devTrace = findOrCreateDeviceTrace(state, currentDevicesById, deviceId);
        if (devTrace.getTemplateName() == null) {
            devTrace.setTemplateName(smv.getTemplateName());
        }
        if (devTrace.getModelTokenSource() == null) {
            devTrace.setModelTokenSource(smv.getModelTokenSource() != null
                    ? smv.getModelTokenSource() : ModelTokenSource.UNKNOWN);
        }
        if (devTrace.getDeviceLabel() == null) {
            devTrace.setDeviceLabel(smv.getDeviceLabel() != null
                    ? smv.getDeviceLabel()
                    : (smv.getVarName() != null ? smv.getVarName() : deviceId));
        }
        if (devTrace.getState() == null && stateName != null) {
            String matchedState = matchState(smv, stateName);
            devTrace.setState(matchedState != null ? matchedState : stateName);
            if (devTrace.getMode() == null && smv.getModes() != null && smv.getModes().size() == 1) {
                devTrace.setMode(smv.getModes().get(0));
            }
        }

        // NuSMV state variable name is mode name (e.g. HvacMode / SwitchState).
        if (smv.getModes() != null && smv.getModes().contains(attr)) {
            TraceVariableDto modeVar = findOrCreateVariable(devTrace, "__mode__" + attr);
            modeVar.setValue(cleanValue);
            return;
        }

        if ("is_attack".equals(attr)) {
            devTrace.setCompromised("TRUE".equalsIgnoreCase(cleanValue));
            return;
        }

        if (isKnownDeviceVariable(smv, attr)) {
            TraceVariableDto varTrace = findOrCreateVariable(devTrace, attr);
            // An affect-only shared declaration is declared but never assigned, so `cleanValue` is an
            // arbitrary domain member rather than a reading. Keep the row (the fixer requires one per
            // manifest variable) and keep the trust the label line supplies, but do not publish the
            // number as this device's own. See TraceVariableDto#observed.
            if (isAffectOnlySharedVariable(smv, attr)) {
                varTrace.setObserved(false);
                varTrace.setValue("");
                return;
            }
            varTrace.setValue(cleanValue);
            return;
        }

        if (attr.startsWith("trust_")) {
            processTrustVariable(devTrace, smv, attr.substring("trust_".length()), cleanValue);
            return;
        }

        if (attr.startsWith("privacy_")) {
            processPrivacyVariable(devTrace, smv, attr.substring("privacy_".length()), cleanValue);
            return;
        }

        if (isInternalControlVariable(attr)) {
            return;
        }

        TraceVariableDto varTrace = findOrCreateVariable(devTrace, attr);
        varTrace.setValue(cleanValue);
    }

    private void processEnvVariable(TraceStateDto state,
                                    String name,
                                    String value,
                                    Map<String, DeviceSmvData> deviceSmvMap,
                                    Map<Integer, Boolean> ruleExecutionValues,
                                    Map<Integer, Boolean> automationLinkAttackValues) {
        if (state == null || name == null || value == null) {
            return;
        }

        String cleanValue = value.replace(";", "").trim();
        Matcher ruleProbeMatcher = RULE_EXECUTION_PROBE_PATTERN.matcher(name);
        if (ruleProbeMatcher.matches()) {
            int ruleIndex = Integer.parseInt(ruleProbeMatcher.group(1));
            ruleExecutionValues.put(ruleIndex, "TRUE".equalsIgnoreCase(cleanValue));
            return;
        }
        Matcher automationLinkMatcher = AUTOMATION_LINK_ATTACK_PATTERN.matcher(name);
        if (automationLinkMatcher.matches()) {
            int ruleIndex = Integer.parseInt(automationLinkMatcher.group(1));
            automationLinkAttackValues.put(ruleIndex, "TRUE".equalsIgnoreCase(cleanValue));
            return;
        }
        if (SmvConstants.NUSMV_COMPROMISED_POINT_COUNT.equals(name)) {
            putStateVariable(state.getGlobalVariables(), state::setGlobalVariables,
                    SmvConstants.TRACE_COMPROMISED_POINT_COUNT, cleanValue, ModelTokenSource.UNKNOWN);
            return;
        }
        String publicName = toPublicEnvironmentVariableName(name, deviceSmvMap);
        if (isGeneratedEnvironmentVariableName(name, deviceSmvMap)) {
            putStateVariable(state.getEnvVariables(), state::setEnvVariables, publicName, cleanValue,
                    environmentModelTokenSource(publicName, deviceSmvMap));
        } else {
            putStateVariable(state.getGlobalVariables(), state::setGlobalVariables,
                    name, cleanValue, ModelTokenSource.UNKNOWN);
        }
    }

    private void finalizeTriggeredRules(TraceStateDto state,
                                        Map<Integer, Boolean> ruleExecutionValues,
                                        List<RuleDto> rules) {
        List<TraceTriggeredRuleDto> triggeredRules = new ArrayList<>();
        ruleExecutionValues.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int index = entry.getKey();
                    if (rules == null || index < 0 || index >= rules.size()) {
                        log.warn("Trace rule probe {} has no matching submitted rule snapshot", index);
                        return;
                    }
                    RuleDto rule = rules.get(index);
                    if (rule == null) {
                        return;
                    }
                    String label = rule.getRuleString();
                    if (label != null && label.isBlank()) {
                        label = null;
                    }
                    triggeredRules.add(TraceTriggeredRuleDto.builder()
                            .ruleIndex(index)
                            .ruleId(rule.getId() != null ? String.valueOf(rule.getId()) : null)
                            .ruleLabel(label)
                            .build());
                });
        state.setTriggeredRules(triggeredRules);
    }

    private void finalizeCompromisedAutomationLinks(TraceStateDto state,
                                                      Map<Integer, Boolean> attackValues,
                                                      List<RuleDto> rules) {
        List<TraceTriggeredRuleDto> compromisedLinks = new ArrayList<>();
        attackValues.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int index = entry.getKey();
                    if (rules == null || index < 0 || index >= rules.size()) {
                        log.warn("Trace automation-link attack choice {} has no matching submitted rule snapshot", index);
                        return;
                    }
                    RuleDto rule = rules.get(index);
                    if (rule == null) {
                        return;
                    }
                    String label = rule.getRuleString();
                    if (label != null && label.isBlank()) {
                        label = null;
                    }
                    compromisedLinks.add(TraceTriggeredRuleDto.builder()
                            .ruleIndex(index)
                            .ruleId(rule.getId() != null ? String.valueOf(rule.getId()) : null)
                            .ruleLabel(label)
                            .build());
                });
        state.setCompromisedAutomationLinks(compromisedLinks);
    }

    /** NuSMV emits deltas after the first state; API trace states are complete snapshots. */
    private void materializeCompleteState(TraceStateDto current, TraceStateDto previous) {
        if (current == null || previous == null) {
            return;
        }
        current.setDevices(mergeDevices(previous.getDevices(), current.getDevices()));
        current.setEnvVariables(mergeVariables(previous.getEnvVariables(), current.getEnvVariables()));
        current.setGlobalVariables(mergeVariables(previous.getGlobalVariables(), current.getGlobalVariables()));
        current.setTrustPrivacies(mergeTrustPrivacy(
                previous.getTrustPrivacies(), current.getTrustPrivacies()));
    }

    private List<TraceDeviceDto> mergeDevices(List<TraceDeviceDto> previous,
                                              List<TraceDeviceDto> current) {
        LinkedHashMap<String, TraceDeviceDto> merged = new LinkedHashMap<>();
        if (previous != null) {
            for (TraceDeviceDto device : previous) {
                if (device != null && device.getDeviceId() != null) {
                    merged.put(device.getDeviceId(), copyDevice(device));
                }
            }
        }
        if (current != null) {
            for (TraceDeviceDto update : current) {
                if (update == null || update.getDeviceId() == null) {
                    continue;
                }
                TraceDeviceDto target = merged.computeIfAbsent(
                        update.getDeviceId(), ignored -> new TraceDeviceDto());
                mergeDevice(target, update);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private TraceDeviceDto copyDevice(TraceDeviceDto source) {
        TraceDeviceDto copy = new TraceDeviceDto();
        mergeDevice(copy, source);
        return copy;
    }

    private void mergeDevice(TraceDeviceDto target, TraceDeviceDto update) {
        if (update.getDeviceId() != null) target.setDeviceId(update.getDeviceId());
        if (update.getDeviceLabel() != null) target.setDeviceLabel(update.getDeviceLabel());
        if (update.getTemplateName() != null) target.setTemplateName(update.getTemplateName());
        if (update.getModelTokenSource() != null) target.setModelTokenSource(update.getModelTokenSource());
        if (update.getState() != null) target.setState(update.getState());
        if (update.getMode() != null) target.setMode(update.getMode());
        if (update.getCompromised() != null) target.setCompromised(update.getCompromised());
        List<TraceVariableDto> variables = mergeVariables(target.getVariables(), update.getVariables());
        target.setVariables(variables != null ? variables : new ArrayList<>());
        target.setTrustPrivacy(mergeTrustPrivacy(target.getTrustPrivacy(), update.getTrustPrivacy()));
        target.setPrivacies(mergeTrustPrivacy(target.getPrivacies(), update.getPrivacies()));
    }

    private List<TraceVariableDto> mergeVariables(List<TraceVariableDto> previous,
                                                  List<TraceVariableDto> current) {
        if ((previous == null || previous.isEmpty()) && (current == null || current.isEmpty())) {
            return null;
        }
        LinkedHashMap<String, TraceVariableDto> merged = new LinkedHashMap<>();
        if (previous != null) {
            for (TraceVariableDto variable : previous) {
                if (variable != null && variable.getName() != null) {
                    merged.put(variable.getName(), copyVariable(variable));
                }
            }
        }
        if (current != null) {
            for (TraceVariableDto update : current) {
                if (update == null || update.getName() == null) continue;
                TraceVariableDto target = merged.computeIfAbsent(update.getName(), ignored -> {
                    TraceVariableDto created = new TraceVariableDto();
                    created.setName(update.getName());
                    return created;
                });
                if (update.getValue() != null) {
                    target.setValue(update.getValue());
                    // Observability travels with the value, or a later state that reprints an
                    // affect-only row would inherit `observed=true` from the carried-forward copy and
                    // fail the integrity invariant on a blank value.
                    target.setObserved(update.isObserved());
                }
                if (update.getTrust() != null) target.setTrust(update.getTrust());
                if (update.getModelTokenSource() != null) {
                    target.setModelTokenSource(update.getModelTokenSource());
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private TraceVariableDto copyVariable(TraceVariableDto source) {
        TraceVariableDto copy = new TraceVariableDto();
        copy.setName(source.getName());
        copy.setValue(source.getValue());
        copy.setObserved(source.isObserved());
        copy.setTrust(source.getTrust());
        copy.setModelTokenSource(source.getModelTokenSource());
        return copy;
    }

    private List<TraceTrustPrivacyDto> mergeTrustPrivacy(List<TraceTrustPrivacyDto> previous,
                                                         List<TraceTrustPrivacyDto> current) {
        if ((previous == null || previous.isEmpty()) && (current == null || current.isEmpty())) {
            return null;
        }
        LinkedHashMap<String, TraceTrustPrivacyDto> merged = new LinkedHashMap<>();
        if (previous != null) {
            for (TraceTrustPrivacyDto item : previous) {
                if (item != null && item.getName() != null) {
                    merged.put(trustPrivacyKey(item), copyTrustPrivacy(item));
                }
            }
        }
        if (current != null) {
            for (TraceTrustPrivacyDto update : current) {
                if (update == null || update.getName() == null) continue;
                String key = trustPrivacyKey(update);
                TraceTrustPrivacyDto target = merged.computeIfAbsent(key, ignored -> {
                    TraceTrustPrivacyDto created = new TraceTrustPrivacyDto();
                    created.setName(update.getName());
                    created.setPropertyScope(update.getPropertyScope());
                    created.setMode(update.getMode());
                    return created;
                });
                if (update.getTrust() != null) target.setTrust(update.getTrust());
                if (update.getPrivacy() != null) target.setPrivacy(update.getPrivacy());
            }
        }
        return new ArrayList<>(merged.values());
    }

    private TraceTrustPrivacyDto copyTrustPrivacy(TraceTrustPrivacyDto source) {
        TraceTrustPrivacyDto copy = new TraceTrustPrivacyDto();
        copy.setName(source.getName());
        copy.setPropertyScope(source.getPropertyScope());
        copy.setMode(source.getMode());
        copy.setTrust(source.getTrust());
        copy.setPrivacy(source.getPrivacy());
        return copy;
    }

    private String trustPrivacyKey(TraceTrustPrivacyDto item) {
        return String.join("\u0000",
                Objects.toString(item.getPropertyScope(), ""),
                Objects.toString(item.getMode(), ""),
                Objects.toString(item.getName(), ""));
    }

    private void putStateVariable(List<TraceVariableDto> variables,
                                  Consumer<List<TraceVariableDto>> setter,
                                  String name,
                                  String value,
                                  ModelTokenSource modelTokenSource) {
        List<TraceVariableDto> effectiveVariables = variables;
        if (effectiveVariables == null) {
            effectiveVariables = new ArrayList<>();
            setter.accept(effectiveVariables);
        }
        for (TraceVariableDto variable : effectiveVariables) {
            if (name.equals(variable.getName())) {
                variable.setValue(value);
                variable.setModelTokenSource(modelTokenSource);
                return;
            }
        }
        TraceVariableDto created = new TraceVariableDto();
        created.setName(name);
        created.setValue(value);
        created.setModelTokenSource(modelTokenSource);
        effectiveVariables.add(created);
    }

    private ModelTokenSource environmentModelTokenSource(
            String name, Map<String, DeviceSmvData> deviceSmvMap) {
        ModelTokenSource sharedSource = null;
        if (deviceSmvMap == null) return ModelTokenSource.UNKNOWN;
        for (DeviceSmvData smv : deviceSmvMap.values()) {
            if (!declaresEnvironmentVariable(smv, name)) continue;
            ModelTokenSource source = smv.getModelTokenSource() != null
                    ? smv.getModelTokenSource() : ModelTokenSource.UNKNOWN;
            if (sharedSource != null && sharedSource != source) return ModelTokenSource.UNKNOWN;
            sharedSource = source;
        }
        return sharedSource != null ? sharedSource : ModelTokenSource.UNKNOWN;
    }

    private boolean declaresEnvironmentVariable(DeviceSmvData smv, String name) {
        if (smv == null || name == null) return false;
        return (smv.getEnvVariables() != null && smv.getEnvVariables().containsKey(name))
                || (smv.getImpactedEnvironmentVariables() != null
                    && smv.getImpactedEnvironmentVariables().containsKey(name))
                || (smv.getImpactedVariables() != null && smv.getImpactedVariables().contains(name));
    }

    private String toPublicEnvironmentVariableName(String smvName, Map<String, DeviceSmvData> deviceSmvMap) {
        if (!isGeneratedEnvironmentVariableName(smvName, deviceSmvMap)) {
            return smvName;
        }

        return smvName.substring("a_".length());
    }

    private boolean isGeneratedEnvironmentVariableName(String smvName, Map<String, DeviceSmvData> deviceSmvMap) {
        if (smvName == null || !smvName.startsWith("a_")) {
            return false;
        }
        String candidate = smvName.substring("a_".length());
        return isKnownEnvironmentVariable(candidate, deviceSmvMap);
    }

    private boolean isKnownEnvironmentVariable(String name, Map<String, DeviceSmvData> deviceSmvMap) {
        if (name == null || name.isBlank() || deviceSmvMap == null || deviceSmvMap.isEmpty()) {
            return false;
        }

        for (DeviceSmvData smv : deviceSmvMap.values()) {
            if (smv == null) {
                continue;
            }
            if (smv.getEnvVariables() != null && smv.getEnvVariables().containsKey(name)) {
                return true;
            }
            if (smv.getImpactedEnvironmentVariables() != null
                    && smv.getImpactedEnvironmentVariables().containsKey(name)) {
                return true;
            }
            if (smv.getImpactedVariables() != null && smv.getImpactedVariables().contains(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean isKnownDeviceVariable(DeviceSmvData smv, String name) {
        if (smv == null || name == null || name.isBlank()) {
            return false;
        }
        if (smv.getVariables() != null) {
            for (DeviceManifest.InternalVariable variable : smv.getVariables()) {
                if (variable != null && name.equals(variable.getName())) {
                    return true;
                }
            }
        }
        return (smv.getEnvVariables() != null && smv.getEnvVariables().containsKey(name))
                || (smv.getImpactedEnvironmentVariables() != null
                && smv.getImpactedEnvironmentVariables().containsKey(name));
    }

    /**
     * A shared declaration this device only affects. Mirrors the {@code Reads=false} gate in
     * {@code DeviceSmvDataFactory#extractEnvVariables}: omitting {@code Reads} keeps the historical
     * meaning that a shared declaration reads, so only an explicit {@code false} is affect-only.
     * Read from the manifest variable list, which carries every declaration whatever its capability.
     */
    private boolean isAffectOnlySharedVariable(DeviceSmvData smv, String name) {
        if (smv == null || smv.getVariables() == null) {
            return false;
        }
        for (DeviceManifest.InternalVariable variable : smv.getVariables()) {
            if (variable == null || !name.equals(variable.getName())) {
                continue;
            }
            return (variable.getIsInside() == null || !variable.getIsInside())
                    && Boolean.FALSE.equals(variable.getReads());
        }
        return false;
    }

    private void processTrustVariable(TraceDeviceDto devTrace,
                                      DeviceSmvData smv,
                                      String targetName,
                                      String value) {
        if (targetName == null || targetName.isBlank()) {
            return;
        }

        StateProperty stateProperty = resolveStateProperty(smv, targetName);
        if (stateProperty != null) {
            TraceTrustPrivacyDto trust = findOrCreateTrustPrivacy(
                    devTrace, true, "state", stateProperty.state(), stateProperty.mode());
            trust.setTrust("trusted".equals(value));
            return;
        }

        if (!isKnownDeviceVariable(smv, targetName)) {
            log.debug("Ignoring unmapped generated trust label '{}' for device '{}'",
                    targetName, devTrace.getDeviceId());
            return;
        }
        TraceVariableDto variable = findOrCreateVariable(devTrace, targetName);
        variable.setTrust(value);
    }

    private void processPrivacyVariable(TraceDeviceDto devTrace,
                                        DeviceSmvData smv,
                                        String targetName,
                                        String value) {
        if (targetName == null || targetName.isBlank()) {
            return;
        }
        StateProperty stateProperty = resolveStateProperty(smv, targetName);
        if (stateProperty != null) {
            TraceTrustPrivacyDto privacy = findOrCreateTrustPrivacy(
                    devTrace, false, "state", stateProperty.state(), stateProperty.mode());
            privacy.setPrivacy(value);
            return;
        }
        String scope;
        if (isKnownDeviceVariable(smv, targetName)) {
            scope = "variable";
        } else if (isKnownContent(smv, targetName)) {
            scope = "content";
        } else {
            log.debug("Ignoring unmapped generated privacy label '{}' for device '{}'",
                    targetName, devTrace.getDeviceId());
            return;
        }
        TraceTrustPrivacyDto privacy = findOrCreateTrustPrivacy(
                devTrace, false, scope, targetName, null);
        privacy.setPrivacy(value);
    }

    private StateProperty resolveStateProperty(DeviceSmvData smv, String generatedName) {
        if (smv == null || generatedName == null || smv.getModes() == null) {
            return null;
        }
        for (String mode : smv.getModes()) {
            if (!generatedName.startsWith(mode + "_")) {
                continue;
            }
            String stateName = generatedName.substring((mode + "_").length());
            List<String> modeStates = smv.getModeStates() != null ? smv.getModeStates().get(mode) : null;
            if (modeStates != null && modeStates.contains(stateName)) {
                return new StateProperty(mode, stateName);
            }
        }
        return null;
    }

    private boolean isKnownContent(DeviceSmvData smv, String name) {
        return smv != null && smv.getContents() != null && smv.getContents().stream()
                .anyMatch(content -> content != null && name.equals(content.getName()));
    }

    private record StateProperty(String mode, String state) {}

    private boolean isInternalControlVariable(String attr) {
        return attr.endsWith("_rate") || attr.endsWith("_a");
    }

    private TraceVariableDto findOrCreateVariable(TraceDeviceDto devTrace, String name) {
        if (devTrace.getVariables() == null) {
            devTrace.setVariables(new ArrayList<>());
        }

        for (TraceVariableDto var : devTrace.getVariables()) {
            if (name.equals(var.getName())) {
                if (var.getModelTokenSource() == null) {
                    var.setModelTokenSource(deviceModelTokenSource(devTrace));
                }
                return var;
            }
        }

        TraceVariableDto created = new TraceVariableDto();
        created.setName(name);
        created.setModelTokenSource(deviceModelTokenSource(devTrace));
        devTrace.getVariables().add(created);
        return created;
    }

    private ModelTokenSource deviceModelTokenSource(TraceDeviceDto device) {
        return device != null && device.getModelTokenSource() != null
                ? device.getModelTokenSource() : ModelTokenSource.UNKNOWN;
    }

    private TraceVariableDto findVariable(TraceDeviceDto devTrace, String name) {
        if (devTrace.getVariables() == null) {
            return null;
        }
        for (TraceVariableDto var : devTrace.getVariables()) {
            if (name.equals(var.getName())) {
                return var;
            }
        }
        return null;
    }

    private TraceTrustPrivacyDto findOrCreateTrustPrivacy(TraceDeviceDto devTrace,
                                                          boolean trustList,
                                                          String propertyScope,
                                                          String name,
                                                          String mode) {
        List<TraceTrustPrivacyDto> list = trustList ? devTrace.getTrustPrivacy() : devTrace.getPrivacies();
        if (list == null) {
            list = new ArrayList<>();
            if (trustList) {
                devTrace.setTrustPrivacy(list);
            } else {
                devTrace.setPrivacies(list);
            }
        }

        for (TraceTrustPrivacyDto item : list) {
            if (name.equals(item.getName())
                    && Objects.equals(propertyScope, item.getPropertyScope())
                    && Objects.equals(mode, item.getMode())) {
                return item;
            }
        }

        TraceTrustPrivacyDto created = new TraceTrustPrivacyDto();
        created.setName(name);
        created.setPropertyScope(propertyScope);
        created.setMode(mode);
        list.add(created);
        return created;
    }

    private void finalizeModeStates(TraceStateDto state,
                                    Map<String, DeviceSmvData> deviceSmvMap,
                                    Map<String, Map<String, String>> previousModeValuesByDevice) {
        if (state == null || state.getDevices() == null) {
            return;
        }

        for (TraceDeviceDto dev : state.getDevices()) {
            DeviceSmvData smv = findDeviceById(deviceSmvMap, dev.getDeviceId());
            if (smv == null || smv.getModes() == null || smv.getModes().isEmpty()) {
                continue;
            }

            Map<String, String> previousModeValues =
                    previousModeValuesByDevice.computeIfAbsent(dev.getDeviceId(), k -> new HashMap<>());
            List<String> modeValues = new ArrayList<>();
            List<String> modeNames = new ArrayList<>();
            boolean hasAnyModeValue = false;
            boolean hasAllModeValues = true;
            for (String mode : smv.getModes()) {
                TraceVariableDto modeVar = findVariable(dev, "__mode__" + mode);
                String modeValue = null;
                if (modeVar != null && modeVar.getValue() != null && !modeVar.getValue().isBlank()) {
                    modeValue = modeVar.getValue();
                    previousModeValues.put(mode, modeValue);
                } else {
                    modeValue = previousModeValues.get(mode);
                }

                if (modeValue != null && !modeValue.isBlank()) {
                    hasAnyModeValue = true;
                    modeValues.add(modeValue);
                    modeNames.add(mode);
                } else {
                    hasAllModeValues = false;
                }
            }

            if (hasAnyModeValue) {
                if (smv.getModes().size() > 1 && hasAllModeValues) {
                    dev.setState(String.join(";", modeValues));
                    dev.setMode(String.join(";", smv.getModes()));
                } else if (smv.getModes().size() == 1) {
                    dev.setState(modeValues.get(0));
                    dev.setMode(modeNames.get(0));
                } else if (dev.getState() == null || dev.getState().isBlank()) {
                    // 部分 mode 有值：用实际可用的 mode/value 对
                    dev.setState(modeValues.get(0));
                    dev.setMode(modeNames.get(0));
                }
            }

            // state 已由早期路径设置但 mode 仍为空时，尝试回填
            if (dev.getMode() == null && smv.getModes() != null) {
                if (smv.getModes().size() == 1) {
                    dev.setMode(smv.getModes().get(0));
                } else if (!modeNames.isEmpty()) {
                    dev.setMode(modeNames.get(0));
                }
            }

            if (dev.getVariables() != null) {
                dev.getVariables().removeIf(v -> v.getName() != null && v.getName().startsWith("__mode__"));
            }
        }
    }

    private DeviceSmvData findDeviceById(Map<String, DeviceSmvData> deviceSmvMap, String id) {
        if (deviceSmvMap == null) {
            return null;
        }

        DeviceSmvData directMatch = deviceSmvMap.get(id);
        if (directMatch != null) {
            return directMatch;
        }

        for (DeviceSmvData smv : deviceSmvMap.values()) {
            if (id.equals(smv.getVarName())) {
                return smv;
            }
        }

        return null;
    }

    private TraceDeviceDto findOrCreateDeviceTrace(TraceStateDto state,
                                                   Map<String, TraceDeviceDto> currentDevicesById,
                                                   String deviceId) {
        if (state.getDevices() == null) {
            state.setDevices(new ArrayList<>());
        }

        TraceDeviceDto existing = currentDevicesById.get(deviceId);
        if (existing != null) {
            return existing;
        }

        TraceDeviceDto newDev = new TraceDeviceDto();
        newDev.setDeviceId(deviceId);
        // Keep encounter order in the DTO list; this index belongs only to the current delta state.
        state.getDevices().add(newDev);
        currentDevicesById.put(deviceId, newDev);
        return newDev;
    }

    private String matchState(DeviceSmvData smv, String value) {
        if (value == null || smv.getStates() == null) {
            return null;
        }
        String cleanValue = DeviceSmvDataFactory.cleanStateName(value);

        for (String state : smv.getStates()) {
            if (state.equals(value) || state.equals(cleanValue)) {
                return state;
            }
        }

        if (smv.getModes() != null) {
            for (String mode : smv.getModes()) {
                String modeState = mode + "_" + cleanValue;
                if (smv.getStates().contains(modeState)) {
                    return modeState;
                }
            }
        }

        return null;
    }
}
