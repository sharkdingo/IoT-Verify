package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.SpecResultAlignment;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.NusmvResult;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.CounterexampleInitialStateConstraints;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.GuardProbe;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.AttackScenarioSurfaceValidator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.AttackSurface;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceReferenceResolver;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.util.RuleSemanticSignature;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import cn.edu.nju.Iot_Verify.util.InterruptPreservation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared utilities for fix strategy implementations.
 */
@Slf4j
public final class FixStrategyUtils {

    private FixStrategyUtils() {}

    /**
     * How forward verification judged one candidate. Search strategies exclude a candidate that was not
     * accepted either way; the distinction matters for what they may claim afterwards. A rejection is a
     * verdict on the candidate (it breaks a specification the original rules satisfied, or it is not a
     * board the user could save), while an inconclusive check proves nothing — so a strategy that left a
     * candidate inconclusive cannot claim it listed every repair.
     */
    public enum Verdict { ACCEPTED, REJECTED, INCONCLUSIVE }

    /**
     * The path on which a rejected candidate violated a specification: the target it still fails, or a
     * specification it newly breaks.
     *
     * @param counterexample NuSMV's trace text, from the first state line
     */
    public record Witness(String specId, String counterexample) {
        public Witness {
            Objects.requireNonNull(specId, "specId");
            Objects.requireNonNull(counterexample, "counterexample");
        }
    }

    /**
     * @param preexistingViolationIds for an accepted candidate, the specifications it still violates —
     *                                every one of them already violated by the original rules
     * @param witness                 for a candidate rejected on a specification verdict, the violating
     *                                path when NuSMV printed one; {@code null} otherwise
     */
    public record Verification(Verdict verdict, List<String> preexistingViolationIds, Witness witness) {
        public Verification {
            Objects.requireNonNull(verdict, "verdict");
            preexistingViolationIds = List.copyOf(preexistingViolationIds);
            if (witness != null && verdict != Verdict.REJECTED) {
                throw new IllegalArgumentException("only a rejection has a violating path");
            }
        }

        static Verification accepted(List<String> preexistingViolationIds) {
            return new Verification(Verdict.ACCEPTED, preexistingViolationIds, null);
        }

        static Verification rejected() {
            return rejected(null);
        }

        static Verification rejected(Witness witness) {
            return new Verification(Verdict.REJECTED, List.of(), witness);
        }

        static Verification inconclusive() {
            return new Verification(Verdict.INCONCLUSIVE, List.of(), null);
        }

        public boolean isAccepted() {
            return verdict == Verdict.ACCEPTED;
        }
    }

    /**
     * Forward-verify: regenerate the complete SMV model with the modified rules and accept it when the
     * target specification passes without breaking any other (see {@link #judgeFailingCandidate}).
     */
    public static Verification forwardVerify(SmvGenerator smvGenerator, NusmvExecutor nusmvExecutor,
                                             FixContext ctx, List<RuleDto> modifiedRules,
                                             String strategyName) {
        return forwardVerify(smvGenerator, nusmvExecutor, ctx, modifiedRules, strategyName, List.of());
    }

    /**
     * As above, with {@code guardProbes} added to the checked model so a rejection's {@link Witness}
     * reports their values. They are {@code DEFINE}s and cannot change the verdict.
     */
    public static Verification forwardVerify(SmvGenerator smvGenerator, NusmvExecutor nusmvExecutor,
                                             FixContext ctx, List<RuleDto> modifiedRules,
                                             String strategyName, List<GuardProbe> guardProbes) {
        if (ctx.isExpired()) return Verification.inconclusive();
        if (!candidateRulesPersistable(modifiedRules)) {
            ctx.addDiagnostic("A candidate was rejected because it would create an identical automation rule.");
            log.info("Forward verification rejected a candidate containing identical automation rules");
            return Verification.rejected();
        }
        File smvFile = null;
        try {
            SmvGenerator.GenerateResult genResult = generateResolved(
                    smvGenerator, ctx, modifiedRules, SmvGenerator.GeneratePurpose.VERIFICATION, guardProbes);
            // generateResolved already said why: the candidate would change the attack scenario.
            if (genResult == null) return Verification.rejected();
            smvFile = genResult.smvFile();

            if (genResult.disabledRuleCount() > 0 || genResult.skippedSpecCount() > 0) {
                String diagnostic = "A candidate could not be checked because forward verification generated an incomplete model ("
                        + genResult.disabledRuleCount() + " rule(s) disabled, "
                        + genResult.skippedSpecCount() + " specification(s) skipped).";
                ctx.addDiagnostic(diagnostic);
                ctx.recordStrategyGenerationFailure(strategyName, diagnostic);
                log.warn("Forward verification could not check a candidate, incomplete generated model: disabledRules={}, skippedSpecs={}",
                        genResult.disabledRuleCount(), genResult.skippedSpecCount());
                // Nothing was checked, so nothing is known about the candidate itself.
                return Verification.inconclusive();
            }

            NusmvResult result = executeCheck(nusmvExecutor, smvFile, ctx);
            if (!result.isSuccess()) {
                log.warn("Forward verification: NuSMV execution failed: {}", result.getErrorMessage());
                recordSolverFailure(ctx, strategyName,
                        "NuSMV could not complete forward verification for a candidate.");
                return Verification.inconclusive();
            }

            List<SpecCheckResult> specResults = result.getSpecResults();
            int expectedSpecCount = ctx.getSpecs() != null ? ctx.getSpecs().size() : 0;
            boolean resultCountComplete = specResults != null
                    && !specResults.isEmpty()
                    && (expectedSpecCount == 0 || specResults.size() == expectedSpecCount);
            boolean emittedCountComplete = genResult.emittedSpecs() == null
                    || genResult.emittedSpecs().isEmpty()
                    || expectedSpecCount == 0
                    || genResult.emittedSpecs().size() == expectedSpecCount;
            if (!resultCountComplete || !emittedCountComplete) {
                recordSolverFailure(ctx, strategyName,
                        "NuSMV forward verification did not return one reliable result for every specification.");
                log.warn("Forward verification could not judge a candidate, incomplete result set: expected={}, emitted={}, parsed={}",
                        expectedSpecCount,
                        genResult.emittedSpecs() != null ? genResult.emittedSpecs().size() : 0,
                        specResults != null ? specResults.size() : 0);
                return Verification.inconclusive();
            }

            if (specResults.stream().allMatch(SpecCheckResult::isPassed)) {
                log.info("Forward verification result: all specifications pass");
                return Verification.accepted(List.of());
            }
            return judgeFailingCandidate(smvGenerator, nusmvExecutor, ctx, specResults, genResult.emittedSpecs());
        } catch (Exception e) {
            log.warn("Forward verification failed: {}", e.getMessage(), e);
            if (smvFile == null) {
                String reason = "Candidate model generation failed during forward verification: " + e.getMessage();
                ctx.addDiagnostic(reason);
                ctx.recordStrategyGenerationFailure(strategyName, reason);
            } else {
                recordSolverFailure(ctx, strategyName,
                        "NuSMV forward verification encountered an error: " + e.getMessage());
            }
            // Every strategy reaches NuSMV through here, so this is where a cancellation delivered as
            // an interrupt must survive the broad catch — otherwise ctx.isExpired() stops reporting it
            // and the search keeps taking solver permits for an already-answered request.
            preserveInterrupt(e);
            return Verification.inconclusive();
        } finally {
            cleanupTempDir(smvFile);
        }
    }

