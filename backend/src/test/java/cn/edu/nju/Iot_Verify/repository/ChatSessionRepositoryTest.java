package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.ChatExecutionLeaseGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@H2RepositoryTest
class ChatSessionRepositoryTest {

    @Autowired
    private ChatSessionRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // The Propagation.NOT_SUPPORTED tests below COMMIT their sessions instead of rolling back, and
    // all @DataJpaTest classes share one cached H2 context. Clear committed rows after every test so
    // leftover sessions cannot leak into sibling tests (order-dependent, CI-only). The child fence
    // table is cleared first for its FK.
    @AfterEach
    void clearCommittedSessions() {
        jdbcTemplate.update("DELETE FROM chat_session_pre_admission_stop");
        jdbcTemplate.update("DELETE FROM chat_session");
    }

    @Test
    void directSessionDeletionCascadesPreAdmissionStopFences() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("cascade-session");
        session.setUserId(1L);
        LocalDateTime stoppedAt = LocalDateTime.now();
        session.setPreAdmissionStopTurns(new LinkedHashMap<>(Map.of(
                "turn-one", stoppedAt,
                "turn-two", stoppedAt)));
        repository.saveAndFlush(session);

        ChatSessionPo persisted = repository.findById(session.getId()).orElseThrow();
        assertEquals(stoppedAt, persisted.getPreAdmissionStopTurns().get("turn-one"));
        assertEquals(stoppedAt, persisted.getPreAdmissionStopTurns().get("turn-two"));

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_session_pre_admission_stop WHERE session_id = ?",
                Integer.class,
                session.getId()));
        assertEquals(1, jdbcTemplate.update("DELETE FROM chat_session WHERE id = ?", session.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_session_pre_admission_stop WHERE session_id = ?",
                Integer.class,
                session.getId()));
    }

    @Test
    void leaseCleanupClearsOnlyExpiredRows() {
        LocalDateTime now = repository.currentDatabaseTime();
        assertNotNull(now);
        repository.saveAndFlush(session("live", "live-execution", now.plusSeconds(20)));
        repository.saveAndFlush(session("expired", "expired-execution", now.minusSeconds(1)));

        assertEquals(1, repository.clearExpiredExecutionLeases(now));

        ChatSessionPo live = repository.findById("live").orElseThrow();
        ChatSessionPo expired = repository.findById("expired").orElseThrow();
        assertEquals("live-execution", live.getActiveExecutionId());
        assertEquals("turn-live", live.getActiveExecutionTurnId());
        assertTrue(live.getActiveExecutionExpiresAt().isAfter(now));
        assertTrue(Boolean.TRUE.equals(live.getExecutionStopRequested()));
        assertNull(expired.getActiveExecutionId());
        assertNull(expired.getActiveExecutionTurnId());
        assertNull(expired.getActiveExecutionExpiresAt());
        assertFalse(Boolean.TRUE.equals(expired.getExecutionStopRequested()));
        assertFalse(Boolean.TRUE.equals(expired.getExecutionUserStopRequested()));
    }

    @Test
    void batchLeaseLockSelectsOnlyRequestedExecutionIdsInStableOrder() {
        LocalDateTime now = repository.currentDatabaseTime();
        repository.saveAndFlush(activeSession("batch-b", "execution-b", now.plusMinutes(1)));
        repository.saveAndFlush(activeSession("batch-a", "execution-a", now.plusMinutes(1)));
        repository.saveAndFlush(activeSession("batch-other", "execution-other", now.plusMinutes(1)));

        List<ChatSessionPo> selected = repository.findByIdInForUpdate(
                Set.of("batch-a", "batch-b"));

        assertEquals(List.of("batch-a", "batch-b"), selected.stream().map(ChatSessionPo::getId).toList());
    }

    @Test
    void executionLeaseGuardRejectsWritesAfterTheSessionOwnerChanges() {
        LocalDateTime now = repository.currentDatabaseTime();
        ChatSessionPo session = session("guarded", "current-execution", now.plusMinutes(1));
        session.setExecutionStopRequested(false);
        session.setExecutionUserStopRequested(false);
        repository.saveAndFlush(session);
        ChatExecutionLeaseGuard guard = new ChatExecutionLeaseGuard(repository);

        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("guarded");
        UserContextHolder.setChatExecutionId("current-execution");
        try {
            assertDoesNotThrow(guard::requireCurrentExecutionLease);

            session.setActiveExecutionId("replacement-execution");
            repository.saveAndFlush(session);

            assertThrows(ConflictException.class, guard::requireCurrentExecutionLease);
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void executionLeaseGuardDoesNotBlockHeartbeatDuringBusinessWork() throws Exception {
        LocalDateTime now = inTransaction(repository::currentDatabaseTime);
        saveSession(activeSession("guard-heartbeat", "current-execution", now.plusMinutes(1)));
        ChatExecutionLeaseGuard guard = new ChatExecutionLeaseGuard(repository);
        CountDownLatch guardRegistered = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> guardedTransaction = executor.submit(() -> {
            UserContextHolder.setUserId(1L);
            UserContextHolder.setChatSessionId("guard-heartbeat");
            UserContextHolder.setChatExecutionId("current-execution");
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    guard.requireCurrentExecutionLease();
                    guardRegistered.countDown();
                    await(allowCommit);
                });
            } finally {
                UserContextHolder.clear();
            }
        });

        try {
            assertTrue(guardRegistered.await(2, TimeUnit.SECONDS));
            Future<Integer> renewal = executor.submit(() -> inTransaction(() -> {
                LocalDateTime renewalTime = repository.currentDatabaseTime();
                ChatSessionPo current = repository.findByIdAndUserIdForUpdate("guard-heartbeat", 1L)
                        .orElseThrow();
                current.setActiveExecutionExpiresAt(renewalTime.plusMinutes(1));
                repository.saveAndFlush(current);
                return 1;
            }));

            assertEquals(1, renewal.get(2, TimeUnit.SECONDS));
        } finally {
            allowCommit.countDown();
            guardedTransaction.get(2, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void executionLeaseGuardRejectsCommitAfterLeaseExpiresDuringBusinessWork(boolean lockEarly) {
        LocalDateTime now = inTransaction(repository::currentDatabaseTime);
        ChatSessionPo session = activeSession(
                "guard-expiry",
                "current-execution",
                now.plusSeconds(1));
        session.setTitle("before");
        saveSession(session);
        ChatSessionRepository clockedRepository = mock(ChatSessionRepository.class, delegatesTo(repository));
        doReturn(now, now.plusSeconds(2)).when(clockedRepository).currentDatabaseTime();
        ChatExecutionLeaseGuard guard = new ChatExecutionLeaseGuard(clockedRepository);
        AtomicBoolean initialCheckPassed = new AtomicBoolean(false);

        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("guard-expiry");
        UserContextHolder.setChatExecutionId("current-execution");
        try {
            assertThrows(ConflictException.class, () -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        if (lockEarly) guard.requireCurrentExecutionLeaseAndLock();
                        else guard.requireCurrentExecutionLease();
                        initialCheckPassed.set(true);
                        ChatSessionPo pending = repository.findById("guard-expiry").orElseThrow();
                        pending.setTitle("attempted write");
                        repository.saveAndFlush(pending);
                    }));
            assertTrue(initialCheckPassed.get(), "The lease must expire after the initial check");
            assertEquals("before", inTransaction(() -> repository.findById("guard-expiry").orElseThrow().getTitle()));
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void executionLeaseGuardRejectsCommitAfterOwnerChangesDuringBusinessWork() throws Exception {
        LocalDateTime now = inTransaction(repository::currentDatabaseTime);
        saveSession(activeSession("guard-owner-change", "current-execution", now.plusMinutes(1)));
        ChatSessionPo businessRecord = new ChatSessionPo();
        businessRecord.setId("guarded-business-record");
        businessRecord.setUserId(1L);
        businessRecord.setTitle("before");
        saveSession(businessRecord);
        ChatExecutionLeaseGuard guard = new ChatExecutionLeaseGuard(repository);
        CountDownLatch guardRegistered = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<?> guardedTransaction = executor.submit(() -> {
            UserContextHolder.setUserId(1L);
            UserContextHolder.setChatSessionId("guard-owner-change");
            UserContextHolder.setChatExecutionId("current-execution");
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    guard.requireCurrentExecutionLease();
                    ChatSessionPo pendingWrite = repository.findById("guarded-business-record").orElseThrow();
                    pendingWrite.setTitle("after");
                    repository.save(pendingWrite);
                    guardRegistered.countDown();
                    await(allowCommit);
                });
            } finally {
                UserContextHolder.clear();
            }
        });

        try {
            assertTrue(guardRegistered.await(2, TimeUnit.SECONDS));
            inTransaction(() -> {
                ChatSessionPo claimed = repository.findByIdAndUserIdForUpdate("guard-owner-change", 1L)
                        .orElseThrow();
                claimed.setActiveExecutionId("replacement-execution");
                repository.saveAndFlush(claimed);
                return null;
            });
        } finally {
            allowCommit.countDown();
        }

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> guardedTransaction.get(2, TimeUnit.SECONDS));
        assertTrue(hasCause(failure, ConflictException.class));
        assertEquals("before", inTransaction(() -> repository.findById("guarded-business-record")
                .orElseThrow()
                .getTitle()));
        executor.shutdownNow();
    }

    private void saveSession(ChatSessionPo session) {
        inTransaction(() -> repository.saveAndFlush(session));
    }

    private ChatSessionPo activeSession(String id, String executionId, LocalDateTime expiresAt) {
        ChatSessionPo session = session(id, executionId, expiresAt);
        session.setExecutionStopRequested(false);
        session.setExecutionUserStopRequested(false);
        return session;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating test", e);
        }
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> expectedType) {
        Throwable current = failure;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ChatSessionPo session(String id, String executionId, LocalDateTime expiresAt) {
        ChatSessionPo session = new ChatSessionPo();
        session.setId(id);
        session.setUserId(1L);
        session.setActiveExecutionId(executionId);
        session.setActiveExecutionTurnId("turn-" + id);
        session.setActiveExecutionExpiresAt(expiresAt);
        session.setExecutionStopRequested(true);
        session.setExecutionUserStopRequested(true);
        return session;
    }
}
