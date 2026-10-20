package cn.edu.nju.Iot_Verify.repository.projection;

import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;

/** Scalar state for progress polling without loading model or diagnostic payloads. */
public interface SimulationTaskProgressProjection {
    Integer getProgress();
    SimulationTaskPo.TaskStatus getStatus();
}
