package cn.edu.nju.Iot_Verify.repository.projection;

import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;

/** Run-owned trace context, excluding model text and unrelated result diagnostics. */
public interface VerificationTraceContextProjection {
    Long getId();
    Long getUserId();
    VerificationTaskPo.TaskStatus getStatus();
    Boolean getIsAttack();
    Integer getAttackBudget();
    Boolean getEnablePrivacy();
    Integer getModeledDeviceAttackPointCount();
    Integer getModeledFalsifiableReadingDeviceCount();
    Integer getModeledAutomationLinkAttackPointCount();
    String getRequestJson();
    String getTemplateSnapshotsJson();
    String getModelSnapshotJson();
    String getModelSemanticsJson();
    Integer getDisabledRuleCount();
    Integer getSkippedSpecCount();
    String getGenerationIssuesJson();
}
