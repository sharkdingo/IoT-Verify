package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationInputsTest {
    @ParameterizedTest
    @CsvSource({"en,en", "EN_US,en", "en-GB,en", "zh,zh-CN", "ZH_cn,zh-CN", "zh-TW,zh-CN"})
    void normalizesSupportedLocaleFamilies(String input, String expected) {
        assertEquals(expected, RecommendationInputs.language(input));
    }

    @Test
    void absentOptionsKeepTheDocumentedDefaults() {
        assertEquals(5, RecommendationInputs.maxRecommendations(null));
        assertEquals("en", RecommendationInputs.language(null));
        assertEquals("en", RecommendationInputs.language(" "));
        assertEquals("", RecommendationInputs.userRequirement(null));
        assertEquals("save energy", RecommendationInputs.userRequirement(" save energy "));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 11, Integer.MAX_VALUE})
    void rejectsAnOutOfRangeCount(int count) {
        assertThrows(BadRequestException.class, () -> RecommendationInputs.maxRecommendations(count));
    }

    @Test
    void rejectsUnsupportedOrOversizedTextOptions() {
        assertThrows(BadRequestException.class, () -> RecommendationInputs.language("fr"));
        assertThrows(BadRequestException.class, () -> RecommendationInputs.language("en-" + "x".repeat(20)));
        assertThrows(BadRequestException.class, () -> RecommendationInputs.userRequirement("x".repeat(2001)));
    }
}
