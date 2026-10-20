package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector.ConfirmationDecision;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector.ConfirmationKind;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector.DecisionType;
import cn.edu.nju.Iot_Verify.security.UserContextHolder.BoardWritePermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatConfirmationDetectorTest {

    @Mock
    private PromptCompletionService promptCompletionService;

    private ChatConfirmationDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ChatConfirmationDetector(promptCompletionService, new ObjectMapper());
    }

    @Test
    void boardWritePermissionUsesTheFullCurrentHumanRequestAndStrictDecision() {
        String request = "分析反例，只搜索数值调整方案。" + "保留所有要求。".repeat(700) + "不要应用方案。";
        when(promptCompletionService.completeClassification(
                argThat(prompt -> prompt.contains("even temporarily") && prompt.contains("not keywords")),
                argThat(input -> input.contains(request)), anyDouble(), anyInt()))
                .thenReturn("{\"decision\":\"DENIED\"}");
        assertEquals(BoardWritePermission.DENIED, detector.detectBoardWritePermission(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "[]", "{\"decision\":true}", "{\"decision\":\"MAYBE\"}",
            "{\"decision\":\"ALLOWED\",\"extra\":true}", "not JSON"})
    void malformedBoardWriteClassificationNeverGrantsPermission(String response) {
        when(promptCompletionService.completeClassification(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(response);
        assertEquals(BoardWritePermission.UNAVAILABLE, detector.detectBoardWritePermission("请修改阈值"));
    }

    @Test
    void explicitEditAndUnavailableClassifierRemainDistinct() {
        when(promptCompletionService.completeClassification(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"decision\":\"ALLOWED\"}").thenThrow(new IllegalStateException("unavailable"));
        assertEquals(BoardWritePermission.ALLOWED, detector.detectBoardWritePermission("请只把阈值28改为30"));
        assertEquals(BoardWritePermission.UNAVAILABLE, detector.detectBoardWritePermission("请保存规则"));
    }

    @Test
    void detect_usesModelMeaningAndAcceptsOnlyAPendingKind() {
        when(promptCompletionService.completeClassification(
                argThat(prompt -> prompt.contains("meaning, context, and the user's language")),
                contains("latestUserMessage"), anyDouble(), anyInt()))
                .thenReturn("{\"decision\":\"CONFIRMED\",\"kind\":\"DESTRUCTIVE\"}")
                .thenReturn("{\"decision\":\"CONFIRMED\",\"kind\":\"SCENE_REPLACEMENT\"}");

        ConfirmationDecision confirmed = detector.detect(
                "Express this however the user naturally prefers.",
                EnumSet.of(ConfirmationKind.DESTRUCTIVE));
        ConfirmationDecision impossibleKind = detector.detect(
                "Another natural-language reply.",
                EnumSet.of(ConfirmationKind.DESTRUCTIVE));

        assertEquals(DecisionType.CONFIRMED, confirmed.type());
        assertEquals(ConfirmationKind.DESTRUCTIVE, confirmed.kind());
        assertEquals(DecisionType.NONE, impossibleKind.type());
    }

    @Test
    void detect_preservesAmbiguityAndCancelAllSemantics() {
        when(promptCompletionService.completeClassification(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                anyDouble(), anyInt()))
                .thenReturn("```json\n{\"decision\":\"AMBIGUOUS\",\"kind\":null}\n```")
                .thenReturn("{\"decision\":\"CANCELLED\",\"kind\":\"ALL\"}");
        EnumSet<ConfirmationKind> pending = EnumSet.of(
                ConfirmationKind.DESTRUCTIVE, ConfirmationKind.SCENE_REPLACEMENT);

        assertEquals(DecisionType.AMBIGUOUS, detector.detect("Proceed.", pending).type());
        ConfirmationDecision cancelled = detector.detect("Cancel the pending work.", pending);
        assertEquals(DecisionType.CANCELLED, cancelled.type());
        assertEquals(null, cancelled.kind());
    }

    @Test
    void detect_failsClosedForMissingPendingContextOrInvalidModelOutput() {
        assertEquals(DecisionType.NONE,
                detector.detect("yes", EnumSet.noneOf(ConfirmationKind.class)).type());

        when(promptCompletionService.completeClassification(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                anyDouble(), anyInt())).thenReturn("not-json");

        assertEquals(DecisionType.NONE,
                detector.detect("Any reply", EnumSet.of(ConfirmationKind.CHOICE)).type());
    }
}
