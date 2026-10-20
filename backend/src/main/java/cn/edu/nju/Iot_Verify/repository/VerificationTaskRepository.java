package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationResultProjection;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.repository.projection.CompletedRunDeletionProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTaskProgressProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationRunSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTraceContextProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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

/**
 * 验证任务仓储接口
 */
@Repository
public interface VerificationTaskRepository extends JpaRepository<VerificationTaskPo, Long>, DatabaseClockRepository {

    // Java 17 whitespace; both casts use the connection collation even for historical TEXT columns.
    // Connector/J may bind the regex as binary under NO_BACKSLASH_ESCAPES; MySQL 8 regexp rejects that charset.
    String SMV_NON_BLANK_CHARACTERS = "[^\\x{0009}-\\x{000D}\\x{001C}-\\x{0020}\\x{1680}"
            + "\\x{2000}-\\x{2006}\\x{2008}-\\x{200A}\\x{2028}\\x{2029}\\x{205F}\\x{3000}]";

    String RESULT_VIEW_SELECT = "SELECT t.id AS id, t.userId AS userId, t.initiator AS initiator, "
            + "t.status AS status, t.createdAt AS createdAt, t.startedAt AS startedAt, "
            + "t.completedAt AS completedAt, t.processingTimeMs AS processingTimeMs, "
            + "t.isAttack AS isAttack, t.attackBudget AS attackBudget, t.enablePrivacy AS enablePrivacy, "
            + "t.modeledDeviceAttackPointCount AS modeledDeviceAttackPointCount, "
            + "t.modeledFalsifiableReadingDeviceCount AS modeledFalsifiableReadingDeviceCount, "
            + "t.modeledAutomationLinkAttackPointCount AS modeledAutomationLinkAttackPointCount, "
            + "t.modelSnapshotJson AS modelSnapshotJson, t.modelSemanticsJson AS modelSemanticsJson, "
            + "t.outcome AS outcome, t.violatedSpecCount AS violatedSpecCount, "
            + "t.disabledRuleCount AS disabledRuleCount, t.skippedSpecCount AS skippedSpecCount, "
            + "t.generationIssuesJson AS generationIssuesJson, t.specResultsJson AS specResultsJson, "
            + "t.checkLogsJson AS checkLogsJson, t.nusmvOutput AS nusmvOutput, "
            + "CASE WHEN t.smvModelContent IS NOT NULL "
            + "AND FUNCTION('REGEXP_LIKE', CAST(t.smvModelContent AS string), CAST(:nonBlankCharacters AS string)) = true "
            + "THEN true ELSE false END AS hasSmvModel, "
            + "t.errorMessage AS errorMessage, t.progress AS progress, t.progressStage AS progressStage "
            + "FROM VerificationTaskPo t ";

    @Query(RESULT_VIEW_SELECT + "WHERE t.id = :taskId AND t.userId = :userId")
    Optional<VerificationResultProjection> findResultViewByIdAndUserId(
            @Param("taskId") Long taskId, @Param("userId") Long userId,
            @Param("nonBlankCharacters") String nonBlankCharacters);

    @Query(RESULT_VIEW_SELECT + "WHERE t.id = :runId AND t.userId = :userId AND t.status = :status")
    Optional<VerificationResultProjection> findCompletedResultView(
            @Param("runId") Long runId, @Param("userId") Long userId,
            @Param("status") VerificationTaskPo.TaskStatus status,
            @Param("nonBlankCharacters") String nonBlankCharacters);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM VerificationTaskPo t WHERE t.id = :taskId")
    Optional<VerificationTaskPo> findByIdForUpdate(@Param("taskId") Long taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM VerificationTaskPo t WHERE t.id = :taskId AND t.userId = :userId")
    Optional<VerificationTaskPo> findByIdAndUserIdForUpdate(
            @Param("taskId") Long taskId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM VerificationTaskPo t WHERE t.id = :runId "
         + "AND t.userId = :userId AND t.status = :status")
    Optional<VerificationTaskPo> findCompletedRunForUpdate(
            @Param("runId") Long runId,
            @Param("userId") Long userId,
            @Param("status") VerificationTaskPo.TaskStatus status);

    @Query("SELECT t.id AS id, t.createdAt AS createdAt, t.completedAt AS completedAt "
         + "FROM VerificationTaskPo t WHERE t.id = :runId "
         + "AND t.userId = :userId AND t.status = :status")
    Optional<CompletedRunDeletionProjection> findDeletionProjection(
            @Param("runId") Long runId,
            @Param("userId") Long userId,
            @Param("status") VerificationTaskPo.TaskStatus status);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM VerificationTaskPo t WHERE t.id = :runId "
         + "AND t.userId = :userId AND t.status = :status")
    int deleteCompletedRun(
            @Param("runId") Long runId,
            @Param("userId") Long userId,
            @Param("status") VerificationTaskPo.TaskStatus status);

