package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 共享工具类：将画板设备节点转换为验证/模拟所需的 DTO
 */
@Component
@RequiredArgsConstructor
public class BoardDataConverter {

    private final BoardStorageService boardStorageService;

    /**
     * Captures and converts every model-defining board collection from one persisted snapshot.
     * Referenced template manifests are carried forward so verification never re-resolves a newer
     * template version after the board lock is released.
     */
    public ModelInputSnapshot getModelInputSnapshot(Long userId) {
        return toModelInputSnapshot(boardStorageService.getSemanticSnapshot(userId));
    }

    /** Convert an already locked storage snapshot without performing another repository read. */
    public ModelInputSnapshot toModelInputSnapshot(BoardSemanticSnapshotDto snapshot) {
        List<DeviceNodeDto> nodes = snapshot != null ? snapshot.nodes() : List.of();
        List<DeviceTemplateDto> templates = snapshot != null ? snapshot.deviceTemplates() : List.of();
        Map<String, DeviceManifest> manifestsByTemplate = manifestsByTemplate(templates);
        Map<String, ModelTokenSource> tokenSourcesByTemplate = tokenSourcesByTemplate(templates);
        List<DeviceVerificationDto> devices = devicesForVerification(
                nodes, manifestsByTemplate, tokenSourcesByTemplate);
        return new ModelInputSnapshot(
                nodes,
                devices,
                snapshot != null ? snapshot.environmentVariables() : List.of(),
                normalizeRules(snapshot != null ? snapshot.rules() : List.of()),
                normalizeSpecs(snapshot != null ? snapshot.specifications() : List.of()),
                referencedTemplateManifests(devices, manifestsByTemplate));
    }

    private List<DeviceVerificationDto> devicesForVerification(
            List<DeviceNodeDto> nodes,
            Map<String, DeviceManifest> manifestsByTemplate,
            Map<String, ModelTokenSource> tokenSourcesByTemplate) {
        List<DeviceNodeDto> safeNodes = nodes == null ? List.of() : nodes;
        return safeNodes.stream()
                .filter(n -> n != null)
                .map(n -> {
                    String varName = trimToNull(n.getId());
                    if (varName == null) {
                        return null;
                    }

                    DeviceVerificationDto dv = new DeviceVerificationDto();
                    dv.setVarName(DeviceNameNormalizer.normalize(varName));
                    String label = trimToNull(n.getLabel());
                    dv.setDeviceLabel(label != null ? label : varName);
                    dv.setTemplateName(n.getTemplateName());
                    dv.setModelTokenSource(modelTokenSourceForTemplate(
                            n.getTemplateName(), tokenSourcesByTemplate));
                    if (templateHasModes(n.getTemplateName(), manifestsByTemplate)) {
                        dv.setState(n.getState());
                        dv.setCurrentStateTrust(n.getCurrentStateTrust());
                        dv.setCurrentStatePrivacy(n.getCurrentStatePrivacy());
                    }
                    DeviceManifest manifest = manifestForTemplate(n.getTemplateName(), manifestsByTemplate);
                    dv.setVariables(localVariables(n.getVariables(), manifest));
                    dv.setPrivacies(localPrivacies(n.getPrivacies(), manifest));
                    return dv;
                })
                .filter(dv -> dv != null)
                .toList();
    }

    private List<RuleDto> normalizeRules(List<RuleDto> rules) {
        if (rules == null) {
            return Collections.emptyList();
        }
        return rules.stream()
                .filter(r -> r != null)
                .map(this::normalizeRuleForModel)
                .toList();
    }

    private List<SpecificationDto> normalizeSpecs(List<SpecificationDto> specs) {
        if (specs == null) {
            return Collections.emptyList();
        }
        return specs.stream()
                .filter(s -> s != null)
                .map(this::normalizeSpecForModel)
                .toList();
    }

