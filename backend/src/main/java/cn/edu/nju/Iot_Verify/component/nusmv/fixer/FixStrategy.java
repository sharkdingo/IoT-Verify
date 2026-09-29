package cn.edu.nju.Iot_Verify.component.nusmv.fixer;

import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;

import java.util.List;

/**
 * Common interface for fix strategies (OCP — Open/Closed Principle).
 *
 * <p>Implementations: ParameterAdjustStrategy (§5.1), ConditionAdjustStrategy (§5.2),
 * RemoveRulesFixStrategy (destructive fallback).</p>
 */
public interface FixStrategy {

    /**
     * Returns the strategy name used for dispatch ("parameter", "condition", or "remove").
     */
    String name();

    /**
     * Whether this strategy requires a valid {@code violatedSpecIndex} (≥ 0) in the context.
     * Strategies that need spec negation (§5.1, §5.2) return {@code true};
     * strategies like "remove" that work without it return {@code false}.
     *
     * <p>Default is {@code true} for safety — strategies that don't need it must opt out.</p>
     */
    default boolean requiresViolatedSpec() {
        return true;
    }

    /**
     * Search for forward-verified repairs of the violation described in {@code ctx}. Why no repair
     * was found is recorded on the context, not returned.
     */
    StrategyOutcome tryFix(FixContext ctx);

    /**
     * @param suggestions          the verified, minimal alternatives this strategy found, smallest
     *                             change first; empty when it found none
     * @param alternativesComplete {@code true} when the search space was exhausted, so no other
     *                             minimal repair of this kind exists; {@code false} when the listing
     *                             limit, the attempt budget or the time share cut it short, or a
     *                             candidate could not be checked
     */
    record StrategyOutcome(List<FixSuggestionDto> suggestions, boolean alternativesComplete) {
        public StrategyOutcome {
            suggestions = List.copyOf(suggestions);
        }

        /** Nothing found; the context records why. */
        public static StrategyOutcome none() {
            return new StrategyOutcome(List.of(), false);
        }
    }
}
