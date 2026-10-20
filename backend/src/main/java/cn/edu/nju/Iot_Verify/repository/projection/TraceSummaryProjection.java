package cn.edu.nju.Iot_Verify.repository.projection;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/** Constructor projection avoids a dynamic proxy lookup for every history-field access. */
@Getter
@AllArgsConstructor
public final class TraceSummaryProjection implements TraceTokenSourceProjection {
    private final Long id;
    private final Long verificationTaskId;
    private final String violatedSpecId;
    private final String violatedSpecJson;
    private final Integer stateCount;
    private final LocalDateTime createdAt;
    private final String deviceIdsJson;
    private final String tokenSourcesJson;
}
