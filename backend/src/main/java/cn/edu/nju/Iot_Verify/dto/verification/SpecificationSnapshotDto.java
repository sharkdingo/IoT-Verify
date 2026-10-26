package cn.edu.nju.Iot_Verify.dto.verification;

import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;

import java.util.List;
import java.util.Map;

/** Structured property and display provenance captured with its verdict, independent of the live board. */
public record SpecificationSnapshotDto(
        String id,
        String name,
        String templateId,
        List<SpecConditionDto> aConditions,
        List<SpecConditionDto> ifConditions,
        List<SpecConditionDto> thenConditions,
        Map<String, Device> devices) {

    public record Device(String label, ModelTokenSource modelTokenSource, Integer modeCount) {}
}
