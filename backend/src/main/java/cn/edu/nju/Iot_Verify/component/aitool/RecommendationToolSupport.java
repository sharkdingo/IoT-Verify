package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationLimits;
import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationResponseDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationFailure;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.LoggerFactory;

/** JSON argument and error handling shared by the device, rule, and specification tools. */
public final class RecommendationToolSupport {

    private RecommendationToolSupport() {
    }

    @FunctionalInterface
    public interface ArgumentCheck {
        void validate(JsonNode args) throws AbstractAiTool.ArgValidationException;
    }

    @FunctionalInterface
    public interface Recommender {
        RecommendationResponseDto<?> recommend(Long userId, int max, String language, String userRequirement);
    }

    public static String execute(AbstractAiTool tool, Long userId, String argsJson,
                                 ArgumentCheck check, Recommender recommender,
                                 Class<?> toolClass, String recommendationKind) {
        try {
            JsonNode args = tool.parseArgs(argsJson);
            check.validate(args);
            int maxRecommendations = tool.intArgInRange(args, "maxRecommendations", 5, 1, 10);
            String language = tool.languageArg(args, "language");
            String userRequirement = tool.optionalTextArg(
                    args, "userRequirement", "", RecommendationLimits.MAX_USER_REQUIREMENT_LENGTH);
            return tool.objectMapper.writeValueAsString(recommender.recommend(
                    userId, maxRecommendations, language, userRequirement));
        } catch (AbstractAiTool.ArgValidationException e) {
            return e.getErrorResponse();
        } catch (RecommendationFailure e) {
            return tool.errorJson(e.getMessage(), e.getErrorCode(), e.getCode(), e.getDetails());
        } catch (ServiceUnavailableException e) {
            return tool.errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            return tool.errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            LoggerFactory.getLogger(toolClass).error(toolClass.getSimpleName() + " failed", e);
            return tool.errorJson("Failed to generate " + recommendationKind + " recommendations.",
                    "INTERNAL_ERROR", 500);
        }
    }
}
