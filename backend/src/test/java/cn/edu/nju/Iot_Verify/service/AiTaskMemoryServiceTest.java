package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.component.ai.TaskMemory;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiTaskMemoryServiceTest {
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final PromptCompletionService completion = mock(PromptCompletionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ChatExecutionLeaseGuard lease = mock(ChatExecutionLeaseGuard.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatSessionPo session = new ChatSessionPo();
    private final AiTaskMemoryService service = new AiTaskMemoryService(sessions, messages, completion,
            users, mapper, new TransactionTemplate(mock(PlatformTransactionManager.class)), lease);

    @BeforeEach void setUp() {
        when(sessions.findByIdAndUserId("s", 7L)).thenReturn(Optional.of(session));
        when(sessions.findByIdAndUserIdForUpdate("s", 7L)).thenReturn(Optional.of(session));
        when(users.findSceneRevision(7L)).thenReturn(Optional.of(1L));
    }

    @Test void refreshReadsUnprocessedOriginalsBeyondTheRecentWindowAndPersistsSources() throws Exception {
        ChatMessagePo source = original(1, "长期要求：不要删除门锁");
        when(messages.findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(eq("s"), eq("user"), eq(0L), any()))
                .thenReturn(List.of(source));
        when(completion.completeClassification(anyString(), anyString(), eq(0.0), anyInt())).thenReturn("""
                {"add":[{"kind":"CONSTRAINT","scope":"SESSION","sourceMessageId":1,"quote":"不要删除门锁"}],"remove":[]}
                """);
        var view = service.refresh(7L, "s");
        assertEquals("CURRENT", view.status());
        assertEquals("不要删除门锁", view.items().get(0).quote());
        assertEquals(1, view.items().get(0).sourceMessageId());
        assertEquals(1, mapper.readTree(session.getTaskMemoryJson()).path("throughMessageId").longValue());
        verify(messages, never()).findTop80BySessionIdOrderByIdDesc(anyString());
    }

    @Test void malformedExtractionLeavesLastValidMemoryAndCoverageUntouched() throws Exception {
        prior();
        when(messages.findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(eq("s"), eq("user"), eq(1L), any()))
                .thenReturn(List.of(original(200, "现在只保留玄关门锁")));
        when(completion.completeClassification(anyString(), anyString(), eq(0.0), anyInt()))
                .thenReturn("{\"add\":[],\"remove\":[]} {\"add\":[]}");
        String before = session.getTaskMemoryJson();
        var view = service.refresh(7L, "s");
        assertEquals("PARTIAL", view.status());
        assertEquals(1, view.throughMessageId());
        assertEquals(before, session.getTaskMemoryJson());
    }

    @Test void sceneChangeHidesOldSceneNotesEvenWhenExtractionFails() throws Exception {
        prior();
        when(users.findSceneRevision(7L)).thenReturn(Optional.of(2L));
        when(messages.findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(eq("s"), eq("user"), eq(1L), any()))
                .thenReturn(List.of(original(200, "检查新场景")));
        when(completion.completeClassification(anyString(), anyString(), eq(0.0), anyInt()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        var view = service.refresh(7L, "s");
        assertTrue(view.sceneChanged());
        assertEquals(List.of("保留门锁"), view.items().stream().map(TaskMemory.Item::quote).toList());
        assertEquals("PARTIAL", view.status());
    }

    @Test void lostExecutionLeaseCannotPersistMemory() {
        when(messages.findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(eq("s"), eq("user"), eq(0L), any()))
                .thenReturn(List.of(original(1, "你好")));
        when(completion.completeClassification(anyString(), anyString(), eq(0.0), anyInt()))
                .thenReturn("{\"add\":[],\"remove\":[]}");
        doThrow(new ConflictException("lease lost")).when(lease).requireCurrentExecutionLeaseAndLock();
        assertThrows(ConflictException.class, () -> service.refresh(7L, "s"));
        assertNull(session.getTaskMemoryJson());
    }

    @Test void crossAccountReadAndRefreshAreRejectedBeforeProviderAccess() {
        assertThrows(ResourceNotFoundException.class, () -> service.read(8L, "s"));
        assertThrows(ResourceNotFoundException.class, () -> service.refresh(8L, "s"));
        verifyNoInteractions(completion, users, messages);
    }

    @Test void corruptMemoryIsExplicitlyUnavailableInsteadOfAnEmptySuccess() {
        session.setTaskMemoryJson("{\"schemaVersion\":0}");
        assertEquals("UNAVAILABLE", service.read(7L, "s").status());
        assertEquals("UNAVAILABLE", service.refresh(7L, "s").status());
        assertEquals("{\"schemaVersion\":0}", session.getTaskMemoryJson());
        verifyNoInteractions(completion);
    }

    @Test void backlogCannotRebindAnOldSceneRequestToTheCurrentScene() {
        when(messages.findUserOriginals(eq("s"), isNull(), isNull(), isNull(), any()))
                .thenReturn(List.of(original(200, "已换成新场景")));
        when(messages.findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(eq("s"), eq("user"), eq(0L), any()))
                .thenReturn(List.of(original(1, "检查旧客厅"), original(200, "已换成新场景")));
        when(completion.completeClassification(anyString(), anyString(), eq(0.0), anyInt())).thenReturn("""
                {"add":[{"kind":"GOAL","scope":"SCENE","sourceMessageId":1,"quote":"检查旧客厅"}],"remove":[]}
                """);
        var view = service.refresh(7L, "s");
        assertEquals("PARTIAL", view.status());
        assertTrue(view.items().isEmpty());
        assertNull(session.getTaskMemoryJson());
    }

    @Test void promptBudgetReportsOmittedSourcesInsteadOfSilentlyLosingRequirements() {
        var notes = java.util.stream.IntStream.range(0, 20).mapToObj(i ->
                new TaskMemory.Item("1:" + i, "CONSTRAINT", "SESSION", 1, "保留门锁".repeat(60))).toList();
        var context = service.context(new cn.edu.nju.Iot_Verify.dto.chat.ChatTaskMemoryDto("CURRENT", 1, false, notes), 2000);
        assertTrue(context.content().length() <= 2000);
        assertTrue(context.content().contains("omittedSourceMessageIds"));
        assertTrue(context.content().contains("PARTIAL"));
    }

    @Test void sceneReplacementDuringExtractionWithdrawsTheOldSceneAtReturn() throws Exception {
        prior();
        when(messages.findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(eq("s"), eq("user"), eq(1L), any()))
                .thenReturn(List.of(original(2, "谢谢")));
        when(completion.completeClassification(anyString(), anyString(), eq(0.0), anyInt()))
                .thenAnswer(invocation -> {
                    when(users.findSceneRevision(7L)).thenReturn(Optional.of(2L));
                    return "{\"add\":[],\"remove\":[]}";
                });
        var view = service.refresh(7L, "s");
        assertTrue(view.sceneChanged());
        assertEquals(List.of("保留门锁"), view.items().stream().map(TaskMemory.Item::quote).toList());
    }

    @Test void retriesOneMalformedModelReplyBeforeCommittingValidatedMemory() {
        when(messages.findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(eq("s"), eq("user"), eq(0L), any()))
                .thenReturn(List.of(original(1, "本会话不要删除门锁")));
        when(completion.completeClassification(anyString(), anyString(), eq(0.0), anyInt()))
                .thenReturn("我会记住这个要求。", """
                        {"add":[{"kind":"CONSTRAINT","scope":"SESSION","sourceMessageId":1,"quote":"不要删除门锁"}],"remove":[]}
                        """);
        var view = service.refresh(7L, "s");
        assertEquals("CURRENT", view.status());
        assertEquals("不要删除门锁", view.items().get(0).quote());
        verify(completion, times(2)).completeClassification(anyString(), anyString(), eq(0.0), anyInt());
        verify(sessions, times(1)).save(session);
    }

    @Test void acceptsACompleteJsonFenceWithoutTreatingItAsLostMemory() {
        when(messages.findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(eq("s"), eq("user"), eq(0L), any()))
                .thenReturn(List.of(original(1, "本会话不要删除门锁")));
        when(completion.completeClassification(anyString(), anyString(), eq(0.0), anyInt())).thenReturn("""
                ```json
                {"add":[{"kind":"CONSTRAINT","scope":"SESSION","sourceMessageId":1,"quote":"不要删除门锁"}],"remove":[]}
                ```
                """);
        var view = service.refresh(7L, "s");
        assertEquals("CURRENT", view.status());
        assertEquals("不要删除门锁", view.items().get(0).quote());
        verify(completion, times(1)).completeClassification(anyString(), anyString(), eq(0.0), anyInt());
    }

    @Test void repeatedInvalidOutputCannotEraseMemoryOrRetryWithoutBound() throws Exception {
        prior();
        when(messages.findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(eq("s"), eq("user"), eq(1L), any()))
                .thenReturn(List.of(original(2, "改为保留所有设备")));
        when(completion.completeClassification(anyString(), anyString(), eq(0.0), anyInt()))
                .thenReturn("```json\n{\"add\":[],\"remove\":[]}\n``` trailing text");
        String previous = session.getTaskMemoryJson();
        assertEquals("PARTIAL", service.refresh(7L, "s").status());
        assertEquals(previous, session.getTaskMemoryJson());
        verify(completion, times(2)).completeClassification(anyString(), anyString(), eq(0.0), anyInt());
        verify(sessions, never()).save(any());
    }

    private void prior() throws Exception {
        session.setTaskMemoryJson(mapper.writeValueAsString(new TaskMemory(1, 1, "1", List.of(
                new TaskMemory.Item("1:0", "CONSTRAINT", "SESSION", 1, "保留门锁"),
                new TaskMemory.Item("1:1", "GOAL", "SCENE", 1, "检查旧客厅")))));
    }
    private ChatMessagePo original(long id, String content) {
        ChatMessagePo row = new ChatMessagePo();
        row.setId(id); row.setRole("user"); row.setSessionId("s"); row.setContent(content);
        return row;
    }
}
