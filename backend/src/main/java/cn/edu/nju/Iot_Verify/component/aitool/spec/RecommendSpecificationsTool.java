package cn.edu.nju.Iot_Verify.component.aitool.spec;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationLimits;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.recommendation.SpecificationRecommendationService;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationFailure;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/** JSON transport adapter for the shared recommendation application service. */
@Slf4j
@Component
public class RecommendSpecificationsTool extends AbstractAiTool {
    private final SpecificationRecommendationService recommendationService;

    public RecommendSpecificationsTool(SpecificationRecommendationService recommendationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.recommendationService = recommendationService;
    }

    @Override
    public String getName() {
        return "recommend_specifications";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();

        props.put("maxRecommendations", Map.of(
                "type", "integer",
                "description", "Maximum number of specification recommendations to return (1-10). Default 5."
        ));

        props.put("language", Map.of(
                "type", "string",
                "enum", List.of("en", "zh-CN"),
                "description", "Natural-language output locale: en or zh-CN. Default en."
        ));

        props.put("userRequirement", Map.of(
                "type", "string",
                "maxLength", RecommendationLimits.MAX_USER_REQUIREMENT_LENGTH,
                "description", "Optional user scenario or goal that should guide the specification recommendations."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, Collections.emptyList()
        );

        return LlmToolSpec.of(getName(),
                "Analyze current board devices, rules, and existing specifications to recommend new formal specifications using AI. " +
                        "This tool uses AI to find suitable specifications that can verify system correctness, safety, and reliability.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "$", Set.of("maxRecommendations", "language", "userRequirement"));
            int maxRecommendations = intArgInRange(args, "maxRecommendations", 5, 1, 10);
            String language = languageArg(args, "language");
            String userRequirement = optionalTextArg(
                    args, "userRequirement", "", RecommendationLimits.MAX_USER_REQUIREMENT_LENGTH);
            return objectMapper.writeValueAsString(recommendationService.recommend(
                    userId, maxRecommendations, language, userRequirement));
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (RecommendationFailure e) {
            return errorJson(e.getMessage(), e.getErrorCode(), e.getCode(), e.getDetails());
        } catch (ServiceUnavailableException e) {
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("RecommendSpecificationsTool failed", e);
            return errorJson("Failed to generate specification recommendations.", "INTERNAL_ERROR", 500);
        }
    }
}
