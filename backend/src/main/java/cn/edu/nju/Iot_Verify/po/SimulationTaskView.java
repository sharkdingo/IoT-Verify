package cn.edu.nju.Iot_Verify.po;

import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;

import java.time.LocalDateTime;

/** Simulation task fields shared by detail and inbox reads. */
public interface SimulationTaskView {
    Long getId();
    Long getUserId();
    RunInitiator getInitiator();
    SimulationTaskPo.TaskStatus getStatus();
    LocalDateTime getCreatedAt();
    LocalDateTime getStartedAt();
    LocalDateTime getCompletedAt();
    Long getProcessingTimeMs();
    Boolean getIsAttack();
    Integer getAttackBudget();
    Integer getModeledDeviceAttackPointCount();
    Integer getModeledFalsifiableReadingDeviceCount();
    Integer getModeledAutomationLinkAttackPointCount();
    Boolean getEnablePrivacy();
    String getModelSnapshotJson();
    String getModelSemanticsJson();
    Integer getRequestedSteps();
    Integer getSteps();
    Long getSimulationTraceId();
    String getCheckLogsJson();
    String getGenerationIssuesJson();
    String getErrorMessage();
    Integer getProgress();
    TaskProgressStage getProgressStage();
}
