package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.NusmvResult;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParameterAdjustStrategyTest {

    @Mock private SmvGenerator smvGenerator;
    @Mock private NusmvExecutor nusmvExecutor;

    private ParameterAdjustStrategy strategy;

    @Test
    void refinementMath_handlesFullIntegerDomainWithoutOverflow() {
        assertEquals(4_294_967_295L,
                ParameterAdjustStrategy.distance(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertArrayEquals(
                new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE - 1},
                ParameterAdjustStrategy.refinementWindow(
                        Integer.MIN_VALUE, 4_294_967_295L,
                        Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertArrayEquals(
                new int[]{Integer.MIN_VALUE + 1, Integer.MAX_VALUE},
                ParameterAdjustStrategy.refinementWindow(
                        Integer.MAX_VALUE, 4_294_967_295L,
                        Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void boundaryDescription_distinguishesImpossibleStrictAndReachableNonStrictEndpoints() {
        List<RuleDto> rules = List.of(RuleDto.builder().ruleString("Boundary rule").build());

        ParameterAdjustment strictUpper = ParameterAdjustment.builder()
                .ruleIndex(0).conditionIndex(0).attribute("temperature")
                .relation(">").originalValue("30").newValue("50")
                .lowerBound(0).upperBound(50).build();
        ParameterAdjustment nonStrictUpper = ParameterAdjustment.builder()
                .ruleIndex(0).conditionIndex(0).attribute("temperature")
                .relation(">=").originalValue("30").newValue("50")
                .lowerBound(0).upperBound(50).build();
        ParameterAdjustment strictLower = ParameterAdjustment.builder()
                .ruleIndex(0).conditionIndex(0).attribute("temperature")
                .relation("<").originalValue("20").newValue("0")
                .lowerBound(0).upperBound(50).build();
        ParameterAdjustment nonStrictLower = ParameterAdjustment.builder()
                .ruleIndex(0).conditionIndex(0).attribute("temperature")
                .relation("<=").originalValue("20").newValue("0")
                .lowerBound(0).upperBound(50).build();

        assertTrue(ParameterAdjustStrategy.describeParameterAdjustment(strictUpper, rules)
                .contains("rule unreachable"));
        assertTrue(ParameterAdjustStrategy.describeParameterAdjustment(strictLower, rules)
                .contains("rule unreachable"));
        assertFalse(ParameterAdjustStrategy.describeParameterAdjustment(nonStrictUpper, rules)
                .contains("unreachable"));
        assertFalse(ParameterAdjustStrategy.describeParameterAdjustment(nonStrictLower, rules)
                .contains("unreachable"));
    }

    @BeforeEach
    void setUp() throws Exception {
        FixConfig fixConfig = new FixConfig();
        fixConfig.setMaxRefineAttempts(10);
        strategy = new ParameterAdjustStrategy(smvGenerator, nusmvExecutor, fixConfig);

        // Keep the strategy tests focused on solver behavior while routing their existing
        // generator fixtures through the production snapshot-aware entry points.
        lenient().when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                        anyLong(), anyList(), anyList(), anyList(), anyList(),
                        any(), anyBoolean(), any(ParameterizationConfig.class),
                        any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenAnswer(invocation -> smvGenerator.generateParameterized(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(3), invocation.getArgument(4),
                        invocation.getArgument(5), invocation.getArgument(6),
                        invocation.getArgument(7), invocation.getArgument(8)));
        lenient().when(smvGenerator.generateWithResolvedDeviceModel(
                        anyLong(), anyList(), anyList(), anyList(), anyList(),
                        any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                        any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenAnswer(invocation -> smvGenerator.generate(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(3), invocation.getArgument(4),
                        invocation.getArgument(5), invocation.getArgument(6),
                        invocation.getArgument(7), invocation.getArgument(8)));
    }

    private SmvGenerator.GenerateResult createGenResult() throws IOException {
        File tempDir = Files.createTempDirectory("smv-test").toFile();
        File smvFile = new File(tempDir, "test.smv");
        smvFile.createNewFile();
        return new SmvGenerator.GenerateResult(smvFile, Map.of());
    }

    private Map<String, DeviceSmvData> buildDeviceMap() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setModuleName("SensorModule");
        DeviceManifest.InternalVariable tempVar = DeviceManifest.InternalVariable.builder()
                .name("temperature")
                .lowerBound(0)
                .upperBound(50)
                .build();
        smv.setVariables(List.of(tempVar));
        return Map.of("sensor_1", smv);
    }

    private Map<String, DeviceSmvData> buildEnvironmentDeviceMap(String environmentName) {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setModuleName("SensorModule");
        DeviceManifest.InternalVariable envVar = DeviceManifest.InternalVariable.builder()
                .name(environmentName)
                .isInside(false)
                .lowerBound(0)
                .upperBound(50)
                .build();
        smv.setVariables(List.of(envVar));
        smv.getEnvVariables().put(environmentName, envVar);
        return Map.of("sensor_1", smv);
    }

    private Map<String, DeviceSmvData> buildDigitLeadingDeviceMap() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("d_1Sensor");
        smv.setModuleName("SensorModule");
        DeviceManifest.InternalVariable tempVar = DeviceManifest.InternalVariable.builder()
                .name("temperature")
                .lowerBound(0)
                .upperBound(50)
                .build();
        smv.setVariables(List.of(tempVar));
        return Map.of("d_1Sensor", smv);
    }

    private FixContext ctx(List<FaultRuleDto> faultRules, List<RuleDto> allRules,
                            List<SpecificationDto> specs, Map<String, DeviceSmvData> deviceSmvMap) {
        return FixContext.builder()
                .faultRules(faultRules)
                .repairRuleIndices(faultRules == null ? List.of()
                        : faultRules.stream().map(FaultRuleDto::getRuleIndex).toList())
                .allRules(allRules)
                .devices(List.of())
                .specs(specs)
                .deviceSmvMap(deviceSmvMap)
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(20)
                .build();
    }

    private static NusmvResult solverResult(boolean specPassed, String output) {
        SpecCheckResult spec = mock(SpecCheckResult.class);
        lenient().when(spec.isPassed()).thenReturn(specPassed);
        NusmvResult result = mock(NusmvResult.class);
        lenient().when(result.isSuccess()).thenReturn(true);
        lenient().when(result.getSpecResults()).thenReturn(List.of(spec));
        lenient().when(result.getOutput()).thenReturn(output);
        return result;
    }

    /** A forward-verification verdict on the target "s1", attributable through its emitted CTLSPEC. */
    private static NusmvResult targetVerdict(boolean passed) {
        return NusmvResult.success("", List.of(new SpecCheckResult("AG safe", passed, null)));
    }

    /** Answers successive NuSMV calls from a list prepared outside the stubbing call. */
    private static org.mockito.stubbing.Answer<NusmvResult> sequence(List<NusmvResult> results) {
        int[] next = {0};
        return invocation -> results.get(Math.min(next[0]++, results.size() - 1));
    }

    private static RuleDto twoThresholdRule() {        return RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder().deviceName("sensor_1").attribute("temperature")
                                .targetType("variable").relation(">").value("30").build(),
                        RuleDto.Condition.builder().deviceName("sensor_1").attribute("temperature")
                                .targetType("variable").relation("<").value("40").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
    }

    private FixContext twoThresholdContext(int maxAttempts) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");
        return FixContext.builder()
                .faultRules(List.of(FaultRuleDto.builder().ruleIndex(0).build()))
                .repairRuleIndices(List.of(0))
                .allRules(List.of(twoThresholdRule())).devices(List.of()).specs(List.of(spec))
                .deviceSmvMap(buildDeviceMap()).violatedSpecIndex(0).userId(1L)
                .attackScenario(AttackScenarioDto.none()).maxAttempts(maxAttempts).build();
    }

    @Test
    void tryFix_refine_singleThresholdNeverReverifiesTheCounterexampleBoard() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        SpecificationDto spec = new SpecificationDto();
        ArgumentCaptor<ParameterizationConfig> configs = ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configs.capture(), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        // discovery 25 → verify passes → refinement UNSAT, keeps 25
        List<NusmvResult> executions = List.of(
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 25\n"),
                solverResult(true, ""),
                solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(ctx(
                List.of(FaultRuleDto.builder().ruleIndex(0).build()), List.of(rule), List.of(spec),
                buildDeviceMap()));

        assertEquals(1, outcome.suggestions().size());
        assertEquals("25", outcome.suggestions().get(0).getParameterAdjustments().get(0).getNewValue());
        // configs: discovery, refinement, then the solve for another repair.
        List<String> refinementExclusions = configs.getAllValues().get(1).getExclusionInvars();
        assertTrue(refinementExclusions.contains("!(param_r0_c0=30)"),
                "with no other threshold moved, the original value is the counterexample's own board");
        assertTrue(refinementExclusions.contains("!(param_r0_c0=25)"));
        verify(smvGenerator, times(1)).generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class));
    }

    /**
     * In a joint repair the original value of one threshold may work once another moved. An
     * inconclusive Step A proves nothing about it, so refinement may still return it for one more
     * check; a threshold that returns to its original value is no longer part of the repair.
     */
    @Test
    void tryFix_refine_inconclusiveOriginalCheckLeavesTheOriginalToRefinement() throws Exception {
        ArgumentCaptor<ParameterizationConfig> configs = ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configs.capture(), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        NusmvResult crash = mock(NusmvResult.class);
        when(crash.isSuccess()).thenReturn(false);
        lenient().when(crash.getErrorMessage()).thenReturn("NuSMV process crashed");
        // 1. joint solve          → c0=25, c1=45
        // 2. forward-verify       → passes
        // 3. Step A (c0=30)       → crash, inconclusive
        // 4. refine c0            → 30
        // 5. verify 30            → passes, c0 returns to its original value
        // 6. refine c1            → UNSAT, keeps 45
        List<NusmvResult> executions = List.of(
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 25\n    param_r0_c1 = 45\n"),
                solverResult(true, ""),
                crash,
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 30\n"),
                solverResult(true, ""),
                solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(twoThresholdContext(1));

        assertEquals(1, outcome.suggestions().size());
        List<ParameterAdjustment> adjustments = outcome.suggestions().get(0).getParameterAdjustments();
        assertEquals(1, adjustments.size(), "c0 went back to its original value and is not part of the repair");
        assertEquals(1, adjustments.get(0).getConditionIndex());
        assertEquals("45", adjustments.get(0).getNewValue());
        assertFalse(configs.getAllValues().get(1).getExclusionInvars().contains("!(param_r0_c0=30)"),
                "an inconclusive check of the original must not exclude it");
    }

    /** A rejected original is settled, so refinement must not spend a solve and a verification on it again. */
    @Test
    void tryFix_refine_rejectedOriginalIsExcludedFromRefinement() throws Exception {
        ArgumentCaptor<ParameterizationConfig> configs = ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configs.capture(), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        // Verdicts must be attributable to "s1" for Step A's failure to count as a rejection.
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> new SmvGenerator.GenerateResult(createGenResult().smvFile(), Map.of(),
                        List.of(), 0, 0,
                        List.of(new SmvGenerationContext.EmittedSpec(null, "s1", "CTLSPEC AG(safe)"))));
        // 1. joint solve          → c0=25, c1=45
        // 2. forward-verify       → the target passes
        // 3. Step A (c0=30)       → the target fails: rejected
        // 4. refine c0            → UNSAT, keeps 25
        // 5. Step A (c1=40)       → the target passes, c1 returns to its original value
        List<NusmvResult> executions = List.of(
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 25\n    param_r0_c1 = 45\n"),
                targetVerdict(true),
                targetVerdict(false),
                solverResult(true, ""),
                targetVerdict(true));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(twoThresholdContext(1));

        assertEquals(1, outcome.suggestions().size());
        assertEquals(List.of("25"), outcome.suggestions().get(0).getParameterAdjustments().stream()
                .map(ParameterAdjustment::getNewValue).toList());
        assertTrue(configs.getAllValues().get(1).getExclusionInvars().contains("!(param_r0_c0=30)"),
                "the original was rejected on evidence");
    }

    /**
     * Once the solver runs out of values, a search in which every candidate was rejected must say so:
     * "no threshold helps" would be false, since the rejected values do block this counterexample.
     */
    @Test
    void tryFix_everyCandidateRejectedSaysSoOnceTheSearchIsExhausted() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> new SmvGenerator.GenerateResult(createGenResult().smvFile(), Map.of(),
                        List.of(), 0, 0,
                        List.of(new SmvGenerationContext.EmittedSpec(null, "s1", "CTLSPEC AG(safe)"))));
        // solve → 25, forward verification rejects it, the next solve is UNSAT
        List<NusmvResult> executions = List.of(
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 25\n"),
                targetVerdict(false),
                solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixContext context = ctx(List.of(FaultRuleDto.builder().ruleIndex(0).build()), List.of(rule),
                List.of(spec), buildDeviceMap());
        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);

        assertTrue(outcome.suggestions().isEmpty());
        assertTrue(outcome.alternativesComplete());
        assertEquals("ALL_CANDIDATES_REJECTED", context.strategyNoResult("parameter").status());
        assertTrue(context.strategyNoResult("parameter").reason().contains("rejected every one"),
                context.strategyNoResult("parameter").reason());
    }

    /**
     * Rules that fired but cannot influence the property are outside the repair scope; their thresholds
     * are not targets even when they are numeric. This is what kept the camera-at-night fix from
     * spending its whole time limit on heart-rate and CO2 thresholds.
     */
    @Test
    void tryFix_onlyThresholdsInsideTheRepairScopeAreTargets() {
        RuleDto outOfScope = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").targetType("variable").attribute("temperature")
                        .relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        RuleDto inScope = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").targetType("state").attribute("state")
                        .relation("=").value("on").build()))
                .command(RuleDto.Command.builder().deviceName("lamp").action("on").build())
                .build();
        FixContext context = FixContext.builder()
                .faultRules(List.of(FaultRuleDto.builder().ruleIndex(1).build()))
                .repairRuleIndices(List.of(1))
                .allRules(List.of(outOfScope, inScope)).devices(List.of()).specs(List.of(new SpecificationDto()))
                .deviceSmvMap(buildDeviceMap()).violatedSpecIndex(0).userId(1L)
                .attackScenario(AttackScenarioDto.none()).maxAttempts(20).build();

        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        assertEquals("SKIPPED_NO_PARAMETERIZABLE_VALUES", context.strategyNoResult("parameter").status());
        assertTrue(context.parameterTargetsSnapshot().isEmpty());
        verifyNoInteractions(nusmvExecutor);
    }

    /**
     * Phase 1 solves each threshold alone before the joint solve. An UNSAT there costs one ¬ρ call and
     * no full-model verification, which is what keeps the search inside the time limit.
     */
    @Test
    void tryFix_singleThresholdPhaseTriesEachThresholdAloneBeforeTheJointSolve() throws Exception {
        ArgumentCaptor<ParameterizationConfig> configs = ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configs.capture(), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        List<NusmvResult> executions = List.of(
                solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixContext context = twoThresholdContext(4);
        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);
        assertTrue(outcome.suggestions().isEmpty());
        assertTrue(outcome.alternativesComplete(), "the joint UNSAT proves no parameter repair exists");

        assertEquals(List.of(java.util.Set.of("r0_c0"), java.util.Set.of("r0_c1"),
                        java.util.Set.of("r0_c0", "r0_c1")),
                configs.getAllValues().stream()
                        .map(config -> config.getParameterizedThresholds().keySet())
                        .toList());
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class));
        assertEquals("NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", context.strategyNoResult("parameter").status());
    }

    @Test
    void tryFix_verifiedSingleThresholdRepairLeavesTheOtherThresholdUntouched() throws Exception {
        FixConfig noRefineConfig = new FixConfig();
        noRefineConfig.setMaxRefineAttempts(0);
        ParameterAdjustStrategy noRefineStrategy =
                new ParameterAdjustStrategy(smvGenerator, nusmvExecutor, noRefineConfig);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        List<NusmvResult> executions = List.of(
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 45\n"),
                solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixStrategy.StrategyOutcome outcome = noRefineStrategy.tryFix(twoThresholdContext(20));

        assertEquals(1, outcome.suggestions().size());
        List<ParameterAdjustment> adjustments = outcome.suggestions().get(0).getParameterAdjustments();
        assertEquals(1, adjustments.size());
        assertEquals(0, adjustments.get(0).getConditionIndex());
        assertEquals("45", adjustments.get(0).getNewValue());
        assertTrue(outcome.alternativesComplete());
    }

    /**
     * Every minimal repair is listed for the user to choose from. Once one is listed, the same
     * threshold moved to another value is only a variant of it, and a repair moving it plus another
     * threshold is not minimal; both are excluded from later solves.
     */
    @Test
    void tryFix_listsEachSingleThresholdRepairAndExcludesItsSupersetsFromTheJointSolve() throws Exception {
        FixConfig noRefineConfig = new FixConfig();
        noRefineConfig.setMaxRefineAttempts(0);
        ParameterAdjustStrategy noRefineStrategy =
                new ParameterAdjustStrategy(smvGenerator, nusmvExecutor, noRefineConfig);
        ArgumentCaptor<ParameterizationConfig> configs = ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configs.capture(), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        NusmvResult crash = mock(NusmvResult.class);
        when(crash.isSuccess()).thenReturn(false);
        lenient().when(crash.getErrorMessage()).thenReturn("NuSMV process crashed");
        // 1. c0 alone  → 45       2. verify → crash, inconclusive, excluded exactly
        // 3. c0 alone  → 20       4. verify → passes, listed
        // 5. c1 alone  → 45       6. verify → passes, listed
        // 7. jointly   → UNSAT: no minimal repair is left
        List<NusmvResult> executions = List.of(
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 45\n"),
                crash,
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 20\n"),
                solverResult(true, ""),
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c1 = 45\n"),
                solverResult(true, ""),
                solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixStrategy.StrategyOutcome outcome = noRefineStrategy.tryFix(twoThresholdContext(20));

        assertEquals(List.of(List.of("r0_c0=20"), List.of("r0_c1=45")), outcome.suggestions().stream()
                .map(suggestion -> suggestion.getParameterAdjustments().stream()
                        .map(adjustment -> "r" + adjustment.getRuleIndex() + "_c" + adjustment.getConditionIndex()
                                + "=" + adjustment.getNewValue())
                        .toList())
                .toList());
        assertTrue(outcome.alternativesComplete(),
                "the only unchecked candidate moves r0_c0, which a listed repair already covers");
        assertEquals(List.of(
                        List.of(),
                        List.of("!(param_r0_c0=45)"),
                        List.of(),
                        List.of("!(param_r0_c0=45 & param_r0_c1=40)",
                                "!(param_r0_c0!=30)", "!(param_r0_c1!=40)")),
                configs.getAllValues().stream().map(ParameterizationConfig::getExclusionInvars).toList());
    }

    /**
     * Step A re-verifies a threshold's original value only when another threshold moved: with every
     * other value at its original, that board is the one that produced the counterexample. When the
     * original works, that threshold drops out of the repair.
     */
    @Test
    void tryFix_refine_aThresholdWhoseOriginalValueWorksLeavesTheRepair() throws Exception {
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        // 1. joint solve       → c0=25, c1=45
        // 2. forward-verify    → passes
        // 3. Step A for c0=30  → passes (c1 moved), so c0 keeps its original value
        // 4. refine c1         → UNSAT, keeps 45 (no Step A: c0 is back at its original)
        List<NusmvResult> executions = List.of(
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 25\n    param_r0_c1 = 45\n"),
                solverResult(true, ""),
                solverResult(true, ""),
                solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(twoThresholdContext(1));

        assertEquals(1, outcome.suggestions().size());
        List<ParameterAdjustment> adjustments = outcome.suggestions().get(0).getParameterAdjustments();
        assertEquals(List.of(1), adjustments.stream().map(ParameterAdjustment::getConditionIndex).toList());
        assertEquals(List.of("45"), adjustments.stream().map(ParameterAdjustment::getNewValue).toList());
        verify(smvGenerator, times(2)).generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class));
    }

    @Test
    void tryFix_nullFaultRules_findsNothing() {
        assertTrue(strategy.tryFix(ctx(null, List.of(), List.of(), Map.of())).suggestions().isEmpty());
    }

    @Test
    void tryFix_emptyFaultRules_findsNothing() {
        assertTrue(strategy.tryFix(ctx(List.of(), List.of(), List.of(), Map.of())).suggestions().isEmpty());
    }

    @Test
    void resolveBounds_environmentVariableNamesAreLiteral() throws Exception {
        java.lang.reflect.Method method = ParameterAdjustStrategy.class
                .getDeclaredMethod("resolveBounds", RuleDto.Condition.class, Map.class);
        method.setAccessible(true);

        RuleDto.Condition actualAPrefixedName = RuleDto.Condition.builder()
                .deviceName("sensor_1")
                .targetType("variable")
                .attribute("a_temperature")
                .relation(">")
                .value("30")
                .build();

        int[] literalBounds = (int[]) method.invoke(
                strategy,
                actualAPrefixedName,
                buildEnvironmentDeviceMap("a_temperature"));
        assertArrayEquals(new int[]{0, 50}, literalBounds);

        int[] aliasBounds = (int[]) method.invoke(
                strategy,
                actualAPrefixedName,
                buildEnvironmentDeviceMap("temperature"));
        assertNull(aliasBounds, "a_temperature must not resolve as an alias for temperature");
    }

    @Test
    void tryFix_noNumericConditions_skipsTheStrategy() {
        // Rule with state condition (non-numeric) — should not be parameterizable
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("state").relation("=").value("active").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        FixContext context = ctx(List.of(fault), List.of(rule), List.of(spec), buildDeviceMap());
        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        assertEquals("SKIPPED_NO_PARAMETERIZABLE_VALUES",
                context.strategyNoResult("parameter").status());
        assertTrue(context.parameterTargetsSnapshot().isEmpty());
    }

    @Test
    void tryFix_oneAttemptWithMultipleThresholds_reservesJointSolve() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature")
                                .targetType("variable").relation(">=").value("30").build(),
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature")
                                .targetType("variable").relation("<").value("40").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());
        SpecCheckResult passing = mock(SpecCheckResult.class);
        when(passing.isPassed()).thenReturn(true);
        NusmvResult result = mock(NusmvResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.getSpecResults()).thenReturn(List.of(passing));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(result);

        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex()))
                .allRules(List.of(rule))
                .devices(List.of())
                .specs(List.of(spec))
                .deviceSmvMap(buildDeviceMap())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(1)
                .build();

        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        verify(smvGenerator, times(1)).generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap());
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), anyList());
    }

    @Test
    void tryFix_numericCondition_negatedSpecTrue_provesNoParameterRepairExists() throws Exception {
        // Setup: numeric condition with bounds available
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        // Negated spec passes (universally true) → no fix possible
        SpecCheckResult passing = mock(SpecCheckResult.class);
        when(passing.isPassed()).thenReturn(true);
        NusmvResult result = mock(NusmvResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.getSpecResults()).thenReturn(List.of(passing));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(result);

        FixContext context = ctx(List.of(fault), List.of(rule), List.of(spec), buildDeviceMap());
        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);
        assertTrue(outcome.suggestions().isEmpty());
        assertTrue(outcome.alternativesComplete());
        assertEquals(1, context.parameterTargetsSnapshot().size(),
                "eligible targets must be returned even when no verified suggestion is found");
        assertEquals(0, context.parameterTargetsSnapshot().get(0).getLowerBound());
        assertEquals(50, context.parameterTargetsSnapshot().get(0).getUpperBound());
    }

    @Test
    void tryFix_numericCondition_findsFixValue() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        // generateParameterized: called for initial discovery AND refinement NuSMV solves
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        // generate: called for forward-verify (initial, try-original, candidate verify)
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        // NuSMV counterexample outputs
        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult25 = mock(NusmvResult.class);
        when(negatedResult25.isSuccess()).thenReturn(true);
        when(negatedResult25.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult25.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 25\n    sensor_1.temperature = 30\n");

        NusmvResult negatedResult29 = mock(NusmvResult.class);
        when(negatedResult29.isSuccess()).thenReturn(true);
        when(negatedResult29.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult29.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 29\n");

        // Forward-verify results
        SpecCheckResult allPass = mock(SpecCheckResult.class);
        when(allPass.isPassed()).thenReturn(true);
        NusmvResult verifyPass = mock(NusmvResult.class);
        when(verifyPass.isSuccess()).thenReturn(true);
        when(verifyPass.getSpecResults()).thenReturn(List.of(allPass));

        // execute() call sequence (a single threshold has no try-original step: with nothing else
        // moved, the original board is the one that produced the counterexample):
        // 1. negatedResult25        → initial discovery (param=25)
        // 2. verifyPass             → initial forward-verify passes
        // 3. negatedResult29        → refinement NuSMV solve → param=29
        // 4. verifyPass             → candidate 29 forward-verify passes
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenReturn(negatedResult25)
                .thenReturn(verifyPass)
                .thenReturn(negatedResult29)
                .thenReturn(verifyPass);

        List<FixSuggestionDto> suggestions = strategy.tryFix(
                ctx(List.of(fault), List.of(rule), List.of(spec), buildDeviceMap())).suggestions();

        assertEquals(1, suggestions.size());
        FixSuggestionDto suggestion = suggestions.get(0);
        assertEquals("parameter", suggestion.getStrategy());
        assertNotNull(suggestion.getParameterAdjustments());
        assertEquals(1, suggestion.getParameterAdjustments().size());
        // NuSMV-guided refinement: initial=25, NuSMV finds 29 in the distance-bounded range,
        // forward-verify passes → bestDist=1 → done
        assertEquals("29", suggestion.getParameterAdjustments().get(0).getNewValue());
        assertEquals("30", suggestion.getParameterAdjustments().get(0).getOriginalValue());
    }

    @Test
    void tryFix_rawDigitLeadingDeviceLabel_resolvesBoundsLikeGenerator() throws Exception {
        FixConfig noRefineConfig = new FixConfig();
        noRefineConfig.setMaxRefineAttempts(0);
        ParameterAdjustStrategy noRefineStrategy =
                new ParameterAdjustStrategy(smvGenerator, nusmvExecutor, noRefineConfig);

        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("d_1Sensor").targetType("variable")
                        .attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult = mock(NusmvResult.class);
        when(negatedResult.isSuccess()).thenReturn(true);
        when(negatedResult.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 25\n");

        SpecCheckResult allPass = mock(SpecCheckResult.class);
        when(allPass.isPassed()).thenReturn(true);
        NusmvResult verifyResult = mock(NusmvResult.class);
        when(verifyResult.isSuccess()).thenReturn(true);
        when(verifyResult.getSpecResults()).thenReturn(List.of(allPass));

        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenReturn(negatedResult)
                .thenReturn(verifyResult);

        List<FixSuggestionDto> suggestions = noRefineStrategy.tryFix(
                ctx(List.of(fault), List.of(rule), List.of(spec), buildDigitLeadingDeviceMap())).suggestions();

        assertEquals(1, suggestions.size());
        assertEquals("25", suggestions.get(0).getParameterAdjustments().get(0).getNewValue());
    }

    @Test
    void tryFix_refine_unsat_keepsInitialValue() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult = mock(NusmvResult.class);
        when(negatedResult.isSuccess()).thenReturn(true);
        when(negatedResult.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 25\n");

        SpecCheckResult allPass = mock(SpecCheckResult.class);
        when(allPass.isPassed()).thenReturn(true);
        NusmvResult verifyPass = mock(NusmvResult.class);
        when(verifyPass.isSuccess()).thenReturn(true);
        when(verifyPass.getSpecResults()).thenReturn(List.of(allPass));

        // UNSAT result for refinement (¬ρ universally true in distance range)
        SpecCheckResult unsatPass = mock(SpecCheckResult.class);
        when(unsatPass.isPassed()).thenReturn(true);
        NusmvResult unsatResult = mock(NusmvResult.class);
        when(unsatResult.isSuccess()).thenReturn(true);
        when(unsatResult.getSpecResults()).thenReturn(List.of(unsatPass));

        // 1. negatedResult → discovery (param=25)
        // 2. verifyPass    → initial forward-verify
        // 3. unsatResult   → refinement UNSAT → break, keep initial value 25
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenReturn(negatedResult)
                .thenReturn(verifyPass)
                .thenReturn(unsatResult);

        List<FixSuggestionDto> suggestions = strategy.tryFix(
                ctx(List.of(fault), List.of(rule), List.of(spec), buildDeviceMap())).suggestions();

        assertEquals(1, suggestions.size());
        FixSuggestionDto suggestion = suggestions.get(0);
        assertEquals("25", suggestion.getParameterAdjustments().get(0).getNewValue());
    }

    @Test
    void tryFix_refine_solverErrorEndsRefinementAndKeepsTheAcceptedValue() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult = mock(NusmvResult.class);
        when(negatedResult.isSuccess()).thenReturn(true);
        when(negatedResult.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 25\n");

        SpecCheckResult allPass = mock(SpecCheckResult.class);
        when(allPass.isPassed()).thenReturn(true);
        NusmvResult verifyPass = mock(NusmvResult.class);
        when(verifyPass.isSuccess()).thenReturn(true);
        when(verifyPass.getSpecResults()).thenReturn(List.of(allPass));

        NusmvResult errorResult = mock(NusmvResult.class);
        when(errorResult.isSuccess()).thenReturn(false);
        when(errorResult.getErrorMessage()).thenReturn("NuSMV crashed");

        // 1. negatedResult → discovery (param=25)
        // 2. verifyPass    → initial forward-verify
        // 3. errorResult   → refinement: the identical model would fail again, so refinement ends
        // 4. errorResult   → the next joint solve fails too, ending the search
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenReturn(negatedResult)
                .thenReturn(verifyPass)
                .thenReturn(errorResult);

        List<FixSuggestionDto> suggestions = strategy.tryFix(
                ctx(List.of(fault), List.of(rule), List.of(spec), buildDeviceMap())).suggestions();

        assertEquals(1, suggestions.size());
        FixSuggestionDto suggestion = suggestions.get(0);
        assertEquals("25", suggestion.getParameterAdjustments().get(0).getNewValue());
        verify(nusmvExecutor, times(4)).executeRepairSearch(any(File.class), anyLong());
    }

    @Test
    void tryFix_refine_busySolvesAreRetriedWithinTheRefinementBudget() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        // Refinement from 25 toward the original 30: three refused permits, then a closer value.
        List<NusmvResult> executions = List.of(
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 25\n"),
                solverResult(true, ""),
                NusmvResult.busy("busy"),
                NusmvResult.busy("busy"),
                NusmvResult.busy("busy"),
                solverResult(false, "  -> State: 1.1 <-\n    param_r0_c0 = 29\n"),
                solverResult(true, ""),
                solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        List<FixSuggestionDto> suggestions = strategy.tryFix(
                ctx(List.of(fault), List.of(rule), List.of(spec), buildDeviceMap())).suggestions();

        assertEquals(1, suggestions.size());
        assertEquals("29", suggestions.get(0).getParameterAdjustments().get(0).getNewValue());
    }

    @Test
    void tryFix_refine_outOfBoundsCandidate_treatedAsError() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult = mock(NusmvResult.class);
        when(negatedResult.isSuccess()).thenReturn(true);
        when(negatedResult.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 25\n");

        // Out-of-bounds: NuSMV returns 999, but refined range is [26,34]
        NusmvResult outOfBoundsResult = mock(NusmvResult.class);
        when(outOfBoundsResult.isSuccess()).thenReturn(true);
        when(outOfBoundsResult.getSpecResults()).thenReturn(List.of(failing));
        when(outOfBoundsResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 999\n");

        SpecCheckResult unsatPass = mock(SpecCheckResult.class);
        when(unsatPass.isPassed()).thenReturn(true);
        NusmvResult unsatResult = mock(NusmvResult.class);
        when(unsatResult.isSuccess()).thenReturn(true);
        when(unsatResult.getSpecResults()).thenReturn(List.of(unsatPass));

        SpecCheckResult allPass = mock(SpecCheckResult.class);
        when(allPass.isPassed()).thenReturn(true);
        NusmvResult verifyPass = mock(NusmvResult.class);
        when(verifyPass.isSuccess()).thenReturn(true);
        when(verifyPass.getSpecResults()).thenReturn(List.of(allPass));

        // 1. negatedResult      → discovery (param=25, original=30, distance=5)
        // 2. verifyPass         → initial forward-verify
        // 3. outOfBoundsResult  → refinement: param=999 outside [26,34] → ERROR
        // 4. unsatResult        → refinement: UNSAT → break
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenReturn(negatedResult)
                .thenReturn(verifyPass)
                .thenReturn(outOfBoundsResult)
                .thenReturn(unsatResult);

        List<FixSuggestionDto> suggestions = strategy.tryFix(
                ctx(List.of(fault), List.of(rule), List.of(spec), buildDeviceMap())).suggestions();

        assertEquals(1, suggestions.size());
        FixSuggestionDto suggestion = suggestions.get(0);
        // Out-of-bounds treated as error, then UNSAT → keeps initial value 25
        assertEquals("25", suggestion.getParameterAdjustments().get(0).getNewValue());
    }

    @Test
    void tryFix_refine_duplicateCandidate_skipsForwardVerify() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult25 = mock(NusmvResult.class);
        when(negatedResult25.isSuccess()).thenReturn(true);
        when(negatedResult25.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult25.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 25\n");

        NusmvResult negatedResult28 = mock(NusmvResult.class);
        when(negatedResult28.isSuccess()).thenReturn(true);
        when(negatedResult28.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult28.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 28\n");

        SpecCheckResult allPass = mock(SpecCheckResult.class);
        when(allPass.isPassed()).thenReturn(true);
        NusmvResult verifyPass = mock(NusmvResult.class);
        when(verifyPass.isSuccess()).thenReturn(true);
        when(verifyPass.getSpecResults()).thenReturn(List.of(allPass));

        SpecCheckResult specFail = mock(SpecCheckResult.class);
        when(specFail.isPassed()).thenReturn(false);
        NusmvResult verifyFail = mock(NusmvResult.class);
        when(verifyFail.isSuccess()).thenReturn(true);
        when(verifyFail.getSpecResults()).thenReturn(List.of(specFail));

        SpecCheckResult unsatPass = mock(SpecCheckResult.class);
        when(unsatPass.isPassed()).thenReturn(true);
        NusmvResult unsatResult = mock(NusmvResult.class);
        when(unsatResult.isSuccess()).thenReturn(true);
        when(unsatResult.getSpecResults()).thenReturn(List.of(unsatPass));

        // 1. negatedResult25  → discovery (param=25)
        // 2. verifyPass       → initial forward-verify
        // 3. negatedResult28  → refinement candidate=28
        // 4. verifyFail       → candidate 28 forward-verify fails
        // 5. negatedResult28  → duplicate 28 → seen.contains(28)=true, skip forward-verify
        // 6. unsatResult      → UNSAT → break
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenReturn(negatedResult25)
                .thenReturn(verifyPass)
                .thenReturn(negatedResult28)
                .thenReturn(verifyFail)
                .thenReturn(negatedResult28)
                .thenReturn(unsatResult);

        List<FixSuggestionDto> suggestions = strategy.tryFix(
                ctx(List.of(fault), List.of(rule), List.of(spec), buildDeviceMap())).suggestions();

        assertEquals(1, suggestions.size());
        FixSuggestionDto suggestion = suggestions.get(0);
        assertEquals("25", suggestion.getParameterAdjustments().get(0).getNewValue());
        // generate() = forward-verify calls: initial + candidate 28 (once) = 2
        // If dedup failed, would be 3 (candidate 28 verified twice)
        verify(smvGenerator, times(2)).generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class));
    }

    @Test
    void tryFix_nusmvFailure_endsTheSearchWithoutRepeatingTheIdenticalSolve() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        NusmvResult failedResult = mock(NusmvResult.class);
        when(failedResult.isSuccess()).thenReturn(false);
        when(failedResult.getErrorMessage()).thenReturn("NuSMV crashed");
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(failedResult);

        // Budget left for two more solves: a failure must not spend it re-running the same model.
        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex()))
                .allRules(List.of(rule))
                .devices(List.of())
                .specs(List.of(spec))
                .deviceSmvMap(buildDeviceMap())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(3)
                .build();

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);
        assertTrue(outcome.suggestions().isEmpty());
        assertFalse(outcome.alternativesComplete(), "a failed solve proves nothing");
        verify(nusmvExecutor, times(1)).executeRepairSearch(any(File.class), anyLong());
        assertNotNull(context.strategySolverFailure("parameter"));
        assertEquals(1, context.strategySearchProgress("parameter").attemptsUsed());
    }

    @Test
    void tryFix_singleThresholdSolverFailureThenJointUnsat_clearsFailureOutcome() throws Exception {
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class))).thenAnswer(invocation -> createGenResult());
        NusmvResult failed = mock(NusmvResult.class);
        when(failed.isSuccess()).thenReturn(false);
        when(failed.getErrorMessage()).thenReturn("single-threshold solve failed");
        // r0_c0 alone fails, r0_c1 alone is UNSAT, then the joint solve is UNSAT.
        List<NusmvResult> executions = List.of(failed, solverResult(true, ""), solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixContext context = twoThresholdContext(4);

        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        assertNull(context.strategySolverFailure("parameter"),
                "a conclusive joint UNSAT covers the threshold whose single solve failed");
        assertEquals("NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", context.strategyNoResult("parameter").status());
        assertEquals(3, context.strategySearchProgress("parameter").attemptsUsed());
    }

    @Test
    void tryFix_jointSolverFailure_endsTheSearchWithoutRepeatingTheIdenticalSolve() throws Exception {
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class))).thenAnswer(invocation -> createGenResult());
        NusmvResult failed = mock(NusmvResult.class);
        when(failed.isSuccess()).thenReturn(false);
        when(failed.getErrorMessage()).thenReturn("NuSMV execution timed out after 120000ms");
        // Both single-threshold solves are UNSAT; the joint solve then fails, with budget left over.
        List<NusmvResult> executions = List.of(solverResult(true, ""), solverResult(true, ""), failed);
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixContext context = twoThresholdContext(10);

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);
        assertTrue(outcome.suggestions().isEmpty());
        assertFalse(outcome.alternativesComplete());
        verify(nusmvExecutor, times(3)).executeRepairSearch(any(File.class), anyLong());
        assertNotNull(context.strategySolverFailure("parameter"));
        assertNull(context.strategyNoResult("parameter"),
                "a failed solve is not a completed search and must not read as budget exhaustion");
    }

    @Test
    void tryFix_busyJointSolve_isRetriedRatherThanEndingTheSearch() throws Exception {
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class))).thenAnswer(invocation -> createGenResult());
        // Both single-threshold solves are UNSAT; the concurrency cap then refuses the joint solve once.
        List<NusmvResult> executions = List.of(solverResult(true, ""), solverResult(true, ""),
                NusmvResult.busy("NuSMV execution is busy, please retry later"), solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixContext context = twoThresholdContext(10);

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);
        assertTrue(outcome.alternativesComplete(), "the retried joint solve proved that no repair exists");
        verify(nusmvExecutor, times(4)).executeRepairSearch(any(File.class), anyLong());
        assertNull(context.strategySolverFailure("parameter"));
        assertEquals("NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", context.strategyNoResult("parameter").status());
    }

    @Test
    void tryFix_busySingleThresholdSolve_isRetriedRatherThanSkippingTheThreshold() throws Exception {
        ArgumentCaptor<ParameterizationConfig> configs = ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configs.capture(), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        List<NusmvResult> executions = List.of(NusmvResult.busy("NuSMV execution is busy, please retry later"),
                solverResult(true, ""), solverResult(true, ""), solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));

        FixContext context = twoThresholdContext(10);

        assertTrue(strategy.tryFix(context).alternativesComplete());
        assertEquals(List.of(java.util.Set.of("r0_c0"), java.util.Set.of("r0_c0"), java.util.Set.of("r0_c1"),
                        java.util.Set.of("r0_c0", "r0_c1")),
                configs.getAllValues().stream()
                        .map(config -> config.getParameterizedThresholds().keySet())
                        .toList());
        assertNull(context.strategySolverFailure("parameter"));
    }

    /**
     * A preferred range that excludes a threshold's original value obliges that threshold to move. A
     * single-threshold solve holds it at the original, so phase 1 would list repairs outside the range
     * the user chose; the joint solve is the only one that respects every range.
     */
    @Test
    void tryFix_preferredRangeExcludingAnOriginal_searchesOnlyJointly() throws Exception {
        ArgumentCaptor<ParameterizationConfig> configs = ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configs.capture(), any(SmvGenerator.TempModelContext.class)))
                .thenAnswer(invocation -> createGenResult());
        List<NusmvResult> executions = List.of(solverResult(true, ""));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(sequence(executions));
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");
        FixContext context = FixContext.builder()
                .faultRules(List.of(FaultRuleDto.builder().ruleIndex(0).build()))
                .repairRuleIndices(List.of(0))
                .allRules(List.of(twoThresholdRule())).devices(List.of()).specs(List.of(spec))
                .deviceSmvMap(buildDeviceMap()).violatedSpecIndex(0).userId(1L)
                .attackScenario(AttackScenarioDto.none()).maxAttempts(10)
                // r0_c0's original value 30 lies outside the chosen range.
                .preferredRanges(Map.of(PreferredRangeSelection.targetIdFor(0, 0), new PreferredRange(35, 45)))
                .build();

        assertTrue(strategy.tryFix(context).alternativesComplete());
        assertEquals(List.of(java.util.Set.of("r0_c0", "r0_c1")),
                configs.getAllValues().stream()
                        .map(config -> config.getParameterizedThresholds().keySet())
                        .toList());
    }

    // ---- P3: preferred range tests ----

    @Test
    void tryFix_preferredRange_narrowsBounds() throws Exception {
        // Use maxRefineAttempts=0 to skip refinement and test bounds narrowing only
        FixConfig noRefineConfig = new FixConfig();
        noRefineConfig.setMaxRefineAttempts(0);
        ParameterAdjustStrategy noRefineStrategy =
                new ParameterAdjustStrategy(smvGenerator, nusmvExecutor, noRefineConfig);

        // Template bounds [0,50], preferred [20,30] → intersection [20,30]
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        // Capture the ParameterizationConfig to verify bounds
        ArgumentCaptor<ParameterizationConfig> configCaptor =
                ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configCaptor.capture(), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult = mock(NusmvResult.class);
        when(negatedResult.isSuccess()).thenReturn(true);
        when(negatedResult.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    param_r0_c0 = 25\n");

        SmvGenerator.GenerateResult verifyGenResult = createGenResult();
        when(smvGenerator.generate(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class)))
                .thenReturn(verifyGenResult);

        SpecCheckResult allPass = mock(SpecCheckResult.class);
        when(allPass.isPassed()).thenReturn(true);
        NusmvResult verifyResult = mock(NusmvResult.class);
        when(verifyResult.isSuccess()).thenReturn(true);
        when(verifyResult.getSpecResults()).thenReturn(List.of(allPass));

        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenReturn(negatedResult)
                .thenReturn(verifyResult);

        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex()))
                .allRules(List.of(rule))
                .devices(List.of())
                .specs(List.of(spec))
                .deviceSmvMap(buildDeviceMap())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(20)
                .preferredRanges(Map.of(PreferredRangeSelection.targetIdFor(0, 0), new PreferredRange(20, 30)))
                .build();

        List<FixSuggestionDto> suggestions = noRefineStrategy.tryFix(context).suggestions();
        assertEquals(1, suggestions.size());
        FixSuggestionDto suggestion = suggestions.get(0);

        // Verify the ParamInfo bounds were narrowed to [20,30]
        ParameterizationConfig captured = configCaptor.getValue();
        ParameterizationConfig.ParamInfo paramInfo = captured.getParameterizedThresholds().get("r0_c0");
        assertNotNull(paramInfo);
        assertEquals(20, paramInfo.getLowerBound());
        assertEquals(30, paramInfo.getUpperBound());

        // Verify the adjustment also has narrowed bounds
        assertEquals(1, suggestion.getParameterAdjustments().size());
        ParameterAdjustment adj = suggestion.getParameterAdjustments().get(0);
        assertEquals(20, adj.getLowerBound());
        assertEquals(30, adj.getUpperBound());
    }

    @Test
    void tryFix_preferredRange_noIntersection_skipsParam() {
        // Template bounds [0,50], preferred [60,70] → no intersection, skip
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");

        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex()))
                .allRules(List.of(rule))
                .devices(List.of())
                .specs(List.of(spec))
                .deviceSmvMap(buildDeviceMap())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(20)
                .preferredRanges(Map.of(PreferredRangeSelection.targetIdFor(0, 0), new PreferredRange(60, 70)))
                .build();

        // The only parameterizable condition was skipped, so there is nothing to search.
        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
    }

    @Test
    void tryFix_mixedPreferredRanges_skipsOnlyTheDisjointTarget() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder().deviceName("sensor_1")
                                .attribute("temperature").targetType("variable")
                                .relation(">").value("30").build(),
                        RuleDto.Condition.builder().deviceName("sensor_1")
                                .attribute("temperature").targetType("variable")
                                .relation("<").value("40").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");
        long traceId = 77L;
        String disjointTarget = PreferredRangeSelection.targetIdFor(traceId, 0, 0);
        String usableTarget = PreferredRangeSelection.targetIdFor(traceId, 0, 1);

        ArgumentCaptor<ParameterizationConfig> configCaptor =
                ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterized(anyLong(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configCaptor.capture(),
                any(SmvGenerator.TempModelContext.class))).thenReturn(createGenResult());
        SpecCheckResult unsat = mock(SpecCheckResult.class);
        when(unsat.isPassed()).thenReturn(true);
        NusmvResult result = mock(NusmvResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.getSpecResults()).thenReturn(List.of(unsat));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(result);

        FixContext context = FixContext.builder()
                .traceId(traceId).faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex())).allRules(List.of(rule))
                .devices(List.of()).specs(List.of(spec)).deviceSmvMap(buildDeviceMap())
                .violatedSpecIndex(0).userId(1L).attackScenario(AttackScenarioDto.none()).maxAttempts(1)
                .preferredRanges(Map.of(
                        disjointTarget, new PreferredRange(60, 70),
                        usableTarget, new PreferredRange(35, 45)))
                .build();

        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        assertEquals(2, context.parameterTargetsSnapshot().size());
        // A range disjoint from the device's own limits was never searched, so it must not be
        // reported as honoured; it stays unmatched and the user is told why.
        assertEquals(java.util.Set.of(usableTarget),
                context.matchedPreferredRangeTargetIdsSnapshot());
        assertTrue(context.diagnosticsSnapshot().stream()
                        .anyMatch(d -> d.contains("60-70") && d.contains("left unchanged")),
                context.diagnosticsSnapshot()::toString);
        assertEquals(java.util.Set.of("r0_c1"),
                configCaptor.getValue().getParameterizedThresholds().keySet());
        ParameterizationConfig.ParamInfo usable =
                configCaptor.getValue().getParameterizedThresholds().get("r0_c1");
        assertEquals(35, usable.getLowerBound());
        assertEquals(45, usable.getUpperBound());
    }
}
