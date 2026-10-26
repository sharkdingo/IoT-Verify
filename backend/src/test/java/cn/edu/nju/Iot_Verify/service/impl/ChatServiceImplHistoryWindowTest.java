package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.ai.chat.ChatToolProgressPresenter;
import cn.edu.nju.Iot_Verify.component.ai.LlmChatService;
import cn.edu.nju.Iot_Verify.component.ai.LlmMessageCodec;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.component.ai.model.ChatExecutionStatus;
import cn.edu.nju.Iot_Verify.component.ai.AiTaskContinuationStore;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolManager;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.scenario.AiScenarioDraftStore;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.chat.ChatMessageResponseDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatHistoryPageDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatSessionResponseDto;
import cn.edu.nju.Iot_Verify.dto.chat.StreamResponseDto;
import cn.edu.nju.Iot_Verify.exception.ChatSessionBusyException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.util.mapper.ChatMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplHistoryWindowTest {

    @Mock
    private ChatSessionRepository sessionRepo;
    @Mock
    private ChatMessageRepository messageRepo;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LlmChatService llmChatService;
    @Mock
    private AiToolManager aiToolManager;
    @Mock
    private AiDestructiveActionGuard destructiveActionGuard;
    @Mock
    private AiScenarioDraftStore scenarioDraftStore;
    @Mock
    private AiTaskContinuationStore taskContinuationStore;
    @Mock
    private ChatMapper chatMapper;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Captor
    private ArgumentCaptor<List<ChatMessagePo>> chatMessageListCaptor;

    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(org.mockito.Mockito.mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(org.mockito.ArgumentMatchers.any());
        lenient().when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(UserPo.builder().id(1L).build()));
        lenient().when(sessionRepo.currentDatabaseTime()).thenAnswer(invocation -> LocalDateTime.now());
        service = new ChatServiceImpl(
                sessionRepo,
                messageRepo,
                userRepository,
                llmChatService,
                new LlmMessageCodec(new ObjectMapper()),
                new ChatConfirmationDetector(
                        org.mockito.Mockito.mock(cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService.class),
                        new ObjectMapper()),
                aiToolManager,
                destructiveActionGuard,
                scenarioDraftStore,
                taskContinuationStore,
                org.mockito.Mockito.mock(cn.edu.nju.Iot_Verify.service.AiTaskMemoryService.class),
                new ObjectMapper(),
                new ChatToolProgressPresenter(new ObjectMapper()),
                chatMapper,
                transactionTemplate,
                new ChatExecutionConfig()
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSmartHistory_shouldUseIdOrderedRecentWindowDespiteClockSkew() throws Exception {
        ChatMessagePo newest = new ChatMessagePo();
        newest.setId(2L);
        newest.setRole("assistant");
        newest.setContent("reply");
        newest.setCreatedAt(LocalDateTime.of(2026, 7, 24, 8, 0));

        ChatMessagePo older = new ChatMessagePo();
        older.setId(1L);
        older.setRole("user");
        older.setContent("hello");
        older.setCreatedAt(LocalDateTime.of(2026, 7, 24, 12, 0));

        // The later id remains authoritative even when another JVM supplied an earlier timestamp.
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1"))
                .thenReturn(List.of(newest, older));

        Method method = ChatServiceImpl.class.getDeclaredMethod("getSmartHistory", String.class, int.class);
        method.setAccessible(true);

        List<ChatMessagePo> history = (List<ChatMessagePo>) method.invoke(service, "s1", 4000);

        assertEquals(2, history.size());
        assertEquals(List.of(1L, 2L), history.stream().map(ChatMessagePo::getId).toList());
        verify(messageRepo).findTop80BySessionIdOrderByIdDesc("s1");
        verify(messageRepo, never()).findBySessionIdOrderByCreatedAtAsc("s1");
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSmartHistory_shouldDropOrphanToolMessagesAtWindowBoundary() throws Exception {
        ChatMessagePo newestAssistant = new ChatMessagePo();
        newestAssistant.setRole("assistant");
        newestAssistant.setContent("final reply");

        ChatMessagePo orphanTool = new ChatMessagePo();
        orphanTool.setRole("tool");
        orphanTool.setContent("{\"type\":\"tool_result\",\"toolCallId\":\"tc_1\",\"result\":\"ok\"}");

        ChatMessagePo olderUser = new ChatMessagePo();
        olderUser.setRole("user");
        olderUser.setContent("hello");

        // Repository returns DESC window. The matching assistant tool_call is outside this window.
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s2"))
                .thenReturn(List.of(newestAssistant, orphanTool, olderUser));

        Method method = ChatServiceImpl.class.getDeclaredMethod("getSmartHistory", String.class, int.class);
        method.setAccessible(true);

        List<ChatMessagePo> history = (List<ChatMessagePo>) method.invoke(service, "s2", 4000);

        assertEquals(2, history.size());
        assertTrue(history.stream().noneMatch(m -> "tool".equalsIgnoreCase(m.getRole())));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSmartHistory_shouldDropIncompletePersistedToolCallBlock() throws Exception {
        ChatMessagePo finalReply = new ChatMessagePo();
        finalReply.setRole("assistant");
        finalReply.setContent("The previous response could not be completed.");

        ChatMessagePo oneOfTwoResults = new ChatMessagePo();
        oneOfTwoResults.setRole("tool");
        oneOfTwoResults.setContent("{\"type\":\"tool_result\",\"toolCallId\":\"tc_1\",\"result\":\"ok\"}");

        ChatMessagePo incompleteCalls = new ChatMessagePo();
        incompleteCalls.setRole("assistant");
        incompleteCalls.setContent("{\"type\":\"tool_calls\",\"toolCalls\":["
                + "{\"id\":\"tc_1\",\"function\":{\"name\":\"board_overview\",\"arguments\":\"{}\"}},"
                + "{\"id\":\"tc_2\",\"function\":{\"name\":\"list_specs\",\"arguments\":\"{}\"}}]}");

        ChatMessagePo user = new ChatMessagePo();
        user.setRole("user");
        user.setContent("inspect the board");

        when(messageRepo.findTop80BySessionIdOrderByIdDesc("incomplete"))
                .thenReturn(List.of(finalReply, oneOfTwoResults, incompleteCalls, user));

        Method method = ChatServiceImpl.class.getDeclaredMethod("getSmartHistory", String.class, int.class);
        method.setAccessible(true);
        List<ChatMessagePo> history = (List<ChatMessagePo>) method.invoke(service, "incomplete", 4000);

        assertEquals(2, history.size());
        assertEquals("user", history.get(0).getRole());
        assertEquals("assistant", history.get(1).getRole());
        assertTrue(history.stream().noneMatch(m -> "tool".equalsIgnoreCase(m.getRole())));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSmartHistory_shouldDropAssistantToolCallWithoutAnyResult() throws Exception {
        ChatMessagePo incompleteCalls = new ChatMessagePo();
        incompleteCalls.setRole("assistant");
        incompleteCalls.setContent("{\"type\":\"tool_calls\",\"toolCalls\":["
                + "{\"id\":\"tc_1\",\"function\":{\"name\":\"board_overview\",\"arguments\":\"{}\"}}]}");

        ChatMessagePo user = new ChatMessagePo();
        user.setRole("user");
        user.setContent("inspect the board");

        when(messageRepo.findTop80BySessionIdOrderByIdDesc("missing-results"))
                .thenReturn(List.of(incompleteCalls, user));

        Method method = ChatServiceImpl.class.getDeclaredMethod("getSmartHistory", String.class, int.class);
        method.setAccessible(true);
        List<ChatMessagePo> history = (List<ChatMessagePo>) method.invoke(service, "missing-results", 4000);

        assertEquals(1, history.size());
        assertEquals("user", history.get(0).getRole());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSmartHistory_shouldKeepLatestMessageEvenWhenOverCharLimit() throws Exception {
        ChatMessagePo oversizedUser = new ChatMessagePo();
        oversizedUser.setRole("user");
        oversizedUser.setContent("x".repeat(4500));

        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s3"))
                .thenReturn(List.of(oversizedUser));

        Method method = ChatServiceImpl.class.getDeclaredMethod("getSmartHistory", String.class, int.class);
        method.setAccessible(true);

        List<ChatMessagePo> history = (List<ChatMessagePo>) method.invoke(service, "s3", 4000);

        assertEquals(1, history.size());
        assertEquals("user", history.get(0).getRole());
        assertEquals(4500, history.get(0).getContent().length());
    }

    @Test
    void getHistory_shouldHideInternalToolMessagesFromFrontend() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s4");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserId("s4", 1L)).thenReturn(Optional.of(session));

        ChatMessagePo userMsg = new ChatMessagePo();
        userMsg.setRole("user");
        userMsg.setContent("turn on the light");

        ChatMessagePo assistantToolCall = new ChatMessagePo();
        assistantToolCall.setRole("assistant");
        assistantToolCall.setContent("{\"type\":\"tool_calls\",\"toolCalls\":[]}");

        ChatMessagePo toolResult = new ChatMessagePo();
        toolResult.setRole("tool");
        toolResult.setContent("{\"type\":\"tool_result\",\"toolCallId\":\"tc_1\",\"result\":\"ok\"}");

        ChatMessagePo assistantFallback = new ChatMessagePo();
        assistantFallback.setRole("assistant");
        assistantFallback.setContent("Calling tool...");

        ChatMessagePo assistantReply = new ChatMessagePo();
        assistantReply.setRole("assistant");
        assistantReply.setContent("Done. The light is on.");

        stubHistoryPage("s4", List.of(
                userMsg, assistantToolCall, toolResult, assistantFallback, assistantReply));
        when(chatMapper.toChatMessageDtoList(anyList()))
                .thenReturn(List.of(new ChatMessageResponseDto(), new ChatMessageResponseDto()));

        List<ChatMessageResponseDto> history = service.getHistoryPage(1L, "s4", null, RequestLimits.MAX_CHAT_HISTORY_PAGE_SIZE).getMessages();

        assertEquals(2, history.size());
        verify(chatMapper).toChatMessageDtoList(chatMessageListCaptor.capture());
        List<ChatMessagePo> visible = chatMessageListCaptor.getValue();
        assertEquals(2, visible.size());
        assertEquals("user", visible.get(0).getRole());
        assertEquals("assistant", visible.get(1).getRole());
        assertTrue(visible.stream().noneMatch(m -> "tool".equalsIgnoreCase(m.getRole())));
    }

    @Test
    void getHistory_shouldKeepNormalAssistantMessageCallingToolWhenNotAdjacentToToolBlock() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s5");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserId("s5", 1L)).thenReturn(Optional.of(session));

        ChatMessagePo userMsg = new ChatMessagePo();
        userMsg.setRole("user");
        userMsg.setContent("say exactly Calling tool...");

        ChatMessagePo assistantLiteral = new ChatMessagePo();
        assistantLiteral.setRole("assistant");
        assistantLiteral.setContent("Calling tool...");

        ChatMessagePo assistantReply = new ChatMessagePo();
        assistantReply.setRole("assistant");
        assistantReply.setContent("Done.");

        stubHistoryPage("s5", List.of(userMsg, assistantLiteral, assistantReply));
        when(chatMapper.toChatMessageDtoList(anyList()))
                .thenReturn(List.of(new ChatMessageResponseDto(), new ChatMessageResponseDto(), new ChatMessageResponseDto()));

        List<ChatMessageResponseDto> history = service.getHistoryPage(1L, "s5", null, RequestLimits.MAX_CHAT_HISTORY_PAGE_SIZE).getMessages();

        assertEquals(3, history.size());
        verify(chatMapper).toChatMessageDtoList(chatMessageListCaptor.capture());
        List<ChatMessagePo> visible = chatMessageListCaptor.getValue();
        assertEquals(3, visible.size());
        assertEquals("assistant", visible.get(1).getRole());
        assertEquals("Calling tool...", visible.get(1).getContent());
    }

    @Test
    void getHistory_shouldNotSynthesizeExecutionEvidenceFromHiddenToolMessages() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("trace-history");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserId("trace-history", 1L)).thenReturn(Optional.of(session));

        ObjectMapper mapper = new ObjectMapper();
        LlmMessageCodec codec = new LlmMessageCodec(mapper);
        LocalDateTime started = LocalDateTime.of(2026, 7, 18, 10, 0, 0);

        ChatMessagePo userMsg = new ChatMessagePo();
        userMsg.setRole("user");
        userMsg.setContent("检查当前画布");
        userMsg.setCreatedAt(started);

        ChatMessagePo assistantToolCall = new ChatMessagePo();
        assistantToolCall.setRole("assistant");
        assistantToolCall.setContent(codec.serializeToolCalls(List.of(
                new LlmToolCall("tc_1", "board_overview", "{}"))));

        ChatMessagePo toolResult = new ChatMessagePo();
        toolResult.setRole("tool");
        toolResult.setContent(codec.serializeToolResult("tc_1", """
                {"devices":[{},{}],"rules":[{}],"specs":[{}],"environmentVariables":[{}]}
                """));

        ChatMessagePo assistantReply = new ChatMessagePo();
        assistantReply.setRole("assistant");
        assistantReply.setContent("画布包含两个设备。");
        assistantReply.setCreatedAt(started.plusSeconds(12));

        stubHistoryPage("trace-history", List.of(userMsg, assistantToolCall, toolResult, assistantReply));
        ChatMessageResponseDto userDto = new ChatMessageResponseDto();
        userDto.setRole("user");
        ChatMessageResponseDto assistantDto = new ChatMessageResponseDto();
        assistantDto.setRole("assistant");
        when(chatMapper.toChatMessageDtoList(anyList())).thenReturn(List.of(userDto, assistantDto));

        List<ChatMessageResponseDto> history = service.getHistoryPage(1L, "trace-history", null, RequestLimits.MAX_CHAT_HISTORY_PAGE_SIZE).getMessages();

        assertEquals(2, history.size());
        assertNull(assistantDto.getExecutionElapsedSeconds());
        assertNull(assistantDto.getExecutionTrace());
    }

    @Test
    void getHistory_shouldRestoreExactTraceMetadataWithoutToolCallReconstruction() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("exact-trace-history");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserId("exact-trace-history", 1L)).thenReturn(Optional.of(session));

        ChatMessagePo userMsg = new ChatMessagePo();
        userMsg.setRole("user");
        userMsg.setContent("continue");

        List<StreamResponseDto.ProgressDto> persisted = List.of(
                new StreamResponseDto.ProgressDto(
                        "CONTEXT_READY", null, null, null, null, null, null, null),
                new StreamResponseDto.ProgressDto(
                        "TASK_RESUMED", null, null, null, null, null, null, "resume objective"),
                new StreamResponseDto.ProgressDto(
                        "EXECUTION_GUARD", null, 2, "NO_PROGRESS", 1, 0, 0, null));
        ChatMessagePo assistantReply = new ChatMessagePo();
        assistantReply.setId(42L);
        assistantReply.setRole("assistant");
        assistantReply.setContent("Stopped after no progress.");
        assistantReply.setExecutionTraceJson(new ObjectMapper().writeValueAsString(persisted));
        assistantReply.setExecutionElapsedSeconds(43);

        stubHistoryPage("exact-trace-history", List.of(userMsg, assistantReply));
        ChatMessageResponseDto userDto = new ChatMessageResponseDto();
        userDto.setRole("user");
        ChatMessageResponseDto assistantDto = new ChatMessageResponseDto();
        assistantDto.setRole("assistant");
        when(chatMapper.toChatMessageDtoList(anyList())).thenReturn(List.of(userDto, assistantDto));

        service.getHistoryPage(1L, "exact-trace-history", null, RequestLimits.MAX_CHAT_HISTORY_PAGE_SIZE).getMessages();

        assertEquals(43, assistantDto.getExecutionElapsedSeconds());
        assertEquals(List.of("CONTEXT_READY", "TASK_RESUMED", "EXECUTION_GUARD"),
                assistantDto.getExecutionTrace().stream().map(StreamResponseDto.ProgressDto::getStage).toList());
        assertEquals("NO_PROGRESS", assistantDto.getExecutionTrace().get(2).getOutcome());
    }

    @Test
    void sessionListExposesTheSameAuthoritativeLeaseActivityUsedByPolling() {
        ChatSessionPo live = new ChatSessionPo();
        live.setId("live-session");
        live.setActiveExecutionId("live-execution");
        live.setActiveExecutionExpiresAt(LocalDateTime.now().plusMinutes(1));
        live.setLastSeenTerminalMessageId(7L);
        ChatSessionPo expired = new ChatSessionPo();
        expired.setId("expired-session");
        expired.setActiveExecutionId("expired-execution");
        expired.setActiveExecutionExpiresAt(LocalDateTime.now().minusSeconds(1));
        expired.setLastSeenTerminalMessageId(9L);
        ChatSessionResponseDto liveDto = new ChatSessionResponseDto();
        ChatSessionResponseDto expiredDto = new ChatSessionResponseDto();
        ChatMessageRepository.LatestTerminalView liveTerminal = mock(
                ChatMessageRepository.LatestTerminalView.class);
        when(liveTerminal.getSessionId()).thenReturn("live-session");
        when(liveTerminal.getMessageId()).thenReturn(8L);
        when(liveTerminal.getExecutionStatus()).thenReturn(ChatExecutionStatus.COMPLETED);
        ChatMessageRepository.LatestTerminalView expiredTerminal = mock(
                ChatMessageRepository.LatestTerminalView.class);
        when(expiredTerminal.getSessionId()).thenReturn("expired-session");
        when(expiredTerminal.getMessageId()).thenReturn(9L);
        when(expiredTerminal.getExecutionStatus()).thenReturn(ChatExecutionStatus.FAILED);
        when(sessionRepo.findTop100ByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(live, expired));
        when(chatMapper.toChatSessionDtoList(List.of(live, expired)))
                .thenReturn(List.of(liveDto, expiredDto));
        when(messageRepo.findLatestTerminalBySessionIdIn(List.of("live-session", "expired-session")))
                .thenReturn(List.of(liveTerminal, expiredTerminal));

        List<ChatSessionResponseDto> result = service.getUserSessions(1L);

        assertTrue(result.get(0).isActive());
        assertEquals(8L, result.get(0).getLatestTerminalMessageId());
        assertEquals(ChatExecutionStatus.COMPLETED, result.get(0).getLatestExecutionStatus());
        assertTrue(result.get(0).isHasUnreadUpdate());
        assertFalse(result.get(1).isActive());
        assertEquals(9L, result.get(1).getLatestTerminalMessageId());
        assertEquals(ChatExecutionStatus.FAILED, result.get(1).getLatestExecutionStatus());
        assertFalse(result.get(1).isHasUnreadUpdate());
    }

    @Test
    void markTerminalSeenAdvancesOnlyToTheExactOwnedTerminalMessage() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("seen-session");
        session.setUserId(1L);
        session.setLastSeenTerminalMessageId(20L);
        when(sessionRepo.findByIdAndUserIdForUpdate("seen-session", 1L))
                .thenReturn(Optional.of(session));
        when(messageRepo.existsByIdAndSessionIdAndRoleAndExecutionStatusIsNotNull(
                24L, "seen-session", "assistant")).thenReturn(true);

        service.markTerminalSeen(1L, "seen-session", 24L);

        assertEquals(24L, session.getLastSeenTerminalMessageId());
        verify(sessionRepo).saveAndFlush(session);
    }

    @Test
    void markTerminalSeenNeverMovesTheCursorBackwards() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("seen-session");
        session.setUserId(1L);
        session.setLastSeenTerminalMessageId(30L);
        when(sessionRepo.findByIdAndUserIdForUpdate("seen-session", 1L))
                .thenReturn(Optional.of(session));
        when(messageRepo.existsByIdAndSessionIdAndRoleAndExecutionStatusIsNotNull(
                24L, "seen-session", "assistant")).thenReturn(true);

        service.markTerminalSeen(1L, "seen-session", 24L);

        assertEquals(30L, session.getLastSeenTerminalMessageId());
        verify(sessionRepo, never()).saveAndFlush(any(ChatSessionPo.class));
    }

    @Test
    void markTerminalSeenRejectsAMessageThatIsNotATerminalInTheOwnedSession() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("seen-session");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserIdForUpdate("seen-session", 1L))
                .thenReturn(Optional.of(session));

        assertThrows(BadRequestException.class,
                () -> service.markTerminalSeen(1L, "seen-session", 24L));

        verify(sessionRepo, never()).saveAndFlush(any(ChatSessionPo.class));
    }

    @Test
    void historyPageReturnsNewestVisibleMessagesWithAnOlderCursor() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("paged-history");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserId("paged-history", 1L)).thenReturn(Optional.of(session));
        ChatMessagePo oldest = visibleMessage(1L, "user", "oldest");
        ChatMessagePo middle = visibleMessage(2L, "assistant", "middle");
        ChatMessagePo newest = visibleMessage(3L, "user", "newest");
        when(messageRepo.findBySessionIdOrderByIdDesc(eq("paged-history"), any(Pageable.class)))
                .thenReturn(List.of(newest, middle, oldest));
        when(chatMapper.toChatMessageDtoList(anyList()))
                .thenReturn(List.of(new ChatMessageResponseDto(), new ChatMessageResponseDto()));

        ChatHistoryPageDto page = service.getHistoryPage(1L, "paged-history", null, 2);

        assertEquals(2, page.getMessages().size());
        assertTrue(page.isHasMore());
        assertEquals(2L, page.getNextBeforeId());
        verify(chatMapper).toChatMessageDtoList(chatMessageListCaptor.capture());
        assertEquals(List.of("middle", "newest"), chatMessageListCaptor.getValue().stream()
                .map(ChatMessagePo::getContent).toList());
    }

    @Test
    void createSessionUsesOneDatabaseTimestampForOrderingFields() {
        LocalDateTime databaseTime = LocalDateTime.of(2026, 7, 24, 10, 15, 30);
        when(sessionRepo.currentDatabaseTime()).thenReturn(databaseTime);
        when(sessionRepo.save(any(ChatSessionPo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createSession(1L);

        ArgumentCaptor<ChatSessionPo> sessionCaptor = ArgumentCaptor.forClass(ChatSessionPo.class);
        verify(sessionRepo).save(sessionCaptor.capture());
        assertEquals(databaseTime, sessionCaptor.getValue().getCreatedAt());
        assertEquals(databaseTime, sessionCaptor.getValue().getUpdatedAt());
    }

    @Test
    void touchSessionTitleUsesDatabaseTimestamp() throws Exception {
        LocalDateTime databaseTime = LocalDateTime.of(2026, 7, 24, 10, 16, 45);
        ChatSessionPo session = new ChatSessionPo();
        session.setId("clocked-session");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserId("clocked-session", 1L)).thenReturn(Optional.of(session));
        when(sessionRepo.currentDatabaseTime()).thenReturn(databaseTime);

        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "touchSessionTitle", String.class, Long.class, String.class);
        method.setAccessible(true);
        method.invoke(service, "clocked-session", 1L, "inspect the board");

        assertEquals(databaseTime, session.getUpdatedAt());
        assertEquals("inspect the ...", session.getTitle());
        verify(sessionRepo).save(session);
    }

    @Test
    void touchSessionTitleFoldsUnicodeWhitespace() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("whitespace-title");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserId("whitespace-title", 1L)).thenReturn(Optional.of(session));

        invokeTitle("whitespace-title", " \t hello\n\u00A0world\r\u2028");

        assertEquals("hello world", session.getTitle());
    }

    @Test
    void touchSessionTitleTruncatesByCodePointWithoutSplittingEmoji() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("unicode-title");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserId("unicode-title", 1L)).thenReturn(Optional.of(session));

        invokeTitle("unicode-title", "12345678901\uD83D\uDE80tail");

        assertEquals("12345678901\uD83D\uDE80...", session.getTitle());
        assertEquals(12, session.getTitle().codePointCount(0, session.getTitle().indexOf("...")));
    }

    @Test
    void createSessionRejectsAnUnboundedCatalog() {
        when(sessionRepo.countByUserId(1L)).thenReturn(100L);

        assertThrows(cn.edu.nju.Iot_Verify.exception.BadRequestException.class,
                () -> service.createSession(1L));
        verify(sessionRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private ChatMessagePo visibleMessage(Long id, String role, String content) {
        ChatMessagePo message = new ChatMessagePo();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private void invokeTitle(String sessionId, String content) throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "touchSessionTitle", String.class, Long.class, String.class);
        method.setAccessible(true);
        method.invoke(service, sessionId, 1L, content);
    }

    private void stubHistoryPage(String sessionId, List<ChatMessagePo> chronological) {
        long id = 1L;
        for (ChatMessagePo message : chronological) {
            if (message.getId() == null) message.setId(id);
            id = Math.max(id + 1, message.getId() + 1);
        }
        List<ChatMessagePo> newestFirst = new ArrayList<>(chronological);
        Collections.reverse(newestFirst);
        when(messageRepo.findBySessionIdOrderByIdDesc(eq(sessionId), any(Pageable.class)))
                .thenReturn(newestFirst);
    }

    @Test
    void activeStream_shouldBlockConcurrentStreamAndSessionDeletionUntilServerFinishes() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("busy-session");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserId("busy-session", 1L)).thenReturn(Optional.of(session));
        when(sessionRepo.findByIdAndUserIdForUpdate("busy-session", 1L)).thenReturn(Optional.of(session));

        String executionId = service.beginStreamRequest(1L, "busy-session", "turn-busy", "hello");

        assertTrue(service.getSessionActivity(1L, "busy-session").isActive());
        assertThrows(ChatSessionBusyException.class,
                () -> service.beginStreamRequest(1L, "busy-session", "turn-busy", "hello"));
        assertThrows(ChatSessionBusyException.class,
                () -> service.deleteSession(1L, "busy-session"));
        verify(messageRepo, never()).deleteBySessionId("busy-session");

        service.endStreamRequest(1L, "busy-session", executionId);

        assertFalse(service.getSessionActivity(1L, "busy-session").isActive());
        service.deleteSession(1L, "busy-session");
        verify(messageRepo).deleteBySessionId("busy-session");
        verify(sessionRepo).deleteById("busy-session");
        verify(destructiveActionGuard).clearSession(1L, "busy-session");
    }
}
