package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.util.NaturalChangeRateParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.assumeAvailableNusmvPath;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.module.RateSemanticsTestSupport.transitionCandidates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the soundness property of a declared {@code NaturalChangeRate} interval against real NuSMV.
 *
 * <p>A declared interval constrains {@code v' - v} (MEDIC §3.1, Fig. 2b). While the generator
 * emitted only {@code {lower, 0, upper}}, a variable declared {@code [-3, 3]} could not reach
 * {@code v+1} in one step, so NuSMV <em>proved</em> {@code AG (v = 5 -> AX v != 6)} — a SATISFIED
 * verdict for behaviour the declaration permits. {@code [-1, 1]} has no interior, which is why every
 * bundled template hid the defect.
 *
 * <p>This test runs the model checker rather than asserting on generated text alone: the claim is
 * about the transition relation NuSMV explores, not about a string.
 */
class NaturalChangeRateIntervalSoundnessTest {

    private static final RateSemanticsTestSupport NUSMV = new RateSemanticsTestSupport("ncr-soundness", 120);

    @Test
    void aWideIntervalAdmitsEveryInteriorStepNotOnlyItsEndpoints() {
        List<Integer> deltas = NaturalChangeRateParser.parse("[-3, 3]").admissibleDeltas();

        assertEquals(List.of(-3, -2, -1, 0, 1, 2, 3), deltas);
        assertTrue(deltas.contains(1), "an interval that permits +1 must model +1");
        assertTrue(deltas.contains(-2), "an interval that permits -2 must model -2");
    }

    @Test
    void nusmvNoLongerProvesAnUnreachableClaimForAnIntervalThatPermitsTheStep() throws Exception {
        String nusmv = assumeAvailableNusmvPath("NuSMV executable is required for this soundness check");

        String model = """
                MODULE main
                VAR
                  v : 0..10;
                ASSIGN
                  init(v) := 5;
                  next(v) := {%s};
                CTLSPEC AG (v = 5 -> AX (v != 6))
                CTLSPEC AG (v = 5 -> AX (v != 7))
                CTLSPEC AG (v >= 0 & v <= 10)
                """.formatted(transitionCandidates("[-3, 3]", "v", 0, 10));

        List<String> results = NUSMV.runNusmv(nusmv, model);

        // The declaration permits +1 and +2, so neither "cannot become 6" nor "cannot become 7"
        // may be provable. Before the fix both were reported true.
        assertTrue(results.stream().anyMatch(line ->
                        line.contains("v != 6") && line.contains("is false")),
                () -> "expected the +1 step to be reachable, got: " + results);
        assertTrue(results.stream().anyMatch(line ->
                        line.contains("v != 7") && line.contains("is false")),
                () -> "expected the +2 step to be reachable, got: " + results);
        // Clamping still confines the variable to its declared domain, so no separate
        // at-boundary branch is needed.
        assertTrue(results.stream().anyMatch(line ->
                        line.contains("v >= 0") && line.contains("is true")),
                () -> "expected the declared domain to remain invariant, got: " + results);
    }

    @Test
    void theMedicBaselineIntervalIsUnchangedByExhaustiveModelling() throws Exception {
        String nusmv = assumeAvailableNusmvPath("NuSMV executable is required for this soundness check");

        // [-1, 1] has no interior, so the endpoint model and the exhaustive model coincide. This is
        // the regression guard for MEDIC's exact baseline.
        assertEquals("max(0, min(10, v - 1)), max(0, min(10, v)), max(0, min(10, v + 1))",
                transitionCandidates("[-1, 1]", "v", 0, 10));

        String model = """
                MODULE main
                VAR
                  v : 0..10;
                ASSIGN
                  init(v) := 5;
                  next(v) := {%s};
                CTLSPEC AG (v = 5 -> AX (v != 7))
                CTLSPEC AG (v = 5 -> AX (v != 6))
                """.formatted(transitionCandidates("[-1, 1]", "v", 0, 10));

        List<String> results = NUSMV.runNusmv(nusmv, model);

        // +2 remains genuinely impossible in one step under [-1, 1]; +1 remains possible.
        assertTrue(results.stream().anyMatch(line ->
                        line.contains("v != 7") && line.contains("is true")),
                () -> "[-1, 1] must not admit a two-step jump: " + results);
        assertTrue(results.stream().anyMatch(line ->
                        line.contains("v != 6") && line.contains("is false")),
                () -> "[-1, 1] must still admit its own +1 endpoint: " + results);
    }

    /**
     * An interval that excludes zero is a <em>mandatory</em> change, and the model must say so.
     *
     * <p>Injecting a stutter into every interval was reliable but unfaithful: for a tank declared
     * {@code [-4, -2]} ("always drains 2-4 per step") NuSMV reported {@code AF (level = 0)} false and
     * {@code EG (level = 10)} true, offering a trace in which the mandatory drain never happened.
     * That is a pseudo-counterexample — the user cannot act on behaviour their declaration forbids.
     * Both verdicts invert once the interval means exactly itself.
     */
    @Test
    void anIntervalExcludingZeroForcesTheChangeAndInvertsBothVerdicts() throws Exception {
        String nusmv = assumeAvailableNusmvPath("NuSMV executable is required for this soundness check");

        assertEquals(List.of(-4, -3, -2),
                NaturalChangeRateParser.parse("[-4, -2]").admissibleDeltas());

        String faithful = "MODULE main\nVAR\n  level : 0..10;\nASSIGN\n  init(level) := 10;\n"
                + "  next(level) := {" + transitionCandidates("[-4, -2]", "level", 0, 10) + "};\n"
                + "CTLSPEC AF (level = 0)\nCTLSPEC EG (level = 10)\n";
        List<String> faithfulResults = NUSMV.runNusmv(nusmv, faithful);
        assertTrue(faithfulResults.stream().anyMatch(line ->
                        line.contains("AF level = 0") && line.contains("is true")),
                () -> "a mandatory drain must empty the tank: " + faithfulResults);
        assertTrue(faithfulResults.stream().anyMatch(line ->
                        line.contains("EG level = 10") && line.contains("is false")),
                () -> "a mandatory drain forbids staying full: " + faithfulResults);

        // The same model with a stutter injected -- the old behaviour -- must disagree on both, which
        // is what lets this test fail if the injection ever returns.
        String withStutter = "MODULE main\nVAR\n  level : 0..10;\nASSIGN\n  init(level) := 10;\n"
                + "  next(level) := {max(0, min(10, level - 4)), max(0, min(10, level - 3)), "
                + "max(0, min(10, level - 2)), max(0, min(10, level))};\n"
                + "CTLSPEC AF (level = 0)\nCTLSPEC EG (level = 10)\n";
        List<String> stutterResults = NUSMV.runNusmv(nusmv, withStutter);
        assertTrue(stutterResults.stream().anyMatch(line ->
                        line.contains("AF level = 0") && line.contains("is false")),
                () -> "an injected stutter must be shown to break the mandatory drain: "
                        + stutterResults);
    }

    @Test
    void holdingStillIsADifferentDeclarationTheUserWrites() {
        // "may drain 2-4, or hold" is [-4, 0]: strictly weaker, and it says so on its face.
        assertEquals(List.of(-4, -3, -2, -1, 0),
                NaturalChangeRateParser.parse("[-4, 0]").admissibleDeltas());
    }

    /**
     * A device effect must reach the environment in the step the device is acting, per MEDIC §3.1,
     * Fig. 2b. While the impact rate was a state variable, {@code next(a_v)} read the previous
     * step's rate: a device initialised into an acting mode applied nothing on the first transition,
     * and switching one on took two steps to move the value. Emitting the rate as a DEFINE over the
     * current state removes the lag; this pins the difference in NuSMV rather than in generated text.
     */
    @Test
    void aDeviceAlreadyActingMovesTheEnvironmentOnTheFirstStep() throws Exception {
        String nusmv = assumeAvailableNusmvPath("NuSMV executable is required for this soundness check");

        String lagging = """
                MODULE AC
                VAR
                  MachineState : {off, cool};
                  temperature_rate : -2..0;
                ASSIGN
                  init(MachineState) := cool;
                  init(temperature_rate) := 0;
                  next(MachineState) := MachineState;
                  next(temperature_rate) := case MachineState = cool : -2; TRUE : 0; esac;
                MODULE main
                VAR
                  ac : AC;
                  a_temperature : 20..30;
                ASSIGN
                  init(a_temperature) := 30;
                  next(a_temperature) := max(20, min(30, a_temperature - 1 + ac.temperature_rate));
                CTLSPEC AG (a_temperature = 30 -> AX (a_temperature <= 27))
                """;
        String contemporaneous = """
                MODULE AC
                VAR
                  MachineState : {off, cool};
                DEFINE
                  temperature_rate := case MachineState = cool : -2; TRUE : 0; esac;
                ASSIGN
                  init(MachineState) := cool;
                  next(MachineState) := MachineState;
                MODULE main
                VAR
                  ac : AC;
                  a_temperature : 20..30;
                ASSIGN
                  init(a_temperature) := 30;
                  next(a_temperature) := max(20, min(30, a_temperature - 1 + ac.temperature_rate));
                CTLSPEC AG (a_temperature = 30 -> AX (a_temperature <= 27))
                """;

        assertTrue(NUSMV.runNusmv(nusmv, lagging).stream()
                        .anyMatch(line -> line.contains("is false")),
                "a stored rate must be shown to lag, otherwise this test proves nothing");
        assertTrue(NUSMV.runNusmv(nusmv, contemporaneous).stream()
                        .anyMatch(line -> line.contains("is true")),
                "a DEFINE rate must apply the device effect in the same step");
    }
}
