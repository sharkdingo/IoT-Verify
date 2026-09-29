package cn.edu.nju.Iot_Verify.component.nusmv.fixer;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize.FaultLocalizer;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize.RuleInfluenceScope;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixStrategyAttemptDto;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RuleFixer: orchestrates fault localization and fix strategy execution.
 *
 * <p>Default strategy order follows Salus paper §5:
 * parameter (§5.1) → condition (§5.2) → permanent rule removal (fallback).</p>
 */
@Slf4j
@Component
public class RuleFixer {

    private static final List<String> DEFAULT_STRATEGIES = List.of("parameter", "condition", "remove");
    /** Recorded outcomes that are facts about the model, not about how far the search got. */
    private static final Set<String> SETTLED_NO_RESULTS = Set.of(
            "NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", "ALL_CANDIDATES_REJECTED", "SKIPPED_NO_PARAMETERIZABLE_VALUES");

    private final FaultLocalizer faultLocalizer;
    private final Map<String, FixStrategy> strategyRegistry;
    private final FixConfig fixConfig;

    public RuleFixer(FaultLocalizer faultLocalizer, List<FixStrategy> strategies, FixConfig fixConfig) {
        this.faultLocalizer = faultLocalizer;
        this.fixConfig = fixConfig;
        this.strategyRegistry = strategies.stream()
                .collect(Collectors.toMap(
                        FixStrategy::name,
                        Function.identity(),
                        (existing, duplicate) -> {
                            log.warn("Duplicate FixStrategy name '{}': keeping {}, ignoring {}",
                                    existing.name(), existing.getClass().getSimpleName(),
                                    duplicate.getClass().getSimpleName());
                            return existing;
                        }));
    }

    /**
     * Only localize faults (no fix attempt): the rules that fired in the counterexample and can influence
     * the violated property.
     */
    public List<FaultRuleDto> localizeFaults(List<TraceStateDto> states,
                                              List<RuleDto> rules,
                                              String violatedSpecId,
                                              List<SpecificationDto> specs,
                                              Map<String, DeviceSmvData> deviceSmvMap) {
        int specIndex = resolveSpecIndex(violatedSpecId, specs);
        return repairScope(states, rules, specIndex < 0 ? null : specs.get(specIndex), deviceSmvMap)
                .faultRules();
    }

    /**
     * A rule that fired in the counterexample but cannot reach the violated property is not a fault of
     * that property, and no edit to it can repair it. When the property's scope is unknown the fired
     * rules are kept unfiltered, since dropping them would hide a possible cause.
     */
    private RepairScope repairScope(List<TraceStateDto> states, List<RuleDto> rules,
                                    SpecificationDto violatedSpec,
                                    Map<String, DeviceSmvData> deviceSmvMap) {
        List<FaultRuleDto> triggered = faultLocalizer.localize(states, rules, deviceSmvMap);
        Optional<Set<Integer>> influence =
                RuleInfluenceScope.relevantRuleIndices(violatedSpec, rules, deviceSmvMap);
        List<FaultRuleDto> faults = influence
                .map(scope -> triggered.stream()
                        .filter(fault -> scope.contains(fault.getRuleIndex()))
                        .toList())
                .orElse(triggered);
        // Fault rules first so bounded searches start from the rules the counterexample exercised.
        Set<Integer> repairIndices = new LinkedHashSet<>();
        faults.forEach(fault -> repairIndices.add(fault.getRuleIndex()));
        if (!faults.isEmpty()) {
            influence.ifPresent(repairIndices::addAll);
        }
        return new RepairScope(faults, List.copyOf(repairIndices), !triggered.isEmpty());
    }

    private record RepairScope(List<FaultRuleDto> faultRules, List<Integer> repairRuleIndices,
                               boolean anyRuleFired) {
    }

    /**
     * Full fix pipeline: localize faults, then attempt fix strategies.
     */
    public FixResultDto fix(Long traceId,
                            String violatedSpecId,
                            List<TraceStateDto> states,
                            List<RuleDto> rules,
                            List<DeviceVerificationDto> devices,
                            List<SpecificationDto> specs,
                            Map<String, DeviceSmvData> deviceSmvMap,
                            Long userId,
                            AttackScenarioDto attackScenario,
                            boolean enablePrivacy,
                            List<String> strategies,
                            int maxAttempts,
                            Map<String, PreferredRange> preferredRanges) {
        return fix(traceId, violatedSpecId, states, rules, devices, List.of(), specs, deviceSmvMap,
                userId, attackScenario, enablePrivacy, strategies, maxAttempts, preferredRanges);
    }

