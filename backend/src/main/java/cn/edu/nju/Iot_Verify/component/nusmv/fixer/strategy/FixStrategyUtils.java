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
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvNamespace;
import cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.verification.AntecedentReachability;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.util.RuleSemanticSignature;
import cn.edu.nju.Iot_Verify.util.SmvIntegerLiteral;
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
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared utilities for fix strategy implementations.
 */
@Slf4j
public final class FixStrategyUtils {

    private FixStrategyUtils() {}

    /** Direct property devices rank probes; the influence scope still includes indirect effects. */
    static Set<String> propertyDeviceVariables(
            SpecificationDto spec, Map<String, DeviceSmvData> deviceSmvMap) {
        if (spec == null) return Set.of();
        return Stream.of(spec.getAConditions(), spec.getIfConditions(), spec.getThenConditions())
                .filter(Objects::nonNull).flatMap(List::stream).filter(Objects::nonNull)
                .map(SpecConditionDto::getDeviceId)
                .map(reference -> resolveVarNameSafe(reference, deviceSmvMap))
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

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
        if (!candidateRulesPersistable(modifiedRules, ctx.getDeviceSmvMap())) {
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
            int expectedResultCount = expectedSpecCount + (genResult.emittedSpecs() == null ? 0 :
                    (int) genResult.emittedSpecs().stream().filter(spec -> spec.antecedentExpression() != null).count());
            boolean resultCountComplete = specResults != null
                    && !specResults.isEmpty()
                    && (expectedSpecCount == 0 || specResults.size() == expectedResultCount);
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

            boolean hasResponses = ctx.getSpecs() != null && ctx.getSpecs().stream()
                    .anyMatch(spec -> AntecedentReachability.appliesTo(spec.getTemplateId()));
            if (!hasResponses && specResults.stream().allMatch(SpecCheckResult::isPassed)) {
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
        AttributedResults attributed = resultsBySpecId(specResults, emittedSpecs, ctx.getSpecs());
        Map<String, SpecCheckResult> results = attributed == null ? null : attributed.properties();
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
        if (!attributed.reachableResponseIds().containsAll(baseline.reachableResponseIds())) {
            Set<String> lost = new LinkedHashSet<>(baseline.reachableResponseIds());
            lost.removeAll(attributed.reachableResponseIds());
            ctx.addDiagnostic("A candidate was rejected because it made previously reachable response triggers unreachable: " + lost);
            return Verification.rejected();
        }
        log.info("Forward verification result: target passes, pre-existing violations remain {}", stillViolated);
        return Verification.accepted(stillViolated);
    }

    private static Witness witness(String specId, SpecCheckResult result) {
        String trace = result.getCounterexample();
        return trace == null || trace.isBlank() ? null : new Witness(specId, trace);
    }

    /** The result per specification id, or {@code null} when any verdict's attribution is uncertain. */
    private record AttributedResults(Map<String, SpecCheckResult> properties, Set<String> reachableResponseIds) {}

    private static AttributedResults resultsBySpecId(
            List<SpecCheckResult> specResults, List<SmvGenerationContext.EmittedSpec> emittedSpecs,
            List<SpecificationDto> expectedSpecs) {
        if (emittedSpecs == null || expectedSpecs == null || emittedSpecs.size() != expectedSpecs.size()) return null;
        SpecResultAlignment.Alignment alignment = SpecResultAlignment.align(specResults, emittedSpecs);
        if (alignment.backFilled() > 0 || alignment.expectedResultCount() != specResults.size()) return null;
        Map<String, SpecCheckResult> results = new LinkedHashMap<>();
        Set<String> reachable = new LinkedHashSet<>();
        for (int i = 0; i < emittedSpecs.size(); i++) {
            String specId = emittedSpecs.get(i) != null ? emittedSpecs.get(i).specId() : null;
            SpecCheckResult result = alignment.aligned().get(i);
            // "unknown" is the generator's placeholder for a specification without an id; it names no
            // specification, so a verdict filed under it cannot be compared with the original rules'.
            if (result == null || specId == null || specId.isBlank() || "unknown".equals(specId)
                    || results.putIfAbsent(specId, result) != null) {
                return null;
            }
            SpecificationDto expected = expectedSpecs.stream().filter(spec -> specId.equals(spec.getId())).findFirst().orElse(null);
            if (expected == null) return null;
            if (AntecedentReachability.appliesTo(expected.getTemplateId())) {
                SpecCheckResult antecedent = alignment.antecedents().get(i);
                if (antecedent == null) return null;
                if (!antecedent.isPassed()) reachable.add(specId);
            }
        }
        return new AttributedResults(results, Collections.unmodifiableSet(reachable));
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
            ctx.addDiagnostic("The original rules could not be re-checked; candidates requiring baseline "
                    + "property or response-trigger preservation remain inconclusive.");
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
            AttributedResults results = resultsBySpecId(result.getSpecResults(), genResult.emittedSpecs(), ctx.getSpecs());
            if (results == null) return FixContext.BaselineVerdict.unavailable();
            Set<String> violated = new LinkedHashSet<>();
            results.properties().forEach((specId, specResult) -> {
                if (!specResult.isPassed()) violated.add(specId);
            });
            return new FixContext.BaselineVerdict(Collections.unmodifiableSet(violated), results.reachableResponseIds());
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

    static boolean candidateRulesPersistable(List<RuleDto> rules, Map<String, DeviceSmvData> deviceSmvMap) {
        List<RuleDto> safeRules = rules == null ? List.of() : rules;
        ToIntFunction<String> modeCountForDevice = device -> {
            DeviceSmvData smv = DeviceReferenceResolver.resolve(device, deviceSmvMap);
            return smv == null ? 0 : smv.getModes().size();
        };
        for (int left = 0; left < safeRules.size(); left++) {
            for (int right = 0; right < left; right++) {
                if (RuleSemanticSignature.exactlyMatches(safeRules.get(left), safeRules.get(right), modeCountForDevice)) {
                    return false;
                }
            }
        }
        return true;
    }

    static Set<String> occupiedRepairSymbols(FixContext ctx) {
        Map<String, DeviceManifest> manifests = new LinkedHashMap<>();
        Set<String> occupied = new HashSet<>();
        ctx.getDeviceSmvMap().forEach((device, smv) -> {
            if (smv == null) return;
            if (smv.getManifest() != null) manifests.put(device, smv.getManifest());
            // Generated repair names are lowercase and the main namespace compares instances case-insensitively.
            if (smv.getVarName() != null) occupied.add(smv.getVarName().trim().toLowerCase(Locale.ROOT));
        });
        occupied.addAll(DeviceSmvNamespace.sceneConstants(manifests, ctx.isEnablePrivacy()).keySet());
        return occupied;
    }

    static String freshSolverName(String requested, Set<String> occupied) {
        String name = requested;
        int suffix = 1;
        while (occupied.contains(name)) {
            name = requested + "_" + suffix++;
        }
        return name;
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
                if (!assertsSignal(relation, sc.getValue())) {
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
                if (isPostconditionOnly(rule, candidate, effects, deviceSmvMap)) {
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
            String mode = DeviceSmvDataFactory.resolveModeIdentifier(smv, candidate.getAttribute());
            List<String> values = mode == null || smv.getModeStates() == null ? null : smv.getModeStates().get(mode);
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
                    RuleDto.Condition selected = copyConditionWithValue(candidate,
                            authoredCandidateValue(candidate, value, deviceSmvMap));
                    return !isPostconditionOnly(rule, selected, effects, deviceSmvMap)
                            && !isCommandPrestateIncompatible(rule, selected, deviceSmvMap);
                })
                .toList();
        if (allowedValues.isEmpty()) return null;
        return ParameterizationConfig.ConditionValueInfo.builder()
                .frozenVarName(valueInfo.getFrozenVarName())
                .values(new ArrayList<>(allowedValues))
                .build();
    }

    /** Candidate domains contain solver atoms; rule operands use the declared state spelling. */
    static String authoredCandidateValue(
            RuleDto.Condition condition, String solverValue, Map<String, DeviceSmvData> deviceSmvMap) {
        if (!"mode".equals(condition.getTargetType())) return solverValue;
        DeviceSmvData device = DeviceReferenceResolver.resolve(condition.getDeviceName(), deviceSmvMap);
        String mode = DeviceSmvDataFactory.resolveModeIdentifier(device, condition.getAttribute());
        return device.authoredStateName(mode, solverValue);
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

    enum EffectMatch { GUARANTEED, POSSIBLE }

    /**
     * Guaranteed positive outcomes can prune guards. Possible outcomes, including newly enabled
     * negative predicates, only trace potential feedback through other rules.
     */
    static boolean matchesApiOutcome(
            DeviceSmvData commandDevice,
            DeviceManifest.API api,
            Set<String> writableModes,
            RuleDto.Condition candidate,
            EffectMatch match) {
        if (commandDevice == null || api == null || candidate == null) return false;
        String targetType = normalizedTargetType(candidate);
        String relation = SmvRelationUtils.normalizeRelation(candidate.getRelation());
        if ("api".equals(targetType)) {
            return Boolean.TRUE.equals(api.getSignal())
                    && candidate.getAttribute() != null
                    && candidate.getAttribute().trim().equals(api.getName())
                    && (relation == null ? candidate.getValue() == null : assertsSignal(relation, candidate.getValue()));
        }
        if (api.getEndState() == null || api.getEndState().isBlank()) {
            return false;
        }
        boolean positive = "=".equals(relation) || "in".equals(relation);
        boolean negative = "!=".equals(relation) || "not in".equals(relation);
        if (!positive && (match == EffectMatch.GUARANTEED || !negative)) return false;

        List<Map<String, String>> patterns = conditionStatePatterns(commandDevice, candidate, targetType, relation);
        Map<String, String> endTuple = concreteEndState(commandDevice, api.getEndState());
        if (endTuple.isEmpty()) return false;
        Set<String> relevantModes = new LinkedHashSet<>(endTuple.keySet());
        if (match == EffectMatch.POSSIBLE) relevantModes.addAll(writableModes);
        List<Map<String, String>> affectedPatterns = patterns.stream()
                .filter(pattern -> !Collections.disjoint(pattern.keySet(), relevantModes)).toList();
        if (affectedPatterns.isEmpty()) return false;
        if (match == EffectMatch.GUARANTEED && coversStateDomain(commandDevice, endTuple, patterns)) return true;

        // A disjoint command or autonomous transition can still write an untouched mode on this tick.
        Map<String, String> startTuple = concreteStartState(commandDevice, api.getStartState());
        Map<String, String> postState = new LinkedHashMap<>(endTuple);
        startTuple.forEach((mode, state) -> {
            if (!writableModes.contains(mode)) postState.putIfAbsent(mode, state);
        });
        if (match == EffectMatch.GUARANTEED) {
            return postState.size() > endTuple.size()
                    && coversStateDomain(commandDevice, postState, patterns)
                    && patterns.stream().noneMatch(pattern -> overlapsStatePattern(startTuple, pattern));
        }
        if (positive) {
            return affectedPatterns.stream().anyMatch(pattern -> overlapsStatePattern(postState, pattern));
        }
        return affectedPatterns.stream().anyMatch(pattern -> overlapsStatePattern(startTuple, pattern))
                && !coversStateDomain(commandDevice, postState, patterns);
    }

    /** Whether a Boolean comparison is true exactly when the API signal is active. */
    private static boolean assertsSignal(String relation, String value) {
        if (relation == null || value == null) return false;
        String requiredOperand = switch (relation) {
            case "=", "in" -> "TRUE";
            case "!=", "not in" -> "FALSE";
            default -> null;
        };
        if (requiredOperand == null) return false;
        List<String> values = "in".equals(relation) || "not in".equals(relation)
                ? SmvRelationUtils.splitRuleValues(value) : List.of(value);
        return !values.isEmpty() && values.stream()
                .map(ManifestConditionSemantics::canonicalApiSignalValue)
                .allMatch(requiredOperand::equals);
    }

    private static boolean isPostconditionOnly(
            RuleDto rule,
            RuleDto.Condition candidate,
            CommandEffects effects,
            Map<String, DeviceSmvData> deviceSmvMap) {
        if (!effects.establishes(candidate)) return false;
        CommandApiContext command = commandApiContext(rule, candidate, deviceSmvMap);
        if (command == null) return true;

        DeviceSmvData device = command.device();
        List<Map<String, String>> patterns = conditionStatePatterns(
                device, candidate, normalizedTargetType(candidate), SmvRelationUtils.normalizeRelation(candidate.getRelation()));
        Map<String, String> start = concreteStartState(device, command.api().getStartState());
        Map<String, String> end = concreteEndState(device, command.api().getEndState());

        // A predicate can cover every outcome and still admit a source state from which the command makes a change.
        return patterns.stream().filter(pattern -> overlapsStatePattern(start, pattern))
                .noneMatch(pattern -> end.entrySet().stream().anyMatch(write -> {
                    String mode = write.getKey();
                    String before = start.getOrDefault(mode, pattern.get(mode));
                    if (before != null) return !before.equals(write.getValue());
                    return device.getModeStates().getOrDefault(mode, List.of()).stream()
                            .anyMatch(state -> !state.equals(write.getValue()));
                }));
    }

    /**
     * Whether a candidate condition is provably false whenever the command API is executable.
     * Wildcard start-state segments range over their declared mode domains.
     */
    static boolean isCommandPrestateIncompatible(
            RuleDto rule,
            RuleDto.Condition candidate,
            Map<String, DeviceSmvData> deviceSmvMap) {
        CommandApiContext command = commandApiContext(rule, candidate, deviceSmvMap);
        if (command == null) return false;
        DeviceSmvData commandDevice = command.device();
        String relation = SmvRelationUtils.normalizeRelation(candidate.getRelation());
        String targetType = normalizedTargetType(candidate);

        if (relation == null) return false;
        List<Map<String, String>> patterns = conditionStatePatterns(commandDevice, candidate, targetType, relation);
        if (patterns.isEmpty()) return false;
        Map<String, String> startTuple = concreteStartState(commandDevice, command.api().getStartState());
        return switch (relation) {
            case "=", "in" -> patterns.stream().noneMatch(pattern -> overlapsStatePattern(startTuple, pattern));
            case "!=", "not in" -> coversStateDomain(commandDevice, startTuple, patterns);
            default -> false;
        };
    }

    /** Prove that two fixed authored state guards cannot both be true in the source state. */
    static boolean stateConditionsDisjoint(RuleDto.Condition left, RuleDto.Condition right,
                                           Map<String, DeviceSmvData> deviceSmvMap) {
        if (left == null || right == null) return false;
        String deviceVar = resolveVarNameSafe(left.getDeviceName(), deviceSmvMap);
        if (deviceVar == null || !deviceVar.equals(resolveVarNameSafe(right.getDeviceName(), deviceSmvMap))) return false;

        DeviceSmvData device = deviceSmvMap.get(deviceVar);
        String leftRelation = SmvRelationUtils.normalizeRelation(left.getRelation());
        String rightRelation = SmvRelationUtils.normalizeRelation(right.getRelation());
        if (leftRelation == null || rightRelation == null) return false;
        boolean leftPositive = "=".equals(leftRelation) || "in".equals(leftRelation);
        boolean rightPositive = "=".equals(rightRelation) || "in".equals(rightRelation);
        if ((!leftPositive && !"!=".equals(leftRelation) && !"not in".equals(leftRelation))
                || (!rightPositive && !"!=".equals(rightRelation) && !"not in".equals(rightRelation))) return false;

        List<Map<String, String>> leftPatterns = conditionStatePatterns(device, left, normalizedTargetType(left), leftRelation);
        List<Map<String, String>> rightPatterns = conditionStatePatterns(device, right, normalizedTargetType(right), rightRelation);
        if (leftPatterns.isEmpty() || rightPatterns.isEmpty()) return false;
        if (leftPositive && rightPositive) {
            return leftPatterns.stream().noneMatch(first -> rightPatterns.stream()
                    .anyMatch(second -> overlapsStatePattern(first, second)));
        }
        if (leftPositive) return leftPatterns.stream().allMatch(pattern -> coversStateDomain(device, pattern, rightPatterns));
        if (rightPositive) return rightPatterns.stream().allMatch(pattern -> coversStateDomain(device, pattern, leftPatterns));
        List<Map<String, String>> excluded = new ArrayList<>(leftPatterns);
        excluded.addAll(rightPatterns);
        return coversStateDomain(device, Map.of(), excluded);
    }

    private static String normalizedTargetType(RuleDto.Condition condition) {
        return condition.getTargetType() == null ? "" : condition.getTargetType().trim().toLowerCase(Locale.ROOT);
    }

    private static List<Map<String, String>> conditionStatePatterns(
            DeviceSmvData device, RuleDto.Condition condition, String targetType, String relation) {
        if (device.getModes() == null || condition.getValue() == null) return List.of();
        if ("mode".equals(targetType)) {
            String mode = DeviceSmvDataFactory.resolveModeIdentifier(device, condition.getAttribute());
            if (mode == null) return List.of();
            List<Map<String, String>> patterns = new ArrayList<>();
            for (String authored : splitModeValues(condition.getValue(), relation)) {
                String state = device.resolveAuthoredState(mode, authored);
                if (state == null) return List.of();
                patterns.add(Map.of(mode, state));
            }
            return patterns;
        }
        String attribute = condition.getAttribute();
        if (!"state".equals(targetType) || attribute == null || !"state".equalsIgnoreCase(attribute.trim())) {
            return List.of();
        }
        List<String> values = SmvRelationUtils.stateConditionValues(
                condition.getValue(), relation, device.getModes().size());
        List<Map<String, String>> patterns = new ArrayList<>();
        for (String value : values) {
            Map<String, String> pattern = ManifestConditionSemantics.resolveStatePattern(
                    value, device.getModes(), device::resolveAuthoredState);
            if (pattern.isEmpty()) return List.of();
            patterns.add(pattern);
        }
        return patterns;
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

    static Map<String, String> concreteEndState(DeviceSmvData smv, String rawEndState) {
        return concreteApiState(smv, rawEndState, false);
    }

    static Map<String, String> concreteStartState(DeviceSmvData smv, String rawStartState) {
        return concreteApiState(smv, rawStartState, true);
    }

    private static Map<String, String> concreteApiState(
            DeviceSmvData smv,
            String rawState,
            boolean allowWildcard) {
        List<String> modes = smv.getModes();
        if (modes == null || modes.isEmpty() || rawState == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < modes.size(); i++) {
            String state = DeviceSmvDataFactory.getStateForMode(rawState, i, allowWildcard);
            if (state == null || state.isBlank()) continue;
            result.put(modes.get(i), state);
        }
        return result;
    }

    static boolean overlapsStatePattern(Map<String, String> known, Map<String, String> pattern) {
        return pattern.entrySet().stream().allMatch(entry -> !known.containsKey(entry.getKey())
                || entry.getValue().equals(known.get(entry.getKey())));
    }

    private static boolean coversStateDomain(DeviceSmvData device, Map<String, String> known,
                                             List<Map<String, String>> patterns) {
        return new StateSetCoverage(device.getModeStates()).covers(new LinkedHashMap<>(known), patterns);
    }

    private static final class StateSetCoverage {
        private final Map<String, List<String>> domains;
        // Repair pruning must not spend its deadline proving a large union. An unfinished proof leaves the candidate.
        private int remainingBranches = 1024;

        private StateSetCoverage(Map<String, List<String>> domains) {
            this.domains = domains;
        }

        private boolean covers(Map<String, String> known, List<Map<String, String>> patterns) {
            if (remainingBranches-- <= 0) return false;
            List<Map<String, String>> compatible = patterns.stream()
                    .filter(pattern -> overlapsStatePattern(known, pattern)).toList();
            if (compatible.isEmpty()) return false;
            if (compatible.stream().anyMatch(pattern -> known.keySet().containsAll(pattern.keySet()))) return true;

            String mode = compatible.get(0).keySet().stream()
                    .filter(key -> !known.containsKey(key)).findFirst().orElseThrow();
            List<String> domain = domains == null ? null : domains.get(mode);
            if (domain == null || domain.isEmpty()) return false;
            Set<String> branches = compatible.stream().map(pattern -> pattern.get(mode))
                    .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
            // States unnamed by the remaining patterns match exactly the same wildcard branches.
            domain.stream().filter(state -> !branches.contains(state)).findFirst().ifPresent(branches::add);
            for (String state : branches) {
                known.put(mode, state);
                boolean covered = covers(known, compatible);
                known.remove(mode);
                if (!covered) return false;
            }
            return true;
        }
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
            if (!"state".equalsIgnoreCase(attr)) return false;
            // State only allows = != in not_in (SmvMainModuleBuilder.buildRuleStateCondition:595-600)
            if (!"=".equals(normalizedRel) && !"!=".equals(normalizedRel)
                    && !"in".equals(normalizedRel) && !"not in".equals(normalizedRel)) {
                return false;
            }
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
            String mode = DeviceSmvDataFactory.resolveModeIdentifier(smv, attr);
            if (mode == null) return false;
            if (!"=".equals(normalizedRel) && !"!=".equals(normalizedRel)
                    && !"in".equals(normalizedRel) && !"not in".equals(normalizedRel)) {
                return false;
            }
            List<String> values = splitModeValues(candidate.getValue(), normalizedRel);
            if (values.isEmpty()) return false;
            if (("=".equals(normalizedRel) || "!=".equals(normalizedRel)) && values.size() != 1) {
                return false;
            }
            return values.stream().allMatch(value -> smv.resolveAuthoredState(mode, value) != null);
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

    private static boolean isValidStateValue(DeviceSmvData smv, String rawValue) {
        return !ManifestConditionSemantics.resolveStatePattern(
                rawValue, smv.getModes(), smv::resolveAuthoredState).isEmpty();
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
     * Uses the condition's type to match the generated predicate: state tuples keep their
     * semicolons, mode membership splits on them, and numeric variables preserve signed values.
     * Returns null if device resolution fails.
     */
    static String conditionFingerprint(
            RuleDto.Condition c, Map<String, DeviceSmvData> deviceSmvMap) {
        if (c == null || c.getDeviceName() == null) return null;
        String varName = resolveVarNameSafe(c.getDeviceName(), deviceSmvMap);
        if (varName == null) return null;
        String normRel = SmvRelationUtils.normalizeRelation(c.getRelation());
        String targetType = c.getTargetType() != null ? c.getTargetType().trim().toLowerCase(Locale.ROOT) : "";
        // Resolve mode count for mode-aware value normalization
        int modeCount = 1;
        if ("state".equals(targetType)) {
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
        String normVal = switch (targetType) {
            case "variable" -> variableFingerprintValue(c, normRel, deviceSmvMap.get(varName));
            case "state", "mode" -> authoredStateFingerprintValue(c.getValue(), normRel, modeCount);
            default -> SmvRelationUtils.cleanRuleValueByRelation(normRel, c.getValue(), modeCount);
        };
        if (normVal == null) normVal = "";
        String attr = c.getAttribute() != null ? c.getAttribute() : "";
        return varName + "|" + targetType + "|" + attr + "|" + normRel + "|" + normVal;
    }

    private static String authoredStateFingerprintValue(String value, String relation, int modeCount) {
        if ("in".equals(relation) || "not in".equals(relation)) {
            return SmvRelationUtils.canonicalStateMembershipValue(value, modeCount);
        }
        List<String> operands = SmvRelationUtils.stateConditionValues(value, relation, modeCount);
        return operands.stream().map(operand -> {
            if (modeCount <= 1 || !operand.contains(";")) return operand.trim();
            return Arrays.stream(operand.split(";", -1))
                    .map(segment -> DeviceSmvDataFactory.isWildcardStateSegment(segment) ? "" : segment.trim())
                    .collect(Collectors.joining(";"));
        }).collect(Collectors.joining(","));
    }

    private static String variableFingerprintValue(RuleDto.Condition condition, String relation, DeviceSmvData smv) {
        if (condition.getValue() == null) return "";
        DeviceManifest.InternalVariable variable = smv != null
                ? ManifestConditionSemantics.findConditionVariable(smv.getManifest(), condition.getAttribute()) : null;
        Stream<String> values = "in".equals(relation) || "not in".equals(relation)
                ? SmvRelationUtils.splitRuleValues(condition.getValue()).stream() : Stream.of(condition.getValue());
        return values.map(value -> {
            if (variable != null && variable.getValues() != null && !variable.getValues().isEmpty()) {
                return ManifestConditionSemantics.cleanEnumLiteral(value);
            }
            if (variable != null && variable.getLowerBound() != null && variable.getUpperBound() != null) {
                try {
                    return Integer.toString(SmvIntegerLiteral.parse(value));
                } catch (NumberFormatException invalid) {
                    // Preserve malformed operands literally on stale snapshots instead of merging them.
                    return value.trim();
                }
            }
            return value.trim();
        }).collect(Collectors.joining(","));
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
