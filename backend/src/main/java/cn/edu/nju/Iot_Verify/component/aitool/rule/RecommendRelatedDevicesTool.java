package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationToolSupport;
import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationLimits;
import cn.edu.nju.Iot_Verify.service.recommendation.DeviceRecommendationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/** JSON transport adapter for the shared recommendation application service. */
@Component
public class RecommendRelatedDevicesTool extends AbstractAiTool {
    private final DeviceRecommendationService recommendationService;

    public RecommendRelatedDevicesTool(DeviceRecommendationService recommendationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.recommendationService = recommendationService;
    }

    @Override
    public String getName() {
        return "recommend_related_devices";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();

        props.put("maxRecommendations", Map.of(
                "type", "integer",
                "description", "Maximum number of device recommendations to return (1-10). Default 5."
        ));

        props.put("language", Map.of(
                "type", "string",
                "enum", List.of("en", "zh-CN"),
                "description", "Natural-language output locale: en or zh-CN. Default en."
        ));

        props.put("userRequirement", Map.of(
                "type", "string",
                "maxLength", RecommendationLimits.MAX_USER_REQUIREMENT_LENGTH,
                "description", "Optional user scenario or goal that should guide the device recommendations."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema("object", props, List.of());

        return LlmToolSpec.of(getName(),
                "Analyze the current user's saved board and recommend new devices from available templates. " +
                        "This tool uses AI to find suitable devices that can enhance the IoT system.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return RecommendationToolSupport.execute(this, userId, argsJson,
                args -> requireOnlyFields(args, "$", Set.of("maxRecommendations", "language", "userRequirement")),
                recommendationService::recommend, RecommendRelatedDevicesTool.class, "device");
    }
}
