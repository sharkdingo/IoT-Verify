package cn.edu.nju.Iot_Verify.component.aitool.chat;

import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.AiConversationHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadUserMessagesToolTest {
    private final AiConversationHistoryService history = mock(AiConversationHistoryService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReadUserMessagesTool tool = new ReadUserMessagesTool(history, mapper);

    @AfterEach void clearContext() { UserContextHolder.clear(); }

    @Test void retrievesExactOriginalsOnlyFromTheAuthenticatedConversation() throws Exception {
        UserContextHolder.setUserId(7L);
        UserContextHolder.setChatSessionId("s1");
        when(history.readOriginals(7L, "s1", null, 42L, null, 5)).thenReturn(
                new AiConversationHistoryService.OriginalPage(List.of(
                        new AiConversationHistoryService.UserOriginal(42L, "请保留门锁，先不要应用。")), null, false));
        var result = mapper.readTree(tool.execute("{\"messageId\":42}"));
        assertEquals("请保留门锁，先不要应用。", result.path("messages").get(0).path("text").asText());
        assertTrue(result.path("historicalEvidenceOnly").asBoolean());
        assertFalse(result.path("hasMore").asBoolean());
        assertEquals(1, result.path("count").asInt());
        verify(history).readOriginals(7L, "s1", null, 42L, null, 5);
    }

    @Test void rejectsForeignSessionArgumentsAndInvalidTypesBeforeAccess() throws Exception {
        UserContextHolder.setUserId(7L);
        for (String args : List.of("{\"sessionId\":\"foreign\"}", "{\"limit\":11}",
                "{\"messageId\":0}", "{\"messageId\":1.5}", "{\"query\":null}",
                "{\"beforeMessageId\":null}", "{\"query\":true}")) {
            assertEquals("VALIDATION_ERROR", mapper.readTree(tool.execute(args)).path("errorCode").asText());
        }
        verifyNoInteractions(history);
    }

    @Test void aMissingConversationCannotLookLikeAnEmptySuccessfulSearch() throws Exception {
        UserContextHolder.setUserId(7L);
        UserContextHolder.setChatSessionId("deleted");
        when(history.readOriginals(7L, "deleted", null, null, null, 5))
                .thenThrow(ResourceNotFoundException.session("deleted"));
        var result = mapper.readTree(tool.execute("{}"));
        assertEquals(404, result.path("status").asInt());
        assertFalse(result.has("messages"));
        assertFalse(result.has("count"));
    }
}
