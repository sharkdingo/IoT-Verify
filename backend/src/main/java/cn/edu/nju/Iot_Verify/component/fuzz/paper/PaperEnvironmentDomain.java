package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.Set;

/**
 * One named environment property and its paper-compatible value domains.
 *
 * <p>{@code values} are direct values used only to materialize the random initial state.
 * Event values represent change rates when a rate domain is available. For a discrete
 * environment without rates, direct values are retained as a compatibility extension.</p>
 */
public record PaperEnvironmentDomain(
        String name,
        String targetId,
        String property,
        List<String> values,
        List<String> rates,
        Integer valueLowerBound,
        Integer valueUpperBound,
        Integer rateLowerBound,
        Integer rateUpperBound) {

    public static final String RATE_PREFIX = "rate:";

    public PaperEnvironmentDomain(
            String name,
            String targetId,
            String property,
            List<String> values,
            List<String> rates) {
        this(name, targetId, property, values, rates, null, null, null, null);
    }

    public static PaperEnvironmentDomain numeric(
            String name,
            String targetId,
            String property,
            int valueLowerBound,
            int valueUpperBound,
            int rateLowerBound,
            int rateUpperBound) {
        return new PaperEnvironmentDomain(
                name,
                targetId,
                property,
                List.of(),
                List.of(),
                valueLowerBound,
                valueUpperBound,
                rateLowerBound,
                rateUpperBound);
    }

    public PaperEnvironmentDomain {
        name = requireText(name, "name");
        targetId = requireText(targetId, "targetId");
        property = requireText(property, "property");
        values = immutableDomain(values, "values");
        rates = immutableRates(rates);
        requireRange(valueLowerBound, valueUpperBound, "value");
        requireRange(rateLowerBound, rateUpperBound, "rate");
        if (!values.isEmpty() && valueLowerBound != null) {
            throw new IllegalArgumentException("environment values must use either entries or a numeric range");
        }
        if (!rates.isEmpty() && rateLowerBound != null) {
            throw new IllegalArgumentException("environment rates must use either entries or a numeric range");
        }
        if (values.isEmpty() && valueLowerBound == null) {
            throw new IllegalArgumentException(
                    "an environment domain needs a direct value for initial-state materialization");
        }
        Set<String> combined = new HashSet<>(values);
        for (String rate : rates) {
            if (!combined.add(rate)) {
                throw new IllegalArgumentException("environment values and rates must not overlap");
            }
        }
    }

    /** Values eligible for a mutable environment Event. */
    public List<String> eventValues() {
        return hasRateDomain() ? rates : values;
    }

    /** Direct values eligible only for random initial-state materialization. */
    public List<String> initialStateValues() {
        return values;
    }

    public boolean hasRateDomain() {
        return !rates.isEmpty() || rateLowerBound != null;
    }

    public boolean isValidInitialValue(String value) {
        return containsValue(value, values, valueLowerBound, valueUpperBound, false);
    }

    public boolean isValidEventValue(String value) {
        return hasRateDomain()
                ? containsValue(value, rates, rateLowerBound, rateUpperBound, true)
                : isValidInitialValue(value);
    }

    public String randomInitialValue(SplittableRandom random) {
        return randomValue(random, values, valueLowerBound, valueUpperBound, false, null);
    }

    public String randomEventValue(SplittableRandom random) {
        return hasRateDomain()
                ? randomValue(random, rates, rateLowerBound, rateUpperBound, true, null)
                : randomInitialValue(random);
    }

    public boolean hasAlternativeEventValue(String current) {
        Integer lowerBound = hasRateDomain() ? rateLowerBound : valueLowerBound;
        Integer upperBound = hasRateDomain() ? rateUpperBound : valueUpperBound;
        if (lowerBound != null) {
            return lowerBound < upperBound || !isValidEventValue(current);
        }
        List<String> domain = eventValues();
        return domain.size() > 1 || domain.stream().noneMatch(current::equals);
    }

    public String differentEventValue(SplittableRandom random, String current) {
        return hasRateDomain()
                ? randomValue(random, rates, rateLowerBound, rateUpperBound, true, current)
                : randomValue(random, values, valueLowerBound, valueUpperBound, false, current);
    }

    private static List<String> immutableDomain(List<String> input, String field) {
        if (input == null || input.isEmpty()) return List.of();
        if (input.size() > PaperEventDomain.MAX_VALUES_PER_TARGET) {
            throw new IllegalArgumentException(
                    field + " contains more than " + PaperEventDomain.MAX_VALUES_PER_TARGET + " entries");
        }
        List<String> normalized = input.stream()
                .map(value -> {
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException(field + " must not contain blank values");
                    }
                    return value.trim();
                })
                .toList();
        if (new HashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        return List.copyOf(normalized);
    }

    private static List<String> immutableRates(List<String> input) {
        if (input == null || input.isEmpty()) return List.of();
        if (input.size() > PaperEventDomain.MAX_VALUES_PER_TARGET) {
            throw new IllegalArgumentException(
                    "rates contains more than " + PaperEventDomain.MAX_VALUES_PER_TARGET + " entries");
        }
        List<String> normalized = input.stream()
                .map(PaperEnvironmentDomain::canonicalRate)
                .toList();
        if (new HashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException("rates must not contain duplicate numeric values");
        }
        return List.copyOf(normalized);
    }

    private static String canonicalRate(String value) {
        String normalized = requireText(value, "rate");
        String literal = normalized.startsWith(RATE_PREFIX)
                ? normalized.substring(RATE_PREFIX.length())
                : normalized;
        try {
            return RATE_PREFIX + Integer.parseInt(literal);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("rates must contain integer change rates", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void requireRange(Integer lower, Integer upper, String field) {
        if ((lower == null) != (upper == null)) {
            throw new IllegalArgumentException(field + " range requires both bounds");
        }
        if (lower != null && lower > upper) {
            throw new IllegalArgumentException(field + " lower bound must not exceed its upper bound");
        }
    }

    private static boolean containsValue(
            String value,
            List<String> entries,
            Integer lower,
            Integer upper,
            boolean rate) {
        if (value == null) return false;
        if (lower == null) return entries.contains(value);
        if (rate && !value.startsWith(RATE_PREFIX)) return false;
        String literal = rate && value.startsWith(RATE_PREFIX)
                ? value.substring(RATE_PREFIX.length())
                : value;
        try {
            int numeric = Integer.parseInt(literal);
            return numeric >= lower && numeric <= upper;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String randomValue(
            SplittableRandom random,
            List<String> entries,
            Integer lower,
            Integer upper,
            boolean rate,
            String excluded) {
        if (lower == null) {
            if (entries.isEmpty()) {
                throw new IllegalStateException("enumerated paper domain is empty");
            }
            return excluded == null
                    ? entries.get(random.nextInt(entries.size()))
                    : PaperRandomChoice.different(random, entries, excluded);
        }
        String excludedLiteral = rate && excluded != null && excluded.startsWith(RATE_PREFIX)
                ? excluded.substring(RATE_PREFIX.length())
                : excluded;
        long value = PaperRandomChoice.numeric(random, lower, upper, excludedLiteral);
        return rate ? RATE_PREFIX + value : Long.toString(value);
    }
}
