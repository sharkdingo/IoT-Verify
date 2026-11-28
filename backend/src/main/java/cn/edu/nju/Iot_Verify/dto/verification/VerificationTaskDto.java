package cn.edu.nju.Iot_Verify.dto.verification;

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

/**
 * 验证任务 DTO
 *
 * 用于Controller层返回任务信息，不包含敏感内部字段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerificationTaskDto implements TaskToolState {
    /**
     * 任务ID
     */
    private Long id;

    private RunInitiator initiator;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    /**
     * 处理时间（毫秒）
     */
    private Long processingTimeMs;

    @JsonProperty("isAttack")
    private Boolean isAttack;

    private Integer attackBudget;

    private Boolean enablePrivacy;

    private ModelSemanticsDto modelSemantics;

    private ModelRunSnapshotDto modelSnapshot;

    private VerificationOutcome outcome;

    private Boolean modelComplete;

    /**
     * 违规规格数量
     */
    private Integer violatedSpecCount;

    private Integer disabledRuleCount;

    private Integer skippedSpecCount;

    private List<ModelGenerationIssueDto> generationIssues;

    /**
     * 每个规格的检查结果（完成后返回）
     */
    private List<SpecResultDto> specResults;

    /**
     * 检查日志
     */
    private List<String> checkLogs;

    private String nusmvOutput;

    /**
     * Whether this run still holds the SMV model it checked, gating
     * {@code GET /api/verify/runs/{id}/smv}.
     *
     * <p>Present here as well as on {@code VerificationRunDto} because a completed asynchronous task
     * <em>is</em> the run, and the task response is what a client reads when polling finishes — so
     * omitting it left the client unable to tell whether the download could succeed on the path most
     * runs take. Runs recorded before the model was stored report {@code false}.
     */
    private boolean hasSmvModel;

    /**
     * 错误消息
     */
    private String errorMessage;

    private Integer progress;
    private TaskProgressStage progressStage;
}