    /**
     * A repair is judged against the violation it was asked to repair, not against every flaw on the
     * board: a candidate is accepted when the target specification passes and every specification it
     * still violates was already violated by the original rules. Requiring all specifications to pass
     * made a counterexample unrepairable whenever an unrelated specification also failed, since no edit
     * inside this counterexample's repair scope can reach it.
     *
     * <p>Fails closed. A candidate is rejected only on evidence — the target still fails, or it breaks a
     * specification the original rules satisfied. When per-specification attribution is not certain, the
     * target cannot be identified, or the original rules' verdict could not be obtained, the rule cannot
     * be evaluated and the candidate is inconclusive, never accepted.
     */
    private static Verification judgeFailingCandidate(
            SmvGenerator smvGenerator, NusmvExecutor nusmvExecutor, FixContext ctx,
            List<SpecCheckResult> specResults, List<SmvGenerationContext.EmittedSpec> emittedSpecs)
            throws InterruptedException {
        Map<String, SpecCheckResult> results = resultsBySpecId(specResults, emittedSpecs);
        String targetSpecId = targetSpecId(ctx);
        SpecCheckResult target = results == null || targetSpecId == null ? null : results.get(targetSpecId);
        if (target == null) {
            log.info("Forward verification result: inconclusive, the target specification's verdict is not attributable");
            return Verification.inconclusive();
        }
        if (!target.isPassed()) {
            log.info("Forward verification result: rejected, the target specification still fails");
            return Verification.rejected(witness(targetSpecId, target));
        }
        List<String> stillViolated = results.entrySet().stream()
                .filter(entry -> !entry.getValue().isPassed())
                .map(Map.Entry::getKey)
                .toList();
        FixContext.BaselineVerdict baseline = baselineVerdict(smvGenerator, nusmvExecutor, ctx);
        if (!baseline.available()) {
            log.info("Forward verification result: inconclusive, candidate violates {} and the original rules' verdict is unknown",
                    stillViolated);
            return Verification.inconclusive();
        }
        String newlyViolated = stillViolated.stream()
                .filter(specId -> !baseline.violatedSpecIds().contains(specId))
                .findFirst()
                .orElse(null);
        if (newlyViolated != null) {
            log.info("Forward verification result: rejected, candidate violates {} (originally violated: {})",
                    stillViolated, baseline.violatedSpecIds());
            return Verification.rejected(witness(newlyViolated, results.get(newlyViolated)));
        }
        log.info("Forward verification result: target passes, pre-existing violations remain {}", stillViolated);
        return Verification.accepted(stillViolated);
    }

    private static Witness witness(String specId, SpecCheckResult result) {
        String trace = result.getCounterexample();
        return trace == null || trace.isBlank() ? null : new Witness(specId, trace);
    }

    /** The result per specification id, or {@code null} when any verdict's attribution is uncertain. */
    private static Map<String, SpecCheckResult> resultsBySpecId(
            List<SpecCheckResult> specResults, List<SmvGenerationContext.EmittedSpec> emittedSpecs) {
        if (emittedSpecs == null || emittedSpecs.size() != specResults.size()) return null;
        SpecResultAlignment.Alignment alignment = SpecResultAlignment.align(specResults, emittedSpecs);
        if (alignment.backFilled() > 0) return null;
        Map<String, SpecCheckResult> results = new LinkedHashMap<>();
        for (int i = 0; i < emittedSpecs.size(); i++) {
            String specId = emittedSpecs.get(i) != null ? emittedSpecs.get(i).specId() : null;
            SpecCheckResult result = alignment.aligned().get(i);
            // "unknown" is the generator's placeholder for a specification without an id; it names no
            // specification, so a verdict filed under it cannot be compared with the original rules'.
            if (result == null || specId == null || specId.isBlank() || "unknown".equals(specId)
                    || results.putIfAbsent(specId, result) != null) {
                return null;
            }
        }
        return results;
    }

