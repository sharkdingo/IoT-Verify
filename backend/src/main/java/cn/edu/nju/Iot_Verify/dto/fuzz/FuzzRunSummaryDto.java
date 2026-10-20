package cn.edu.nju.Iot_Verify.dto.fuzz;

import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FuzzRunSummaryDto {
    private Long id;
    private RunInitiator initiator;
    private FuzzOutcome outcome;
    private FuzzExplorationMode explorationMode;
    private Long effectiveSeed;
    private Integer iterations;
    private Long generatedPaths;
    private Long elapsedMs;
    private ModelRunSnapshotDto modelSnapshot;
    private FuzzEligibilityDto eligibility;
    private Map<String, SpecificationDto> eligibleSpecifications;
    private Map<String, ModelTokenSource> modelTokenSourcesByDeviceId;
    @Builder.Default
    private List<String> limitations = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private Integer findingCount;
    private Integer maxIterations;
    private Integer pathLength;
    private Integer populationSize;
    @Builder.Default
    private List<FuzzFindingSummaryDto> findings = new ArrayList<>();
    @Builder.Default
    private boolean dataAvailable = true;
    private String unavailableReasonCode;
}
