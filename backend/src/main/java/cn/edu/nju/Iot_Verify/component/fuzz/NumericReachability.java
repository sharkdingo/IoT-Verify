package cn.edu.nju.Iot_Verify.component.fuzz;

import java.util.Set;

/** Time-scoped support and atomic distances for an integer with only natural changes. */
record NumericReachability(int initial, int lower, int upper, int lowerRate, int upperRate) {

    NumericReachability {
        if (lower > upper || initial < lower || initial > upper || lowerRate > upperRate) {
            throw new IllegalArgumentException("Invalid numeric reachability bounds.");
        }
    }

    double distanceAt(int step, int actual, String normalizedRelation, Set<Integer> expected, boolean truth) {
        if (step < 0) {
            throw new IllegalArgumentException("Reachability step must be non-negative.");
        }
        long minimum = Math.max(lower, Math.min(upper, initial + step * (long) lowerRate));
        long maximum = Math.max(lower, Math.min(upper, initial + step * (long) upperRate));
        return distanceInRange(actual, (int) minimum, (int) maximum, normalizedRelation, expected, truth);
    }

    static double distanceInRange(
            int actual, int lower, int upper, String normalizedRelation, Set<Integer> expected, boolean truth) {
        if (lower > upper) {
            return Double.POSITIVE_INFINITY;
        }
        // A false target is the opposite relation, not a negated existence test.
        String relation = truth ? normalizedRelation : switch (normalizedRelation) {
            case "=" -> "!=";
            case "!=" -> "=";
            case ">" -> "<=";
            case ">=" -> "<";
            case "<" -> ">=";
            case "<=" -> ">";
            case "in" -> "not in";
            case "not in" -> "in";
            default -> throw new IllegalArgumentException("Unsupported numeric relation: " + normalizedRelation);
        };
        if (!"in".equals(relation) && !"not in".equals(relation) && expected.size() != 1) {
            throw new IllegalArgumentException("A numeric comparison requires one expected value.");
        }
        if ("in".equals(relation) || "=".equals(relation)) {
            double distance = Double.POSITIVE_INFINITY;
            for (int value : expected) {
                if (value >= lower && value <= upper) {
                    distance = Math.min(distance, Math.abs((long) actual - value));
                }
            }
            return distance;
        }
        if ("not in".equals(relation) || "!=".equals(relation)) {
            return distanceOutsideSet(actual, lower, upper, expected);
        }
        int value = expected.iterator().next();
        return switch (relation) {
            case ">" -> distanceToInterval(actual, Math.max(lower, (long) value + 1), upper);
            case ">=" -> distanceToInterval(actual, Math.max(lower, value), upper);
            case "<" -> distanceToInterval(actual, lower, Math.min(upper, (long) value - 1));
            case "<=" -> distanceToInterval(actual, lower, Math.min(upper, value));
            default -> throw new IllegalArgumentException("Unsupported numeric relation: " + normalizedRelation);
        };
    }

    private static double distanceOutsideSet(int actual, int lower, int upper, Set<Integer> members) {
        long closest = Math.max(lower, Math.min(upper, (long) actual));
        // Walk only the containing member block, bounded by this step's support.
        long below = closest;
        while (below >= lower && members.contains((int) below)) below--;
        long above = closest;
        while (above <= upper && members.contains((int) above)) above++;
        double belowDistance = below >= lower ? Math.abs((long) actual - below) : Double.POSITIVE_INFINITY;
        double aboveDistance = above <= upper ? Math.abs(above - (long) actual) : Double.POSITIVE_INFINITY;
        return Math.min(belowDistance, aboveDistance);
    }

    private static double distanceToInterval(int actual, long lower, long upper) {
        if (lower > upper) {
            return Double.POSITIVE_INFINITY;
        }
        long closest = Math.max(lower, Math.min(upper, (long) actual));
        return Math.abs((long) actual - closest);
    }
}
