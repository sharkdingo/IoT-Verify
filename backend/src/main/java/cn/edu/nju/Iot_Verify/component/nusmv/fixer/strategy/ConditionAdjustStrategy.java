package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterExtractor;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.NusmvResult;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceReferenceResolver;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.dto.fix.ConditionAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * §5.2 Triggering Condition Adjustment (Salus paper).
 *
 * <p>For each sub-condition in expanded rules, introduces a boolean FROZENVAR lambda.
 * Existing conditions: lambda=TRUE → keep, lambda=FALSE → remove.
 * Candidate conditions (from violated spec): lambda=TRUE → add, lambda=FALSE → ignore.
 * NuSMV solves for which conditions to remove/add.</p>
 *
 * <p>The search continues after the first verified repair and lists every minimal set of touched
 * conditions it verifies (see {@link FixAlternatives}).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConditionAdjustStrategy implements FixStrategy {

    private static final String NAME = "condition";
    private static final Pattern KEY_PATTERN = Pattern.compile("r(\\d+)_c(\\d+)");

    private final SmvGenerator smvGenerator;
    private final NusmvExecutor nusmvExecutor;
    private final FixConfig fixConfig;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public StrategyOutcome tryFix(FixContext ctx) {
        List<FaultRuleDto> faultRules = ctx.getFaultRules();
        List<RuleDto> allRules = ctx.getAllRules();
        int violatedSpecIndex = ctx.getViolatedSpecIndex();
        int maxAttempts = ctx.getMaxAttempts() > 0 ? ctx.getMaxAttempts() : 20;

        Set<Integer> repairIndices = new LinkedHashSet<>(ctx.getRepairRuleIndices());
        if (repairIndices.isEmpty()) return StrategyOutcome.none();

        // §5.2: candidate conditions on every rule that can influence the violated property
        SpecificationDto violatedSpec = ctx.getSpecs().get(violatedSpecIndex);
        Map<String, DeviceSmvData> deviceSmvMap = ctx.getDeviceSmvMap();

        PreparedData prep = prepareAugmentedRules(allRules, repairIndices, violatedSpec, deviceSmvMap);
        if (prep == null) return StrategyOutcome.none();

        ctx.initializeStrategySearch(NAME, maxAttempts);
        log.info("ConditionAdjustStrategy: created {} lambda variable(s)", prep.conditionLambdas.size());
        CounterexampleLemma lemma = CounterexampleLemma.plan(
                prep.augmentedRules, prep.conditionLambdas, prep.candidateConditionValues);

        // Solve loop. A checked assignment is excluded exactly; a rejected one also excludes every
        // assignment its violating path cannot tell apart from it (see CounterexampleLemma), and a
        // listed repair excludes every assignment that flips at least the same conditions (see
        // supersetExclusion).
        FixAlternatives alternatives = new FixAlternatives(ctx);
        List<String> exclusionInvars = new ArrayList<>();
        List<Set<String>> prioritizedConfigurations = maxAttempts > 1
                ? prioritizedConfigurations(prep, deviceSmvMap, faultRules)
                : List.of();
        int prioritizedBudget = maxAttempts > 1 ? Math.max(1, maxAttempts / 2) : 0;
        int prioritizedIndex = 0;
        boolean exhausted = false;
        // Set when a run failed without excluding anything (other than a refused permit), so the next
        // iteration would rebuild the identical model and can only repeat the failure.
        boolean failedWithoutProgress = false;

        for (int attempt = 0; attempt < maxAttempts && !alternatives.full(); attempt++) {
            if (ctx.isExpired()) {
                log.info("ConditionAdjustStrategy: deadline expired at attempt {}/{}", attempt + 1, maxAttempts);
                break;
            }
            if (failedWithoutProgress) {
                // A failed pinned configuration is left to the joint search, which still covers it; a
                // failed joint run ends the search.
                if (prioritizedIndex >= prioritizedConfigurations.size()) break;
                prioritizedIndex++;
                failedWithoutProgress = false;
            }
            ctx.addStrategyAttempts(NAME, 1);
            if (attempt >= prioritizedBudget
                    && prioritizedIndex < prioritizedConfigurations.size()) {
                log.info("ConditionAdjust: prioritized probes used their {}-attempt budget; continuing with joint search",
                        prioritizedBudget);
                prioritizedIndex = prioritizedConfigurations.size();
            }
            // A pinned configuration that contains a listed repair can only yield supersets of it.
            while (prioritizedIndex < prioritizedConfigurations.size()
                    && alternatives.covers(prioritizedConfigurations.get(prioritizedIndex))) {
                prioritizedIndex++;
            }
            List<String> attemptInvars = new ArrayList<>(exclusionInvars);
            if (prioritizedIndex < prioritizedConfigurations.size()) {
                attemptInvars.add(lambdaConfiguration(prep, prioritizedConfigurations.get(prioritizedIndex)));
            }
            ParameterizationConfig config = ParameterizationConfig.builder()
                    .conditionLambdas(prep.conditionLambdas)
                    .candidateConditionValues(prep.candidateConditionValues)
                    .negatedSpecIndex(violatedSpecIndex)
                    .exclusionInvars(attemptInvars.isEmpty() ? null : attemptInvars)
                    .build();

            File smvFile = null;
            try {
                // Pass augmentedRules (with candidate conditions appended) to NuSMV
                SmvGenerator.GenerateResult genResult =
                        FixStrategyUtils.generateParameterizedResolved(
                                smvGenerator, ctx, prep.augmentedRules, config);
                if (genResult == null) {
                    log.warn("ConditionAdjust attempt {}: SMV generation returned null", attempt + 1);
                    ctx.recordStrategyGenerationFailure(NAME,
                            "The condition candidate model could not preserve the original attack scenario.");
                    return alternatives.outcome(false);
                }
                if (!FixStrategyUtils.candidateModelComplete(genResult, ctx, NAME)) {
                    return alternatives.outcome(false);
                }
                smvFile = genResult.smvFile();

                NusmvResult result = FixStrategyUtils.executeWithinDeadline(nusmvExecutor, smvFile, ctx);
                if (!result.isSuccess()) {
                    log.warn("ConditionAdjust attempt {}: NuSMV execution failed: {}",
                            attempt + 1, result.getErrorMessage());
                    FixStrategyUtils.recordSolverFailure(ctx, NAME, result.isBusy()
                            ? "NuSMV was busy while searching for condition changes."
                            : "NuSMV failed while searching for condition changes.");
                    failedWithoutProgress = !result.isBusy();
                    continue;
                }

                List<SpecCheckResult> specResults = result.getSpecResults();
                if (specResults == null || specResults.isEmpty()) {
                    log.warn("ConditionAdjust attempt {}: empty spec results", attempt + 1);
                    FixStrategyUtils.recordSolverFailure(ctx, NAME,
                            "NuSMV returned no usable specification result during condition search.");
                    failedWithoutProgress = true;
                    continue;
                }

                SpecCheckResult negatedResult = specResults.get(0);
                if (negatedResult.isPassed()) {
                    if (prioritizedIndex < prioritizedConfigurations.size()) {
                        log.info("ConditionAdjust: no candidate for prioritized configuration {}/{}",
                                prioritizedIndex + 1, prioritizedConfigurations.size());
                        prioritizedIndex++;
                        continue;
                    }
                    log.info("ConditionAdjust: ¬ρ is universally true, no further condition fix possible");
                    exhausted = true;
                    break;
                }

                // Extract lambda values
                String rawOutput = result.getOutput();
                Map<String, String> extractedValues = ParameterExtractor.extract(rawOutput, prep.frozenVarNames);
                if (extractedValues.size() < prep.frozenVarNames.size()) {
                    // An incomplete assignment can be neither interpreted nor excluded (excluding the
                    // part that was read would rule out valid completions of it), so the next attempt
                    // would rebuild the identical model and read the same output.
                    log.warn("ConditionAdjust attempt {}: extracted {}/{} condition parameters",
                            attempt + 1, extractedValues.size(), prep.frozenVarNames.size());
                    FixStrategyUtils.recordSolverFailure(ctx, NAME,
                            "NuSMV output did not contain the complete condition assignment required by the search.");
                    failedWithoutProgress = true;
                    continue;
                }

                // Interpret results: determine removals and additions
                InterpretedResult ir = interpretResults(prep, extractedValues, allRules, deviceSmvMap);

                if (ir.changes.isEmpty()) {
                    log.info("ConditionAdjust attempt {}: no changes, adding exclusion", attempt + 1);
                    addExclusion(exclusionInvars, prep, extractedValues);
                    continue;
                }

                // Apply changes to a fresh copy of allRules
                List<RuleDto> modifiedRules = applyChanges(allRules, ir.conditionsToRemove, ir.conditionsToAdd);

                // Reject solutions that strip a rule down to zero trigger conditions. NuSMV treats an
                // empty-condition rule as fail-closed (never fires), so such a solution can verify — but
                // RuleDto forbids empty conditions and the apply layer rejects it, so returning it would
                // hand the user a "verified" suggestion that can never be applied. Exclude and keep searching.
                if (emptiesAnyRule(modifiedRules, ir.conditionsToRemove.keySet())) {
                    log.info("ConditionAdjust attempt {}: solution empties a rule's conditions; excluding", attempt + 1);
                    // It avoids the counterexample but is not a persistable rule, the same ground on
                    // which forward verification rejects a candidate.
                    alternatives.notAccepted(ir.changes, FixStrategyUtils.Verification.rejected());
                    addExclusion(exclusionInvars, prep, extractedValues);
                    continue;
                }

                CounterexampleLemma.Attempt lemmaAttempt = lemma != null ? lemma.attempt(modifiedRules) : null;
                FixStrategyUtils.Verification verification = FixStrategyUtils.forwardVerify(
                        smvGenerator, nusmvExecutor, ctx, modifiedRules, NAME,
                        lemmaAttempt != null ? lemmaAttempt.probes() : List.of());
                if (verification.isAccepted()) {
                    // Filter out action="keep" entries — they add no value for the user
                    List<ConditionAdjustment> actionableAdjustments = ir.adjustments.stream()
                            .filter(a -> !"keep".equals(a.getAction()))
                            .collect(Collectors.toList());
                    alternatives.accept(ir.changes, FixSuggestionDto.builder()
                            .strategy(NAME)
                            .description("Adjust conditions: " + buildDescription(ir.adjustments))
                            .conditionAdjustments(actionableAdjustments)
                            .build(), verification);
                    exclusionInvars.add(supersetExclusion(prep, ir.changes));
                    log.info("ConditionAdjust attempt {}: listed a verified repair, excluding its supersets",
                            attempt + 1);
                    continue;
                }

                alternatives.notAccepted(ir.changes, verification);
                addExclusion(exclusionInvars, prep, extractedValues);
                String learned = lemmaAttempt != null ? lemmaAttempt.learn(verification, ctx.getSpecs()) : null;
                if (CounterexampleLemma.REFUTES_ALL.equals(learned)) {
                    log.info("ConditionAdjust attempt {}: every remaining assignment shares the rejected candidate's"
                            + " violation", attempt + 1);
                    exhausted = true;
                    break;
                }
                if (learned != null) exclusionInvars.add(learned);
                log.info("ConditionAdjust attempt {}: forward verification did not accept the candidate, excluding it{}",
                        attempt + 1, learned != null ? " and the assignments its violation also refutes" : "");

            } catch (Exception e) {
                log.warn("ConditionAdjust attempt {}: failed: {}", attempt + 1, e.getMessage(), e);
                if (e instanceof cn.edu.nju.Iot_Verify.exception.SmvGenerationException) {
                    String reason = "Condition candidate generation failed: " + e.getMessage();
                    ctx.addDiagnostic(reason);
                    ctx.recordStrategyGenerationFailure(NAME, reason);
                    return alternatives.outcome(false);
                }
                FixStrategyUtils.preserveInterrupt(e);
                FixStrategyUtils.recordSolverFailure(ctx, NAME,
                        "Condition search encountered an execution error: " + e.getMessage());
                failedWithoutProgress = true;
            } finally {
                FixStrategyUtils.cleanupTempDir(smvFile);
            }
        }

        StrategyOutcome outcome = alternatives.outcome(exhausted);
        if (outcome.suggestions().isEmpty()) {
            if (outcome.alternativesComplete()) {
                // The solver proved that no unchecked assignment is left, which supersedes a
                // transient failure on an earlier attempt.
                ctx.clearStrategySolverFailure(NAME);
                if (alternatives.anyRejected()) {
                    ctx.recordStrategyNoResult(NAME, "ALL_CANDIDATES_REJECTED",
                            "Condition changes that prevent this counterexample exist, but each one either leaves a"
                                    + " rule without a trigger condition or was rejected by forward verification on"
                                    + " the complete model.");
                } else {
                    ctx.recordStrategyNoResult(NAME, "NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE",
                            "No combination of removing existing trigger conditions and adding candidate ones"
                                    + " prevents this counterexample.");
                }
            } else if (!exhausted && !ctx.isExpired()) {
                FixContext.StrategySearchProgress progress = ctx.strategySearchProgress(NAME);
                if (progress != null && progress.attemptsUsed() >= progress.attemptLimit()) {
                    ctx.recordStrategyNoResult(NAME, "SEARCH_BUDGET_EXHAUSTED",
                            "Condition search reached its candidate limit before it could establish that"
                                    + " no repair exists.");
                }
            }
        }
        return outcome;
    }

    // -------- Preparation: deep copy + inject candidates + build lambdas --------

    private PreparedData prepareAugmentedRules(
            List<RuleDto> allRules,
            Set<Integer> expandedIndices,
            SpecificationDto violatedSpec,
            Map<String, DeviceSmvData> deviceSmvMap) {

        List<RuleDto> augmentedRules = FixStrategyUtils.deepCopyRules(allRules);
        Map<Integer, Integer> originalCondCounts = new HashMap<>();

        Map<Integer, CommandEffects> effectsByRule = new HashMap<>();
        for (int ruleIdx : expandedIndices) {
            RuleDto augRule = augmentedRules.get(ruleIdx);
            originalCondCounts.put(ruleIdx, augRule.getConditions() != null
                    ? augRule.getConditions().size() : 0);

            CommandEffects effects = CommandEffects.of(allRules.get(ruleIdx), allRules, deviceSmvMap);
            effectsByRule.put(ruleIdx, effects);
            List<RuleDto.Condition> candidates = FixStrategyUtils.extractCandidateConditions(
                    violatedSpec, allRules.get(ruleIdx), effects, deviceSmvMap, fixConfig.getMaxCandidatesPerRule());
            if (!candidates.isEmpty()) {
                if (augRule.getConditions() == null) {
                    augRule.setConditions(new ArrayList<>());
                }
                augRule.getConditions().addAll(candidates);
            }
        }

        // Build conditionLambdas covering all conditions (original + candidate) in expanded rules
        Map<String, String> conditionLambdas = new LinkedHashMap<>();
        Map<String, ParameterizationConfig.ConditionValueInfo> candidateConditionValues =
                new LinkedHashMap<>();
        List<String> frozenVarNames = new ArrayList<>();

        for (int ruleIdx : expandedIndices) {
            RuleDto augRule = augmentedRules.get(ruleIdx);
            if (augRule.getConditions() == null || augRule.getConditions().isEmpty()) continue;
            for (int condIdx = 0; condIdx < augRule.getConditions().size(); condIdx++) {
                if (augRule.getConditions().get(condIdx) == null) continue;
                String key = "r" + ruleIdx + "_c" + condIdx;
                String lambdaName = "lambda_" + key;
                conditionLambdas.put(key, lambdaName);
                frozenVarNames.add(lambdaName);

                int originalCount = originalCondCounts.getOrDefault(ruleIdx, 0);
                if (condIdx >= originalCount) {
                    String valueName = "condition_value_" + key;
                    ParameterizationConfig.ConditionValueInfo valueInfo =
                            FixStrategyUtils.candidateConditionValueInfo(
                                    augRule.getConditions().get(condIdx), allRules.get(ruleIdx),
                                    effectsByRule.get(ruleIdx), deviceSmvMap, valueName);
                    if (valueInfo != null) {
                        candidateConditionValues.put(key, valueInfo);
                        frozenVarNames.add(valueName);
                    }
                }
            }
        }

        if (conditionLambdas.isEmpty()) {
            log.info("ConditionAdjustStrategy: no conditions found in expanded rules");
            return null;
        }

        return new PreparedData(augmentedRules, originalCondCounts, conditionLambdas,
                candidateConditionValues, frozenVarNames);
    }

    // -------- Result interpretation --------

    private InterpretedResult interpretResults(
            PreparedData prep,
            Map<String, String> extractedValues,
            List<RuleDto> allRules,
            Map<String, DeviceSmvData> deviceSmvMap) {

        List<ConditionAdjustment> adjustments = new ArrayList<>();
        Map<Integer, List<Integer>> conditionsToRemove = new LinkedHashMap<>();
        Map<Integer, List<RuleDto.Condition>> conditionsToAdd = new LinkedHashMap<>();
        Set<String> changes = new LinkedHashSet<>();

        for (Map.Entry<String, String> entry : prep.conditionLambdas.entrySet()) {
            String key = entry.getKey();
            String lambdaName = entry.getValue();
            String lambdaValue = extractedValues.get(lambdaName);

            Matcher m = KEY_PATTERN.matcher(key);
            if (!m.matches()) {
                log.warn("ConditionAdjust: unexpected key format '{}'", key);
                continue;
            }
            int ruleIdx = Integer.parseInt(m.group(1));
            int condIdx = Integer.parseInt(m.group(2));

            int originalCount = prep.originalCondCounts.getOrDefault(ruleIdx, 0);
            RuleDto.Condition cond = prep.augmentedRules.get(ruleIdx).getConditions().get(condIdx);
            ParameterizationConfig.ConditionValueInfo valueInfo = prep.candidateConditionValues.get(key);
            if (valueInfo != null) {
                String selectedValue = extractedValues.get(valueInfo.getFrozenVarName());
                if (selectedValue != null) {
                    cond = copyConditionWithValue(cond, selectedValue);
                }
            }
            String ruleDescription = describeRule(allRules, ruleIdx);
            String deviceLabel = displayDeviceLabel(cond == null ? null : cond.getDeviceName(), deviceSmvMap);

            // [C4] fail-safe: missing lambda → keep for existing, ignore for candidate
            if (lambdaValue == null) {
                log.warn("lambda '{}' not found in NuSMV output, defaulting to {}", lambdaName,
                        condIdx < originalCount ? "keep" : "ignore");
            }

            if (condIdx < originalCount) {
                // Existing condition
                String action = "FALSE".equalsIgnoreCase(lambdaValue) ? "remove" : "keep";
                if ("remove".equals(action)) {
                    conditionsToRemove.computeIfAbsent(ruleIdx, k -> new ArrayList<>()).add(condIdx);
                    changes.add(key);
                }
                adjustments.add(ConditionAdjustment.builder()
                        .ruleIndex(ruleIdx).conditionIndex(condIdx)
                        .action(action)
                        .attribute(cond != null ? cond.getAttribute() : "unknown")
                        .targetType(cond != null ? cond.getTargetType() : null)
                        .ruleDescription(ruleDescription)
                        .deviceLabel(deviceLabel)
                        .deviceName(cond != null ? cond.getDeviceName() : null)
                        .relation(cond != null ? cond.getRelation() : null)
                        .value(cond != null ? cond.getValue() : null)
                        .description(action + " " + conditionSummary(deviceLabel, cond)
                                + " from '" + ruleDescription + "'")
                        .build());
            } else {
                // Candidate condition
                if ("TRUE".equalsIgnoreCase(lambdaValue)) {
                    conditionsToAdd.computeIfAbsent(ruleIdx, k -> new ArrayList<>()).add(cond);
                    changes.add(key);
                    adjustments.add(ConditionAdjustment.builder()
                            .ruleIndex(ruleIdx).conditionIndex(condIdx)
                            .action("add")
                            .attribute(cond.getAttribute())
                            .targetType(cond.getTargetType())
                            .ruleDescription(ruleDescription)
                            .deviceLabel(deviceLabel)
                            .deviceName(cond.getDeviceName())
                            .relation(cond.getRelation())
                            .value(cond.getValue())
                            .description("add " + conditionSummary(deviceLabel, cond)
                                    + " to '" + ruleDescription + "'")
                            .build());
                }
                // lambda=FALSE for candidate → ignore, not recorded
            }
        }

        return new InterpretedResult(adjustments, conditionsToRemove, conditionsToAdd, changes);
    }

    // -------- Apply changes --------

    private static List<RuleDto> applyChanges(
            List<RuleDto> allRules,
            Map<Integer, List<Integer>> conditionsToRemove,
            Map<Integer, List<RuleDto.Condition>> conditionsToAdd) {

        List<RuleDto> modifiedRules = FixStrategyUtils.deepCopyRules(allRules);

        // 1) Remove (in reverse index order to preserve indices)
        for (Map.Entry<Integer, List<Integer>> e : conditionsToRemove.entrySet()) {
            List<Integer> indices = new ArrayList<>(e.getValue());
            indices.sort(Collections.reverseOrder());
            List<RuleDto.Condition> conds = modifiedRules.get(e.getKey()).getConditions();
            for (int idx : indices) {
                if (idx < conds.size()) conds.remove(idx);
            }
        }
        // 2) Add
        for (Map.Entry<Integer, List<RuleDto.Condition>> e : conditionsToAdd.entrySet()) {
            List<RuleDto.Condition> conds = modifiedRules.get(e.getKey()).getConditions();
            if (conds == null) {
                conds = new ArrayList<>();
                modifiedRules.get(e.getKey()).setConditions(conds);
            }
            conds.addAll(e.getValue());
        }

        return modifiedRules;
    }

    /**
     * True if applying the changes leaves any affected rule with zero trigger conditions. Only rules
     * that had conditions removed can be emptied (adds never reduce the count), so we check those.
     */
    private static boolean emptiesAnyRule(List<RuleDto> modifiedRules, Set<Integer> touchedRuleIndices) {
        for (int ruleIdx : touchedRuleIndices) {
            if (ruleIdx < 0 || ruleIdx >= modifiedRules.size()) continue;
            List<RuleDto.Condition> conds = modifiedRules.get(ruleIdx).getConditions();
            if (conds == null || conds.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // -------- Description builder --------

    private static String buildDescription(List<ConditionAdjustment> adjustments) {
        return adjustments.stream()
                .filter(a -> "remove".equals(a.getAction()) || "add".equals(a.getAction()))
                .map(ConditionAdjustStrategy::sentenceCase)
                .collect(Collectors.joining("; "));
    }

    private static String sentenceCase(ConditionAdjustment adjustment) {
        String description = adjustment.getDescription();
        if (description == null || description.isBlank()) {
            return adjustment.getAction();
        }
        return Character.toUpperCase(description.charAt(0)) + description.substring(1);
    }

    private static String describeRule(List<RuleDto> rules, int ruleIndex) {
        if (rules != null && ruleIndex >= 0 && ruleIndex < rules.size()) {
            RuleDto rule = rules.get(ruleIndex);
            if (rule != null && rule.getRuleString() != null && !rule.getRuleString().isBlank()) {
                // Unquoted: this is the structured ruleDescription field, and the UI supplies its own quotes.
                return rule.getRuleString().trim();
            }
        }
        return "affected rule";
    }

    private static String displayDeviceLabel(String deviceRef, Map<String, DeviceSmvData> deviceSmvMap) {
        if (deviceRef != null && deviceSmvMap != null) {
            try {
                DeviceSmvData smv = DeviceReferenceResolver.resolve(deviceRef, deviceSmvMap);
                if (smv != null && smv.getDeviceLabel() != null && !smv.getDeviceLabel().isBlank()) {
                    return smv.getDeviceLabel();
                }
            } catch (RuntimeException ignored) {
                // The adjustment remains structurally useful; do not expose the unresolved model id.
            }
        }
        return "unknown device";
    }

    private static String conditionSummary(String deviceLabel, RuleDto.Condition condition) {
        if (condition == null) return deviceLabel + ".unknown condition";
        StringBuilder summary = new StringBuilder(deviceLabel)
                .append('.').append(condition.getAttribute() == null ? "unknown" : condition.getAttribute());
        if (condition.getRelation() != null && !condition.getRelation().isBlank()) {
            summary.append(' ').append(condition.getRelation());
        }
        if (condition.getValue() != null && !condition.getValue().isBlank()) {
            summary.append(' ').append(condition.getValue());
        }
        return summary.toString();
    }

    private static RuleDto.Condition copyConditionWithValue(
            RuleDto.Condition condition, String selectedValue) {
        if (condition == null) return null;
        return RuleDto.Condition.builder()
                .deviceName(condition.getDeviceName())
                .attribute(condition.getAttribute())
                .targetType(condition.getTargetType())
                .relation(condition.getRelation())
                .value(selectedValue)
                .build();
    }

    // -------- Helpers --------

    private static void addExclusion(
            List<String> exclusionInvars,
            PreparedData prep,
            Map<String, String> extractedValues) {
        String exclusion = semanticExclusion(
                prep.conditionLambdas, prep.candidateConditionValues, extractedValues);
        if (exclusion != null) exclusionInvars.add(exclusion);
    }

    static String semanticExclusion(
            Map<String, String> conditionLambdas,
            Map<String, ParameterizationConfig.ConditionValueInfo> candidateConditionValues,
            Map<String, String> extractedValues) {
        List<String> eqParts = new ArrayList<>();
        for (Map.Entry<String, String> lambda : conditionLambdas.entrySet()) {
            String lambdaValue = extractedValues.get(lambda.getValue());
            if (lambdaValue == null) continue;
            eqParts.add(lambda.getValue() + "=" + lambdaValue);
            ParameterizationConfig.ConditionValueInfo valueInfo =
                    candidateConditionValues.get(lambda.getKey());
            if (valueInfo != null && "TRUE".equalsIgnoreCase(lambdaValue)) {
                String selectedValue = extractedValues.get(valueInfo.getFrozenVarName());
                if (selectedValue != null) {
                    eqParts.add(valueInfo.getFrozenVarName() + "=" + selectedValue);
                }
            }
        }
        return eqParts.isEmpty() ? null : "!(" + String.join(" & ", eqParts) + ")";
    }

    /**
     * Excludes every assignment that makes at least the changes of a listed repair. Such an
     * assignment edits more of the board than the listed repair without being needed for it; an
     * assignment that makes only some of them, or others, stays reachable.
     */
    private static String supersetExclusion(PreparedData prep, Set<String> changes) {
        List<String> literals = new ArrayList<>();
        for (String key : changes) {
            // An existing condition is removed by lambda=FALSE, a candidate is added by lambda=TRUE.
            literals.add(prep.conditionLambdas.get(key) + "=" + (isCandidateKey(prep, key) ? "TRUE" : "FALSE"));
        }
        return "!(" + String.join(" & ", literals) + ")";
    }

    /** Change sets to pin before the unrestricted joint search, most likely repairs first. */
    private static List<Set<String>> prioritizedConfigurations(
            PreparedData prep,
            Map<String, DeviceSmvData> deviceSmvMap,
            List<FaultRuleDto> faultRules) {
        List<Set<String>> configurations = new ArrayList<>();
        Set<Integer> localizedRuleIndices = faultRules.stream()
                .filter(Objects::nonNull)
                .map(FaultRuleDto::getRuleIndex)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // A single added guard can suppress the observed behavior only when it changes a
        // localized rule, so try those one-edit candidates first.
        for (String key : prep.conditionLambdas.keySet()) {
            if (isCandidateKey(prep, key)
                    && localizedRuleIndices.contains(ruleIndexForKey(key))) {
                configurations.add(Set.of(key));
            }
        }
        // If no one-guard addition works, try removing one existing condition at a time.
        for (String key : prep.conditionLambdas.keySet()) {
            if (!isCandidateKey(prep, key) && originalConditionCount(prep, key) > 1) {
                configurations.add(Set.of(key));
            }
        }
        // Redundant rules issuing the same unsafe behavior can require the same guard on each
        // rule. Constrain those common-shape additions before falling back to the exponential
        // unrestricted lambda search.
        Map<String, LinkedHashSet<String>> candidateKeysByShape = new LinkedHashMap<>();
        for (String key : prep.conditionLambdas.keySet()) {
            if (!isCandidateKey(prep, key)) continue;
            RuleDto.Condition condition = conditionForKey(prep, key);
            String shape = FixStrategyUtils.conditionShapeFingerprint(condition, deviceSmvMap);
            int ruleIndex = ruleIndexForKey(key);
            String command = ruleIndex >= 0 && ruleIndex < prep.augmentedRules.size()
                    ? commandKey(prep.augmentedRules.get(ruleIndex), deviceSmvMap)
                    : null;
            if (shape != null && command != null) {
                candidateKeysByShape.computeIfAbsent(command + "\u0000" + shape,
                        ignored -> new LinkedHashSet<>()).add(key);
            }
        }
        for (LinkedHashSet<String> keys : candidateKeysByShape.values()) {
            long distinctRules = keys.stream()
                    .map(ConditionAdjustStrategy::ruleIndexForKey)
                    .filter(index -> index >= 0)
                    .distinct()
                    .count();
            if (distinctRules > 1) {
                configurations.add(Set.copyOf(keys));
            }
        }
        // Keep expanded-rule additions reachable for unusual priority interactions, but place
        // them after the coordinated candidates that address the common backup-rule case.
        for (String key : prep.conditionLambdas.keySet()) {
            if (isCandidateKey(prep, key)
                    && !localizedRuleIndices.contains(ruleIndexForKey(key))) {
                configurations.add(Set.of(key));
            }
        }
        return configurations;
    }

    private static RuleDto.Condition conditionForKey(PreparedData prep, String key) {
        Matcher matcher = KEY_PATTERN.matcher(key);
        if (!matcher.matches()) return null;
        int ruleIndex = Integer.parseInt(matcher.group(1));
        int conditionIndex = Integer.parseInt(matcher.group(2));
        if (ruleIndex < 0 || ruleIndex >= prep.augmentedRules.size()) return null;
        List<RuleDto.Condition> conditions = prep.augmentedRules.get(ruleIndex).getConditions();
        if (conditions == null || conditionIndex < 0 || conditionIndex >= conditions.size()) return null;
        return conditions.get(conditionIndex);
    }

    private static int ruleIndexForKey(String key) {
        Matcher matcher = KEY_PATTERN.matcher(key);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String commandKey(
            RuleDto rule, Map<String, DeviceSmvData> deviceSmvMap) {
        return FixStrategyUtils.commandFingerprint(rule, deviceSmvMap);
    }

    private static boolean isCandidateKey(PreparedData prep, String key) {
        Matcher matcher = KEY_PATTERN.matcher(key);
        if (!matcher.matches()) return false;
        int ruleIndex = Integer.parseInt(matcher.group(1));
        int conditionIndex = Integer.parseInt(matcher.group(2));
        return conditionIndex >= prep.originalCondCounts.getOrDefault(ruleIndex, 0);
    }

    private static int originalConditionCount(PreparedData prep, String key) {
        Matcher matcher = KEY_PATTERN.matcher(key);
        if (!matcher.matches()) return 0;
        return prep.originalCondCounts.getOrDefault(Integer.parseInt(matcher.group(1)), 0);
    }

    private static String lambdaConfiguration(PreparedData prep, Set<String> changedKeys) {
        List<String> assignments = new ArrayList<>();
        for (Map.Entry<String, String> lambda : prep.conditionLambdas.entrySet()) {
            boolean baseline = !isCandidateKey(prep, lambda.getKey());
            boolean selected = changedKeys.contains(lambda.getKey()) ? !baseline : baseline;
            assignments.add(lambda.getValue() + "=" + (selected ? "TRUE" : "FALSE"));
        }
        return "(" + String.join(" & ", assignments) + ")";
    }

    // -------- Internal records --------

    private record PreparedData(
            List<RuleDto> augmentedRules,
            Map<Integer, Integer> originalCondCounts,
            Map<String, String> conditionLambdas,
            Map<String, ParameterizationConfig.ConditionValueInfo> candidateConditionValues,
            List<String> frozenVarNames
    ) {}

    /**
     * @param changes the lambda keys whose condition is removed or added. A repair is identified by
     *                which conditions it touches, not by the value an added guard compares against:
     *                the same guard with another threshold is a variant of that repair, not an
     *                alternative to it.
     */
    private record InterpretedResult(
            List<ConditionAdjustment> adjustments,
            Map<Integer, List<Integer>> conditionsToRemove,
            Map<Integer, List<RuleDto.Condition>> conditionsToAdd,
            Set<String> changes
    ) {}
}
