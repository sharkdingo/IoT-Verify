package cn.edu.nju.Iot_Verify.dto.model;

/** Stable phase codes for request-scoped recommendation and automatic-fix work. */
public enum InteractiveOperationStage {
    QUEUED,
    RUNNING,
    PREPARING_CONTEXT,
    REQUESTING_MODEL,
    VALIDATING_RESULT,
    PREPARING_MODEL,
    SEARCHING_AND_VERIFYING,
    COMPARING_VERIFIED_OPTIONS,
    FINALIZING,
    CANCELLING
}
