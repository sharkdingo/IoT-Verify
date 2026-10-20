package cn.edu.nju.Iot_Verify.repository.projection;

import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;

/** Scalar state for progress polling without loading model or diagnostic payloads. */
public interface VerificationTaskProgressProjection {
    Integer getProgress();
    VerificationTaskPo.TaskStatus getStatus();
}
