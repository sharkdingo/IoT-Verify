package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/** Legal direct values for one mandatory paper initial-state target. */
public record PaperInitialValueDomain(
        PaperInitialValue.Type type,
        String targetId,
        String property,
        List<String> legalValues,
        Integer lowerBound,
        Integer upperBound) {

    public PaperInitialValueDomain {
        type = Objects.requireNonNull(type, "type must not be null");
        targetId = requireText(targetId, "targetId");
        property = requireText(property, "property");
        legalValues = immutableValues(legalValues);
        if ((lowerBound == null) != (upperBound == null)) {
            throw new IllegalArgumentException("initial value range requires both bounds");
        }
        if (lowerBound != null && lowerBound > upperBound) {
            throw new IllegalArgumentException(
                    "initial value lower bound must not exceed its upper bound");
        }
        if (legalValues.isEmpty() == (lowerBound == null)) {
            throw new IllegalArgumentException(
                    "initial values must use either entries or a numeric range");
        }
    }

    public static PaperInitialValueDomain enumerated(
            PaperInitialValue.Type type,
            String targetId,
            String property,
            List<String> legalValues) {
        return new PaperInitialValueDomain(
                type, targetId, property, legalValues, null, null);
    }

    public static PaperInitialValueDomain numeric(
            PaperInitialValue.Type type,
            String targetId,
            String property,
            int lowerBound,
            int upperBound) {
        return new PaperInitialValueDomain(
                type, targetId, property, List.of(), lowerBound, upperBound);
    }

    public PaperInitialValue.Target target() {
        return new PaperInitialValue.Target(type, targetId, property);
    }

    public boolean isValidValue(String value) {
        if (value == null) return false;
        if (lowerBound == null) return legalValues.contains(value);
        try {
            long numeric = Long.parseLong(value);
            return numeric >= lowerBound && numeric <= upperBound;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public String randomValue(SplittableRandom random) {
        return randomValue(random, null);
    }

    public String differentValue(SplittableRandom random, String current) {
        return randomValue(random, current);
    }

    public boolean hasAlternativeValue(String current) {
        if (lowerBound != null) {
            return lowerBound < upperBound || !isValidValue(current);
        }
        return legalValues.size() > 1 || !legalValues.contains(current);
    }

    private String randomValue(SplittableRandom random, String excluded) {
        Objects.requireNonNull(random, "random must not be null");
        if (lowerBound == null) {
            return excluded == null
                    ? legalValues.get(random.nextInt(legalValues.size()))
                    : PaperRandomChoice.different(random, legalValues, excluded);
        }
        return Long.toString(PaperRandomChoice.numeric(random, lowerBound, upperBound, excluded));
    }

    private static List<String> immutableValues(List<String> input) {
        if (input == null || input.isEmpty()) return List.of();
        if (input.size() > PaperEventDomain.MAX_VALUES_PER_TARGET) {
            throw new IllegalArgumentException(
                    "initial legalValues exceeds " + PaperEventDomain.MAX_VALUES_PER_TARGET);
        }
        List<String> values = input.stream()
                .map(value -> requireText(value, "initial legal value"))
                .toList();
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(
                    "initial legalValues must not contain duplicates");
        }
        return List.copyOf(values);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
