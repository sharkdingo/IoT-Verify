package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/** Uniform choices for paper-domain mutation without allocating filtered copies. */
final class PaperRandomChoice {

    private PaperRandomChoice() {
    }

    static <T> T different(SplittableRandom random, List<T> values, T excluded) {
        Objects.requireNonNull(random, "random");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        int excludedIndex = values.indexOf(excluded);
        if (excludedIndex < 0) {
            return values.get(random.nextInt(values.size()));
        }
        int compactIndex = random.nextInt(values.size() - 1);
        return values.get(alternativeIndex(values.size(), excludedIndex, compactIndex));
    }

    static long numeric(SplittableRandom random, int lower, int upper, String excluded) {
        // A domain covering all signed integers is wider than an int index can represent.
        long size = (long) upper - lower + 1L;
        long excludedOffset = numericOffset(excluded, lower, upper);
        long available = excludedOffset >= 0L ? size - 1L : size;
        if (available <= 0L) return lower;
        long offset = random.nextLong(available);
        if (excludedOffset >= 0L && offset >= excludedOffset) offset++;
        return lower + offset;
    }

    private static long numericOffset(String value, int lower, int upper) {
        if (value == null) return -1L;
        try {
            long numeric = Long.parseLong(value);
            return numeric < lower || numeric > upper ? -1L : numeric - lower;
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    static int alternativeIndex(int size, int excludedIndex, int compactIndex) {
        if (size < 2 || excludedIndex < 0 || excludedIndex >= size
                || compactIndex < 0 || compactIndex >= size - 1) {
            throw new IllegalArgumentException("invalid alternative index arguments");
        }
        return compactIndex < excludedIndex ? compactIndex : compactIndex + 1;
    }
}
