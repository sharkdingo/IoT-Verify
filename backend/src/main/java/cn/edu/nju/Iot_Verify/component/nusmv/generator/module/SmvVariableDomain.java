package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;

import java.util.List;

final class SmvVariableDomain {
    private SmvVariableDomain() {
    }

    // Missing-domain handling belongs to the builder emitting this expression.
    static String render(DeviceManifest.InternalVariable variable) {
        List<String> values = variable.getValues();
        if (values != null && !values.isEmpty()) {
            return "{" + String.join(", ", enumTokens(values)) + "}";
        }
        if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
            return variable.getLowerBound() + ".." + variable.getUpperBound();
        }
        return null;
    }

    static List<String> enumTokens(List<String> values) {
        return values.stream().map(value -> value.replace(" ", "")).toList();
    }
}
