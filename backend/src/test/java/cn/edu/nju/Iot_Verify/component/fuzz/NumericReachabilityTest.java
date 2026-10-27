package cn.edu.nju.Iot_Verify.component.fuzz;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericReachabilityTest {

    @Test
    void matchesIndependentlyEnumeratedNaturalEvolutionInASmallDomain() {
        for (int initial = -2; initial <= 3; initial++) {
            for (int lowerRate = -2; lowerRate <= 2; lowerRate++) {
                for (int upperRate = lowerRate; upperRate <= 2; upperRate++) {
                    var bounds = new NumericReachability(initial, -2, 3, lowerRate, upperRate);
                    Set<Integer> reachable = Set.of(initial);
                    for (int step = 0; step <= 5; step++) {
                        String context = "initial=" + initial + ", rates=" + lowerRate + ".." + upperRate
                                + ", step=" + step;
                        for (String relation : List.of("=", "!=", ">", ">=", "<", "<=")) {
                            for (int value = -3; value <= 4; value++) {
                                assertPossibility(bounds, step, relation, Set.of(value), reachable, context);
                            }
                        }
                        for (int mask = 0; mask < 64; mask++) {
                            Set<Integer> members = new HashSet<>();
                            for (int bit = 0; bit < 6; bit++) {
                                if ((mask & (1 << bit)) != 0) members.add(bit - 2);
                            }
                            assertPossibility(bounds, step, "in", members, reachable, context);
                            assertPossibility(bounds, step, "not in", members, reachable, context);
                        }

                        Set<Integer> next = new HashSet<>();
                        for (int value : reachable) {
                            for (int delta = lowerRate; delta <= upperRate; delta++) {
                                next.add(Math.max(-2, Math.min(3, value + delta)));
                            }
                        }
                        reachable = next;
                    }
                }
            }
        }
    }

    @Test
    void distancesOnlyChooseAlternativesReachableAtThatStep() {
        var bounds = new NumericReachability(1, 0, 8, -4, 4);
        Set<Integer> members = Set.of(0, 6);
        Set<Integer> blocked = Set.of(1, 2, 3, 4, 5);
        assertEquals(5.0, bounds.distanceAt(1, 5, "in", members, true));
        assertEquals(4.0, bounds.distanceAt(1, 4, "in", members, true));
        assertEquals(5.0, bounds.distanceAt(1, 5, "not in", members, false));
        assertEquals(4.0, bounds.distanceAt(1, 4, "not in", members, false));
        assertEquals(5.0, bounds.distanceAt(1, 5, "not in", blocked, true));
        assertEquals(4.0, bounds.distanceAt(1, 4, "not in", blocked, true));
        assertEquals(5.0, bounds.distanceAt(1, 5, "in", blocked, false));
        assertEquals(4.0, bounds.distanceAt(1, 4, "in", blocked, false));
    }

    @Test
    void distancesMatchEnumerationOfEverySmallSupportAndCurrentValue() {
        for (int lower = -2; lower <= 3; lower++) {
            for (int upper = lower; upper <= 3; upper++) {
                Set<Integer> support = new HashSet<>();
                for (int value = lower; value <= upper; value++) support.add(value);
                for (int actual = -3; actual <= 4; actual++) {
                    String context = "support=" + lower + ".." + upper + ", actual=" + actual;
                    for (String relation : List.of("=", "!=", ">", ">=", "<", "<=")) {
                        for (int value = -3; value <= 4; value++) {
                            assertRangeDistance(actual, lower, upper, relation, Set.of(value), support, context);
                        }
                    }
                    for (int mask = 0; mask < 64; mask++) {
                        Set<Integer> members = new HashSet<>();
                        for (int bit = 0; bit < 6; bit++) {
                            if ((mask & (1 << bit)) != 0) members.add(bit - 2);
                        }
                        assertRangeDistance(actual, lower, upper, "in", members, support, context);
                        assertRangeDistance(actual, lower, upper, "not in", members, support, context);
                    }
                }
            }
        }
    }

    @Test
    void distancesPreserveTheFullIntegerSpanAndStrictEdges() {
        assertEquals(4_294_967_295.0, NumericReachability.distanceInRange(Integer.MIN_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE, "=", Set.of(Integer.MAX_VALUE), true));
        assertEquals(4_294_967_295.0, NumericReachability.distanceInRange(Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE, "!=", Set.of(Integer.MIN_VALUE), false));
        assertEquals(Double.POSITIVE_INFINITY, NumericReachability.distanceInRange(0,
                Integer.MIN_VALUE, Integer.MAX_VALUE, ">", Set.of(Integer.MAX_VALUE), true));
        assertEquals(Double.POSITIVE_INFINITY, NumericReachability.distanceInRange(0,
                Integer.MIN_VALUE, Integer.MAX_VALUE, "<", Set.of(Integer.MIN_VALUE), true));
        assertEquals(1.0, NumericReachability.distanceInRange(Integer.MIN_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE + 1, "not in", Set.of(Integer.MIN_VALUE), true));
        assertEquals(1.0, NumericReachability.distanceInRange(Integer.MAX_VALUE,
                Integer.MAX_VALUE - 1, Integer.MAX_VALUE, "in", Set.of(Integer.MAX_VALUE), false));
        var rising = new NumericReachability(
                0, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(4_294_967_295.0, rising.distanceAt(49, Integer.MIN_VALUE,
                "=", Set.of(Integer.MAX_VALUE), true));
        assertEquals(Double.POSITIVE_INFINITY, rising.distanceAt(0, 0,
                "=", Set.of(Integer.MAX_VALUE), true));
    }

    @Test
    void falsePossibilityIsNotTheNegationOfTruePossibility() {
        assertTrue(Double.isFinite(NumericReachability.distanceInRange(0, 0, 1, "=", Set.of(0), true)));
        assertTrue(Double.isFinite(NumericReachability.distanceInRange(0, 0, 1, "=", Set.of(0), false)));
        assertTrue(Double.isFinite(NumericReachability.distanceInRange(0, 0, 1, "in", Set.of(0), true)));
        assertTrue(Double.isFinite(NumericReachability.distanceInRange(0, 0, 1, "in", Set.of(0), false)));
        assertFalse(Double.isFinite(NumericReachability.distanceInRange(0, 0, 1, "in", Set.of(0, 1), false)));
        assertFalse(Double.isFinite(NumericReachability.distanceInRange(0, 0, 1, "not in", Set.of(0, 1), true)));
    }

    @Test
    void keepsTheInitialStateExactAndClampsExtremeRatesWithoutOverflow() {
        var spread = new NumericReachability(
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertTrue(Double.isFinite(spread.distanceAt(0, 0, "=", Set.of(Integer.MIN_VALUE), true)));
        assertFalse(Double.isFinite(spread.distanceAt(0, 0, "!=", Set.of(Integer.MIN_VALUE), true)));
        assertFalse(Double.isFinite(spread.distanceAt(1, 0, ">=", Set.of(0), true)));
        assertFalse(Double.isFinite(spread.distanceAt(2, 0, "=", Set.of(Integer.MAX_VALUE), true)));
        assertTrue(Double.isFinite(spread.distanceAt(3, 0, "=", Set.of(Integer.MAX_VALUE), true)));
        assertTrue(Double.isFinite(spread.distanceAt(49, 0, "=", Set.of(Integer.MAX_VALUE), true)));

        var rising = new NumericReachability(
                0, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(Double.isFinite(rising.distanceAt(49, 0, "=", Set.of(Integer.MAX_VALUE), true)));
        assertFalse(Double.isFinite(rising.distanceAt(49, 0, "=", Set.of(Integer.MAX_VALUE), false)));
        var falling = new NumericReachability(
                0, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertTrue(Double.isFinite(falling.distanceAt(49, 0, "=", Set.of(Integer.MIN_VALUE), true)));
        assertFalse(Double.isFinite(falling.distanceAt(49, 0, "!=", Set.of(Integer.MIN_VALUE), true)));
        var stationary = new NumericReachability(-1, -5, 5, 0, 0);
        assertTrue(Double.isFinite(stationary.distanceAt(49, 0, "=", Set.of(-1), true)));
        assertFalse(Double.isFinite(stationary.distanceAt(49, 0, "=", Set.of(0), true)));
    }

    @Test
    void handlesTheFullIntegerDomainAndEmptySetsWithoutEnumeratingTheDomain() {
        assertTrue(Double.isFinite(NumericReachability.distanceInRange(0, Integer.MIN_VALUE, Integer.MAX_VALUE,
                "not in", Set.of(Integer.MIN_VALUE, Integer.MAX_VALUE), true)));
        assertFalse(Double.isFinite(NumericReachability.distanceInRange(0, Integer.MAX_VALUE, Integer.MAX_VALUE,
                "in", Set.of(Integer.MAX_VALUE), false)));
        assertFalse(Double.isFinite(NumericReachability.distanceInRange(0, 0, 5, "in", Set.of(), true)));
        assertTrue(Double.isFinite(NumericReachability.distanceInRange(0, 0, 5, "not in", Set.of(), true)));
        assertFalse(Double.isFinite(NumericReachability.distanceInRange(0, 1, 0, "not in", Set.of(), true)));
    }

    private static void assertPossibility(
            NumericReachability bounds, int step, String relation, Set<Integer> expected,
            Set<Integer> reachable, String context) {
        for (boolean truth : List.of(false, true)) {
            boolean possible = reachable.stream().anyMatch(value -> matches(value, relation, expected) == truth);
            double actualDistance = bounds.distanceAt(step, bounds.initial(), relation, expected, truth);
            assertEquals(possible, Double.isFinite(actualDistance),
                    context + ", relation=" + relation + ", expected=" + expected + ", truth=" + truth);
            double distance = enumeratedDistance(bounds.initial(), reachable, relation, expected, truth);
            assertEquals(distance, actualDistance,
                    context + ", relation=" + relation + ", expected=" + expected + ", truth=" + truth);
        }
    }

    private static void assertRangeDistance(
            int actual, int lower, int upper, String relation, Set<Integer> expected,
            Set<Integer> support, String context) {
        for (boolean truth : List.of(false, true)) {
            double distance = enumeratedDistance(actual, support, relation, expected, truth);
            assertEquals(distance, NumericReachability.distanceInRange(
                    actual, lower, upper, relation, expected, truth),
                    context + ", relation=" + relation + ", expected=" + expected + ", truth=" + truth);
        }
    }

    private static double enumeratedDistance(
            int actual, Set<Integer> support, String relation, Set<Integer> expected, boolean truth) {
        double distance = Double.POSITIVE_INFINITY;
        for (int candidate : support) {
            if (matches(candidate, relation, expected) == truth) {
                distance = Math.min(distance, Math.abs((long) actual - candidate));
            }
        }
        return distance;
    }

    private static boolean matches(int actual, String relation, Set<Integer> expected) {
        if ("in".equals(relation)) return expected.contains(actual);
        if ("not in".equals(relation)) return !expected.contains(actual);
        int value = expected.iterator().next();
        return switch (relation) {
            case "=" -> actual == value;
            case "!=" -> actual != value;
            case ">" -> actual > value;
            case ">=" -> actual >= value;
            case "<" -> actual < value;
            case "<=" -> actual <= value;
            default -> throw new IllegalArgumentException(relation);
        };
    }
}
