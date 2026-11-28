package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Shared relation normalization and validation logic for SMV generation.
 * Used by SmvModelValidator, SmvMainModuleBuilder, SmvSpecificationBuilder, FixStrategyUtils, and
 * CounterexampleLemma.
 */
public final class SmvRelationUtils {

    private SmvRelationUtils() {
    }

    /**
     * Normalize a trigger relation string to its canonical NuSMV form.
     * Supports: EQ/== → =, NEQ/!= → !=, GT → >, GTE → >=, LT → <, LTE → <=
     */
    public static String normalizeTriggerRelation(String relation) {
        return normalizeRelation(relation, false);
    }

    /**
     * Check whether a (already-normalized) trigger relation is supported.
     */
    public static boolean isSupportedTriggerRelation(String relation) {
        return "=".equals(relation)
                || "!=".equals(relation)
                || ">".equals(relation)
                || ">=".equals(relation)
                || "<".equals(relation)
                || "<=".equals(relation);
    }

    /**
     * Normalize a rule/spec relation string, including IN/NOT_IN support.
     * Returns null for null input (consistent with normalizeTriggerRelation).
     */
    public static String normalizeRelation(String relation) {
        return normalizeRelation(relation, true);
    }

    private static String normalizeRelation(String relation, boolean allowMembership) {
        if (relation == null) return null;
        String normalized = relation.trim();
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "EQ", "==" -> "=";
            case "NEQ", "!=" -> "!=";
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            case "IN" -> allowMembership ? "in" : normalized;
            case "NOT_IN", "NOT IN" -> allowMembership ? "not in" : normalized;
            default -> normalized;
        };
    }

    /**
     * Check whether a (already-normalized) rule/spec relation is supported.
     */
    public static boolean isSupportedRelation(String relation) {
        return isSupportedTriggerRelation(relation) || isMembershipRelation(relation);
    }

    /** Enumeration conditions have no ordering; callers supply a normalized relation. */
    public static boolean isEnumRelation(String relation) {
        return "=".equals(relation) || "!=".equals(relation) || isMembershipRelation(relation);
    }

    private static boolean isMembershipRelation(String relation) {
        return "in".equals(relation) || "not in".equals(relation);
    }

    /**
     * A rule condition's relation as an SMV expression: IN/NOT_IN expand to {@code (x=a | x=b)} and
     * {@code (x!=a & x!=b)}, a single-element list without parentheses; any other relation is
     * {@code left + relation + value}. Returns {@code null} for an IN/NOT_IN with no values.
     *
     * <p>The one rendering of a rule-condition relation: the main module builds rule guards with it, and
     * condition adjustment's counterexample lemma rebuilds a free candidate's guard with it, so the lemma
     * cannot drift from the guard NuSMV actually evaluates.
     */
    public static String ruleRelationExpression(String left, String relation, String value) {
        if (isMembershipRelation(relation)) {
            List<String> cleaned = splitRuleValues(value);
            if (cleaned.isEmpty()) {
                return null;
            }
            String op = "in".equals(relation) ? "=" : "!=";
            String join = "in".equals(relation) ? " | " : " & ";
            if (cleaned.size() == 1) {
                return left + op + cleaned.get(0);
            }
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < cleaned.size(); i++) {
                if (i > 0) sb.append(join);
                sb.append(left).append(op).append(cleaned.get(i));
            }
            sb.append(")");
            return sb.toString();
        }
        return left + relation + value;
    }

    /**
     * Split operands at commas, semicolons or pipes, preserving authored order.
     * For whole-state tuples use {@link #splitStateRuleValues(String, int)}.
     */
    public static List<String> splitRuleValues(String value) {
        return splitValues(value, "[,;|]");
    }

    /**
     * Mode-aware split for state IN/NOT_IN values.
     * Multi-mode devices (modeCount &gt; 1) use `;` within tuple values (e.g. "cool;off"),
     * so only split by [,|]. Single-mode devices split by [,;|].
     */
    public static List<String> splitStateRuleValues(String value, int modeCount) {
        return splitValues(value, modeCount > 1 ? "[,|]" : "[,;|]");
    }

    private static List<String> splitValues(String value, String delimiter) {
        if (value == null) return List.of();
        String[] parts = value.split(delimiter);
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** Canonical set of authored operands, with wildcards kept in their original mode positions. */
    public static String canonicalStateMembershipValue(String value, int modeCount) {
        return splitStateRuleValues(value, modeCount).stream()
                .map(operand -> {
                    if (modeCount <= 1 || !operand.contains(";")) return operand;
                    return Arrays.stream(operand.split(";", -1))
                            .map(segment -> DeviceSmvDataFactory.isWildcardStateSegment(segment) ? "_" : segment.trim())
                            .collect(Collectors.joining(";"));
                })
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * Canonical membership for a known authored state or mode condition.
     * Returns null for other relations, other targets, or whole-state conditions without a resolved mode count.
     */
    public static String canonicalStateMembershipValue(
            String value, String normalizedRelation, String targetType, int stateModeCount) {
        if (!isMembershipRelation(normalizedRelation)) return null;
        if ("mode".equals(targetType)) return canonicalStateMembershipValue(value, 1);
        if ("state".equals(targetType) && stateModeCount > 0) {
            return canonicalStateMembershipValue(value, stateModeCount);
        }
        return null;
    }

    /** Compare authored operands when template-specific membership normalization does not apply. */
    public static String canonicalComparisonValue(String value, String normalizedRelation, String targetType) {
        String normalized = value == null ? "" : value.trim();
        if (!isMembershipRelation(normalizedRelation)) {
            return normalized;
        }
        // Whole-state operands can still contain positional tuples when their mode count is unknown.
        String delimiter = "state".equals(targetType) ? "[,|]" : "[,;|]";
        return Arrays.stream(normalized.split(delimiter))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /** Preserve an equality operand as one pattern, including its positional wildcards. */
    public static List<String> stateConditionValues(String value, String normalizedRelation, int modeCount) {
        if (isMembershipRelation(normalizedRelation)) {
            return splitStateRuleValues(value, modeCount);
        }
        return value == null || value.isBlank() ? List.of() : List.of(value.trim());
    }

    /**
     * Clean state literals as the main module builder does. Multi-mode membership uses commas and
     * pipes as list separators so semicolons inside a state tuple stay intact.
     */
    public static String cleanRuleValueByRelation(String normalizedRelation, String value, int modeCount) {
        if (value == null) return null;
        if (isMembershipRelation(normalizedRelation)) {
            List<String> parts = splitStateRuleValues(value, modeCount);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(DeviceSmvDataFactory.cleanStateName(parts.get(i)));
            }
            return sb.toString();
        }
        return DeviceSmvDataFactory.cleanStateName(value);
    }
}
