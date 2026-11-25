package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory.cleanStateName;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory.getStateForMode;

/** Proves assistant condition groups impossible using the formal model's independent mode domains. */
final class DeviceModeStateAnalysis {
    private static final int MAX_STATE_TUPLES_TO_CHECK = 4096;

    enum Outcome { POSSIBLE, CONTRADICTORY, UNREACHABLE, INCONCLUSIVE }

    private final DeviceManifest manifest;
    private final List<String> modes;
    private final Map<String, List<String>> authoredStates;
    private final Map<String, List<String>> domains;

    DeviceModeStateAnalysis(DeviceManifest manifest) {
        this.manifest = manifest;
        this.modes = DeviceManifestModes.modeNames(manifest);
        this.authoredStates = DeviceManifestModes.authoredModeStates(manifest);
        this.domains = DeviceManifestModes.modeStates(manifest);
    }

    record Condition(List<Map<String, String>> alternatives, boolean negated) {
        boolean matches(Map<String, String> state) {
            boolean matchesAny = alternatives.stream().anyMatch(pattern -> matchesPattern(state, pattern));
            return negated ? !matchesAny : matchesAny;
        }
    }

    Condition condition(String targetType, String key, String rawRelation, String rawValue) {
        String relation = SmvRelationUtils.normalizeRelation(rawRelation);
        if (!SmvRelationUtils.isEnumRelation(relation)) return new Condition(List.of(), false);
        boolean membership = "in".equals(relation) || "not in".equals(relation);
        boolean negated = "!=".equals(relation) || "not in".equals(relation);
        List<String> singleValue = rawValue == null ? List.of() : List.of(rawValue.trim());
        if ("mode".equals(targetType)) {
            String mode = key == null ? null : key.trim();
            if (mode == null || !modes.contains(mode)) return new Condition(List.of(), false);
            List<String> candidates = membership ? SmvRelationUtils.splitRuleValues(rawValue) : singleValue;
            return new Condition(candidates.stream()
                    .map(candidate -> Map.of(mode, cleanStateName(candidate)))
                    .toList(), negated);
        }
        List<String> candidates = membership ? SmvRelationUtils.splitStateRuleValues(rawValue, modes.size()) : singleValue;
        return new Condition(candidates.stream()
                .map(this::statePattern)
                .filter(pattern -> !pattern.isEmpty())
                .toList(), negated);
    }

    Optional<Condition> commandStart(String startState) {
        Map<String, String> pattern = stateEndpoint(startState, true);
        return pattern.isEmpty() ? Optional.empty() : Optional.of(new Condition(List.of(pattern), false));
    }

    Outcome assess(List<Condition> conditions, String currentState, boolean checkReachability) {
        if (domains.isEmpty() || domains.values().stream().anyMatch(List::isEmpty)) {
            return Outcome.INCONCLUSIVE;
        }
        // Declared mode domains are independent. Modes absent from the conditions cannot
        // affect whether any assignment satisfies them.
        Map<String, List<String>> constrainedDomains = new LinkedHashMap<>();
        for (Condition condition : conditions) {
            for (Map<String, String> pattern : condition.alternatives()) {
                pattern.keySet().forEach(mode -> constrainedDomains.putIfAbsent(mode, domains.get(mode)));
            }
        }
        Predicate<Map<String, String>> matches = state -> conditions.stream().allMatch(condition -> condition.matches(state));
        var declared = stateTuples(constrainedDomains, MAX_STATE_TUPLES_TO_CHECK);
        if (declared != null && declared.stream().noneMatch(matches)) return Outcome.CONTRADICTORY;
        if (!checkReachability) return declared == null ? Outcome.INCONCLUSIVE : Outcome.POSSIBLE;
        return checkReachable(currentState, matches);
    }

