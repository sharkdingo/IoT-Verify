package cn.edu.nju.Iot_Verify.dto.recommendation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableSceneDtoJsonTest {

    @Test
    void specificationSerializesOneCanonicalAConditionsKey() throws Exception {
        PortableSceneDto.PortableSpecConditionDto condition = new PortableSceneDto.PortableSpecConditionDto();
        condition.setDeviceId("camera_1");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("taking photo");

        PortableSceneDto.PortableSpecificationDto specification =
                new PortableSceneDto.PortableSpecificationDto();
        specification.setName("Safety requirement");
        specification.setTemplateId("3");
        specification.setAConditions(List.of(condition));
        specification.setIfConditions(List.of());
        specification.setThenConditions(List.of());

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        String json = objectMapper.writeValueAsString(specification);
        JsonNode parsed = objectMapper.readTree(json);

        assertTrue(parsed.has("aConditions"));
        assertFalse(parsed.has("aconditions"));
        assertEquals(1, parsed.path("aConditions").size());
    }
}
