package cn.edu.nju.Iot_Verify.po;

import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;

import java.time.LocalDateTime;

/** Bounded verification task fields shared by detail and task-inbox reads. */
public interface VerificationTaskView {
    Long getId();
    Long getUserId();
    RunInitiator getInitiator();
    VerificationTaskPo.TaskStatus getStatus();
    LocalDateTime getCreatedAt();
    LocalDateTime getStartedAt();
    LocalDateTime getCompletedAt();
    Long getProcessingTimeMs();
    Boolean getIsAttack();
    Integer getAttackBudget();
    Boolean getEnablePrivacy();
    Integer getModeledDeviceAttackPointCount();
    Integer getModeledFalsifiableReadingDeviceCount();
    Integer getModeledAutomationLinkAttackPointCount();
    String getModelSnapshotJson();
    String getModelSemanticsJson();
    VerificationOutcome getOutcome();
    Integer getViolatedSpecCount();
    Integer getDisabledRuleCount();
    Integer getSkippedSpecCount();
    String getGenerationIssuesJson();
    String getErrorMessage();
    Integer getProgress();
    TaskProgressStage getProgressStage();
}
