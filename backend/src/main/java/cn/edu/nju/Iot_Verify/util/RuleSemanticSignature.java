package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/** Canonical, order-insensitive signature for the behavior represented by a board rule. */
public final class RuleSemanticSignature {

    private RuleSemanticSignature() {
    }

    public record Signature(
            String commandKey,
            Set<String> conditionKeys,
            Set<String> conditionShapeKeys
    ) {
    }

    public static Signature from(RuleDto rule) {
        return from(rule, null);
    }

    public static Signature from(RuleDto rule, ToIntFunction<String> modeCountForDevice) {
        if (rule == null) {
            return new Signature("", Set.of(), Set.of());
        }
        return new Signature(
                commandKey(rule.getCommand()),
                canonicalConditionKeys(rule.getConditions(), false, modeCountForDevice),
                canonicalConditionKeys(rule.getConditions(), true, modeCountForDevice));
    }

    /**
     * True when both rules command the same thing under exactly the same conditions.
     *
     * <p>Compares condition <em>multisets</em>, not the {@link Signature} sets. Order carries no
     * meaning in a conjunction, so this stays order-insensitive — but cardinality does: a rule
     * edited from {@code [C, C]} to {@code [C]} has changed. This predicate also gates the
     * "delete only if unchanged" and undo/redo conflict checks, where treating those two as equal
     * let a delete land on a rule the user had never reviewed.
     *
     * <p>{@code Signature} keeps set semantics deliberately: its consumer reasons about subset and
     * overlap between different rules, where collapsing duplicates is correct.
     */
    public static boolean exactlyMatches(RuleDto left, RuleDto right) {
        return exactlyMatches(left, right, null);
    }

    public static boolean exactlyMatches(RuleDto left, RuleDto right, ToIntFunction<String> modeCountForDevice) {
        return commandKey(left == null ? null : left.getCommand())
                .equals(commandKey(right == null ? null : right.getCommand()))
                && conditionMultiset(left, modeCountForDevice).equals(conditionMultiset(right, modeCountForDevice));
    }

    /** Canonical condition keys with their occurrence counts, so cardinality survives comparison. */
    private static Map<String, Long> conditionMultiset(RuleDto rule, ToIntFunction<String> modeCountForDevice) {
        if (rule == null) {
            return Map.of();
        }
        return canonicalConditionKeyList(rule.getConditions(), false, modeCountForDevice).stream()
                .collect(Collectors.groupingBy(key -> key, Collectors.counting()));
    }

    private static String commandKey(RuleDto.Command command) {
        if (command == null) {
            return "";
        }
        return String.join("|",
                normalize(command.getDeviceName()),
                normalize(command.getAction()),
                normalize(command.getContentDevice()),
                normalize(command.getContent()));
    }

    private static Set<String> canonicalConditionKeys(List<RuleDto.Condition> conditions, boolean shapeOnly,
                                                       ToIntFunction<String> modeCountForDevice) {
        return new TreeSet<>(canonicalConditionKeyList(conditions, shapeOnly, modeCountForDevice));
    }

    /**
     * One canonical key per condition, duplicates retained.
     *
     * <p>The set and multiset views are both derived from this list so a normalization change cannot
     * apply to only one of them.
     */
    private static List<String> canonicalConditionKeyList(List<RuleDto.Condition> conditions, boolean shapeOnly,
                                                          ToIntFunction<String> modeCountForDevice) {
        List<String> result = new ArrayList<>();
        if (conditions == null) {
            return result;
        }
        for (RuleDto.Condition condition : conditions) {
            if (condition == null) {
                continue;
            }
            String targetType = normalize(condition.getTargetType()).toLowerCase(Locale.ROOT);
            String relation = "api".equals(targetType) ? "" : normalizeRelation(condition.getRelation());
            String device = normalize(condition.getDeviceName());
            String value = shapeOnly || "api".equals(targetType)
                    ? ""
                    : normalizeConditionValue(condition.getValue(), relation, targetType, device, modeCountForDevice);
            result.add(String.join("|",
                    device,
                    targetType,
                    normalize(condition.getAttribute()),
                    relation,
                    value));
        }
        return result;
    }

    private static String normalizeConditionValue(String value, String relation, String targetType, String device,
                                                  ToIntFunction<String> modeCountForDevice) {
        if (modeCountForDevice != null) {
            String membership = SmvRelationUtils.canonicalStateMembershipValue(
                    value, relation, targetType, modeCountForDevice.applyAsInt(device));
            if (membership != null) return membership;
        }
        String normalized = normalize(value);
        if (!"in".equals(relation) && !"not in".equals(relation)) {
            return normalized;
        }
        String delimiter = "state".equals(targetType) ? "[,|]" : "[,;|]";
        return Arrays.stream(normalized.split(delimiter))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    /**
     * One owner for relation aliases: {@link SmvRelationUtils#normalizeRelation}.
     *
     * This was a private copy of that switch, and it had already drifted — its `NEQ` case omitted the `"!="`
     * alias the canonical version carries. The two agreed only by accident, through the passthrough default, so
     * adding an alias to the generator alone would have made a signature disagree with the model without any test
     * noticing.
     *
     * That matters more here than in most places. This signature gates duplicate detection, delete-if-unchanged
     * (`BoardStorageServiceImpl:1160`, `:4027`) and fix conflict detection (`FixStrategyUtils:152`) — so a
     * mismatch lands a delete or an undo on a record the user never reviewed.
     *
     * The empty-string contract is kept: a signature is a string key, and a `null` inside one would compare
     * unequal to an absent relation rather than equal to it.
     */
    private static String normalizeRelation(String relation) {
        String canonical = SmvRelationUtils.normalizeRelation(relation);
        return canonical == null ? "" : canonical;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
