package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.aitool.RecommendationCapabilityView;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class RecommendationPromptContext {

    private RecommendationPromptContext() {
    }

    static Map<String, Object> currentDevice(DeviceInfoHelper.DeviceInfo device, DeviceTemplateDto template) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("deviceId", device.nodeId());
        fields.put("label", device.label());
        fields.put("templateName", device.templateName());
        fields.put("currentState", device.currentState());
        fields.put("currentStateTrust", device.currentStateTrust());
        fields.put("currentStatePrivacy", device.currentStatePrivacy());
        fields.put("initialVariables", safeList(device.instanceVariables()));
        fields.put("initialPrivacies", safeList(device.instancePrivacies()));
        fields.put("capabilities", RecommendationCapabilityView.fromManifest(
                template == null ? null : template.getManifest()));
        return fields;
    }

    static String deviceInfoJson(ObjectMapper objectMapper, List<DeviceInfoHelper.DeviceInfo> devices,
                                 List<DeviceTemplateDto> templates,
                                 List<BoardEnvironmentVariableDto> environmentVariables,
                                 Logger logger, DeviceFields appendFields) {
        try {
            Map<String, DeviceTemplateDto> templatesByName = RecommendationCapabilityView.indexTemplates(templates);
            List<Map<String, Object>> deviceList = new ArrayList<>();
            for (DeviceInfoHelper.DeviceInfo device : devices) {
                DeviceTemplateDto template = RecommendationCapabilityView.resolveTemplate(
                        templatesByName, device.templateName());
                Map<String, Object> fields = currentDevice(device, template);
                appendFields.append(device, template, fields);
                deviceList.add(fields);
            }
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("devices", deviceList);
            context.put("environmentVariables", environmentVariables == null
                    ? Collections.emptyList() : environmentVariables);
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            logger.error("Failed to build device info JSON", e);
            throw new IllegalStateException("Could not serialize current device capabilities for recommendation", e);
        }
    }

    @FunctionalInterface
    interface DeviceFields {
        void append(DeviceInfoHelper.DeviceInfo device, DeviceTemplateDto template, Map<String, Object> fields);
    }

    static List<DeviceInfoHelper.ApiInfo> apiSignals(DeviceInfoHelper.DeviceInfo device) {
        return safeList(device.apis()).stream()
                .filter(api -> Boolean.TRUE.equals(api.signal()) && api.name() != null)
                .toList();
    }

    static List<Map<String, Object>> modes(List<DeviceInfoHelper.ModeInfo> modes) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (DeviceInfoHelper.ModeInfo mode : safeList(modes)) {
            Map<String, Object> modeFields = new LinkedHashMap<>();
            modeFields.put("name", mode.name());
            modeFields.put("values", safeList(mode.values()));
            fields.add(modeFields);
        }
        return fields;
    }

    static String existingRules(ObjectMapper objectMapper, List<RuleDto> existingRules) {
        if (existingRules == null || existingRules.isEmpty()) {
            return "无现有规则";
        }

        try {
            List<Map<String, Object>> rulesInfo = new ArrayList<>();
            for (var rule : existingRules) {
                Map<String, Object> ruleInfo = new LinkedHashMap<>();
                ruleInfo.put("description", rule.getRuleString());
                ruleInfo.put("conditions", safeList(rule.getConditions()).stream().map(cond -> {
                    Map<String, Object> condition = new LinkedHashMap<>();
                    condition.put("deviceId", cond.getDeviceName());
                    condition.put("targetType", cond.getTargetType());
                    condition.put("attribute", cond.getAttribute());
                    condition.put("relation", cond.getRelation());
                    condition.put("value", cond.getValue());
                    return condition;
                }).toList());
                if (rule.getCommand() != null) {
                    Map<String, Object> command = new LinkedHashMap<>();
                    command.put("deviceId", rule.getCommand().getDeviceName());
                    command.put("action", rule.getCommand().getAction());
                    command.put("contentDevice", rule.getCommand().getContentDevice());
                    command.put("content", rule.getCommand().getContent());
                    ruleInfo.put("command", command);
                }
                rulesInfo.add(ruleInfo);
            }
            return objectMapper.writeValueAsString(rulesInfo);
        } catch (Exception e) {
            log.error("Failed to build existing rules info", e);
            throw new IllegalStateException("Could not serialize existing rules for recommendation", e);
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