    private static String targetSpecId(FixContext ctx) {
        List<SpecificationDto> specs = ctx.getSpecs();
        int index = ctx.getViolatedSpecIndex();
        if (specs == null || index < 0 || index >= specs.size() || specs.get(index) == null) return null;
        String id = specs.get(index).getId();
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * The original rules' verdict on the same forward-verification model, memoized on the context. A
     * failure is memoized too: it would recur for every candidate and each attempt costs a full NuSMV run.
     * Two outcomes are not failures and are not memoized: a capacity refusal, which is retried, and a run
     * cut off by the deadline or a cancellation, which a later strategy's window may still complete.
     */
    private static FixContext.BaselineVerdict baselineVerdict(
            SmvGenerator smvGenerator, NusmvExecutor nusmvExecutor, FixContext ctx) throws InterruptedException {
        FixContext.BaselineVerdict memo = ctx.baselineVerdict();
        if (memo != null) return memo;
        FixContext.BaselineVerdict verdict = computeBaselineVerdict(smvGenerator, nusmvExecutor, ctx);
        if (!verdict.available() && ctx.isExpired()) {
            // Cut off by the deadline or a cancellation: not a property of the rules, and the strategy's
            // TIMED_OUT status already says the search was incomplete.
            return verdict;
        }
        if (!verdict.available()) {
            ctx.addDiagnostic("The original rules could not be re-checked, so a candidate was accepted only "
                    + "if it satisfied every specification.");
        }
        ctx.recordBaselineVerdict(verdict);
        return ctx.baselineVerdict();
    }

    private static FixContext.BaselineVerdict computeBaselineVerdict(
            SmvGenerator smvGenerator, NusmvExecutor nusmvExecutor, FixContext ctx) throws InterruptedException {
        File smvFile = null;
        try {
            SmvGenerator.GenerateResult genResult = generateResolved(
                    smvGenerator, ctx, ctx.getAllRules(), SmvGenerator.GeneratePurpose.VERIFICATION, List.of());
            if (genResult == null) return FixContext.BaselineVerdict.unavailable();
            smvFile = genResult.smvFile();
            if (genResult.disabledRuleCount() > 0 || genResult.skippedSpecCount() > 0) {
                return FixContext.BaselineVerdict.unavailable();
            }
            NusmvResult result = executeCheck(nusmvExecutor, smvFile, ctx);
            if (!result.isSuccess() || result.getSpecResults() == null) {
                return FixContext.BaselineVerdict.unavailable();
            }
            Map<String, SpecCheckResult> results = resultsBySpecId(result.getSpecResults(), genResult.emittedSpecs());
            if (results == null) return FixContext.BaselineVerdict.unavailable();
            Set<String> violated = new LinkedHashSet<>();
            results.forEach((specId, specResult) -> {
                if (!specResult.isPassed()) violated.add(specId);
            });
            return new FixContext.BaselineVerdict(Collections.unmodifiableSet(violated));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Baseline verification of the original rules failed: {}", e.getMessage(), e);
            preserveInterrupt(e);
            return FixContext.BaselineVerdict.unavailable();
        } finally {
            cleanupTempDir(smvFile);
        }
    }

    /**
     * Re-arms the interrupt flag if this exception was (or wrapped) an interruption.
     *
     * <p>Delegates to {@link InterruptPreservation}, which the chat tool loop shares — this stays as a
     * package-local alias only because the strategies call it on nearly every catch.
     */
    static void preserveInterrupt(Exception e) {
        InterruptPreservation.preserveInterrupt(e);
    }

    /**
     * A NuSMV run killed by the fix deadline or by cancellation did not fail; it was cut off. Recording
     * it as a solver failure showed the user "NuSMV failed while searching" next to a timeout, two
     * contradictory causes for one event. The caller's {@code TIMED_OUT} status already says it.
     */
    static void recordSolverFailure(FixContext ctx, String strategyName, String reason) {
        if (ctx.isExpired()) return;
        ctx.addDiagnostic(reason);
        ctx.recordStrategySolverFailure(strategyName, reason);
    }

    static boolean candidateRulesPersistable(List<RuleDto> rules) {
        List<RuleDto> safeRules = rules == null ? List.of() : rules;
        for (int left = 0; left < safeRules.size(); left++) {
            for (int right = 0; right < left; right++) {
                if (RuleSemanticSignature.exactlyMatches(safeRules.get(left), safeRules.get(right))) {
                    return false;
                }
            }
        }
        return true;
    }

    public static SmvGenerator.GenerateResult generateResolved(
            SmvGenerator smvGenerator,
            FixContext ctx,
            List<RuleDto> rules,
            SmvGenerator.GeneratePurpose purpose,
            List<GuardProbe> guardProbes) throws java.io.IOException {
        AttackScenarioDto scenario = ctx.resolvedAttackScenario();
        if (!preservesExactAttackSelection(scenario, rules, ctx.getDeviceSmvMap())) {
            ctx.addDiagnostic("A candidate was rejected because it would remove an explicitly selected automation-link attack point or device attack point and change the original attack scenario.");
            return null;
        }
        return smvGenerator.generateWithResolvedDeviceModel(
                ctx.getUserId(), ctx.getDevices(), ctx.getEnvironmentVariables(), rules, ctx.getSpecs(),
                scenario, ctx.isEnablePrivacy(), purpose, tempContext(ctx), ctx.getDeviceSmvMap(), guardProbes);
    }

    public static SmvGenerator.GenerateResult generateParameterizedResolved(
            SmvGenerator smvGenerator,
            FixContext ctx,
            List<RuleDto> rules,
            ParameterizationConfig config) throws java.io.IOException {
        AttackScenarioDto scenario = ctx.resolvedAttackScenario();
        if (!preservesExactAttackSelection(scenario, rules, ctx.getDeviceSmvMap())) {
            ctx.addDiagnostic("A candidate was rejected because it would remove an explicitly selected automation-link attack point or device attack point and change the original attack scenario.");
            return null;
        }
        if (ctx.isRequireCounterexampleReplay() && ctx.getCounterexampleInitialState() == null) {
            throw SmvGenerationException.smvGenerationError(
                    "Cannot reproduce the counterexample initial state: the persisted trace has no first state");
        }
        config.setInitialStateConstraints(CounterexampleInitialStateConstraints.build(
                ctx.getCounterexampleInitialState(), rules, ctx.getDeviceSmvMap(),
                scenario, ctx.isEnablePrivacy()));
        return smvGenerator.generateParameterizedWithResolvedDeviceModel(
                ctx.getUserId(), ctx.getDevices(), ctx.getEnvironmentVariables(), rules, ctx.getSpecs(),
                scenario, ctx.isEnablePrivacy(), config, tempContext(ctx), ctx.getDeviceSmvMap());
    }

    static boolean preservesExactAttackSelection(
            AttackScenarioDto scenario,
            List<RuleDto> rules,
            Map<String, DeviceSmvData> deviceSmvMap) {
        AttackSurface surface = AttackSurface.analyze(rules, deviceSmvMap);
        return AttackScenarioSurfaceValidator
                .firstExactSelectionViolation(scenario, surface, rules)
                .isEmpty();
    }

    static boolean candidateModelComplete(SmvGenerator.GenerateResult result, FixContext ctx,
                                          String strategyName) {
        if (result == null) return false;
        if (result.disabledRuleCount() == 0 && result.skippedSpecCount() == 0) {
            return true;
        }
        String reason = "Candidate model generation disabled "
                + result.disabledRuleCount() + " rule(s) and skipped "
                + result.skippedSpecCount() + " specification(s).";
        ctx.addDiagnostic(reason);
        ctx.recordStrategyGenerationFailure(strategyName, reason);
        return false;
    }

    /**
     * Deep-copy rules including conditions and command, so modifications don't affect the originals.
     */
    public static List<RuleDto> deepCopyRules(List<RuleDto> rules) {
        List<RuleDto> copy = new ArrayList<>();
        for (RuleDto rule : rules) {
            List<RuleDto.Condition> condCopy = new ArrayList<>();
            if (rule.getConditions() != null) {
                for (RuleDto.Condition c : rule.getConditions()) {
                    condCopy.add(RuleDto.Condition.builder()
                            .deviceName(c.getDeviceName())
                            .attribute(c.getAttribute())
                            .targetType(c.getTargetType())
                            .relation(c.getRelation())
                            .value(c.getValue())
                            .build());
                }
            }
            RuleDto.Command cmdCopy = null;
            if (rule.getCommand() != null) {
                RuleDto.Command cmd = rule.getCommand();
                cmdCopy = RuleDto.Command.builder()
                        .deviceName(cmd.getDeviceName())
                        .action(cmd.getAction())
                        .contentDevice(cmd.getContentDevice())
                        .content(cmd.getContent())
                        .build();
            }
            copy.add(RuleDto.builder()
                    .id(rule.getId())
                    .userId(rule.getUserId())
                    .conditions(condCopy)
                    .command(cmdCopy)
                    .ruleString(rule.getRuleString())
                    .build());
        }
        return copy;
    }

    /**
     * Clean up the temp directory containing the SMV file.
     */
    public static void cleanupTempDir(File smvFile) {
        if (smvFile == null) return;
        try {
            File parentDir = smvFile.getParentFile();
            if (parentDir != null && parentDir.exists()) {
                File[] files = parentDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.delete()) {
                            log.debug("Failed to delete temp file: {}", f);
                        }
                    }
                }
                if (!parentDir.delete()) {
                    log.debug("Failed to delete temp dir: {}", parentDir);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to clean up temp dir: {}", e.getMessage());
        }
    }

    /**
     * Every repair model runs through {@link NusmvExecutor#executeRepairSearch}, deadline or not: its
     * {@code -df} is what keeps an unconstrained search variable from stalling the reachability pass,
     * and that need does not depend on whether the context carries a deadline.
     */
    static NusmvResult executeWithinDeadline(NusmvExecutor nusmvExecutor, File smvFile,
                                              FixContext ctx) throws InterruptedException {
        long remainingMs = ctx.remainingMillis();
        if (remainingMs <= 0) {
            // No diagnostic: the strategy's TIMED_OUT status is the one statement of this outcome.
            return NusmvResult.error("Automatic-fix deadline expired before NuSMV execution");
        }
        return nusmvExecutor.executeRepairSearch(smvFile, remainingMs);
    }

