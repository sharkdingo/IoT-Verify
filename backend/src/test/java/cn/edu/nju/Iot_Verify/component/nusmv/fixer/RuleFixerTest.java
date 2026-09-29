package cn.edu.nju.Iot_Verify.component.nusmv.fixer;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize.FaultLocalizer;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixStrategyAttemptDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterTarget;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for RuleFixer default strategy chain behavior (P0 regression).
 */
@ExtendWith(MockitoExtension.class)
class RuleFixerTest {

    @Mock private FaultLocalizer faultLocalizer;

    private FixConfig fixConfig() {
        FixConfig cfg = new FixConfig();
        cfg.setFixTimeoutMs(300_000);
        return cfg;
    }

    private FixStrategy mockStrategy(String name, boolean requiresSpec) {
        FixStrategy s = mock(FixStrategy.class);
        when(s.name()).thenReturn(name);
        lenient().when(s.requiresViolatedSpec()).thenReturn(requiresSpec);
        lenient().when(s.tryFix(any())).thenReturn(FixStrategy.StrategyOutcome.none());
        return s;
    }

    /**
     * When strategies=null, RuleFixer should use DEFAULT_STRATEGIES and try all three.
     * Precondition: valid violatedSpecId (index >= 0) and non-empty faultRules,
     * otherwise requiresViolatedSpec strategies get skipped.
     */
    @Test
    void fix_nullStrategies_usesDefaultAndTriesAllThree() {
        FixStrategy paramStrategy = mockStrategy("parameter", true);
        FixStrategy condStrategy = mockStrategy("condition", true);
        FixStrategy removeStrategy = mockStrategy("remove", false);

        RuleFixer fixer = new RuleFixer(faultLocalizer,
                List.of(paramStrategy, condStrategy, removeStrategy), fixConfig());

        // Setup: faultLocalizer returns non-empty fault rules
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        when(faultLocalizer.localize(any(), any(), any())).thenReturn(List.of(fault));

        // Setup: spec with id matching violatedSpecId
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");

        RuleDto rule = RuleDto.builder().build();

        fixer.fix(1L, "spec_0", List.of(), List.of(rule), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, null, 20, null);

        // All three strategies should have tryFix called
        verify(paramStrategy).tryFix(any(FixContext.class));
        verify(condStrategy).tryFix(any(FixContext.class));
        verify(removeStrategy).tryFix(any(FixContext.class));
    }

    @Test
    void fix_emptyStrategies_isRejectedInsteadOfUsingDefaultChain() {
        FixStrategy paramStrategy = mockStrategy("parameter", true);
        FixStrategy condStrategy = mockStrategy("condition", true);
        FixStrategy removeStrategy = mockStrategy("remove", false);

        RuleFixer fixer = new RuleFixer(faultLocalizer,
                List.of(paramStrategy, condStrategy, removeStrategy), fixConfig());

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");
        RuleDto rule = RuleDto.builder().build();

        assertThrows(IllegalArgumentException.class, () ->
                fixer.fix(1L, "spec_0", List.of(), List.of(rule), List.of(), List.of(spec),
                        Map.of(), 1L, AttackScenarioDto.none(), false, List.of(), 20, null));

        verify(paramStrategy, never()).tryFix(any(FixContext.class));
        verify(condStrategy, never()).tryFix(any(FixContext.class));
        verify(removeStrategy, never()).tryFix(any(FixContext.class));
    }

    @Test
    void fix_explicitRemoveOnly_onlyRemoveCalled() {
        FixStrategy paramStrategy = mockStrategy("parameter", true);
        FixStrategy condStrategy = mockStrategy("condition", true);
        FixStrategy removeStrategy = mockStrategy("remove", false);

        RuleFixer fixer = new RuleFixer(faultLocalizer,
                List.of(paramStrategy, condStrategy, removeStrategy), fixConfig());

        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        when(faultLocalizer.localize(any(), any(), any())).thenReturn(List.of(fault));

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");
        RuleDto rule = RuleDto.builder().build();

        fixer.fix(1L, "spec_0", List.of(), List.of(rule), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, List.of("remove"), 20, null);

        verify(paramStrategy, never()).tryFix(any());
        verify(condStrategy, never()).tryFix(any());
        verify(removeStrategy).tryFix(any(FixContext.class));
    }

