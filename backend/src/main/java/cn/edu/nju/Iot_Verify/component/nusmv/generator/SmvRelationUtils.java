package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        if (relation == null) return null;
        String normalized = relation.trim();
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "EQ", "==" -> "=";
            case "NEQ", "!=" -> "!=";
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            default -> normalized;
        };
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
        if (relation == null) return null;
        String normalized = relation.trim();
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "EQ", "==" -> "=";
            case "NEQ", "!=" -> "!=";
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            case "IN" -> "in";
            case "NOT_IN", "NOT IN" -> "not in";
            default -> normalized;
        };
    }

    /**
     * Check whether a (already-normalized) rule/spec relation is supported.
     */
    public static boolean isSupportedRelation(String relation) {
        return "=".equals(relation)
                || "!=".equals(relation)
                || ">".equals(relation)
                || ">=".equals(relation)
                || "<".equals(relation)
                || "<=".equals(relation)
                || "in".equals(relation)
                || "not in".equals(relation);
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
        if ("in".equals(relation) || "not in".equals(relation)) {
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
     * Split value by ,;| delimiters for IN/NOT_IN; for other relations return the value as single-element list.
     * <p><b>Note:</b> For multi-mode devices where `;` is used inside tuple values (e.g. "cool;off"),
     * use {@link #splitStateRuleValues(String, int)} instead to avoid splitting within tuples.</p>
     */
    public static List<String> splitRuleValues(String value) {
        if (value == null) return List.of();
        String[] parts = value.split("[,;|]");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Mode-aware split for state IN/NOT_IN values.
     * Multi-mode devices (modeCount &gt; 1) use `;` within tuple values (e.g. "cool;off"),
     * so only split by [,|]. Single-mode devices split by [,;|].
     * Matches SmvMainModuleBuilder.splitStateRuleCandidates() semantics.
     */
    public static List<String> splitStateRuleValues(String value, int modeCount) {
        if (value == null) return List.of();
        String splitRegex = (modeCount > 1) ? "[,|]" : "[,;|]";
        String[] parts = value.split(splitRegex);
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Clean state value via cleanStateName; for IN/NOT_IN, clean each segment individually.
     * Matches the normalization semantics used by SmvMainModuleBuilder during SMV generation.
     */
    public static String cleanRuleValueByRelation(String normalizedRelation, String value) {
        return cleanRuleValueByRelation(normalizedRelation, value, 1);
    }

    /**
     * Mode-aware variant: for IN/NOT_IN with multi-mode devices, splits by [,|] only
     * (preserving ; within tuple values). For single-mode or non-IN relations, behaves identically.
     */
    public static String cleanRuleValueByRelation(String normalizedRelation, String value, int modeCount) {
        if (value == null) return null;
        if ("in".equals(normalizedRelation) || "not in".equals(normalizedRelation)) {
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
