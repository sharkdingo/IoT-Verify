package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.po.TaskView;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskLeaseRenewalTest {

    @Test
    void lockWaitThatCrossesExpiryCannotRenewOrConfirmLease() {
        LocalDateTime beforeLock = LocalDateTime.of(2026, 7, 23, 10, 0);
        LocalDateTime afterLock = beforeLock.plusSeconds(2);
        TestTask task = new TestTask("worker-a", "RUNNING", beforeLock.plusSeconds(1));
        AtomicReference<LocalDateTime> databaseTime = new AtomicReference<>(beforeLock);
        AtomicBoolean flushed = new AtomicBoolean();

        boolean renewed = TaskLeaseRenewal.renew(
                transactionTemplate(),
                () -> {
                    databaseTime.set(afterLock);
                    return Optional.of(task);
                },
                databaseTime::get,
                ignored -> flushed.set(true),
                "worker-a",
                Duration.ofMinutes(2));

        assertFalse(renewed);
        assertFalse(flushed.get());
        assertEquals(beforeLock.plusSeconds(1), task.getLeaseExpiresAt());
    }

    @Test
    void ownedActiveLeaseUsesDatabaseTimeSampledAfterLock() {
        LocalDateTime afterLock = LocalDateTime.of(2026, 7, 23, 10, 0, 5);
        TestTask task = new TestTask("worker-a", "PENDING", afterLock.plusSeconds(10));
        AtomicBoolean lockAcquired = new AtomicBoolean();
        AtomicBoolean flushed = new AtomicBoolean();

        boolean renewed = TaskLeaseRenewal.renew(
                transactionTemplate(),
                () -> {
                    lockAcquired.set(true);
                    return Optional.of(task);
                },
                () -> {
                    assertTrue(lockAcquired.get());
                    return afterLock;
                },
                ignored -> flushed.set(true),
                "worker-a",
                Duration.ofMinutes(2));

        assertTrue(renewed);
        assertTrue(flushed.get());
        assertEquals(afterLock.plusMinutes(2), task.getLeaseExpiresAt());
    }

    @Test
    void leaseUpdateConfirmationExcludesTimeSpentWaitingForTaskLock() {
        AtomicLong monotonicNanos = new AtomicLong(100L);
        LocalDateTime now = LocalDateTime.of(2026, 9, 30, 10, 0);
        TestTask task = new TestTask("worker-a", "PENDING", now.plusSeconds(1));

        TaskLeaseRenewal.LeaseUpdateResult update = TaskLeaseRenewal.updateWithConfirmation(
                transactionTemplate(),
                () -> {
                    monotonicNanos.addAndGet(Duration.ofMinutes(3).toNanos());
                    return Optional.of(task);
                },
                () -> now,
                (lockedTask, databaseTime) -> 1,
                monotonicNanos::get);

        assertEquals(1, update.updated());
        assertEquals(monotonicNanos.get(), update.confirmationStartedNanos());
    }

    @Test
    void missingTaskAndRejectedUpdateCannotConfirmOwnership() {
        AtomicBoolean clockRead = new AtomicBoolean();
        AtomicBoolean updated = new AtomicBoolean();
        TaskLeaseRenewal.LeaseUpdateResult missing = TaskLeaseRenewal.updateWithConfirmation(
                transactionTemplate(),
                Optional::<TestTask>empty,
                () -> {
                    clockRead.set(true);
                    return LocalDateTime.now();
                },
                (task, now) -> {
                    updated.set(true);
                    return 1;
                });

        assertEquals(0, missing.updated());
        assertEquals(Long.MIN_VALUE, missing.confirmationStartedNanos());
        assertFalse(clockRead.get());
        assertFalse(updated.get());

        LocalDateTime now = LocalDateTime.of(2026, 9, 30, 10, 0);
        TaskLeaseRenewal.LeaseUpdateResult rejected = TaskLeaseRenewal.updateWithConfirmation(
                transactionTemplate(),
                () -> Optional.of(new TestTask("worker-a", "PENDING", now.plusSeconds(1))),
                () -> now,
                (task, databaseTime) -> 0);
        assertEquals(0, rejected.updated());
        assertEquals(Long.MIN_VALUE, rejected.confirmationStartedNanos());
    }

    @Test
    void ownershipLossAndTerminalStatusCannotRenewLease() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 30, 10, 0);
        for (TestTask task : List.of(
                new TestTask("worker-b", "RUNNING", now.plusSeconds(1)),
                new TestTask("worker-a", "CANCELLED", now.plusSeconds(1)),
                new TestTask("worker-a", "FAILED", now.plusSeconds(1)),
                new TestTask("worker-a", "COMPLETED", now.plusSeconds(1)))) {
            AtomicBoolean flushed = new AtomicBoolean();
            TaskLeaseRenewal.RenewalResult renewal = TaskLeaseRenewal.renewWithConfirmation(
                    transactionTemplate(), () -> Optional.of(task), () -> now,
                    ignored -> flushed.set(true), "worker-a", Duration.ofMinutes(2));
            assertFalse(renewal.renewed());
            assertFalse(flushed.get());
            assertEquals(Long.MIN_VALUE, renewal.confirmationStartedNanos());
        }
    }

    @Test
    void commitResponseThatConsumesTheRenewedTtlCannotConfirmLease() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 10, 0, 5);
        Duration leaseDuration = Duration.ofSeconds(2);
        TestTask task = new TestTask("worker-a", "RUNNING", now.plusSeconds(1));
        AtomicLong monotonicNanos = new AtomicLong(100L);
        AtomicBoolean flushed = new AtomicBoolean();

        TaskLeaseRenewal.RenewalResult renewal = TaskLeaseRenewal.renewWithConfirmation(
                transactionTemplate(() -> monotonicNanos.addAndGet(leaseDuration.toNanos())),
                () -> Optional.of(task),
                () -> now,
                ignored -> flushed.set(true),
                "worker-a",
                leaseDuration,
                monotonicNanos::get);

        assertFalse(renewal.renewed());
        assertTrue(flushed.get());
        assertEquals(now.plus(leaseDuration), task.getLeaseExpiresAt());
    }

    @Test
    void successfulRenewalConfirmationRemainsAnchoredBeforeCommitResponse() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 10, 0, 5);
        Duration leaseDuration = Duration.ofSeconds(2);
        TestTask task = new TestTask("worker-a", "RUNNING", now.plusSeconds(1));
        AtomicLong monotonicNanos = new AtomicLong(100L);

        TaskLeaseRenewal.RenewalResult renewal = TaskLeaseRenewal.renewWithConfirmation(
                transactionTemplate(() -> monotonicNanos.addAndGet(Duration.ofSeconds(1).toNanos())),
                () -> Optional.of(task),
                () -> now,
                ignored -> { },
                "worker-a",
                leaseDuration,
                monotonicNanos::get);

        assertTrue(renewal.renewed());
        assertEquals(100L, renewal.confirmationStartedNanos());

        LeaseConfirmation confirmation = new LeaseConfirmation(0L);
        confirmation.confirmAt(renewal.confirmationStartedNanos());
        monotonicNanos.set(renewal.confirmationStartedNanos() + leaseDuration.toNanos());
        assertTrue(confirmation.isUnconfirmedFor(leaseDuration, monotonicNanos::get));
    }

    private TransactionTemplate transactionTemplate() {
        return transactionTemplate(() -> { });
    }

    private TransactionTemplate transactionTemplate(Runnable afterCommit) {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                afterCommit.run();
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    private static final class TestTask implements TaskView {
        private final String workerId;
        private final String status;
        private LocalDateTime leaseExpiresAt;

        private TestTask(String workerId, String status, LocalDateTime leaseExpiresAt) {
            this.workerId = workerId;
            this.status = status;
            this.leaseExpiresAt = leaseExpiresAt;
        }

        @Override public Long getId() { return 1L; }
        @Override public Integer getProgress() { return 0; }
        @Override public boolean isTerminalStatus() { return false; }
        @Override public boolean isCancelledStatus() { return false; }
        @Override public String getTaskStatusName() { return status; }
        @Override public String getCheckLogsJson() { return "[]"; }
        @Override public void setCheckLogsJson(String json) { }
        @Override public String getWorkerId() { return workerId; }
        @Override public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
        @Override public void setLeaseExpiresAt(LocalDateTime value) { leaseExpiresAt = value; }
    }
}
