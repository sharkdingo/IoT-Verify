package cn.edu.nju.Iot_Verify.dto.board;

/** Both identities are computed from one locked current-board snapshot. */
public record BoardModelFingerprintsDto(String verification, String simulation) {
}
