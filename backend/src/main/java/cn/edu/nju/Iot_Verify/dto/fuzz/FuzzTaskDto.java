package cn.edu.nju.Iot_Verify.dto.fuzz;

import cn.edu.nju.Iot_Verify.dto.model.TaskToolState;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;

import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FuzzTaskDto implements TaskToolState {
    private Long id;
    private RunInitiator initiator;
    private String status;
    private Integer progress;
    private TaskProgressStage progressStage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long processingTimeMs;
    private String errorMessage;
    private Long runId;
    private FuzzOutcome outcome;
    private FuzzExplorationMode explorationMode;
    private ModelRunSnapshotDto modelSnapshot;
    private Integer maxIterations;
    private Integer pathLength;
    private Integer populationSize;
    private Long seed;
    @Builder.Default
    private List<String> targetSpecIds = new ArrayList<>();
}
