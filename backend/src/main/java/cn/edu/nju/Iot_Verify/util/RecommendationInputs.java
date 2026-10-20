package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationLimits;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;

import java.util.Locale;

/** Canonical options shared by typed REST requests and parsed tool arguments. */
public final class RecommendationInputs {
    private RecommendationInputs() {
    }

    public static int maxRecommendations(Integer value) {
        int count = value == null ? 5 : value;
        if (count < 1 || count > 10) {
            throw new BadRequestException("maxRecommendations must be an integer between 1 and 10.");
        }
        return count;
    }

    public static String language(String value) {
        String language = text(value, "en", 20, "language").toLowerCase(Locale.ROOT);
        if ("en".equals(language) || language.startsWith("en-") || language.startsWith("en_")) {
            return "en";
        }
        if ("zh".equals(language) || language.startsWith("zh-") || language.startsWith("zh_")) {
            return "zh-CN";
        }
        throw new BadRequestException("language must be en or zh-CN.");
    }

    public static String userRequirement(String value) {
        return text(value, "", RecommendationLimits.MAX_USER_REQUIREMENT_LENGTH, "userRequirement");
    }

    private static String text(String value, String defaultValue, int limit, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > limit) {
            throw new BadRequestException(field + " must be at most " + limit + " characters.");
        }
        return normalized.isEmpty() ? defaultValue : normalized;
    }
}
