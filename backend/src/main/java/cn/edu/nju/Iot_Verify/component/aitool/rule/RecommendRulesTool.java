package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationToolSupport;
import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationLimits;
import cn.edu.nju.Iot_Verify.service.recommendation.RuleRecommendationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/** JSON transport adapter for the shared recommendation application service. */
@Component
public class RecommendRulesTool extends AbstractAiTool {
    private final RuleRecommendationService recommendationService;

    public RecommendRulesTool(RuleRecommendationService recommendationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.recommendationService = recommendationService;
    }

    @Override
    public String getName() {
        return "recommend_rules";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();

        props.put("maxRecommendations", Map.of(
                "type", "integer",
                "description", "Maximum number of rule recommendations to return (1-10). Default 5."
        ));

        props.put("language", Map.of(
                "type", "string",
                "enum", List.of("en", "zh-CN"),
                "description", "Natural-language output locale: en or zh-CN. Default en."
        ));

        props.put("userRequirement", Map.of(
                "type", "string",
                "maxLength", RecommendationLimits.MAX_USER_REQUIREMENT_LENGTH,
                "description", "Optional user scenario or goal that should guide the recommendations."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, Collections.emptyList()
        );

        return LlmToolSpec.of(getName(),
                "Analyze current board devices and recommend intelligent automation rules using AI. " +
                        "This tool uses AI to analyze each device's capabilities (APIs, variables, modes, states) and suggests " +
                        "meaningful rules based on device linkage, security, energy saving, or comfort scenarios.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return RecommendationToolSupport.execute(this, userId, argsJson,
                args -> requireOnlyFields(args, "$", Set.of("maxRecommendations", "language", "userRequirement")),
                recommendationService::recommend, RecommendRulesTool.class, "rule");
    }
}
