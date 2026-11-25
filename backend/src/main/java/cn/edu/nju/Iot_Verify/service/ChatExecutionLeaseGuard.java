package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Objects;

/** Fences AI-originated writes with the chat execution lease that authorized them. */
@Component
@RequiredArgsConstructor
public class ChatExecutionLeaseGuard {

    private final ChatSessionRepository sessionRepository;

    public void requireCurrentExecutionLease() {
        String executionId = UserContextHolder.getChatExecutionId();
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        Long userId = UserContextHolder.getUserId();
        String sessionId = UserContextHolder.getChatSessionId();
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            throw leaseLost();
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("A chat execution commit fence requires an active Spring transaction");
        }

        ChatSessionRepository.ExecutionLeaseView lease = sessionRepository
                .findExecutionLeaseByIdAndUserId(sessionId, userId)
                .orElseThrow(this::leaseLost);
        long checkedAtNanos = System.nanoTime();
        LocalDateTime checkedAt = currentDatabaseTime();
        requireOwnedLease(
                executionId,
                lease.getActiveExecutionId(),
                lease.getActiveExecutionExpiresAt(),
                lease.getExecutionStopRequested(),
                checkedAt);
        requireLeaseAtCommit(userId, sessionId, executionId, checkedAt, checkedAtNanos);
    }

    private void requireLeaseAtCommit(Long userId, String sessionId, String executionId,
                                      LocalDateTime checkedAt, long checkedAtNanos) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                ChatSessionPo lockedSession = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                        .orElseThrow(ChatExecutionLeaseGuard.this::leaseLost);
                LocalDateTime databaseNow = currentDatabaseTime();
                long elapsedNanos = Math.max(0L, System.nanoTime() - checkedAtNanos);
                LocalDateTime monotonicNow = checkedAt.plusNanos(elapsedNanos);
                LocalDateTime effectiveNow = databaseNow.isAfter(monotonicNow) ? databaseNow : monotonicNow;
                requireOwnedLease(
                        executionId,
                        lockedSession.getActiveExecutionId(),
                        lockedSession.getActiveExecutionExpiresAt(),
                        lockedSession.getExecutionStopRequested(),
                        effectiveNow);
            }
        });
    }

    /**
     * Acquires the chat row before a short write that may lock another row. In particular,
     * task cancellation follows the same chat-before-task order as account deletion.
     */
    public void requireCurrentExecutionLeaseAndLock() {
        String executionId = UserContextHolder.getChatExecutionId();
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        Long userId = UserContextHolder.getUserId();
        String sessionId = UserContextHolder.getChatSessionId();
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            throw leaseLost();
        }
        ChatSessionPo session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                .orElseThrow(this::leaseLost);
        long checkedAtNanos = System.nanoTime();
        LocalDateTime checkedAt = currentDatabaseTime();
        requireOwnedLease(
                executionId,
                session.getActiveExecutionId(),
                session.getActiveExecutionExpiresAt(),
                session.getExecutionStopRequested(),
                checkedAt);
        requireLeaseAtCommit(userId, sessionId, executionId, checkedAt, checkedAtNanos);
    }

    private void requireOwnedLease(String expectedExecutionId,
                                   String activeExecutionId,
                                   LocalDateTime activeExecutionExpiresAt,
                                   Boolean stopRequested,
                                   LocalDateTime now) {
        if (!Objects.equals(expectedExecutionId, activeExecutionId)
                || activeExecutionExpiresAt == null
                || !activeExecutionExpiresAt.isAfter(now)
                || Boolean.TRUE.equals(stopRequested)) {
            throw leaseLost();
        }
    }

    private LocalDateTime currentDatabaseTime() {
        return Objects.requireNonNull(
                sessionRepository.currentDatabaseTime(),
                "Database current timestamp must not be null");
    }

    private ConflictException leaseLost() {
        return new ConflictException(
                "The assistant execution no longer owns this session. No new mutation was committed.");
    }
}
