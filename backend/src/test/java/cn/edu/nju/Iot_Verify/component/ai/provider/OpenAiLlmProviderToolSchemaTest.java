package cn.edu.nju.Iot_Verify.component.ai.provider;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.configure.LlmConfig;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonValue;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiLlmProviderToolSchemaTest {

    @Test
    void chatReadsOnlyExplicitSafeReasoningSummaryFields() {
        OpenAiLlmProvider provider = new OpenAiLlmProvider(new LlmConfig());
        ChatCompletion safeCompletion = completion(Map.of(
                "reasoning_summary", JsonValue.from("Checked current board state."),
                "reasoning_content", JsonValue.from("private chain of thought")));
        ChatCompletion rawOnlyCompletion = completion(Map.of(
                "reasoning_content", JsonValue.from("must remain hidden")));

        LlmChatResponse safe = ReflectionTestUtils.invokeMethod(
                provider, "parseResponse", safeCompletion);
        LlmChatResponse rawOnly = ReflectionTestUtils.invokeMethod(
                provider, "parseResponse", rawOnlyCompletion);

        assertEquals("Checked current board state.", safe.reasoningSummary());
        assertTrue(rawOnly.reasoningSummary().isBlank());
    }

    @Test
    void chatForwardsRootAndNestedAdditionalPropertiesWithoutLosingConstraints() {
        FunctionParameterSchema nestedClosed = new FunctionParameterSchema(
                "object",
                Map.of("id", Map.of("type", "integer", "minimum", 1)),
                List.of("id"));
        FunctionParameterSchema nestedOpen = new FunctionParameterSchema(
                "object",
                Map.of("label", Map.of("type", "string", "maxLength", 40)),
                List.of());
        nestedOpen.additionalProperties = true;
        FunctionParameterSchema closedRoot = new FunctionParameterSchema(
                "object",
                Map.of(
                        "closed", nestedClosed,
                        "open", nestedOpen,
                        "entries", Map.of(
                                "type", "array",
                                "items", nestedClosed,
                                "minItems", 1)),
                List.of("closed", "entries"));
        FunctionParameterSchema openRoot = new FunctionParameterSchema(
                "object", Map.of(), List.of());
        openRoot.additionalProperties = true;

        ChatCompletionServiceAsync completions = mock(ChatCompletionServiceAsync.class);
        OpenAiLlmProvider provider = providerWith(completions);
        provider.chat(LlmChatRequest.builder()
                .messages(List.of(LlmMessage.user("use a tool")))
                .tools(List.of(
                        LlmToolSpec.of("closed_tool", "closed", closedRoot),
                        LlmToolSpec.of("open_tool", "open", openRoot)))
                .build());

        ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor.forClass(
                ChatCompletionCreateParams.class);
        verify(completions).createStreaming(captor.capture());
        List<ChatCompletionTool> tools = captor.getValue().tools().orElseThrow();

        FunctionParameters closedParameters = tools.get(0).asFunction().function()
                .parameters().orElseThrow();
        assertFalse(closedParameters._additionalProperties()
                .get("additionalProperties").convert(Boolean.class));
        JsonNode required = closedParameters._additionalProperties()
                .get("required").convert(JsonNode.class);
        assertEquals(List.of("closed", "entries"), List.of(
                required.get(0).asText(), required.get(1).asText()));

        JsonNode properties = closedParameters._additionalProperties()
                .get("properties").convert(JsonNode.class);
        assertEquals("object", properties.path("closed").path("type").asText());
        assertFalse(properties.path("closed").path("additionalProperties").asBoolean(true));
        assertEquals(1, properties.path("closed").path("properties")
                .path("id").path("minimum").asInt());
        assertEquals("id", properties.path("closed").path("required").get(0).asText());
        assertTrue(properties.path("open").path("additionalProperties").asBoolean(false));
        assertEquals(40, properties.path("open").path("properties")
                .path("label").path("maxLength").asInt());
        assertFalse(properties.path("entries").path("items")
                .path("additionalProperties").asBoolean(true));
        assertEquals(1, properties.path("entries").path("minItems").asInt());

        FunctionParameters openParameters = tools.get(1).asFunction().function()
                .parameters().orElseThrow();
        assertTrue(openParameters._additionalProperties()
                .get("additionalProperties").convert(Boolean.class));
    }

    @SuppressWarnings("unchecked")
    private OpenAiLlmProvider providerWith(ChatCompletionServiceAsync completions) {
        OpenAIClient client = mock(OpenAIClient.class);
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        ChatServiceAsync chatService = mock(ChatServiceAsync.class);
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completions);
        AsyncStreamResponse<ChatCompletionChunk> response = mock(AsyncStreamResponse.class);
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(response);
        org.mockito.Mockito.doAnswer(invocation -> {
            AsyncStreamResponse.Handler<ChatCompletionChunk> handler = invocation.getArgument(0);
            handler.onNext(ChatCompletionChunk.builder().id("plan").created(0L).model("mock-model")
                    .addChoice(ChatCompletionChunk.Choice.builder().index(0L)
                            .delta(ChatCompletionChunk.Choice.Delta.builder().content("").build())
                            .finishReason(ChatCompletionChunk.Choice.FinishReason.STOP).build())
                    .build());
            return response;
        }).when(response).subscribe(any(AsyncStreamResponse.Handler.class));
        when(response.onCompleteFuture()).thenReturn(CompletableFuture.completedFuture(null));

        LlmConfig config = new LlmConfig();
        config.setModel("mock-model");
        OpenAiLlmProvider provider = new OpenAiLlmProvider(config);
        when(client.withOptions(any())).thenReturn(client);
        ReflectionTestUtils.setField(provider, "transport", new okhttp3.OkHttpClient());
        ReflectionTestUtils.setField(provider, "client", client);
        return provider;
    }

    private ChatCompletion completion(Map<String, JsonValue> additionalProperties) {
        ChatCompletionMessage message = mock(ChatCompletionMessage.class);
        when(message.content()).thenReturn(Optional.of(""));
        when(message.toolCalls()).thenReturn(Optional.empty());
        when(message._additionalProperties()).thenReturn(additionalProperties);
        ChatCompletion.Choice choice = mock(ChatCompletion.Choice.class);
        when(choice.message()).thenReturn(message);
        ChatCompletion completion = mock(ChatCompletion.class);
        when(completion.choices()).thenReturn(List.of(choice));
        return completion;
    }
}