    /**
     * A verification run, retried while NuSMV refuses it for capacity. A refusal says nothing about the
     * model, so it must not make a candidate inconclusive or leave the original rules' verdict unknown
     * for the rest of the request. The deadline bounds the retries: under it, a permit wait that cannot
     * fit the remaining budget comes back as an error, not as a refusal.
     */
    private static NusmvResult executeCheck(NusmvExecutor nusmvExecutor, File smvFile, FixContext ctx)
            throws InterruptedException {
        NusmvResult result = executeWithinDeadline(nusmvExecutor, smvFile, ctx);
        while (result.isBusy() && !ctx.isExpired()) {
            result = executeWithinDeadline(nusmvExecutor, smvFile, ctx);
        }
        return result;
    }

    static boolean hasEnvironmentPool(FixContext ctx) {
        return ctx != null && ctx.getEnvironmentVariables() != null && !ctx.getEnvironmentVariables().isEmpty();
    }

    static SmvGenerator.TempModelContext tempContext(FixContext ctx) {
        return SmvGenerator.TempModelContext.fixTrace(ctx != null ? ctx.getTraceId() : null);
    }

    /**
     * Strict: resolve device name → varName, null on failure or ambiguity.
     * Used by validateCandidateCondition / conditionFingerprint where
     * precision matters (false-positive = broken SMV model).
     */
    static String resolveVarNameSafe(
            String deviceName, Map<String, DeviceSmvData> deviceSmvMap) {
        try {
            DeviceSmvData smv = DeviceReferenceResolver.resolve(deviceName, deviceSmvMap);
            return smv != null ? smv.getVarName() : null;
        } catch (SmvGenerationException e) {
            log.warn("resolveVarNameSafe: ambiguous device '{}', skipping: {}", deviceName, e.getMessage());
            return null;
        }
    }

    /** Full command identity used when coordinating rules that perform the same action. */
    static String commandFingerprint(
            RuleDto rule, Map<String, DeviceSmvData> deviceSmvMap) {
        if (rule == null || rule.getCommand() == null) return null;
        RuleDto.Command command = rule.getCommand();
        if (command.getDeviceName() == null || command.getAction() == null) return null;
        String target = resolveVarNameSafe(command.getDeviceName(), deviceSmvMap);
        if (target == null) return null;
        String contentDevice = "";
        if (command.getContentDevice() != null && !command.getContentDevice().isBlank()) {
            contentDevice = resolveVarNameSafe(command.getContentDevice(), deviceSmvMap);
            if (contentDevice == null) return null;
        }
        return String.join("\u0000",
                target,
                command.getAction().trim(),
                contentDevice,
                command.getContent() != null ? command.getContent().trim() : "");
    }

    // ======================== E1: Candidate condition extraction ========================

    /**
     * Extract candidate conditions from violated spec not already in the rule.
     * Dispatches by targetType; validates compilability; drops conditions {@code effects} establishes;
     * dedup by 4-tuple; truncates at max.
     */
    static List<RuleDto.Condition> extractCandidateConditions(
            SpecificationDto violatedSpec,
            RuleDto rule,
            CommandEffects effects,
            Map<String, DeviceSmvData> deviceSmvMap,
            int maxCandidatesPerRule) {

        if (violatedSpec == null) return Collections.emptyList();
        if (maxCandidatesPerRule <= 0) return Collections.emptyList();

        // 1. Build existing-condition fingerprint set for dedup
        Set<String> existingFingerprints = new HashSet<>();
        Set<String> existingShapes = new HashSet<>();
        if (rule.getConditions() != null) {
            for (RuleDto.Condition c : rule.getConditions()) {
                String fp = conditionFingerprint(c, deviceSmvMap);
                if (fp != null) existingFingerprints.add(fp);
                String shape = conditionShapeFingerprint(c, deviceSmvMap);
                if (shape != null) existingShapes.add(shape);
            }
        }

        // 2. Collect spec conditions (a + if + then)
        List<SpecConditionDto> allSpecConds = new ArrayList<>();
        if (violatedSpec.getAConditions() != null) allSpecConds.addAll(violatedSpec.getAConditions());
        if (violatedSpec.getIfConditions() != null) allSpecConds.addAll(violatedSpec.getIfConditions());
        if (violatedSpec.getThenConditions() != null) allSpecConds.addAll(violatedSpec.getThenConditions());

        // 3. Map, validate, dedup, truncate
        List<RuleDto.Condition> candidates = new ArrayList<>();
        Set<String> candidateFingerprints = new HashSet<>();
        Set<String> candidateShapes = new HashSet<>();
        Set<String> freeValueCandidateShapes = new HashSet<>();

        for (SpecConditionDto sc : allSpecConds) {
            // Trimmed and Locale.ROOT-pinned to match the generator, which resolves the attribute this
            // candidate carries by equals() against manifest names. A padded key would produce a
            // suggestion that can never resolve.
            if (sc.getTargetType() == null) continue;
            String targetType = sc.getTargetType().trim().toLowerCase(Locale.ROOT);
            String specKey = sc.getKey() == null ? null : sc.getKey().trim();

            if (!"state".equals(targetType) && !"mode".equals(targetType)
                    && !"variable".equals(targetType) && !"api".equals(targetType)) continue;

            String deviceRef = DeviceReferenceResolver.resolvableReference(
                    sc.getDeviceId(), deviceSmvMap);

            RuleDto.Condition candidate;
            if ("api".equals(targetType)) {
                String relation = SmvRelationUtils.normalizeRelation(sc.getRelation());
                if (!"=".equals(relation) || !"TRUE".equalsIgnoreCase(sc.getValue())) {
                    continue;
                }
                candidate = RuleDto.Condition.builder()
                        .deviceName(deviceRef)
                        .attribute(specKey)
                        .targetType(targetType)
                        .build();
            } else if ("state".equals(targetType)) {
                candidate = RuleDto.Condition.builder()
                        .deviceName(deviceRef)
                        .attribute("state")
                        .targetType(targetType)
                        .relation(sc.getRelation())
                        .value(sc.getValue())
                        .build();
            } else if ("mode".equals(targetType)) {
                candidate = RuleDto.Condition.builder()
                        .deviceName(deviceRef)
                        .attribute(specKey)
                        .targetType(targetType)
                        .relation(sc.getRelation())
                        .value(sc.getValue())
                        .build();
            } else {
                candidate = RuleDto.Condition.builder()
                        .deviceName(deviceRef)
                        .attribute(specKey)
                        .targetType(targetType)
                        .relation(sc.getRelation())
                        .value(sc.getValue())
                        .build();
            }

            if (!validateCandidateCondition(candidate, deviceSmvMap)) {
                log.debug("extractCandidates: candidate from spec ({}/{}) failed validation, skipping",
                        deviceRef, sc.getKey());
                continue;
            }

            ParameterizationConfig.ConditionValueInfo freeValueInfo = candidateConditionValueInfo(
                    candidate, rule, effects, deviceSmvMap, "candidate_value_probe");
            if (freeValueInfo == null) {
                // Fixed-value postconditions can make the useful automation unreachable. A free-Y
                // candidate is handled differently: its domain has already had those values removed.
                if (effects.establishes(candidate)) {
                    log.debug("extractCandidates: skipped condition established by rule command {} or a rule it triggers",
                            rule.getCommand() != null ? rule.getCommand().getAction() : "<none>");
                    continue;
                }
                if (isCommandPrestateIncompatible(rule, candidate, deviceSmvMap)) {
                    log.debug("extractCandidates: skipped condition incompatible with the command API pre-state for {}",
                            rule.getCommand() != null ? rule.getCommand().getAction() : "<none>");
                    continue;
                }
            }

            String fp = conditionFingerprint(candidate, deviceSmvMap);
            String shape = conditionShapeFingerprint(candidate, deviceSmvMap);
            if (fp == null || shape == null) continue;
            if (freeValueInfo != null) {
                if (existingShapes.contains(shape) || candidateShapes.contains(shape)) continue;
                freeValueCandidateShapes.add(shape);
            } else if (existingFingerprints.contains(fp)
                    || candidateFingerprints.contains(fp)
                    || freeValueCandidateShapes.contains(shape)) {
                continue;
            }
            candidateFingerprints.add(fp);
            candidateShapes.add(shape);

            candidates.add(candidate);
            if (candidates.size() >= maxCandidatesPerRule) break;
        }
        return candidates;
    }

