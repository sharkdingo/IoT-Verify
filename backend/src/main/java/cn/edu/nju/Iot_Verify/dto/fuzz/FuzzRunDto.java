package cn.edu.nju.Iot_Verify.dto.fuzz;

import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelPlaybackSceneDto;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
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
public class FuzzRunDto {
    private Long id;
    private RunInitiator initiator;
    private FuzzOutcome outcome;
    private FuzzExplorationMode explorationMode;
    private Long effectiveSeed;
    private Integer iterations;
    private Long generatedPaths;
    private Long elapsedMs;
    private ModelRunSnapshotDto modelSnapshot;
    private ModelPlaybackSceneDto playbackScene;
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
    private List<String> targetSpecIds = new ArrayList<>();
    @Builder.Default
    private List<FuzzFindingDto> findings = new ArrayList<>();
}
