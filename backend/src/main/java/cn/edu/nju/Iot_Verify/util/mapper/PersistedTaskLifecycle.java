package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/** Shared field-specific lifecycle rules for persisted task and run reads. */
final class PersistedTaskLifecycle {

    private PersistedTaskLifecycle() {
    }

    static void validateTimeOrder(String recordType, Long id, LocalDateTime createdAt, LocalDateTime startedAt,
                                  LocalDateTime completedAt, Long processingTimeMs) {
        if (startedAt != null && startedAt.isBefore(createdAt)) {
            fail(recordType, id, "startedAt", "task start time precedes creation time");
        }
        if (completedAt != null && (completedAt.isBefore(createdAt)
                || (startedAt != null && completedAt.isBefore(startedAt)))) {
            fail(recordType, id, "completedAt", "task completion time is out of order");
        }
        if (processingTimeMs != null && (startedAt == null || completedAt == null)) {
            fail(recordType, id, "processingTimeMs", "processing time requires start and completion times");
        }
    }

    static void validateStatus(String recordType, Long id, String status, LocalDateTime startedAt,
                               LocalDateTime completedAt, int progress, Supplier<String> errorMessage) {
        boolean terminal = "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        if ("PENDING".equals(status) && startedAt != null) {
            fail(recordType, id, "startedAt", "pending task cannot have a start time");
        }
        if (("RUNNING".equals(status) || "COMPLETED".equals(status)) && startedAt == null) {
            fail(recordType, id, "startedAt", "running or completed task start time is missing");
        }
        if (terminal) {
            if (completedAt == null) {
                fail(recordType, id, "completedAt", "terminal task completion time is missing");
            }
            // Cancellation and failure preserve the progress from the last heartbeat.
            if ("COMPLETED".equals(status) && progress != 100) {
                fail(recordType, id, "progress", "completed task progress must be 100");
            }
        } else if (completedAt != null) {
            fail(recordType, id, "completedAt", "active task cannot have a completion time");
        }
        if ("FAILED".equals(status)) {
            String message = errorMessage.get();
            if (message == null || message.isBlank()) {
                fail(recordType, id, "errorMessage", "failed task error message is missing");
            }
        }
    }

    private static void fail(String recordType, Long id, String field, String detail) {
        throw new PersistedDataIntegrityException(recordType, id, field, detail);
    }
}
