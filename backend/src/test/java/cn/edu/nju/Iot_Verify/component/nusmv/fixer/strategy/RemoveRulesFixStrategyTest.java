package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.NusmvResult;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackPointDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/**
 * Tests for RemoveRulesFixStrategy: verifies combination search, fail-closed re-verification,
 * maxAttempts budget, and lazy DFS behavior.
 */
@ExtendWith(MockitoExtension.class)
class RemoveRulesFixStrategyTest {

    @Mock
    private SmvGenerator smvGenerator;
    @Mock
    private NusmvExecutor nusmvExecutor;

    private RemoveRulesFixStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new RemoveRulesFixStrategy(smvGenerator, nusmvExecutor);
    }

    private SmvGenerator.GenerateResult createGenResult() throws IOException {
        File tempDir = Files.createTempDirectory("smv-test").toFile();
        File smvFile = new File(tempDir, "test.smv");
        smvFile.createNewFile();
        return new SmvGenerator.GenerateResult(smvFile, Map.of());
    }

    private FixContext ctx(List<FaultRuleDto> faultRules, List<RuleDto> allRules, int maxAttempts) {
        return ctx(faultRules, allRules, maxAttempts, AttackScenarioDto.none());
    }

    private FixContext ctx(List<FaultRuleDto> faultRules, List<RuleDto> allRules, int maxAttempts,
                           AttackScenarioDto attackScenario) {
        return FixContext.builder()
                .faultRules(faultRules)
                .repairRuleIndices(faultRules == null ? List.of()
                        : faultRules.stream().map(FaultRuleDto::getRuleIndex).toList())
                .allRules(allRules)
                .devices(List.of())
                .environmentVariables(List.of())
                .specs(List.of())
                .deviceSmvMap(Map.of())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(attackScenario)
                .enablePrivacy(false)
                .maxAttempts(maxAttempts)
                .build();
    }

    private void mockGenerateReturns(SmvGenerator.GenerateResult genResult) throws IOException {
        when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenReturn(genResult);
    }

    @Test
    void tryFix_nullFaultRules_findsNothing() {
        assertTrue(strategy.tryFix(ctx(null, List.of(), 20)).suggestions().isEmpty());
    }

    @Test
    void tryFix_emptyFaultRules_findsNothing() {
        assertTrue(strategy.tryFix(ctx(List.of(), List.of(), 20)).suggestions().isEmpty());
    }

    @Test
    void tryFix_singleFaultRule_disableFixesViolation() throws Exception {
        mockGenerateReturns(createGenResult());

        SpecCheckResult passing = mock(SpecCheckResult.class);
        when(passing.isPassed()).thenReturn(true);
        NusmvResult nusmvResult = mock(NusmvResult.class);
        when(nusmvResult.isSuccess()).thenReturn(true);
        when(nusmvResult.getSpecResults()).thenReturn(List.of(passing));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(nusmvResult);

        List<FaultRuleDto> faultRules = List.of(
                FaultRuleDto.builder().ruleIndex(1).build());
        List<RuleDto> allRules = List.of(
                RuleDto.builder().ruleString("rule0").build(),
                RuleDto.builder().ruleString("rule1 (fault)").build());

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(ctx(faultRules, allRules, 20));

        assertEquals(1, outcome.suggestions().size());
        assertTrue(outcome.alternativesComplete(), "the only candidate removal was checked");
        FixSuggestionDto suggestion = outcome.suggestions().get(0);
        assertEquals("remove", suggestion.getStrategy());
        assertEquals(List.of(1), suggestion.getRemovedRuleIndices());
        assertEquals(List.of("rule1 (fault)"), suggestion.getRemovedRuleDescriptions());
        assertFalse(suggestion.getDescription().contains("'rule1 (fault)'"));
    }

    @Test
    void tryFix_dormantRuleInRepairScopeCanJoinTheMinimalRemoval() throws Exception {
        mockGenerateReturns(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult failedVerification = mock(NusmvResult.class);
        when(failedVerification.isSuccess()).thenReturn(true);
        when(failedVerification.getSpecResults()).thenReturn(List.of(failing));
        SpecCheckResult passing = mock(SpecCheckResult.class);
        when(passing.isPassed()).thenReturn(true);
        NusmvResult passedVerification = mock(NusmvResult.class);
        when(passedVerification.isSuccess()).thenReturn(true);
        when(passedVerification.getSpecResults()).thenReturn(List.of(passing));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenReturn(failedVerification, failedVerification, passedVerification);

        DeviceSmvData sensor = new DeviceSmvData();
        sensor.setVarName("sensor_1");
        DeviceSmvData heater = new DeviceSmvData();
        heater.setVarName("heater_1");
        List<RuleDto> allRules = List.of(
                RuleDto.builder().ruleString("executed unsafe rule")
                        .conditions(List.of(RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature")
                                .targetType("variable").relation(">=").value("28").build()))
                        .command(RuleDto.Command.builder()
                                .deviceName("heater_1").action("heat").build())
                        .build(),
                RuleDto.builder().ruleString("dormant backup rule")
                        .conditions(List.of(RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature")
                                .targetType("variable").relation(">=").value("29").build()))
                        .command(RuleDto.Command.builder()
                                .deviceName("heater_1").action("heat").build())
                        .build());
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("heater_1");
        condition.setTargetType("mode");
        condition.setKey("Mode");
        condition.setRelation("=");
        condition.setValue("heat");
        SpecificationDto spec = new SpecificationDto();
        spec.setId("safety");
        spec.setTemplateId("3");
        spec.setAConditions(List.of(condition));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());
        FixContext context = FixContext.builder()
                .faultRules(List.of(FaultRuleDto.builder().ruleIndex(0).build()))
                // RuleFixer.repairScope puts the dormant rule that shares the property's cone in scope.
                .repairRuleIndices(List.of(0, 1))
                .allRules(allRules)
                .devices(List.of())
                .environmentVariables(List.of())
                .specs(List.of(spec))
                .deviceSmvMap(Map.of("sensor_1", sensor, "heater_1", heater))
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .maxAttempts(3)
                .build();

        List<FixSuggestionDto> suggestions = strategy.tryFix(context).suggestions();

        assertEquals(1, suggestions.size());
        assertEquals(List.of(0, 1), suggestions.get(0).getRemovedRuleIndices());
        verify(nusmvExecutor, times(3)).executeRepairSearch(any(File.class), anyLong());
    }

    @Test
    void tryFix_rejectsRemovalOfExplicitlyAttackedAutomationLink() {
        List<FaultRuleDto> faultRules = List.of(FaultRuleDto.builder().ruleIndex(0).build());
        List<RuleDto> allRules = List.of(
                RuleDto.builder().id(7L).ruleString("selected attacked link").build());
        FixContext context = ctx(faultRules, allRules, 20,
                AttackScenarioDto.exactPoints(List.of(AttackPointDto.automationLink(7L))));

        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        verifyNoInteractions(smvGenerator, nusmvExecutor);
        assertTrue(context.diagnosticsSnapshot().stream()
                .anyMatch(message -> message.contains("selected automation-link attack point")));
    }

    @Test
    void tryFix_rejectsRemovalThatEliminatesExplicitDeviceAttackPoint() {
        DeviceSmvData light = new DeviceSmvData();
        light.setVarName("light_1");
        List<FaultRuleDto> faultRules = List.of(FaultRuleDto.builder().ruleIndex(0).build());
        List<RuleDto> allRules = List.of(RuleDto.builder()
                .id(7L)
                .ruleString("selected attacked actuator")
                .command(RuleDto.Command.builder().deviceName("light_1").action("on").build())
                .build());
        FixContext context = FixContext.builder()
                .faultRules(faultRules)
                .repairRuleIndices(faultRules == null ? List.of()
                        : faultRules.stream().map(FaultRuleDto::getRuleIndex).toList())
                .allRules(allRules)
                .devices(List.of())
                .environmentVariables(List.of())
                .specs(List.of())
                .deviceSmvMap(Map.of("light_1", light))
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.exactPoints(List.of(
                        AttackPointDto.device("light_1"))))
                .maxAttempts(20)
                .build();

        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        verifyNoInteractions(smvGenerator, nusmvExecutor);
        assertTrue(context.diagnosticsSnapshot().stream()
                .anyMatch(message -> message.contains("device attack point")));
    }

    @Test
    void tryFix_failClosedOnEmptySpecResults() throws Exception {
        mockGenerateReturns(createGenResult());

        NusmvResult nusmvResult = mock(NusmvResult.class);
        when(nusmvResult.isSuccess()).thenReturn(true);
        when(nusmvResult.getSpecResults()).thenReturn(List.of()); // empty!
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(nusmvResult);

        List<FaultRuleDto> faultRules = List.of(FaultRuleDto.builder().ruleIndex(0).build());
        List<RuleDto> allRules = List.of(RuleDto.builder().ruleString("rule0").build());

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(ctx(faultRules, allRules, 20));

        // Empty specResults → fail-closed → no fix confirmed, and the unchecked removal keeps the
        // search from claiming that none exists.
        assertTrue(outcome.suggestions().isEmpty());
        assertFalse(outcome.alternativesComplete());
    }

    @Test
    void tryFix_rejectsCandidateWhenForwardModelDisabledAnyRule() throws Exception {
        SmvGenerator.GenerateResult incomplete = createGenResult();
        incomplete = new SmvGenerator.GenerateResult(
                incomplete.smvFile(), Map.of(),
                List.of("Generation warning [rule-disabled]: invalid rule"), 1, 0, List.of());
        mockGenerateReturns(incomplete);

        List<FaultRuleDto> faultRules = List.of(FaultRuleDto.builder().ruleIndex(0).build());
        List<RuleDto> allRules = List.of(RuleDto.builder().ruleString("rule0").build());
        FixContext context = ctx(faultRules, allRules, 20);

        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        verify(nusmvExecutor, never()).executeRepairSearch(any(File.class), anyLong());
        assertTrue(context.diagnosticsSnapshot().stream().anyMatch(message -> message.contains("incomplete model")));
    }

    @Test
    void tryFix_respectsMaxAttemptsBudget() throws Exception {
        mockGenerateReturns(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult nusmvResult = mock(NusmvResult.class);
        when(nusmvResult.isSuccess()).thenReturn(true);
        when(nusmvResult.getSpecResults()).thenReturn(List.of(failing));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(nusmvResult);

        // 5 fault rules → many possible combinations, but maxAttempts=3 should cap it
        List<FaultRuleDto> faultRules = List.of(
                FaultRuleDto.builder().ruleIndex(0).build(),
                FaultRuleDto.builder().ruleIndex(1).build(),
                FaultRuleDto.builder().ruleIndex(2).build(),
                FaultRuleDto.builder().ruleIndex(3).build(),
                FaultRuleDto.builder().ruleIndex(4).build());
        List<RuleDto> allRules = List.of(
                RuleDto.builder().command(RuleDto.Command.builder().deviceName("d0").action("a").build()).build(),
                RuleDto.builder().command(RuleDto.Command.builder().deviceName("d1").action("a").build()).build(),
                RuleDto.builder().command(RuleDto.Command.builder().deviceName("d2").action("a").build()).build(),
                RuleDto.builder().command(RuleDto.Command.builder().deviceName("d3").action("a").build()).build(),
                RuleDto.builder().command(RuleDto.Command.builder().deviceName("d4").action("a").build()).build());

        FixContext context = ctx(faultRules, allRules, 3);
        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        verify(nusmvExecutor, atMost(3)).executeRepairSearch(any(File.class), anyLong());
        assertEquals(3, context.strategySearchProgress("remove").attemptsUsed());
        assertEquals("SEARCH_BUDGET_EXHAUSTED", context.strategyNoResult("remove").status());
    }

    @Test
    void tryFix_invalidMaxAttemptsDefaultsTo20() throws Exception {
        mockGenerateReturns(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult nusmvResult = mock(NusmvResult.class);
        when(nusmvResult.isSuccess()).thenReturn(true);
        when(nusmvResult.getSpecResults()).thenReturn(List.of(failing));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(nusmvResult);

        List<FaultRuleDto> faultRules = List.of(FaultRuleDto.builder().ruleIndex(0).build());
        List<RuleDto> allRules = List.of(RuleDto.builder().build());

        // maxAttempts=0 should be corrected to 20
        FixContext context = ctx(faultRules, allRules, 0);
        strategy.tryFix(context);

        // Should still execute (defaulted to 20, but only 1 combination exists)
        verify(nusmvExecutor, times(1)).executeRepairSearch(any(File.class), anyLong());
        assertNull(context.strategyNoResult("remove"),
                "checking the complete one-candidate space is not budget exhaustion");
    }

    @Test
    void tryFix_nusmvExecutionFailure_treatedAsNoFix() throws Exception {
        mockGenerateReturns(createGenResult());

        NusmvResult failedResult = mock(NusmvResult.class);
        when(failedResult.isSuccess()).thenReturn(false);
        when(failedResult.getErrorMessage()).thenReturn("NuSMV crashed");
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(failedResult);

        List<FaultRuleDto> faultRules = List.of(FaultRuleDto.builder().ruleIndex(0).build());
        List<RuleDto> allRules = List.of(RuleDto.builder().build());

        FixContext context = ctx(faultRules, allRules, 20);
        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        assertNotNull(context.strategySolverFailure("remove"));
    }

    @Test
    void tryFix_smvGeneratorReturnsNull_treatedAsNoFix() throws Exception {
        mockGenerateReturns(null);

        List<FaultRuleDto> faultRules = List.of(FaultRuleDto.builder().ruleIndex(0).build());
        List<RuleDto> allRules = List.of(RuleDto.builder().build());

        assertTrue(strategy.tryFix(ctx(faultRules, allRules, 20)).suggestions().isEmpty());
    }

    @Test
    void tryFix_listsEveryMinimalRemovalAndSkipsRemovalsContainingOne() throws Exception {
        mockGenerateReturns(targetModel());
        // Lexicographic order: {0} {1} {2} {3}, then the pairs not containing {0}: {1,2} {1,3} {2,3}.
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(
                targetVerdict(true), targetVerdict(false), targetVerdict(false), targetVerdict(false),
                targetVerdict(true), targetVerdict(false), targetVerdict(false));

        FixContext context = targetedCtx(distinctRules(4), 20);
        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);

        assertEquals(List.of(List.of(0), List.of(1, 2)), outcome.suggestions().stream()
                .map(FixSuggestionDto::getRemovedRuleIndices).toList());
        // {0,1}, {0,2}, {0,3} and every triple containing a listed removal were never checked.
        verify(nusmvExecutor, times(7)).executeRepairSearch(any(File.class), anyLong());
        assertTrue(outcome.alternativesComplete(), "every other removal was checked or contains a listed one");
        assertNull(context.strategyNoResult("remove"));
    }

    @Test
    void tryFix_everyRemovalRejectedIsAProofThatRemovalCannotRepairIt() throws Exception {
        mockGenerateReturns(targetModel());
        // {0}, {1} and {0,1} all leave the target violated.
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(targetVerdict(false));

        FixContext context = targetedCtx(distinctRules(2), 20);
        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);

        assertTrue(outcome.suggestions().isEmpty());
        verify(nusmvExecutor, times(3)).executeRepairSearch(any(File.class), anyLong());
        assertTrue(outcome.alternativesComplete());
        assertEquals("ALL_CANDIDATES_REJECTED", context.strategyNoResult("remove").status());
    }

    @Test
    void tryFix_attemptBudgetEndingTheSearchLeavesTheListingIncomplete() throws Exception {
        mockGenerateReturns(targetModel());
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(targetVerdict(true));

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(targetedCtx(distinctRules(3), 2));

        assertEquals(List.of(List.of(0), List.of(1)), outcome.suggestions().stream()
                .map(FixSuggestionDto::getRemovedRuleIndices).toList());
        assertFalse(outcome.alternativesComplete(), "removing rule 2 alone was never checked");
    }

    @Test
    void tryFix_stopsAtTheListingLimitWithoutClaimingCompleteness() throws Exception {
        mockGenerateReturns(targetModel());
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(targetVerdict(true));

        FixStrategy.StrategyOutcome outcome =
                strategy.tryFix(targetedCtx(distinctRules(FixAlternatives.LIMIT + 1), 20));

        assertEquals(FixAlternatives.LIMIT, outcome.suggestions().size());
        assertFalse(outcome.alternativesComplete());
        verify(nusmvExecutor, times(FixAlternatives.LIMIT)).executeRepairSearch(any(File.class), anyLong());
    }

    /** Every rule in the repair scope, violating the one specification the verdicts are about. */
    private FixContext targetedCtx(List<RuleDto> allRules, int maxAttempts) {
        SpecificationDto target = new SpecificationDto();
        target.setId("target");
        target.setTemplateId("1");
        return FixContext.builder()
                .faultRules(List.of(FaultRuleDto.builder().ruleIndex(0).build()))
                .repairRuleIndices(java.util.stream.IntStream.range(0, allRules.size()).boxed().toList())
                .allRules(allRules)
                .devices(List.of())
                .environmentVariables(List.of())
                .specs(List.of(target))
                .deviceSmvMap(Map.of())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .maxAttempts(maxAttempts)
                .build();
    }

    /** Rules with distinct commands, so no removal leaves two identical rules behind. */
    private static List<RuleDto> distinctRules(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> RuleDto.builder()
                        .ruleString("rule" + index)
                        .command(RuleDto.Command.builder().deviceName("d" + index).action("a").build())
                        .build())
                .toList();
    }

    /** A forward-verification model whose one specification is the target, so its verdict is attributable. */
    private SmvGenerator.GenerateResult targetModel() throws IOException {
        return new SmvGenerator.GenerateResult(createGenResult().smvFile(), Map.of(), List.of(), 0, 0,
                List.of(new SmvGenerationContext.EmittedSpec(null, "target", "CTLSPEC AG(safe)")));
    }

    private static NusmvResult targetVerdict(boolean passed) {
        return NusmvResult.success("", List.of(new SpecCheckResult("AG safe", passed, null)));
    }
}
