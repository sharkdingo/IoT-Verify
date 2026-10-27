package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;

/** Explicit deterministic monitor FSM with the distance calculation from HAFuzz Algorithm 1. */
public final class PaperMonitorFsm {

    private static final int MAX_SOLVER_LEVELS = 30;

    private final Map<String, State> states;
    private final List<Transition> transitions;
    private final Map<String, List<Transition>> outgoing;
    private final String initialStateId;
    private String currentStateId;

    private PaperMonitorFsm(
            Map<String, State> states,
            List<Transition> transitions,
            String initialStateId) {
        this.states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
        this.transitions = List.copyOf(transitions);
        this.initialStateId = initialStateId;
        this.currentStateId = initialStateId;
        Map<String, List<Transition>> grouped = new LinkedHashMap<>();
        for (String stateId : states.keySet()) {
            grouped.put(stateId, new ArrayList<>());
        }
        for (Transition transition : transitions) {
            grouped.get(transition.fromStateId()).add(transition);
        }
        Map<String, List<Transition>> immutable = new LinkedHashMap<>();
        grouped.forEach((stateId, stateTransitions) ->
                immutable.put(stateId, List.copyOf(stateTransitions)));
        this.outgoing = Collections.unmodifiableMap(immutable);
    }

    public static Builder builder(String initialStateId) {
        return new Builder(initialStateId);
    }

    public State currentState() {
        return states.get(currentStateId);
    }

    public PaperTruthValue currentTruthValue() {
        return currentState().truthValue();
    }

    public List<State> states() {
        return List.copyOf(states.values());
    }

    public List<Transition> transitions() {
        return transitions;
    }

    public void reset() {
        currentStateId = initialStateId;
    }

    public PaperTruthValue step(PaperAtomValuation valuation) {
        Objects.requireNonNull(valuation, "valuation");
        List<Transition> matching = outgoing.getOrDefault(currentStateId, List.of()).stream()
                .filter(transition -> transition.condition().test(valuation))
                .toList();
        if (matching.isEmpty()) {
            throw new IllegalStateException("Monitor state '" + currentStateId + "' has no matching transition");
        }
        if (matching.size() > 1) {
            throw new IllegalStateException("Monitor state '" + currentStateId + "' has ambiguous transitions");
        }
        currentStateId = matching.get(0).toStateId();
        return currentTruthValue();
    }

    public OptionalInt shortestDistanceToViolation() {
        return shortestDistanceToViolation(currentStateId);
    }

    public Optional<PaperCondition> nextConditionTowardViolation() {
        OptionalInt currentDistance = shortestDistanceToViolation();
        if (currentDistance.isEmpty() || currentDistance.getAsInt() == 0) {
            return Optional.empty();
        }
        int expectedDistance = currentDistance.getAsInt() - 1;
        List<PaperCondition> conditions = new ArrayList<>();
        for (Transition transition : outgoing.getOrDefault(currentStateId, List.of())) {
            OptionalInt targetDistance = shortestDistanceToViolation(transition.toStateId());
            if (targetDistance.isPresent() && targetDistance.getAsInt() == expectedDistance) {
                conditions.add(transition.condition());
            }
        }
        return conditions.isEmpty()
                ? Optional.empty()
                : Optional.of(PaperCondition.anyOf(conditions));
    }

    public Optional<Distance> distanceToViolation(
            PaperAtomValuation valuation,
            PaperPredecessorResolver predecessorResolver,
            int solverLevels) {
        Objects.requireNonNull(valuation, "valuation");
        if (solverLevels < 1 || solverLevels > MAX_SOLVER_LEVELS) {
            throw new IllegalArgumentException("solverLevels must be in [1, " + MAX_SOLVER_LEVELS + "]");
        }
        OptionalInt graphDistance = shortestDistanceToViolation();
        if (graphDistance.isEmpty()) {
            return Optional.empty();
        }
        if (graphDistance.getAsInt() == 0) {
            return Optional.of(new Distance(0, 0.0, 0.0, Optional.empty()));
        }

        PaperCondition directCondition = nextConditionTowardViolation()
                .orElseThrow(() -> new IllegalStateException("No shortest-path transition condition exists"));
        PaperPredecessorResolver resolver = predecessorResolver == null
                ? PaperPredecessorResolver.none()
                : predecessorResolver;
        double denominator = Math.scalb(1.0, solverLevels) - 1.0;
        double weightedSatisfaction = 0.0;
        Map<PaperCondition, Double> satisfactionByIdentity = new IdentityHashMap<>();
        PaperCondition levelCondition = directCondition;
        for (int level = 1; level <= solverLevels; level++) {
            double weight = Math.scalb(1.0, solverLevels - level) / denominator;
            if (levelCondition != null) {
                weightedSatisfaction += conditionSatisfaction(levelCondition, valuation, satisfactionByIdentity) * weight;
                if (level < solverLevels) {
                    levelCondition = resolver.previousConditions(levelCondition, level);
                }
            }
        }
        return Optional.of(new Distance(
                graphDistance.getAsInt(),
                weightedSatisfaction,
                graphDistance.getAsInt() - weightedSatisfaction,
                Optional.of(directCondition)));
    }

