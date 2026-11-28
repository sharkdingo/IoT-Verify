package cn.edu.nju.Iot_Verify.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses the single canonical NaturalChangeRate grammar used by the template schema and both
 * model engines. A missing property means no declared natural change for local variables; a
 * present property must use an integer or a bracketed ordered integer interval.
 */
public final class NaturalChangeRateParser {

    private static final Pattern CANONICAL_SYNTAX = Pattern.compile(
            "(?:-?[0-9]+|\\[\\s*-?[0-9]+\\s*,\\s*-?[0-9]+\\s*\\])");

    private NaturalChangeRateParser() {
    }

    /** Parse a nullable declaration; null is the omitted local-variable declaration. */
    public static RateRange parse(String raw) {
        if (raw == null) {
            return new RateRange(0, 0);
        }
        if (!CANONICAL_SYNTAX.matcher(raw).matches()) {
            throw new ParseException(false);
        }
        String body = raw.startsWith("[")
                ? raw.substring(1, raw.length() - 1)
                : raw;
        String[] parts = body.split(",", -1);
        try {
            if (parts.length == 1) {
                int rate = Integer.parseInt(parts[0].trim());
                return rate < 0 ? new RateRange(rate, 0) : new RateRange(0, rate);
            }
            int lower = Integer.parseInt(parts[0].trim());
            int upper = Integer.parseInt(parts[1].trim());
            if (lower > upper) {
                throw new ParseException(true);
            }
            return new RateRange(lower, upper);
        } catch (NumberFormatException exception) {
            throw new ParseException(false);
        }
    }

    public static String canonical(String raw) {
        try {
            RateRange range = parse(raw);
            return range.lower() + ".." + range.upper();
        } catch (ParseException exception) {
            return raw == null ? "0..0" : raw.trim();
        }
    }

    /**
     * A declared per-step change interval.
     *
     * <p>The interval is a <em>constraint</em> on {@code v' - v}, exactly as MEDIC §3.1, Fig. 2b
     * writes it — not a shortlist of interesting deltas. Emitting only the endpoints was unsound:
     * for {@code [-3, 3]} it omitted ±1 and ±2, so NuSMV proved
     * {@code AG (v = 5 -> AX v != 6)} <em>true</em> for a variable the declaration lets reach 6 in
     * one step. A verifier must never report SATISFIED for behaviour the declaration permits, so
     * every integer in the interval is admitted. {@code [-1, 1]} is unchanged by this — it has no
     * interior — which is why the unsoundness stayed hidden while every bundled template used it.
     */
    public record RateRange(int lower, int upper) {

        /**
         * Widest single-step magnitude the interval permits, as a long so {@code Integer.MIN_VALUE}
         * cannot overflow the comparison.
         */
        public long span() {
            return (long) upper - (long) lower;
        }

        /**
         * Exactly the integers the declaration admits, ascending. Nothing is added.
         *
         * <p>The interval <em>is</em> the meaning: MEDIC §3.1, Fig. 2b constrains {@code v' - v} to
         * {@code [-1 + D, 1 + D]} and never re-adds a stutter, because {@code 0} is arithmetically
         * inside {@code [-1, 1]} already. So an interval that excludes {@code 0} says the value
         * <em>always</em> changes, and an interval that includes it says the value <em>may</em> hold —
         * the user picks between those two meanings by writing the interval they mean.
         *
         * <p>Injecting {@code 0} into every interval collapsed that distinction and produced
         * unactionable counterexamples. For a tank declared {@code [-4, -2]} ("always drains 2-4 per
         * step"), NuSMV reported {@code AF (level = 0)} <em>false</em> and {@code EG (level = 10)}
         * <em>true</em> — it offered a trace where a mandatory drain simply did not happen, which the
         * declaration forbids and the user cannot act on. Both verdicts invert once the interval means
         * itself. No bundled template was affected either way: every one of them declares
         * {@code [-1, 1]}, {@code [0, 1]}, or {@code 0}, all of which contain {@code 0} already.
         *
         * <p>Callers combine each delta with the active device effect for that step, so a device
         * effect can still hold a value steady even when the natural rate cannot.
         */
        public List<Integer> admissibleDeltas() {
            List<Integer> deltas = new ArrayList<>();
            for (int delta = lower; delta <= upper; delta++) {
                deltas.add(delta);
                if (delta == Integer.MAX_VALUE) break;
            }
            return List.copyOf(deltas);
        }
    }

    public static final class ParseException extends IllegalArgumentException {
        private final boolean descending;

        private ParseException(boolean descending) {
            super(descending ? "NaturalChangeRate lower bound exceeds upper bound"
                    : "NaturalChangeRate must be an integer or [lower, upper] using 32-bit integers");
            this.descending = descending;
        }

        public boolean isDescending() {
            return descending;
        }
    }
}