    /**
     * Resolve the finite value domain for a §5.2 candidate clause. The violated policy
     * supplies the clause shape; NuSMV chooses its value Y from this domain.
     */
    static ParameterizationConfig.ConditionValueInfo candidateConditionValueInfo(
            RuleDto.Condition candidate,
            Map<String, DeviceSmvData> deviceSmvMap,
            String frozenVarName) {
        return candidateConditionValueInfo(candidate, null, CommandEffects.NONE, deviceSmvMap, frozenVarName);
    }

    /**
     * As above, with the values {@code effects} establishes removed from the domain, as well as those
     * the pre-state of {@code rule}'s command rules out.
     */
    static ParameterizationConfig.ConditionValueInfo candidateConditionValueInfo(
            RuleDto.Condition candidate,
            RuleDto rule,
            CommandEffects effects,
            Map<String, DeviceSmvData> deviceSmvMap,
            String frozenVarName) {
        if (candidate == null || frozenVarName == null || frozenVarName.isBlank()) return null;

        DeviceSmvData smv;
        try {
            smv = DeviceReferenceResolver.resolve(candidate.getDeviceName(), deviceSmvMap);
        } catch (SmvGenerationException e) {
            return null;
        }
        if (smv == null || candidate.getTargetType() == null || candidate.getAttribute() == null) {
            return null;
        }

        String targetType = candidate.getTargetType().trim().toLowerCase(Locale.ROOT);
        String relation = SmvRelationUtils.normalizeRelation(candidate.getRelation());
        if ("mode".equals(targetType)) {
            if (!List.of("=", "!=", "in", "not in").contains(relation)) return null;
            List<String> values = smv.getModeStates() == null
                    ? null : smv.getModeStates().get(candidate.getAttribute());
            return filterCommandIncompatibleValues(candidate, rule, effects, deviceSmvMap,
                    discreteConditionValueInfo(frozenVarName, values));
        }
        if (!"variable".equals(targetType)) return null;

        DeviceManifest.InternalVariable variable = null;
        if (smv.getVariables() != null) {
            variable = smv.getVariables().stream()
                    .filter(Objects::nonNull)
                    .filter(value -> candidate.getAttribute().equals(value.getName()))
                    .findFirst()
                    .orElse(null);
        }
        if (variable == null && smv.getEnvVariables() != null) {
            variable = smv.getEnvVariables().get(candidate.getAttribute());
        }
        if (variable == null) return null;

        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            if (!List.of("=", "!=", "in", "not in").contains(relation)) return null;
            return filterCommandIncompatibleValues(candidate, rule, effects, deviceSmvMap,
                    discreteConditionValueInfo(frozenVarName, variable.getValues()));
        }
        if (variable.getLowerBound() == null || variable.getUpperBound() == null
                || variable.getLowerBound() >= variable.getUpperBound()) {
            return null;
        }
        return ParameterizationConfig.ConditionValueInfo.builder()
                .frozenVarName(frozenVarName)
                .lowerBound(variable.getLowerBound())
                .upperBound(variable.getUpperBound())
                .build();
    }

    private static ParameterizationConfig.ConditionValueInfo filterCommandIncompatibleValues(
            RuleDto.Condition candidate,
            RuleDto rule,
            CommandEffects effects,
            Map<String, DeviceSmvData> deviceSmvMap,
            ParameterizationConfig.ConditionValueInfo valueInfo) {
        if (valueInfo == null || rule == null || valueInfo.getValues() == null
                || valueInfo.getValues().isEmpty()) {
            return valueInfo;
        }
        List<String> allowedValues = valueInfo.getValues().stream()
                .filter(value -> {
                    RuleDto.Condition selected = copyConditionWithValue(candidate, value);
                    return !effects.establishes(selected)
                            && !isCommandPrestateIncompatible(rule, selected, deviceSmvMap);
                })
                .toList();
        if (allowedValues.isEmpty()) return null;
        return ParameterizationConfig.ConditionValueInfo.builder()
                .frozenVarName(valueInfo.getFrozenVarName())
                .values(new ArrayList<>(allowedValues))
                .build();
    }

    private static RuleDto.Condition copyConditionWithValue(
            RuleDto.Condition condition, String value) {
        return RuleDto.Condition.builder()
                .deviceName(condition.getDeviceName())
                .attribute(condition.getAttribute())
                .targetType(condition.getTargetType())
                .relation(condition.getRelation())
                .value(value)
                .build();
    }

    private static ParameterizationConfig.ConditionValueInfo discreteConditionValueInfo(
            String frozenVarName, List<String> rawValues) {
        if (rawValues == null) return null;
        List<String> values = rawValues.stream()
                .filter(Objects::nonNull)
                .map(value -> value.replace(" ", ""))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (values.size() < 2) return null;
        return ParameterizationConfig.ConditionValueInfo.builder()
                .frozenVarName(frozenVarName)
                .values(new ArrayList<>(values))
                .build();
    }

    /**
     * Whether calling {@code api} on {@code device} definitely makes {@code candidate}, a condition on
     * that same device, hold: the API's end state satisfies a positive state or mode condition, or its
     * event satisfies an API condition that asserts the event. Negative state and mode conditions (for
     * example {@code != taking_photo}) are never established; they are legitimate guards against
     * repeating a command. Internal variables are never established, since commands do not assign them.
     */
    static boolean apiOutcomeSatisfies(
            DeviceSmvData commandDevice,
            DeviceManifest.API api,
            RuleDto.Condition candidate) {
        if (commandDevice == null || api == null || candidate == null) return false;
        String targetType = candidate.getTargetType() == null
                ? ""
                : candidate.getTargetType().trim().toLowerCase(Locale.ROOT);
        String relation = SmvRelationUtils.normalizeRelation(candidate.getRelation());
        if ("api".equals(targetType)) {
            return Boolean.TRUE.equals(api.getSignal())
                    && candidate.getAttribute() != null
                    && candidate.getAttribute().trim().equals(api.getName())
                    && assertsSignal(relation, candidate.getValue());
        }
        if (api.getEndState() == null || api.getEndState().isBlank()) {
            return false;
        }
        // Only positive membership can be guaranteed by an action's result.
        if (!"=".equals(relation) && !"in".equals(relation)) {
            return false;
        }

        if ("mode".equals(targetType)) {
            int modeIndex = commandDevice.getModes() == null
                    ? -1
                    : commandDevice.getModes().indexOf(candidate.getAttribute());
            if (modeIndex < 0) return false;
            String endState = endStateForMode(commandDevice, api.getEndState(), modeIndex);
            return endState != null && positiveValueContains(relation, candidate.getValue(), endState);
        }
        if (!"state".equals(targetType) || !"state".equals(candidate.getAttribute())) {
            return false;
        }

        Map<String, String> endTuple = concreteEndState(commandDevice, api.getEndState());
        if (endTuple.isEmpty()) return false;
        List<String> candidateValues = "in".equals(relation)
                ? SmvRelationUtils.splitStateRuleValues(candidate.getValue(), commandDevice.getModes().size())
                : candidate.getValue() == null ? List.of() : List.of(candidate.getValue());
        return candidateValues.stream().anyMatch(value -> stateConditionMatchesTuple(
                commandDevice, value, endTuple));
    }

    /**
     * Whether an API condition with this normalized relation and value is exactly "the event
     * happened", as the generator renders it: a bare condition is {@code <api>_a=TRUE}.
     */
    private static boolean assertsSignal(String relation, String value) {
        if (relation == null) return true;
        List<String> values = SmvRelationUtils.splitRuleValues(value);
        if (values.isEmpty()) return false;
        return switch (relation) {
            case "=", "in" -> values.stream().allMatch("TRUE"::equalsIgnoreCase);
            case "!=", "not in" -> values.stream().allMatch("FALSE"::equalsIgnoreCase);
            default -> false;
        };
    }

    /**
     * Whether a candidate condition is provably false whenever the command API is executable.
     * Wildcard API start-state segments remain eligible: they leave at least one model state in
     * which the condition and command can both hold, so this guard never rejects on uncertainty.
     */
    static boolean isCommandPrestateIncompatible(
            RuleDto rule,
            RuleDto.Condition candidate,
            Map<String, DeviceSmvData> deviceSmvMap) {
        CommandApiContext command = commandApiContext(rule, candidate, deviceSmvMap);
        if (command == null || command.api().getStartState() == null
                || command.api().getStartState().isBlank()) {
            return false;
        }
        DeviceSmvData commandDevice = command.device();
        String relation = SmvRelationUtils.normalizeRelation(candidate.getRelation());
        String targetType = candidate.getTargetType() == null
                ? ""
                : candidate.getTargetType().trim().toLowerCase(Locale.ROOT);

        if ("mode".equals(targetType)) {
            int modeIndex = commandDevice.getModes() == null
                    ? -1
                    : commandDevice.getModes().indexOf(candidate.getAttribute());
            if (modeIndex < 0) return false;
            String startState = startStateForMode(commandDevice, command.api().getStartState(), modeIndex);
            if (startState == null) return false;
            List<String> values = "in".equals(relation) || "not in".equals(relation)
                    ? SmvRelationUtils.splitRuleValues(candidate.getValue())
                    : candidate.getValue() == null ? List.of() : List.of(candidate.getValue());
            boolean containsStart = values.stream()
                    .map(DeviceSmvDataFactory::cleanStateName)
                    .anyMatch(startState::equals);
            return switch (relation) {
                case "=" -> !containsStart;
                case "!=" -> containsStart;
                case "in" -> !containsStart;
                case "not in" -> containsStart;
                default -> false;
            };
        }
        if (!"state".equals(targetType) || !"state".equals(candidate.getAttribute())) {
            return false;
        }

        Map<String, String> startTuple = concreteStartState(commandDevice, command.api().getStartState());
        if (startTuple.isEmpty()) return false;
        List<String> values = "in".equals(relation) || "not in".equals(relation)
                ? SmvRelationUtils.splitStateRuleValues(candidate.getValue(), commandDevice.getModes().size())
                : candidate.getValue() == null ? List.of() : List.of(candidate.getValue());
        List<TupleMatch> matches = values.stream()
                .map(value -> compareCandidateToKnownState(commandDevice, value, startTuple))
                .toList();
        if (matches.isEmpty()) return false;
        return switch (relation) {
            case "=" -> matches.get(0) == TupleMatch.IMPOSSIBLE;
            case "!=" -> matches.get(0) == TupleMatch.DEFINITE;
            case "in" -> matches.stream().allMatch(match -> match == TupleMatch.IMPOSSIBLE);
            case "not in" -> matches.stream().anyMatch(match -> match == TupleMatch.DEFINITE);
            default -> false;
        };
    }

    private static CommandApiContext commandApiContext(
            RuleDto rule,
            RuleDto.Condition candidate,
            Map<String, DeviceSmvData> deviceSmvMap) {
        if (rule == null || candidate == null || rule.getCommand() == null
                || deviceSmvMap == null || deviceSmvMap.isEmpty()) {
            return null;
        }
        String commandVar = resolveVarNameSafe(rule.getCommand().getDeviceName(), deviceSmvMap);
        String candidateVar = resolveVarNameSafe(candidate.getDeviceName(), deviceSmvMap);
        if (commandVar == null || !Objects.equals(commandVar, candidateVar)) {
            return null;
        }
        DeviceSmvData commandDevice = deviceSmvMap.get(commandVar);
        DeviceManifest.API api = commandDevice == null
                ? null
                : DeviceSmvDataFactory.findApi(commandDevice.getManifest(), rule.getCommand().getAction());
        return api == null ? null : new CommandApiContext(commandDevice, api);
    }

    private record CommandApiContext(DeviceSmvData device, DeviceManifest.API api) {}

    private enum TupleMatch {
        DEFINITE,
        POSSIBLE,
        IMPOSSIBLE
    }

    private static boolean positiveValueContains(String relation, String rawValue, String endState) {
        if (rawValue == null || endState == null) return false;
        if ("=".equals(relation)) {
            return Objects.equals(DeviceSmvDataFactory.cleanStateName(rawValue), endState);
        }
        return SmvRelationUtils.splitStateRuleValues(rawValue, 1).stream()
                .map(DeviceSmvDataFactory::cleanStateName)
                .anyMatch(endState::equals);
    }

    private static Map<String, String> concreteEndState(DeviceSmvData smv, String rawEndState) {
        return concreteApiState(smv, rawEndState, false);
    }

    private static Map<String, String> concreteStartState(DeviceSmvData smv, String rawStartState) {
        return concreteApiState(smv, rawStartState, true);
    }

    private static Map<String, String> concreteApiState(
            DeviceSmvData smv,
            String rawState,
            boolean allowWildcard) {
        List<String> modes = smv.getModes();
        if (modes == null || modes.isEmpty() || rawState == null) return Map.of();
        String[] parts = rawState.split(";", -1);
        if (modes.size() == 1) {
            String state = allowWildcard
                    ? DeviceSmvDataFactory.cleanStartState(rawState)
                    : DeviceSmvDataFactory.cleanStateName(rawState);
            return state == null || state.isBlank() || "_".equals(state)
                    ? Map.of()
                    : Map.of(modes.get(0), state);
        }
        if (parts.length != modes.size()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < modes.size(); i++) {
            String state = allowWildcard
                    ? DeviceSmvDataFactory.cleanStartState(parts[i])
                    : DeviceSmvDataFactory.cleanStateName(parts[i]);
            if (state == null || state.isBlank() || "_".equals(state)) continue;
            result.put(modes.get(i), state);
        }
        return result;
    }

    private static String endStateForMode(DeviceSmvData smv, String rawEndState, int modeIndex) {
        Map<String, String> endTuple = concreteEndState(smv, rawEndState);
        if (endTuple.isEmpty() || smv.getModes() == null || modeIndex >= smv.getModes().size()) return null;
        return endTuple.get(smv.getModes().get(modeIndex));
    }

    private static String startStateForMode(DeviceSmvData smv, String rawStartState, int modeIndex) {
        Map<String, String> startTuple = concreteStartState(smv, rawStartState);
        if (startTuple.isEmpty() || smv.getModes() == null || modeIndex >= smv.getModes().size()) return null;
        return startTuple.get(smv.getModes().get(modeIndex));
    }

    private static boolean stateConditionMatchesTuple(
            DeviceSmvData smv,
            String rawCandidate,
            Map<String, String> endTuple) {
        return compareCandidateToKnownState(smv, rawCandidate, endTuple) == TupleMatch.DEFINITE;
    }

    private static TupleMatch compareCandidateToKnownState(
            DeviceSmvData smv,
            String rawCandidate,
            Map<String, String> knownState) {
        Map<String, String> candidateTuple = resolveCandidateStateTuple(smv, rawCandidate);
        if (candidateTuple.isEmpty()) return TupleMatch.POSSIBLE;
        boolean hasUnknownState = false;
        for (Map.Entry<String, String> entry : candidateTuple.entrySet()) {
            String actual = knownState.get(entry.getKey());
            if (actual == null) {
                hasUnknownState = true;
            } else if (!Objects.equals(actual, entry.getValue())) {
                return TupleMatch.IMPOSSIBLE;
            }
        }
        return hasUnknownState ? TupleMatch.POSSIBLE : TupleMatch.DEFINITE;
    }

    private static Map<String, String> resolveCandidateStateTuple(
            DeviceSmvData smv,
            String rawCandidate) {
        if (smv == null || smv.getModes() == null || smv.getModes().isEmpty()
                || rawCandidate == null || rawCandidate.isBlank()) {
            return Map.of();
        }
        List<String> modes = smv.getModes();
        String candidate = rawCandidate.trim();
        if (candidate.contains(";") && modes.size() > 1) {
            String[] parts = candidate.split(";", -1);
            if (parts.length != modes.size()) return Map.of();
            Map<String, String> tuple = new LinkedHashMap<>();
            for (int i = 0; i < modes.size(); i++) {
                String state = DeviceSmvDataFactory.cleanStateName(parts[i]);
                if (state == null || state.isBlank() || "_".equals(state)) continue;
                List<String> legalStates = smv.getModeStates().get(modes.get(i));
                if (legalStates == null || !legalStates.contains(state)) return Map.of();
                tuple.put(modes.get(i), state);
            }
            return tuple;
        }

        String state = DeviceSmvDataFactory.cleanStateName(candidate);
        if (state == null || state.isBlank()) return Map.of();
        if (modes.size() == 1) {
            return Map.of(modes.get(0), state);
        }
        List<String> matchedModes = modes.stream()
                .filter(mode -> smv.getModeStates().getOrDefault(mode, List.of()).contains(state))
                .toList();
        return matchedModes.size() == 1 ? Map.of(matchedModes.get(0), state) : Map.of();
    }

    /**
     * Validate that a candidate condition can be compiled by SmvMainModuleBuilder
     * without triggering fail-closed (FALSE for entire rule).
     */
    static boolean validateCandidateCondition(
            RuleDto.Condition candidate,
            Map<String, DeviceSmvData> deviceSmvMap) {
        // 1. Device resolvable and unambiguous
        DeviceSmvData smv;
        try {
            smv = DeviceReferenceResolver.resolve(candidate.getDeviceName(), deviceSmvMap);
        } catch (SmvGenerationException e) {
            return false;
        }
        if (smv == null) return false;

        // 2. Attribute resolvable
        String attr = candidate.getAttribute();
        if (attr == null || attr.isBlank()) return false;
        String targetType = candidate.getTargetType();
        if (targetType == null || targetType.isBlank()) return false;
        targetType = targetType.toLowerCase(Locale.ROOT);
        if (!"state".equals(targetType) && !"mode".equals(targetType)
                && !"variable".equals(targetType) && !"api".equals(targetType)) {
            return false;
        }

        if ("api".equals(targetType)) {
            boolean signalExists = smv.getManifest() != null && smv.getManifest().getApis() != null
                    && smv.getManifest().getApis().stream().anyMatch(api -> api != null
                    && Boolean.TRUE.equals(api.getSignal()) && attr.equals(api.getName()));
            if (!signalExists) return false;
            if (candidate.getRelation() == null && candidate.getValue() == null) return true;
        }

        // 3. Non-bare conditions require a value.
        if (candidate.getValue() == null || candidate.getValue().isBlank()) return false;

        // 4. Relation valid (type-specific)
        if (candidate.getRelation() == null) return false;
        String normalizedRel = SmvRelationUtils.normalizeRelation(candidate.getRelation());

        if ("state".equals(targetType)) {
            if (!"state".equals(attr)) return false;
            // State only allows = != in not_in (SmvMainModuleBuilder.buildRuleStateCondition:595-600)
            if (!"=".equals(normalizedRel) && !"!=".equals(normalizedRel)
                    && !"in".equals(normalizedRel) && !"not in".equals(normalizedRel)) {
                return false;
            }
            // Validate each state value exists in the device
            if (smv.getStates() == null || smv.getStates().isEmpty()) return false;
            if ("in".equals(normalizedRel) || "not in".equals(normalizedRel)) {
                // Mode-aware split: multi-mode devices use ; inside tuples (e.g. "cool;off")
                int modeCount = (smv.getModes() != null) ? smv.getModes().size() : 1;
                List<String> parts = SmvRelationUtils.splitStateRuleValues(candidate.getValue(), modeCount);
                if (parts.isEmpty()) return false;
                for (String part : parts) {
                    if (!isValidStateValue(smv, part)) return false;
                }
                return true;
            } else {
                return isValidStateValue(smv, candidate.getValue());
            }
        } else if ("mode".equals(targetType)) {
            if (!isModeAttribute(smv, attr)) return false;
            if (!"=".equals(normalizedRel) && !"!=".equals(normalizedRel)
                    && !"in".equals(normalizedRel) && !"not in".equals(normalizedRel)) {
                return false;
            }
            List<String> values = splitModeValues(candidate.getValue(), normalizedRel);
            if (values.isEmpty()) return false;
            if (("=".equals(normalizedRel) || "!=".equals(normalizedRel)) && values.size() != 1) {
                return false;
            }
            List<String> legalValues = smv.getModeStates() != null ? smv.getModeStates().get(attr) : null;
            if (legalValues == null || legalValues.isEmpty()) return false;
            for (String value : values) {
                String cleanValue = DeviceSmvDataFactory.cleanStateName(value);
                if (cleanValue == null || cleanValue.isBlank() || !legalValues.contains(cleanValue)) {
                    return false;
                }
            }
            return true;
        } else if ("variable".equals(targetType)) {
            // Non-state: general relation validation
            if (!SmvRelationUtils.isSupportedRelation(normalizedRel)) return false;

            boolean foundInVars = smv.getVariables() != null && smv.getVariables().stream()
                    .anyMatch(v -> attr.equals(v.getName()));
            if (foundInVars) return true;
            if (smv.getEnvVariables() != null) {
                return smv.getEnvVariables().containsKey(attr);
            }
            return false;
        } else {
            if (!List.of("=", "!=", "in", "not in").contains(normalizedRel)) return false;
            List<String> values = SmvRelationUtils.splitRuleValues(candidate.getValue());
            return !values.isEmpty() && values.stream()
                    .allMatch(value -> "TRUE".equalsIgnoreCase(value) || "FALSE".equalsIgnoreCase(value));
        }
    }

    /**
     * Check if a raw state value is valid for the given device.
     * Mirrors SmvMainModuleBuilder.resolveStateTupleCandidate() semantics:
     * <ul>
     *   <li>No modes: reject (buildRuleStateCondition:591 requires modes)</li>
     *   <li>Multi-mode tuple (contains ;): split by ; → check each segment per-mode;
     *       all-wildcard tuple rejected (resolveStateTupleCandidate:697)</li>
     *   <li>Single-mode: cleanStateName → check against mode's state list</li>
     *   <li>Multi-mode single value (no ;): cleanStateName → valid if exactly one mode contains it</li>
     * </ul>
     */
    private static boolean isValidStateValue(DeviceSmvData smv, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return false;
        String trimmed = rawValue.trim();
        List<String> modes = smv.getModes();
        Map<String, List<String>> modeStates = smv.getModeStates();

        // No modes → state conditions cannot be resolved (SmvMainModuleBuilder:591-593)
        if (modes == null || modes.isEmpty()) return false;

        // Multi-mode tuple resolution: "cool;high" → split by ; → check per-mode
        /*
         * The `modes.size() > 1` term differs from the three sibling implementations, which gate on `contains(";")`
         * alone — and that difference is unreachable, which is why it can stay.
         *
         * An audit flagged it as a live divergence: for a single-mode device given `"off;"` this takes the
         * single-value path while `NusmvRequestValidator:1306` takes the tuple path and rejects on segment count.
         * Measured against the running API, `"off;"` never gets that far — `/api/board/nodes` answers **422
         * "Illegal state value for device template: off;"** at the write boundary, so no persisted device can carry
         * it. Verified alongside `"off"` (200) to rule out the template itself being at fault.
         *
         * Left as-is deliberately: aligning four implementations to remove a difference no input can reach would
         * be change without a defect behind it.
         */
        if (trimmed.contains(";") && modes.size() > 1) {
            String[] segments = trimmed.split(";", -1);
            if (segments.length != modes.size()) return false;
            if (modeStates == null) return false;
            boolean anyNonWildcard = false;
            for (int i = 0; i < modes.size(); i++) {
                if (DeviceSmvDataFactory.isWildcardStateSegment(segments[i])) continue;
                String cleanSeg = DeviceSmvDataFactory.cleanStateName(segments[i]);
                if (cleanSeg == null || cleanSeg.isBlank()) continue; // wildcard
                anyNonWildcard = true;
                List<String> modeStateList = modeStates.get(modes.get(i));
                if (modeStateList == null || !modeStateList.contains(cleanSeg)) return false;
            }
            // All-wildcard tuple → empty map → null in generator (line 697)
            return anyNonWildcard;
        }

        // Single value
        String cleanValue = DeviceSmvDataFactory.cleanStateName(trimmed);
        if (cleanValue == null || cleanValue.isEmpty()) return false;

        // Single-mode: check against that mode's state list
        if (modes.size() == 1) {
            List<String> modeStateList = modeStates != null ? modeStates.get(modes.get(0)) : null;
            return modeStateList != null && modeStateList.contains(cleanValue);
        }

        // Multi-mode single value (no ;): valid if exactly one mode contains it
        // (SmvMainModuleBuilder:716-728 — ambiguous match → null)
        if (modeStates != null) {
            int matchCount = 0;
            for (String mode : modes) {
                List<String> modeStateList = modeStates.get(mode);
                if (modeStateList != null && modeStateList.contains(cleanValue)) {
                    matchCount++;
                }
            }
            return matchCount == 1;
        }

        return false;
    }

    private static boolean isModeAttribute(DeviceSmvData smv, String attr) {
        return smv != null && smv.getModes() != null && smv.getModes().contains(attr);
    }

    private static List<String> splitModeValues(String rawValue, String normalizedRel) {
        if (rawValue == null) {
            return List.of();
        }
        if ("in".equals(normalizedRel) || "not in".equals(normalizedRel)) {
            return Arrays.stream(rawValue.split("[,;|]"))
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .toList();
        }
        String trimmed = rawValue.trim();
        return trimmed.isEmpty() ? List.of() : List.of(trimmed);
    }

    /**
     * Fingerprint = "varName|targetType|attribute|normalizedRelation|normalizedValue".
     * [C2] normalizedValue uses SmvRelationUtils.cleanRuleValueByRelation()
     * to match SmvMainModuleBuilder's normalization semantics exactly.
     * Mode-aware: for state IN/NOT_IN on multi-mode devices, preserves ; within tuples.
     * Returns null if device resolution fails.
     */
    static String conditionFingerprint(
            RuleDto.Condition c, Map<String, DeviceSmvData> deviceSmvMap) {
        if (c == null || c.getDeviceName() == null) return null;
        String varName = resolveVarNameSafe(c.getDeviceName(), deviceSmvMap);
        if (varName == null) return null;
        String normRel = SmvRelationUtils.normalizeRelation(c.getRelation());
        // Resolve mode count for mode-aware value normalization
        int modeCount = 1;
        if ("state".equals(c.getAttribute())) {
            DeviceSmvData smv = deviceSmvMap.get(varName);
            if (smv == null) {
                // Fallback: try resolving from device name
                try {
                    smv = DeviceReferenceResolver.resolve(c.getDeviceName(), deviceSmvMap);
                } catch (SmvGenerationException ignored) { }
            }
            if (smv != null && smv.getModes() != null) {
                modeCount = smv.getModes().size();
            }
        }
        String normVal = SmvRelationUtils.cleanRuleValueByRelation(normRel, c.getValue(), modeCount);
        if (normVal == null) normVal = "";
        String attr = c.getAttribute() != null ? c.getAttribute() : "";
        String targetType = c.getTargetType() != null ? c.getTargetType().toLowerCase(Locale.ROOT) : "";
        return varName + "|" + targetType + "|" + attr + "|" + normRel + "|" + normVal;
    }

    /** Fingerprint for a candidate whose value is a free NuSMV parameter. */
    static String conditionShapeFingerprint(
            RuleDto.Condition c, Map<String, DeviceSmvData> deviceSmvMap) {
        if (c == null || c.getDeviceName() == null) return null;
        String varName = resolveVarNameSafe(c.getDeviceName(), deviceSmvMap);
        if (varName == null) return null;
        String normRel = SmvRelationUtils.normalizeRelation(c.getRelation());
        String attr = c.getAttribute() != null ? c.getAttribute() : "";
        String targetType = c.getTargetType() != null ? c.getTargetType().toLowerCase(Locale.ROOT) : "";
        return varName + "|" + targetType + "|" + attr + "|" + normRel;
    }
}
