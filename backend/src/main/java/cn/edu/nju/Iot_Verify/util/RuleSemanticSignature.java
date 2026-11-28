package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    public record Duplicate(int firstIndex, int duplicateIndex) {
    }

    /** Find the earliest duplicate in board order, using the same condition counts as exactlyMatches. */
    public static Duplicate firstDuplicate(List<RuleDto> rules, ToIntFunction<String> modeCountForDevice) {
        Map<String, Integer> firstByCommand = new HashMap<>();
        Map<String, Map<Map<String, Long>, Integer>> conditionIndicesByCommand = new HashMap<>();
        for (int index = 0; index < rules.size(); index++) {
            RuleDto rule = rules.get(index);
            String command = commandKey(rule == null ? null : rule.getCommand());
            Integer first = firstByCommand.putIfAbsent(command, index);
            if (first == null) {
                continue;
            }
            // Delay normalizing condition lists until a second rule has the same command.
            Map<Map<String, Long>, Integer> conditionIndices = conditionIndicesByCommand.computeIfAbsent(command, key -> {
                Map<Map<String, Long>, Integer> indices = new HashMap<>();
                indices.put(conditionMultiset(rules.get(first), modeCountForDevice), first);
                return indices;
            });
            Integer previous = conditionIndices.putIfAbsent(conditionMultiset(rule, modeCountForDevice), index);
            if (previous != null) {
                return new Duplicate(previous, index);
            }
        }
        return null;
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
            String relation = "api".equals(targetType) ? ""
                    : Objects.toString(SmvRelationUtils.normalizeRelation(condition.getRelation()), "");
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
        return SmvRelationUtils.canonicalComparisonValue(value, relation, targetType);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
