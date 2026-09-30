package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.po.TaskView;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Samples lease clocks only after acquiring the async task's database row lock. */
final class TaskLeaseRenewal {

    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "RUNNING");

    private TaskLeaseRenewal() {
    }

    static <T extends TaskView> boolean renew(
            TransactionTemplate transactionTemplate,
            Supplier<Optional<T>> lockTask,
            Supplier<LocalDateTime> databaseClock,
            Consumer<T> flushTask,
            String workerId,
            Duration leaseDuration) {
        return renewWithConfirmation(transactionTemplate, lockTask, databaseClock, flushTask,
                workerId, leaseDuration).renewed();
    }

    static <T extends TaskView> RenewalResult renewWithConfirmation(
            TransactionTemplate transactionTemplate,
            Supplier<Optional<T>> lockTask,
            Supplier<LocalDateTime> databaseClock,
            Consumer<T> flushTask,
            String workerId,
            Duration leaseDuration) {
        return renewWithConfirmation(transactionTemplate, lockTask, databaseClock, flushTask,
                workerId, leaseDuration, System::nanoTime);
    }

    static <T extends TaskView> RenewalResult renewWithConfirmation(
            TransactionTemplate transactionTemplate,
            Supplier<Optional<T>> lockTask,
            Supplier<LocalDateTime> databaseClock,
            Consumer<T> flushTask,
            String workerId,
            Duration leaseDuration,
            LongSupplier monotonicClock) {
        Objects.requireNonNull(leaseDuration, "Lease duration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Lease duration must be positive");
        }
        LeaseUpdateResult update = updateWithConfirmation(
                transactionTemplate, lockTask, databaseClock, (task, now) -> {
                    LocalDateTime currentExpiry = task.getLeaseExpiresAt();
                    if (!Objects.equals(workerId, task.getWorkerId())
                            || !ACTIVE_STATUSES.contains(task.getTaskStatusName())
                            || currentExpiry == null
                            || !currentExpiry.isAfter(now)) {
                        return 0;
                    }

                    task.setLeaseExpiresAt(now.plus(leaseDuration));
                    flushTask.accept(task);
                    return 1;
                }, monotonicClock);
        RenewalResult renewal = new RenewalResult(update.updated() > 0, update.confirmationStartedNanos());
        if (!renewal.renewed()) return renewal;
        long elapsedNanos = monotonicClock.getAsLong() - renewal.confirmationStartedNanos();
        return elapsedNanos >= 0 && elapsedNanos < leaseDuration.toNanos()
                ? renewal
                : new RenewalResult(false, renewal.confirmationStartedNanos());
    }

    static <T extends TaskView> LeaseUpdateResult updateWithConfirmation(
            TransactionTemplate transactionTemplate,
            Supplier<Optional<T>> lockTask,
            Supplier<LocalDateTime> databaseClock,
            BiFunction<T, LocalDateTime, Integer> updateTask) {
        return updateWithConfirmation(transactionTemplate, lockTask, databaseClock, updateTask,
                System::nanoTime);
    }

    /** The callback retains the domain's atomic owner/status/expiry predicate while the row stays locked. */
    static <T extends TaskView> LeaseUpdateResult updateWithConfirmation(
            TransactionTemplate transactionTemplate,
            Supplier<Optional<T>> lockTask,
            Supplier<LocalDateTime> databaseClock,
            BiFunction<T, LocalDateTime, Integer> updateTask,
            LongSupplier monotonicClock) {
        LeaseUpdateResult result = transactionTemplate.execute(status -> {
            T task = lockTask.get().orElse(null);
            if (task == null) return LeaseUpdateResult.notUpdated();
            long confirmationStartedNanos = monotonicClock.getAsLong();
            LocalDateTime now = Objects.requireNonNull(
                    databaseClock.get(), "Database current timestamp must not be null");
            int updated = updateTask.apply(task, now);
            return updated > 0
                    ? new LeaseUpdateResult(updated, confirmationStartedNanos)
                    : LeaseUpdateResult.notUpdated();
        });
        return result != null ? result : LeaseUpdateResult.notUpdated();
    }

    static long monotonicNow() {
        return System.nanoTime();
    }

    static boolean completedBeforeTtl(long startedNanos, Duration leaseDuration) {
        if (startedNanos == Long.MIN_VALUE || leaseDuration == null
                || leaseDuration.isZero() || leaseDuration.isNegative()) {
            return false;
        }
        long elapsedNanos = monotonicNow() - startedNanos;
        return elapsedNanos >= 0 && elapsedNanos < leaseDuration.toNanos();
    }

    record RenewalResult(boolean renewed, long confirmationStartedNanos) {
    }

    record LeaseUpdateResult(int updated, long confirmationStartedNanos) {
        private static LeaseUpdateResult notUpdated() {
            return new LeaseUpdateResult(0, Long.MIN_VALUE);
        }
    }
}
