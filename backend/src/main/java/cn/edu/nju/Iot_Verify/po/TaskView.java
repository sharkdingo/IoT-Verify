package cn.edu.nju.Iot_Verify.po;

import java.time.LocalDateTime;

/**
 * 任务 PO 的公共视图接口，为 {@link cn.edu.nju.Iot_Verify.service.impl.AbstractAsyncTaskService}
 * 的泛型参数提供类型约束。
 *
 * <p>{@link VerificationTaskPo}, {@link SimulationTaskPo}, and {@link FuzzTaskPo}
 * implement this interface.
 */
public interface TaskView {

    Long getId();

    Integer getProgress();

    /** Stable public task status name (PENDING / RUNNING / terminal states). */
    String getTaskStatusName();

    String getCheckLogsJson();

    String getWorkerId();

    LocalDateTime getLeaseExpiresAt();

    void setLeaseExpiresAt(LocalDateTime leaseExpiresAt);
}
