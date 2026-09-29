package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RemoveRulesFixStrategy: attempts to fix a specification violation by finding the minimal sets of
 * repair-scope rules to remove, then re-verifying with NuSMV.
 *
 * Algorithm:
 * 1. Try removing 1 rule, then combinations of 2, 3, ... up to every rule in the repair scope.
 * 2. For each candidate set, regenerate the NuSMV model without those rules and re-verify
 *    (see FixStrategyUtils#forwardVerify).
 * 3. List every accepted combination, skipping those that contain a listed one (see FixAlternatives).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoveRulesFixStrategy implements FixStrategy {

    private static final String NAME = "remove";

    private final SmvGenerator smvGenerator;
    private final NusmvExecutor nusmvExecutor;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean requiresViolatedSpec() {
        return false;
    }

    @Override
    public StrategyOutcome tryFix(FixContext ctx) {
        List<RuleDto> allRules = ctx.getAllRules();
        int maxAttempts = ctx.getMaxAttempts() > 0 ? ctx.getMaxAttempts() : 20;

        // The repair scope includes rules that stayed dormant in this trace but can influence the
        // property: one of them may take over once the localized rule is gone, so a minimal removal
        // set must be able to contain it.
        List<Integer> candidates = ctx.getRepairRuleIndices().stream()
                .filter(index -> index != null && index >= 0 && index < allRules.size())
                .toList();
        if (candidates.isEmpty()) {
            return StrategyOutcome.none();
        }
        ctx.initializeStrategySearch(NAME, maxAttempts);

        // Smallest removals first: a combination containing a listed one is then never checked.
        FixAlternatives alternatives = new FixAlternatives(ctx);
        boolean exhausted = true;
        for (int size = 1; size <= candidates.size() && exhausted; size++) {
            exhausted = checkRemovals(candidates, new ArrayList<>(), 0, size, alternatives, ctx);
        }

        StrategyOutcome outcome = alternatives.outcome(exhausted);
        if (outcome.suggestions().isEmpty() && outcome.alternativesComplete()) {
            // Every combination reached forward verification and was rejected; there is no pinned
            // counterexample step here that could have ruled a combination out before it.
            ctx.recordStrategyNoResult(NAME, "ALL_CANDIDATES_REJECTED",
                    "Every combination of the rules that can influence the violated property was checked;"
                            + " removing any of them either leaves the target specification violated, breaks a"
                            + " specification the original rules satisfied, or changes the fixed attack scenario.");
        } else if (outcome.suggestions().isEmpty() && !exhausted && !ctx.isExpired()) {
            ctx.recordStrategyNoResult(NAME, "SEARCH_BUDGET_EXHAUSTED",
                    "Rule-removal search reached its candidate limit before all candidate combinations"
                            + " were checked.");
        }
        return outcome;
    }

    /**
     * Check, in lexicographic order, every removal of {@code size} rules that extends {@code chosen} with
     * candidates from {@code from} on. A prefix that already contains a listed removal is skipped whole,
     * since every extension of it does too.
     *
     * @return false when the search stopped before checking them all
     */
    private boolean checkRemovals(List<Integer> candidates, List<Integer> chosen, int from, int size,
                                  FixAlternatives alternatives, FixContext ctx) {
        if (alternatives.covers(changes(chosen))) return true;
        if (chosen.size() == size) return check(chosen, alternatives, ctx);
        for (int i = from; i <= candidates.size() - (size - chosen.size()); i++) {
            chosen.add(candidates.get(i));
            boolean finished = checkRemovals(candidates, chosen, i + 1, size, alternatives, ctx);
            chosen.remove(chosen.size() - 1);
            if (!finished) return false;
        }
        return true;
    }

    /** @return false when the removal could not be checked: listing limit, attempt budget or time. */
    private boolean check(List<Integer> removal, FixAlternatives alternatives, FixContext ctx) {
        if (alternatives.full() || !ctx.hasStrategyAttemptsLeft(NAME) || ctx.isExpired()) return false;
        ctx.addStrategyAttempts(NAME, 1);
        List<RuleDto> allRules = ctx.getAllRules();
        List<RuleDto> remainingRules = removeRulesByIndex(allRules, removal);
        log.info("Fix attempt {}: removing rule indices {} ({} rules remaining)",
                ctx.strategySearchProgress(NAME).attemptsUsed(), removal, remainingRules.size());

        FixStrategyUtils.Verification verification = FixStrategyUtils.forwardVerify(
                smvGenerator, nusmvExecutor, ctx, remainingRules, NAME);
        if (verification.isAccepted()) {
            alternatives.accept(changes(removal), suggestion(List.copyOf(removal), allRules), verification);
        } else {
            alternatives.notAccepted(changes(removal), verification);
        }
        return true;
    }

    private static Set<String> changes(List<Integer> removal) {
        return removal.stream().map(String::valueOf).collect(Collectors.toSet());
    }

    private static FixSuggestionDto suggestion(List<Integer> removal, List<RuleDto> allRules) {
        List<String> ruleDescriptions = removal.stream()
                .map(idx -> describeRule(allRules, idx))
                .collect(Collectors.toList());
        return FixSuggestionDto.builder()
                .strategy(NAME)
                .description("Permanently remove " + removal.size() + " automation rule(s): "
                        + String.join(", ", ruleDescriptions))
                .removedRuleIndices(removal)
                .removedRuleDescriptions(ruleDescriptions)
                .build();
    }

    private List<RuleDto> removeRulesByIndex(List<RuleDto> allRules, List<Integer> indicesToRemove) {
        Set<Integer> removeSet = new HashSet<>(indicesToRemove);
        List<RuleDto> remaining = new ArrayList<>();
        for (int i = 0; i < allRules.size(); i++) {
            if (!removeSet.contains(i)) {
                remaining.add(allRules.get(i));
            }
        }
        return remaining;
    }

    private static String describeRule(List<RuleDto> rules, int ruleIndex) {
        if (rules != null && ruleIndex >= 0 && ruleIndex < rules.size()) {
            RuleDto rule = rules.get(ruleIndex);
            if (rule != null && rule.getRuleString() != null && !rule.getRuleString().isBlank()) {
                return rule.getRuleString().trim();
            }
        }
        return "Unnamed automation rule";
    }
}
