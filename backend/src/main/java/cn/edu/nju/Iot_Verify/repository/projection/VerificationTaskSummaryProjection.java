package cn.edu.nju.Iot_Verify.repository.projection;

import cn.edu.nju.Iot_Verify.po.VerificationTaskView;

/** Closed task-inbox projection that excludes detail-only request, log, result, and solver output. */
public interface VerificationTaskSummaryProjection extends VerificationTaskView {
}