    private RuleDto normalizeRuleForModel(RuleDto source) {
        RuleDto target = new RuleDto();
        target.setId(source.getId());
        target.setUserId(source.getUserId());
        target.setRuleString(source.getRuleString());
        target.setCreatedAt(source.getCreatedAt());

        if (source.getConditions() != null) {
            target.setConditions(source.getConditions().stream()
                    .filter(c -> c != null)
                    .map(c -> RuleDto.Condition.builder()
                            .deviceName(normalizeRef(c.getDeviceName()))
                            .attribute(c.getAttribute())
                            .targetType(c.getTargetType())
                            .relation(c.getRelation())
                            .value(c.getValue())
                            .build())
                    .toList());
        } else {
            target.setConditions(Collections.emptyList());
        }

        RuleDto.Command command = source.getCommand();
        if (command != null) {
            target.setCommand(RuleDto.Command.builder()
                    .deviceName(normalizeRef(command.getDeviceName()))
                    .action(command.getAction())
                    .contentDevice(normalizeRef(command.getContentDevice()))
                    .content(command.getContent())
                    .build());
        }
        return target;
    }

    private SpecificationDto normalizeSpecForModel(SpecificationDto source) {
        SpecificationDto target = new SpecificationDto();
        target.setId(source.getId());
        target.setName(source.getName());
        target.setTemplateId(source.getTemplateId());
        target.setTemplateLabel(source.getTemplateLabel());
        target.setAConditions(normalizeConditions(source.getAConditions()));
        target.setIfConditions(normalizeConditions(source.getIfConditions()));
        target.setThenConditions(normalizeConditions(source.getThenConditions()));
        target.setFormula(source.getFormula());
        if (source.getDevices() != null) {
            target.setDevices(source.getDevices().stream()
                    .filter(d -> d != null)
                    .map(d -> new SpecificationDto.DeviceRefDto(
                            normalizeRef(d.getDeviceId()),
                            d.getDeviceLabel(),
                            d.getSelectedApis() == null
                                    ? Collections.emptyList()
                                    : List.copyOf(d.getSelectedApis())))
                    .toList());
        } else {
            target.setDevices(Collections.emptyList());
        }
        return target;
    }

