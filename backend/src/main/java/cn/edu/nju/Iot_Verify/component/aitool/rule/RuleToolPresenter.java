package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.util.BoardItemVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RuleToolPresenter {

    private RuleToolPresenter() {
    }

    public static Map<String, Object> present(Long userId, RuleDto rule, List<DeviceNodeDto> nodes) {
        Map<String, String> labelsById = labelsById(nodes);
        Map<String, Object> result = new LinkedHashMap<>();
        if (rule.getId() != null) {
            result.put("ruleId", rule.getId());
            result.put("expectedVersion", BoardItemVersion.rule(userId, rule));
        }
        putIfText(result, "label", rule.getRuleString());

        List<Map<String, Object>> conditions = new ArrayList<>();
        for (RuleDto.Condition condition : safeList(rule.getConditions())) {
            if (condition != null) {
                conditions.add(presentCondition(condition, labelsById));
            }
        }
        result.put("conditions", conditions);

        if (rule.getCommand() != null) {
            result.put("command", presentCommand(rule.getCommand(), labelsById));
        }
        result.put("description", describeRule(rule, labelsById));
        return result;
    }

    static boolean containsKeyword(Long userId, RuleDto rule, List<DeviceNodeDto> nodes, String keyword) {
        if (rule == null || keyword == null || keyword.isBlank()) {
            return rule != null;
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        Map<String, Object> searchable = present(userId, rule, nodes);
        searchable.remove("expectedVersion");
        String serialized = searchable.values().toString().toLowerCase(Locale.ROOT);
        return serialized.contains(normalized);
    }

    static Map<String, String> labelsById(List<DeviceNodeDto> nodes) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (DeviceNodeDto node : safeList(nodes)) {
            String id = node == null ? null : text(node.getId());
            if (id == null) {
                continue;
            }
            String label = text(node.getLabel());
            labels.put(id, label != null ? label : "Unknown device");
        }
        return labels;
    }

    static String describeRule(RuleDto rule, Map<String, String> labelsById) {
        List<String> conditionTexts = new ArrayList<>();
        for (RuleDto.Condition condition : safeList(rule.getConditions())) {
            if (condition != null) {
                conditionTexts.add(describeCondition(condition, labelsById));
            }
        }
        String command = rule.getCommand() == null
                ? "no valid action"
                : describeCommand(rule.getCommand(), labelsById);
        return "IF " + (conditionTexts.isEmpty() ? "no valid trigger" : String.join(" AND ", conditionTexts))
                + " THEN " + command;
    }

    private static Map<String, Object> presentCondition(RuleDto.Condition condition,
                                                         Map<String, String> labelsById) {
        Map<String, Object> result = new LinkedHashMap<>();
        putIfText(result, "deviceId", condition.getDeviceName());
        result.put("deviceLabel", displayLabel(condition.getDeviceName(), labelsById));
        putIfText(result, "attribute", condition.getAttribute());
        putIfText(result, "targetType", condition.getTargetType());
        putIfText(result, "relation", condition.getRelation());
        putIfText(result, "value", condition.getValue());
        result.put("summary", describeCondition(condition, labelsById));
        return result;
    }

    private static Map<String, Object> presentCommand(RuleDto.Command command,
                                                       Map<String, String> labelsById) {
        Map<String, Object> result = new LinkedHashMap<>();
        putIfText(result, "deviceId", command.getDeviceName());
        result.put("deviceLabel", displayLabel(command.getDeviceName(), labelsById));
        putIfText(result, "action", command.getAction());
        if (text(command.getContentDevice()) != null) {
            result.put("contentDevice", command.getContentDevice().trim());
            result.put("contentDeviceLabel", displayLabel(command.getContentDevice(), labelsById));
        }
        putIfText(result, "content", command.getContent());
        result.put("summary", describeCommand(command, labelsById));
        return result;
    }

    private static String describeCondition(RuleDto.Condition condition,
                                            Map<String, String> labelsById) {
        String device = displayLabel(condition.getDeviceName(), labelsById);
        String attribute = text(condition.getAttribute()) != null ? condition.getAttribute().trim() : "condition";
        if ("api".equalsIgnoreCase(text(condition.getTargetType()))) {
            return device + " emits " + attribute;
        }
        String relation = text(condition.getRelation()) != null ? condition.getRelation().trim() : "=";
        String value = text(condition.getValue()) != null ? condition.getValue().trim() : "value";
        return device + "." + attribute + " " + relation + " " + value;
    }

    private static String describeCommand(RuleDto.Command command,
                                          Map<String, String> labelsById) {
        String target = displayLabel(command.getDeviceName(), labelsById) + "."
                + (text(command.getAction()) != null ? command.getAction().trim() : "action");
        if (text(command.getContentDevice()) == null || text(command.getContent()) == null) {
            return target;
        }
        return target + " using " + displayLabel(command.getContentDevice(), labelsById)
                + "." + command.getContent().trim();
    }

    private static String displayLabel(String id, Map<String, String> labelsById) {
        String normalized = text(id);
        if (normalized == null) {
            return "Unknown device";
        }
        return labelsById.getOrDefault(normalized, "Unknown device");
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

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
