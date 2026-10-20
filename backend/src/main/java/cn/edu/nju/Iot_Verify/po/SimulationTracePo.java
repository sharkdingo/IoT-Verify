package cn.edu.nju.Iot_Verify.po;

import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 模拟轨迹持久化实体
 */
@Entity
@Table(name = "simulation_trace", indexes = {
        @Index(name = "idx_sim_trace_user_id", columnList = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SimulationTracePo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32) default 'UNKNOWN'")
    @Builder.Default
    private RunInitiator initiator = RunInitiator.UNKNOWN;

    private int requestedSteps;

    private int steps;

    @Column(name = "states_json", columnDefinition = "JSON", nullable = false)
    private String statesJson;

    @Column(name = "state_count", nullable = false)
    private Integer stateCount;

    @Column(name = "logs_json", columnDefinition = "TEXT")
    private String logsJson;

    @Column(name = "generation_issues_json", columnDefinition = "TEXT")
    private String generationIssuesJson;

    @Column(name = "nusmv_output", columnDefinition = "TEXT")
    private String nusmvOutput;

    @Column(name = "request_json", columnDefinition = "JSON")
    private String requestJson;

    @Column(name = "template_snapshots_json", columnDefinition = "JSON")
    private String templateSnapshotsJson;

    @Column(name = "model_snapshot_json", columnDefinition = "TEXT")
    private String modelSnapshotJson;

    @Column(name = "model_semantics_json", columnDefinition = "TEXT")
    private String modelSemanticsJson;

    @Column(name = "is_attack")
    private Boolean isAttack;

    @Column(name = "attack_budget")
    private Integer attackBudget;

    @Column(name = "enable_privacy")
    private Boolean enablePrivacy;

    @Column(name = "modeled_device_attack_point_count")
    private Integer modeledDeviceAttackPointCount;

    @Column(name = "modeled_falsifiable_reading_device_count")
    private Integer modeledFalsifiableReadingDeviceCount;

    @Column(name = "modeled_automation_link_attack_point_count")
    private Integer modeledAutomationLinkAttackPointCount;

    /** The exact SMV model NuSMV executed for this trajectory. */
    @Column(name = "smv_model_content", columnDefinition = "TEXT")
    private String smvModelContent;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (initiator == null) {
            initiator = RunInitiator.UNKNOWN;
        }
    }
}
