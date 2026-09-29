package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A cancelled fix search must stop launching NuSMV runs.
 *
 * <p>Cancellation reaches a fix worker as a thread interrupt. The strategy loops only ever consulted
 * the deadline, and their broad {@code catch (Exception)} swallowed the {@code InterruptedException}
 * that {@code Semaphore.tryAcquire} throws — clearing the flag with it. The search then ran its
 * remaining attempts (up to 20, each holding a permit from the shared NuSMV semaphore) for a request
 * whose response had already been sent, starving concurrent verification of solver capacity.
 */
class FixContextCancellationTest {

    /*
     * Not `Files.createTempFile`: forwardVerify's cleanup deletes every file in the model's parent
     * directory, which for a bare temp file is the system temp directory itself.
     */
    @TempDir
    Path tempDir;

    @AfterEach
    void clearInterrupt() {
        // Never leak an interrupt into the next test on this thread.
        Thread.interrupted();
    }

    @Test
    void anInterruptedWorkerIsAskedToStopEvenWithBudgetRemaining() {
        FixContext ctx = FixContext.builder()
                .deadline(Instant.now().plusSeconds(300))
                .build();
        assertFalse(ctx.isExpired(), "a fresh context with budget left must not report a stop");

        Thread.currentThread().interrupt();

        assertTrue(ctx.isExpired(),
                "a cancelled search must stop even though its deadline has not passed");
    }

    @Test
    void askingWhetherToStopDoesNotConsumeTheInterrupt() {
        FixContext ctx = FixContext.builder().build();
        Thread.currentThread().interrupt();

        assertTrue(ctx.isExpired());
        // Non-clearing: anything else checking the flag afterwards must still see it.
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void anInterruptSwallowedByABroadCatchIsRestored() {
        assertFalse(Thread.currentThread().isInterrupted());

        // What `Semaphore.tryAcquire` throws when the worker is cancelled. Catching it clears the
        // flag, so the strategy loop would otherwise never learn the search was cancelled.
        FixStrategyUtils.preserveInterrupt(new InterruptedException("cancelled"));

        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void aWrappedInterruptIsAlsoRestored() {
        FixStrategyUtils.preserveInterrupt(
                new RuntimeException("wrapped", new InterruptedException("cancelled")));

        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void anOrdinaryFailureDoesNotFabricateAnInterrupt() {
        // A transient solver error must stay retryable; marking it as cancellation would abandon a
        // search the user is still waiting for.
        FixStrategyUtils.preserveInterrupt(new IllegalStateException("transient solver error"));

        assertFalse(Thread.currentThread().isInterrupted());
    }

    /**
     * Pins the shared solver entry point, not just the helper.
     *
     * <p>The tests above exercise {@code preserveInterrupt} directly, so they stay green even if the
     * production code stops calling it. Every strategy reaches NuSMV through
     * {@code forwardVerify}, whose broad {@code catch (Exception)} is where the
     * {@code InterruptedException} declared by {@code executeWithinDeadline} actually lands — so
     * driving a cancelled executor through it is the reachable behavioural check.
     */
    @Test
    void aCancelledForwardVerificationLeavesTheSearchAskedToStop() throws Exception {
        SmvGenerator smvGenerator = mock(SmvGenerator.class);
        NusmvExecutor nusmvExecutor = mock(NusmvExecutor.class);
        File smvFile = Files.createFile(tempDir.resolve("fix-cancel.smv")).toFile();

        when(smvGenerator.generateWithResolvedDeviceModel(
                any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new SmvGenerator.GenerateResult(smvFile, Map.of()));
        // What the shared NuSMV semaphore throws once the worker has been cancelled.
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenThrow(new InterruptedException("cancelled"));

        FixContext ctx = FixContext.builder()
                .deadline(Instant.now().plusSeconds(300))
                .allRules(List.of())
                .specs(List.of())
                .deviceSmvMap(Map.of())
                .attackScenario(AttackScenarioDto.builder().build())
                .build();

        FixStrategyUtils.Verification verification = FixStrategyUtils.forwardVerify(
                smvGenerator, nusmvExecutor, ctx, List.of(), "PARAMETER_ADJUST");

        // Inconclusive, not rejected: a cancelled check says nothing about the candidate, so a
        // strategy must not count it towards proving that no repair exists.
        assertEquals(FixStrategyUtils.Verdict.INCONCLUSIVE, verification.verdict(),
                "a cancelled candidate must never be reported as verified or as rejected");
        assertTrue(Thread.currentThread().isInterrupted(),
                "the interrupt must survive the broad catch, or the search keeps taking NuSMV "
                        + "permits for a request whose response was already sent");
        assertTrue(ctx.isExpired(), "the restored interrupt must make the search loop stop");
    }
}
