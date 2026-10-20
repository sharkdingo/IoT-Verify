package cn.edu.nju.Iot_Verify.po;

import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;

import java.time.LocalDateTime;
import java.util.List;

/** Result presentation fields shared by the entity and the bounded database read. */
public interface VerificationResultView {
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
    String getSpecResultsJson();
    String getCheckLogsJson();
    String getNusmvOutput();
    Boolean getHasSmvModel();
    String getErrorMessage();
    Integer getProgress();
    TaskProgressStage getProgressStage();

    default List<String> getCheckLogs() {
        return null;
    }
}
