package cn.edu.nju.Iot_Verify.dto.recommendation;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
public class ScenarioRecommendationResponseDto {
    private String message;
    private Integer count;
    private Integer requestedCount;
    private Integer validatedCount;
    private Integer filteredCount;
    private List<RecommendationFilterItemDto> filteredItems;
    private Integer adjustedCount;
    private List<RecommendationAdjustmentItemDto> adjustedItems;
    private Integer rawCandidateCount;
    private Integer inspectedCount;
    private Integer truncatedCount;
    private String scenarioName;
    private String rationale;
    private ScenarioObjectiveTargetsDto objectiveTargets;
    private String objectiveStatus;
    private List<ScenarioObjectiveIssueDto> objectiveIssues;
    private Boolean verificationReady;
    private List<ScenarioReadinessIssueDto> readinessIssues;
    private List<ScenarioSemanticWarningDto> semanticWarnings;
    private PortableSceneDto scene;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean draftStored;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean previousDraftRetained;
}
