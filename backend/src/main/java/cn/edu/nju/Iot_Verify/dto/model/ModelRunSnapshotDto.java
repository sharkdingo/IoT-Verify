package cn.edu.nju.Iot_Verify.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** User-facing scope of the immutable model input captured for one run. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ModelRunSnapshotDto {

    /** Time at which device instances and their referenced template manifests were resolved. */
    private LocalDateTime capturedAt;

    private int deviceCount;
    private int ruleCount;
    private int specificationCount;
    private int environmentVariableCount;
    private int deviceTemplateCount;

    /** Canonical SHA-256 of the captured board model; null means comparison evidence was not recorded. */
    private String modelFingerprint;

    /** True when model generation reused the captured manifests instead of reading mutable templates again. */
    private boolean templatesFrozen;

    /**
     * Per-value semantic provenance for every environment variable in this run.
     * Makes historical counterexamples self-explanatory without consulting the current Board.
     */
    private List<EnvironmentValueProvenanceDto> environmentProvenance;

    public static ModelRunSnapshotDto captured(LocalDateTime capturedAt,
                                               int deviceCount,
                                               int ruleCount,
                                               int specificationCount,
                                               int environmentVariableCount,
                                               int deviceTemplateCount) {
        return ModelRunSnapshotDto.builder()
                .capturedAt(capturedAt)
                .deviceCount(Math.max(0, deviceCount))
                .ruleCount(Math.max(0, ruleCount))
                .specificationCount(Math.max(0, specificationCount))
                .environmentVariableCount(Math.max(0, environmentVariableCount))
                .deviceTemplateCount(Math.max(0, deviceTemplateCount))
                .templatesFrozen(true)
                .build();
    }
}