    List<VerificationTraceContextProjection> findTraceContextsByUserIdAndIdInAndStatus(
            Long userId, List<Long> ids, VerificationTaskPo.TaskStatus status);

    long countByUserId(Long userId);

    Optional<VerificationTaskProgressProjection> findProgressByIdAndUserId(Long id, Long userId);

    long countByUserIdAndStatusIn(Long userId, List<VerificationTaskPo.TaskStatus> statuses);

    List<VerificationTaskSummaryProjection> findSummaryByUserIdAndStatusNotOrderByCreatedAtDesc(
            Long userId, VerificationTaskPo.TaskStatus status);

    List<VerificationTaskSummaryProjection> findSummaryByUserIdAndStatusNotAndIdNotInOrderByCreatedAtDesc(
            Long userId, VerificationTaskPo.TaskStatus status, List<Long> excludedIds);

    /**
     * Completed runs for the history list.
     *
     * <p>Spelled as an explicit query rather than a derived one because {@code hasSmvModel} is not an
     * entity property: it is {@code smvModelContent} tested for content, computed in SQL so a history
     * page never loads tens of thousands of characters per row to decide whether one download button
     * can succeed. Every other alias here matches the projection getter it feeds.
     */
    @Query("SELECT t.id AS id, t.initiator AS initiator, t.status AS status, "
         + "t.createdAt AS createdAt, t.startedAt AS startedAt, t.completedAt AS completedAt, "
         + "t.processingTimeMs AS processingTimeMs, t.isAttack AS isAttack, "
         + "t.attackBudget AS attackBudget, "
         + "t.modeledDeviceAttackPointCount AS modeledDeviceAttackPointCount, "
         + "t.modeledFalsifiableReadingDeviceCount AS modeledFalsifiableReadingDeviceCount, "
         + "t.modeledAutomationLinkAttackPointCount AS modeledAutomationLinkAttackPointCount, "
         + "t.enablePrivacy AS enablePrivacy, t.modelSnapshotJson AS modelSnapshotJson, "
         + "t.modelSemanticsJson AS modelSemanticsJson, t.outcome AS outcome, "
         + "t.violatedSpecCount AS violatedSpecCount, t.disabledRuleCount AS disabledRuleCount, "
         + "t.skippedSpecCount AS skippedSpecCount, "
         + "t.generationIssuesJson AS generationIssuesJson, "
         // The correlated scalar keeps SMV text out of the outer filesort row, including pagination.
         + "(SELECT CASE WHEN stored.smvModelContent IS NOT NULL AND stored.smvModelContent <> '' "
         + "THEN TRUE ELSE FALSE END FROM VerificationTaskPo stored "
         + "WHERE stored.id = t.id AND stored.userId = t.userId) AS hasSmvModel "
         + "FROM VerificationTaskPo t WHERE t.userId = :userId AND t.status = :status "
         + "ORDER BY t.completedAt DESC, t.id DESC")
    List<VerificationRunSummaryProjection> findCompletedRunSummaries(
            @Param("userId") Long userId,
            @Param("status") VerificationTaskPo.TaskStatus status,
            Pageable pageable);

    /**
     * 根据ID和用户ID查询任务
     */
    Optional<VerificationTaskPo> findByIdAndUserId(Long id, Long userId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM VerificationTaskPo t WHERE t.id = :taskId AND t.userId = :userId "
         + "AND t.workerId = :workerId AND t.status = :pending")
    int deleteUndispatchedTask(@Param("taskId") Long taskId,
                               @Param("userId") Long userId,
                               @Param("workerId") String workerId,
                               @Param("pending") VerificationTaskPo.TaskStatus pending);

    /**
     * 根据用户ID和状态查询任务
     */
    List<VerificationTaskPo> findByUserIdAndStatus(Long userId, VerificationTaskPo.TaskStatus status);

    /**
     * 删除用户的所有任务
     */
    void deleteByUserId(Long userId);

