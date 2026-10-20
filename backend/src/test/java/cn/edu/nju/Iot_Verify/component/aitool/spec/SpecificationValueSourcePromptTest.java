package cn.edu.nju.Iot_Verify.component.aitool.spec;

import cn.edu.nju.Iot_Verify.service.recommendation.ScenarioRecommendationService;
import cn.edu.nju.Iot_Verify.service.recommendation.SpecificationRecommendationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecificationValueSourcePromptTest {
    @Test
    void bothRecommendationBoundariesIncludeTheSameReportedAndPhysicalValueDistinction() throws Exception {
        String reference = SpecificationTemplateSemantics.chinesePromptReference();
        assertTrue(reference.contains("设备报告了什么"));
        assertTrue(reference.contains("variableSource=reported"));
        assertTrue(reference.contains("真实环境值"));
        assertTrue(reference.contains("variableSource=environment"));
        assertTrue(reference.contains("初值只定义场景起点"));
        for (Class<?> service : new Class<?>[]{ScenarioRecommendationService.class, SpecificationRecommendationService.class}) {
            var field = service.getDeclaredField("SYSTEM_PROMPT");
            field.setAccessible(true);
            assertTrue(((String) field.get(null)).contains(reference), service.getSimpleName());
        }
    }
}
