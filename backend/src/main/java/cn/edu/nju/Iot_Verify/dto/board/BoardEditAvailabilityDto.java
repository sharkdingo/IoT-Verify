package cn.edu.nju.Iot_Verify.dto.board;

/** Undo and redo availability captured with the Board state that the user can review. */
public record BoardEditAvailabilityDto(boolean canUndo, boolean canRedo, String editHistoryRevision) {
}
