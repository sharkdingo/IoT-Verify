package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.AttackSurface;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.EnvironmentProvenanceCollector;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.EnvironmentValueProvenanceDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prepares validation and generation from the same frozen template manifests. */
final class ModelRunPreparation {

    private ModelRunPreparation() {
    }

    static PreparedModel prepare(SmvGenerator smvGenerator,
                                 EnvironmentProvenanceCollector provenanceCollector,
                                 List<DeviceVerificationDto> devices,
                                 List<RuleDto> rules,
                                 List<SpecificationDto> specs,
                                 List<BoardEnvironmentVariableDto> environmentVariables,
                                 boolean isAttack,
                                 Map<String, DeviceManifest> templateManifests,
                                 Map<String, String> errors) {
        if (!errors.isEmpty()) {
            return new PreparedModel(devices, environmentVariables, new AttackSurface(Set.of(), 0, 0),
                    Map.of(), Map.of(), null);
        }
        try {
            SmvGenerator.CapturedDeviceModel capturedDeviceModel = smvGenerator.captureDeviceModelFromTemplateSnapshots(
                    devices, templateManifests);
            Map<String, DeviceSmvData> deviceSmvMap = capturedDeviceModel.deviceSmvMap();
            LocalDateTime capturedAt = LocalDateTime.now();
            NusmvRequestValidator.validateDeviceSemantics(devices, deviceSmvMap, errors);
            NusmvRequestValidator.validateEnvironmentVariableOverrides(
                    environmentVariables, deviceSmvMap, errors);
            List<BoardEnvironmentVariableDto> mergedEnvironmentVariables =
                    NusmvEnvironmentPool.mergeWithDefaults(environmentVariables, deviceSmvMap);
            NusmvRequestValidator.validateEnvironmentVariables(mergedEnvironmentVariables, deviceSmvMap, errors);
            NusmvRequestValidator.validateMainNamespace(devices, rules, deviceSmvMap, isAttack, errors);
            NusmvRequestValidator.validateRuleSemantics(rules, deviceSmvMap, errors);
            NusmvRequestValidator.validateSpecificationSemantics(specs, deviceSmvMap, errors);
            NusmvRequestValidator.validateAttackHasModeledEffect(isAttack, rules, deviceSmvMap, errors);
            AttackSurface attackSurface = AttackSurface.analyze(rules, deviceSmvMap);
            if (!errors.isEmpty()) {
                return new PreparedModel(devices, mergedEnvironmentVariables, attackSurface,
                        Map.of(), capturedDeviceModel.templateManifests(), null);
            }
            List<DeviceVerificationDto> expandedDevices = NusmvEnvironmentPool.expandDevices(
                    devices, mergedEnvironmentVariables, deviceSmvMap);
            Map<String, DeviceSmvData> expandedDeviceSmvMap =
                    smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(
                            expandedDevices, capturedDeviceModel.templateManifests());
            List<EnvironmentValueProvenanceDto> environmentProvenance =
                    provenanceCollector.collectEnvironmentProvenance(
                            mergedEnvironmentVariables, expandedDevices, expandedDeviceSmvMap);
            ModelRunSnapshotDto modelSnapshot = ModelRunSnapshotDto.captured(
                    capturedAt,
                    expandedDevices.size(),
                    rules != null ? rules.size() : 0,
                    specs != null ? specs.size() : 0,
                    mergedEnvironmentVariables.size(),
                    capturedDeviceModel.templateManifests().size())
                    .toBuilder()
                    .environmentProvenance(environmentProvenance)
                    .build();
            return new PreparedModel(expandedDevices, mergedEnvironmentVariables, attackSurface,
                    expandedDeviceSmvMap, capturedDeviceModel.templateManifests(), modelSnapshot);
        } catch (SmvGenerationException e) {
            errors.putIfAbsent("devices", e.getMessage());
            return new PreparedModel(devices, environmentVariables, new AttackSurface(Set.of(), 0, 0),
                    Map.of(), Map.of(), null);
        }
    }

    record PreparedModel(List<DeviceVerificationDto> devices,
                         List<BoardEnvironmentVariableDto> environmentVariables,
                         AttackSurface attackSurface,
                         Map<String, DeviceSmvData> deviceSmvMap,
                         Map<String, DeviceManifest> templateManifests,
                         ModelRunSnapshotDto modelSnapshot) {
    }
}
