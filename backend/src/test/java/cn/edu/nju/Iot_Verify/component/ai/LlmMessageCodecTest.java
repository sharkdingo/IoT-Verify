package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmRole;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the persisted-content ⇄ domain-model conversion previously covered by the
 * ArkAiClient parsing tests, now owned by {@link LlmMessageCodec} and expressed in
 * vendor-neutral {@link LlmMessage} terms.
 */
class LlmMessageCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmMessageCodec codec = new LlmMessageCodec(objectMapper);

    @Test
    void opaqueContinuationRemainsInMemoryAndNeverEntersHistoryOrJson() throws Exception {
        var calls = List.of(new LlmToolCall("call-opaque", "board_overview", "{}"));
        var response = new LlmChatResponse("visible", calls, "safe summary", List.of("encrypted-private-state"));
        var message = LlmMessage.assistantToolCalls(response.toolCalls(), response.continuationItems());
        assertEquals(List.of("encrypted-private-state"), message.continuationItems());
        assertFalse(objectMapper.writeValueAsString(response).contains("encrypted-private-state"));
        assertFalse(objectMapper.writeValueAsString(message).contains("encrypted-private-state"));
        assertFalse(response.toString().contains("encrypted-private-state"));
        assertFalse(message.toString().contains("encrypted-private-state"));

        ChatMessagePo po = new ChatMessagePo();
        po.setRole("assistant");
        po.setContent(codec.serializeToolCalls(message.toolCalls()));
        assertFalse(po.getContent().contains("encrypted-private-state"));
        var restored = codec.toMessage(po);
        assertEquals(calls, restored.toolCalls());
        assertTrue(restored.continuationItems().isEmpty());
    }

    @Test
    void toMessages_shouldParseStructuredToolResult() {
        ChatMessagePo po = new ChatMessagePo();
        po.setRole("tool");
        po.setContent("{\"type\":\"tool_result\",\"toolCallId\":\"tc_1\",\"result\":\"ok\"}");

        List<LlmMessage> messages = codec.toMessages(List.of(po));

        assertEquals(1, messages.size());
        LlmMessage toolMsg = messages.get(0);
        assertEquals(LlmRole.TOOL, toolMsg.role());
        assertEquals("tc_1", toolMsg.toolCallId());
        assertEquals("ok", toolMsg.content());
    }

    @Test
    void toMessages_shouldParseStructuredAssistantToolCalls() throws Exception {
        String content = objectMapper.writeValueAsString(Map.of(
                "type", LlmMessageCodec.TOOL_CALLS_JSON_TYPE,
                "toolCalls", List.of(Map.of(
                        "id", "call_1",
                        "type", "function",
                        "function", Map.of("name", "search_devices", "arguments", "{}")))));

        ChatMessagePo po = new ChatMessagePo();
        po.setRole("assistant");
        po.setContent(content);

        List<LlmMessage> messages = codec.toMessages(List.of(po));

        assertEquals(1, messages.size());
        LlmMessage assistantMsg = messages.get(0);
        assertEquals(LlmRole.ASSISTANT, assistantMsg.role());
        assertTrue(assistantMsg.hasToolCalls());
        assertEquals(1, assistantMsg.toolCalls().size());
        assertEquals("search_devices", assistantMsg.toolCalls().get(0).name());
    }

    @Test
    void toMessages_shouldParseStructuredToolResultWithLeadingWhitespace() {
        ChatMessagePo po = new ChatMessagePo();
        po.setRole("tool");
        po.setContent("  \n {\"type\":\"tool_result\",\"toolCallId\":\"tc_2\",\"result\":\"done\"}");

        List<LlmMessage> messages = codec.toMessages(List.of(po));

        assertEquals(1, messages.size());
        LlmMessage toolMsg = messages.get(0);
        assertEquals(LlmRole.TOOL, toolMsg.role());
        assertEquals("tc_2", toolMsg.toolCallId());
        assertEquals("done", toolMsg.content());
    }

    @Test
    void toMessages_shouldParseAssistantToolCallsWithLeadingWhitespace() throws Exception {
        String content = "  " + objectMapper.writeValueAsString(Map.of(
                "type", LlmMessageCodec.TOOL_CALLS_JSON_TYPE,
                "toolCalls", List.of(Map.of(
                        "id", "call_2",
                        "type", "function",
                        "function", Map.of("name", "list_devices", "arguments", "{}")))));

        ChatMessagePo po = new ChatMessagePo();
        po.setRole("assistant");
        po.setContent(content);

        List<LlmMessage> messages = codec.toMessages(List.of(po));

        assertEquals(1, messages.size());
        LlmMessage assistantMsg = messages.get(0);
        assertEquals(LlmRole.ASSISTANT, assistantMsg.role());
        assertTrue(assistantMsg.hasToolCalls());
        assertEquals("list_devices", assistantMsg.toolCalls().get(0).name());
    }

    @Test
    void toMessages_shouldRejectUnstructuredToolContentInsteadOfInventingAResult() {
        ChatMessagePo po = new ChatMessagePo();
        po.setRole("tool");
        po.setContent("raw tool output");

        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class,
                () -> codec.toMessages(List.of(po)));
        assertEquals("PERSISTED_SEMANTIC_DATA_INVALID", error.getReasonCode());
        assertEquals("content", error.getField());
    }

    @Test
    void roundTrip_serializeThenParseToolCalls() {
        String serialized = codec.serializeToolCalls(List.of(new LlmToolCall("id_9", "add_device", "{\"x\":1}")));

        ChatMessagePo po = new ChatMessagePo();
        po.setRole("assistant");
        po.setContent(serialized);

        LlmMessage msg = codec.toMessages(List.of(po)).get(0);
        assertTrue(msg.hasToolCalls());
        assertEquals("id_9", msg.toolCalls().get(0).id());
        assertEquals("add_device", msg.toolCalls().get(0).name());
        assertEquals("{\"x\":1}", msg.toolCalls().get(0).argumentsJson());
    }

    @Test
    void formalFixSuggestionsUseStableReferencesWithoutExposingTheirSignatureToTheModel() throws Exception {
        String signature = "signed-proposal-" + "x".repeat(390);
        String result = objectMapper.writeValueAsString(Map.of(
                "traceId", 31,
                "suggestions", List.of(
                        Map.of("strategy", "parameter", "description", "Lower the threshold",
                                "suggestionToken", signature),
                        Map.of("strategy", "remove", "description", "Remove the conflicting rule",
                                "suggestionToken", "second-signature"))));
        ChatMessagePo po = new ChatMessagePo();
        po.setId(521L);
        po.setRole("tool");
        po.setContent(codec.serializeToolResult("fix-call", result));

        var projected = objectMapper.readTree(codec.toMessage(po).content());

        assertEquals("521:1", projected.path("suggestions").get(0).path("suggestionId").asText());
        assertEquals("521:2", projected.path("suggestions").get(1).path("suggestionId").asText());
        assertEquals("Lower the threshold", projected.path("suggestions").get(0).path("description").asText());
        assertFalse(projected.path("suggestions").get(0).has("suggestionToken"));
        assertFalse(projected.path("suggestions").get(1).has("suggestionToken"));
        assertTrue(po.getContent().contains(signature));
        assertEquals(projected, objectMapper.readTree(codec.toMessage(po).content()));
    }

    @Test
    void signedRepairOptionsWithoutAPersistedIdentityCannotBecomeSelectable() {
        ChatMessagePo po = new ChatMessagePo();
        po.setRole("tool");
        po.setContent(codec.serializeToolResult("fix-call",
                "{\"suggestions\":[{\"suggestionToken\":\"signature\"}]}"));

        assertThrows(PersistedDataIntegrityException.class, () -> codec.toMessage(po));
    }
}
