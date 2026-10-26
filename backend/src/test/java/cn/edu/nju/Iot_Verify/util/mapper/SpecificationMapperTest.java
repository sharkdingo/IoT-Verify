package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.po.SpecificationPo;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpecificationMapperTest {

    private final SpecificationMapper mapper = new SpecificationMapper();

    @Test
    void toEntity_derivesConditionSideFromContainingCollection() {
        SpecificationDto spec = validSpec();
        spec.setAConditions(List.of(validCondition(null)));
        spec.setIfConditions(List.of(validCondition("then")));
        spec.setThenConditions(List.of(validCondition("if")));

        SpecificationPo po = mapper.toEntity(spec, 1L);

        assertEquals("a", readFirstSide(po.getAConditionsJson()));
        assertEquals("if", readFirstSide(po.getIfConditionsJson()));
        assertEquals("then", readFirstSide(po.getThenConditionsJson()));
    }

    @Test
    void toDto_normalizesLegacyPersistedConditionSides() {
        SpecificationPo po = new SpecificationPo();
        po.setId("spec_1");
        po.setUserId(1L);
        po.setName("Safety requirement");
        po.setTemplateId("1");
        po.setTemplateLabel("Always");
        po.setAConditionsJson(JsonUtils.toJsonOrEmpty(List.of(validCondition("then"))));
        po.setIfConditionsJson(JsonUtils.toJsonOrEmpty(List.of(validCondition(null))));
        po.setThenConditionsJson(JsonUtils.toJsonOrEmpty(List.of(validCondition("if"))));
        po.setDevicesJson("[]");

        SpecificationDto dto = mapper.toDto(po);

        assertEquals("a", dto.getAConditions().get(0).getSide());
        assertEquals("if", dto.getIfConditions().get(0).getSide());
        assertEquals("then", dto.getThenConditions().get(0).getSide());
    }

    @Test
    void statePropertyScope_roundTripsWithoutGeneratedStateKey() {
        SpecificationDto spec = validSpec();
        SpecConditionDto condition = validCondition("a");
        condition.setTargetType("privacy");
        condition.setPropertyScope("state");
        condition.setKey("SwitchState");
        condition.setValue("private");
        spec.setAConditions(List.of(condition));

        SpecificationPo po = mapper.toEntity(spec, 1L);
        SpecificationDto restored = mapper.toDto(po);

        SpecConditionDto restoredCondition = restored.getAConditions().get(0);
        assertEquals("state", restoredCondition.getPropertyScope());
        assertEquals("SwitchState", restoredCondition.getKey());
    }

    @Test
    void variableSource_survivesEntityRoundTrip() {
        /*
         * A round trip, not a field copy, because the field was dropped by FIVE separate hand-written
         * copy sites before this test existed — including the one on the only spec write path, where the
         * request validated, persisted blank, and reloaded unresolved. Asserting up to the mapper boundary
         * is what let that through: the bug lived exactly where the assertion stopped.
         */
        SpecificationDto spec = validSpec();
        SpecConditionDto condition = validCondition("a");
        condition.setTargetType("variable");
        condition.setKey("temperature");
        condition.setVariableSource("reported");
        condition.setRelation(">");
        condition.setValue("30");
        spec.setAConditions(List.of(condition));

        SpecificationDto restored = mapper.toDto(mapper.toEntity(spec, 1L));

        assertEquals("reported", restored.getAConditions().get(0).getVariableSource(),
                "a persisted variable condition must come back asking the same question");
    }

    @Test
    void blankPersistedConditionColumnFailsClosedInsteadOfBecomingEmpty() {
        SpecificationPo po = new SpecificationPo();
        po.setId("spec_bad");
        po.setName("Safety requirement");
        po.setAConditionsJson(" ");
        po.setIfConditionsJson("[]");
        po.setThenConditionsJson("[]");
        po.setDevicesJson("[]");

        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po));

        assertEquals("aConditionsJson", error.getField());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void toDto_rejectsMissingPersistedRequirementNames(String name) {
        SpecificationPo po = mapper.toEntity(validSpec(), 1L);
        po.setName(name);

        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po));

        assertEquals("name", error.getField());
        assertEquals("spec_1", error.getRecordId());
    }

    @Test
    void toDto_rejectsOverlongPersistedRequirementNames() {
        SpecificationPo po = mapper.toEntity(validSpec(), 1L);
        po.setName("x".repeat(101));

        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po));

        assertEquals("name", error.getField());
    }

    private SpecificationDto validSpec() {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setTemplateLabel("Always");
        return spec;
    }

    private SpecConditionDto validCondition(String side) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setId("cond_1");
        condition.setSide(side);
        condition.setDeviceId("light");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("on");
        return condition;
    }

    private String readFirstSide(String json) {
        return JsonUtils.fromJsonList(json, SpecConditionDto.class).get(0).getSide();
    }
}
