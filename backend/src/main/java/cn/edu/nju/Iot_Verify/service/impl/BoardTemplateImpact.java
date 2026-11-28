package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateAffectedDeviceDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetBlockerDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDeletionBlockerDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDeletionResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import cn.edu.nju.Iot_Verify.service.board.BoardEditHistoryState;
import cn.edu.nju.Iot_Verify.util.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class BoardTemplateImpact {

    private BoardTemplateImpact() {
    }

    static DeviceTemplateDeletionResultDto previewDeletion(
            DeviceTemplateDto target,
            List<DeviceNodePo> nodes,
            List<DeviceTemplateDto> currentTemplates,
            BoardEditHistoryState editHistory) {
        List<DeviceTemplateDeletionBlockerDto> blockers = nodes.stream()
                .filter(Objects::nonNull)
                .filter(node -> hasText(node.getTemplateName())
                        && node.getTemplateName().trim().equalsIgnoreCase(target.getName()))
                .sorted(Comparator.comparing(DeviceNodePo::getId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(node -> DeviceTemplateDeletionBlockerDto.builder()
                        .reasonCode("DEVICE_INSTANCE_USES_TEMPLATE")
                        .itemId(node.getId())
                        .itemLabel(firstText(node.getLabel(), node.getId()))
                        .reason("This canvas device instance uses the device type.")
                        .build())
                .toList();
        String impactToken = templateDeletionImpactToken(target, blockers, editHistory);
        return DeviceTemplateDeletionResultDto.builder()
                .operation("preview")
                .impactToken(impactToken)
                .canDelete(blockers.isEmpty())
                .editHistoryEntryCount(editHistory.entryCount())
                .template(target)
                .blockers(blockers)
                .currentTemplates(currentTemplates)
                .build();
    }

    private static String templateDeletionImpactToken(
            DeviceTemplateDto template,
            List<DeviceTemplateDeletionBlockerDto> blockers,
            BoardEditHistoryState editHistory) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("contract", "device-template-deletion-v2");
        impact.put("template", template);
        impact.put("blockers", blockers != null ? blockers : List.of());
        impact.put("editHistoryImpactToken", editHistory.impactToken());
        return impactToken(impact, "Unable to create template-deletion impact token");
    }

    static List<DefaultTemplateResetBlockerDto> resetBlockers(
            Map<String, String> errors,
            List<DeviceNodeDto> nodes,
            List<RuleDto> rules,
            List<SpecificationDto> specs) {
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, DefaultTemplateResetBlockerDto> blockers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            String reasonCode = resetBlockerReasonCode(entry.getKey());
            String label = "ENVIRONMENT_POOL_INCOMPATIBLE".equals(reasonCode)
                    ? "Environment Pool" : resetBlockerLabel(entry.getKey(), nodes, rules, specs);
            String key = label + "\n" + entry.getValue();
            blockers.putIfAbsent(key, DefaultTemplateResetBlockerDto.builder()
                    .itemLabel(label)
                    .reasonCode(reasonCode)
                    .reason(entry.getValue())
                    .build());
        }
        return List.copyOf(blockers.values());
    }

    private static String resetBlockerReasonCode(String field) {
        String normalized = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("nodes[")) return "DEVICE_INSTANCE_INCOMPATIBLE";
        if (normalized.startsWith("rules[")) return "AUTOMATION_RULE_INCOMPATIBLE";
        if (normalized.startsWith("specs[") || normalized.startsWith("specifications[")) {
            return "SPECIFICATION_INCOMPATIBLE";
        }
        if ("environmentvariables".equals(normalized)
                || normalized.startsWith("environmentvariables[") || normalized.startsWith("environment.")) {
            return "ENVIRONMENT_POOL_INCOMPATIBLE";
        }
        return "BOARD_MODEL_INCOMPATIBLE";
    }

    private static String resetBlockerLabel(String field,
                                     List<DeviceNodeDto> nodes,
                                     List<RuleDto> rules,
                                     List<SpecificationDto> specs) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(nodes|rules|specs)\\[(\\d+)]")
                .matcher(field == null ? "" : field);
        if (!matcher.find()) {
            return "Board model";
        }
        int index = Integer.parseInt(matcher.group(2));
        return switch (matcher.group(1)) {
            case "nodes" -> index < nodes.size() && nodes.get(index) != null
                    ? "Device: " + firstText(nodes.get(index).getLabel(), nodes.get(index).getTemplateName())
                    : "Device";
            case "rules" -> index < rules.size() && rules.get(index) != null
                    ? "Rule: " + firstText(rules.get(index).getRuleString(), "Rule " + (index + 1))
                    : "Rule " + (index + 1);
            case "specs" -> index < specs.size() && specs.get(index) != null
                    ? "Specification: " + specs.get(index).getName()
                    : "Specification " + (index + 1);
            default -> "Board model";
        };
    }

    static String firstText(String preferred, String fallback) {
        return hasText(preferred) ? preferred.trim() : (hasText(fallback) ? fallback.trim() : "Unknown item");
    }

    static String resetImpactToken(
            List<DeviceTemplateDto> currentTemplates,
            List<DeviceTemplateDto> defaultTemplates,
            List<DeviceNodeDto> nodes,
            List<RuleDto> rules,
            List<SpecificationDto> specs,
            List<BoardEnvironmentVariableDto> environment,
            List<DefaultTemplateResetChangeDto> changes,
            List<DefaultTemplateAffectedDeviceDto> affectedDevices,
            List<EnvironmentVariableChangeDto> environmentChanges,
            Map<String, String> validationErrors,
            BoardEditHistoryState editHistory) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("contract", "default-template-reset-v2");
        impact.put("currentTemplates", currentTemplates);
        impact.put("defaultTemplates", defaultTemplates);
        impact.put("nodes", nodes);
        impact.put("rules", rules);
        impact.put("specifications", specs);
        impact.put("environmentVariables", environment);
        impact.put("templateChanges", changes);
        impact.put("affectedDevices", affectedDevices);
        impact.put("environmentChanges", environmentChanges);
        impact.put("validationErrors", validationErrors);
        impact.put("editHistoryImpactToken", editHistory.impactToken());
        return impactToken(impact, "Unable to create default-template reset impact token");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String impactToken(Map<String, Object> impact, String failureMessage) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtils.toJson(impact).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new InternalServerException(failureMessage, failure);
        }
    }
}
