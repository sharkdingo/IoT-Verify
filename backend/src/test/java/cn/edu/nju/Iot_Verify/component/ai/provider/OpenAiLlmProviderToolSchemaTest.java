package cn.edu.nju.Iot_Verify.component.ai.provider;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.configure.LlmConfig;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.edu.nju.Iot_Verify.component.ai.provider.OpenAiLlmProviderPlanningStreamTest.Gateway;
import static cn.edu.nju.Iot_Verify.component.ai.provider.OpenAiLlmProviderPlanningStreamTest.chunk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
    void chatForwardsRootAndNestedAdditionalPropertiesWithoutLosingConstraints() throws Exception {
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

        String completed = chunk("{\"content\":\"\"}", "stop") + "data: [DONE]\n\n";
        try (Gateway gateway = new Gateway(completed)) {
            gateway.provider.chat(LlmChatRequest.builder()
                    .messages(List.of(LlmMessage.user("use a tool")))
                    .tools(List.of(
                            LlmToolSpec.of("closed_tool", "closed", closedRoot),
                            LlmToolSpec.of("open_tool", "open", openRoot)))
                    .build());
            assertEquals(List.of("/v1/chat/completions"), gateway.paths);
            JsonNode tools = gateway.requests.get(0).path("tools");
            JsonNode closedParameters = tools.get(0).path("function").path("parameters");

            assertEquals("function", tools.get(0).path("type").asText());
            assertEquals("closed_tool", tools.get(0).path("function").path("name").asText());
            assertFalse(closedParameters.path("additionalProperties").asBoolean(true));
            JsonNode required = closedParameters.path("required");
            assertEquals(List.of("closed", "entries"), List.of(
                    required.get(0).asText(), required.get(1).asText()));

            JsonNode properties = closedParameters.path("properties");
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

            assertEquals("function", tools.get(1).path("type").asText());
            assertEquals("open_tool", tools.get(1).path("function").path("name").asText());
            JsonNode openParameters = tools.get(1).path("function").path("parameters");
            assertTrue(openParameters.path("additionalProperties").asBoolean(false));
        }
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