    private List<SpecConditionDto> normalizeConditions(List<SpecConditionDto> conditions) {
        if (conditions == null) {
            return Collections.emptyList();
        }
        return conditions.stream()
                .filter(c -> c != null)
                .map(c -> {
                    SpecConditionDto copy = new SpecConditionDto();
                    copy.setId(c.getId());
                    copy.setSide(c.getSide());
                    copy.setDeviceId(normalizeRef(c.getDeviceId()));
                    copy.setDeviceLabel(c.getDeviceLabel());
                    copy.setTargetType(c.getTargetType());
                    copy.setKey(c.getKey());
                    copy.setPropertyScope(c.getPropertyScope());
                    copy.setVariableSource(c.getVariableSource());
                    copy.setRelation(c.getRelation());
                    copy.setValue(c.getValue());
                    return copy;
                })
                .toList();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeRef(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : DeviceNameNormalizer.normalize(trimmed);
    }

    private Map<String, DeviceManifest> manifestsByTemplate(List<DeviceTemplateDto> templates) {
        if (templates == null || templates.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, DeviceManifest> result = new HashMap<>();
        for (DeviceTemplateDto template : templates) {
            if (template == null || template.getManifest() == null) {
                continue;
            }
            putManifestAlias(result, template.getName(), template.getManifest());
            putManifestAlias(result, template.getManifest().getName(), template.getManifest());
        }
        return result;
    }

    private Map<String, ModelTokenSource> tokenSourcesByTemplate(List<DeviceTemplateDto> templates) {
        if (templates == null || templates.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, ModelTokenSource> result = new HashMap<>();
        for (DeviceTemplateDto template : templates) {
            if (template == null) {
                continue;
            }
            ModelTokenSource source = ModelTokenSource.fromDefaultTemplate(template.getDefaultTemplate());
            putModelTokenSourceAlias(result, template.getName(), source);
            if (template.getManifest() != null) {
                putModelTokenSourceAlias(result, template.getManifest().getName(), source);
            }
        }
        return result;
    }

    private ModelTokenSource modelTokenSourceForTemplate(
            String templateName,
            Map<String, ModelTokenSource> tokenSourcesByTemplate) {
        String key = trimToNull(templateName);
        if (key == null || tokenSourcesByTemplate == null) {
            return ModelTokenSource.UNKNOWN;
        }
        return tokenSourcesByTemplate.getOrDefault(
                key.toLowerCase(Locale.ROOT), ModelTokenSource.UNKNOWN);
    }

    private Map<String, DeviceManifest> referencedTemplateManifests(
            List<DeviceVerificationDto> devices,
            Map<String, DeviceManifest> manifestsByTemplate) {
        Map<String, DeviceManifest> result = new LinkedHashMap<>();
        for (DeviceVerificationDto device : devices == null ? List.<DeviceVerificationDto>of() : devices) {
            if (device == null) {
                continue;
            }
            String templateName = trimToNull(device.getTemplateName());
            DeviceManifest manifest = manifestForTemplate(templateName, manifestsByTemplate);
            if (templateName != null && manifest != null) {
                result.putIfAbsent(templateName, manifest);
            }
        }
        return result;
    }

    private void putManifestAlias(Map<String, DeviceManifest> result, String rawName, DeviceManifest manifest) {
        String name = trimToNull(rawName);
        if (name != null) {
            result.putIfAbsent(name.toLowerCase(Locale.ROOT), manifest);
        }
    }

    private void putModelTokenSourceAlias(
            Map<String, ModelTokenSource> result,
            String rawName,
            ModelTokenSource source) {
        String name = trimToNull(rawName);
        if (name != null) {
            result.putIfAbsent(name.toLowerCase(Locale.ROOT), source);
        }
    }

    private boolean templateHasModes(String templateName, Map<String, DeviceManifest> manifestsByTemplate) {
        DeviceManifest manifest = manifestForTemplate(templateName, manifestsByTemplate);
        if (manifest == null) {
            // Unknown template: keep state so downstream validation fails conservatively if needed.
            return true;
        }
        return manifest.getModes() != null && !manifest.getModes().isEmpty()
                && manifest.getWorkingStates() != null && !manifest.getWorkingStates().isEmpty();
    }

    private DeviceManifest manifestForTemplate(String templateName, Map<String, DeviceManifest> manifestsByTemplate) {
        String key = trimToNull(templateName);
        if (key == null || manifestsByTemplate == null) {
            return null;
        }
        return manifestsByTemplate.get(key.toLowerCase(Locale.ROOT));
    }

    private List<VariableStateDto> localVariables(List<VariableStateDto> variables, DeviceManifest manifest) {
        if (variables == null || variables.isEmpty()) {
            return Collections.emptyList();
        }
        if (manifest == null || manifest.getInternalVariables() == null) {
            return variables;
        }
        return variables.stream()
                .filter(v -> v != null && !isEnvironmentVariable(manifestVariable(manifest, v.getName())))
                .toList();
    }

    private List<PrivacyStateDto> localPrivacies(List<PrivacyStateDto> privacies, DeviceManifest manifest) {
        if (privacies == null || privacies.isEmpty()) {
            return Collections.emptyList();
        }
        if (manifest == null || manifest.getInternalVariables() == null) {
            return privacies;
        }
        return privacies.stream()
                .filter(p -> p != null && !isEnvironmentVariable(manifestVariable(manifest, p.getName())))
                .toList();
    }

    private DeviceManifest.InternalVariable manifestVariable(DeviceManifest manifest, String name) {
        String key = trimToNull(name);
        if (manifest == null || manifest.getInternalVariables() == null || key == null) {
            return null;
        }
        for (DeviceManifest.InternalVariable variable : manifest.getInternalVariables()) {
            if (variable != null && key.equals(variable.getName())) {
                return variable;
            }
        }
        return null;
    }

    private boolean isEnvironmentVariable(DeviceManifest.InternalVariable variable) {
        return variable != null && !Boolean.TRUE.equals(variable.getIsInside());
    }

    public record ModelInputSnapshot(
            List<DeviceNodeDto> nodes,
            List<DeviceVerificationDto> devices,
            List<BoardEnvironmentVariableDto> environmentVariables,
            List<RuleDto> rules,
            List<SpecificationDto> specifications,
            Map<String, DeviceManifest> templateManifests) {

        public ModelInputSnapshot {
            nodes = immutable(nodes);
            devices = immutable(devices);
            environmentVariables = immutable(environmentVariables);
            rules = immutable(rules);
            specifications = immutable(specifications);
            templateManifests = templateManifests == null || templateManifests.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(templateManifests));
        }

        private static <T> List<T> immutable(List<T> values) {
            return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
        }
    }
}
