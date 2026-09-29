package cn.edu.nju.Iot_Verify.dto.fix;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A specification the suggested rules still violate and the original rules already violated. It
 * belongs to a different counterexample, so this suggestion neither caused nor repairs it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreexistingViolationDto {
    private String specId;
    /** Specification template id; the client localizes the template name from it. */
    private String templateId;
    /** Readable formula over device labels, as in verification results. */
    private String formulaPreview;
}