    private static double conditionSatisfaction(
            PaperCondition condition,
            PaperAtomValuation valuation,
            Map<PaperCondition, Double> satisfactionByIdentity) {
        Double cached = satisfactionByIdentity.get(condition);
        if (cached != null) {
            return cached;
        }
        // Shared subtrees are evaluated once, while every occurrence retains its mean weight.
        double satisfaction;
        if (condition instanceof PaperCondition.All all) {
            satisfaction = all.conditions().stream()
                    .mapToDouble(child -> conditionSatisfaction(child, valuation, satisfactionByIdentity))
                    .average()
                    .orElse(1.0);
        } else if (condition instanceof PaperCondition.Any any) {
            satisfaction = any.conditions().stream()
                    .mapToDouble(child -> conditionSatisfaction(child, valuation, satisfactionByIdentity))
                    .max()
                    .orElse(0.0);
        } else if (condition instanceof PaperCondition.Terminal terminal) {
            satisfaction = conditionSatisfaction(terminal.condition(), valuation, satisfactionByIdentity);
        } else {
            satisfaction = condition.satisfaction(valuation);
        }
        satisfactionByIdentity.put(condition, satisfaction);
        return satisfaction;
    }

    private OptionalInt shortestDistanceToViolation(String startStateId) {
        State start = states.get(startStateId);
        if (start.truthValue() == PaperTruthValue.FALSE) {
            return OptionalInt.of(0);
        }
        Queue<StateDistance> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(new StateDistance(startStateId, 0));
        visited.add(startStateId);
        while (!queue.isEmpty()) {
            StateDistance current = queue.remove();
            for (Transition transition : outgoing.getOrDefault(current.stateId(), List.of())) {
                if (!visited.add(transition.toStateId())) {
                    continue;
                }
                int distance = current.distance() + 1;
                State target = states.get(transition.toStateId());
                if (target.truthValue() == PaperTruthValue.FALSE) {
                    return OptionalInt.of(distance);
                }
                queue.add(new StateDistance(target.id(), distance));
            }
        }
        return OptionalInt.empty();
    }

    public record State(String id, PaperTruthValue truthValue) {
        public State {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("state id is required");
            }
            id = id.trim();
            Objects.requireNonNull(truthValue, "truthValue");
        }
    }

    public record Transition(
            String fromStateId,
            String toStateId,
            PaperCondition condition) {
        public Transition {
            if (fromStateId == null || fromStateId.isBlank()
                    || toStateId == null || toStateId.isBlank()) {
                throw new IllegalArgumentException("transition state ids are required");
            }
            fromStateId = fromStateId.trim();
            toStateId = toStateId.trim();
            Objects.requireNonNull(condition, "condition");
        }
    }

    public record Distance(
            int graphDistance,
            double weightedConditionSatisfaction,
            double combinedDistance,
            Optional<PaperCondition> nextCondition) {
        public Distance {
            if (graphDistance < 0) {
                throw new IllegalArgumentException("graphDistance must not be negative");
            }
            if (!Double.isFinite(weightedConditionSatisfaction)
                    || weightedConditionSatisfaction < 0.0
                    || weightedConditionSatisfaction > 1.0) {
                throw new IllegalArgumentException("weightedConditionSatisfaction must be in [0, 1]");
            }
            if (!Double.isFinite(combinedDistance)) {
                throw new IllegalArgumentException("combinedDistance must be finite");
            }
            nextCondition = nextCondition == null ? Optional.empty() : nextCondition;
        }
    }

    public static final class Builder {
        private final String initialStateId;
        private final Map<String, State> states = new LinkedHashMap<>();
        private final List<Transition> transitions = new ArrayList<>();

        private Builder(String initialStateId) {
            if (initialStateId == null || initialStateId.isBlank()) {
                throw new IllegalArgumentException("initialStateId is required");
            }
            this.initialStateId = initialStateId.trim();
        }

        public Builder state(String id, PaperTruthValue truthValue) {
            State state = new State(id, truthValue);
            if (states.putIfAbsent(state.id(), state) != null) {
                throw new IllegalArgumentException("Duplicate monitor state '" + state.id() + "'");
            }
            return this;
        }

        public Builder transition(String fromStateId, String toStateId, PaperCondition condition) {
            transitions.add(new Transition(fromStateId, toStateId, condition));
            return this;
        }

        public PaperMonitorFsm build() {
            if (!states.containsKey(initialStateId)) {
                throw new IllegalArgumentException("Initial state '" + initialStateId + "' is not declared");
            }
            for (Transition transition : transitions) {
                if (!states.containsKey(transition.fromStateId())) {
                    throw new IllegalArgumentException(
                            "Transition source '" + transition.fromStateId() + "' is not declared");
                }
                if (!states.containsKey(transition.toStateId())) {
                    throw new IllegalArgumentException(
                            "Transition target '" + transition.toStateId() + "' is not declared");
                }
            }
            return new PaperMonitorFsm(states, transitions, initialStateId);
        }
    }

    private record StateDistance(String stateId, int distance) {
    }
}
