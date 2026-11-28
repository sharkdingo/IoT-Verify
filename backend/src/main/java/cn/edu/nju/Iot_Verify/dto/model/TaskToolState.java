package cn.edu.nju.Iot_Verify.dto.model;

import java.time.LocalDateTime;

/** The lifecycle and diagnostics fields shared by async task tools. */
public interface TaskToolState {
    String getStatus();

    Integer getProgress();

    TaskProgressStage getProgressStage();

    LocalDateTime getCreatedAt();

    LocalDateTime getStartedAt();

    LocalDateTime getCompletedAt();

    Long getProcessingTimeMs();

    String getErrorMessage();
}
