package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.component.ai.model.ChatExecutionStatus;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ChatMessageRepositoryTest {

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // The Propagation.NOT_SUPPORTED test below COMMITs its rows instead of rolling back, and all
    // @DataJpaTest classes share one cached H2 context. Clear committed rows after every test so
    // they cannot leak into sibling tests (order-dependent, CI-only). Raw SQL is used rather than
    // deleteAllInBatch() so cleanup never autoflushes a persistence context left broken by a test
    // that deliberately triggers a constraint violation. Messages first for their FK to sessions.
    @AfterEach
    void clearCommittedRows() {
        jdbcTemplate.update("DELETE FROM chat_message");
        jdbcTemplate.update("DELETE FROM chat_session_pre_admission_stop");
        jdbcTemplate.update("DELETE FROM chat_session");
    }

    @Test
    void turnRoleConstraintRejectsASecondVisibleMessage() {
        messageRepository.saveAndFlush(message("session-1", "turn-1", "user", "execution-1"));

        assertThrows(DataIntegrityViolationException.class, () ->
                messageRepository.saveAndFlush(
                        message("session-1", "turn-1", "user", "execution-2")));
    }

    @Test
    void oneUserAndOneTerminalAssistantMayShareATurn() {
        messageRepository.saveAndFlush(message("session-2", "turn-1", "user", "execution-1"));
        messageRepository.saveAndFlush(message("session-2", "turn-1", "assistant", "execution-1"));

        assertEquals(2, messageRepository.countBySessionId("session-2"));
    }

    @Test
    void internalMessagesWithNullTurnIdsRemainRepeatable() {
        messageRepository.saveAndFlush(message("session-3", null, "assistant", "execution-1"));
        messageRepository.saveAndFlush(message("session-3", null, "assistant", "execution-1"));
        messageRepository.saveAndFlush(message("session-3", null, "tool", "execution-1"));
        messageRepository.saveAndFlush(message("session-3", null, "tool", "execution-1"));

        assertEquals(4, messageRepository.countBySessionId("session-3"));
    }

    @Test
    void exactExecutionDeleteCannotRemoveAnotherTurnOrRole() {
        messageRepository.saveAndFlush(message("session-4", "turn-1", "user", "execution-1"));
        messageRepository.saveAndFlush(message("session-4", "turn-1", "assistant", "execution-1"));
        messageRepository.saveAndFlush(message("session-4", "turn-2", "user", "execution-2"));

        assertEquals(0, messageRepository.deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                "session-4", "turn-1", "wrong-execution", "user"));
        assertEquals(1, messageRepository.deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                "session-4", "turn-1", "execution-1", "user"));

        assertFalse(messageRepository.existsBySessionIdAndTurnIdAndExecutionIdAndRole(
                "session-4", "turn-1", "execution-1", "user"));
        assertTrue(messageRepository.existsBySessionIdAndTurnIdAndExecutionIdAndRole(
                "session-4", "turn-1", "execution-1", "assistant"));
        assertTrue(messageRepository.existsBySessionIdAndTurnIdAndExecutionIdAndRole(
                "session-4", "turn-2", "execution-2", "user"));
    }

    @Test
    void latestTerminalProjectionReturnsOneAuthoritativeOutcomePerSession() {
        ChatMessagePo firstTerminal = message(
                "session-terminal-a", "turn-1", "assistant", "execution-1");
        firstTerminal.setExecutionStatus(ChatExecutionStatus.PARTIAL);
        messageRepository.saveAndFlush(firstTerminal);
        ChatMessagePo latestTerminal = message(
                "session-terminal-a", "turn-2", "assistant", "execution-2");
        latestTerminal.setExecutionStatus(ChatExecutionStatus.COMPLETED);
        messageRepository.saveAndFlush(latestTerminal);
        messageRepository.saveAndFlush(message(
                "session-terminal-a", "turn-3", "user", "execution-3"));
        ChatMessagePo otherSessionTerminal = message(
                "session-terminal-b", "turn-1", "assistant", "execution-4");
        otherSessionTerminal.setExecutionStatus(ChatExecutionStatus.FAILED);
        messageRepository.saveAndFlush(otherSessionTerminal);

        Map<String, ChatMessageRepository.LatestTerminalView> terminals =
                messageRepository.findLatestTerminalBySessionIdIn(
                                List.of("session-terminal-a", "session-terminal-b"))
                        .stream()
                        .collect(Collectors.toMap(
                                ChatMessageRepository.LatestTerminalView::getSessionId,
                                terminal -> terminal));

        assertEquals(latestTerminal.getId(), terminals.get("session-terminal-a").getMessageId());
        assertEquals(ChatExecutionStatus.COMPLETED,
                terminals.get("session-terminal-a").getExecutionStatus());
        assertEquals(otherSessionTerminal.getId(),
                terminals.get("session-terminal-b").getMessageId());
        assertEquals(ChatExecutionStatus.FAILED,
                terminals.get("session-terminal-b").getExecutionStatus());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void leaseAndAdmittedUserTurnRollBackInOneTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            ChatSessionPo session = new ChatSessionPo();
            session.setId("session-atomic");
            session.setUserId(7L);
            session.setTitle("New Chat");
            sessionRepository.saveAndFlush(session);
        });

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            ChatSessionPo session = sessionRepository.findById("session-atomic").orElseThrow();
            session.setActiveExecutionId("execution-atomic");
            session.setActiveExecutionExpiresAt(LocalDateTime.now().plusMinutes(1));
            sessionRepository.saveAndFlush(session);
            messageRepository.saveAndFlush(
                    message("session-atomic", "turn-atomic", "user", "execution-atomic"));
            throw new IllegalStateException("force admission rollback");
        }));

        transaction.executeWithoutResult(status -> {
            ChatSessionPo session = sessionRepository.findById("session-atomic").orElseThrow();
            assertNull(session.getActiveExecutionId());
            assertNull(session.getActiveExecutionExpiresAt());
            assertFalse(messageRepository.existsBySessionIdAndTurnIdAndExecutionIdAndRole(
                    "session-atomic", "turn-atomic", "execution-atomic", "user"));
        });
    }

    @Test
    void activityPagesUseExactSessionRoleAndIdCursorWithoutLosingOlderToolEvidence() {
        ChatMessagePo request = messageRepository.saveAndFlush(message("activity-owner", null, "assistant", "e1"));
        ChatMessagePo older = messageRepository.saveAndFlush(message("activity-owner", null, "tool", "e1"));
        ChatMessagePo newer = messageRepository.saveAndFlush(message("activity-owner", null, "tool", "e1"));
        messageRepository.saveAndFlush(message("activity-owner", "visible", "user", "e2"));
        messageRepository.saveAndFlush(message("activity-other", null, "tool", "e3"));
        var page = org.springframework.data.domain.PageRequest.of(0, 1);

        assertEquals(List.of(newer.getId()), messageRepository.findToolActivityPage("activity-owner", null, page)
                .stream().map(ChatMessagePo::getId).toList());
        assertEquals(List.of(older.getId()), messageRepository.findToolActivityPage("activity-owner", newer.getId(), page)
                .stream().map(ChatMessagePo::getId).toList());
        assertTrue(messageRepository.findToolActivityPage("activity-owner", older.getId(), page).isEmpty());
        assertEquals(request.getId(), messageRepository
                .findFirstBySessionIdAndIdLessThanAndRoleOrderByIdDesc("activity-owner", newer.getId(), "assistant")
                .orElseThrow().getId());
    }

    private ChatMessagePo message(
            String sessionId, String turnId, String role, String executionId) {
        ChatMessagePo message = new ChatMessagePo();
        message.setSessionId(sessionId);
        message.setTurnId(turnId);
        message.setRole(role);
        message.setExecutionId(executionId);
        message.setContent(role + " content");
        return message;
    }
}