    private Outcome checkReachable(String currentState, Predicate<Map<String, String>> matches) {
        String start = currentState != null && !currentState.isBlank() ? currentState : manifest.getInitState();
        Map<String, String> initial = statePattern(start);
        if (initial.size() != modes.size()) return Outcome.INCONCLUSIVE;
        if (matches.test(initial)) return Outcome.POSSIBLE;

        List<StateWrite> writes = Stream.concat(
                stream(manifest.getApis()).map(api -> stateWrite(api.getStartState(), api.getEndState())),
                stream(manifest.getTransitions()).map(transition -> stateWrite(transition.getStartState(),
                        transition.getEndState())))
                .filter(write -> !write.end().isEmpty())
                .toList();

        Set<Map<String, String>> seen = new HashSet<>();
        seen.add(initial);
        List<Map<String, String>> pending = new ArrayList<>();
        pending.add(initial);
        int remaining = MAX_STATE_TUPLES_TO_CHECK;
        for (int i = 0; i < pending.size(); i++) {
            Map<String, String> current = pending.get(i);
            Map<String, Set<String>> next = new LinkedHashMap<>();
            current.forEach((mode, value) -> next.put(mode, new LinkedHashSet<>(List.of(value))));
            // Separate next(mode) equations can select writes simultaneously. Let each mode hold
            // or accept any write allowed by the source state, regardless of triggers and priority.
            for (StateWrite write : writes) {
                if (matchesPattern(current, write.start())) {
                    write.end().forEach((mode, value) -> next.get(mode).add(value));
                }
            }
            var successors = stateTuples(next, remaining);
            if (successors == null) return Outcome.INCONCLUSIVE;
            remaining -= successors.size();
            for (Map<String, String> successor : successors) {
                if (seen.add(successor)) {
                    if (matches.test(successor)) return Outcome.POSSIBLE;
                    pending.add(successor);
                }
            }
        }
        return Outcome.UNREACHABLE;
    }

    private Map<String, String> statePattern(String candidate) {
        return ManifestConditionSemantics.resolveStatePattern(candidate, modes,
                (mode, state) -> authoredStates.getOrDefault(mode, List.of()).contains(state)
                        ? cleanStateName(state) : null);
    }

    private StateWrite stateWrite(String startState, String endState) {
        return new StateWrite(stateEndpoint(startState, true), stateEndpoint(endState, false));
    }

    private Map<String, String> stateEndpoint(String tuple, boolean start) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < modes.size(); i++) {
            String value = getStateForMode(tuple, i, start);
            if (value != null) values.put(modes.get(i), value);
        }
        return values;
    }

    private static boolean matchesPattern(Map<String, String> state, Map<String, String> pattern) {
        return state.entrySet().containsAll(pattern.entrySet());
    }

    /** A null result means the search cannot exhaust these options within the remaining budget. */
    private static List<Map<String, String>> stateTuples(Map<String, ? extends Collection<String>> options,
                                                        int limit) {
        if (limit < 1) return null;
        int size = 1;
        for (Collection<String> values : options.values()) {
            if (values.isEmpty() || size > limit / values.size()) return null;
            size *= values.size();
        }
        List<Map<String, String>> tuples = new ArrayList<>(size);
        appendStateTuples(List.copyOf(options.entrySet()), 0, new LinkedHashMap<>(), tuples);
        return tuples;
    }

    private static void appendStateTuples(List<? extends Map.Entry<String, ? extends Collection<String>>> options,
                                          int index, Map<String, String> values, List<Map<String, String>> tuples) {
        if (index == options.size()) {
            tuples.add(Map.copyOf(values));
            return;
        }
        var mode = options.get(index);
        for (String value : mode.getValue()) {
            values.put(mode.getKey(), value);
            appendStateTuples(options, index + 1, values, tuples);
        }
    }

    private static <T> Stream<T> stream(List<T> values) {
        return values == null ? Stream.empty() : values.stream().filter(value -> value != null);
    }

    private record StateWrite(Map<String, String> start, Map<String, String> end) {}
}
