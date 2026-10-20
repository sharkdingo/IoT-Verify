package cn.edu.nju.Iot_Verify.po;

import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzOutcome;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "fuzz_task", indexes = {
        @Index(name = "idx_fuzz_task_user_created", columnList = "user_id,created_at"),
        @Index(name = "idx_fuzz_task_user_status", columnList = "user_id,status"),
        @Index(name = "idx_fuzz_task_status", columnList = "status"),
        @Index(name = "idx_fuzz_task_active_lease", columnList = "status,lease_expires_at"),
        @Index(name = "idx_fuzz_task_user_status_created",
                columnList = "user_id,status,created_at,id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_fuzz_task_user_id", columnNames = {"user_id", "id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FuzzTaskPo implements TaskView {

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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "processing_time_ms")
    private Long processingTimeMs;

    private Integer progress;

    @Enumerated(EnumType.STRING)
    @Column(name = "progress_stage", length = 40)
    private TaskProgressStage progressStage;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "check_logs_json", columnDefinition = "TEXT")
    @JsonIgnore
    private String checkLogsJson;

    @Column(name = "target_spec_ids_json", nullable = false, columnDefinition = "TEXT")
    @JsonIgnore
    private String targetSpecIdsJson;

    @Column(name = "max_iterations", nullable = false)
    private Integer maxIterations;

    @Column(name = "path_length", nullable = false)
    private Integer pathLength;

    @Column(name = "population_size", nullable = false)
    private Integer populationSize;

    @Column(name = "requested_seed")
    private Long seed;

    @Enumerated(EnumType.STRING)
    @Column(name = "exploration_mode", nullable = false)
    private FuzzExplorationMode explorationMode;

    @Column(name = "model_input_snapshot_json", nullable = false, columnDefinition = "LONGTEXT")
    @JsonIgnore
    private String modelInputSnapshotJson;

    @Column(name = "model_snapshot_json", nullable = false, columnDefinition = "TEXT")
    @JsonIgnore
    private String modelSnapshotJson;

    @Enumerated(EnumType.STRING)
    private FuzzOutcome outcome;

    @Column(name = "effective_seed")
    private Long effectiveSeed;

    private Integer iterations;

    @Column(name = "generated_paths")
    private Long generatedPaths;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    @Column(name = "eligibility_json", columnDefinition = "LONGTEXT")
    @JsonIgnore
    private String eligibilityJson;

    @Column(name = "limitations_json", columnDefinition = "LONGTEXT")
    @JsonIgnore
    private String limitationsJson;

    @Column(name = "finding_count")
    @Builder.Default
    private Integer findingCount = 0;

    @Column(name = "worker_id", length = 64)
    @JsonIgnore
    private String workerId;

    @Column(name = "lease_expires_at")
    @JsonIgnore
    private LocalDateTime leaseExpiresAt;

    @Override
    public boolean isTerminalStatus() {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    @Override
    public boolean isCancelledStatus() {
        return status == TaskStatus.CANCELLED;
    }

    @Override
    public String getTaskStatusName() {
        return status == null ? "UNKNOWN" : status.name();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = TaskStatus.PENDING;
        if (initiator == null) initiator = RunInitiator.UNKNOWN;
        if (progress == null) progress = 0;
        if (findingCount == null) findingCount = 0;
        if (explorationMode == null) explorationMode = FuzzExplorationMode.BOARD_SNAPSHOT;
    }
}
