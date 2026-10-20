package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 辅助类：获取设备节点及其对应模板的详细信息
 * 用于规则推荐时获取设备的真实 API 和内部变量
 */
@Slf4j
@Component
public class DeviceInfoHelper {

    /**
     * 从已捕获的画布快照投影设备及模板信息
     */
    public List<DeviceInfo> getDevicesWithTemplateInfo(BoardSemanticSnapshotDto snapshot) {
        List<DeviceNodeDto> nodes = snapshot.nodes();
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyList();
        }

        List<DeviceTemplateDto> templates = snapshot.deviceTemplates();
        Map<String, DeviceTemplateDto> templateMap = buildTemplateMap(templates);

        return nodes.stream()
                .filter(n -> n != null && n.getTemplateName() != null)
                .map(n -> {
                    DeviceTemplateDto template = templateMap.get(normalizeTemplateKey(n.getTemplateName()));
                    return new DeviceInfo(
                            n.getId(),
                            n.getLabel(),
                            n.getTemplateName(),
                            n.getState(),
                            n.getCurrentStateTrust(),
                            n.getCurrentStatePrivacy(),
                            n.getVariables(),
                            n.getPrivacies(),
                            extractVariables(template),
                            extractApis(template),
                            extractTransitions(template),
                            extractModes(template),
                            extractWorkingStates(template),
                            extractContents(template)
                    );
                })
                .collect(Collectors.toList());
    }

    private Map<String, DeviceTemplateDto> buildTemplateMap(List<DeviceTemplateDto> templates) {
        if (templates == null || templates.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, DeviceTemplateDto> templateMap = new HashMap<>();
        for (DeviceTemplateDto template : templates) {
            if (template == null) {
                continue;
            }
            putTemplateAlias(templateMap, template.getName(), template);
            if (template.getManifest() != null) {
                putTemplateAlias(templateMap, template.getManifest().getName(), template);
            }
        }
        return templateMap;
    }

    private void putTemplateAlias(Map<String, DeviceTemplateDto> templateMap,
                                  String rawName,
                                  DeviceTemplateDto template) {
        String key = normalizeTemplateKey(rawName);
        if (key != null) {
            templateMap.putIfAbsent(key, template);
        }
    }

    private String normalizeTemplateKey(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        return rawName.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 提取模板中的内部变量
     */
    private List<VariableInfo> extractVariables(DeviceTemplateDto template) {
        if (template == null || template.getManifest() == null
                || template.getManifest().getInternalVariables() == null) {
            return Collections.emptyList();
        }

        return template.getManifest().getInternalVariables().stream()
                .map(v -> new VariableInfo(
                        v.getName(),
                        v.getDescription(),
                        v.getValues(),
                        v.getLowerBound(),
                        v.getUpperBound(),
                        v.getTrust(),
                        v.getPrivacy()
                ))
                .collect(Collectors.toList());
    }

    /**
     * 提取模板中的 API
     */
    private List<ApiInfo> extractApis(DeviceTemplateDto template) {
        if (template == null || template.getManifest() == null
                || template.getManifest().getApis() == null) {
            return Collections.emptyList();
        }

        return template.getManifest().getApis().stream()
                .map(a -> new ApiInfo(
                        a.getName(),
                        a.getDescription(),
                        a.getSignal(),
                        a.getAcceptsContent(),
                        a.getStartState(),
                        a.getEndState()
                ))
                .collect(Collectors.toList());
    }

    /**
     * 提取模板中的状态转换
     */
    private List<TransitionInfo> extractTransitions(DeviceTemplateDto template) {
        if (template == null || template.getManifest() == null
                || template.getManifest().getTransitions() == null) {
            return Collections.emptyList();
        }

        return template.getManifest().getTransitions().stream()
                .map(t -> new TransitionInfo(
                        t.getName(),
                        t.getDescription(),
                        t.getStartState(),
                        t.getEndState()
                ))
                .collect(Collectors.toList());
    }

    /**
     * 提取工作状态
     */
    private List<String> extractWorkingStates(DeviceTemplateDto template) {
        if (template == null || template.getManifest() == null
                || template.getManifest().getWorkingStates() == null) {
            return Collections.emptyList();
        }
        return template.getManifest().getWorkingStates().stream()
                .map(DeviceTemplateDto.DeviceManifest.WorkingState::getName)
                .collect(Collectors.toList());
    }

    private List<ContentInfo> extractContents(DeviceTemplateDto template) {
        if (template == null || template.getManifest() == null
                || template.getManifest().getContents() == null) {
            return Collections.emptyList();
        }
        return template.getManifest().getContents().stream()
                .filter(Objects::nonNull)
                .map(content -> new ContentInfo(content.getName(), content.getPrivacy()))
                .collect(Collectors.toList());
    }

    /**
     * 提取模式和值域。多模式设备的 WorkingStates 使用 "modeValue;stateValue" 元组，
     * 这里按 Modes 中的顺序拆出每个 mode 的可选值，供 AI 推荐和后端校验使用。
     */
    private List<ModeInfo> extractModes(DeviceTemplateDto template) {
        if (template == null || template.getManifest() == null
                || template.getManifest().getModes() == null
                || template.getManifest().getModes().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> modes = template.getManifest().getModes();
        Map<String, LinkedHashSet<String>> valuesByMode = new LinkedHashMap<>();
        for (String mode : modes) {
            if (mode != null && !mode.isBlank()) {
                valuesByMode.put(mode, new LinkedHashSet<>());
            }
        }

        List<DeviceTemplateDto.DeviceManifest.WorkingState> states = template.getManifest().getWorkingStates();
        if (states != null) {
            for (DeviceTemplateDto.DeviceManifest.WorkingState state : states) {
                String name = state != null ? state.getName() : null;
                if (name == null || name.isBlank()) {
                    continue;
                }
                String[] parts = name.split(";", -1);
                if (modes.size() == 1) {
                    addModeValue(valuesByMode, modes.get(0), name);
                } else if (parts.length == modes.size()) {
                    for (int i = 0; i < modes.size(); i++) {
                        addModeValue(valuesByMode, modes.get(i), parts[i]);
                    }
                }
            }
        }

        return valuesByMode.entrySet().stream()
                .map(e -> new ModeInfo(e.getKey(), new ArrayList<>(e.getValue())))
                .collect(Collectors.toList());
    }

    private void addModeValue(Map<String, LinkedHashSet<String>> valuesByMode, String mode, String rawValue) {
        if (mode == null || rawValue == null) {
            return;
        }
        LinkedHashSet<String> values = valuesByMode.get(mode);
        if (values == null) {
            return;
        }
        String value = rawValue.trim();
        if (!value.isBlank() && !"_".equals(value)) {
            values.add(value);
        }
    }

    /**
     * 设备信息数据结构
     */
    public record DeviceInfo(
            String nodeId,
            String label,
            String templateName,
            String currentState,
            String currentStateTrust,
            String currentStatePrivacy,
            List<VariableStateDto> instanceVariables,
            List<PrivacyStateDto> instancePrivacies,
            List<VariableInfo> variables,
            List<ApiInfo> apis,
            List<TransitionInfo> transitions,
            List<ModeInfo> modes,
            List<String> workingStates,
            List<ContentInfo> contents
    ) {}

    public record ContentInfo(String name, String privacy) {}

    /**
     * 模式信息
     */
    public record ModeInfo(
            String name,
            List<String> values
    ) {}

    /**
     * 变量信息
     */
    public record VariableInfo(
            String name,
            String description,
            List<String> values,
            Integer lowerBound,
            Integer upperBound,
            String trust,
            String privacy
    ) {}

    /**
     * API 信息
     */
    public record ApiInfo(
            String name,
            String description,
            Boolean signal,
            Boolean acceptsContent,
            String startState,
            String endState
    ) {}

    /**
     * 转换信息
     */
    public record TransitionInfo(
            String name,
            String description,
            String startState,
            String endState
    ) {}
}

