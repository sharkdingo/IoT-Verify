package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmRole;
import cn.edu.nju.Iot_Verify.component.ai.provider.LlmProvider;
import cn.edu.nju.Iot_Verify.configure.LlmConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
