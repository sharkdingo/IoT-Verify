package cn.edu.nju.Iot_Verify.testutil;

import cn.edu.nju.Iot_Verify.component.ai.LlmMessageCodec;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import cn.edu.nju.Iot_Verify.service.AiToolResultReferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Keeps the real read projection and reference resolver in targeted edit tests. */
public final class AiToolReadFixture {
    public final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    public final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    public final ObjectMapper mapper = new ObjectMapper();
    public final LlmMessageCodec codec = new LlmMessageCodec(mapper);
    public final AiToolResultReferenceService service = new AiToolResultReferenceService(messages, sessions, mapper, codec);
    public ChatMessagePo lastRequest;
    public ChatMessagePo lastResult;
    private long nextId = 521;

    public JsonNode read(Long userId, String sessionId, String source, String result) throws Exception {
        long id = nextId++;
        String callId = "read-" + id;
        ChatSessionPo session = new ChatSessionPo();
        session.setId(sessionId);
        session.setUserId(userId);
        lenient().when(sessions.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(session));
        lastRequest = new ChatMessagePo();
        lastRequest.setSessionId(sessionId);
        lastRequest.setRole("assistant");
        lastRequest.setContent(codec.serializeToolCalls(List.of(new LlmToolCall(callId, source, "{}"))));
        ObjectNode complete = (ObjectNode) mapper.readTree(result);
        if (!complete.has("resultStatus")) complete.put("resultStatus", "SUCCESS");
        if (!complete.has("resultAvailable")) complete.put("resultAvailable", true);
        lastResult = new ChatMessagePo();
        lastResult.setId(id);
        lastResult.setSessionId(sessionId);
        lastResult.setRole("tool");
        lastResult.setContent(codec.serializeToolResult(callId, complete.toString()));
        lenient().when(messages.findByIdAndSessionIdAndRole(id, sessionId, "tool")).thenReturn(Optional.of(lastResult));
        lenient().when(messages.findFirstBySessionIdAndIdLessThanAndRoleOrderByIdDesc(sessionId, id, "assistant"))
                .thenReturn(Optional.of(lastRequest));
        return mapper.readTree(codec.toMessages(List.of(lastRequest, lastResult)).get(1).content());
    }
}
