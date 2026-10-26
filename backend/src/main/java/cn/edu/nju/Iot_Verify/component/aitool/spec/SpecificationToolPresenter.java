package cn.edu.nju.Iot_Verify.component.aitool.spec;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.BoardItemVersion;
import cn.edu.nju.Iot_Verify.util.SpecificationFormulaPreview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SpecificationToolPresenter {

    private SpecificationToolPresenter() {
    }

    public static SpecificationFormulaPreview.Context context(List<DeviceNodeDto> nodes,
                                                              List<DeviceTemplateDto> templates) {
        return SpecificationFormulaPreview.context(nodes, templates);
    }

    public static Map<String, Object> present(Long userId, SpecificationDto spec,
                                              SpecificationFormulaPreview.Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        putIfText(result, "specId", spec.getId());
        result.put("name", spec.getName());
        if (text(spec.getId()) != null) {
            result.put("expectedVersion", BoardItemVersion.specification(userId, spec));
        }
        putIfText(result, "templateId", spec.getTemplateId());
        putIfText(result, "templateLabel", SpecificationFormulaPreview.templateLabel(spec.getTemplateId()));
        result.put("aConditions", presentConditions(spec.getAConditions(), context));
        result.put("ifConditions", presentConditions(spec.getIfConditions(), context));
        result.put("thenConditions", presentConditions(spec.getThenConditions(), context));
        putIfText(result, "formulaPreview", SpecificationFormulaPreview.format(spec, context));
        result.put("description", describe(spec, context));
        return result;
    }

    static boolean containsKeyword(Long userId, SpecificationDto spec,
                                   SpecificationFormulaPreview.Context context,
                                   String keyword) {
        if (spec == null || keyword == null || keyword.isBlank()) {
            return spec != null;
        }
        Map<String, Object> searchable = present(userId, spec, context);
        searchable.remove("expectedVersion");
        return searchable.values().toString()
                .toLowerCase(Locale.ROOT)
                .contains(keyword.toLowerCase(Locale.ROOT));
    }

    private static List<Map<String, Object>> presentConditions(
            List<SpecConditionDto> conditions,
            SpecificationFormulaPreview.Context context) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SpecConditionDto condition : conditions == null ? List.<SpecConditionDto>of() : conditions) {
            if (condition == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            putIfText(row, "deviceId", condition.getDeviceId());
            row.put("deviceLabel", context.displayLabel(condition));
            putIfText(row, "targetType", condition.getTargetType());
            putIfText(row, "key", condition.getKey());
            putIfText(row, "propertyScope", condition.getPropertyScope());
            // The structured row is what the assistant rebuilds a condition from when asked to edit one.
            // With the reading only in the prose summary it could describe a specification correctly and
            // still round-trip it into the other question, or omit the now-required field and be rejected.
            putIfText(row, "variableSource", condition.getVariableSource());
            putIfText(row, "relation", condition.getRelation());
            putIfText(row, "value", condition.getValue());
            row.put("summary", describeCondition(condition, context));
            result.add(row);
        }
        return result;
    }

    private static String describe(SpecificationDto spec,
                                   SpecificationFormulaPreview.Context context) {
        List<String> groups = new ArrayList<>();
        appendGroup(groups, "A", spec.getAConditions(), context);
        appendGroup(groups, "IF", spec.getIfConditions(), context);
        appendGroup(groups, "THEN", spec.getThenConditions(), context);
        String label = SpecificationFormulaPreview.templateLabel(spec.getTemplateId());
        return groups.isEmpty() ? label : label + " [" + String.join("; ", groups) + "]";
    }

    private static void appendGroup(List<String> groups,
                                    String group,
                                    List<SpecConditionDto> conditions,
                                    SpecificationFormulaPreview.Context context) {
        List<String> parts = new ArrayList<>();
        for (SpecConditionDto condition : conditions == null ? List.<SpecConditionDto>of() : conditions) {
            if (condition != null) {
                parts.add(describeCondition(condition, context));
            }
        }
        if (!parts.isEmpty()) {
            groups.add(group + ": " + String.join(" AND ", parts));
        }
    }

    private static String describeCondition(SpecConditionDto condition,
                                            SpecificationFormulaPreview.Context context) {
        String label = context.displayLabel(condition);
        String targetType = text(condition.getTargetType());
        String key = text(condition.getKey());
        String subject;
        if ("state".equalsIgnoreCase(targetType)) {
            subject = label + " state";
        } else if (("trust".equalsIgnoreCase(targetType) || "privacy".equalsIgnoreCase(targetType))
                && text(condition.getPropertyScope()) != null) {
            subject = label + " " + condition.getPropertyScope().trim() + " "
                    + (key != null ? key : "property") + " " + targetType;
        } else if ("variable".equalsIgnoreCase(targetType)) {
            // The declared question, not an inference from sharedness. This prose is what the assistant
            // reads back when describing existing specifications, so guessing here would have it tell the
            // user their spec is about the home when it is about what one device reported.
            String variableSource = text(condition.getVariableSource());
            String named = key != null ? key : "property";
            if (variableSource == null) {
                subject = "<unresolved>." + named;
            } else if ("environment".equalsIgnoreCase(variableSource.trim())) {
                subject = "Environment." + named;
            } else {
                subject = label + "." + named;
            }
        } else {
            subject = label + "." + (key != null ? key : targetType != null ? targetType : "property");
        }
        String relation = text(condition.getRelation()) != null ? condition.getRelation().trim() : "=";
        String value = text(condition.getValue()) != null ? condition.getValue().trim() : "TRUE";
        return subject + " " + relation + " " + value;
    }

    private static void putIfText(Map<String, Object> target, String key, String value) {
        String normalized = text(value);
        if (normalized != null) {
            target.put(key, normalized);
        }
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
