package cn.edu.nju.Iot_Verify.po;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "rules", indexes = {
        @Index(name = "idx_rules_user_id", columnList = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RulePo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conditions_json", columnDefinition = "JSON")
    private String conditionsJson;

    @Column(name = "command_json", columnDefinition = "JSON")
    private String commandJson;

    @Column(name = "rule_string", columnDefinition = "TEXT")
    private String ruleString;

    /** Zero-based model execution order; lower rules win per target mode when guards overlap. */
    @Column(name = "execution_order")
    private Integer executionOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (executionOrder == null) {
            executionOrder = 0;
        }
        if (createdAt == null) {
            // Match timestamp(6) before this metadata is returned and recorded in the journal.
            createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        }
    }
}
