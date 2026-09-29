package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterExtractor;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.NusmvResult;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceReferenceResolver;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterTarget;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * §5.1 Rule Parameter Adjustment (Salus paper).
 *
 * <p>Parameterizes numeric thresholds of the rules that can influence the violated property as
 * FROZENVARs, uses NuSMV with the negated spec (¬ρ) on the pinned counterexample to find corrective
 * values — first one threshold at a time, then jointly — and forward-verifies each candidate on the
 * complete model before refining it toward the original values. It lists every minimal set of moved
 * thresholds it verifies (see {@link FixAlternatives}).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParameterAdjustStrategy implements FixStrategy {

    private static final String NAME = "parameter";
    private static final Set<String> NUMERIC_RELATIONS = Set.of(">", ">=", "<", "<=");

    private final SmvGenerator smvGenerator;
    private final NusmvExecutor nusmvExecutor;
    private final FixConfig fixConfig;

    /**
     * Outcome of one ¬ρ solve on the pinned counterexample. UNSAT is a proof that no value in the given
     * ranges prevents the violation; ERROR is not, and must never be reported as one. BUSY is the one
     * failure that says nothing about the model: NuSMV's concurrency cap refused the run, so the same
     * solve can succeed once a permit frees up.
     */
    private enum SolveStatus { CANDIDATE, UNSAT, ERROR, BUSY, GENERATION_FAILED }

    /**
     * {@code values} maps each FROZENVAR name to the solver's value and is empty unless CANDIDATE;
     * {@code error} explains an ERROR or BUSY. The caller decides whether an error is worth reporting:
     * in the search it is, during refinement of an already verified repair it is not.
     */
    private record SolveResult(SolveStatus status, Map<String, Integer> values, String error) {
        static SolveResult of(SolveStatus status) { return new SolveResult(status, Map.of(), null); }
        static SolveResult error(String reason) { return new SolveResult(SolveStatus.ERROR, Map.of(), reason); }
        boolean failed() { return status == SolveStatus.ERROR || status == SolveStatus.BUSY; }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public StrategyOutcome tryFix(FixContext ctx) {
        List<RuleDto> allRules = ctx.getAllRules();
        Map<String, DeviceSmvData> deviceSmvMap = ctx.getDeviceSmvMap();
        int maxAttempts = ctx.getMaxAttempts() > 0 ? ctx.getMaxAttempts() : 20;
        List<Integer> repairIndices = ctx.getRepairRuleIndices();

        if (allRules == null || repairIndices == null || repairIndices.isEmpty()) return StrategyOutcome.none();

        // Step 1: bounded integer thresholds in the rules that can influence the violated property.
        // The scope is fault-first, so the targets below are too.
        Map<String, ParameterizationConfig.ParamInfo> thresholds = new LinkedHashMap<>();
        List<ParameterAdjustment> adjustmentTemplate = new ArrayList<>();
        int eligibleTargetCount = 0;
        int preferredRangeEmptyIntersections = 0;

        for (int ruleIdx : repairIndices) {
            if (ruleIdx < 0 || ruleIdx >= allRules.size()) continue;
            RuleDto rule = allRules.get(ruleIdx);
            if (rule == null || rule.getConditions() == null) continue;

            for (int condIdx = 0; condIdx < rule.getConditions().size(); condIdx++) {
                RuleDto.Condition cond = rule.getConditions().get(condIdx);
                if (cond == null || cond.getRelation() == null || cond.getValue() == null) continue;

                String normalizedRel = SmvRelationUtils.normalizeRelation(cond.getRelation());
                if (!NUMERIC_RELATIONS.contains(normalizedRel)) continue;

                // Template numeric domains are integer ranges, so decimal thresholds are not valid targets.
                int originalValue;
                try {
                    originalValue = Integer.parseInt(cond.getValue().trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                // Resolve bounds from device template
                int[] bounds = resolveBounds(cond, deviceSmvMap);
                if (bounds == null || bounds[0] >= bounds[1]
                        || originalValue < bounds[0] || originalValue > bounds[1]) continue;

                String key = "r" + ruleIdx + "_c" + condIdx;
                String targetId = PreferredRangeSelection.targetIdFor(ctx.getTraceId(), ruleIdx, condIdx);
                String frozenVarName = "param_" + key;
                eligibleTargetCount++;
                ctx.registerParameterTarget(ParameterTarget.builder()
                        .targetId(targetId)
                        .ruleIndex(ruleIdx)
                        .conditionIndex(condIdx)
                        .attribute(cond.getAttribute())
                        .relation(normalizedRel)
                        .originalValue(cond.getValue().trim())
                        .lowerBound(bounds[0])
                        .upperBound(bounds[1])
                        .description(describeParameterTarget(allRules, ruleIdx, condIdx, cond,
                                normalizedRel))
                        .build());

                // Apply preferred range intersection if specified
                PreferredRange preferred = (ctx.getPreferredRanges() != null)
                        ? ctx.getPreferredRanges().get(targetId) : null;
                if (preferred != null) {
                    int prefLower = Math.max(bounds[0], preferred.getLower());
                    int prefUpper = Math.min(bounds[1], preferred.getUpper());
                    if (prefLower > prefUpper) {
                        // Deliberately *not* marked as matched: a range disjoint from the device's own
                        // domain was never searched, so reporting it as honoured would tell the user
                        // their constraint held when nothing tested it. Leaving it unmatched surfaces it
                        // in `unusedPreferredRangeSelections`, and the diagnostic says why.
                        preferredRangeEmptyIntersections++;
                        ctx.addDiagnostic("Preferred range " + preferred.getLower() + "-" + preferred.getUpper()
                                + " lies outside the device's own limits " + bounds[0] + "-" + bounds[1]
                                + "; that parameter was left unchanged.");
                        log.warn("Preferred range [{},{}] has no intersection with template bounds [{},{}] for {}, skipping",
                                preferred.getLower(), preferred.getUpper(), bounds[0], bounds[1], key);
                        continue;
                    }
                    ctx.markPreferredRangeTargetMatched(targetId);
                    bounds = new int[]{prefLower, prefUpper};
                }

                thresholds.put(key, ParameterizationConfig.ParamInfo.builder()
                        .frozenVarName(frozenVarName)
                        .lowerBound(bounds[0])
                        .upperBound(bounds[1])
                        .originalValue(cond.getValue().trim())
                        .build());

                adjustmentTemplate.add(ParameterAdjustment.builder()
                        .targetId(targetId)
                        .ruleIndex(ruleIdx)
                        .conditionIndex(condIdx)
                        .attribute(cond.getAttribute())
                        .relation(normalizedRel)
                        .originalValue(cond.getValue().trim())
                        .lowerBound(bounds[0])
                        .upperBound(bounds[1])
                        .build());
            }
        }

        if (thresholds.isEmpty()) {
            log.info("ParameterAdjustStrategy: no numeric conditions found in the repair scope");
            if (eligibleTargetCount == 0) {
                ctx.recordStrategyNoResult(NAME, "SKIPPED_NO_PARAMETERIZABLE_VALUES",
                        "No rule that can influence the violated property compares a numeric value with a threshold, so there is no parameter to adjust.");
            } else if (preferredRangeEmptyIntersections == eligibleTargetCount) {
                // Vacuously settled: the allowed ranges contain no value at all. Widening them is the
                // only way to a different answer, which is what the client says for narrowed ranges.
                ctx.recordStrategyNoResult(NAME, "NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE",
                        "Every eligible numeric target was excluded because its preferred range does not overlap the declared template bounds.");
            }
            return StrategyOutcome.none();
        }

        ctx.initializeStrategySearch(NAME, maxAttempts);
        log.info("ParameterAdjustStrategy: found {} parameterizable threshold(s)", thresholds.size());
        return new ThresholdSearch(ctx, allRules, thresholds, adjustmentTemplate).run(maxAttempts);
    }

    /**
     * One request's search. A repair is identified by the thresholds it moves (see
     * {@link FixAlternatives}); the same thresholds moved to other values are variants of it, and
     * refinement already picks the variant closest to the original.
     */
    private final class ThresholdSearch {
        private final FixContext ctx;
        private final List<RuleDto> allRules;
        private final Map<String, ParameterizationConfig.ParamInfo> thresholds;
        private final List<ParameterAdjustment> adjustmentTemplate;
        private final Map<String, Integer> originals = new LinkedHashMap<>();
        private final FixAlternatives alternatives;
        /** Checked, unaccepted assignments over every threshold, so no solve returns one again. */
        private final List<Map<String, Integer>> excluded = new ArrayList<>();

        ThresholdSearch(FixContext ctx, List<RuleDto> allRules,
                        Map<String, ParameterizationConfig.ParamInfo> thresholds,
                        List<ParameterAdjustment> adjustmentTemplate) {
            this.ctx = ctx;
            this.allRules = allRules;
            this.thresholds = thresholds;
            this.adjustmentTemplate = adjustmentTemplate;
            this.alternatives = new FixAlternatives(ctx);
            // tryFix admits only thresholds whose original value parses as an integer.
            thresholds.forEach((key, info) -> originals.put(key, Integer.parseInt(info.getOriginalValue())));
        }

        StrategyOutcome run(int maxAttempts) {
            // Phase 1: one threshold at a time. A repair that moves a single value is the easiest for a
            // user to judge, so those are found first. Each probe is one ¬ρ solve on the pinned
            // counterexample: UNSAT settles a threshold in a single NuSMV call, and only a returned
            // value pays for a full-model verification. Half the budget is reserved for the joint phase.
            // It needs every original value inside its search range: a preferred range that excludes
            // a threshold's original obliges that threshold to move, and a single-threshold solve holds
            // it at its original, so it would list repairs outside the range the user asked for.
            if (thresholds.size() > 1 && maxAttempts > 1 && originalsWithinRanges()
                    && !searchEachAlone(Math.max(1, maxAttempts / 2))) {
                return alternatives.outcome(false);
            }
            // Phase 2: all thresholds jointly. Redundant rules need this: moving either one alone
            // leaves the other able to reproduce the violation. Only its UNSAT proves that no other
            // repair exists.
            return searchJointly();
        }

        private boolean originalsWithinRanges() {
            return thresholds.entrySet().stream().allMatch(entry -> {
                int original = originals.get(entry.getKey());
                return original >= entry.getValue().getLowerBound() && original <= entry.getValue().getUpperBound();
            });
        }

        /** Each threshold alone, the others at their original values, in repair-scope order. */
        private boolean searchEachAlone(int phaseBudget) {
            int used = 0;
            for (String key : thresholds.keySet()) {
                Map<String, ParameterizationConfig.ParamInfo> alone = Map.of(key, thresholds.get(key));
                while (used < phaseBudget && canSolve()) {
                    used++;
                    ctx.addStrategyAttempts(NAME, 1);
                    SolveResult solved = solve(allRules, alone, exclusions(alone), ctx);
                    if (solved.status() == SolveStatus.GENERATION_FAILED) return false;
                    if (solved.failed()) {
                        FixStrategyUtils.recordSolverFailure(ctx, NAME, solved.error());
                        // A refused permit says nothing about the model, so the same solve is retried.
                        if (solved.status() == SolveStatus.BUSY) continue;
                    }
                    // UNSAT: this threshold alone cannot help. Any other failure: move on rather than
                    // repeat the identical solve; the joint phase still covers this threshold. A listed
                    // repair: other values of this threshold are only variants of it.
                    if (solved.status() != SolveStatus.CANDIDATE || offer(solved.values())) break;
                }
                if (used >= phaseBudget) break;
            }
            return true;
        }

        private StrategyOutcome searchJointly() {
            boolean exhausted = false;
            while (canSolve()) {
                ctx.addStrategyAttempts(NAME, 1);
                SolveResult solved = solve(allRules, thresholds, exclusions(thresholds), ctx);
                if (solved.status() == SolveStatus.GENERATION_FAILED) return alternatives.outcome(false);
                if (solved.status() == SolveStatus.UNSAT) {
                    // More exclusions only restrict further, so no later attempt can succeed.
                    exhausted = true;
                    break;
                }
                if (solved.failed()) {
                    // The next iteration would run the identical model (no exclusion was added), so a
                    // retry can only repeat the failure until the deadline, unless the run was merely
                    // refused a permit.
                    FixStrategyUtils.recordSolverFailure(ctx, NAME, solved.error());
                    if (solved.status() == SolveStatus.BUSY) continue;
                    break;
                }
                offer(solved.values());
            }
            return finish(exhausted);
        }

        private StrategyOutcome finish(boolean exhausted) {
            StrategyOutcome outcome = alternatives.outcome(exhausted);
            if (!outcome.suggestions().isEmpty()) return outcome;
            if (outcome.alternativesComplete()) {
                log.info("ParameterAdjust: no threshold assignment is left to check, no parameter fix exists");
                // The proof supersedes a transient solver failure on an earlier attempt.
                ctx.clearStrategySolverFailure(NAME);
                if (alternatives.anyRejected()) {
                    ctx.recordStrategyNoResult(NAME, "ALL_CANDIDATES_REJECTED",
                            "Threshold values that prevent this counterexample exist, but forward verification on"
                                    + " the complete model rejected every one of them.");
                } else {
                    ctx.recordStrategyNoResult(NAME, "NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE",
                            "No threshold values within the allowed ranges prevent this counterexample.");
                }
            } else if (!ctx.isExpired() && !ctx.hasStrategyAttemptsLeft(NAME)) {
                recordBudgetExhausted(ctx);
            }
            return outcome;
        }

        /**
         * Forward-verify a solved assignment and list it if accepted. Either way it is excluded from
         * later solves: a listed repair through the superset exclusion, anything else exactly.
         *
         * @return whether the assignment was listed
         */
        private boolean offer(Map<String, Integer> solvedValues) {
            Map<String, Integer> assignment = new LinkedHashMap<>(originals);
            solvedValues.forEach((frozenVar, value) -> assignment.put(keyOfFrozenVar(thresholds, frozenVar), value));
            Set<String> moved = moved(assignment);
            if (moved.isEmpty()) {
                // The counterexample's own board: nothing to verify, and nothing the user could apply.
                excluded.add(assignment);
                return false;
            }
            FixStrategyUtils.Verification verification = FixStrategyUtils.forwardVerify(
                    smvGenerator, nusmvExecutor, ctx, withValues(assignment, moved), NAME);
            if (!verification.isAccepted()) {
                excluded.add(assignment);
                alternatives.notAccepted(moved, verification);
                log.info("ParameterAdjust: candidate moving {} was not accepted, excluding it", moved);
                return false;
            }
            list(assignment, moved, verification);
            return true;
        }

        /** Refine an accepted assignment toward the original values and list the result. */
        private void list(Map<String, Integer> assignment, Set<String> moved,
                          FixStrategyUtils.Verification verification) {
            Map<String, String> discovered = new LinkedHashMap<>();
            assignment.forEach((key, value) ->
                    discovered.put(thresholds.get(key).getFrozenVarName(), String.valueOf(value)));
            List<ParameterAdjustment> adjustments = new ArrayList<>();
            for (ParameterAdjustment template : adjustmentTemplate) {
                if (moved.contains(paramKey(template))) {
                    adjustments.add(adjustmentWithValue(template, assignment.get(paramKey(template)), allRules));
                }
            }
            FixStrategyUtils.Verification refined =
                    refineToClosest(adjustments, thresholds, discovered, verification, allRules, ctx);
            // A threshold refinement returned to its original value is no longer part of this repair.
            adjustments.removeIf(adjustment -> Objects.equals(
                    safeParseInt(adjustment.getNewValue()), safeParseInt(adjustment.getOriginalValue())));
            adjustments.forEach(adjustment ->
                    adjustment.setDescription(describeParameterAdjustment(adjustment, allRules)));
            alternatives.accept(
                    adjustments.stream().map(ParameterAdjustStrategy::paramKey).collect(Collectors.toSet()),
                    FixSuggestionDto.builder()
                            .strategy(NAME)
                            .description("Adjust parameter(s): " + adjustments.stream()
                                    .map(ParameterAdjustment::getDescription)
                                    .collect(Collectors.joining("; ")))
                            .parameterAdjustments(adjustments)
                            .build(),
                    refined);
        }

        private Set<String> moved(Map<String, Integer> assignment) {
            Set<String> moved = new LinkedHashSet<>();
            assignment.forEach((key, value) -> {
                if (!value.equals(originals.get(key))) moved.add(key);
            });
            return moved;
        }

        /**
         * INVARs for a solve over {@code space}, the thresholds outside it held at their original
         * values: no excluded assignment, and no assignment that moves at least the thresholds of a
         * listed repair. One that moves a threshold outside the space cannot occur in it and is left out.
         */
        private List<String> exclusions(Map<String, ParameterizationConfig.ParamInfo> space) {
            List<String> invars = new ArrayList<>();
            for (Map<String, Integer> assignment : excluded) {
                if (!space.keySet().containsAll(moved(assignment))) continue;
                invars.add("!(" + space.keySet().stream()
                        .map(key -> space.get(key).getFrozenVarName() + "=" + assignment.get(key))
                        .collect(Collectors.joining(" & ")) + ")");
            }
            for (Set<String> changes : alternatives.listedChanges()) {
                if (!space.keySet().containsAll(changes)) continue;
                invars.add("!(" + space.keySet().stream()
                        .filter(changes::contains)
                        .map(key -> space.get(key).getFrozenVarName() + "!=" + originals.get(key))
                        .collect(Collectors.joining(" & ")) + ")");
            }
            return invars;
        }

        private boolean canSolve() {
            return !alternatives.full() && ctx.hasStrategyAttemptsLeft(NAME) && !ctx.isExpired();
        }

        private List<RuleDto> withValues(Map<String, Integer> assignment, Set<String> keys) {
            List<RuleDto> modified = FixStrategyUtils.deepCopyRules(allRules);
            keys.forEach(key -> applyParamValue(modified, key, String.valueOf(assignment.get(key))));
            return modified;
        }
    }

    private static String paramKey(ParameterAdjustment template) {
        return "r" + template.getRuleIndex() + "_c" + template.getConditionIndex();
    }

    private static String keyOfFrozenVar(Map<String, ParameterizationConfig.ParamInfo> thresholds, String frozenVar) {
        return thresholds.entrySet().stream()
                .filter(entry -> entry.getValue().getFrozenVarName().equals(frozenVar))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private static void recordBudgetExhausted(FixContext ctx) {
        ctx.recordStrategyNoResult(NAME, "SEARCH_BUDGET_EXHAUSTED",
                "Parameter search reached its candidate limit before it could establish that no repair exists.");
    }

    private static ParameterAdjustment adjustmentWithValue(
            ParameterAdjustment template, int value, List<RuleDto> allRules) {
        ParameterAdjustment adjustment = ParameterAdjustment.builder()
                .targetId(template.getTargetId())
                .ruleIndex(template.getRuleIndex())
                .conditionIndex(template.getConditionIndex())
                .attribute(template.getAttribute())
                .relation(template.getRelation())
                .originalValue(template.getOriginalValue())
                .newValue(String.valueOf(value))
                .lowerBound(template.getLowerBound())
                .upperBound(template.getUpperBound())
                .build();
        adjustment.setDescription(describeParameterAdjustment(adjustment, allRules));
        return adjustment;
    }

    static long distance(int value, int original) {
        return Math.abs((long) value - original);
    }

    static int[] refinementWindow(
            int original, long bestDistance, int lowerBound, int upperBound) {
        long lower = Math.max((long) lowerBound, (long) original - bestDistance + 1L);
        long upper = Math.min((long) upperBound, (long) original + bestDistance - 1L);
        return new int[]{(int) lower, (int) upper};
    }

    static String describeParameterAdjustment(ParameterAdjustment adjustment, List<RuleDto> allRules) {
        String base = describeRule(allRules, adjustment.getRuleIndex())
                + ": adjust parameter condition " + (adjustment.getConditionIndex() + 1)
                + " (" + adjustment.getAttribute() + " " + adjustment.getRelation() + " "
                + adjustment.getOriginalValue() + " -> " + adjustment.getNewValue() + ")";
        Integer newVal = safeParseInt(adjustment.getNewValue());
        String relation = SmvRelationUtils.normalizeRelation(adjustment.getRelation());
        if (newVal != null && ((">".equals(relation) && newVal == adjustment.getUpperBound())
                || ("<".equals(relation) && newVal == adjustment.getLowerBound()))) {
            base += " (strict boundary makes the rule unreachable)";
        }
        return base;
    }

    private static String describeParameterTarget(
            List<RuleDto> allRules,
            int ruleIndex,
            int conditionIndex,
            RuleDto.Condition condition,
            String normalizedRelation) {
        return describeRule(allRules, ruleIndex)
                + ": parameter condition " + (conditionIndex + 1)
                + " (" + condition.getAttribute() + " " + normalizedRelation + " "
                + condition.getValue().trim() + ")";
    }

    private static String describeRule(List<RuleDto> rules, int ruleIndex) {
        if (rules != null && ruleIndex >= 0 && ruleIndex < rules.size()) {
            RuleDto rule = rules.get(ruleIndex);
            if (rule != null && rule.getRuleString() != null && !rule.getRuleString().isBlank()) {
                return "'" + rule.getRuleString() + "'";
            }
        }
        return "Rule #" + (ruleIndex + 1);
    }

    // ======================== §5.3: Refine to closest original ========================

    /**
     * §5.3 NuSMV-guided branch-and-bound refinement (heuristic extension of paper §5.1/§5.3).
     *
     * <p>For each parameter, uses distance-based range narrowing with NuSMV ¬ρ to find
     * a valid value closer to the original than the initial discovery. Does not assume
     * monotonicity — uses exclusion constraints instead of binary search pruning.</p>
     *
     * <p>Budget: maxRefineAttempts counts refinement loop iterations (each = 1 NuSMV ¬ρ + up to 1
     * forward-verify), shared across all parameters, so with a small budget later parameters may have
     * reduced refinement capacity. The try-original step is outside the budget and runs only for a
     * joint repair in which another threshold moved (extra cost ≤ param count).
     * Total NuSMV calls ≤ paramCount + maxRefineAttempts × 2.</p>
     *
     * @param verification the forward verification of the assignment in {@code discoveredValues}
     * @return the forward verification of the refined assignment, which is the one the caller lists
     */
    private FixStrategyUtils.Verification refineToClosest(
            List<ParameterAdjustment> candidateAdjustments,
            Map<String, ParameterizationConfig.ParamInfo> thresholds,
            Map<String, String> discoveredValues,
            FixStrategyUtils.Verification verification,
            List<RuleDto> allRules,
            FixContext ctx) {

        FixStrategyUtils.Verification accepted = verification;
        int[] remainingBudget = { fixConfig.getMaxRefineAttempts() };

        for (ParameterAdjustment adj : candidateAdjustments) {
            if (remainingBudget[0] <= 0 || ctx.isExpired()) break;

            int original;
            int current;
            try {
                original = Integer.parseInt(adj.getOriginalValue());
                current = Integer.parseInt(adj.getNewValue());
            } catch (NumberFormatException e) {
                continue;
            }

            String paramKey = "r" + adj.getRuleIndex() + "_c" + adj.getConditionIndex();
            ParameterizationConfig.ParamInfo paramInfo = thresholds.get(paramKey);
            if (paramInfo == null) continue;

            int best = current;
            long bestDist = distance(best, original);

            // Step A: Try original value (other params changed, original may now work; not counted in budget).
            //
            // Only when the original still lies inside this target's effective bounds. Those bounds are
            // already the intersection of the manifest domain with the user's preferred range, so a user
            // who narrowed the range to exclude the original must not be handed the original back
            // labelled with the range they asked for.
            //
            // And only when some other threshold actually moved. Otherwise the "original" board is the
            // one that produced this counterexample, and a full-model verification would spend tens of
            // seconds re-proving a violation already on record.
            boolean originalWithinBounds =
                    original >= paramInfo.getLowerBound() && original <= paramInfo.getUpperBound();
            boolean otherThresholdMoved = thresholds.entrySet().stream()
                    .filter(e -> !e.getKey().equals(paramKey))
                    .anyMatch(e -> !Objects.equals(
                            safeParseInt(discoveredValues.get(e.getValue().getFrozenVarName())),
                            safeParseInt(e.getValue().getOriginalValue())));
            boolean originalRejected = false;
            if (bestDist > 0 && originalWithinBounds && otherThresholdMoved) {
                List<RuleDto> testRules = FixStrategyUtils.deepCopyRules(allRules);
                for (Map.Entry<String, ParameterizationConfig.ParamInfo> e : thresholds.entrySet()) {
                    String value;
                    if (e.getKey().equals(paramKey)) {
                        value = String.valueOf(original);
                    } else {
                        value = discoveredValues.get(e.getValue().getFrozenVarName());
                        if (value == null) continue;
                    }
                    applyParamValue(testRules, e.getKey(), value);
                }
                try {
                    FixStrategyUtils.Verification originalVerification =
                            FixStrategyUtils.forwardVerify(smvGenerator, nusmvExecutor, ctx, testRules, NAME);
                    if (originalVerification.isAccepted()) {
                        best = original;
                        bestDist = 0;
                        accepted = originalVerification;
                    }
                    originalRejected = originalVerification.verdict() == FixStrategyUtils.Verdict.REJECTED;
                } catch (Exception e) {
                    // forwardVerify re-arms the flag itself, but keep the guard at the catch so a
                    // future call added inside this block cannot silently swallow a cancellation.
                    FixStrategyUtils.preserveInterrupt(e);
                    log.warn("refineToClosest: original-value test failed: {}", e.getMessage());
                }
            }

            if (bestDist <= 1) {
                adj.setNewValue(String.valueOf(best));
                discoveredValues.put(paramInfo.getFrozenVarName(), String.valueOf(best));
                continue;
            }

            // Step B: NuSMV-guided branch-and-bound loop. Every value it checks is excluded afterwards.
            Set<Integer> exclusionValues = new HashSet<>();
            exclusionValues.add(current); // don't re-return the initial discovered value
            if (!otherThresholdMoved || originalRejected) {
                // With nothing else moved, the original value rebuilds the board that produced the
                // counterexample (same reason as Step A); a rejected original is settled. Only an
                // inconclusive Step A leaves the original reachable, for one more check.
                exclusionValues.add(original);
            }

            // Prepare baseRules with other params fixed at discovered values
            List<RuleDto> baseRules = FixStrategyUtils.deepCopyRules(allRules);
            for (Map.Entry<String, ParameterizationConfig.ParamInfo> e : thresholds.entrySet()) {
                if (e.getKey().equals(paramKey)) continue;
                String value = discoveredValues.get(e.getValue().getFrozenVarName());
                if (value != null) {
                    applyParamValue(baseRules, e.getKey(), value);
                }
            }

            refineLoop:
            while (remainingBudget[0] > 0 && bestDist > 1) {
                if (ctx.isExpired()) break;

                int[] window = refinementWindow(
                        original, bestDist, paramInfo.getLowerBound(), paramInfo.getUpperBound());
                int L = window[0];
                int U = window[1];
                if (L > U) break;

                remainingBudget[0]--;

                // Build exclusion INVARs
                List<String> exclusionInvars = new ArrayList<>();
                for (int excl : exclusionValues) {
                    exclusionInvars.add("!(" + paramInfo.getFrozenVarName() + "=" + excl + ")");
                }

                ParameterizationConfig.ParamInfo narrowed = ParameterizationConfig.ParamInfo.builder()
                        .frozenVarName(paramInfo.getFrozenVarName())
                        .lowerBound(L)
                        .upperBound(U)
                        .originalValue(paramInfo.getOriginalValue())
                        .build();
                SolveResult result = solve(baseRules, Map.of(paramKey, narrowed), exclusionInvars, ctx);

                switch (result.status()) {
                    case UNSAT:
                    case GENERATION_FAILED:
                    case ERROR:
                        // A failure would recur on the identical model, since nothing new was excluded.
                        // The value already accepted is listed as it stands.
                        break refineLoop;
                    case BUSY:
                        // A refused permit says nothing about the model; the shared budget bounds retries.
                        continue refineLoop;
                    case CANDIDATE:
                        int cand = result.values().get(paramInfo.getFrozenVarName());
                        // A value already excluded can come back only from a misbehaving solver.
                        if (!exclusionValues.add(cand)) continue refineLoop;

                        // Forward-verify the candidate (from baseRules copy, never mutate baseRules)
                        try {
                            List<RuleDto> verifyRules = FixStrategyUtils.deepCopyRules(baseRules);
                            applyParamValue(verifyRules, paramKey, String.valueOf(cand));
                            FixStrategyUtils.Verification candidateVerification =
                                    FixStrategyUtils.forwardVerify(smvGenerator, nusmvExecutor, ctx, verifyRules, NAME);
                            if (candidateVerification.isAccepted()) {
                                best = cand;
                                bestDist = distance(cand, original);
                                accepted = candidateVerification;
                            }
                        } catch (Exception e) {
                            FixStrategyUtils.preserveInterrupt(e);
                            log.warn("refineToClosest: candidate {} verify failed: {}", cand, e.getMessage());
                        }
                        continue refineLoop;
                }
            }

            adj.setNewValue(String.valueOf(best));
            discoveredValues.put(paramInfo.getFrozenVarName(), String.valueOf(best));
        }
        return accepted;
    }

    /**
     * One ¬ρ solve (Salus §5.1): the given thresholds become FROZENVARs over their ranges, the
     * counterexample's first state is pinned, and NuSMV checks the negated property. A counterexample to
     * ¬ρ carries threshold values under which the violation does not happen from that state; ¬ρ holding
     * proves no such values exist. The search phases and refinement share this, so a solver hiccup and a
     * genuine UNSAT are told apart the same way everywhere.
     *
     * <p>A generation failure is recorded here because it ends the whole strategy; solver errors are only
     * returned, see {@link SolveResult}.</p>
     */
    private SolveResult solve(
            List<RuleDto> rules,
            Map<String, ParameterizationConfig.ParamInfo> parameterized,
            List<String> exclusionInvars,
            FixContext ctx) {
        ParameterizationConfig config = ParameterizationConfig.builder()
                .parameterizedThresholds(new LinkedHashMap<>(parameterized))
                .negatedSpecIndex(ctx.getViolatedSpecIndex())
                .exclusionInvars(new ArrayList<>(exclusionInvars))
                .build();
        File smvFile = null;
        try {
            SmvGenerator.GenerateResult genResult =
                    FixStrategyUtils.generateParameterizedResolved(smvGenerator, ctx, rules, config);
            if (genResult == null) {
                ctx.recordStrategyGenerationFailure(NAME,
                        "The parameter candidate model could not preserve the original attack scenario.");
                return SolveResult.of(SolveStatus.GENERATION_FAILED);
            }
            if (!FixStrategyUtils.candidateModelComplete(genResult, ctx, NAME)) {
                return SolveResult.of(SolveStatus.GENERATION_FAILED);
            }
            smvFile = genResult.smvFile();

            NusmvResult result = FixStrategyUtils.executeWithinDeadline(nusmvExecutor, smvFile, ctx);
            if (!result.isSuccess()) {
                log.warn("ParameterAdjust solve: NuSMV execution failed: {}", result.getErrorMessage());
                return result.isBusy()
                        ? new SolveResult(SolveStatus.BUSY, Map.of(),
                                "NuSMV was busy while searching for parameter values.")
                        : SolveResult.error("NuSMV failed while searching for parameter values.");
            }
            List<SpecCheckResult> specResults = result.getSpecResults();
            if (specResults == null || specResults.isEmpty()) {
                return SolveResult.error("NuSMV returned no usable specification result during parameter search.");
            }
            if (specResults.get(0).isPassed()) {
                return SolveResult.of(SolveStatus.UNSAT);
            }

            List<String> frozenVarNames = parameterized.values().stream()
                    .map(ParameterizationConfig.ParamInfo::getFrozenVarName)
                    .toList();
            Map<String, String> extracted = ParameterExtractor.extract(result.getOutput(), frozenVarNames);
            Map<String, Integer> values = new LinkedHashMap<>();
            for (ParameterizationConfig.ParamInfo info : parameterized.values()) {
                Integer value = safeParseInt(extracted.get(info.getFrozenVarName()));
                if (value == null) {
                    return SolveResult.error("NuSMV output did not contain the parameter assignment required by the search.");
                }
                // Defensive: NuSMV honours FROZENVAR ranges, but an out-of-range value must not be applied.
                if (value < info.getLowerBound() || value > info.getUpperBound()) {
                    log.warn("ParameterAdjust solve: {}={} outside [{},{}]", info.getFrozenVarName(), value,
                            info.getLowerBound(), info.getUpperBound());
                    return SolveResult.error("NuSMV returned a parameter value outside the requested bounds.");
                }
                values.put(info.getFrozenVarName(), value);
            }
            return new SolveResult(SolveStatus.CANDIDATE, values, null);
        } catch (Exception e) {
            log.warn("ParameterAdjust solve failed: {}", e.getMessage(), e);
            if (e instanceof cn.edu.nju.Iot_Verify.exception.SmvGenerationException) {
                String reason = "Parameter candidate generation failed: " + e.getMessage();
                ctx.addDiagnostic(reason);
                ctx.recordStrategyGenerationFailure(NAME, reason);
                return SolveResult.of(SolveStatus.GENERATION_FAILED);
            }
            // executeWithinDeadline declares InterruptedException, and this catch consumes it — clearing
            // the flag. Re-arm it so ctx.isExpired() stops every loop that calls this.
            FixStrategyUtils.preserveInterrupt(e);
            return SolveResult.error("Parameter search encountered an execution error: " + e.getMessage());
        } finally {
            FixStrategyUtils.cleanupTempDir(smvFile);
        }
    }

    /** Apply a param value to rules by parsing the key "r{ruleIdx}_c{condIdx}". */
    private static void applyParamValue(List<RuleDto> rules, String key, String value) {
        String[] parts = key.replace("r", "").split("_c");
        if (parts.length != 2) return;
        try {
            int ruleIdx = Integer.parseInt(parts[0]);
            int condIdx = Integer.parseInt(parts[1]);
            if (ruleIdx < rules.size()) {
                RuleDto rule = rules.get(ruleIdx);
                if (rule.getConditions() != null && condIdx < rule.getConditions().size()) {
                    rule.getConditions().get(condIdx).setValue(value);
                }
            }
        } catch (NumberFormatException ignored) {
            // skip
        }
    }

    // ======================== Helpers ========================

    private int[] resolveBounds(RuleDto.Condition condition, Map<String, DeviceSmvData> deviceSmvMap) {
        String deviceId = condition.getDeviceName();
        if (deviceId == null) return null;

        DeviceSmvData smv;
        try {
            smv = DeviceReferenceResolver.resolve(deviceId, deviceSmvMap);
        } catch (Exception e) {
            // An unresolvable device reference legitimately means "no parameterizable bounds here", so
            // the null is the honest answer — but a cancellation must not be read as that answer.
            FixStrategyUtils.preserveInterrupt(e);
            return null;
        }
        if (smv == null) return null;

        String attr = condition.getAttribute();
        if (attr == null) return null;

        // Check internal variables
        if (smv.getVariables() != null) {
            for (DeviceManifest.InternalVariable var : smv.getVariables()) {
                if (var != null && attr.equals(var.getName())
                        && var.getLowerBound() != null && var.getUpperBound() != null) {
                    return new int[]{var.getLowerBound(), var.getUpperBound()};
                }
            }
        }

        // Check env variables
        if (smv.getEnvVariables() != null) {
            DeviceManifest.InternalVariable envVar = smv.getEnvVariables().get(attr);
            if (envVar != null && envVar.getLowerBound() != null && envVar.getUpperBound() != null) {
                return new int[]{envVar.getLowerBound(), envVar.getUpperBound()};
            }
        }

        return null;
    }

    /** Parse int safely, returning null on failure (non-integer NuSMV output). */
    private static Integer safeParseInt(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
