package cn.edu.nju.Iot_Verify.po;

import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "simulation_task", indexes = {
        @Index(name = "idx_simulation_task_user_id", columnList = "user_id"),
        @Index(name = "idx_simulation_task_active_lease", columnList = "status,lease_expires_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SimulationTaskPo implements TaskView, SimulationTaskView {

    public enum TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32) default 'UNKNOWN'")
    @Builder.Default
    private RunInitiator initiator = RunInitiator.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private Long processingTimeMs;

    @Column(name = "is_attack", nullable = false)
    @Builder.Default
    private Boolean isAttack = false;

    @Column(name = "attack_budget", nullable = false)
    @Builder.Default
    private Integer attackBudget = 0;

    @Column(name = "modeled_device_attack_points", nullable = false)
    @Builder.Default
    private Integer modeledDeviceAttackPointCount = 0;

    @Column(name = "modeled_falsifiable_reading_devices", nullable = false)
    @Builder.Default
    private Integer modeledFalsifiableReadingDeviceCount = 0;

    @Column(name = "modeled_automation_link_attack_points", nullable = false)
    @Builder.Default
    private Integer modeledAutomationLinkAttackPointCount = 0;

    @Column(name = "enable_privacy", nullable = false)
    @Builder.Default
    private Boolean enablePrivacy = false;

    @Column(name = "model_snapshot_json", columnDefinition = "TEXT")
    @JsonIgnore
    private String modelSnapshotJson;

    @Column(name = "model_semantics_json", columnDefinition = "TEXT")
    @JsonIgnore
    private String modelSemanticsJson;

    private Integer requestedSteps;

    private Integer steps;

    private Long simulationTraceId;

    @Column(columnDefinition = "TEXT")
    @JsonIgnore
    private String checkLogsJson;

    @Column(columnDefinition = "TEXT")
    @JsonIgnore
    private String generationIssuesJson;

    @Transient
    private List<String> checkLogs;

    @Transient
    private List<ModelGenerationIssueDto> generationIssues;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** 0-100 progress percentage, persisted for multi-instance visibility */
    private Integer progress;

    @Enumerated(EnumType.STRING)
    @Column(name = "progress_stage", length = 40)
    private TaskProgressStage progressStage;

    @Column(name = "worker_id", length = 36)
    @JsonIgnore
    private String workerId;

    @Column(name = "lease_expires_at")
    @JsonIgnore
    private LocalDateTime leaseExpiresAt;

    @Override
    public String getTaskStatusName() {
        return status == null ? "UNKNOWN" : status.name();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = TaskStatus.PENDING;
        if (initiator == null) initiator = RunInitiator.UNKNOWN;
        if (isAttack == null) isAttack = false;
        if (attackBudget == null) attackBudget = 0;
        if (modeledDeviceAttackPointCount == null) modeledDeviceAttackPointCount = 0;
        if (modeledFalsifiableReadingDeviceCount == null) modeledFalsifiableReadingDeviceCount = 0;
        if (modeledAutomationLinkAttackPointCount == null) modeledAutomationLinkAttackPointCount = 0;
        if (enablePrivacy == null) enablePrivacy = false;
    }
}
