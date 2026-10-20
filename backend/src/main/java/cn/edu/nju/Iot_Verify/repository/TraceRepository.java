package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.repository.projection.TraceSummaryProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TraceRepository extends JpaRepository<TracePo, Long> {

    /**
     * 根据用户ID查询所有轨迹
     */
    List<TracePo> findByUserId(Long userId);

    /**
     * 根据ID和用户ID查询轨迹
     */
    Optional<TracePo> findByIdAndUserId(Long id, Long userId);

    /**
     * 根据用户ID和验证任务ID查询所有轨迹
     */
    List<TracePo> findByUserIdAndVerificationTaskId(Long userId, Long verificationTaskId);

    // The service sorts the small summaries. SQL filesort would carry the full trajectory JSON.
    @NativeQuery(value = "SELECT id AS id, verification_task_id AS verificationTaskId, "
         + "violated_spec_id AS violatedSpecId, CAST(violated_spec_json AS CHAR) AS violatedSpecJson, "
         + "state_count AS stateCount, created_at AS createdAt, "
         + "CASE WHEN JSON_VALID(states_json) THEN CAST(JSON_EXTRACT(states_json, '$[0].devices[*].deviceId') AS CHAR) END AS deviceIdsJson, "
         + "CASE WHEN JSON_VALID(states_json) THEN CAST(JSON_EXTRACT(states_json, '$[0].devices[*].modelTokenSource') AS CHAR) END AS tokenSourcesJson "
         + "FROM trace WHERE user_id = :userId AND verification_task_id IN :verificationTaskIds",
            sqlResultSetMapping = "TraceSummary")
    List<TraceSummaryProjection> findSummariesByUserIdAndVerificationTaskIdIn(
            @Param("userId") Long userId,
            @Param("verificationTaskIds") List<Long> verificationTaskIds);

    long countByUserIdAndVerificationTaskId(Long userId, Long verificationTaskId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TracePo t WHERE t.userId = :userId "
         + "AND t.verificationTaskId = :verificationTaskId")
    int deleteByUserIdAndVerificationTaskId(
            @Param("userId") Long userId,
            @Param("verificationTaskId") Long verificationTaskId);

    /**
     * 删除用户的所有轨迹
     */
    void deleteByUserId(Long userId);
}
