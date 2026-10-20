package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.SimulationTracePo;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTraceSummaryProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SimulationTraceRepository extends JpaRepository<SimulationTracePo, Long> {

    @Query("SELECT trace.id AS id, trace.initiator AS initiator, "
            + "trace.requestedSteps AS requestedSteps, "
            + "trace.steps AS steps, trace.stateCount AS stateCount, "
            + "trace.logsJson AS logsJson, trace.generationIssuesJson AS generationIssuesJson, "
            + "trace.modelSnapshotJson AS modelSnapshotJson, "
            + "trace.modelSemanticsJson AS modelSemanticsJson, trace.isAttack AS isAttack, "
            + "trace.attackBudget AS attackBudget, trace.enablePrivacy AS enablePrivacy, "
            + "trace.modeledDeviceAttackPointCount AS modeledDeviceAttackPointCount, "
            + "trace.modeledFalsifiableReadingDeviceCount AS modeledFalsifiableReadingDeviceCount, "
            + "trace.modeledAutomationLinkAttackPointCount AS modeledAutomationLinkAttackPointCount, "
            + "trace.createdAt AS createdAt, "
            // Resolve presence by primary key so outer sorting/pagination carries no full SMV text.
            + "(SELECT CASE WHEN stored.smvModelContent IS NOT NULL AND stored.smvModelContent <> '' "
            + "THEN TRUE ELSE FALSE END FROM SimulationTracePo stored "
            + "WHERE stored.id = trace.id AND stored.userId = trace.userId) AS hasSmvModel "
            + "FROM SimulationTracePo trace WHERE trace.userId = :userId "
            + "ORDER BY trace.createdAt DESC, trace.id DESC")
    List<SimulationTraceSummaryProjection> findSummariesByUserId(
            @Param("userId") Long userId, Pageable pageable);

    @Query("SELECT COUNT(trace) FROM SimulationTracePo trace "
            + "WHERE trace.userId = :userId AND NOT EXISTS ("
            + "SELECT task.id FROM SimulationTaskPo task "
            + "WHERE task.userId = :userId AND task.simulationTraceId = trace.id)")
    long countStandaloneByUserId(@Param("userId") Long userId);

    /**
     * 根据ID和用户ID查询模拟轨迹
     */
    Optional<SimulationTracePo> findByIdAndUserId(Long id, Long userId);

    void deleteByUserId(Long userId);
}
