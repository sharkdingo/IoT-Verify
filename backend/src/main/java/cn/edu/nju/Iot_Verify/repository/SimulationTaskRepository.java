package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTaskProgressProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SimulationTaskRepository extends JpaRepository<SimulationTaskPo, Long>, DatabaseClockRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SimulationTaskPo t WHERE t.id = :taskId")
    Optional<SimulationTaskPo> findByIdForUpdate(@Param("taskId") Long taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SimulationTaskPo t WHERE t.id = :taskId AND t.userId = :userId")
    Optional<SimulationTaskPo> findByIdAndUserIdForUpdate(
            @Param("taskId") Long taskId, @Param("userId") Long userId);

    long countByUserId(Long userId);

    long countByUserIdAndStatusIn(Long userId, List<SimulationTaskPo.TaskStatus> statuses);

    Optional<SimulationTaskPo> findByIdAndUserId(Long id, Long userId);

    Optional<SimulationTaskProgressProjection> findProgressByIdAndUserId(Long id, Long userId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SimulationTaskPo t WHERE t.id = :taskId AND t.userId = :userId "
         + "AND t.workerId = :workerId AND t.status = :pending")
    int deleteUndispatchedTask(@Param("taskId") Long taskId,
                               @Param("userId") Long userId,
                               @Param("workerId") String workerId,
                               @Param("pending") SimulationTaskPo.TaskStatus pending);

    List<SimulationTaskSummaryProjection> findSummaryByUserIdAndStatusNotOrderByCreatedAtDesc(
            Long userId, SimulationTaskPo.TaskStatus status);

    List<SimulationTaskSummaryProjection> findSummaryByUserIdAndStatusNotAndIdNotInOrderByCreatedAtDesc(
            Long userId, SimulationTaskPo.TaskStatus status, List<Long> excludedIds);

    void deleteByUserIdAndSimulationTraceId(Long userId, Long simulationTraceId);

    void deleteByUserId(Long userId);

    List<SimulationTaskPo> findByUserIdAndStatus(Long userId, SimulationTaskPo.TaskStatus status);

    /**
     * Atomically complete a simulation task only while it is RUNNING.
     * Terminal states are immutable: CANCELLED/COMPLETED/FAILED must not be overwritten.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    // `progressStage` is cleared: a terminal task has no stage in progress.
    //
    // Every value of `TaskProgressStage` names work being done — QUEUED, GENERATING_MODEL,
    // RUNNING_SIMULATION, PERSISTING_RESULT — and there is no terminal member, so a task that finished
    // kept whatever stage it was in when it stopped. Observed on a real run: `status: COMPLETED,
    // progress: 100, progressStage: PERSISTING_RESULT`, i.e. a finished task still claiming to be saving.
    //
    // The web client already hid this (`activeTaskProgressStage` returns null for terminal statuses), but
    // six AI tools publish the field raw, so an agent asked about a finished simulation was told it was
    // still persisting. Nulling it here makes the stored row agree with the contract the client already
    // assumes, rather than leaving each consumer to remember the exception — the same fix as `progress`
    // no longer being forced to 100 on a task that never finished its work.
    @Query("UPDATE SimulationTaskPo t SET t.status = :newStatus, t.completedAt = :completedAt, "
         + "t.progress = 100, t.progressStage = NULL, t.steps = :steps, "
         + "t.simulationTraceId = :simulationTraceId, "
         + "t.errorMessage = :errorMessage, t.checkLogsJson = :checkLogsJson, "
         + "t.generationIssuesJson = :generationIssuesJson, t.processingTimeMs = :processingTimeMs, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.id = :taskId AND t.status = :runningStatus "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int completeTaskIfRunning(@Param("taskId") Long taskId,
                              @Param("newStatus") SimulationTaskPo.TaskStatus newStatus,
                              @Param("completedAt") LocalDateTime completedAt,
                              @Param("steps") Integer steps,
                              @Param("simulationTraceId") Long simulationTraceId,
                              @Param("errorMessage") String errorMessage,
                              @Param("checkLogsJson") String checkLogsJson,
                              @Param("generationIssuesJson") String generationIssuesJson,
                              @Param("processingTimeMs") Long processingTimeMs,
                              @Param("runningStatus") SimulationTaskPo.TaskStatus runningStatus,
                              @Param("workerId") String workerId,
                              @Param("currentTime") LocalDateTime currentTime);

    /**
     * Atomically fail a simulation task only while it is still active.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    // Progress is preserved: the worker failed partway, so 100 would claim work it never finished.
    @Query("UPDATE SimulationTaskPo t SET t.status = :newStatus, t.progressStage = NULL, t.completedAt = :completedAt, "
         + "t.errorMessage = :errorMessage, "
         + "t.checkLogsJson = :checkLogsJson, t.processingTimeMs = :processingTimeMs, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.id = :taskId AND t.status IN (:activeStatuses) "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int failTaskIfActive(@Param("taskId") Long taskId,
                         @Param("newStatus") SimulationTaskPo.TaskStatus newStatus,
                         @Param("completedAt") LocalDateTime completedAt,
                         @Param("errorMessage") String errorMessage,
                         @Param("checkLogsJson") String checkLogsJson,
                         @Param("processingTimeMs") Long processingTimeMs,
                         @Param("activeStatuses") List<SimulationTaskPo.TaskStatus> activeStatuses,
                         @Param("workerId") String workerId,
                         @Param("currentTime") LocalDateTime currentTime);

    /**
     * Atomically transition a task from PENDING to RUNNING.
     * Closes the race window where a concurrent cancel could be overwritten by a plain save().
     * Returns 1 if updated, 0 if the task is no longer PENDING (e.g. already CANCELLED).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SimulationTaskPo t SET t.status = :running, "
         + "t.startedAt = :startedAt, t.progress = :progress, "
         + "t.checkLogsJson = :checkLogsJson, t.leaseExpiresAt = :leaseExpiresAt "
         + "WHERE t.id = :taskId AND t.status = :pendingStatus "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int startTaskIfStillPending(@Param("taskId") Long taskId,
                                @Param("running") SimulationTaskPo.TaskStatus running,
                                @Param("startedAt") LocalDateTime startedAt,
                                @Param("progress") int progress,
                                @Param("checkLogsJson") String checkLogsJson,
                                @Param("pendingStatus") SimulationTaskPo.TaskStatus pendingStatus,
                                @Param("workerId") String workerId,
                                @Param("currentTime") LocalDateTime currentTime,
                                @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SimulationTaskPo t SET t.workerId = NULL, t.leaseExpiresAt = :expiredAt "
         + "WHERE t.id = :taskId AND t.workerId = :workerId "
         + "AND t.status IN (:activeStatuses)")
    int releaseOwnedActiveLease(@Param("taskId") Long taskId,
                                @Param("workerId") String workerId,
                                @Param("expiredAt") LocalDateTime expiredAt,
                                @Param("activeStatuses") List<SimulationTaskPo.TaskStatus> activeStatuses);

    @Transactional
    @Modifying(clearAutomatically = true)
    // Progress is deliberately not overwritten — see FuzzTaskRepository.failExpiredActiveTasks: an
    // expired lease means the work was abandoned, so a forced 100 asserts completed work.
    @Query("UPDATE SimulationTaskPo t SET t.status = :failed, t.progressStage = NULL, t.completedAt = :completedAt, "
         + "t.errorMessage = :errorMessage, t.checkLogsJson = :checkLogsJson, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.status IN (:activeStatuses) "
         + "AND (t.leaseExpiresAt IS NULL OR t.leaseExpiresAt <= :expiredBefore)")
    int failExpiredActiveTasks(@Param("failed") SimulationTaskPo.TaskStatus failed,
                               @Param("completedAt") LocalDateTime completedAt,
                               @Param("errorMessage") String errorMessage,
                               @Param("checkLogsJson") String checkLogsJson,
                               @Param("activeStatuses") List<SimulationTaskPo.TaskStatus> activeStatuses,
                               @Param("expiredBefore") LocalDateTime expiredBefore);

    /**
     * Atomically cancel a task only if it is still PENDING or RUNNING.
     * Prevents overwriting a legitimately COMPLETED or FAILED status.
     * Returns 1 if updated, 0 if the task already finished.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    // Progress is preserved — a user-cancelled run stopped partway, so 100 would claim work never done.
    @Query("UPDATE SimulationTaskPo t SET t.status = :cancelledStatus, t.progressStage = NULL, "
         + "t.completedAt = :completedAt, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.id = :taskId AND t.status IN (:activeStatuses)")
    int cancelTaskIfStillActive(@Param("taskId") Long taskId,
                                @Param("cancelledStatus") SimulationTaskPo.TaskStatus cancelledStatus,
                                @Param("completedAt") LocalDateTime completedAt,
                                @Param("activeStatuses") List<SimulationTaskPo.TaskStatus> activeStatuses);

    /**
     * Atomically update progress only if the task is still active (PENDING or RUNNING).
     * Prevents overwriting progress on terminal-state tasks.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SimulationTaskPo t SET t.progress = :progress, t.progressStage = :stage "
         + "WHERE t.id = :taskId AND t.status IN ('PENDING', 'RUNNING') "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int updateProgressIfActive(@Param("taskId") Long taskId, @Param("progress") int progress,
                               @Param("stage") TaskProgressStage stage,
                               @Param("workerId") String workerId,
                               @Param("currentTime") LocalDateTime currentTime);

    /** Persist the assumptions under which this task will run without replacing task state. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SimulationTaskPo t SET t.isAttack = :isAttack, "
         + "t.attackBudget = :attackBudget, t.enablePrivacy = :enablePrivacy, "
         + "t.modeledDeviceAttackPointCount = :devicePointCount, "
         + "t.modeledFalsifiableReadingDeviceCount = :falsifiableReadingDeviceCount, "
         + "t.modeledAutomationLinkAttackPointCount = :linkPointCount, "
         + "t.modelSnapshotJson = :modelSnapshotJson, "
         + "t.modelSemanticsJson = :modelSemanticsJson "
         + "WHERE t.id = :taskId")
    int updateModelContext(@Param("taskId") Long taskId,
                           @Param("isAttack") boolean isAttack,
                           @Param("attackBudget") int attackBudget,
                           @Param("enablePrivacy") boolean enablePrivacy,
                           @Param("devicePointCount") int devicePointCount,
                           @Param("falsifiableReadingDeviceCount") int falsifiableReadingDeviceCount,
                           @Param("linkPointCount") int linkPointCount,
                           @Param("modelSnapshotJson") String modelSnapshotJson,
                           @Param("modelSemanticsJson") String modelSemanticsJson);
}