    /**
     * Atomically complete a task only while it is RUNNING.
     * Terminal states are immutable: CANCELLED/COMPLETED/FAILED must not be overwritten.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VerificationTaskPo t SET t.status = :newStatus, t.progressStage = NULL, t.completedAt = :completedAt, "
         + "t.progress = 100, t.outcome = :outcome, t.violatedSpecCount = :violatedSpecCount, "
         + "t.disabledRuleCount = :disabledRuleCount, t.skippedSpecCount = :skippedSpecCount, "
         + "t.specResultsJson = :specResultsJson, t.checkLogsJson = :checkLogsJson, "
         + "t.generationIssuesJson = :generationIssuesJson, t.nusmvOutput = :nusmvOutput, "
         + "t.smvModelContent = :smvModelContent, "
         + "t.errorMessage = :errorMessage, t.processingTimeMs = :processingTimeMs, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.id = :taskId AND t.status = :runningStatus "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int completeTaskIfRunning(@Param("taskId") Long taskId,
                              @Param("newStatus") VerificationTaskPo.TaskStatus newStatus,
                              @Param("completedAt") LocalDateTime completedAt,
                              @Param("outcome") VerificationOutcome outcome,
                              @Param("violatedSpecCount") Integer violatedSpecCount,
                              @Param("disabledRuleCount") Integer disabledRuleCount,
                              @Param("skippedSpecCount") Integer skippedSpecCount,
                              @Param("specResultsJson") String specResultsJson,
                              @Param("checkLogsJson") String checkLogsJson,
                              @Param("generationIssuesJson") String generationIssuesJson,
                              @Param("nusmvOutput") String nusmvOutput,
                              @Param("smvModelContent") String smvModelContent,
                              @Param("errorMessage") String errorMessage,
                              @Param("processingTimeMs") Long processingTimeMs,
                              @Param("runningStatus") VerificationTaskPo.TaskStatus runningStatus,
                              @Param("workerId") String workerId,
                              @Param("currentTime") LocalDateTime currentTime);

    /**
     * Atomically fail a task only while it is still active.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    // Progress is preserved: the worker failed partway, so 100 would claim work it never finished.
    @Query("UPDATE VerificationTaskPo t SET t.status = :newStatus, t.progressStage = NULL, t.completedAt = :completedAt, "
         + "t.outcome = :outcome, t.errorMessage = :errorMessage, "
         + "t.checkLogsJson = :checkLogsJson, t.processingTimeMs = :processingTimeMs, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.id = :taskId AND t.status IN (:activeStatuses) "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int failTaskIfActive(@Param("taskId") Long taskId,
                         @Param("newStatus") VerificationTaskPo.TaskStatus newStatus,
                         @Param("completedAt") LocalDateTime completedAt,
                         @Param("outcome") VerificationOutcome outcome,
                         @Param("errorMessage") String errorMessage,
                         @Param("checkLogsJson") String checkLogsJson,
                         @Param("processingTimeMs") Long processingTimeMs,
                         @Param("activeStatuses") List<VerificationTaskPo.TaskStatus> activeStatuses,
                         @Param("workerId") String workerId,
                         @Param("currentTime") LocalDateTime currentTime);

    /**
     * Atomically transition a task from PENDING to RUNNING.
     * Closes the race window where a concurrent cancel could be overwritten by a plain save().
     * Returns 1 if updated, 0 if the task is no longer PENDING (e.g. already CANCELLED).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VerificationTaskPo t SET t.status = :running, "
         + "t.startedAt = :startedAt, t.progress = :progress, "
         + "t.checkLogsJson = :checkLogsJson, t.leaseExpiresAt = :leaseExpiresAt "
         + "WHERE t.id = :taskId AND t.status = :pendingStatus "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int startTaskIfStillPending(@Param("taskId") Long taskId,
                                @Param("running") VerificationTaskPo.TaskStatus running,
                                @Param("startedAt") LocalDateTime startedAt,
                                @Param("progress") int progress,
                                @Param("checkLogsJson") String checkLogsJson,
                                @Param("pendingStatus") VerificationTaskPo.TaskStatus pendingStatus,
                                @Param("workerId") String workerId,
                                @Param("currentTime") LocalDateTime currentTime,
                                @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VerificationTaskPo t SET t.workerId = NULL, t.leaseExpiresAt = :expiredAt "
         + "WHERE t.id = :taskId AND t.workerId = :workerId "
         + "AND t.status IN (:activeStatuses)")
    int releaseOwnedActiveLease(@Param("taskId") Long taskId,
                                @Param("workerId") String workerId,
                                @Param("expiredAt") LocalDateTime expiredAt,
                                @Param("activeStatuses") List<VerificationTaskPo.TaskStatus> activeStatuses);

    @Transactional
    @Modifying(clearAutomatically = true)
    // Progress is deliberately not overwritten — see FuzzTaskRepository.failExpiredActiveTasks: an
    // expired lease means the work was abandoned, so a forced 100 asserts completed work.
    @Query("UPDATE VerificationTaskPo t SET t.status = :failed, t.progressStage = NULL, t.completedAt = :completedAt, "
         + "t.outcome = :outcome, t.errorMessage = :errorMessage, "
         + "t.checkLogsJson = :checkLogsJson, t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.status IN (:activeStatuses) "
         + "AND (t.leaseExpiresAt IS NULL OR t.leaseExpiresAt <= :expiredBefore)")
    int failExpiredActiveTasks(@Param("failed") VerificationTaskPo.TaskStatus failed,
                               @Param("completedAt") LocalDateTime completedAt,
                               @Param("outcome") VerificationOutcome outcome,
                               @Param("errorMessage") String errorMessage,
                               @Param("checkLogsJson") String checkLogsJson,
                               @Param("activeStatuses") List<VerificationTaskPo.TaskStatus> activeStatuses,
                               @Param("expiredBefore") LocalDateTime expiredBefore);

    /**
     * Atomically cancel a task only if it is still PENDING or RUNNING.
     * Prevents overwriting a legitimately COMPLETED or FAILED status.
     * Returns 1 if updated, 0 if the task already finished.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    // Progress is preserved — a user-cancelled run stopped partway, so 100 would claim work never done.
    @Query("UPDATE VerificationTaskPo t SET t.status = :cancelledStatus, t.progressStage = NULL, "
         + "t.completedAt = :completedAt, t.outcome = :outcome, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.id = :taskId AND t.status IN (:activeStatuses)")
    int cancelTaskIfStillActive(@Param("taskId") Long taskId,
                                @Param("cancelledStatus") VerificationTaskPo.TaskStatus cancelledStatus,
                                @Param("completedAt") LocalDateTime completedAt,
                                @Param("outcome") VerificationOutcome outcome,
                                @Param("activeStatuses") List<VerificationTaskPo.TaskStatus> activeStatuses);

    /**
     * Atomically update progress only if the task is still active (PENDING or RUNNING).
     * Prevents overwriting progress on terminal-state tasks.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VerificationTaskPo t SET t.progress = :progress, t.progressStage = :stage "
         + "WHERE t.id = :taskId AND t.status IN ('PENDING', 'RUNNING') "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int updateProgressIfActive(@Param("taskId") Long taskId, @Param("progress") int progress,
                               @Param("stage") TaskProgressStage stage,
                               @Param("workerId") String workerId,
                               @Param("currentTime") LocalDateTime currentTime);

    /** Persist the assumptions under which this task will run without replacing task state. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VerificationTaskPo t SET t.isAttack = :isAttack, "
         + "t.attackBudget = :attackBudget, t.enablePrivacy = :enablePrivacy, "
         + "t.modeledDeviceAttackPointCount = :devicePointCount, "
         + "t.modeledFalsifiableReadingDeviceCount = :falsifiableReadingDeviceCount, "
         + "t.modeledAutomationLinkAttackPointCount = :linkPointCount, "
         + "t.modelSnapshotJson = :modelSnapshotJson, "
         + "t.modelSemanticsJson = :modelSemanticsJson, "
         + "t.requestJson = :requestJson, t.templateSnapshotsJson = :templateSnapshotsJson "
         + "WHERE t.id = :taskId")
    int updateModelContext(@Param("taskId") Long taskId,
                           @Param("isAttack") boolean isAttack,
                           @Param("attackBudget") int attackBudget,
                           @Param("enablePrivacy") boolean enablePrivacy,
                           @Param("devicePointCount") int devicePointCount,
                           @Param("falsifiableReadingDeviceCount") int falsifiableReadingDeviceCount,
                           @Param("linkPointCount") int linkPointCount,
                           @Param("modelSnapshotJson") String modelSnapshotJson,
                           @Param("modelSemanticsJson") String modelSemanticsJson,
                           @Param("requestJson") String requestJson,
                           @Param("templateSnapshotsJson") String templateSnapshotsJson);
}
