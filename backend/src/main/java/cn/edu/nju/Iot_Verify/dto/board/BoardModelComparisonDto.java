package cn.edu.nju.Iot_Verify.dto.board;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record BoardModelComparisonDto(
        @JsonUnwrapped BoardModelFingerprintsDto fingerprints,
        BoardSemanticSnapshotDto scene) {
}
