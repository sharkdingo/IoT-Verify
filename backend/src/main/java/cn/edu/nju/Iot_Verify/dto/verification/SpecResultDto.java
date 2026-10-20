package cn.edu.nju.Iot_Verify.dto.verification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result for one specification emitted to NuSMV.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecResultDto {
    /**
     * Stable SpecificationDto.id for the emitted specification.
     */
    private String specId;

    /** Template semantics captured from the submitted specification. */
    private String templateId;

    /** User-facing template label captured for result interpretation. */
    private String specificationLabel;

    /** Descriptive formula rebuilt from submitted structured conditions and device labels. */
    private String formulaPreview;

    /** Null when a stored result has no structured interpretation evidence. */
    private SpecificationSnapshotDto specificationSnapshot;

    /** Actual emitted property language: CTL or LTL. */
    private String formulaKind;

    /** Explicit per-spec conclusion; missing or unreliable parser output is INCONCLUSIVE. */
    private VerificationOutcome outcome;

    /**
     * NuSMV expression that was checked for this specification.
     */
    private String expression;
}
