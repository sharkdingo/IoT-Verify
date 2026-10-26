package cn.edu.nju.Iot_Verify.dto.recommendation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecificationRecommendationDtoJsonTest {

    @Test
    void serializesOneCanonicalAConditionsKey() throws Exception {
        SpecificationRecommendationDto.ConditionDto condition =
                new SpecificationRecommendationDto.ConditionDto();
        condition.setDeviceId("camera_1");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("taking photo");

        SpecificationRecommendationDto recommendation = new SpecificationRecommendationDto();
        recommendation.setName("Safety requirement");
        recommendation.setTemplateId("3");
        recommendation.setAConditions(List.of(condition));
        recommendation.setIfConditions(List.of());
        recommendation.setThenConditions(List.of());

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        String json = objectMapper.writeValueAsString(recommendation);
        JsonNode parsed = objectMapper.readTree(json);

        assertTrue(parsed.has("aConditions"));
        assertFalse(parsed.has("aconditions"));
        assertEquals(1, parsed.path("aConditions").size());
    }
}
