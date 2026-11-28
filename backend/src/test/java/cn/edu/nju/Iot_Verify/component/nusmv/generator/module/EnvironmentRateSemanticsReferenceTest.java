package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.util.NaturalChangeRateParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.assumeAvailableNusmvPath;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.module.RateSemanticsTestSupport.transitionCandidates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the declared-rate semantics against an independent reference, not against the generator.
 *
 * <p>Everything else about this rule is verified by asking the implementation what it does. That
 * cannot catch a shared wrong assumption, which is exactly how the endpoint-only model and the
 * injected stutter both survived. So this test computes the reachable value set by brute force from
 * the <em>definition</em> — "each step adds some integer of the declared interval, then clamps" —
 * and requires real NuSMV to agree, state for state, on a small domain where exhaustive enumeration
 * is exact.
 */
class EnvironmentRateSemanticsReferenceTest {

    private static final RateSemanticsTestSupport NUSMV = new RateSemanticsTestSupport("rate-reference", 180);

    private static final int LOWER = 0;
    private static final int UPPER = 8;

    /** Reference semantics, derived from the declaration rather than from any production class. */
    private static Set<Integer> reachableByDefinition(int from, List<Integer> deltas, int steps) {
        Set<Integer> frontier = new LinkedHashSet<>();
        frontier.add(from);
        for (int step = 0; step < steps; step++) {
            Set<Integer> next = new LinkedHashSet<>();
            for (int value : frontier) {
                for (int delta : deltas) {
                    next.add(Math.max(LOWER, Math.min(UPPER, value + delta)));
                }
            }
            frontier = next;
        }
        return frontier;
    }

    /**
     * The generator's delta set must equal the declared interval exactly — no additions, no omissions.
     * This is the property both historical defects violated, in opposite directions.
     */
    @Test
    void theAdmittedDeltaSetEqualsTheDeclaredIntervalForEveryInterval() {
        for (int lower = -6; lower <= 6; lower++) {
            for (int upper = lower; upper <= 6; upper++) {
                List<Integer> expected = new ArrayList<>();
                for (int delta = lower; delta <= upper; delta++) expected.add(delta);

                String declaration = "[" + lower + ", " + upper + "]";
                assertEquals(expected,
                        NaturalChangeRateParser.parse(declaration).admissibleDeltas(),
                        () -> declaration + " must admit exactly its own integers");
            }
        }
    }

    /** A wider interval must admit a superset: a weaker declaration can never lose behaviour. */
    @Test
    void wideningAnIntervalIsMonotoneInAdmittedBehaviour() {
        for (int lowerBound = -4; lowerBound <= 0; lowerBound++) {
            for (int upperBound = 0; upperBound <= 4; upperBound++) {
                final int lower = lowerBound;
                final int upper = upperBound;
                Set<Integer> inner = new LinkedHashSet<>(
                        NaturalChangeRateParser.parse("[" + lower + ", " + upper + "]")
                                .admissibleDeltas());
                Set<Integer> outer = new LinkedHashSet<>(
                        NaturalChangeRateParser.parse("[" + (lower - 1) + ", " + (upper + 1) + "]")
                                .admissibleDeltas());
                assertTrue(outer.containsAll(inner),
                        () -> "widening [" + lower + ", " + upper + "] must not drop behaviour");
                assertTrue(outer.size() > inner.size(), "widening must add behaviour");
            }
        }
    }

    /**
     * Exhaustive reference vs. real NuSMV, over several declarations and step counts. A disagreement
     * means the generated transition relation is not the one the declaration describes.
     */
    @Test
    void nusmvReachabilityMatchesTheExhaustiveReferenceModel() throws Exception {
        String nusmv = assumeAvailableNusmvPath("NuSMV executable is required for this reference comparison");

        int start = 4;
        for (String declaration : new String[]{"[-1, 1]", "0", "[0, 2]", "[2, 3]", "[-3, -1]",
                "[-4, 4]"}) {
            List<Integer> deltas = NaturalChangeRateParser.parse(declaration).admissibleDeltas();
            Set<Integer> expected = reachableByDefinition(start, deltas, 1);

            // Ask NuSMV, for every value in the domain, whether it is reachable in exactly one step.
            StringBuilder model = new StringBuilder("MODULE main\nVAR\n  v : ")
                    .append(LOWER).append("..").append(UPPER).append(";\nASSIGN\n  init(v) := ")
                    .append(start).append(";\n  next(v) := {").append(transitionCandidates(declaration, "v", LOWER, UPPER))
                    .append("};\n");
            for (int value = LOWER; value <= UPPER; value++) {
                model.append("CTLSPEC AX (v != ").append(value).append(")\n");
            }

            List<String> results = NUSMV.runNusmv(nusmv, model.toString());
            Set<Integer> nusmvReachable = new LinkedHashSet<>();
            for (int value = LOWER; value <= UPPER; value++) {
                final int probed = value;
                // "AX (v != k) is false" means some successor equals k.
                boolean reachable = results.stream().anyMatch(line ->
                        line.contains("AX v != " + probed) && line.contains("is false"));
                if (reachable) nusmvReachable.add(value);
            }

            assertEquals(expected, nusmvReachable,
                    () -> declaration + ": NuSMV one-step successors must equal the reference set");
        }
    }

