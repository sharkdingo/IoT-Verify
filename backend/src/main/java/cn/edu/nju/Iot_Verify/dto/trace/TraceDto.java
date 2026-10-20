package cn.edu.nju.Iot_Verify.dto.trace;

import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelPlaybackSceneDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 验证轨迹传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceDto {
    /**
     * 数据库ID
     */
    private Long id;
    
    /**
     * 用户ID
     */
    @JsonIgnore
    @NotNull(message = "User ID is required")
    private Long userId;
    
    /** Owning completed verification-run id; absent only before a result is persisted. */
    private Long verificationTaskId;
    
    /**
     * 违反的规格ID
     */
    private String violatedSpecId;
    
    /**
     * Internal persisted specification snapshot. API clients receive the parsed
     * {@link #violatedSpec} object instead of having to parse an implementation JSON string.
     */
    @JsonIgnore
    private String violatedSpecJson;

    /** Structured verification-time specification snapshot for user-facing trace context. */
    private SpecificationDto violatedSpec;

    /** Exact CTL/LTL expression emitted to NuSMV for the violated specification. */
    private String checkedExpression;
    
    /**
     * 状态序列
     */
    @Valid
    @NotNull(message = "States are required")
    private List<TraceStateDto> states;

    /** Generation omissions from the verification run that produced this trace. */
    private Integer disabledRuleCount;

    private Integer skippedSpecCount;

    private Boolean modelComplete;

    private List<ModelGenerationIssueDto> generationIssues;

    /**
     * 验证时的攻击模式开关，从轨迹所属已完成运行读取。
     * 暴露给前端，使历史 trace 能显示自身的验证上下文，而非当前表单。
     */
    @JsonProperty("isAttack")
    private Boolean attack;

    /**
     * 验证时的攻击预算，从轨迹所属已完成运行读取。
     */
    private Integer attackBudget;

    /**
     * 验证时的隐私维度开关，从轨迹所属已完成运行读取。
     */
    private Boolean enablePrivacy;

    /** Structured semantics derived from the stored run context. */
    private ModelSemanticsDto modelSemantics;

    private ModelRunSnapshotDto modelSnapshot;

    /** Exact device layout and rules shown while replaying this historical evidence. */
    private ModelPlaybackSceneDto playbackScene;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
