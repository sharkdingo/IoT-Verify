package cn.edu.nju.Iot_Verify.dto.simulation;

import cn.edu.nju.Iot_Verify.dto.model.TaskToolState;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SimulationTaskDto implements TaskToolState {

    private Long id;
    private RunInitiator initiator;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long processingTimeMs;
    @JsonProperty("isAttack")
    private Boolean isAttack;
    private Integer attackBudget;
    private Boolean enablePrivacy;
    private ModelSemanticsDto modelSemantics;
    private ModelRunSnapshotDto modelSnapshot;
    private Integer requestedSteps;
    private Integer steps;
    private Boolean modelComplete;
    private Integer disabledRuleCount;
    private List<ModelGenerationIssueDto> generationIssues;
    private Long simulationTraceId;
    private List<String> checkLogs;
    private String errorMessage;
    private Integer progress;
    private TaskProgressStage progressStage;
}
