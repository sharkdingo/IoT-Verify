package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

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
        return from(specification, null);
    }

    public static Signature from(SpecificationDto specification, ToIntFunction<String> modeCountForDevice) {
        if (specification == null) {
            return new Signature("", Map.of(), Map.of(), Map.of());
        }
        return new Signature(
                normalize(specification.getTemplateId()),
                conditionKeys(specification.getAConditions(), modeCountForDevice),
                conditionKeys(specification.getIfConditions(), modeCountForDevice),
                conditionKeys(specification.getThenConditions(), modeCountForDevice));
    }

    public static boolean exactlyMatches(SpecificationDto left, SpecificationDto right) {
        return from(left).equals(from(right));
    }

    public static boolean exactlyMatches(SpecificationDto left, SpecificationDto right,
                                         ToIntFunction<String> modeCountForDevice) {
        return from(left, modeCountForDevice).equals(from(right, modeCountForDevice));
    }

    /**
     * Canonical condition keys with their occurrence counts.
     *
     * <p>A multiset rather than a set: cardinality is part of a specification's identity, and this
     * signature gates the "delete only if unchanged" and duplicate-spec checks. Collapsing a
     * repeated condition let a delete land on a specification that had genuinely been edited.
     */
    private static Map<String, Long> conditionKeys(List<SpecConditionDto> conditions,
                                                   ToIntFunction<String> modeCountForDevice) {
        Map<String, Long> keys = new TreeMap<>();
        if (conditions == null) {
            return keys;
        }
        for (SpecConditionDto condition : conditions) {
            if (condition == null) {
                continue;
            }
            String targetType = normalize(condition.getTargetType()).toLowerCase(Locale.ROOT);
            String relation = Objects.toString(SmvRelationUtils.normalizeRelation(condition.getRelation()), "");
            String device = normalize(condition.getDeviceId());
            String value = modeCountForDevice == null ? null : SmvRelationUtils.canonicalStateMembershipValue(
                    condition.getValue(), relation, targetType, modeCountForDevice.applyAsInt(device));
            keys.merge(String.join("|",
                    device,
                    targetType,
                    normalize(condition.getPropertyScope()).toLowerCase(Locale.ROOT),
                    // Part of authored identity: the same key asked as `environment` (the value in the
                    // home) and as `reported` (what the device said) are two different specifications.
                    // Without it, "delete only if unchanged" and the undo conflict check would treat one
                    // as the other and land a delete on a record the user never reviewed.
                    normalize(condition.getVariableSource()).toLowerCase(Locale.ROOT),
                    normalize(condition.getKey()),
                    relation,
                    value != null ? value : canonicalConditionValue(condition.getValue(), relation, targetType)),
                    1L, Long::sum);
        }
        return keys;
    }

    /** Canonical value for a comparison key, with an already normalized relation and target type. */
    public static String canonicalConditionValue(String value, String relation, String targetType) {
        String operand = "api".equals(targetType) ? ManifestConditionSemantics.canonicalApiSignalValue(value) : value;
        return SmvRelationUtils.canonicalComparisonValue(operand, relation, targetType);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
