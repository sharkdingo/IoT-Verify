package cn.edu.nju.Iot_Verify.component.ai.provider;

import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.configure.LlmConfig;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.testutil.LogCapture;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static cn.edu.nju.Iot_Verify.component.ai.provider.OpenAiLlmProviderPlanningStreamTest.Gateway;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiLlmProviderResponsesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MESSAGE = """
            {"id":"msg-1","type":"message","role":"assistant","status":"completed",
             "content":[{"type":"output_text","text":"Done.","annotations":[]}]}
            """;
    private static final String CALL = """
            {"id":"fc-1","type":"function_call","call_id":"call-1","name":"board_overview",
             "arguments":"{}","status":"completed"}
            """;
    private static final String REASONING = """
            {"id":"rs-1","type":"reasoning","summary":[{"type":"summary_text","text":"Safe summary."}],
             "encrypted_content":"opaque-private-state"}
            """;

    @ParameterizedTest
    @ValueSource(strings = {"type", "code", "unknown", "missing", "invalid-type"})
    void unavailableModelHasASafeDiagnosticWithoutLoggingProviderText(String field) throws Exception {
        String sensitive = "private-provider-text-never-log";
        String errorFields = switch (field) {
            case "type" -> "\"type\":\"model_not_found\"";
            case "code" -> "\"code\":\"model_not_found\"";
            case "missing" -> "";
            case "invalid-type" -> "\"type\":42,\"code\":[]";
            default -> "\"type\":\"" + sensitive + "\",\"code\":\"" + sensitive + "\"";
        };
        String body = "{\"error\":{\"message\":\"" + sensitive + "\""
                + (errorFields.isEmpty() ? "" : "," + errorFields) + "}}";
        try (Gateway gateway = new Gateway(body, null, false, 404);
             LogCapture logs = LogCapture.forClass(OpenAiLlmProvider.class)) {
            ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.chat(request()));
            assertEquals("AI service is temporarily unavailable", failure.getMessage());
            String reason = List.of("type", "code").contains(field) ? "MODEL_NOT_FOUND" : "UNCLASSIFIED";
            assertTrue(logs.messages().stream().anyMatch(message ->
                    message.contains("status=404") && message.contains("reason=" + reason)));
            assertTrue(logs.messages().stream().anyMatch(message ->
                    message.contains("LLM stream finished:") && message.contains("model=gpt-6-luna")));
            assertFalse(logs.messages().stream().anyMatch(message -> message.contains(sensitive)));
            assertTrue(logs.events().stream().allMatch(event -> event.getThrowableProxy() == null));
            assertEquals(1, gateway.requests.size());
        }
    }

    @Test
    void leadingProjectSystemMessagesBecomeInstructionsAndLaterRemindersKeepTheirPosition() throws Exception {
        var request = request(List.of(LlmMessage.system("Project semantics."), LlmMessage.system("Tool policy."),
                LlmMessage.user("inspect"), LlmMessage.system("Check the returned evidence.")));
        try (Gateway gateway = new Gateway(completed(MESSAGE))) {
            gateway.provider.chat(request);
            var sent = gateway.requests.get(0);
            assertEquals("Project semantics.\n\nTool policy.", sent.path("instructions").asText());
            assertEquals(2, sent.path("input").size());
            assertEquals("user", sent.path("input").get(0).path("role").asText());
            assertEquals("inspect", sent.path("input").get(0).path("content").asText());
            assertEquals("system", sent.path("input").get(1).path("role").asText());
            assertEquals("Check the returned evidence.", sent.path("input").get(1).path("content").asText());
        }
    }

    @Test
    void aCompletedEventCannotOverrideAnIncompleteResponseStatus() throws Exception {
        try (Gateway gateway = new Gateway(terminal("response.completed", "incomplete", CALL, null))) {
            assertThrows(ServiceUnavailableException.class, () -> gateway.provider.chat(request()));
        }
    }

    @Test
    void explicitResponsesSupportsDeploymentNamesAndAutoRecognizesDatedFamilyIds() {
        LlmConfig config = new LlmConfig();
        assertTrue(config.usesResponses("gpt-6-luna-2026-10-01"));
        assertFalse(config.usesResponses("my-deployment"));
        config.setApi("responses");
        assertTrue(config.usesResponses("my-deployment"));
    }

    @Test
    void reasoningModelUsesResponsesWithCompleteSchemasAndUnchangedOutputBudget() throws Exception {
        try (Gateway gateway = new Gateway(completed(MESSAGE))) {
            assertEquals("Done.", gateway.provider.chat(request()).text());
            JsonNode sent = gateway.requests.get(0);
            assertEquals(List.of("/v1/responses"), gateway.paths);
            assertTrue(sent.path("stream").asBoolean());
            assertFalse(sent.path("store").asBoolean(true));
            assertEquals("medium", sent.path("reasoning").path("effort").asText());
            assertEquals("auto", sent.path("reasoning").path("summary").asText());
            assertFalse(sent.has("temperature"));
            assertEquals(8192, sent.path("max_output_tokens").asInt());
            assertEquals("reasoning.encrypted_content", sent.path("include").get(0).asText());
            assertEquals("board_overview", sent.path("tools").get(0).path("name").asText());
            assertFalse(sent.path("tools").get(0).path("strict").asBoolean(true));
            assertEquals("string", sent.path("tools").get(0).path("parameters")
                    .path("properties").path("scope").path("type").asText());
        }
    }

    @Test
    void completedReasoningAndCallsAreReplayedIntactWithTheirMatchingToolResults() throws Exception {
        try (Gateway gateway = new Gateway(sent -> sent.path("input").size() == 1
                ? completed(REASONING + "," + CALL) : completed(MESSAGE), null, false, 200)) {
            var first = gateway.provider.chat(request());
            assertEquals("Safe summary.", first.reasoningSummary());
            assertEquals("call-1", first.toolCalls().get(0).id());
            assertEquals(2, first.continuationItems().size());
            var second = request(List.of(LlmMessage.user("inspect the board"),
                    LlmMessage.assistantToolCalls(first.toolCalls(), first.continuationItems()),
                    LlmMessage.tool("call-1", "{\"devices\":[]}")));
            assertEquals("Done.", gateway.provider.chat(second).text());
            JsonNode input = gateway.requests.get(1).path("input");
            assertEquals(4, input.size());
            assertEquals("opaque-private-state", input.get(1).path("encrypted_content").asText());
            assertEquals("rs-1", input.get(1).path("id").asText());
            assertEquals("fc-1", input.get(2).path("id").asText());
            assertEquals("call-1", input.get(2).path("call_id").asText());
            assertEquals("function_call_output", input.get(3).path("type").asText());
            assertEquals("call-1", input.get(3).path("call_id").asText());
            assertEquals("{\"devices\":[]}", input.get(3).path("output").asText());
            assertEquals(1, gateway.clientPorts.stream().distinct().count());
            assertFalse(JSON.writeValueAsString(first).contains("opaque-private-state"));
        }
    }

    @Test
    void classificationUsesNoneAndDoesNotRequestAnUnsupportedReasoningSummary() throws Exception {
        var request = LlmChatRequest.builder().model("gpt-6-luna")
                .messages(List.of(LlmMessage.user("Classify authorization")))
                .reasoningEffort("none").temperature(0.0).maxTokens(160).build();
        try (Gateway gateway = new Gateway(completed(MESSAGE))) {
            gateway.provider.chat(request);
            var sent = gateway.requests.get(0);
            assertEquals("none", sent.path("reasoning").path("effort").asText());
            assertFalse(sent.path("reasoning").has("summary"));
            assertEquals(0.0, sent.path("temperature").asDouble());
            assertEquals(160, sent.path("max_output_tokens").asInt());
            assertFalse(sent.has("tools"));
        }
    }

    @Test
    void publicTextArrivesBeforeCompletionAndStopDoesNotReturnPartialCalls() throws Exception {
        CountDownLatch release = new CountDownLatch(1), visible = new CountDownLatch(1);
        try (Gateway gateway = new Gateway(delta("Early answer."), release)) {
            LlmRequestControl control = new LlmRequestControl();
            StringBuilder text = new StringBuilder();
            CompletableFuture<LlmChatResponse> pending = CompletableFuture.supplyAsync(() ->
                    gateway.provider.chat(request(), control, part -> { text.append(part); visible.countDown(); }));
            try {
                assertTrue(visible.await(10, TimeUnit.SECONDS));
                assertEquals("Early answer.", text.toString());
                assertFalse(pending.isDone());
                control.cancel();
                assertEquals(LlmChatResponse.empty(), pending.get(5, TimeUnit.SECONDS));
            } finally {
                control.cancel();
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void refusalTextIsStreamedOnceForPlanningAndFinalReplies(boolean planning) throws Exception {
        String refusal = "I cannot help with that request.";
        String frames = """
                event: response.refusal.delta
                data: {"type":"response.refusal.delta","item_id":"msg-1","output_index":0,"content_index":0,"sequence_number":0,"delta":"I cannot "}

                event: response.refusal.delta
                data: {"type":"response.refusal.delta","item_id":"msg-1","output_index":0,"content_index":0,"sequence_number":1,"delta":"help with that request."}

                event: response.refusal.done
                data: {"type":"response.refusal.done","item_id":"msg-1","output_index":0,"content_index":0,"sequence_number":2,"refusal":"I cannot help with that request."}

                """ + completed("""
                {"id":"msg-1","type":"message","role":"assistant","status":"completed",
                 "content":[{"type":"refusal","refusal":"I cannot help with that request."}]}
                """).replace("\"sequence_number\":1", "\"sequence_number\":3");
        try (Gateway gateway = new Gateway(frames)) {
            List<String> visible = new ArrayList<>();
            if (planning) {
                LlmChatResponse response = gateway.provider.chat(request(), new LlmRequestControl(), visible::add);
                assertEquals(refusal, response.text());
                assertTrue(response.toolCalls().isEmpty());
            } else {
                gateway.provider.streamChat(request(), visible::add, () -> false);
            }
            assertEquals(List.of("I cannot ", "help with that request."), visible);
            assertEquals(1, gateway.requests.size());
        }
    }

    @Test
    void stopCancelsAResponsesRequestBeforeHeadersArrive() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Gateway gateway = new Gateway(completed(MESSAGE), release, true, 200)) {
            LlmRequestControl control = new LlmRequestControl();
            var pending = CompletableFuture.supplyAsync(() -> gateway.provider.chat(request(), control));
            try {
                assertTrue(gateway.requestReceived.await(10, TimeUnit.SECONDS));
                control.cancel();
                assertEquals(LlmChatResponse.empty(), pending.get(5, TimeUnit.SECONDS));
            } finally {
                control.cancel();
                release.countDown();
            }
        }
    }

    @Test
    void eofWithoutACompletedResponseDoesNotAuthorizeTools() throws Exception {
        try (Gateway gateway = new Gateway(delta("Partial."))) {
            assertThrows(ServiceUnavailableException.class, () -> gateway.provider.chat(request()));
            assertEquals(1, gateway.requests.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"incomplete", "failed"})
    void nonCompletedResultsAreNotReturnedAsSuccess(String status) throws Exception {
        try (Gateway gateway = new Gateway(terminal("response." + status, status, CALL, null))) {
            ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.chat(request()));
            assertFalse(error instanceof LlmStreamInterruptedException);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"insufficient_quota", "invalid_prompt"})
    void permanentResponseErrorsDoNotRequestAContinuation(String code) throws Exception {
        try (Gateway gateway = new Gateway(terminal("response.failed", "failed", CALL, code))) {
            ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.chat(request()));
            assertFalse(error instanceof LlmStreamInterruptedException);
            assertEquals(1, gateway.requests.size());
        }
    }

    @Test
    void serverErrorUsesTheExistingSingleContinuationWithoutSdkRetries() throws Exception {
        try (Gateway gateway = new Gateway(terminal("response.failed", "failed", CALL, "server_error"))) {
            assertThrows(LlmStreamInterruptedException.class, () -> gateway.provider.chat(request()));
            assertEquals(1, gateway.requests.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " call-1 ", "call-1"})
    void invalidOrRepeatedCallIdentitiesAreRejectedBeforeDispatch(String id) throws Exception {
        String invalid = CALL.replace("\"call-1\"", JSON.writeValueAsString(id));
        var previous = new cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall("call-1", "board_overview", "{}");
        try (Gateway gateway = new Gateway(completed(invalid))) {
            var request = request(List.of(LlmMessage.user("inspect"), LlmMessage.assistantToolCalls(List.of(previous)),
                    LlmMessage.tool("call-1", "{}")));
            assertThrows(ServiceUnavailableException.class, () -> gateway.provider.chat(request));
        }
    }

    @Test
    void aToolFreeReplyRejectsUnexpectedCalls() throws Exception {
        try (Gateway gateway = new Gateway(completed(CALL))) {
            assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.streamChat(request(), ignored -> {}, () -> false));
        }
    }

    @Test
    void forcingLegacyChatDoesNotSilentlyDisableReasoningWithTools() throws Exception {
        try (Gateway gateway = new Gateway(completed(MESSAGE))) {
            LlmConfig config = (LlmConfig) ReflectionTestUtils.getField(gateway.provider, "config");
            config.setApi("chat-completions");
            assertThrows(ServiceUnavailableException.class, () -> gateway.provider.chat(request()));
            assertTrue(gateway.requests.isEmpty());
        }
    }

    private static LlmChatRequest request() {
        return request(List.of(LlmMessage.user("inspect the board")));
    }

    private static LlmChatRequest request(List<LlmMessage> messages) {
        return LlmChatRequest.builder().model("gpt-6-luna").messages(messages).temperature(0.2).maxTokens(8192)
                .tools(List.of(LlmToolSpec.of("board_overview", "Read the board", new FunctionParameterSchema(
                        "object", Map.of("scope", Map.of("type", "string")), List.of())))).build();
    }

    private static String completed(String output) {
        return terminal("response.completed", "completed", output, null);
    }

    private static String terminal(String event, String status, String output, String errorCode) {
        return "event: " + event + "\ndata: {\"type\":\"" + event + "\",\"sequence_number\":1,\"response\":{"
                + "\"id\":\"resp-1\",\"object\":\"response\",\"created_at\":0,\"model\":\"gpt-6-luna\","
                + "\"status\":\"" + status + "\",\"output\":[" + output.replace("\n", "") + "],\"error\":"
                + (errorCode == null ? "null" : "{\"code\":\"" + errorCode + "\",\"message\":\"Unavailable\"}")
                + ",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15,"
                + "\"input_tokens_details\":{\"cached_tokens\":0},\"output_tokens_details\":{\"reasoning_tokens\":2}}}}\n\n";
    }

    private static String delta(String text) {
        return "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\","
                + "\"item_id\":\"msg-1\",\"output_index\":0,\"content_index\":0,\"sequence_number\":0,"
                + "\"delta\":\"" + text + "\"}\n\n";
    }
}
