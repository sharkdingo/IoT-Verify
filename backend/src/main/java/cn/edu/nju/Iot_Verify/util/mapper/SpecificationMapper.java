package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.SpecificationPo;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Specification PO 与 DTO 之间的转换映射器
 */
@Component
public class SpecificationMapper {

    /**
     * SpecificationPo -> SpecificationDto
     */
    public SpecificationDto toDto(SpecificationPo po) {
        if (po == null) {
            return null;
        }
        if (po.getName() == null || po.getName().isBlank() || po.getName().length() > 100) {
            throw new PersistedDataIntegrityException("specification", po.getId(), "name",
                    "A requirement name of 1 to 100 characters is required");
        }
        SpecificationDto dto = new SpecificationDto();
        dto.setId(po.getId());
        dto.setName(po.getName());
        dto.setTemplateId(po.getTemplateId());
        dto.setTemplateLabel(po.getTemplateLabel());

        dto.setAConditions(withSide(JsonUtils.readPersistedJsonRequired(
                "specification", po.getId(), "aConditionsJson", po.getAConditionsJson(),
                () -> JsonUtils.fromJsonList(po.getAConditionsJson(), SpecConditionDto.class)), "a"));
        dto.setIfConditions(withSide(JsonUtils.readPersistedJsonRequired(
                "specification", po.getId(), "ifConditionsJson", po.getIfConditionsJson(),
                () -> JsonUtils.fromJsonList(po.getIfConditionsJson(), SpecConditionDto.class)), "if"));
        dto.setThenConditions(withSide(JsonUtils.readPersistedJsonRequired(
                "specification", po.getId(), "thenConditionsJson", po.getThenConditionsJson(),
                () -> JsonUtils.fromJsonList(po.getThenConditionsJson(), SpecConditionDto.class)), "then"));
        dto.setFormula(po.getFormula());
        dto.setDevices(JsonUtils.readPersistedJsonRequired(
                "specification", po.getId(), "devicesJson", po.getDevicesJson(),
                () -> JsonUtils.fromJsonList(po.getDevicesJson(), SpecificationDto.DeviceRefDto.class)));

        return dto;
    }

    /**
     * SpecificationDto -> SpecificationPo
     */
    public SpecificationPo toEntity(SpecificationDto dto, Long userId) {
        if (dto == null) {
            return null;
        }
        SpecificationPo po = new SpecificationPo();
        po.setId(dto.getId());
        po.setUserId(userId);
        po.setName(dto.getName());
        po.setTemplateId(dto.getTemplateId());
        po.setTemplateLabel(dto.getTemplateLabel());

        po.setAConditionsJson(JsonUtils.toJsonOrEmpty(withSide(dto.getAConditions(), "a")));
        po.setIfConditionsJson(JsonUtils.toJsonOrEmpty(withSide(dto.getIfConditions(), "if")));
        po.setThenConditionsJson(JsonUtils.toJsonOrEmpty(withSide(dto.getThenConditions(), "then")));
        po.setFormula(dto.getFormula());
        po.setDevicesJson(JsonUtils.toJsonOrEmpty(dto.getDevices()));

        return po;
    }

    public String toJson(SpecificationDto dto) {
        return JsonUtils.toJson(dto);
    }

    private List<SpecConditionDto> withSide(List<SpecConditionDto> conditions, String side) {
        if (conditions == null) {
            return new ArrayList<>();
        }
        List<SpecConditionDto> normalized = new ArrayList<>(conditions.size());
        for (SpecConditionDto condition : conditions) {
            normalized.add(copyWithSide(condition, side));
        }
        return normalized;
    }

    private SpecConditionDto copyWithSide(SpecConditionDto condition, String side) {
        if (condition == null) {
            return null;
        }
        SpecConditionDto copy = new SpecConditionDto();
        copy.setId(condition.getId());
        copy.setSide(side);
        copy.setDeviceId(condition.getDeviceId());
        copy.setDeviceLabel(condition.getDeviceLabel());
        copy.setTargetType(condition.getTargetType());
        copy.setKey(condition.getKey());
        copy.setPropertyScope(condition.getPropertyScope());
        copy.setVariableSource(condition.getVariableSource());
        copy.setRelation(condition.getRelation());
        copy.setValue(condition.getValue());
        return copy;
    }
}
