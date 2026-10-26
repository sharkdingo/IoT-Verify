package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.ai.chat.ChatToolProgressPresenter;
import cn.edu.nju.Iot_Verify.component.ai.AiTaskContinuationStore;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector;
import cn.edu.nju.Iot_Verify.component.ai.LlmChatService;
import cn.edu.nju.Iot_Verify.component.ai.LlmMessageCodec;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolManager;
import cn.edu.nju.Iot_Verify.component.aitool.scenario.AiScenarioDraftStore;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.util.mapper.ChatMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatExecutionLeaseHeartbeatIntegrationTest {

    @Autowired
    private ChatSessionRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // This class runs Propagation.NOT_SUPPORTED so its saveAndFlush COMMITs chat_session rows, and
    // all @DataJpaTest classes share one cached H2 context. Clear committed rows after every test so
    // they cannot leak into sibling tests (order-dependent, CI-only).
    @AfterEach
    void clearCommittedSessions() {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> repository.deleteAllInBatch());
    }

    @Test
    void heartbeatDoesNotRenewWithStaleTimeAfterRowLockWaitExceedsTtl() throws Exception {
        ChatSessionRepository observedRepository = mock(
                ChatSessionRepository.class, delegatesTo(repository));
        ChatExecutionConfig config = new ChatExecutionConfig();
        config.setLeaseTtlMs(5_000);
        config.setLeaseHeartbeatMs(1_000);
        doReturn(0).when(observedRepository)
                .clearExpiredExecutionLeases(any(java.time.LocalDateTime.class));
        ChatServiceImpl service = service(observedRepository, config);
        ChatSessionPo session = new ChatSessionPo();
        session.setId("locked-session");
        session.setUserId(1L);
        session.setExecutionStopRequested(false);
        session.setExecutionUserStopRequested(false);
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> repository.saveAndFlush(session));
        String executionId = service.beginStreamRequest(
                1L, "locked-session", "turn-locked", "hello");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ChatSessionPo admitted = repository.findByIdAndUserIdForUpdate(
                    "locked-session", 1L).orElseThrow();
            admitted.setActiveExecutionExpiresAt(
                    repository.currentDatabaseTime().plusNanos(Duration.ofMillis(50).toNanos()));
            repository.saveAndFlush(admitted);
        });
        config.setLeaseTtlMs(50);
        config.setLeaseHeartbeatMs(10);

        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch heartbeatWaitingForLock = new CountDownLatch(1);
        CountDownLatch releaseRow = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    repository.findByIdAndUserIdForUpdate("locked-session", 1L).orElseThrow();
                    rowLocked.countDown();
                    await(releaseRow);
                }));

        try {
            assertTrue(rowLocked.await(2, TimeUnit.SECONDS));
            doAnswer(invocation -> {
                heartbeatWaitingForLock.countDown();
                return repository.findByIdInForUpdate(invocation.getArgument(0));
            }).when(observedRepository).findByIdInForUpdate(anySet());
            Future<?> heartbeat = executor.submit(service::maintainExecutionLeases);

            assertTrue(heartbeatWaitingForLock.await(2, TimeUnit.SECONDS));
            LockSupport.parkNanos(Duration.ofMillis(150).toNanos());
            releaseRow.countDown();
            heartbeat.get(2, TimeUnit.SECONDS);

            verify(observedRepository, times(1)).saveAndFlush(session);
            assertFalse(service.getSessionActivity(1L, "locked-session").isActive());
            ChatSessionPo expired = repository.findById("locked-session").orElseThrow();
            assertEquals(executionId, expired.getActiveExecutionId());
            assertTrue(expired.getActiveExecutionExpiresAt().isBefore(repository.currentDatabaseTime()));
        } finally {
            releaseRow.countDown();
            lockHolder.get(2, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    private ChatServiceImpl service(
            ChatSessionRepository observedRepository,
            ChatExecutionConfig config) {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(UserPo.builder().id(1L).build()));
        ObjectMapper objectMapper = new ObjectMapper();
        return new ChatServiceImpl(
                observedRepository,
                mock(ChatMessageRepository.class),
                userRepository,
                mock(LlmChatService.class),
                new LlmMessageCodec(objectMapper),
                mock(ChatConfirmationDetector.class),
                mock(AiToolManager.class),
                mock(AiDestructiveActionGuard.class),
                mock(AiScenarioDraftStore.class),
                mock(AiTaskContinuationStore.class),
                mock(cn.edu.nju.Iot_Verify.service.AiTaskMemoryService.class),
                objectMapper,
                new ChatToolProgressPresenter(objectMapper),
                mock(ChatMapper.class),
                new TransactionTemplate(transactionManager),
                config);
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
}
