package cn.edu.nju.Iot_Verify.repository.projection;

import cn.edu.nju.Iot_Verify.po.SimulationTaskView;

/** Closed task-inbox projection that excludes worker ownership and detail-only task payloads. */
public interface SimulationTaskSummaryProjection extends SimulationTaskView {
}
