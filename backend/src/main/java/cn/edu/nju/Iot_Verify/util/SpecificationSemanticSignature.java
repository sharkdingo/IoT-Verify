package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Canonical signature for the authored inputs from which a specification is rebuilt. */
public final class SpecificationSemanticSignature {

    private SpecificationSemanticSignature() {
    }

    public record Signature(
            String templateId,
            Map<String, Long> aConditions,
            Map<String, Long> ifConditions,
            Map<String, Long> thenConditions
    ) {
    }

    public static Signature from(SpecificationDto specification) {
        if (specification == null) {
            return new Signature("", Map.of(), Map.of(), Map.of());
        }
        return new Signature(
                normalize(specification.getTemplateId()),
                conditionKeys(specification.getAConditions()),
                conditionKeys(specification.getIfConditions()),
                conditionKeys(specification.getThenConditions()));
    }

    public static boolean exactlyMatches(SpecificationDto left, SpecificationDto right) {
        return from(left).equals(from(right));
    }

    /**
     * Canonical condition keys with their occurrence counts.
     *
     * <p>A multiset rather than a set: cardinality is part of a specification's identity, and this
     * signature gates the "delete only if unchanged" and duplicate-spec checks. Collapsing a
     * repeated condition let a delete land on a specification that had genuinely been edited.
     */
    private static Map<String, Long> conditionKeys(List<SpecConditionDto> conditions) {
        Map<String, Long> keys = new TreeMap<>();
        if (conditions == null) {
            return keys;
        }
        for (SpecConditionDto condition : conditions) {
            if (condition == null) {
                continue;
            }
            String targetType = normalize(condition.getTargetType()).toLowerCase(Locale.ROOT);
            String relation = normalizeRelation(condition.getRelation());
            keys.merge(String.join("|",
                    normalize(condition.getDeviceId()),
                    targetType,
                    normalize(condition.getPropertyScope()).toLowerCase(Locale.ROOT),
                    // Part of authored identity: the same key asked as `environment` (the value in the
                    // home) and as `reported` (what the device said) are two different specifications.
                    // Without it, "delete only if unchanged" and the undo conflict check would treat one
                    // as the other and land a delete on a record the user never reviewed.
                    normalize(condition.getVariableSource()).toLowerCase(Locale.ROOT),
                    normalize(condition.getKey()),
                    relation,
                    normalizeValue(condition.getValue(), relation, targetType)), 1L, Long::sum);
        }
        return keys;
    }

    private static String normalizeValue(String value, String relation, String targetType) {
        String normalized = normalize(value);
        if ("api".equals(targetType)) {
            normalized = normalized.toUpperCase(Locale.ROOT);
        }
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
