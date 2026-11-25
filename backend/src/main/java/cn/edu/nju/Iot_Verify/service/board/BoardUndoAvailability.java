package cn.edu.nju.Iot_Verify.service.board;

/**
 * Whether the account currently has an edit to undo and/or one to redo.
 *
 * <p>Returned with every board mutation and undo result so the client never has to guess at
 * availability from its own local history — the journal is the authority.
 */
public record BoardUndoAvailability(Long lastEditId, Long undoSequence, Long redoSequence) {
    public static final BoardUndoAvailability EMPTY = new BoardUndoAvailability(null, null, null);

    public boolean canUndo() {
        return undoSequence != null;
    }

    public boolean canRedo() {
        return redoSequence != null;
    }

    public String editHistoryRevision() {
        // The row id distinguishes histories whose sequence restarted after a clear or branch discard.
        return orZero(lastEditId) + ":" + orZero(undoSequence) + ":" + orZero(redoSequence);
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