    @Test
    void fix_numericSpecReferenceIsNotTreatedAsAnInternalListIndex() {
        FixStrategy paramStrategy = mockStrategy("parameter", true);
        RuleFixer fixer = new RuleFixer(faultLocalizer, List.of(paramStrategy), fixConfig());
        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));

        SpecificationDto spec = new SpecificationDto();
        spec.setId("real-spec-id");
        FixResultDto result = fixer.fix(1L, "0", List.of(),
                List.of(RuleDto.builder().build()), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, List.of("parameter"), 20, null);

        verify(paramStrategy, never()).tryFix(any());
        assertEquals("SKIPPED_NO_SPEC", result.getStrategyAttempts().get(0).getStatus());
        assertFalse(result.getSummary().contains("violatedSpecId"));
        assertFalse(result.getSummary().contains("'0'"));
        assertTrue(result.getSummary().contains("saved verification snapshot"));
    }

    @Test
    void fix_modelGenerationFailureIsNotReportedAsNoVerifiedSuggestion() {
        FixStrategy paramStrategy = mockStrategy("parameter", true);
        when(paramStrategy.tryFix(any())).thenAnswer(invocation -> {
            FixContext context = invocation.getArgument(0);
            context.recordStrategyGenerationFailure(
                    "parameter", "The counterexample initial state is incomplete.");
            return FixStrategy.StrategyOutcome.none();
        });
        RuleFixer fixer = new RuleFixer(faultLocalizer, List.of(paramStrategy), fixConfig());
        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");

        FixResultDto result = fixer.fix(1L, "spec_0", List.of(),
                List.of(RuleDto.builder().build()), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, List.of("parameter"), 20, null);

        assertEquals("FAILED_MODEL_GENERATION", result.getStrategyAttempts().get(0).getStatus());
        assertEquals("The counterexample initial state is incomplete.",
                result.getStrategyAttempts().get(0).getReason());
    }

    @Test
    void fix_solverFailureIsNotReportedAsNoVerifiedSuggestion() {
        FixStrategy parameterStrategy = mockStrategy("parameter", true);
        when(parameterStrategy.tryFix(any())).thenAnswer(invocation -> {
            FixContext context = invocation.getArgument(0);
            context.recordStrategySolverFailure("parameter", "NuSMV returned incomplete output.");
            return FixStrategy.StrategyOutcome.none();
        });
        RuleFixer fixer = new RuleFixer(faultLocalizer, List.of(parameterStrategy), fixConfig());
        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");

        FixResultDto result = fixer.fix(1L, "spec_0", List.of(),
                List.of(RuleDto.builder().build()), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, List.of("parameter"), 7, null);

        assertEquals("FAILED_SOLVER_EXECUTION", result.getStrategyAttempts().get(0).getStatus());
        assertEquals("NuSMV returned incomplete output.", result.getStrategyAttempts().get(0).getReason());
    }

    @Test
    void fix_everyStrategysAlternativesReachTheResultWithTheirOwnCompleteness() {
        FixStrategy parameterStrategy = mockStrategy("parameter", true);
        when(parameterStrategy.tryFix(any())).thenReturn(new FixStrategy.StrategyOutcome(List.of(
                FixSuggestionDto.builder().strategy("parameter").description("p1").build(),
                FixSuggestionDto.builder().strategy("parameter").description("p2").build()),
                true));
        FixStrategy conditionStrategy = mockStrategy("condition", true);
        FixStrategy removeStrategy = mockStrategy("remove", false);
        when(removeStrategy.tryFix(any())).thenReturn(new FixStrategy.StrategyOutcome(List.of(
                FixSuggestionDto.builder().strategy("remove").description("r1").build()),
                false));
        RuleFixer fixer = new RuleFixer(faultLocalizer,
                List.of(parameterStrategy, conditionStrategy, removeStrategy), fixConfig());
        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");

        FixResultDto result = fixer.fix(1L, "spec_0", List.of(),
                List.of(RuleDto.builder().build()), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, null, 20, null);

        assertEquals(List.of("p1", "p2", "r1"),
                result.getSuggestions().stream().map(FixSuggestionDto::getDescription).toList());
        assertTrue(result.isFixable());
        assertTrue(result.getSummary().startsWith("Found 3 fix suggestion(s)"), result.getSummary());
        List<FixStrategyAttemptDto> attempts = result.getStrategyAttempts();
        assertEquals(List.of("VERIFIED", "INCONCLUSIVE", "VERIFIED"),
                attempts.stream().map(FixStrategyAttemptDto::getStatus).toList());
        assertEquals(Boolean.TRUE, attempts.get(0).getAlternativesComplete());
        assertTrue(attempts.get(0).getReason().contains("No other minimal repair of this kind exists."));
        assertNull(attempts.get(1).getAlternativesComplete(), "only a verified attempt lists alternatives");
        assertEquals(Boolean.FALSE, attempts.get(2).getAlternativesComplete());
        assertTrue(attempts.get(2).getReason().contains("other repairs of this kind may exist"));
    }

    /** A strategy that used up its share of the time looking for alternatives leaves the rest to the next one. */
    @Test
    void fix_aStrategyThatSpentItsAlternativesShareDoesNotSkipTheNextStrategy() {
        FixStrategy parameterStrategy = mockStrategy("parameter", true);
        when(parameterStrategy.tryFix(any())).thenAnswer(invocation -> {
            FixContext context = invocation.getArgument(0);
            context.startAlternativesSearch();
            long giveUpAt = System.currentTimeMillis() + 10_000;
            while (!context.isExpired()) {
                if (System.currentTimeMillis() > giveUpAt) fail("the alternatives share never ended");
                Thread.sleep(10);
            }
            return new FixStrategy.StrategyOutcome(List.of(
                    FixSuggestionDto.builder().strategy("parameter").description("p1").build()),
                    false);
        });
        FixStrategy removeStrategy = mockStrategy("remove", false);
        FixConfig config = new FixConfig();
        // Two strategies to run: the first one's share is half, so the second starts with about 1.5 s.
        config.setFixTimeoutMs(3_000);
        RuleFixer fixer = new RuleFixer(faultLocalizer, List.of(parameterStrategy, removeStrategy), config);
        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");

        FixResultDto result = fixer.fix(1L, "spec_0", List.of(),
                List.of(RuleDto.builder().build()), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, List.of("parameter", "remove"), 20, null);

        verify(removeStrategy).tryFix(any(FixContext.class));
        assertNotEquals("SKIPPED_TIMEOUT", result.getStrategyAttempts().get(1).getStatus());
    }

    @Test
    void fix_budgetExhaustionRemainsDistinctFromCompleteNoResult() {
        FixStrategy removeStrategy = mockStrategy("remove", false);
        when(removeStrategy.tryFix(any())).thenAnswer(invocation -> {
            FixContext context = invocation.getArgument(0);
            context.initializeStrategySearch("remove", 2);
            context.addStrategyAttempts("remove", 2);
            context.recordStrategyNoResult("remove", "SEARCH_BUDGET_EXHAUSTED",
                    "Unchecked combinations remain.");
            return FixStrategy.StrategyOutcome.none();
        });
        RuleFixer fixer = new RuleFixer(faultLocalizer, List.of(removeStrategy), fixConfig());
        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));

        FixResultDto result = fixer.fix(1L, "UNKNOWN_SPEC", List.of(),
                List.of(RuleDto.builder().build()), List.of(), List.of(),
                Map.of(), 1L, AttackScenarioDto.none(), false, List.of("remove"), 2, null);

        assertEquals("SEARCH_BUDGET_EXHAUSTED", result.getStrategyAttempts().get(0).getStatus());
        assertEquals("Unchecked combinations remain.", result.getStrategyAttempts().get(0).getReason());
    }

    @Test
    void fix_preferredRangeMatchedWithoutSuggestionIsUsedAndTargetRemainsDiscoverable() {
        String targetId = PreferredRangeSelection.targetIdFor(1L, 0, 0);
        FixStrategy parameterStrategy = mockStrategy("parameter", true);
        when(parameterStrategy.tryFix(any())).thenAnswer(invocation -> {
            FixContext context = invocation.getArgument(0);
            context.registerParameterTarget(ParameterTarget.builder()
                    .targetId(targetId)
                    .attribute("temperature")
                    .relation(">")
                    .originalValue("30")
                    .lowerBound(0)
                    .upperBound(50)
                    .description("temperature target")
                    .build());
            context.markPreferredRangeTargetMatched(targetId);
            return FixStrategy.StrategyOutcome.none();
        });
        RuleFixer fixer = new RuleFixer(faultLocalizer, List.of(parameterStrategy), fixConfig());
        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");

        FixResultDto result = fixer.fix(1L, "spec_0", List.of(),
                List.of(RuleDto.builder().build()), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, List.of("parameter"), 20,
                Map.of(targetId, new PreferredRange(20, 40)));

        assertTrue(result.getUnusedPreferredRangeSelections().isEmpty());
        assertEquals(1, result.getParameterTargets().size());
        assertEquals(targetId, result.getParameterTargets().get(0).getTargetId());
    }

    @Test
    void fix_expiredDeadline_skipsAllStrategiesAndAppendsSummary() {
        FixStrategy paramStrategy = mockStrategy("parameter", true);
        FixStrategy condStrategy = mockStrategy("condition", true);
        FixStrategy removeStrategy = mockStrategy("remove", false);

        // Use a fixConfig with 1000ms (minimum allowed), but we don't rely on it expiring —
        // the faultLocalizer mock will return instantly, so the deadline won't expire naturally.
        // Instead we test the summary text by using a very short timeout.
        // A more reliable approach: we verify that the summary contains "timed out"
        // when the deadline is already expired by the time strategies run.
        // To simulate this, we use a fixConfig with minimum timeout and a slow strategy.
        FixConfig expiredConfig = new FixConfig();
        expiredConfig.setFixTimeoutMs(1000); // minimum allowed

        RuleFixer fixer = new RuleFixer(faultLocalizer,
                List.of(paramStrategy, condStrategy, removeStrategy), expiredConfig);

        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        when(faultLocalizer.localize(any(), any(), any())).thenReturn(List.of(fault));

        // Make first strategy consume the deadline
        when(paramStrategy.tryFix(any())).thenAnswer(inv -> {
            Thread.sleep(1100); // exceed 1000ms deadline
            return FixStrategy.StrategyOutcome.none();
        });

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");
        RuleDto rule = RuleDto.builder().build();

        FixResultDto result = fixer.fix(1L, "spec_0", List.of(), List.of(rule), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, null, 20, null);

        // After paramStrategy sleeps past deadline, remaining strategies should be skipped
        verify(paramStrategy).tryFix(any(FixContext.class));
        verify(condStrategy, never()).tryFix(any());
        verify(removeStrategy, never()).tryFix(any());
        assertTrue(result.getSummary().contains("incomplete because the automatic-fix time limit expired"));
        assertEquals(List.of("TIMED_OUT", "SKIPPED_TIMEOUT", "SKIPPED_TIMEOUT"),
                result.getStrategyAttempts().stream().map(a -> a.getStatus()).toList());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("not attempted")));
    }

    @Test
    void fix_timeoutInsideLastStrategyDoesNotClaimSkippedStrategies() {
        FixStrategy paramStrategy = mockStrategy("parameter", true);
        FixConfig shortConfig = new FixConfig();
        shortConfig.setFixTimeoutMs(1000);
        RuleFixer fixer = new RuleFixer(faultLocalizer, List.of(paramStrategy), shortConfig);
        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));
        when(paramStrategy.tryFix(any())).thenAnswer(inv -> {
            Thread.sleep(1100);
            return FixStrategy.StrategyOutcome.none();
        });
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");

        FixResultDto result = fixer.fix(1L, "spec_0", List.of(),
                List.of(RuleDto.builder().build()), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, List.of("parameter"), 20, null);

        assertEquals("TIMED_OUT", result.getStrategyAttempts().get(0).getStatus());
        assertTrue(result.getWarnings().stream().noneMatch(w -> w.contains("not attempted")),
                "the only requested strategy ran, so no strategy was skipped");
    }

    /** A proof is a fact about the model: a deadline that passes after it was recorded must not relabel it. */
    @Test
    void fix_aProofRecordedBeforeTheDeadlinePassedIsNotReportedAsATimeout() {
        FixStrategy paramStrategy = mockStrategy("parameter", true);
        FixConfig shortConfig = new FixConfig();
        shortConfig.setFixTimeoutMs(1000);
        RuleFixer fixer = new RuleFixer(faultLocalizer, List.of(paramStrategy), shortConfig);
        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));
        when(paramStrategy.tryFix(any())).thenAnswer(inv -> {
            FixContext context = inv.getArgument(0);
            context.recordStrategyNoResult("parameter", "NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", "proved");
            Thread.sleep(1100);
            return FixStrategy.StrategyOutcome.none();
        });
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");

        FixResultDto result = fixer.fix(1L, "spec_0", List.of(),
                List.of(RuleDto.builder().build()), List.of(), List.of(spec),
                Map.of(), 1L, AttackScenarioDto.none(), false, List.of("parameter"), 20, null);

        assertEquals("NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", result.getStrategyAttempts().get(0).getStatus());
        assertFalse(result.getSummary().contains("time limit"), result.getSummary());
    }

    /**
     * A rule that fired but writes nothing the violated property can observe is not a fault of it:
     * editing that rule cannot change whether the property holds.
     */
    @Test
    void fix_firedRulesOutsideThePropertysInfluenceAreNotFaults() {
        FixStrategy paramStrategy = mockStrategy("parameter", true);
        RuleFixer fixer = new RuleFixer(faultLocalizer, List.of(paramStrategy), fixConfig());
        // rule 0 writes the fan, rule 1 writes the lamp the property talks about
        RuleDto fanRule = RuleDto.builder()
                .command(RuleDto.Command.builder().deviceName("fan").action("on").build()).build();
        RuleDto lampRule = RuleDto.builder()
                .command(RuleDto.Command.builder().deviceName("lamp").action("on").build()).build();
        // rule 2 also writes the lamp but never fires in the trace
        RuleDto dormantLampRule = RuleDto.builder()
                .command(RuleDto.Command.builder().deviceName("lamp").action("off").build()).build();
        List<RuleDto> rules = List.of(fanRule, lampRule, dormantLampRule);
        SpecificationDto spec = lampSpec();
        Map<String, DeviceSmvData> devices = Map.of("fan", device("fan"), "lamp", device("lamp"));

        when(faultLocalizer.localize(any(), any(), any()))
                .thenReturn(List.of(FaultRuleDto.builder().ruleIndex(0).build()));
        FixResultDto unrelated = fixer.fix(1L, "spec_0", List.of(), rules,
                List.of(), List.of(spec), devices, 1L, AttackScenarioDto.none(), false,
                List.of("parameter"), 20, null);

        verify(paramStrategy, never()).tryFix(any());
        assertTrue(unrelated.getFaultRules().isEmpty());
        assertEquals("SKIPPED_NO_FAULT_RULES", unrelated.getStrategyAttempts().get(0).getStatus());
        assertTrue(unrelated.getSummary().contains("none of them can influence the violated property"));

        when(faultLocalizer.localize(any(), any(), any())).thenReturn(List.of(
                FaultRuleDto.builder().ruleIndex(0).build(),
                FaultRuleDto.builder().ruleIndex(1).build()));
        List<List<Integer>> scopes = new java.util.ArrayList<>();
        when(paramStrategy.tryFix(any())).thenAnswer(inv -> {
            FixContext context = inv.getArgument(0);
            scopes.add(context.getRepairRuleIndices());
            return FixStrategy.StrategyOutcome.none();
        });
        FixResultDto related = fixer.fix(1L, "spec_0", List.of(), rules,
                List.of(), List.of(spec), devices, 1L, AttackScenarioDto.none(), false,
                List.of("parameter"), 20, null);

        assertEquals(List.of(1), related.getFaultRules().stream().map(FaultRuleDto::getRuleIndex).toList());
        // Fault rule first, then the dormant rule that can also change the lamp; the fan rule stays out.
        assertEquals(List.of(List.of(1, 2)), scopes);
    }

    private static SpecificationDto lampSpec() {
        SpecConditionDto lampOn = new SpecConditionDto();
        lampOn.setDeviceId("lamp");
        lampOn.setTargetType("state");
        lampOn.setRelation("=");
        lampOn.setValue("on");
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_0");
        spec.setAConditions(List.of(lampOn));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());
        return spec;
    }

    private static DeviceSmvData device(String varName) {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName(varName);
        return smv;
    }
}
