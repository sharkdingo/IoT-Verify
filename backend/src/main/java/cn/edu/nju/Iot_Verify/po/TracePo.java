package cn.edu.nju.Iot_Verify.po;

import cn.edu.nju.Iot_Verify.repository.projection.TraceSummaryProjection;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@SqlResultSetMapping(name = "TraceSummary", classes = @ConstructorResult(
        targetClass = TraceSummaryProjection.class, columns = {
        @ColumnResult(name = "id", type = Long.class),
        @ColumnResult(name = "verificationTaskId", type = Long.class),
        @ColumnResult(name = "violatedSpecId", type = String.class),
        @ColumnResult(name = "violatedSpecJson", type = String.class),
        @ColumnResult(name = "stateCount", type = Integer.class),
        @ColumnResult(name = "createdAt", type = LocalDateTime.class),
        @ColumnResult(name = "deviceIdsJson", type = String.class),
        @ColumnResult(name = "tokenSourcesJson", type = String.class)
}))
@Table(name = "trace", indexes = {
        @Index(name = "idx_trace_user_id", columnList = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TracePo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(name = "verification_task_id", nullable = false)
    private Long verificationTaskId;

    @Column(name = "violated_spec_id", nullable = false, length = 100)
    private String violatedSpecId;

    @Column(name = "violated_spec_json", columnDefinition = "JSON")
    private String violatedSpecJson;

    @Column(name = "checked_expression", columnDefinition = "TEXT")
    private String checkedExpression;

    @Column(name = "states_json", columnDefinition = "JSON", nullable = false)
    private String statesJson;

    @Column(name = "state_count", nullable = false)
    private Integer stateCount;

    // Run context and model text belong to verification_task; deleting a run deletes its traces.

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
