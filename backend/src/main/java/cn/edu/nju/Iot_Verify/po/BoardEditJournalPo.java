package cn.edu.nju.Iot_Verify.po;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One reversible board edit, recorded in the same transaction as the edit itself.
 *
 * <p>Undo and redo move {@link #undone} rather than deleting the active branch, so a redo never
 * has to reconstruct an entry that was thrown away. A new edit discards its abandoned redo branch,
 * and the depth cap trims the oldest entries. Each retained entry stores the
 * payload needed to reach the <em>other</em> state. Rule/specification entries hold one record,
 * reorder entries hold the complete ordering, and device entries hold the affected devices,
 * cascaded records, their positions, and the complete Environment Pool.
 *
 * <p>Ordering is by {@link #sequence}, a per-user counter, so concurrent sessions of the same
 * account cannot interleave into an ambiguous order.
 */
@Entity
// The unique constraint is the fence behind BoardEditJournal's read-max-then-add-one allocation: a
// lost race must fail with a duplicate key, not write two entries sharing an ordinal. It is
// enforced by the current schema generated from this declaration.
@Table(name = "board_edit_journal", uniqueConstraints = {
        @UniqueConstraint(name = "uk_board_edit_journal_user_sequence",
                columnNames = {"user_id", "sequence"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BoardEditJournalPo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Monotonic per user. Defines undo/redo order independently of clock skew or row ids. */
    @Column(name = "sequence", nullable = false)
    private Long sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private BoardEditEntityType entityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 32)
    private BoardEditOperation operation;

    /** Business key: rule/specification id, or the first affected device id for a device batch. */
    @Column(name = "entity_key", nullable = false, length = 191)
    private String entityKey;

    /**
     * Canonical state before the edit. Single-record CREATE entries omit it; compound device
     * entries use the same complete snapshot shape on both sides.
     */
    // TEXT rather than JSON: the value is already-serialized JSON held as a String, and a
    // native JSON column re-encodes it as a JSON string literal on some drivers.
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    /**
     * Canonical state after the edit. Single-record DELETE entries omit it; compound device
     * entries use the same complete snapshot shape on both sides. Used for drift detection.
     */
    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    /**
     * A rule's execution order or specification's list order, so redo of a creation and undo of a
     * deletion restore its position and not just its content. Device entries keep every position
     * inside their payload.
     *
     * <p>Execution order is user-controlled model semantics (lower rules win per target mode when
     * guards overlap), and {@code RuleDto} deliberately carries no position — it is a client-facing
     * contract that distrusts client-supplied ordering. Null for entries other than rule or
     * specification create/delete.
     */
    @Column(name = "entity_order")
    private Integer entityOrder;

    /** False while this edit is in effect, true once undone and available to redo. */
    @Column(name = "undone", nullable = false)
    private boolean undone;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
