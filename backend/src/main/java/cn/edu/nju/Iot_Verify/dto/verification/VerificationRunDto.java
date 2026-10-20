package cn.edu.nju.Iot_Verify.dto.verification;

import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** Completed verification-run result. Async task lifecycle fields are intentionally absent. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerificationRunDto {

    private Long id;
    private RunInitiator initiator;
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
    private VerificationOutcome outcome;
    private Boolean modelComplete;
    private Integer violatedSpecCount;
    private Integer counterexampleCount;
    /** Exact saved trace references, including unavailable evidence, for this run alone. */
    @Builder.Default
    private List<TraceSummaryDto> counterexamples = List.of();
    private Integer disabledRuleCount;
    private Integer skippedSpecCount;
    private List<ModelGenerationIssueDto> generationIssues;
    private List<SpecResultDto> specResults;
    private List<String> checkLogs;
    private String nusmvOutput;

    /**
     * Whether this run still holds the SMV model it checked, so a client can offer
     * {@code GET /api/verify/runs/{id}/smv} only when it can succeed.
     *
     * <p>Keyed on the run rather than on a counterexample because all of a run's counterexamples share
     * one model, and a run where every specification holds has no counterexample to key on at all.
     * Runs recorded before the model was stored report {@code false}.
     */
    private boolean hasSmvModel;
}