    /**
     * The bounded explorer must explore the same successors as the formal model. They are separate
     * implementations of one declaration, and a divergence would make a finding and a counterexample
     * describe different systems.
     */
    /**
     * Clamping must lose no permitted value that stays inside the domain, and must never leave it.
     *
     * <p>The engines differ in how they clamp — NuSMV nests {@code max(min(...))} in the transition,
     * the explorer clamps in Java — so this pins the property both must satisfy. The behavioural
     * differential for the explorer itself lives in {@code FuzzEngineTest}, which drives the real
     * engine across many seeds and requires it to reach every value the interval permits.
     */
    @Test
    void clampingConfinesToTheDomainWithoutDroppingAnInteriorValue() {
        for (String declaration : new String[]{"[-1, 1]", "0", "[0, 2]", "[2, 3]", "[-3, -1]",
                "[-9, 9]"}) {
            List<Integer> deltas = NaturalChangeRateParser.parse(declaration).admissibleDeltas();
            for (int start = LOWER; start <= UPPER; start++) {
                Set<Integer> reachable = reachableByDefinition(start, deltas, 1);
                assertTrue(reachable.stream().allMatch(value -> value >= LOWER && value <= UPPER),
                        () -> declaration + " must stay inside the declared domain");
                for (int delta : deltas) {
                    int unclamped = start + delta;
                    if (unclamped >= LOWER && unclamped <= UPPER) {
                        final int expected = unclamped;
                        assertTrue(reachable.contains(expected),
                                () -> declaration + ": an in-domain successor must survive clamping");
                    }
                }
            }
        }
    }

    /** A declared rate of 0 is a real declaration ("no independent drift"), not a missing one. */
    @Test
    void anExplicitZeroRateIsStaticAndAdmitsOnlyHolding() {
        NaturalChangeRateParser.RateRange zero = NaturalChangeRateParser.parse("0");
        assertEquals(List.of(0), zero.admissibleDeltas());
        assertEquals(List.of(0, 1), NaturalChangeRateParser.parse("[0, 1]").admissibleDeltas());
    }

    /**
     * A shared enum a device declares it writes must hold when no declared effect applies.
     *
     * <p>Free choice is right for an exogenous input nobody in the scene controls, and wrong for a
     * value a template declares it changes: "while running, set airQuality := good" says nothing
     * about the value moving on its own. With the humidifier off across a step, the free model still
     * let airQuality flip to good and refuted a property the user cannot act on; holding it makes the
     * same property true. This pins both directions so the split cannot silently regress.
     */
    @Test
    void aDeviceWrittenEnumHoldsWhileAnExogenousOneMayChooseFreely() throws Exception {
        String nusmv = assumeAvailableNusmvPath("NuSMV executable is required for this comparison");

        String property = "CTLSPEC AG ((humidifier = off & airQuality = poor)"
                + " -> AX (humidifier = off -> airQuality = poor))\n";
        String prefix = "MODULE main\nVAR\n  humidifier : {off, on};\n"
                + "  airQuality : {poor, good};\nASSIGN\n"
                + "  init(humidifier) := off;\n  init(airQuality) := poor;\n"
                + "  next(humidifier) := {off, on};\n"
                + "  next(airQuality) := case\n    humidifier = on : good;\n    TRUE : ";

        List<String> written = NUSMV.runNusmv(nusmv,
                prefix + "airQuality;\n  esac;\n" + property);
        assertTrue(written.stream().anyMatch(line -> line.contains("is true")),
                () -> "a device-written enum must hold when nothing writes it: " + written);

        List<String> exogenous = NUSMV.runNusmv(nusmv,
                prefix + "{poor, good};\n  esac;\n" + property);
        assertTrue(exogenous.stream().anyMatch(line -> line.contains("is false")),
                () -> "free choice must be shown to invent an uncaused change: " + exogenous);
    }
}
