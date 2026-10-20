package cn.edu.nju.Iot_Verify.repository.projection;

import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzOutcome;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/** Constructor projection for task/run lists; excludes frozen input and diagnostic-log columns. */
@Getter
@AllArgsConstructor
public final class FuzzTaskSummaryProjection {

    private final Long id;
    private final Long userId;
    private final RunInitiator initiator;
    private final FuzzTaskPo.TaskStatus status;
    private final LocalDateTime createdAt;
    private final LocalDateTime startedAt;
    private final LocalDateTime completedAt;
    private final Long processingTimeMs;
    private final String errorMessage;
    private final Integer progress;
    private final TaskProgressStage progressStage;
    private final String targetSpecIdsJson;
    private final Integer maxIterations;
    private final Integer pathLength;
    private final Integer populationSize;
    private final Long seed;
    private final FuzzExplorationMode explorationMode;
    private final String modelSnapshotJson;
    private final FuzzOutcome outcome;
    private final Long effectiveSeed;
    private final Integer iterations;
    private final Long generatedPaths;
    private final Long elapsedMs;
    private final String eligibilityJson;
    private final String limitationsJson;
    private final Integer findingCount;
}