    public FixResultDto fix(Long traceId,
                            String violatedSpecId,
                            List<TraceStateDto> states,
                            List<RuleDto> rules,
                            List<DeviceVerificationDto> devices,
                            List<BoardEnvironmentVariableDto> environmentVariables,
                            List<SpecificationDto> specs,
                            Map<String, DeviceSmvData> deviceSmvMap,
                            Long userId,
                            AttackScenarioDto attackScenario,
                            boolean enablePrivacy,
                            List<String> strategies,
                            int maxAttempts,
                            Map<String, PreferredRange> preferredRanges) {

        // Never clear the interrupt flag here: it is the search's only cancellation signal
        // (FixContext.isExpired reads it), and cancellation can already have arrived — fault
        // localization and context loading run before the strategy loops.
        AttackScenarioDto safeAttackScenario = Objects.requireNonNull(
                attackScenario, "attackScenario is required");
        if (strategies != null && strategies.isEmpty()) {
            throw new IllegalArgumentException(
                    "strategies must be non-empty when provided; use null for the default order");
        }
        List<String> effectiveStrategies = strategies != null ? strategies : DEFAULT_STRATEGIES;
        int violatedSpecIndex = resolveSpecIndex(violatedSpecId, specs);
        RepairScope scope = repairScope(states, rules,
                violatedSpecIndex < 0 ? null : specs.get(violatedSpecIndex), deviceSmvMap);
        List<FaultRuleDto> faultRules = scope.faultRules();
        log.info("Fault localization: {} fault rule(s), {} rule(s) in repair scope for trace {}",
                faultRules.size(), scope.repairRuleIndices().size(), traceId);

        if (faultRules.isEmpty()) {
            String emptyReason;
            // What is known is that no fired rule contributed; a rule that stayed idle might still have
            // been able to prevent the violation, and the repair search starts only from fired rules.
            if (scope.anyRuleFired()) {
                emptyReason = "Automation rules fired in the counterexample, but none of them can influence the "
                        + "violated property: device or environment behaviour alone produced the violation. "
                        + "Automatic fixes start from rules that contributed, so no repair was searched.";
            } else if (states == null || states.size() < 2) {
                emptyReason = "Counterexample trace has fewer than 2 states; fault localization requires at least 2 states.";
            } else {
                emptyReason = "No automation rule fired in the counterexample: device or environment behaviour "
                        + "alone produced the violation. Automatic fixes start from rules that contributed, so "
                        + "no repair was searched.";
            }
            return FixResultDto.builder()
                    .traceId(traceId)
                    .violatedSpecId(violatedSpecId)
                    .faultRules(faultRules)
                    .suggestions(List.of())
                    .strategyAttempts(effectiveStrategies.stream()
                            .map(strategy -> attempt(strategy, "SKIPPED_NO_FAULT_RULES",
                                    "No rule that fired in the counterexample can influence the violated "
                                            + "property, so this strategy was not run."))
                            .toList())
                    .fixable(false)
                    .sourceModelComplete(true)
                    .summary(emptyReason)
                    .warnings(List.of())
                    // No search ran, so every supplied selection went unhonoured. Reporting none
                    // here would silently drop a threshold the user explicitly pinned.
                    .unusedPreferredRangeSelections(
                            unusedPreferredRangeSelections(preferredRanges, Set.of()))
                    .build();
        }

        // Build shared context for all strategies
        FixContext ctx = FixContext.builder()
                .traceId(traceId)
                .faultRules(faultRules)
                .repairRuleIndices(scope.repairRuleIndices())
                .allRules(rules)
                .devices(devices)
                .environmentVariables(environmentVariables == null ? List.of() : environmentVariables)
                .specs(specs)
                .deviceSmvMap(deviceSmvMap)
                .violatedSpecIndex(violatedSpecIndex)
                .userId(userId)
                .attackScenario(safeAttackScenario)
                .enablePrivacy(enablePrivacy)
                .maxAttempts(maxAttempts)
                .preferredRanges(preferredRanges)
                .counterexampleInitialState(states == null || states.isEmpty() ? null : states.get(0))
                .requireCounterexampleReplay(true)
                .deadline(Instant.now().plusMillis(fixConfig.getFixTimeoutMs()))
                .build();

        // Step 2: Attempt fix strategies via registry
        List<FixSuggestionDto> suggestions = new ArrayList<>();
        List<FixStrategyAttemptDto> strategyAttempts = new ArrayList<>();

        for (int strategyIndex = 0; strategyIndex < effectiveStrategies.size(); strategyIndex++) {
            String strategyName = effectiveStrategies.get(strategyIndex);
            // Before the expiry check: it also ends the previous strategy's share of the time.
            ctx.beginStrategy(strategyName, runnableStrategies(
                    effectiveStrategies.subList(strategyIndex, effectiveStrategies.size()), violatedSpecIndex));
            if (ctx.isExpired()) {
                log.warn("Fix deadline expired before strategy '{}', skipping remaining strategies", strategyName);
                for (int skippedIndex = strategyIndex; skippedIndex < effectiveStrategies.size(); skippedIndex++) {
                    strategyAttempts.add(attempt(effectiveStrategies.get(skippedIndex), "SKIPPED_TIMEOUT",
                            "The automatic-fix time limit expired before this strategy could run."));
                }
                break;
            }
            FixStrategy strategy = strategyRegistry.get(strategyName);
            if (strategy == null) {
                log.info("Unsupported fix strategy '{}', skipping", strategyName);
                strategyAttempts.add(attempt(strategyName, "SKIPPED_UNSUPPORTED",
                        "The requested strategy is not supported by this server."));
                continue;
            }
            // Skip strategies that need a valid violatedSpecIndex when it's missing
            if (violatedSpecIndex < 0 && strategy.requiresViolatedSpec()) {
                log.info("Skipping '{}' strategy: no valid violated spec index", strategyName);
                strategyAttempts.add(attempt(strategyName, "SKIPPED_NO_SPEC",
                        "The violated specification could not be resolved from the trace context."));
                continue;
            }
            FixStrategy.StrategyOutcome outcome = strategy.tryFix(ctx);
            if (!outcome.suggestions().isEmpty()) {
                suggestions.addAll(outcome.suggestions());
                strategyAttempts.add(FixStrategyAttemptDto.builder()
                        .strategy(strategyName)
                        .status("VERIFIED")
                        .reason(outcome.suggestions().size() + " alternative suggestion(s) each satisfy the target"
                                + " specification on the complete generated model without violating any"
                                + " specification the original rules satisfied. "
                                + (outcome.alternativesComplete()
                                        ? "No other minimal repair of this kind exists."
                                        : "The search stopped before every candidate was checked, so other"
                                                + " repairs of this kind may exist."))
                        .alternativesComplete(outcome.alternativesComplete())
                        .build());
            } else if (settled(ctx.strategyNoResult(strategyName))) {
                // A settled verdict does not depend on the clock: a deadline that passed after the
                // strategy recorded it must not relabel it as an incomplete search.
                FixContext.StrategyNoResult noResult = ctx.strategyNoResult(strategyName);
                strategyAttempts.add(attempt(strategyName, noResult.status(), noResult.reason()));
            } else if (ctx.isExpired()) {
                strategyAttempts.add(attempt(strategyName, "TIMED_OUT",
                        "The automatic-fix time limit expired while this strategy was running; its search was incomplete."));
            } else if (ctx.strategyGenerationFailure(strategyName) != null) {
                strategyAttempts.add(attempt(strategyName, "FAILED_MODEL_GENERATION",
                        ctx.strategyGenerationFailure(strategyName)));
            } else if (ctx.strategySolverFailure(strategyName) != null) {
                strategyAttempts.add(attempt(strategyName, "FAILED_SOLVER_EXECUTION",
                        ctx.strategySolverFailure(strategyName)));
            } else if (ctx.strategyNoResult(strategyName) != null) {
                FixContext.StrategyNoResult noResult = ctx.strategyNoResult(strategyName);
                strategyAttempts.add(attempt(strategyName, noResult.status(), noResult.reason()));
            } else {
                // A strategy records the proof itself when its search settled every candidate, so what
                // is left here ended without a repair and without that proof.
                strategyAttempts.add(attempt(strategyName, "INCONCLUSIVE",
                        "The search ended without a repair, but forward verification could not settle every"
                                + " candidate, so this does not establish that no repair of this kind exists."));
            }
        }

        // Strategies return only suggestions forward verification accepted.
        boolean fixable = !suggestions.isEmpty();
        // Read the outcome from the recorded attempts, not from the clock: the deadline can pass after
        // the last strategy finished, and that must not relabel a complete search as partial.
        boolean timedOut = strategyAttempts.stream().anyMatch(attempt ->
                "TIMED_OUT".equals(attempt.getStatus()) || "SKIPPED_TIMEOUT".equals(attempt.getStatus()));
        StringBuilder summaryBuilder = new StringBuilder();
        if (fixable) {
            summaryBuilder.append("Found ").append(suggestions.size())
                    .append(" fix suggestion(s) for ").append(faultRules.size()).append(" fault rule(s).");
        } else {
            summaryBuilder.append(faultRules.size()).append(" fault rule(s) identified. ");
            summaryBuilder.append(timedOut
                    ? "The search was incomplete because the automatic-fix time limit expired."
                    : "No requested strategy produced a verified suggestion.");
        }

        // Keep the persistence id out of ordinary feedback; it does not help the user repair the model.
        if (violatedSpecIndex < 0) {
            summaryBuilder.append(" (the violated specification could not be matched to the saved verification "
                    + "snapshot; parameter and condition strategies were skipped)");
        }

        // A selection is used when it matches an eligible target, regardless of whether that
        // constrained search eventually produces a verified suggestion.
        List<PreferredRangeSelection> unusedSelections = unusedPreferredRangeSelections(
                preferredRanges, ctx.matchedPreferredRangeTargetIdsSnapshot());
        if (!unusedSelections.isEmpty()) {
            summaryBuilder.append(" Note: some preferred range selections matched no eligible parameter target.");
        }

        List<String> warnings = ctx.diagnosticsSnapshot();
        if (strategyAttempts.stream().anyMatch(attempt -> "SKIPPED_TIMEOUT".equals(attempt.getStatus()))) {
            warnings = new ArrayList<>(warnings);
            warnings.add("The automatic-fix time limit expired; some requested strategies were not attempted.");
        }

        return FixResultDto.builder()
                .traceId(traceId)
                .violatedSpecId(violatedSpecId)
                .faultRules(faultRules)
                .suggestions(suggestions)
                .strategyAttempts(strategyAttempts)
                .fixable(fixable)
                .sourceModelComplete(true)
                .summary(summaryBuilder.toString())
                .warnings(warnings)
                .parameterTargets(ctx.parameterTargetsSnapshot())
                .unusedPreferredRangeSelections(unusedSelections)
                .build();
    }

