package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.component.ai.LlmMessageCodec;
import cn.edu.nju.Iot_Verify.component.ai.chat.ChatToolProgressPresenter;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiConversationHistoryServiceTest {

    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmMessageCodec codec = new LlmMessageCodec(mapper);
    private final AiConversationHistoryService service = new AiConversationHistoryService(
            messages, sessions, codec, new ChatToolProgressPresenter(mapper), mapper);

    @BeforeEach
    void ownSession() {
        when(sessions.findByIdAndUserId("s1", 7L)).thenReturn(Optional.of(new ChatSessionPo()));
    }

    @Test
    void readsOlderCanonicalOperationsWithoutMistakingDraftOrPreviewForAppliedScene() {
        ChatMessagePo draft = operation(2, "recommend_scenario", """
                {"draftStored":true,"scenario":{"name":"Temperature control"}}
                """);
        ChatMessagePo preview = operation(4, "apply_scenario", """
                {"operation":"preview","requiresUserConfirmation":true,
                 "resultStatus":"PREVIEW","resultAvailable":true,
                 "impactToken":"must-never-be-replayed"}
                """);
        ChatMessagePo applied = operation(6, "apply_scenario", """
                {"operation":"replaced","resultStatus":"SUCCESS","resultAvailable":true,
                 "verificationStatus":"NOT_VERIFIED","deviceCount":2,"ruleCount":2,"specificationCount":2}
                """);
        when(messages.findToolActivityPage("s1", 1000L, PageRequest.of(0, 4)))
                .thenReturn(List.of(applied, preview, draft));

        var page = service.readPage(7L, "s1", 1000L, 3);

        assertFalse(page.hasMore());
        assertNull(page.nextBeforeMessageId());
        assertEquals(List.of(2L, 4L, 6L), page.activities().stream().map(row -> row.get("messageId")).toList());
        assertFalse(page.activities().get(0).containsKey("appliedAction"));
        assertFalse(page.activities().get(1).containsKey("appliedAction"));
        assertEquals("SCENE_APPLIED", page.activities().get(2).get("appliedAction"));
        assertEquals("NOT_VERIFIED", page.activities().get(2).get("verificationStatus"));
        assertTrue(page.activities().stream().allMatch(row -> "AVAILABLE".equals(row.get("evidenceStatus"))));
        assertFalse(page.toString().contains("must-never-be-replayed"));
        verify(messages, never()).findTop80BySessionIdOrderByIdDesc(anyString());
    }

    @Test
    void cursorReadsEveryPageWithoutSkippingAnOlderOperation() {
        ChatMessagePo newest = operation(10, "manage_environment", "{\"operation\":\"unchanged\",\"changesApplied\":false}");
        ChatMessagePo middle = operation(8, "manage_board_history", "{\"operation\":\"undone\",\"applied\":true}");
        ChatMessagePo oldest = operation(6, "manage_board_history", "{\"operation\":\"redone\",\"applied\":true}");
        when(messages.findToolActivityPage("s1", null, PageRequest.of(0, 3)))
                .thenReturn(List.of(newest, middle, oldest));
        when(messages.findToolActivityPage("s1", 8L, PageRequest.of(0, 3))).thenReturn(List.of(oldest));

        var recent = service.readPage(7L, "s1", null, 2);
        assertTrue(recent.hasMore());
        assertEquals(8L, recent.nextBeforeMessageId());
        assertFalse(recent.activities().get(1).containsKey("appliedAction"));
        var early = service.readPage(7L, "s1", recent.nextBeforeMessageId(), 2);
        assertFalse(early.hasMore());
        assertEquals(List.of(6L), early.activities().stream().map(row -> row.get("messageId")).toList());
        assertEquals("BOARD_REDONE", early.activities().get(0).get("appliedAction"));
    }

    @Test
    void failedUncertainAndMalformedResultsNeverAcquireAnAppliedReceipt() {
        ChatMessagePo failed = operation(2, "apply_scenario", "{\"error\":\"Cancelled\",\"errorCode\":\"CANCELLED\",\"status\":499}");
        ChatMessagePo uncertain = operation(4, "apply_scenario", """
                {"resultStatus":"RESULT_UNAVAILABLE","resultAvailable":false,"mutationMayHaveCommitted":true}
                """);
        ChatMessagePo malformed = operation(6, "apply_scenario", "{\"operation\":\"replaced\",\"resultAvailable\":\"true\"}");
        when(messages.findToolActivityPage("s1", null, PageRequest.of(0, 4)))
                .thenReturn(List.of(malformed, uncertain, failed));

        var page = service.readPage(7L, "s1", null, 3);
        assertEquals(List.of("FAILED", "UNAVAILABLE", "UNAVAILABLE"),
                page.activities().stream().map(row -> row.get("evidenceStatus")).toList());
        assertTrue(page.activities().stream().noneMatch(row -> row.containsKey("appliedAction")));
        assertTrue(page.activities().get(1).containsKey("mutationMayHaveCommitted"));
        assertEquals("PERSISTED_OPERATION_EVIDENCE_INVALID", page.activities().get(2).get("unavailableReasonCode"));
    }

    @Test
    void mismatchedCallCannotTurnAssistantProseIntoProof() {
        ChatMessagePo row = operation(2, "apply_scenario", "{\"operation\":\"replaced\"}");
        ChatMessagePo prose = new ChatMessagePo();
        prose.setRole("assistant");
        prose.setContent("I applied the scene.");
        when(messages.findFirstBySessionIdAndIdLessThanAndRoleOrderByIdDesc("s1", 2L, "assistant"))
                .thenReturn(Optional.of(prose));
        when(messages.findToolActivityPage("s1", null, PageRequest.of(0, 2))).thenReturn(List.of(row));

        var entry = service.readPage(7L, "s1", null, 1).activities().get(0);
        assertEquals("UNAVAILABLE", entry.get("evidenceStatus"));
        assertFalse(entry.containsKey("appliedAction"));
    }

    @Test
    void ownershipAndPageBoundsAreCheckedBeforeReadingAnyActivity() {
        assertThrows(ResourceNotFoundException.class, () -> service.readPage(8L, "s1", null, 3));
        assertThrows(cn.edu.nju.Iot_Verify.exception.BadRequestException.class,
                () -> service.readPage(7L, "s1", 0L, 3));
        verifyNoInteractions(messages);
    }

    private ChatMessagePo operation(long id, String toolName, String result) {
        String callId = "call-" + id;
        ChatMessagePo request = new ChatMessagePo();
        request.setId(id - 1);
        request.setRole("assistant");
        request.setContent(codec.serializeToolCalls(List.of(new LlmToolCall(callId, toolName, "{}"))));
        when(messages.findFirstBySessionIdAndIdLessThanAndRoleOrderByIdDesc("s1", id, "assistant"))
                .thenReturn(Optional.of(request));
        ChatMessagePo row = new ChatMessagePo();
        row.setId(id);
        row.setRole("tool");
        row.setContent(codec.serializeToolResult(callId, result));
        return row;
    }
}
