package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Boolean transition condition with the satisfaction aggregation from HAFuzz Algorithm 1.
 */
public interface PaperCondition {

    PaperCondition TRUE = new Constant(true);
    PaperCondition FALSE = new Constant(false);

    boolean test(PaperAtomValuation valuation);

    double satisfaction(PaperAtomValuation valuation);

    static PaperCondition atom(PaperAtom atom) {
        return new Atom(Objects.requireNonNull(atom, "atom"));
    }

    /** Keeps a condition in the current solver level without treating it as a rule-chain output. */
    static PaperCondition terminal(PaperCondition condition) {
        PaperCondition resolved = Objects.requireNonNull(condition, "condition");
        return resolved instanceof Terminal ? resolved : new Terminal(resolved);
    }

    static PaperCondition not(PaperCondition condition) {
        PaperCondition resolved = Objects.requireNonNull(condition, "condition");
        if (resolved instanceof Constant constant) {
            return constant.value() ? FALSE : TRUE;
        }
        if (resolved instanceof Not not) {
            return not.condition();
        }
        if (resolved instanceof Terminal terminal) {
            return terminal(not(terminal.condition()));
        }
        if (resolved instanceof All all) {
            return anyOf(all.conditions().stream().map(PaperCondition::not).toList());
        }
        if (resolved instanceof Any any) {
            return allOf(any.conditions().stream().map(PaperCondition::not).toList());
        }
        return new Not(resolved);
    }

    static PaperCondition allOf(PaperCondition... conditions) {
        return allOf(List.of(conditions));
    }

    static PaperCondition allOf(List<? extends PaperCondition> conditions) {
        List<PaperCondition> flattened = normalized(conditions, true);
        if (flattened.isEmpty()) {
            return TRUE;
        }
        if (flattened.size() == 1) {
            return flattened.get(0);
        }
        return new All(flattened);
    }

    static PaperCondition anyOf(PaperCondition... conditions) {
        return anyOf(List.of(conditions));
    }

    static PaperCondition anyOf(List<? extends PaperCondition> conditions) {
        List<PaperCondition> flattened = normalized(conditions, false);
        if (flattened.isEmpty()) {
            return FALSE;
        }
        if (flattened.size() == 1) {
            return flattened.get(0);
        }
        return new Any(flattened);
    }

    private static List<PaperCondition> normalized(
            List<? extends PaperCondition> conditions,
            boolean conjunction) {
        Objects.requireNonNull(conditions, "conditions");
        // Repeated conditions retain their occurrence weights in the conjunction mean.
        List<PaperCondition> flattened = new ArrayList<>();
        for (PaperCondition condition : conditions) {
            if (appendNormalized(
                    Objects.requireNonNull(condition, "condition"),
                    conjunction,
                    flattened)) {
                return List.of(conjunction ? FALSE : TRUE);
            }
        }
        return List.copyOf(flattened);
    }

    /** Returns true when an absorbing constant was found. Terminal nodes stay opaque. */
    private static boolean appendNormalized(
            PaperCondition condition,
            boolean conjunction,
            List<PaperCondition> destination) {
        if (condition instanceof Constant constant) {
            if (constant.value() != conjunction) {
                return true;
            }
            return false;
        }
        if (conjunction && condition instanceof All all) {
            for (PaperCondition child : all.conditions()) {
                if (appendNormalized(child, true, destination)) {
                    return true;
                }
            }
            return false;
        }
        if (!conjunction && condition instanceof Any any) {
            for (PaperCondition child : any.conditions()) {
                if (appendNormalized(child, false, destination)) {
                    return true;
                }
            }
            return false;
        }
        destination.add(condition);
        return false;
    }

    record Constant(boolean value) implements PaperCondition {
        @Override
        public boolean test(PaperAtomValuation valuation) {
            return value;
        }

        @Override
        public double satisfaction(PaperAtomValuation valuation) {
            return value ? 1.0 : 0.0;
        }
    }

    record Atom(PaperAtom atom) implements PaperCondition {
        public Atom {
            Objects.requireNonNull(atom, "atom");
        }

        @Override
        public boolean test(PaperAtomValuation valuation) {
            return Objects.requireNonNull(valuation, "valuation").isTrue(atom);
        }

        @Override
        public double satisfaction(PaperAtomValuation valuation) {
            return test(valuation) ? 1.0 : 0.0;
        }
    }

    record Not(PaperCondition condition) implements PaperCondition {
        public Not {
            Objects.requireNonNull(condition, "condition");
        }

        @Override
        public boolean test(PaperAtomValuation valuation) {
            return !condition.test(valuation);
        }

        @Override
        public double satisfaction(PaperAtomValuation valuation) {
            return test(valuation) ? 1.0 : 0.0;
        }
    }

    record Terminal(PaperCondition condition) implements PaperCondition {
        public Terminal {
            Objects.requireNonNull(condition, "condition");
        }

        @Override
        public boolean test(PaperAtomValuation valuation) {
            return condition.test(valuation);
        }

        @Override
        public double satisfaction(PaperAtomValuation valuation) {
            return condition.satisfaction(valuation);
        }
    }

    record All(List<PaperCondition> conditions) implements PaperCondition {
        public All {
            conditions = normalized(conditions, true);
            if (conditions.isEmpty()) {
                throw new IllegalArgumentException("All requires at least one condition");
            }
        }

        @Override
        public boolean test(PaperAtomValuation valuation) {
            return conditions.stream().allMatch(condition -> condition.test(valuation));
        }

        @Override
        public double satisfaction(PaperAtomValuation valuation) {
            return conditions.stream()
                    .mapToDouble(condition -> condition.satisfaction(valuation))
                    .average()
                    .orElse(1.0);
        }
    }

    record Any(List<PaperCondition> conditions) implements PaperCondition {
        public Any {
            conditions = normalized(conditions, false);
            if (conditions.isEmpty()) {
                throw new IllegalArgumentException("Any requires at least one condition");
            }
        }

        @Override
        public boolean test(PaperAtomValuation valuation) {
            return conditions.stream().anyMatch(condition -> condition.test(valuation));
        }

        @Override
        public double satisfaction(PaperAtomValuation valuation) {
            return conditions.stream()
                    .mapToDouble(condition -> condition.satisfaction(valuation))
                    .max()
                    .orElse(0.0);
        }
    }
}
