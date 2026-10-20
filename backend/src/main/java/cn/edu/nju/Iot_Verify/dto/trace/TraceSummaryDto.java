package cn.edu.nju.Iot_Verify.dto.trace;

import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/** Lightweight counterexample evidence for run-history lists. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceSummaryDto {
    private Long id;
    private Long verificationTaskId;
    private String violatedSpecId;
    private SpecificationDto violatedSpec;
    private Map<String, ModelTokenSource> modelTokenSourcesByDeviceId;
    private Integer stateCount;
    private LocalDateTime createdAt;

    /*
     * No `hasSmvModel` here.
     *
     * It was added to gate a per-counterexample download button, and that button was removed in the same
     * change set once the model was recognised as run-level — leaving a field that was queried, mapped,
     * serialized, and read by nothing. The run summary beside it (`VerificationRunSummaryDto`) carries
     * the flag the history panel's single per-run download actually reads.
     */

    @Builder.Default
    private Boolean dataAvailable = true;
    private String unavailableReasonCode;
}
