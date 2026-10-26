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

    /** User-authored requirement name captured for result interpretation. */
    private String specificationLabel;

    /** Descriptive formula rebuilt from submitted structured conditions and device labels. */
    private String formulaPreview;

    /** Required frozen interpretation evidence for the emitted requirement. */
    private SpecificationSnapshotDto specificationSnapshot;

    /** Actual emitted property language: CTL or LTL. */
    private String formulaKind;

    /** Explicit per-spec conclusion; missing or unreliable parser output is INCONCLUSIVE. */
    private VerificationOutcome outcome;

    private AntecedentReachability antecedentReachability;

    /**
     * NuSMV expression that was checked for this specification.
     */
    private String expression;
}
