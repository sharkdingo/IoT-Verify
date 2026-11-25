package cn.edu.nju.Iot_Verify.dto.board;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of a targeted collection mutation.
 *
 * <p>The affected item explains what changed, while {@code currentItems} lets clients
 * replace a stale local collection with the authoritative post-mutation snapshot.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectionMutationResultDto<T> {
    private String operation;
    private T affectedItem;
    private List<T> currentItems;
    private int currentCount;
    /**
     * Whether the account now has a reversible edit / a redoable one.
     *
     * Reported with the mutation so the client's undo affordance is driven by the server journal
     * rather than a local guess. Null for mutations that do not participate in undo.
     */
    private Boolean canUndo;
    private Boolean canRedo;
    private String editHistoryRevision;

    public static <T> CollectionMutationResultDto<T> of(String operation,
                                                         T affectedItem,
                                                         List<T> currentItems) {
        List<T> items = currentItems != null ? currentItems : List.of();
        // Undo availability is attached by the caller only where the mutation is reversible.
        return new CollectionMutationResultDto<>(
                operation, affectedItem, items, items.size(), null, null, null);
    }

    /** Attaches the post-mutation undo availability read from the edit journal. */
    public CollectionMutationResultDto<T> withUndoAvailability(boolean canUndo, boolean canRedo,
                                                                String editHistoryRevision) {
        this.canUndo = canUndo;
        this.canRedo = canRedo;
        this.editHistoryRevision = editHistoryRevision;
        return this;
    }
}
