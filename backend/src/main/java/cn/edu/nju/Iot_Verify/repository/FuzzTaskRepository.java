package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzOutcome;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.repository.projection.CompletedRunDeletionProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskProgressProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzSpecificationPresentationProjection;
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

@Repository
public interface FuzzTaskRepository extends JpaRepository<FuzzTaskPo, Long>, DatabaseClockRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM FuzzTaskPo t WHERE t.id = :taskId")
    Optional<FuzzTaskPo> findByIdForUpdate(@Param("taskId") Long taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM FuzzTaskPo t WHERE t.id = :taskId AND t.userId = :userId")
    Optional<FuzzTaskPo> findByIdAndUserIdForUpdate(
            @Param("taskId") Long taskId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM FuzzTaskPo t WHERE t.id = :runId "
         + "AND t.userId = :userId AND t.status = :status")
    Optional<FuzzTaskPo> findCompletedRunForUpdate(
            @Param("runId") Long runId,
            @Param("userId") Long userId,
            @Param("status") FuzzTaskPo.TaskStatus status);

    @Query("SELECT t.id AS id, t.createdAt AS createdAt, t.completedAt AS completedAt "
         + "FROM FuzzTaskPo t WHERE t.id = :runId "
         + "AND t.userId = :userId AND t.status = :status")
    Optional<CompletedRunDeletionProjection> findDeletionProjection(
            @Param("runId") Long runId,
            @Param("userId") Long userId,
            @Param("status") FuzzTaskPo.TaskStatus status);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM FuzzTaskPo t WHERE t.id = :runId "
         + "AND t.userId = :userId AND t.status = :status")
    int deleteCompletedRun(
            @Param("runId") Long runId,
            @Param("userId") Long userId,
            @Param("status") FuzzTaskPo.TaskStatus status);

    Optional<FuzzTaskPo> findByIdAndUserId(Long id, Long userId);

    Optional<FuzzTaskSummaryProjection> findSummaryByIdAndUserId(Long id, Long userId);

    // Materialize one validated JSON value per owned row; LIMIT prevents derived-table merging
    // from expanding every field extraction back into a separate parse of the LONGTEXT envelope.
    @Query(value = "SELECT id AS id, "
            + "CASE WHEN JSON_EXTRACT(frozenInput, '$.schemaVersion') = 1 THEN "
            + "CAST(JSON_EXTRACT(frozenInput, '$.snapshot.specifications') AS CHAR) END AS specificationsJson, "
            + "CASE WHEN JSON_EXTRACT(frozenInput, '$.schemaVersion') = 1 THEN "
            + "CAST(JSON_EXTRACT(frozenInput, '$.modelTokenSourcesByDeviceId') AS CHAR) END AS modelTokenSourcesJson "
            + "FROM (SELECT id, CASE WHEN JSON_VALID(model_input_snapshot_json) THEN "
            + "CAST(model_input_snapshot_json AS JSON) END AS frozenInput "
            + "FROM fuzz_task WHERE user_id = :userId AND id IN :runIds "
            + "LIMIT 18446744073709551615) frozen", nativeQuery = true)
    List<FuzzSpecificationPresentationProjection> findSpecificationPresentationByUserIdAndIdIn(
            @Param("userId") Long userId, @Param("runIds") List<Long> runIds);

    Optional<FuzzTaskPo> findByIdAndUserIdAndStatus(
            Long id, Long userId, FuzzTaskPo.TaskStatus status);

    @Query("SELECT t.status FROM FuzzTaskPo t WHERE t.id = :taskId")
    Optional<FuzzTaskPo.TaskStatus> findStatusById(@Param("taskId") Long taskId);

    @Query("SELECT t.progress AS progress, t.status AS status FROM FuzzTaskPo t "
         + "WHERE t.id = :taskId AND t.userId = :userId")
    Optional<FuzzTaskProgressProjection> findProgressByIdAndUserId(
            @Param("taskId") Long taskId, @Param("userId") Long userId);

    List<FuzzTaskPo> findByUserIdAndStatus(Long userId, FuzzTaskPo.TaskStatus status);

    long countByUserId(Long userId);

    long countByUserIdAndStatusIn(Long userId, List<FuzzTaskPo.TaskStatus> statuses);

    @Query("SELECT t.id FROM FuzzTaskPo t WHERE t.userId = :userId AND t.status = :status")
    List<Long> findIdsByUserIdAndStatus(@Param("userId") Long userId,
                                        @Param("status") FuzzTaskPo.TaskStatus status);

    List<FuzzTaskSummaryProjection> findSummaryByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(
            Long userId, FuzzTaskPo.TaskStatus status, Pageable pageable);

    List<FuzzTaskSummaryProjection> findSummaryByUserIdAndStatusNotAndIdNotInOrderByCreatedAtDescIdDesc(
            Long userId, FuzzTaskPo.TaskStatus status, List<Long> excludedIds, Pageable pageable);

    List<FuzzTaskSummaryProjection> findSummaryByUserIdAndStatusOrderByCreatedAtDescIdDesc(
            Long userId, FuzzTaskPo.TaskStatus status, Pageable pageable);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM FuzzTaskPo t WHERE t.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM FuzzTaskPo t WHERE t.id = :taskId AND t.userId = :userId "
         + "AND t.workerId = :workerId AND t.status = :pending")
    int deleteUndispatchedTask(@Param("taskId") Long taskId,
                               @Param("userId") Long userId,
                               @Param("workerId") String workerId,
                               @Param("pending") FuzzTaskPo.TaskStatus pending);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FuzzTaskPo t SET t.status = :running, t.startedAt = :startedAt, "
         + "t.progress = 0, t.checkLogsJson = :checkLogsJson, "
         + "t.leaseExpiresAt = :leaseExpiresAt "
         + "WHERE t.id = :taskId AND t.status = :pending AND t.workerId = :workerId "
         + "AND t.leaseExpiresAt > :currentTime")
    int startTaskIfStillPending(@Param("taskId") Long taskId,
                                @Param("running") FuzzTaskPo.TaskStatus running,
                                @Param("startedAt") LocalDateTime startedAt,
                                @Param("workerId") String workerId,
                                @Param("currentTime") LocalDateTime currentTime,
                                @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                                @Param("checkLogsJson") String checkLogsJson,
                                @Param("pending") FuzzTaskPo.TaskStatus pending);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FuzzTaskPo t SET t.workerId = NULL, t.leaseExpiresAt = :expiredAt "
         + "WHERE t.id = :taskId AND t.workerId = :workerId "
         + "AND t.status IN (:activeStatuses)")
    int releaseOwnedActiveLease(@Param("taskId") Long taskId,
                                @Param("workerId") String workerId,
                                @Param("expiredAt") LocalDateTime expiredAt,
                                @Param("activeStatuses") List<FuzzTaskPo.TaskStatus> activeStatuses);

    @Transactional
    @Modifying(clearAutomatically = true)
    // Progress is deliberately left untouched: this sweep fails a task whose lease expired, so the
    // work did not finish. Overwriting it with 100 published a completed-work claim for an abandoned
    // run to every consumer of the task DTO — the REST clients and the AI tools. A real exploration
    // task was observed reporting `status: FAILED, progress: 100`. The last heartbeat's value is the
    // honest answer, and `status` is what says it ended. Only completeTaskIfRunning writes 100.
    // `<=`, matching the verification and simulation sweeps and the renewal guards below. Those guards all
    // require `leaseExpiresAt > :currentTime` to renew or commit, so a lease sitting exactly at the sampled
    // instant is already dead by every other predicate in this file — a strict `<` here was the one place
    // that called it live, leaving a row no worker could advance unreclaimed until the next maintenance tick.
    // Both sides carry microsecond precision (`CURRENT_TIMESTAMP(6)` against Hibernate's `datetime(6)`), so
    // equality is a rare coincidence rather than a routine one; this is a consistency fix among thirteen
    // predicates that disagreed on one boundary, not a frequently-hit bug.
    @Query("UPDATE FuzzTaskPo t SET t.status = :failed, t.progressStage = NULL, t.completedAt = :completedAt, "
         + "t.errorMessage = :errorMessage, t.checkLogsJson = :checkLogsJson, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.status IN (:activeStatuses) "
         + "AND (t.leaseExpiresAt IS NULL OR t.leaseExpiresAt <= :expiredBefore)")
    int failExpiredActiveTasks(@Param("failed") FuzzTaskPo.TaskStatus failed,
                               @Param("completedAt") LocalDateTime completedAt,
                               @Param("errorMessage") String errorMessage,
                               @Param("checkLogsJson") String checkLogsJson,
                               @Param("activeStatuses") List<FuzzTaskPo.TaskStatus> activeStatuses,
                               @Param("expiredBefore") LocalDateTime expiredBefore);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FuzzTaskPo t SET t.status = :completed, t.progressStage = NULL, t.completedAt = :completedAt, "
         + "t.processingTimeMs = :processingTimeMs, t.progress = 100, t.outcome = :outcome, "
         + "t.effectiveSeed = :effectiveSeed, t.iterations = :iterations, "
         + "t.generatedPaths = :generatedPaths, t.elapsedMs = :elapsedMs, "
         + "t.eligibilityJson = :eligibilityJson, t.limitationsJson = :limitationsJson, "
         + "t.findingCount = :findingCount, t.errorMessage = NULL, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL, "
         + "t.checkLogsJson = :checkLogsJson "
         + "WHERE t.id = :taskId AND t.status = :running "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int completeTaskIfRunning(@Param("taskId") Long taskId,
                              @Param("completed") FuzzTaskPo.TaskStatus completed,
                              @Param("completedAt") LocalDateTime completedAt,
                              @Param("processingTimeMs") Long processingTimeMs,
                              @Param("outcome") FuzzOutcome outcome,
                              @Param("effectiveSeed") Long effectiveSeed,
                              @Param("iterations") Integer iterations,
                              @Param("generatedPaths") Long generatedPaths,
                              @Param("elapsedMs") Long elapsedMs,
                              @Param("eligibilityJson") String eligibilityJson,
                              @Param("limitationsJson") String limitationsJson,
                              @Param("findingCount") Integer findingCount,
                              @Param("checkLogsJson") String checkLogsJson,
                              @Param("running") FuzzTaskPo.TaskStatus running,
                              @Param("workerId") String workerId,
                              @Param("currentTime") LocalDateTime currentTime);

    @Transactional
    @Modifying(clearAutomatically = true)
    // Progress is preserved: the worker failed partway, so 100 would claim work it never finished.
    @Query("UPDATE FuzzTaskPo t SET t.status = :failed, t.progressStage = NULL, t.completedAt = :completedAt, "
         + "t.processingTimeMs = :processingTimeMs, "
         + "t.errorMessage = :errorMessage, t.checkLogsJson = :checkLogsJson, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.id = :taskId AND t.status IN (:activeStatuses) "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int failTaskIfActive(@Param("taskId") Long taskId,
                         @Param("failed") FuzzTaskPo.TaskStatus failed,
                         @Param("completedAt") LocalDateTime completedAt,
                         @Param("processingTimeMs") Long processingTimeMs,
                         @Param("errorMessage") String errorMessage,
                         @Param("checkLogsJson") String checkLogsJson,
                         @Param("activeStatuses") List<FuzzTaskPo.TaskStatus> activeStatuses,
                         @Param("workerId") String workerId,
                         @Param("currentTime") LocalDateTime currentTime);

    @Transactional
    @Modifying(clearAutomatically = true)
    // Progress is preserved for the same reason as failExpiredActiveTasks above: the user stopped
    // this run partway, so overwriting progress with 100 claims work that was never done. Observed
    // live — a task cancelled at 30% was published as 100%.
    @Query("UPDATE FuzzTaskPo t SET t.status = :cancelled, t.progressStage = NULL, t.completedAt = :completedAt, "
         + "t.workerId = NULL, t.leaseExpiresAt = NULL "
         + "WHERE t.id = :taskId AND t.status IN (:activeStatuses)")
    int cancelTaskIfStillActive(@Param("taskId") Long taskId,
                                @Param("cancelled") FuzzTaskPo.TaskStatus cancelled,
                                @Param("completedAt") LocalDateTime completedAt,
                                @Param("activeStatuses") List<FuzzTaskPo.TaskStatus> activeStatuses);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FuzzTaskPo t SET t.progress = :progress, t.progressStage = :stage "
         + "WHERE t.id = :taskId AND t.status IN ('PENDING', 'RUNNING') "
         + "AND t.workerId = :workerId AND t.leaseExpiresAt > :currentTime")
    int updateProgressIfActive(@Param("taskId") Long taskId, @Param("progress") int progress,
                               @Param("stage") TaskProgressStage stage,
                               @Param("workerId") String workerId,
                               @Param("currentTime") LocalDateTime currentTime);
}
