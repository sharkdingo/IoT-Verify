package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.NusmvResult;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyUtils.Verification;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyUtils.Witness;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext.EmittedSpec;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A candidate is accepted when the violated (target) specification passes and every specification it
 * still violates was already violated by the original rules. Requiring every specification to pass made
 * a counterexample unrepairable whenever an unrelated specification on the board also failed.
 */
class ForwardVerifyAcceptanceTest {

    private static final String NAME = "parameter";
    private static final List<EmittedSpec> EMITTED = List.of(
            new EmittedSpec(null, "target", "CTLSPEC AG(t)"),
            new EmittedSpec(null, "other", "CTLSPEC AG(o)"),
            new EmittedSpec(null, "healthy", "CTLSPEC AG(h)"));

    @TempDir
    Path tempDir;

    private final SmvGenerator smvGenerator = mock(SmvGenerator.class);
    private final NusmvExecutor nusmvExecutor = mock(NusmvExecutor.class);
    private final List<RuleDto> originalRules = new ArrayList<>();
    private final List<RuleDto> candidateRules = new ArrayList<>();
    private File candidateModel;
    private File baselineModel;
    private FixContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        candidateModel = model("candidate");
        baselineModel = model("baseline");
        givenGenerated(candidateRules, candidateModel);
        givenGenerated(originalRules, baselineModel);
        ctx = FixContext.builder()
                .deadline(Instant.now().plusSeconds(300))
                .allRules(originalRules)
                .specs(List.of(spec("target"), spec("other"), spec("healthy")))
                .violatedSpecIndex(0)
                .deviceSmvMap(Map.of())
                .attackScenario(AttackScenarioDto.none())
                .build();
    }

    @Test
    void aCandidateThatRepairsTheTargetIsAcceptedDespiteAViolationTheOriginalRulesAlreadyHad() throws Exception {
        givenVerdicts(candidateModel, true, false, true);
        givenVerdicts(baselineModel, false, false, true);

        assertEquals(Verification.accepted(List.of("other")), judge(NAME));
    }

    @Test
    void aCandidateThatBreaksAPreviouslySatisfiedSpecificationIsRejected() throws Exception {
        givenVerdicts(candidateModel, true, false, false);
        givenVerdicts(baselineModel, false, false, true);

        assertEquals(Verification.rejected(), judge(NAME));
    }

    @Test
    void aCandidateThatLeavesTheTargetViolatedIsRejectedWithoutCheckingTheOriginalRules() throws Exception {
        givenVerdicts(candidateModel, false, false, true);

        assertEquals(Verification.rejected(), judge(NAME));
        verify(nusmvExecutor, never()).executeRepairSearch(eq(baselineModel), anyLong());
    }

    @Test
    void aCandidateSatisfyingEverySpecificationNeedsNoBaselineAndRecordsNoViolation() throws Exception {
        givenVerdicts(candidateModel, true, true, true);

        assertEquals(Verification.accepted(List.of()), judge(NAME));
        verify(nusmvExecutor, never()).executeRepairSearch(eq(baselineModel), anyLong());
    }

    @Test
    void theOriginalRulesAreCheckedOncePerFixRequest() throws Exception {
        givenVerdicts(candidateModel, true, false, true);
        givenVerdicts(baselineModel, false, false, true);

        judge(NAME);
        judge("remove");

        verify(nusmvExecutor, times(1)).executeRepairSearch(eq(baselineModel), anyLong());
    }

    @Test
    void anUnavailableOriginalVerdictLeavesTheCandidateInconclusiveChecksOnceAndSaysSo() throws Exception {
        givenVerdicts(candidateModel, true, false, true);
        when(nusmvExecutor.executeRepairSearch(eq(baselineModel), anyLong()))
                .thenReturn(NusmvResult.error("solver crashed"));

        // Nothing is known about the candidate, so it is neither listed nor counted as a rejection.
        assertEquals(Verification.inconclusive(), judge(NAME));
        assertEquals(Verification.inconclusive(), judge(NAME));

        verify(nusmvExecutor, times(1)).executeRepairSearch(eq(baselineModel), anyLong());
        assertTrue(ctx.diagnosticsSnapshot().stream().anyMatch(d -> d.contains("original rules could not be re-checked")));
    }

    /** A refused permit says nothing about the model, so it must not leave the candidate unjudged. */
    @Test
    void aCandidateRunRefusedForCapacityIsRetried() throws Exception {
        when(nusmvExecutor.executeRepairSearch(eq(candidateModel), anyLong()))
                .thenReturn(NusmvResult.busy("busy"), verdicts(true, true, true));

        assertEquals(Verification.accepted(List.of()), judge(NAME));
        verify(nusmvExecutor, times(2)).executeRepairSearch(eq(candidateModel), anyLong());
        assertNull(ctx.strategySolverFailure(NAME));
    }

    /** Nor may it be memoized as "the original rules' verdict is unknown" for the rest of the request. */
    @Test
    void anOriginalRulesRunRefusedForCapacityIsRetriedRatherThanRecordedAsUnavailable() throws Exception {
        givenVerdicts(candidateModel, true, false, true);
        when(nusmvExecutor.executeRepairSearch(eq(baselineModel), anyLong()))
                .thenReturn(NusmvResult.busy("busy"), verdicts(false, false, true));

        assertEquals(Verification.accepted(List.of("other")), judge(NAME));
        verify(nusmvExecutor, times(2)).executeRepairSearch(eq(baselineModel), anyLong());
        assertTrue(ctx.diagnosticsSnapshot().stream().noneMatch(d -> d.contains("could not be re-checked")));
    }

    /** {@code -df} keeps unconstrained search variables from stalling NuSMV, deadline or not. */
    @Test
    void aContextWithoutADeadlineStillRunsTheRepairSearchInvocation() throws Exception {
        ctx = FixContext.builder()
                .allRules(originalRules)
                .specs(List.of(spec("target"), spec("other"), spec("healthy")))
                .violatedSpecIndex(0)
                .deviceSmvMap(Map.of())
                .attackScenario(AttackScenarioDto.none())
                .build();
        givenVerdicts(candidateModel, true, true, true);

        assertEquals(Verification.accepted(List.of()), judge(NAME));
        verify(nusmvExecutor).executeRepairSearch(eq(candidateModel), eq(Long.MAX_VALUE));
        verify(nusmvExecutor, never()).execute(any(File.class));
    }

    @Test
    void aVerdictWhoseSpecificationIsOnlyGuessedByPositionIsInconclusive() throws Exception {
        when(nusmvExecutor.executeRepairSearch(eq(candidateModel), anyLong())).thenReturn(NusmvResult.success("",
                List.of(new SpecCheckResult("AG t", true, null),
                        new SpecCheckResult("AG o", false, null),
                        new SpecCheckResult("AG changed_echo", true, null))));
        givenVerdicts(baselineModel, false, false, true);

        assertEquals(Verification.inconclusive(), judge(NAME));
        verify(nusmvExecutor, never()).executeRepairSearch(eq(baselineModel), anyLong());
    }

    @Test
    void withoutAnIdentifiedTargetAFailingSpecificationLeavesTheCandidateInconclusive() throws Exception {
        ctx = FixContext.builder()
                .deadline(Instant.now().plusSeconds(300))
                .allRules(originalRules)
                .specs(List.of(spec("target"), spec("other"), spec("healthy")))
                .violatedSpecIndex(-1)
                .deviceSmvMap(Map.of())
                .attackScenario(AttackScenarioDto.none())
                .build();
        givenVerdicts(candidateModel, true, false, true);
        givenVerdicts(baselineModel, false, false, true);

        assertEquals(Verification.inconclusive(), judge("remove"));
    }

    @Test
    void aRejectionCarriesThePathOnWhichTheTargetStillFails() throws Exception {
        givenTraces(candidateModel, "target path", "other path", null);

        assertEquals(Verification.rejected(new Witness("target", "target path")), judge(NAME));
    }

    @Test
    void aRejectionForBreakingASpecificationCarriesThatSpecificationsPathNotAPreexistingOne() throws Exception {
        givenTraces(candidateModel, null, "other path", "healthy path");
        givenVerdicts(baselineModel, false, false, true);

        // "other" also fails, but the original rules failed it too: its path is not why the candidate is rejected.
        assertEquals(Verification.rejected(new Witness("healthy", "healthy path")), judge(NAME));
    }

    @Test
    void aFailingVerdictThatPrintsNoPathCarriesNoWitness() throws Exception {
        givenTraces(candidateModel, " ", null, null);

        assertEquals(Verification.rejected(), judge(NAME));
    }

    private Verification judge(String strategyName) {
        return FixStrategyUtils.forwardVerify(smvGenerator, nusmvExecutor, ctx, candidateRules, strategyName);
    }

    private File model(String name) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        assertTrue(dir.mkdirs());
        File file = new File(dir, "model.smv");
        assertTrue(file.createNewFile());
        return file;
    }

    private void givenGenerated(List<RuleDto> rules, File smvFile) throws Exception {
        when(smvGenerator.generateWithResolvedDeviceModel(
                any(), any(), any(), same(rules), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new SmvGenerator.GenerateResult(smvFile, Map.of(), List.of(), 0, 0, EMITTED));
    }

    private void givenVerdicts(File smvFile, boolean target, boolean other, boolean healthy) throws Exception {
        when(nusmvExecutor.executeRepairSearch(eq(smvFile), anyLong())).thenReturn(verdicts(target, other, healthy));
    }

    /** Verdicts for target, other, healthy — returned in reverse to exercise matching by expression. */
    private static NusmvResult verdicts(boolean target, boolean other, boolean healthy) {
        return NusmvResult.success("",
                List.of(new SpecCheckResult("AG h", healthy, null),
                        new SpecCheckResult("AG o", other, null),
                        new SpecCheckResult("AG t", target, null)));
    }

    /** Counterexamples for target, other, healthy; a {@code null} one means that specification passes. */
    private void givenTraces(File smvFile, String target, String other, String healthy) throws Exception {
        when(nusmvExecutor.executeRepairSearch(eq(smvFile), anyLong())).thenReturn(NusmvResult.success("",
                List.of(new SpecCheckResult("AG h", healthy == null, healthy),
                        new SpecCheckResult("AG o", other == null, other),
                        new SpecCheckResult("AG t", target == null, target))));
    }

    private static SpecificationDto spec(String id) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId(id);
        spec.setTemplateId("1");
        return spec;
    }
}
