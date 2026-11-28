package cn.edu.nju.Iot_Verify.service.impl;

import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

final class TaskLeaseMaintenance {

    private static final long SWEEP_INTERVAL_SECONDS = 10L;

    private final ScheduledExecutorService executor;

    TaskLeaseMaintenance(String threadName) {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    void start(Runnable maintain) {
        maintain.run();
        executor.scheduleWithFixedDelay(maintain, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    void stop() {
        executor.shutdownNow();
    }

    static void sweep(
            List<LocalLease> executions,
            Duration leaseDuration,
            Supplier<LocalDateTime> databaseClock,
            LongFunction<TaskLeaseRenewal.RenewalResult> renew,
            ToIntFunction<LocalDateTime> recoverExpired,
            Logger log,
            String taskKind) {
        try {
            databaseClock.get();
        } catch (RuntimeException failure) {
            for (LocalLease execution : executions) {
                stopIfUnconfirmed(execution, leaseDuration, log, taskKind);
            }
            log.warn("Could not read database time while maintaining {} task leases", taskKind, failure);
            return;
        }

        for (LocalLease execution : executions) {
            try {
                var renewal = renew.apply(execution.taskId());
                if (renewal.renewed()) {
                    execution.confirmation().confirmAt(renewal.confirmationStartedNanos());
                } else {
                    execution.stop().run();
                }
            } catch (RuntimeException failure) {
                if (!stopIfUnconfirmed(execution, leaseDuration, log, taskKind)) {
                    log.warn("Could not renew {} task lease {}; the next cycle will retry",
                            taskKind, execution.taskId(), failure);
                }
            }
        }

        try {
            int recovered = recoverExpired.applyAsInt(databaseClock.get());
            if (recovered > 0) {
                log.warn("Recovered {} expired {} task lease(s)", recovered, taskKind);
            }
        } catch (RuntimeException failure) {
            log.warn("Could not recover expired {} task leases; the next cycle will retry", taskKind, failure);
        }
    }

    private static boolean stopIfUnconfirmed(
            LocalLease execution, Duration leaseDuration, Logger log, String taskKind) {
        if (!execution.confirmation().isUnconfirmedFor(leaseDuration)) return false;
        execution.stop().run();
        log.warn("Stopped local {} task {} after its lease could not be confirmed for a full TTL",
                taskKind, execution.taskId());
        return true;
    }

    record LocalLease(long taskId, LeaseConfirmation confirmation, Runnable stop) {
    }
}
