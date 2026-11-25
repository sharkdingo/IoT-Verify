package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class NusmvEnvironmentPool {

    private NusmvEnvironmentPool() {
    }

    static List<BoardEnvironmentVariableDto> mergeWithDefaults(
            List<BoardEnvironmentVariableDto> submitted,
            Map<String, DeviceSmvData> deviceSmvMap) {
        Map<String, DeviceManifest.InternalVariable> required = collectRequired(deviceSmvMap);
        Map<String, BoardEnvironmentVariableDto> submittedByName = toMap(submitted);
        List<BoardEnvironmentVariableDto> result = new ArrayList<>();

        for (Map.Entry<String, DeviceManifest.InternalVariable> entry : required.entrySet()) {
            String name = entry.getKey();
            DeviceManifest.InternalVariable definition = entry.getValue();
            BoardEnvironmentVariableDto submittedValue = submittedByName.remove(name);
            String value = submittedValue != null ? trimToNull(submittedValue.getValue()) : null;
            if (value == null) {
                value = defaultValue(definition);
            }
            String trust = submittedValue != null ? submittedValue.getTrust() : definition.getTrust();
            String privacy = submittedValue != null ? submittedValue.getPrivacy() : definition.getPrivacy();
            result.add(new BoardEnvironmentVariableDto(
                    name,
                    value,
                    normalizeTrustPrivacy(trust, normalizeTrustPrivacy(definition.getTrust(), "untrusted")),
                    normalizeTrustPrivacy(privacy, normalizeTrustPrivacy(definition.getPrivacy(), "public"))
            ));
        }

        // Preserve unknown submitted variables so request validation can report them explicitly.
        result.addAll(submittedByName.values());
        return result;
    }

    static Map<String, DeviceManifest.InternalVariable> collectRequired(Map<String, DeviceSmvData> deviceSmvMap) {
        Map<String, DeviceManifest.InternalVariable> required = new TreeMap<>();
        if (deviceSmvMap == null || deviceSmvMap.isEmpty()) {
            return required;
        }
        for (Map.Entry<String, DeviceSmvData> entry : deviceSmvMap.entrySet()) {
            DeviceSmvData smv = entry.getValue();
            if (smv == null) {
                continue;
            }
            if (!Objects.equals(entry.getKey(), smv.getVarName())) {
                continue;
            }
            smv.getSharedDeclarations().forEach(required::putIfAbsent);
        }
        return required;
    }

    static String defaultValue(DeviceManifest.InternalVariable variable) {
        if (variable == null) {
            return null;
        }
        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            return trimToNull(variable.getValues().get(0));
        }
        if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
            return String.valueOf(variable.getLowerBound());
        }
        return null;
    }

    private static Map<String, BoardEnvironmentVariableDto> toMap(List<BoardEnvironmentVariableDto> variables) {
        Map<String, BoardEnvironmentVariableDto> result = new LinkedHashMap<>();
        if (variables == null) {
            return result;
        }
        for (BoardEnvironmentVariableDto variable : variables) {
            if (variable == null || trimToNull(variable.getName()) == null) {
                continue;
            }
            String name = trimToNull(variable.getName());
            result.putIfAbsent(name, new BoardEnvironmentVariableDto(
                    name,
                    trimToNull(variable.getValue()),
                    trimToNull(variable.getTrust()),
                    trimToNull(variable.getPrivacy())
            ));
        }
        return result;
    }

    private static String normalizeTrustPrivacy(String value, String fallback) {
        String normalized = DeviceSmvDataFactory.normalizeTrustPrivacy(value);
        return normalized != null ? normalized : fallback;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
