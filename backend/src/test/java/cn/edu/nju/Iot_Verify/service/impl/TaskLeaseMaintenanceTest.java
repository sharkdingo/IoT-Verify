package cn.edu.nju.Iot_Verify.service.impl;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskLeaseMaintenanceTest {

    @Test
    void transientRenewalFailureDoesNotStopARecentWorkerOrPreventRecovery() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 10, 0);
        List<String> events = new ArrayList<>();
        List<TaskLeaseMaintenance.LocalLease> executions = List.of(
                new TaskLeaseMaintenance.LocalLease(1L, new LeaseConfirmation(), () -> events.add("stop recent")),
                new TaskLeaseMaintenance.LocalLease(2L, new LeaseConfirmation(), () -> events.add("stop rejected")));

        TaskLeaseMaintenance.sweep(executions, Duration.ofMinutes(2), () -> now, taskId -> {
            if (taskId == 1L) throw new IllegalStateException("transient database failure");
            return new TaskLeaseRenewal.RenewalResult(false, Long.MIN_VALUE);
        }, recoveryTime -> {
            assertEquals(now, recoveryTime);
            events.add("recover expired");
            return 0;
        }, LoggerFactory.getLogger(TaskLeaseMaintenanceTest.class), "test");

        assertEquals(List.of("stop rejected", "recover expired"), events);
    }
}
