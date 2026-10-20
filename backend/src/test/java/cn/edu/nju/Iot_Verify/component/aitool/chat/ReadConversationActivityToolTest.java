package cn.edu.nju.Iot_Verify.component.aitool.chat;

import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.AiConversationHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadConversationActivityToolTest {
    private final AiConversationHistoryService history = mock(AiConversationHistoryService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReadConversationActivityTool tool = new ReadConversationActivityTool(history, mapper);

    @AfterEach
    void clearContext() { UserContextHolder.clear(); }

    @Test
    void usesOnlyTheAuthenticatedCurrentConversationAndPreservesTheOlderCursor() throws Exception {
        UserContextHolder.setUserId(7L);
        UserContextHolder.setChatSessionId("s1");
        when(history.readPage(7L, "s1", null, 20)).thenReturn(
                new AiConversationHistoryService.ActivityPage(List.of(Map.of("messageId", 42L)), 42L, true));
        var result = mapper.readTree(tool.execute("{}"));
        assertEquals(42L, result.path("nextBeforeMessageId").asLong());
        assertTrue(result.path("hasMore").asBoolean());
        assertTrue(result.path("historicalEvidenceOnly").asBoolean());
        assertEquals(1, result.path("count").asInt());
        verify(history).readPage(7L, "s1", null, 20);
    }

    @Test
    void rejectsForeignSessionArgumentsAndInvalidPageBoundsBeforeReading() throws Exception {
        UserContextHolder.setUserId(7L);
        for (String args : List.of("{\"sessionId\":\"foreign\"}", "{\"limit\":31}",
                "{\"beforeMessageId\":0}", "{\"beforeMessageId\":null}")) {
            assertEquals("VALIDATION_ERROR", mapper.readTree(tool.execute(args)).path("errorCode").asText());
        }
        verifyNoInteractions(history);
    }

    @Test
    void unavailableHistoryIsAnErrorInsteadOfAnEmptySuccessfulAudit() throws Exception {
        UserContextHolder.setUserId(7L);
        UserContextHolder.setChatSessionId("deleted");
        when(history.readPage(7L, "deleted", null, 20)).thenThrow(ResourceNotFoundException.session("deleted"));
        var result = mapper.readTree(tool.execute("{}"));
        assertEquals(404, result.path("status").asInt());
        assertFalse(result.has("activities"));
        assertFalse(result.has("count"));
    }
}
