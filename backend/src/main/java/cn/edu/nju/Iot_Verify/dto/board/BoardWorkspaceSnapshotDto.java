package cn.edu.nju.Iot_Verify.dto.board;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** Browser view of a saved Board and the edit history captured with it. */
public record BoardWorkspaceSnapshotDto(
        @JsonUnwrapped BoardSemanticSnapshotDto scene,
        BoardEditAvailabilityDto editHistory) {
}
