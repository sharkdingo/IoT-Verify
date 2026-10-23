package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmRole;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException;
import cn.edu.nju.Iot_Verify.component.ai.provider.LlmProvider;
import cn.edu.nju.Iot_Verify.configure.LlmConfig;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder.BoardWritePermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.OpenAIInvalidDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PromptCompletionServiceTest {

    @Test
    void classification_keepsTheSmallOutputBudgetWithoutConsumingItOnReasoning() {
        when(llmConfig.getModel()).thenReturn("gpt-6-luna");
        when(llmProvider.chat(requestCaptor.capture(), any(LlmRequestControl.class)))
                .thenReturn(LlmChatResponse.ofText("{\"allowed\":true}"));
        var service = new PromptCompletionService(llmProvider, llmConfig);

        assertEquals("{\"allowed\":true}", service.completeClassification("s", "u", 0.0, 160));
        assertEquals("none", requestCaptor.getValue().reasoningEffort());
        assertEquals(160, requestCaptor.getValue().maxTokens());
        assertEquals(0.0, requestCaptor.getValue().temperature());
    }

    @Test
    void classification_doesNotAddUnsupportedReasoningParametersToOtherModels() {
        when(llmConfig.getModel()).thenReturn("custom-chat-model");
        when(llmProvider.chat(requestCaptor.capture(), any(LlmRequestControl.class)))
                .thenReturn(LlmChatResponse.ofText("{}"));

        new PromptCompletionService(llmProvider, llmConfig).completeClassification("s", "u", 0.0, 160);
        assertNull(requestCaptor.getValue().reasoningEffort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALLOWED", "DENIED"})
    void classification_recoversOneTransientFailureUsingTheSameRequestAndControl(String decision) {
        ArgumentCaptor<LlmRequestControl> controls = ArgumentCaptor.forClass(LlmRequestControl.class);
        when(llmProvider.chat(requestCaptor.capture(), controls.capture()))
                .thenAnswer(invocation -> {
                    invocation.<LlmRequestControl>getArgument(1).requestAlternativeTransport();
                    throw new LlmStreamInterruptedException(new java.io.IOException("stream reset"));
                })
                .thenReturn(LlmChatResponse.ofText("{\"decision\":\"" + decision + "\"}"));
        var detector = new ChatConfirmationDetector(
                new PromptCompletionService(llmProvider, llmConfig), new ObjectMapper());

        assertEquals(BoardWritePermission.valueOf(decision), detector.detectBoardWritePermission("请保存这条规则"));
        verify(llmProvider, times(2)).chat(any(LlmChatRequest.class), any(LlmRequestControl.class));
        assertSame(requestCaptor.getAllValues().get(0), requestCaptor.getAllValues().get(1));
        assertSame(controls.getAllValues().get(0), controls.getAllValues().get(1));
        assertTrue(controls.getAllValues().get(1).isAlternativeTransportRequested());
        assertEquals(0, requestCaptor.getValue().tools().size());
    }

    @Test
    void classification_propagatesTheSecondTransientFailureWithoutAnotherAttempt() {
        var first = new LlmStreamInterruptedException(new java.io.IOException("first reset"));
        var second = new LlmStreamInterruptedException(new java.io.IOException("second reset"));
        when(llmProvider.chat(any(LlmChatRequest.class), any(LlmRequestControl.class)))
                .thenThrow(first, second);
        var service = new PromptCompletionService(llmProvider, llmConfig);

        assertSame(second, assertThrows(LlmStreamInterruptedException.class,
                () -> service.completeClassification("s", "u", 0.0, 160)));
        verify(llmProvider, times(2)).chat(any(LlmChatRequest.class), any(LlmRequestControl.class));
    }

    @Test
    void classification_remainsUnavailableWhenBothAttemptsFail() {
        when(llmProvider.chat(any(LlmChatRequest.class), any(LlmRequestControl.class)))
                .thenThrow(new LlmStreamInterruptedException(new java.io.IOException("reset")));
        var detector = new ChatConfirmationDetector(
                new PromptCompletionService(llmProvider, llmConfig), new ObjectMapper());

        assertEquals(BoardWritePermission.UNAVAILABLE, detector.detectBoardWritePermission("请保存这条规则"));
        verify(llmProvider, times(2)).chat(any(LlmChatRequest.class), any(LlmRequestControl.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not JSON", "{\"decision\":\"ALLOWED\",\"extra\":true}"})
    void classification_doesNotRetryMalformedCompletedDecisions(String response) {
        when(llmProvider.chat(any(LlmChatRequest.class), any(LlmRequestControl.class)))
                .thenReturn(LlmChatResponse.ofText(response));
        var detector = new ChatConfirmationDetector(
                new PromptCompletionService(llmProvider, llmConfig), new ObjectMapper());

        assertEquals(BoardWritePermission.UNAVAILABLE, detector.detectBoardWritePermission("请保存这条规则"));
        verify(llmProvider).chat(any(LlmChatRequest.class), any(LlmRequestControl.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "unavailable"})
    void classification_doesNotRetryUnclassifiedProviderFailures(String kind) {
        ServiceUnavailableException failure = "invalid".equals(kind)
                ? ServiceUnavailableException.aiService(new OpenAIInvalidDataException("incomplete response"))
                : ServiceUnavailableException.aiService();
        when(llmProvider.chat(any(LlmChatRequest.class), any(LlmRequestControl.class))).thenThrow(failure);
        var service = new PromptCompletionService(llmProvider, llmConfig);

        assertSame(failure, assertThrows(ServiceUnavailableException.class,
                () -> service.completeClassification("s", "u", 0.0, 160)));
        verify(llmProvider).chat(any(LlmChatRequest.class), any(LlmRequestControl.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "interrupted"})
    void classification_doesNotRetryAfterCancellationOrThreadInterruption(String stop) {
        var failure = new LlmStreamInterruptedException(new java.io.IOException("reset"));
        var control = new LlmRequestControl();
        when(llmProvider.chat(any(LlmChatRequest.class), any(LlmRequestControl.class)))
                .thenAnswer(invocation -> {
                    if ("cancelled".equals(stop)) control.cancel();
                    else Thread.currentThread().interrupt();
                    throw failure;
                });
        LlmRequestControlHolder.set(control);
        try {
            var service = new PromptCompletionService(llmProvider, llmConfig);
            assertSame(failure, assertThrows(LlmStreamInterruptedException.class,
                    () -> service.completeClassification("s", "u", 0.0, 160)));
            assertEquals("interrupted".equals(stop), Thread.currentThread().isInterrupted());
            verify(llmProvider).chat(any(LlmChatRequest.class), org.mockito.ArgumentMatchers.same(control));
        } finally {
            Thread.interrupted();
            LlmRequestControlHolder.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"general", "recommendation"})
    void nonClassificationCompletionsDoNotRetryTransientFailures(String purpose) {
        var failure = new LlmStreamInterruptedException(new java.io.IOException("reset"));
        when(llmProvider.chat(any(LlmChatRequest.class), any(LlmRequestControl.class))).thenThrow(failure);
        var service = new PromptCompletionService(llmProvider, llmConfig);

        assertSame(failure, assertThrows(LlmStreamInterruptedException.class, () -> {
            if ("general".equals(purpose)) service.complete("s", "u", 0.7, 4000);
            else service.completeRecommendation("s", "u", 0.7, 4000);
        }));
        verify(llmProvider).chat(any(LlmChatRequest.class), any(LlmRequestControl.class));
    }

    @Mock
    private LlmProvider llmProvider;
    @Mock
    private LlmConfig llmConfig;
    @Captor
    private ArgumentCaptor<LlmChatRequest> requestCaptor;

    @Test
    void complete_buildsSystemUserRequestWithParams_andReturnsText() {
        PromptCompletionService service = new PromptCompletionService(llmProvider, llmConfig);
        when(llmProvider.chat(requestCaptor.capture(), any(LlmRequestControl.class)))
                .thenReturn(LlmChatResponse.ofText("{\"recommendations\":[]}"));

        String result = service.complete("SYS", "USER", 0.7, 4000);

        assertEquals("{\"recommendations\":[]}", result);

        LlmChatRequest sent = requestCaptor.getValue();
        assertEquals(2, sent.messages().size());
        assertEquals(LlmRole.SYSTEM, sent.messages().get(0).role());
        assertEquals("SYS", sent.messages().get(0).content());
        assertEquals(LlmRole.USER, sent.messages().get(1).role());
        assertEquals("USER", sent.messages().get(1).content());
        assertEquals(0.7, sent.temperature());
        assertEquals(4000, sent.maxTokens());
        assertNull(sent.reasoningEffort(), "Analysis retains the configured model reasoning");
        // one-shot completions must not send tools
        assertEquals(0, sent.tools().size());
    }

    @Test
    void complete_returnsEmptyString_whenModelReturnsNothing() {
        PromptCompletionService service = new PromptCompletionService(llmProvider, llmConfig);
        when(llmProvider.chat(org.mockito.ArgumentMatchers.any(LlmChatRequest.class),
                any(LlmRequestControl.class)))
                .thenReturn(LlmChatResponse.empty());

        assertEquals("", service.complete("s", "u", 0.3, 100));
    }

    @Test
    void complete_usesTheCurrentRequestControl() {
        PromptCompletionService service = new PromptCompletionService(llmProvider, llmConfig);
        LlmRequestControl control = new LlmRequestControl();
        when(llmProvider.chat(any(LlmChatRequest.class), any(LlmRequestControl.class)))
                .thenReturn(LlmChatResponse.empty());

        LlmRequestControlHolder.set(control);
        try {
            service.complete("s", "u", 0.3, 100);
        } finally {
            LlmRequestControlHolder.clear();
        }

        verify(llmProvider).chat(any(LlmChatRequest.class), org.mockito.ArgumentMatchers.same(control));
    }
}
