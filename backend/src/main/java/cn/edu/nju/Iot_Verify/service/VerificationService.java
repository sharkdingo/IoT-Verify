package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationResultDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import java.util.List;

/**
 * 验证服务接口
 *
 * 提供 NuSMV 验证的同步和异步方法
 *
 * 注意：Trace 会自动保存（当检测到违规时），无需传入 saveTrace 参数
 */
public interface VerificationService {

    /**
     * 同步验证（立即返回结果）
     */
    VerificationResultDto verify(Long userId, VerificationRequestDto request);

    /**
     * 校验请求、创建任务并提交异步验证。请求非法时不会创建任务；队列拒绝时会
     * 将已创建任务标记为失败并抛出 ServiceUnavailableException。
     *
     * @param userId 用户ID
     * @param request 验证请求
     * @return 任务ID
     */
    Long submitVerification(Long userId, VerificationRequestDto request);

    /**
     * 获取任务状态
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @return 任务状态DTO
     */
    VerificationTaskDto getTask(Long userId, Long taskId);

    /**
     * 获取用户的验证任务收件箱（轻量列表，不含 NuSMV 输出和逐条规约详情）。
     *
     * @param userId 用户ID
     * @param excludedTaskIds 需要从列表中排除的任务ID（通常是前端正在专门轮询的任务）
     * @return 按创建时间倒序排列的任务摘要
     */
    List<VerificationTaskSummaryDto> getTasks(Long userId, List<Long> excludedTaskIds);

    /** Remove a failed or cancelled task that produced no history result. */
    void deleteTask(Long userId, Long taskId);

    /** Completed verification results, independent of foreground/background execution mode. */
    List<VerificationRunSummaryDto> getRuns(Long userId);

    VerificationRunDto getRun(Long userId, Long runId);

    /**
     * The exact SMV model this run checked, or {@code null} if the run holds none.
     *
     * <p>Read straight off the run rather than through {@link #getRun}, whose DTO deliberately omits the
     * content. Returning {@code null} rather than throwing keeps the "no model stored" case a fact the
     * caller decides how to report — a run recorded before the model was persisted has none, and that
     * is not a failure.
     */
    String getRunSmvModel(Long userId, Long runId);

    /** Exact persisted-row impact for an AI deletion preview, without parsing run evidence. */
    RunDeletionImpactDto getRunDeletionImpact(Long userId, Long runId);

    List<TraceDto> getRunTraces(Long userId, Long runId);

    void deleteRun(Long userId, Long runId);

    /** Delete only when the exact persisted trace count still matches the confirmed preview. */
    long deleteRun(Long userId, Long runId, long expectedTraceCount);

    /**
     * 获取用户的所有 Trace
     *
     * @param userId 用户ID
     * @return Trace 列表
     */
    List<TraceDto> getUserTraces(Long userId);

    /**
     * 获取某个验证任务产生的反例 Trace（按 task 维度过滤，避免拿到旧任务/并发任务的反例）。
     *
     * @param userId 当前用户
     * @param taskId 验证任务 id
     * @return 该任务的 Trace 列表
     */
    List<TraceDto> getTracesByTask(Long userId, Long taskId);

    /**
     * 获取单个 Trace
     *
     * @param userId 用户ID
     * @param traceId Trace ID
     * @return Trace
     */
    TraceDto getTrace(Long userId, Long traceId);

    /**
     * 删除 Trace
     *
     * @param userId 用户ID
     * @param traceId Trace ID
     */
    void deleteTrace(Long userId, Long traceId);

    /**
     * 取消验证任务
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @return 本次请求是否改变任务，以及任务的实际状态
     */
    TaskCancellationResultDto cancelTask(Long userId, Long taskId);

    /**
     * 更新任务进度
     *
     * @param taskId 任务ID
     * @param progress 进度 (0-100)
     * @param stage stable, localizable execution phase
     */
    void updateTaskProgress(Long taskId, int progress, TaskProgressStage stage);

    /**
     * 获取任务进度
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @return 进度 (0-100)
     */
    int getTaskProgress(Long userId, Long taskId);

}