    /**
     * Project the caller's preferred-range selections that no eligible parameter target consumed.
     *
     * Shared by the strategy search and by every path that returns before searching. An early return
     * honours no selection at all, so passing an empty {@code matchedTargetIds} reports all of them —
     * previously those paths left the field at its empty default and the user's pinned threshold was
     * dropped with nothing said, which is exactly what this field exists to prevent.
     */
    public static List<PreferredRangeSelection> unusedPreferredRangeSelections(
            Map<String, PreferredRange> preferredRanges, Set<String> matchedTargetIds) {
        if (preferredRanges == null || preferredRanges.isEmpty()) return List.of();
        Set<String> matched = matchedTargetIds != null ? matchedTargetIds : Set.of();
        List<PreferredRangeSelection> unused = new ArrayList<>();
        for (Map.Entry<String, PreferredRange> entry : preferredRanges.entrySet()) {
            String targetId = entry.getKey();
            PreferredRange range = entry.getValue();
            if (!matched.contains(targetId)
                    && PreferredRangeSelection.isValidTargetId(targetId) && range != null) {
                unused.add(PreferredRangeSelection.builder()
                        .targetId(targetId)
                        .lower(range.getLower())
                        .upper(range.getUpper())
                        .build());
            }
        }
        return unused;
    }

    /** How many of {@code strategyNames} will run, which is how many share the remaining time. */
    private int runnableStrategies(List<String> strategyNames, int violatedSpecIndex) {
        return (int) strategyNames.stream()
                .map(strategyRegistry::get)
                .filter(strategy -> strategy != null && (violatedSpecIndex >= 0 || !strategy.requiresViolatedSpec()))
                .count();
    }

    private static boolean settled(FixContext.StrategyNoResult noResult) {
        return noResult != null && SETTLED_NO_RESULTS.contains(noResult.status());
    }

    private static FixStrategyAttemptDto attempt(String strategy, String status, String reason) {
        return FixStrategyAttemptDto.builder()
                .strategy(strategy)
                .status(status)
                .reason(reason)
                .build();
    }

    private int resolveSpecIndex(String violatedSpecId, List<SpecificationDto> specs) {
        if (violatedSpecId == null || specs == null) return -1;
        for (int i = 0; i < specs.size(); i++) {
            SpecificationDto spec = specs.get(i);
            if (spec != null && violatedSpecId.equals(spec.getId())) {
                return i;
            }
        }
        log.warn("Could not resolve violatedSpecId '{}' to spec index", violatedSpecId);
        return -1;
    }
}
