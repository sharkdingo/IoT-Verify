package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.component.fuzz.FuzzInputEventKind;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzInputEventSource;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzLimitationContract;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzMetadataPolicy;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzModelInputSnapshotCodec;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzEligibilityDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingReplayDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzIneligibleSpecDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzInputEventDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzOutcome;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelPlaybackSceneDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.FuzzFindingPo;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzFindingSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzSpecificationPresentationProjection;
import jakarta.validation.Validator;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import cn.edu.nju.Iot_Verify.util.TraceStateIntegrity;
import cn.edu.nju.Iot_Verify.util.ModelPlaybackSceneSnapshot;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class FuzzMapper {

    private static final long MAX_RUN_METADATA_BYTES = FuzzMetadataPolicy.MAX_RUN_METADATA_BYTES;
    private static final int MAX_ELIGIBILITY_LABEL_CHARS = FuzzMetadataPolicy.MAX_ELIGIBILITY_LABEL_CHARS;
    private static final int MAX_ELIGIBILITY_REASON_CHARS = FuzzMetadataPolicy.MAX_ELIGIBILITY_REASON_CHARS;
    private static final int MAX_STABLE_CODE_CHARS = FuzzMetadataPolicy.MAX_STABLE_CODE_CHARS;
    private static final Pattern STABLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final Pattern MODEL_FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");

    private final ModelFingerprint modelFingerprint;
    private final Validator validator;

    public FuzzMapper(ModelFingerprint modelFingerprint, Validator validator) {
        this.modelFingerprint = Objects.requireNonNull(modelFingerprint, "modelFingerprint");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public FuzzTaskDto toTaskDto(FuzzTaskPo task) {
        if (task == null) return null;
        validateTaskLifecycle(task);
        return FuzzTaskDto.builder()
                .id(task.getId())
                .initiator(task.getInitiator())
                .status(statusName(task))
                .progress(task.getProgress())
                .progressStage(task.getProgressStage())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .processingTimeMs(task.getProcessingTimeMs())
                .errorMessage(task.getErrorMessage())
                .runId(runId(task))
                .outcome(task.getOutcome())
                .explorationMode(explorationMode(task))
                .modelSnapshot(readModelSnapshot(task))
                .maxIterations(task.getMaxIterations())
                .pathLength(task.getPathLength())
                .populationSize(task.getPopulationSize())
                .seed(task.getSeed())
                .targetSpecIds(readTargetSpecIds(task))
                .build();
    }

    public FuzzTaskSummaryDto toTaskSummaryDto(FuzzTaskPo task) {
        if (task == null) return null;
        validateTaskLifecycle(task);
        return FuzzTaskSummaryDto.builder()
                .id(task.getId())
                .initiator(task.getInitiator())
                .status(statusName(task))
                .progress(task.getProgress())
                .progressStage(task.getProgressStage())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .processingTimeMs(task.getProcessingTimeMs())
                .errorMessage(task.getErrorMessage())
                .runId(runId(task))
                .outcome(task.getOutcome())
                .explorationMode(explorationMode(task))
                .modelSnapshot(readModelSnapshot(task))
                .maxIterations(task.getMaxIterations())
                .pathLength(task.getPathLength())
                .populationSize(task.getPopulationSize())
                .seed(task.getSeed())
                .targetSpecIds(readTargetSpecIds(task))
                .build();
    }

    /** Maps a closed list projection without touching the frozen input LONGTEXT. */
    public FuzzTaskSummaryDto toTaskSummaryDtoProjection(FuzzTaskSummaryProjection task) {
        if (task == null) return null;
        validateTaskSummaryProjection(task);
        return FuzzTaskSummaryDto.builder()
                .id(task.getId())
                .initiator(task.getInitiator())
                .status(statusName(task))
                .progress(task.getProgress())
                .progressStage(task.getProgressStage())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .processingTimeMs(task.getProcessingTimeMs())
                .errorMessage(task.getErrorMessage())
                .runId(runId(task))
                .outcome(task.getOutcome())
                .explorationMode(explorationMode(task))
                .modelSnapshot(readModelSnapshot(task))
                .maxIterations(task.getMaxIterations())
                .pathLength(task.getPathLength())
                .populationSize(task.getPopulationSize())
                .seed(task.getSeed())
                .targetSpecIds(readTargetSpecIds(task))
                .build();
    }

    /** Maps task detail from the same bounded projection used by polling lists. */
    public FuzzTaskDto toTaskDtoProjection(FuzzTaskSummaryProjection task) {
        if (task == null) return null;
        validateTaskSummaryProjection(task);
        return FuzzTaskDto.builder()
                .id(task.getId())
                .initiator(task.getInitiator())
                .status(statusName(task))
                .progress(task.getProgress())
                .progressStage(task.getProgressStage())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .processingTimeMs(task.getProcessingTimeMs())
                .errorMessage(task.getErrorMessage())
                .runId(runId(task))
                .outcome(task.getOutcome())
                .explorationMode(explorationMode(task))
                .modelSnapshot(readModelSnapshot(task))
                .maxIterations(task.getMaxIterations())
                .pathLength(task.getPathLength())
                .populationSize(task.getPopulationSize())
                .seed(task.getSeed())
                .targetSpecIds(readTargetSpecIds(task))
                .build();
    }

    public FuzzRunDto toRunDto(FuzzTaskPo task, List<FuzzFindingPo> findings) {
        PersistedRunWithFindings validated = readAndValidateRun(task, findings);
        PersistedRunData data = validated.data();
        List<FuzzFindingDto> findingDtos = findings == null ? List.of() : findings.stream()
                .map(finding -> toFindingDto(finding, validated.findingsById().get(finding.getId())))
                .toList();
        return FuzzRunDto.builder()
                .id(task.getId())
                .initiator(task.getInitiator())
                .outcome(task.getOutcome())
                .explorationMode(explorationMode(task))
                .effectiveSeed(task.getEffectiveSeed())
                .iterations(task.getIterations())
                .generatedPaths(task.getGeneratedPaths())
                .elapsedMs(task.getElapsedMs())
                .modelSnapshot(data.modelSnapshot())
                .playbackScene(data.playbackScene())
                .eligibility(data.eligibility())
                .eligibleSpecifications(data.eligibleSpecifications())
                .modelTokenSourcesByDeviceId(data.modelTokenSourcesByDeviceId())
                .limitations(data.limitations())
                .createdAt(task.getCreatedAt())
                .completedAt(task.getCompletedAt())
                .findingCount(task.getFindingCount())
                .maxIterations(task.getMaxIterations())
                .pathLength(task.getPathLength())
                .populationSize(task.getPopulationSize())
                .targetSpecIds(data.targetSpecIds())
                .findings(findingDtos)
                .build();
    }

    public FuzzRunSummaryDto toRunSummaryDto(FuzzTaskPo task, List<FuzzFindingPo> findings) {
        PersistedRunWithFindings validated = readAndValidateRun(task, findings);
        PersistedRunData data = validated.data();
        return FuzzRunSummaryDto.builder()
                .id(task.getId())
                .initiator(task.getInitiator())
                .outcome(task.getOutcome())
                .explorationMode(explorationMode(task))
                .effectiveSeed(task.getEffectiveSeed())
                .iterations(task.getIterations())
                .generatedPaths(task.getGeneratedPaths())
                .elapsedMs(task.getElapsedMs())
                .modelSnapshot(data.modelSnapshot())
                .eligibility(data.eligibility())
                .eligibleSpecifications(data.eligibleSpecifications())
                .modelTokenSourcesByDeviceId(data.modelTokenSourcesByDeviceId())
                .limitations(data.limitations())
                .createdAt(task.getCreatedAt())
                .completedAt(task.getCompletedAt())
                .findingCount(task.getFindingCount())
                .maxIterations(task.getMaxIterations())
                .pathLength(task.getPathLength())
                .populationSize(task.getPopulationSize())
                .findings(findings == null ? List.of() : findings.stream()
                        .map(finding -> toFindingSummaryDto(
                                finding, validated.findingsById().get(finding.getId())))
                        .toList())
                .dataAvailable(true)
                .build();
    }

    /**
     * Maps a run-list projection. Lists validate bounded metadata and finding
     * ownership/identity; full model-input and trace evidence is validated when
     * the detail endpoint loads the complete task/finding rows.
     */
    public FuzzRunSummaryDto toRunSummaryDtoFromTaskProjection(
            FuzzTaskSummaryProjection task, List<FuzzFindingSummaryProjection> findings,
            FuzzSpecificationPresentationProjection presentation) {
        ProjectedRunContext context = readAndValidateProjectedRunContext(task, findings);
        ProjectedRunData data = context.data();
        Map<String, SpecificationDto> specifications = readProjectedSpecifications(
                task, presentation, data.modelSnapshot(), data.eligibility());
        Map<String, ModelTokenSource> tokenSources = readProjectedTokenSources(
                task, presentation, data.modelSnapshot());
        Set<String> deviceIds = new HashSet<>();
        for (String id : tokenSources.keySet()) {
            if (!deviceIds.add(DeviceNameNormalizer.normalize(id))) {
                throw invalidProjectedRun(task, "modelTokenSourcesByDeviceId", "frozen device identities are ambiguous");
            }
        }
        if (specifications.values().stream().anyMatch(specification ->
                List.of(specification.getAConditions(), specification.getIfConditions(), specification.getThenConditions())
                        .stream().flatMap(List::stream)
                        .anyMatch(condition -> !deviceIds.contains(DeviceNameNormalizer.normalize(condition.getDeviceId()))))) {
            throw invalidProjectedRun(task, "specificationPresentation", "frozen conditions reference an uncaptured device");
        }
        return FuzzRunSummaryDto.builder()
                .id(task.getId())
                .initiator(task.getInitiator())
                .outcome(task.getOutcome())
                .explorationMode(explorationMode(task))
                .effectiveSeed(task.getEffectiveSeed())
                .iterations(task.getIterations())
                .generatedPaths(task.getGeneratedPaths())
                .elapsedMs(task.getElapsedMs())
                .modelSnapshot(data.modelSnapshot())
                .eligibility(data.eligibility())
                .eligibleSpecifications(specifications)
                .modelTokenSourcesByDeviceId(tokenSources)
                .limitations(data.limitations())
                .createdAt(task.getCreatedAt())
                .completedAt(task.getCompletedAt())
                .findingCount(task.getFindingCount())
                .maxIterations(task.getMaxIterations())
                .pathLength(task.getPathLength())
                .populationSize(task.getPopulationSize())
                .findings(findings == null ? List.of() : findings.stream()
                        .map(finding -> toFindingSummaryDto(
                                finding,
                                specifications.get(finding.getViolatedSpecId()),
                                data.eligibility().getEligibleSpecLabels()
                                        .get(finding.getViolatedSpecId())))
                        .toList())
                .dataAvailable(true)
                .build();
    }

    public FuzzFindingDto toFindingDto(FuzzFindingPo finding) {
        if (finding == null) return null;
        return toFindingDto(finding, readAndValidateFinding(finding));
    }

    public FuzzFindingDto toFindingDto(
            FuzzTaskPo task, FuzzFindingPo finding, long actualFindingCount) {
        if (finding == null) return null;
        ValidatedFindingReplay validated = validateFindingReplay(task, finding, actualFindingCount);
        return toFindingDto(finding, validated.findingData());
    }

    public FuzzFindingReplayDto toFindingReplayDto(
            FuzzTaskPo task, FuzzFindingPo finding, long actualFindingCount) {
        if (finding == null) return null;
        ValidatedFindingReplay validated = validateFindingReplay(task, finding, actualFindingCount);
        return FuzzFindingReplayDto.builder()
                .finding(toFindingDto(finding, validated.findingData()))
                .modelSnapshot(validated.context().data().modelSnapshot())
                .playbackScene(validated.context().data().playbackScene())
                .build();
    }

    private ValidatedFindingReplay validateFindingReplay(
            FuzzTaskPo task, FuzzFindingPo finding, long actualFindingCount) {
        validateTaskLifecycle(task);
        if (task == null
                || finding.getId() == null
                || !Objects.equals(task.getId(), finding.getFuzzTaskId())
                || !Objects.equals(task.getUserId(), finding.getUserId())) {
            throw new PersistedDataIntegrityException(
                    "FuzzTask", task == null ? null : task.getId(), "findings",
                    "finding ownership does not match the run");
        }
        PersistedRunContext context = readAndValidateRunContext(task, actualFindingCount);
        PersistedFindingData data = readAndValidateFinding(finding);
        validateFindingAgainstRun(
                task,
                finding.getViolatedSpecId(),
                finding.getSeed(),
                finding.getCreatedAt(),
                data,
                context);
        return new ValidatedFindingReplay(context, data);
    }

    private FuzzFindingDto toFindingDto(FuzzFindingPo finding, PersistedFindingData data) {
        return FuzzFindingDto.builder()
                .id(finding.getId())
                .fuzzTaskId(finding.getFuzzTaskId())
                .violatedSpecId(finding.getViolatedSpecId())
                .violatedSpec(data.specification())
                .firstViolationStep(finding.getFirstViolationStep())
                .states(data.states())
                .seed(finding.getSeed())
                .inputEvents(data.inputEvents())
                .createdAt(finding.getCreatedAt())
                .build();
    }

    public FuzzFindingSummaryDto toFindingSummaryDto(FuzzFindingPo finding) {
        if (finding == null) return null;
        return toFindingSummaryDto(finding, readAndValidateFinding(finding));
    }

    private FuzzFindingSummaryDto toFindingSummaryDto(
            FuzzFindingPo finding, PersistedFindingData data) {
        return FuzzFindingSummaryDto.builder()
                .id(finding.getId())
                .fuzzTaskId(finding.getFuzzTaskId())
                .violatedSpecId(finding.getViolatedSpecId())
                .violatedSpec(data.specification())
                .specificationLabel(specificationLabel(data.specification()))
                .firstViolationStep(finding.getFirstViolationStep())
                .seed(finding.getSeed())
                .createdAt(finding.getCreatedAt())
                .stateCount(data.states().size())
                .build();
    }

    public FuzzFindingSummaryDto toFindingSummaryDto(FuzzFindingSummaryProjection finding) {
        if (finding == null) return null;
        return toFindingSummaryDto(finding, null, finding.getViolatedSpecId());
    }

    private FuzzFindingSummaryDto toFindingSummaryDto(
            FuzzFindingSummaryProjection finding, SpecificationDto specification, String frozenLabel) {
        validateFindingSummaryProjection(finding);
        return FuzzFindingSummaryDto.builder()
                .id(finding.getId())
                .fuzzTaskId(finding.getFuzzTaskId())
                .violatedSpecId(finding.getViolatedSpecId())
                .violatedSpec(specification)
                .specificationLabel(hasText(frozenLabel)
                        ? frozenLabel : finding.getViolatedSpecId())
                .firstViolationStep(finding.getFirstViolationStep())
                .seed(finding.getSeed())
                .createdAt(finding.getCreatedAt())
                .stateCount(finding.getStateCount())
                .build();
    }

    /* No `toFindingSummaryDtos`/`toFindingDtos` list wrappers: they had no callers, because callers map the
       singular overloads over their own streams (they need the per-finding validated data). */

    private String statusName(FuzzTaskPo task) {
        return task.getStatus().name();
    }

    private String statusName(FuzzTaskSummaryProjection task) {
        return task.getStatus().name();
    }

    private Long runId(FuzzTaskPo task) {
        return task.getStatus() == FuzzTaskPo.TaskStatus.COMPLETED ? task.getId() : null;
    }

    private Long runId(FuzzTaskSummaryProjection task) {
        return task.getStatus() == FuzzTaskPo.TaskStatus.COMPLETED ? task.getId() : null;
    }

    private List<String> readTargetSpecIds(FuzzTaskPo task) {
        List<String> targetSpecIds = JsonUtils.readPersistedJsonRequired(
                "FuzzTask", task.getId(), "targetSpecIdsJson",
                task.getTargetSpecIdsJson(), () -> JsonUtils.fromJson(
                        task.getTargetSpecIdsJson(), new TypeReference<List<String>>() {}));
        if (!validTargetSpecIds(targetSpecIds)) {
            throw invalidTask(task, "targetSpecIdsJson", "target specification IDs are invalid");
        }
        return targetSpecIds;
    }

    private List<String> readTargetSpecIds(FuzzTaskSummaryProjection task) {
        return JsonUtils.readPersistedJsonRequired("FuzzTask", task.getId(), "targetSpecIdsJson",
                task.getTargetSpecIdsJson(), () -> JsonUtils.fromJson(
                        task.getTargetSpecIdsJson(), new TypeReference<List<String>>() {}));
    }

    private ModelRunSnapshotDto readModelSnapshot(FuzzTaskPo task) {
        ModelRunSnapshotDto snapshot = JsonUtils.readPersistedJsonRequired(
                "FuzzTask", task.getId(), "modelSnapshotJson",
                task.getModelSnapshotJson(), () -> JsonUtils.fromJson(
                        task.getModelSnapshotJson(), ModelRunSnapshotDto.class));
        requireModelSnapshotShape(task.getId(), snapshot);
        return snapshot;
    }

    private ModelRunSnapshotDto readModelSnapshot(FuzzTaskSummaryProjection task) {
        ModelRunSnapshotDto snapshot = JsonUtils.readPersistedJsonRequired(
                "FuzzTask", task.getId(), "modelSnapshotJson",
                task.getModelSnapshotJson(), () -> JsonUtils.fromJson(
                        task.getModelSnapshotJson(), ModelRunSnapshotDto.class));
        requireModelSnapshotShape(task.getId(), snapshot);
        return snapshot;
    }

    private void requireModelSnapshotShape(Long taskId, ModelRunSnapshotDto snapshot) {
        if (snapshot == null || snapshot.getCapturedAt() == null || !snapshot.isTemplatesFrozen()
                || snapshot.getDeviceCount() < 0 || snapshot.getRuleCount() < 0
                || snapshot.getSpecificationCount() < 0
                || snapshot.getEnvironmentVariableCount() < 0
                || snapshot.getDeviceTemplateCount() < 0
                || !validModelFingerprint(snapshot.getModelFingerprint())) {
            throw new PersistedDataIntegrityException(
                    "FuzzTask", taskId, "modelSnapshotJson", "model snapshot fields are invalid");
        }
    }

    private ModelInputSnapshot readModelInputSnapshot(FuzzTaskPo task) {
        return JsonUtils.readPersistedJsonRequired("FuzzTask", task.getId(), "modelInputSnapshotJson",
                task.getModelInputSnapshotJson(), () -> FuzzModelInputSnapshotCodec.decode(
                        task.getModelInputSnapshotJson()));
    }

    private FuzzEligibilityDto readEligibility(FuzzTaskPo task) {
        requireMetadataFieldSize(task.getId(), "eligibilityJson", task.getEligibilityJson());
        return JsonUtils.readPersistedJsonRequired("FuzzTask", task.getId(), "eligibilityJson",
                task.getEligibilityJson(), () -> JsonUtils.fromJson(
                        task.getEligibilityJson(), FuzzEligibilityDto.class));
    }

    private FuzzEligibilityDto readEligibility(FuzzTaskSummaryProjection task) {
        requireMetadataFieldSize(task.getId(), "eligibilityJson", task.getEligibilityJson());
        return JsonUtils.readPersistedJsonRequired("FuzzTask", task.getId(), "eligibilityJson",
                task.getEligibilityJson(), () -> JsonUtils.fromJson(
                        task.getEligibilityJson(), FuzzEligibilityDto.class));
    }

    private List<String> readLimitations(FuzzTaskPo task) {
        requireMetadataFieldSize(task.getId(), "limitationsJson", task.getLimitationsJson());
        return JsonUtils.readPersistedJsonRequired("FuzzTask", task.getId(), "limitationsJson",
                task.getLimitationsJson(), () -> JsonUtils.fromJson(
                        task.getLimitationsJson(), new TypeReference<List<String>>() {}));
    }

    private List<String> readLimitations(FuzzTaskSummaryProjection task) {
        requireMetadataFieldSize(task.getId(), "limitationsJson", task.getLimitationsJson());
        return JsonUtils.readPersistedJsonRequired("FuzzTask", task.getId(), "limitationsJson",
                task.getLimitationsJson(), () -> JsonUtils.fromJson(
                        task.getLimitationsJson(), new TypeReference<List<String>>() {}));
    }

    private SpecificationDto readViolatedSpec(FuzzFindingPo finding) {
        return JsonUtils.readPersistedJsonRequired("FuzzFinding", finding.getId(), "violatedSpecJson",
                finding.getViolatedSpecJson(), () -> JsonUtils.fromJson(
                        finding.getViolatedSpecJson(), SpecificationDto.class));
    }

    private List<TraceStateDto> readStates(FuzzFindingPo finding) {
        return JsonUtils.readPersistedJsonRequired("FuzzFinding", finding.getId(), "statesJson",
                finding.getStatesJson(), () -> JsonUtils.fromJson(
                        finding.getStatesJson(), new TypeReference<List<TraceStateDto>>() {}));
    }

    private List<FuzzInputEventDto> readInputEvents(FuzzFindingPo finding) {
        return JsonUtils.readPersistedJsonRequired("FuzzFinding", finding.getId(), "inputEventsJson",
                finding.getInputEventsJson(), () -> JsonUtils.fromJson(
                        finding.getInputEventsJson(), new TypeReference<List<FuzzInputEventDto>>() {}));
    }

    private PersistedFindingData readAndValidateFinding(FuzzFindingPo finding) {
        SpecificationDto specification = readViolatedSpec(finding);
        List<TraceStateDto> states = readStates(finding);
        List<FuzzInputEventDto> inputEvents = readInputEvents(finding);
        if (specification == null
                || finding.getViolatedSpecId() == null || finding.getViolatedSpecId().isBlank()
                || specification.getId() == null
                || !finding.getViolatedSpecId().equals(specification.getId())) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "violatedSpecJson",
                    "stored specification identity does not match violatedSpecId");
        }
        if (!isSupportedFiniteSpecification(specification)) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "violatedSpecJson",
                    "finding specification is outside the supported finite template subset");
        }
        if (finding.getSeed() == null || finding.getSeed() < 0
                || finding.getSeed() > FuzzRequestDto.JS_SAFE_SEED_MAX
                || finding.getCreatedAt() == null) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "seed", "finding seed or timestamp is invalid");
        }
        if (states == null || states.isEmpty() || states.stream().anyMatch(java.util.Objects::isNull)) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "statesJson", "state list is empty or contains null");
        }
        if (finding.getStateCount() == null || finding.getStateCount() != states.size()) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "stateCount",
                    "stored state count does not match statesJson");
        }
        Integer violationStep = finding.getFirstViolationStep();
        if (violationStep == null || violationStep != states.size() - 1) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "firstViolationStep",
                    "stored trace is not truncated at the first violation");
        }
        try {
            TraceStateIntegrity.requireValidStates(states, 0);
        } catch (IllegalArgumentException exception) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "statesJson", exception.getMessage());
        }
        if (inputEvents == null) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "inputEventsJson", "input event list is missing");
        }
        int previousStep = -1;
        int previousSourceOrder = -1;
        for (FuzzInputEventDto event : inputEvents) {
            if (event == null || event.getStep() < 0 || event.getStep() > violationStep
                    || !isPersistedInputEventKind(event.getKind())
                    || !isPersistedInputEventSource(event.getSource())
                    || !hasText(event.getTargetId())
                    || !hasText(event.getProperty())
                    || !hasText(event.getValue())) {
                throw new PersistedDataIntegrityException(
                        "FuzzFinding", finding.getId(), "inputEventsJson",
                        "input event is incomplete or outside the finding prefix");
            }
            FuzzInputEventSource source = FuzzInputEventSource.valueOf(event.getSource());
            if (source == FuzzInputEventSource.RANDOM_INITIAL_STATE && event.getStep() != 0) {
                throw new PersistedDataIntegrityException(
                        "FuzzFinding", finding.getId(), "inputEventsJson",
                        "random initial-state events are only valid at step 0");
            }
            int sourceOrder = inputEventSourceOrder(source);
            if (event.getStep() < previousStep
                    || (event.getStep() == previousStep && sourceOrder < previousSourceOrder)) {
                throw new PersistedDataIntegrityException(
                        "FuzzFinding", finding.getId(), "inputEventsJson",
                        "input events are not stored in causal order");
            }
            previousStep = event.getStep();
            previousSourceOrder = sourceOrder;
        }
        return new PersistedFindingData(specification, states, inputEvents);
    }

    private void validateFindingSummaryProjection(FuzzFindingSummaryProjection finding) {
        if (finding == null || finding.getId() == null
                || !hasText(finding.getViolatedSpecId())) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding == null ? null : finding.getId(), "violatedSpecId",
                    "finding summary identity is missing");
        }
        if (finding.getSeed() == null || finding.getSeed() < 0
                || finding.getSeed() > FuzzRequestDto.JS_SAFE_SEED_MAX
                || finding.getCreatedAt() == null) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "seed", "finding seed or timestamp is invalid");
        }
        Integer stateCount = finding.getStateCount();
        Integer violationStep = finding.getFirstViolationStep();
        if (stateCount == null || stateCount < 1 || stateCount > 50
                || violationStep == null || violationStep != stateCount - 1) {
            throw new PersistedDataIntegrityException(
                    "FuzzFinding", finding.getId(), "stateCount",
                    "finding trace is not truncated at the first violation");
        }
    }

    private boolean isPersistedInputEventKind(String value) {
        if (!hasText(value)) return false;
        try {
            FuzzInputEventKind.valueOf(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isPersistedInputEventSource(String value) {
        if (!hasText(value)) return false;
        try {
            FuzzInputEventSource.valueOf(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private int inputEventSourceOrder(FuzzInputEventSource source) {
        return switch (source) {
            case RANDOM_INITIAL_STATE -> 0;
            case SEED_EVENT -> 1;
            case MODEL_CHOICE -> 2;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasValidRunStatisticsRelation(
            Integer iterations,
            Long generatedPaths,
            Integer maxIterations,
            Integer populationSize) {
        if (iterations == null || iterations < 0
                || generatedPaths == null || generatedPaths < 0
                || maxIterations == null || maxIterations < 1
                || populationSize == null || populationSize < 1) {
            return false;
        }
        long maximumGeneratedPaths;
        try {
            maximumGeneratedPaths = Math.multiplyExact((long) iterations, populationSize);
        } catch (ArithmeticException e) {
            return false;
        }
        return iterations <= maxIterations
                && (iterations == 0) == (generatedPaths == 0)
                && (iterations == 0 || generatedPaths >= iterations)
                && generatedPaths <= maximumGeneratedPaths;
    }

    private void validateRunEvidence(FuzzTaskPo task, List<FuzzFindingPo> findings) {
        int actualFindingCount = findings == null ? 0 : findings.size();
        validateRunFindingCount(task, actualFindingCount);
        Set<Long> findingIds = new HashSet<>();
        Set<String> violatedSpecIds = new HashSet<>();
        for (FuzzFindingPo finding : findings == null ? List.<FuzzFindingPo>of() : findings) {
            if (finding == null
                    || finding.getId() == null
                    || !Objects.equals(task.getId(), finding.getFuzzTaskId())
                    || !Objects.equals(task.getUserId(), finding.getUserId())) {
                throw new PersistedDataIntegrityException(
                        "FuzzTask", task.getId(), "findings",
                        "finding ownership does not match the run");
            }
            if (!findingIds.add(finding.getId())
                    || finding.getViolatedSpecId() == null
                    || !violatedSpecIds.add(finding.getViolatedSpecId())) {
                throw new PersistedDataIntegrityException(
                        "FuzzTask", task.getId(), "findings",
                        "finding IDs and violated specification IDs must be unique");
            }
        }
    }

    private void validateRunFindingCount(FuzzTaskPo task, long actualFindingCount) {
        if (task == null) {
            throw new PersistedDataIntegrityException("FuzzTask", null, "task", "run is missing");
        }
        if (actualFindingCount < 0 || actualFindingCount > 100) {
            throw new PersistedDataIntegrityException(
                    "FuzzTask", task.getId(), "findingCount", "a run cannot contain more than 100 findings");
        }
        if (task.getFindingCount() == null || task.getFindingCount() != actualFindingCount) {
            throw new PersistedDataIntegrityException(
                    "FuzzTask", task.getId(), "findingCount",
                    "stored count does not match persisted findings");
        }
        if (task.getOutcome() == null) {
            throw new PersistedDataIntegrityException(
                    "FuzzTask", task.getId(), "outcome", "completed run outcome is missing");
        }
        if (task.getOutcome() == cn.edu.nju.Iot_Verify.dto.fuzz.FuzzOutcome.FOUND_VIOLATION) {
            if (actualFindingCount == 0) {
                throw new PersistedDataIntegrityException(
                        "FuzzTask", task.getId(), "findingCount",
                        "FOUND_VIOLATION requires at least one finding");
            }
        } else if (actualFindingCount != 0) {
            throw new PersistedDataIntegrityException(
                    "FuzzTask", task.getId(), "findingCount",
                    "non-violation outcomes cannot contain findings");
        }
    }

    private PersistedRunWithFindings readAndValidateRun(
            FuzzTaskPo task, List<FuzzFindingPo> findings) {
        validateTaskLifecycle(task);
        validateRunEvidence(task, findings);
        PersistedRunContext context = readAndValidateRunContext(
                task, findings == null ? 0 : findings.size());
        Map<Long, PersistedFindingData> findingsById = new LinkedHashMap<>();
        for (FuzzFindingPo finding : findings == null ? List.<FuzzFindingPo>of() : findings) {
            PersistedFindingData findingData = readAndValidateFinding(finding);
            findingsById.put(finding.getId(), findingData);
            validateFindingAgainstRun(
                    task,
                    finding.getViolatedSpecId(),
                    finding.getSeed(),
                    finding.getCreatedAt(),
                    findingData,
                    context);
        }
        return new PersistedRunWithFindings(context.data(), findingsById);
    }

    private void validateTaskSummaryProjection(FuzzTaskSummaryProjection task) {
        if (task.getId() == null || task.getId() <= 0
                || task.getUserId() == null || task.getUserId() <= 0
                || task.getStatus() == null || task.getCreatedAt() == null
                || task.getExplorationMode() == null
                || task.getProgress() == null || task.getProgress() < 0
                || task.getProgress() > 100) {
            throw invalidProjectedTask(task, "task metadata is invalid");
        }
        if (task.getMaxIterations() == null || task.getMaxIterations() < 1
                || task.getMaxIterations() > 5_000
                || task.getPathLength() == null || task.getPathLength() < 1
                || task.getPathLength() > 50
                || task.getPopulationSize() == null || task.getPopulationSize() < 1
                || task.getPopulationSize() > 50) {
            throw invalidProjectedTask(task, "task budgets are invalid");
        }
        validateProjectedLifecycle(task);
        List<String> targetSpecIds = readTargetSpecIds(task);
        if (!validTargetSpecIds(targetSpecIds)) {
            throw invalidProjectedTask(task, "target specification IDs are invalid");
        }
    }

    private ProjectedRunContext readAndValidateProjectedRunContext(
            FuzzTaskSummaryProjection task, List<FuzzFindingSummaryProjection> findings) {
        validateTaskSummaryProjection(task);
        validateProjectedRunEvidence(task, findings);
        validateProjectedRequiredRunScalars(task);
        ModelRunSnapshotDto modelSnapshot = readModelSnapshot(task);
        List<String> targetSpecIds = readTargetSpecIds(task);
        if (!validTargetSpecIds(targetSpecIds)) {
            throw invalidProjectedRun(task, "targetSpecIdsJson", "target specification IDs are invalid");
        }
        FuzzEligibilityDto eligibility = readEligibility(task);
        requireMetadataSize(task.getId(), task.getEligibilityJson(), task.getLimitationsJson());
        validateProjectedEligibility(task, targetSpecIds, eligibility,
                modelSnapshot.getSpecificationCount());
        List<String> limitations = normalizeLimitations(
                task, readLimitations(task));
        Set<String> eligibleIds = new HashSet<>(eligibility.getEligibleSpecIds());
        for (FuzzFindingSummaryProjection finding
                : findings == null ? List.<FuzzFindingSummaryProjection>of() : findings) {
            validateFindingSummaryProjection(finding);
            validateProjectedFindingAgainstRun(task, finding, eligibleIds);
        }
        if (task.getOutcome() == FuzzOutcome.INCONCLUSIVE && !eligibleIds.isEmpty()) {
            throw invalidProjectedRun(task, "eligibilityJson",
                    "INCONCLUSIVE requires zero eligible specifications");
        }
        if (task.getOutcome() != FuzzOutcome.INCONCLUSIVE && eligibleIds.isEmpty()) {
            throw invalidProjectedRun(task, "eligibilityJson",
                    "search outcomes require an eligible specification");
        }
        return new ProjectedRunContext(
                new ProjectedRunData(targetSpecIds, modelSnapshot, eligibility, limitations),
                eligibleIds);
    }

    private void validateProjectedRunEvidence(
            FuzzTaskSummaryProjection task, List<FuzzFindingSummaryProjection> findings) {
        if (task == null || task.getId() == null || task.getUserId() == null) {
            throw invalidProjectedRun(task, "findings", "run ownership is missing");
        }
        int actualCount = findings == null ? 0 : findings.size();
        if (actualCount > 100 || task.getFindingCount() == null
                || task.getFindingCount() != actualCount) {
            throw invalidProjectedRun(task, "findingCount",
                    "stored count does not match projected findings");
        }
        Set<Long> findingIds = new HashSet<>();
        Set<String> violatedSpecIds = new HashSet<>();
        for (FuzzFindingSummaryProjection finding
                : findings == null ? List.<FuzzFindingSummaryProjection>of() : findings) {
            if (finding == null || finding.getId() == null
                    || !Objects.equals(task.getId(), finding.getFuzzTaskId())
                    || !Objects.equals(task.getUserId(), finding.getUserId())
                    || !findingIds.add(finding.getId())
                    || !hasText(finding.getViolatedSpecId())
                    || !violatedSpecIds.add(finding.getViolatedSpecId())) {
                throw invalidProjectedRun(task, "findings",
                        "finding ownership or identity is invalid");
            }
        }
        if ((task.getOutcome() == FuzzOutcome.FOUND_VIOLATION) != (actualCount > 0)) {
            throw invalidProjectedRun(task, "outcome",
                    "outcome must agree with projected finding count");
        }
    }

    private void validateProjectedRequiredRunScalars(FuzzTaskSummaryProjection task) {
        if (task.getStatus() != FuzzTaskPo.TaskStatus.COMPLETED
                || task.getCreatedAt() == null || task.getCompletedAt() == null
                || task.getCompletedAt().isBefore(task.getCreatedAt())
                || task.getOutcome() == null || task.getExplorationMode() == null
                || task.getEffectiveSeed() == null || task.getEffectiveSeed() < 0
                || task.getEffectiveSeed() > FuzzRequestDto.JS_SAFE_SEED_MAX
                || task.getIterations() == null || task.getIterations() < 0
                || task.getGeneratedPaths() == null || task.getGeneratedPaths() < 0
                || task.getGeneratedPaths() > FuzzRequestDto.JS_SAFE_SEED_MAX
                || task.getElapsedMs() == null || task.getElapsedMs() < 0
                || task.getElapsedMs() > FuzzRequestDto.JS_SAFE_SEED_MAX
                || task.getMaxIterations() == null || task.getMaxIterations() < 1
                || task.getMaxIterations() > 5_000
                || task.getPathLength() == null || task.getPathLength() < 1
                || task.getPathLength() > 50
                || task.getPopulationSize() == null || task.getPopulationSize() < 1
                || task.getPopulationSize() > 50
                || !hasValidRunStatisticsRelation(
                        task.getIterations(), task.getGeneratedPaths(),
                        task.getMaxIterations(), task.getPopulationSize())) {
            throw invalidProjectedRun(task, "statistics", "run metadata is invalid");
        }
    }

    private void validateProjectedEligibility(
            FuzzTaskSummaryProjection task,
            List<String> targetSpecIds,
            FuzzEligibilityDto eligibility,
            int frozenSpecificationCount) {
        if (eligibility == null || eligibility.getEligibleSpecIds() == null
                || eligibility.getIneligibleSpecs() == null
                || eligibility.getEligibleSpecLabels() == null) {
            throw invalidProjectedRun(task, "eligibilityJson", "eligibility data is incomplete");
        }
        List<String> eligibleIds = eligibility.getEligibleSpecIds();
        Set<String> classified = new LinkedHashSet<>();
        for (String id : eligibleIds) {
            if (!hasText(id) || !classified.add(id)) {
                throw invalidProjectedRun(task, "eligibilityJson", "eligible IDs are invalid");
            }
            if (!hasText(eligibility.getEligibleSpecLabels().get(id))
                    || eligibility.getEligibleSpecLabels().get(id).length() > MAX_ELIGIBILITY_LABEL_CHARS
                    || !isCanonicalSingleLine(
                    eligibility.getEligibleSpecLabels().get(id), id, MAX_ELIGIBILITY_LABEL_CHARS)) {
                throw invalidProjectedRun(task, "eligibilityJson", "eligible labels are incomplete");
            }
        }
        if (eligibility.getEligibleSpecLabels().size() != eligibleIds.size()
                || !eligibility.getEligibleSpecLabels().keySet().equals(new HashSet<>(eligibleIds))) {
            throw invalidProjectedRun(task, "eligibilityJson", "eligible labels do not match IDs");
        }
        for (FuzzIneligibleSpecDto item : eligibility.getIneligibleSpecs()) {
            if (item == null || !hasText(item.getSpecId())
                    || !hasText(item.getSpecificationLabel())
                    || item.getSpecificationLabel().length() > MAX_ELIGIBILITY_LABEL_CHARS
                    || !isCanonicalSingleLine(
                    item.getSpecificationLabel(), item.getSpecId(), MAX_ELIGIBILITY_LABEL_CHARS)
                    || !hasText(item.getReasonCode())
                    || item.getReasonCode().length() > MAX_STABLE_CODE_CHARS
                    || !STABLE_CODE.matcher(item.getReasonCode()).matches()
                    || !hasText(item.getReason())
                    || item.getReason().length() > MAX_ELIGIBILITY_REASON_CHARS
                    || !isCanonicalSingleLine(item.getReason(), MAX_ELIGIBILITY_REASON_CHARS)
                    || !classified.add(item.getSpecId())) {
                throw invalidProjectedRun(task, "eligibilityJson", "ineligible details are invalid");
            }
        }
        if (frozenSpecificationCount < 0) {
            throw invalidProjectedRun(task, "modelSnapshotJson", "specification count is invalid");
        }
        Set<String> expectedTargets = targetSpecIds.isEmpty()
                ? new HashSet<>(classified)
                : new HashSet<>(targetSpecIds);
        if (targetSpecIds.isEmpty()
                && (frozenSpecificationCount > 100 || expectedTargets.size() != frozenSpecificationCount)) {
            throw invalidProjectedRun(task, "targetSpecIdsJson",
                    "implicit-all target selection does not match the frozen specification count");
        }
        if (!targetSpecIds.isEmpty() && !classified.equals(expectedTargets)) {
            throw invalidProjectedRun(task, "eligibilityJson",
                    "eligibility does not partition the explicit targets");
        }
        int expectedTargetCount = targetSpecIds.isEmpty() ? frozenSpecificationCount : expectedTargets.size();
        if (eligibility.getRequestedSpecCount() != expectedTargetCount
                || eligibility.getEligibleSpecCount() != eligibleIds.size()
                || classified.size() != expectedTargetCount) {
            throw invalidProjectedRun(task, "eligibilityJson", "eligibility counts are inconsistent");
        }
    }

    private void requireMetadataFieldSize(Long taskId, String field, String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_RUN_METADATA_BYTES) {
            throw new PersistedDataIntegrityException(
                    "FuzzTask", taskId, field, "persisted run metadata exceeds the safety limit");
        }
    }

    private void requireMetadataSize(Long taskId, String eligibilityJson, String limitationsJson) {
        long bytes = (long) (eligibilityJson == null ? 0
                : eligibilityJson.getBytes(StandardCharsets.UTF_8).length)
                + (limitationsJson == null ? 0
                : limitationsJson.getBytes(StandardCharsets.UTF_8).length);
        if (bytes > MAX_RUN_METADATA_BYTES) {
            throw new PersistedDataIntegrityException(
                    "FuzzTask", taskId, "runMetadata", "persisted run metadata exceeds the safety limit");
        }
    }

    private void validateProjectedFindingAgainstRun(
            FuzzTaskSummaryProjection task,
            FuzzFindingSummaryProjection finding,
            Set<String> eligibleIds) {
        if (finding.getStateCount() > task.getPathLength()) {
            throw invalidProjectedRun(task, "findings",
                    "finding state count exceeds the run path-length budget");
        }
        if (!eligibleIds.contains(finding.getViolatedSpecId())
                || !Objects.equals(finding.getSeed(), task.getEffectiveSeed())
                || finding.getCreatedAt() == null || task.getCreatedAt() == null
                || task.getCompletedAt() == null
                || finding.getCreatedAt().isBefore(task.getCreatedAt())
                || finding.getCreatedAt().isAfter(task.getCompletedAt())) {
            throw invalidProjectedRun(task, "findings",
                    "finding does not match projected run metadata");
        }
    }

    private boolean validTargetSpecIds(List<String> targetSpecIds) {
        return targetSpecIds != null && targetSpecIds.size() <= 100
                && targetSpecIds.stream().allMatch(id -> hasText(id) && id.length() <= 100)
                && new HashSet<>(targetSpecIds).size() == targetSpecIds.size();
    }

    private void validatePresentationOwner(FuzzTaskSummaryProjection task,
                                            FuzzSpecificationPresentationProjection presentation) {
        if (presentation == null || !Objects.equals(task.getId(), presentation.getId())) {
            throw invalidProjectedRun(task, "specificationPresentation", "frozen presentation ownership is missing");
        }
    }

    private Map<String, SpecificationDto> readProjectedSpecifications(
            FuzzTaskSummaryProjection task, FuzzSpecificationPresentationProjection presentation,
            ModelRunSnapshotDto snapshot, FuzzEligibilityDto eligibility) {
        validatePresentationOwner(task, presentation);
        List<SpecificationDto> specifications = JsonUtils.readPersistedJsonRequired(
                "FuzzTask", task.getId(), "specificationPresentation", presentation.getSpecificationsJson(),
                () -> JsonUtils.fromJson(presentation.getSpecificationsJson(), new TypeReference<List<SpecificationDto>>() {}));
        if (specifications.size() > 100 || specifications.size() != snapshot.getSpecificationCount()) {
            throw invalidProjectedRun(task, "specificationPresentation", "frozen specification count is invalid");
        }
        Map<String, SpecificationDto> byId = new LinkedHashMap<>();
        for (SpecificationDto specification : specifications) {
            if (!validFrozenSpecification(specification)
                    || byId.putIfAbsent(specification.getId(), specification) != null) {
                throw invalidProjectedRun(task, "specificationPresentation", "frozen specifications are invalid");
            }
        }
        Map<String, SpecificationDto> eligible = new LinkedHashMap<>();
        for (String id : eligibility.getEligibleSpecIds()) {
            SpecificationDto specification = byId.get(id);
            if (!isSupportedFiniteSpecification(specification)) {
                throw invalidProjectedRun(task, "specificationPresentation", "eligible frozen specification is missing or unsupported");
            }
            eligible.put(id, specification);
        }
        return Map.copyOf(eligible);
    }

    private Map<String, ModelTokenSource> readProjectedTokenSources(
            FuzzTaskSummaryProjection task, FuzzSpecificationPresentationProjection presentation,
            ModelRunSnapshotDto snapshot) {
        validatePresentationOwner(task, presentation);
        Map<String, ModelTokenSource> sources = JsonUtils.readPersistedJsonRequired(
                "FuzzTask", task.getId(), "modelTokenSourcesByDeviceId", presentation.getModelTokenSourcesJson(),
                () -> JsonUtils.fromJson(presentation.getModelTokenSourcesJson(), new TypeReference<Map<String, ModelTokenSource>>() {}));
        if (sources.size() > 100 || sources.size() != snapshot.getDeviceCount()
                || sources.entrySet().stream().anyMatch(entry -> !hasText(entry.getKey())
                || entry.getKey().length() > RequestLimits.MAX_IDENTIFIER_LENGTH || entry.getValue() == null)) {
            throw invalidProjectedRun(task, "modelTokenSourcesByDeviceId", "frozen token sources are invalid");
        }
        return Map.copyOf(sources);
    }

    private PersistedDataIntegrityException invalidProjectedTask(
            FuzzTaskSummaryProjection task, String detail) {
        return new PersistedDataIntegrityException(
                "FuzzTask", task == null ? null : task.getId(), "summary", detail);
    }

    private PersistedDataIntegrityException invalidProjectedRun(
            FuzzTaskSummaryProjection task, String field, String detail) {
        return new PersistedDataIntegrityException(
                "FuzzTask", task == null ? null : task.getId(), field, detail);
    }

    private PersistedRunContext readAndValidateRunContext(FuzzTaskPo task, long actualFindingCount) {
        validateRunFindingCount(task, actualFindingCount);
        validateRequiredRunScalars(task);
        ModelInputSnapshot inputSnapshot = readModelInputSnapshot(task);
        List<String> targetSpecIds = readTargetSpecIds(task);
        ModelRunSnapshotDto modelSnapshot = readModelSnapshot(task);
        FuzzEligibilityDto eligibility = readEligibility(task);
        requireMetadataSize(task.getId(), task.getEligibilityJson(), task.getLimitationsJson());
        List<String> limitations = normalizeLimitations(task, readLimitations(task));
        FrozenInputData frozenInput = validateModelInputSnapshot(task, inputSnapshot);
        Map<String, SpecificationDto> specificationsById = frozenInput.specificationsById();
        Set<String> expectedTargetIds = validateTargetSpecIds(
                task, targetSpecIds, specificationsById);
        validateModelSnapshot(task, modelSnapshot, inputSnapshot);
        validateEligibility(task, expectedTargetIds, specificationsById, eligibility);
        if (task.getOutcome() == FuzzOutcome.INCONCLUSIVE && eligibility.getEligibleSpecCount() != 0) {
            throw invalidRun(task, "eligibilityJson", "INCONCLUSIVE requires zero eligible specifications");
        }
        if (task.getOutcome() != FuzzOutcome.INCONCLUSIVE && eligibility.getEligibleSpecCount() == 0) {
            throw invalidRun(task, "eligibilityJson", "search outcomes require an eligible specification");
        }
        Map<String, SpecificationDto> eligibleSpecifications = new LinkedHashMap<>();
        for (String id : eligibility.getEligibleSpecIds()) {
            eligibleSpecifications.put(id, specificationsById.get(id));
        }
        PersistedRunData data = new PersistedRunData(
                targetSpecIds, modelSnapshot, frozenInput.playbackScene(), eligibility, limitations,
                Map.copyOf(eligibleSpecifications), frozenInput.tokenSources().deviceSources());
        return new PersistedRunContext(
                data, specificationsById, new HashSet<>(eligibility.getEligibleSpecIds()),
                frozenInput.rules(), frozenInput.tokenSources());
    }

    private void validateFindingAgainstRun(
            FuzzTaskPo task,
            String violatedSpecId,
            Long seed,
            java.time.LocalDateTime createdAt,
            PersistedFindingData findingData,
            PersistedRunContext context) {
        if (findingData.states().size() > task.getPathLength()) {
            throw invalidRun(task, "findings",
                    "finding state count exceeds the run path-length budget");
        }
        SpecificationDto frozenSpecification = context.specificationsById().get(violatedSpecId);
        if (!context.eligibleIds().contains(violatedSpecId)
                || frozenSpecification == null
                || !Objects.equals(frozenSpecification, findingData.specification())
                || !Objects.equals(task.getEffectiveSeed(), seed)
                || createdAt.isBefore(task.getCreatedAt())
                || createdAt.isAfter(task.getCompletedAt())) {
            throw invalidRun(task, "findings",
                    "findings must match eligible frozen specifications, ownership, time, and run seed");
        }
        try {
            TraceStateIntegrity.requireRuleEvidenceMatchesFrozenRules(
                    findingData.states(), context.rules());
        } catch (IllegalArgumentException exception) {
            throw invalidRun(task, "findings", exception.getMessage());
        }
        validateFindingTokenSources(task, findingData.states(), context.tokenSources());
    }

    private void validateRequiredRunScalars(FuzzTaskPo task) {
        if (task.getStatus() != FuzzTaskPo.TaskStatus.COMPLETED
                || task.getCreatedAt() == null
                || task.getCompletedAt() == null
                || task.getCompletedAt().isBefore(task.getCreatedAt())
                || task.getExplorationMode() == null) {
            throw invalidRun(task, "completedAt", "completed run timestamps or status are invalid");
        }
        if (task.getEffectiveSeed() == null || task.getEffectiveSeed() < 0
                || task.getEffectiveSeed() > FuzzRequestDto.JS_SAFE_SEED_MAX) {
            throw invalidRun(task, "effectiveSeed", "effective seed is missing or outside the safe range");
        }
        if (task.getIterations() == null || task.getIterations() < 0
                || task.getGeneratedPaths() == null || task.getGeneratedPaths() < 0
                || task.getGeneratedPaths() > FuzzRequestDto.JS_SAFE_SEED_MAX
                || task.getElapsedMs() == null || task.getElapsedMs() < 0
                || task.getElapsedMs() > FuzzRequestDto.JS_SAFE_SEED_MAX) {
            throw invalidRun(task, "statistics", "run statistics are missing or invalid");
        }
        if (task.getMaxIterations() == null || task.getMaxIterations() < 1 || task.getMaxIterations() > 5_000
                || task.getPathLength() == null || task.getPathLength() < 1 || task.getPathLength() > 50
                || task.getPopulationSize() == null || task.getPopulationSize() < 1
                || task.getPopulationSize() > 50
                || !hasValidRunStatisticsRelation(
                        task.getIterations(), task.getGeneratedPaths(),
                        task.getMaxIterations(), task.getPopulationSize())) {
            throw invalidRun(task, "budgets", "run budgets or iteration count are invalid");
        }
    }

    private void validateTaskLifecycle(FuzzTaskPo task) {
        if (task == null) {
            throw new PersistedDataIntegrityException("FuzzTask", null, "task", "task is missing");
        }
        if (task.getId() == null || task.getId() < 1) {
            throw invalidTask(task, "id", "task id is missing or invalid");
        }
        if (task.getUserId() == null || task.getUserId() < 1) {
            throw invalidTask(task, "userId", "task owner is missing or invalid");
        }
        if (task.getInitiator() == null) {
            throw invalidTask(task, "initiator", "task initiator is missing");
        }
        if (task.getStatus() == null) {
            throw invalidTask(task, "status", "task status is missing");
        }
        if (task.getCreatedAt() == null) {
            throw invalidTask(task, "createdAt", "task creation time is missing");
        }
        if (task.getProgress() == null || task.getProgress() < 0 || task.getProgress() > 100) {
            throw invalidTask(task, "progress", "task progress must be between zero and 100");
        }
        if (task.getProcessingTimeMs() != null && task.getProcessingTimeMs() < 0) {
            throw invalidTask(task, "processingTimeMs", "processing time cannot be negative");
        }
        if (task.getFindingCount() == null || task.getFindingCount() < 0) {
            throw invalidTask(task, "findingCount", "finding count is missing or invalid");
        }
        if (task.getMaxIterations() == null || task.getMaxIterations() < 1
                || task.getMaxIterations() > 5_000
                || task.getPathLength() == null || task.getPathLength() < 1
                || task.getPathLength() > 50
                || task.getPopulationSize() == null || task.getPopulationSize() < 1
                || task.getPopulationSize() > 50) {
            throw invalidTask(task, "budgets", "task budgets are invalid");
        }
        if (task.getSeed() != null
                && (task.getSeed() < 0 || task.getSeed() > FuzzRequestDto.JS_SAFE_SEED_MAX)) {
            throw invalidTask(task, "seed", "requested seed is outside the safe range");
        }
        validateTimeOrder(task);

        FuzzTaskPo.TaskStatus status = task.getStatus();
        boolean terminal = status == FuzzTaskPo.TaskStatus.COMPLETED
                || status == FuzzTaskPo.TaskStatus.FAILED
                || status == FuzzTaskPo.TaskStatus.CANCELLED;
        if (status == FuzzTaskPo.TaskStatus.PENDING && task.getStartedAt() != null) {
            throw invalidTask(task, "startedAt", "pending task cannot have a start time");
        }
        if ((status == FuzzTaskPo.TaskStatus.RUNNING || status == FuzzTaskPo.TaskStatus.COMPLETED)
                && task.getStartedAt() == null) {
            throw invalidTask(task, "startedAt", "running or completed task start time is missing");
        }
        if (terminal) {
            if (task.getCompletedAt() == null) {
                throw invalidTask(task, "completedAt", "terminal task completion time is missing");
            }
            // Only a COMPLETED run finished its work, so only it must report 100. A cancelled or
            // failed run keeps the progress its last heartbeat reported; requiring 100 of every
            // terminal status made such a row unreadable — the task endpoint and the whole task list
            // answered HTTP 500 for a run the user had simply cancelled partway.
            if (status == FuzzTaskPo.TaskStatus.COMPLETED && task.getProgress() != 100) {
                throw invalidTask(task, "progress", "completed task progress must be 100");
            }
        } else if (task.getCompletedAt() != null) {
            throw invalidTask(task, "completedAt", "active task cannot have a completion time");
        }
        if (status == FuzzTaskPo.TaskStatus.FAILED
                && (task.getErrorMessage() == null || task.getErrorMessage().isBlank())) {
            throw invalidTask(task, "errorMessage", "failed task error message is missing");
        }
        if (status == FuzzTaskPo.TaskStatus.COMPLETED) {
            if (task.getProcessingTimeMs() == null) {
                throw invalidTask(task, "processingTimeMs", "completed task processing time is missing");
            }
            if (task.getOutcome() == null) {
                throw invalidTask(task, "outcome", "completed task outcome is missing");
            }
        } else if (task.getOutcome() != null) {
            throw invalidTask(task, "outcome", "non-completed task cannot have a run outcome");
        }
    }

    private void validateTimeOrder(FuzzTaskPo task) {
        if (task.getStartedAt() != null && task.getStartedAt().isBefore(task.getCreatedAt())) {
            throw invalidTask(task, "startedAt", "task start time precedes creation time");
        }
        if (task.getCompletedAt() != null
                && (task.getCompletedAt().isBefore(task.getCreatedAt())
                || (task.getStartedAt() != null && task.getCompletedAt().isBefore(task.getStartedAt())))) {
            throw invalidTask(task, "completedAt", "task completion time is out of order");
        }
        if (task.getProcessingTimeMs() != null
                && (task.getStartedAt() == null || task.getCompletedAt() == null)) {
            throw invalidTask(task, "processingTimeMs",
                    "processing time requires start and completion times");
        }
    }

    private void validateProjectedLifecycle(FuzzTaskSummaryProjection task) {
        if (task.getInitiator() == null) {
            throw invalidProjectedTask(task, "task initiator is missing");
        }
        FuzzTaskPo.TaskStatus status = task.getStatus();
        boolean terminal = status == FuzzTaskPo.TaskStatus.COMPLETED
                || status == FuzzTaskPo.TaskStatus.FAILED
                || status == FuzzTaskPo.TaskStatus.CANCELLED;
        if (task.getProcessingTimeMs() != null && task.getProcessingTimeMs() < 0) {
            throw invalidProjectedTask(task, "processing time cannot be negative");
        }
        if (task.getFindingCount() == null || task.getFindingCount() < 0) {
            throw invalidProjectedTask(task, "finding count is missing or invalid");
        }
        if (task.getStartedAt() != null && task.getStartedAt().isBefore(task.getCreatedAt())) {
            throw invalidProjectedTask(task, "task start time precedes creation time");
        }
        if (task.getCompletedAt() != null
                && (task.getCompletedAt().isBefore(task.getCreatedAt())
                || (task.getStartedAt() != null && task.getCompletedAt().isBefore(task.getStartedAt())))) {
            throw invalidProjectedTask(task, "task completion time is out of order");
        }
        if (task.getProcessingTimeMs() != null
                && (task.getStartedAt() == null || task.getCompletedAt() == null)) {
            throw invalidProjectedTask(task, "processing time requires start and completion times");
        }
        if (status == FuzzTaskPo.TaskStatus.PENDING && task.getStartedAt() != null) {
            throw invalidProjectedTask(task, "pending task cannot have a start time");
        }
        if ((status == FuzzTaskPo.TaskStatus.RUNNING || status == FuzzTaskPo.TaskStatus.COMPLETED)
                && task.getStartedAt() == null) {
            throw invalidProjectedTask(task, "running or completed task start time is missing");
        }
        if (terminal) {
            // Progress bound is already checked upstream; only COMPLETED must claim finished work.
            if (task.getCompletedAt() == null
                    || (status == FuzzTaskPo.TaskStatus.COMPLETED && task.getProgress() != 100)) {
                throw invalidProjectedTask(task, "terminal task completion metadata is invalid");
            }
        } else if (task.getCompletedAt() != null) {
            throw invalidProjectedTask(task, "active task cannot have a completion time");
        }
        if (status == FuzzTaskPo.TaskStatus.FAILED
                && (task.getErrorMessage() == null || task.getErrorMessage().isBlank())) {
            throw invalidProjectedTask(task, "failed task error message is missing");
        }
        if (status == FuzzTaskPo.TaskStatus.COMPLETED) {
            if (task.getProcessingTimeMs() == null || task.getOutcome() == null) {
                throw invalidProjectedTask(task, "completed task result metadata is incomplete");
            }
        } else if (task.getOutcome() != null) {
            throw invalidProjectedTask(task, "non-completed task cannot have a run outcome");
        }
    }

    private PersistedDataIntegrityException invalidTask(
            FuzzTaskPo task, String field, String detail) {
        return new PersistedDataIntegrityException(
                "FuzzTask", task == null ? null : task.getId(), field, detail);
    }

    private FuzzExplorationMode explorationMode(FuzzTaskPo task) {
        if (task == null || task.getExplorationMode() == null) {
            throw new PersistedDataIntegrityException(
                    "FuzzTask", task == null ? null : task.getId(), "explorationMode",
                    "persisted exploration mode is missing");
        }
        return task.getExplorationMode();
    }

    private FuzzExplorationMode explorationMode(FuzzTaskSummaryProjection task) {
        if (task == null || task.getExplorationMode() == null) {
            throw invalidProjectedTask(task, "persisted exploration mode is missing");
        }
        return task.getExplorationMode();
    }

    private FrozenInputData validateModelInputSnapshot(
            FuzzTaskPo task, ModelInputSnapshot snapshot) {
        if (snapshot == null || snapshot.devices() == null || snapshot.rules() == null
                || snapshot.nodes() == null
                || snapshot.specifications() == null || snapshot.environmentVariables() == null
                || snapshot.templateManifests() == null || snapshot.specifications().isEmpty()) {
            throw invalidRun(task, "modelInputSnapshotJson", "frozen model input is missing required data");
        }
        Map<String, SpecificationDto> specificationsById = new LinkedHashMap<>();
        for (SpecificationDto specification : snapshot.specifications()) {
            if (!validFrozenSpecification(specification)
                    || specificationsById.putIfAbsent(specification.getId(), specification) != null) {
                throw invalidRun(task, "modelInputSnapshotJson",
                        "frozen specifications have invalid semantics or duplicate identities");
            }
        }
        if (snapshot.rules().stream().anyMatch(Objects::isNull)) {
            throw invalidRun(task, "modelInputSnapshotJson", "frozen rules contain null");
        }
        try {
            return new FrozenInputData(
                    specificationsById,
                    List.copyOf(snapshot.rules()),
                    ModelPlaybackSceneSnapshot.canonicalize(
                            snapshot.nodes(), snapshot.devices(), snapshot.rules()),
                    frozenTokenSources(task, snapshot));
        } catch (IllegalArgumentException exception) {
            throw invalidRun(task, "modelInputSnapshotJson", exception.getMessage());
        }
    }

    private FrozenTokenSources frozenTokenSources(FuzzTaskPo task, ModelInputSnapshot snapshot) {
        Map<String, ModelTokenSource> deviceSources = new LinkedHashMap<>();
        Map<String, Set<String>> localVariablesByDevice = new LinkedHashMap<>();
        Map<String, ModelTokenSource> environmentSources = new LinkedHashMap<>();
        Map<String, DeviceManifest> manifestsByNormalizedName = new LinkedHashMap<>();
        snapshot.templateManifests().forEach((name, manifest) -> {
            if (hasText(name) && manifest != null) {
                manifestsByNormalizedName.putIfAbsent(
                        name.trim().toLowerCase(Locale.ROOT), manifest);
            }
        });
        for (DeviceVerificationDto device : snapshot.devices()) {
            if (device == null || !hasText(device.getVarName()) || !hasText(device.getTemplateName())) {
                throw invalidRun(task, "modelInputSnapshotJson", "frozen device identity is invalid");
            }
            String deviceId = device.getVarName().trim();
            ModelTokenSource source = device.getModelTokenSource();
            if (source == null) {
                throw invalidRun(task, "modelInputSnapshotJson",
                        "frozen device token provenance is missing");
            }
            if (deviceSources.putIfAbsent(deviceId, source) != null) {
                throw invalidRun(task, "modelInputSnapshotJson", "frozen device identities are duplicate");
            }
            DeviceManifest manifest = manifestsByNormalizedName.get(
                    device.getTemplateName().trim().toLowerCase(Locale.ROOT));
            if (manifest == null) {
                throw invalidRun(task, "modelInputSnapshotJson",
                        "frozen device template manifest is missing");
            }
            Set<String> locals = new LinkedHashSet<>();
            Set<String> environment = new LinkedHashSet<>();
            for (DeviceManifest.InternalVariable variable : safe(manifest.getInternalVariables())) {
                if (variable == null || !hasText(variable.getName())) {
                    throw invalidRun(task, "modelInputSnapshotJson",
                            "frozen template variables are invalid");
                }
                if (Boolean.TRUE.equals(variable.getIsInside())) {
                    if (!locals.add(variable.getName())) {
                        throw invalidRun(task, "modelInputSnapshotJson",
                                "frozen template local variables are duplicate");
                    }
                } else {
                    environment.add(variable.getName());
                }
            }
            for (String variable : safe(manifest.getImpactedVariables())) {
                if (!hasText(variable)) {
                    throw invalidRun(task, "modelInputSnapshotJson",
                            "frozen template impacted variables are invalid");
                }
                environment.add(variable);
            }
            localVariablesByDevice.put(deviceId, Set.copyOf(locals));
            for (String name : environment) {
                ModelTokenSource existing = environmentSources.get(name);
                environmentSources.put(name, existing == null || existing == source
                        ? source : ModelTokenSource.UNKNOWN);
            }
        }
        return new FrozenTokenSources(
                Map.copyOf(deviceSources), Map.copyOf(localVariablesByDevice),
                Map.copyOf(environmentSources));
    }

    private void validateFindingTokenSources(
            FuzzTaskPo task, List<TraceStateDto> states, FrozenTokenSources expected) {
        for (TraceStateDto state : states) {
            Map<String, TraceDeviceDto> devicesById = new LinkedHashMap<>();
            for (TraceDeviceDto device : safe(state.getDevices())) {
                if (device == null || !hasText(device.getDeviceId())
                        || devicesById.putIfAbsent(device.getDeviceId(), device) != null) {
                    throw invalidRun(task, "findings", "finding device provenance identities are invalid");
                }
            }
            if (!devicesById.keySet().equals(expected.deviceSources().keySet())) {
                throw invalidRun(task, "findings",
                        "finding device provenance does not match the frozen model input");
            }
            for (Map.Entry<String, TraceDeviceDto> entry : devicesById.entrySet()) {
                String deviceId = entry.getKey();
                ModelTokenSource source = expected.deviceSources().get(deviceId);
                TraceDeviceDto device = entry.getValue();
                if (device.getModelTokenSource() != source) {
                    throw invalidRun(task, "findings",
                            "finding device provenance does not match the frozen model input");
                }
                if (device.getVariables() == null) {
                    throw invalidRun(task, "findings",
                            "finding device provenance does not match the frozen model input");
                }
                Set<String> expectedLocals = expected.localVariablesByDevice().get(deviceId);
                Set<String> localNames = new LinkedHashSet<>();
                for (TraceVariableDto variable : device.getVariables()) {
                    if (variable == null || !hasText(variable.getName())
                            || !localNames.add(variable.getName())
                            || !expectedLocals.contains(variable.getName())) {
                        throw invalidRun(task, "findings",
                                "finding local-variable provenance does not match the frozen model input");
                    }
                    if (variable.getModelTokenSource() != source) {
                        throw invalidRun(task, "findings",
                                "finding local-variable provenance does not match the frozen model input");
                    }
                }
                if (!localNames.equals(expectedLocals)) {
                    throw invalidRun(task, "findings",
                            "finding local variables do not match the frozen model input");
                }
            }
            if (state.getEnvVariables() == null) {
                if (!expected.environmentSources().isEmpty()) {
                    throw invalidRun(task, "findings", "finding environment provenance is missing");
                }
                continue;
            }
            Set<String> environmentNames = new LinkedHashSet<>();
            for (TraceVariableDto variable : state.getEnvVariables()) {
                ModelTokenSource source = variable == null || !hasText(variable.getName())
                        ? null : expected.environmentSources().get(variable.getName());
                if (variable == null || !environmentNames.add(variable.getName())
                        || source == null) {
                    throw invalidRun(task, "findings",
                            "finding environment provenance does not match the frozen model input");
                }
                if (variable.getModelTokenSource() != source) {
                    throw invalidRun(task, "findings",
                            "finding environment provenance does not match the frozen model input");
                }
            }
            if (!environmentNames.equals(expected.environmentSources().keySet())) {
                throw invalidRun(task, "findings",
                        "finding environment variables do not match the frozen model input");
            }
        }
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Set<String> validateTargetSpecIds(
            FuzzTaskPo task,
            List<String> targetSpecIds,
            Map<String, SpecificationDto> specificationsById) {
        if (targetSpecIds == null || targetSpecIds.size() > 100
                || targetSpecIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 100)
                || new HashSet<>(targetSpecIds).size() != targetSpecIds.size()) {
            throw invalidRun(task, "targetSpecIdsJson", "target specification IDs are invalid");
        }
        Set<String> expectedTargetIds = targetSpecIds.isEmpty()
                ? new HashSet<>(specificationsById.keySet())
                : new HashSet<>(targetSpecIds);
        if (targetSpecIds.isEmpty() && specificationsById.size() > 100) {
            throw invalidRun(task, "targetSpecIdsJson",
                    "implicit-all target selection exceeds the supported target count");
        }
        if (!specificationsById.keySet().containsAll(expectedTargetIds)) {
            throw invalidRun(task, "targetSpecIdsJson",
                    "target specification IDs do not belong to the frozen model input");
        }
        return expectedTargetIds;
    }

    private void validateModelSnapshot(
            FuzzTaskPo task, ModelRunSnapshotDto snapshot, ModelInputSnapshot inputSnapshot) {
        if (snapshot.getDeviceCount() != inputSnapshot.devices().size()
                || snapshot.getRuleCount() != inputSnapshot.rules().size()
                || snapshot.getSpecificationCount() != inputSnapshot.specifications().size()
                || snapshot.getEnvironmentVariableCount() != inputSnapshot.environmentVariables().size()
                || snapshot.getDeviceTemplateCount() != inputSnapshot.templateManifests().size()
                || !Objects.equals(snapshot.getModelFingerprint(),
                        modelFingerprint.modelFingerprint(inputSnapshot))) {
            throw invalidRun(task, "modelSnapshotJson", "model snapshot fields are invalid");
        }
    }

    private boolean validModelFingerprint(String fingerprint) {
        return fingerprint != null && MODEL_FINGERPRINT.matcher(fingerprint).matches();
    }

    private void validateEligibility(FuzzTaskPo task,
                                     Set<String> expectedTargetIds,
                                     Map<String, SpecificationDto> specificationsById,
                                     FuzzEligibilityDto eligibility) {
        if (eligibility == null) {
            throw invalidRun(task, "eligibilityJson", "eligibility data is missing");
        }
        List<String> eligibleIds = eligibility.getEligibleSpecIds();
        List<FuzzIneligibleSpecDto> ineligible = eligibility.getIneligibleSpecs();
        if (eligibleIds == null || ineligible == null
                || eligibleIds.size() > 100 || ineligible.size() > 100
                || eligibleIds.stream().anyMatch(id -> id == null || id.isBlank())
                || new HashSet<>(eligibleIds).size() != eligibleIds.size()) {
            throw invalidRun(task, "eligibilityJson", "eligible specification IDs are invalid");
        }
        Map<String, String> expectedLabels = new LinkedHashMap<>();
        for (String eligibleId : eligibleIds) {
            expectedLabels.put(eligibleId, specificationLabel(specificationsById.get(eligibleId)));
        }
        Map<String, String> labels = eligibility.getEligibleSpecLabels();
        if (labels == null || !labels.equals(expectedLabels)) {
            throw invalidRun(task, "eligibilityJson",
                    "eligible specification labels do not match the frozen model input");
        }
        Set<String> classifiedIds = new HashSet<>(eligibleIds);
        for (String eligibleId : eligibleIds) {
            SpecificationDto specification = specificationsById.get(eligibleId);
            if (!expectedTargetIds.contains(eligibleId)
                    || !isSupportedFiniteSpecification(specification)) {
                throw invalidRun(task, "eligibilityJson",
                        "eligible specifications must be supported targets from the frozen model input");
            }
        }
        for (FuzzIneligibleSpecDto item : ineligible) {
            SpecificationDto specification = item == null
                    ? null : specificationsById.get(item.getSpecId());
            if (item == null || !hasText(item.getSpecId())
                    || !hasText(item.getReasonCode())
                    || item.getReasonCode().length() > MAX_STABLE_CODE_CHARS
                    || !STABLE_CODE.matcher(item.getReasonCode()).matches()
                    || !hasText(item.getReason())
                    || item.getReason().length() > MAX_ELIGIBILITY_REASON_CHARS
                    || !isCanonicalSingleLine(item.getReason(), MAX_ELIGIBILITY_REASON_CHARS)
                    || !expectedTargetIds.contains(item.getSpecId())
                    || !Objects.equals(item.getSpecificationLabel(), specificationLabel(specification))
                    || !classifiedIds.add(item.getSpecId())) {
                throw invalidRun(task, "eligibilityJson", "ineligible specification details are invalid");
            }
        }
        int expectedRequestedCount = expectedTargetIds.size();
        if (eligibility.getRequestedSpecCount() != expectedRequestedCount
                || eligibility.getEligibleSpecCount() != eligibleIds.size()
                || expectedRequestedCount != eligibleIds.size() + ineligible.size()
                || !classifiedIds.equals(expectedTargetIds)) {
            throw invalidRun(task, "eligibilityJson", "eligibility counts do not match their lists");
        }
    }

    private String specificationLabel(SpecificationDto specification) {
        if (specification == null) return null;
        String raw = hasText(specification.getTemplateLabel())
                ? specification.getTemplateLabel()
                : hasText(specification.getFormula()) ? specification.getFormula() : specification.getId();
        return FuzzMetadataPolicy.boundedSingleLine(
                raw, specification.getId(), MAX_ELIGIBILITY_LABEL_CHARS);
    }

    private boolean validFrozenSpecification(SpecificationDto specification) {
        return specification != null
                && validator.validate(specification, SpecificationDto.FrozenModel.class).isEmpty();
    }

    private boolean isSupportedFiniteSpecification(SpecificationDto specification) {
        if (specification == null || !hasText(specification.getId())) return false;
        List<?> aConditions = specification.getAConditions();
        List<?> ifConditions = specification.getIfConditions();
        List<?> thenConditions = specification.getThenConditions();
        if (aConditions == null || ifConditions == null || thenConditions == null
                || aConditions.stream().anyMatch(Objects::isNull)
                || ifConditions.stream().anyMatch(Objects::isNull)
                || thenConditions.stream().anyMatch(Objects::isNull)) {
            return false;
        }
        String templateId = specification.getTemplateId();
        if (templateId == null) return false;
        return switch (templateId) {
            case "1", "3" -> !aConditions.isEmpty() && ifConditions.isEmpty() && thenConditions.isEmpty();
            case "4" -> aConditions.isEmpty() && !ifConditions.isEmpty() && !thenConditions.isEmpty();
            default -> false;
        };
    }

    private List<String> normalizeLimitations(FuzzTaskPo task, List<String> limitations) {
        if (limitations == null
                || limitations.size() > FuzzMetadataPolicy.MAX_LIMITATION_CODES
                || limitations.stream().anyMatch(code -> code == null
                || code.length() > MAX_STABLE_CODE_CHARS || !STABLE_CODE.matcher(code).matches())
                || new HashSet<>(limitations).size() != limitations.size()) {
            throw invalidRun(task, "limitationsJson", "limitation codes are invalid");
        }
        FuzzExplorationMode mode = explorationMode(task);
        if (!limitations.containsAll(FuzzLimitationContract.requiredCodes(mode))) {
            throw invalidRun(task, "limitationsJson", "required semantic limitation codes are missing");
        }
        return List.copyOf(new LinkedHashSet<>(limitations));
    }

    private List<String> normalizeLimitations(
            FuzzTaskSummaryProjection task, List<String> limitations) {
        if (limitations == null
                || limitations.size() > FuzzMetadataPolicy.MAX_LIMITATION_CODES
                || limitations.stream().anyMatch(
                 code -> code == null || code.length() > MAX_STABLE_CODE_CHARS
                         || !STABLE_CODE.matcher(code).matches())
                || new HashSet<>(limitations).size() != limitations.size()) {
            throw invalidProjectedRun(task, "limitationsJson", "limitation codes are invalid");
        }
        FuzzExplorationMode mode = explorationMode(task);
        if (!limitations.containsAll(FuzzLimitationContract.requiredCodes(mode))) {
            throw invalidProjectedRun(task, "limitationsJson",
                    "required semantic limitation codes are missing");
        }
        return List.copyOf(new LinkedHashSet<>(limitations));
    }

    private boolean isCanonicalSingleLine(String value, int maxChars) {
        return isCanonicalSingleLine(
                value, "Unsupported for counterexample exploration", maxChars);
    }

    private boolean isCanonicalSingleLine(String value, String fallback, int maxChars) {
        return value.equals(FuzzMetadataPolicy.boundedSingleLine(value, fallback, maxChars));
    }

    private PersistedDataIntegrityException invalidRun(FuzzTaskPo task, String field, String detail) {
        return new PersistedDataIntegrityException("FuzzTask", task.getId(), field, detail);
    }

    private record PersistedFindingData(
            SpecificationDto specification,
            List<TraceStateDto> states,
            List<FuzzInputEventDto> inputEvents) {
    }

    private record PersistedRunData(
            List<String> targetSpecIds,
            ModelRunSnapshotDto modelSnapshot,
            ModelPlaybackSceneDto playbackScene,
            FuzzEligibilityDto eligibility,
            List<String> limitations,
            Map<String, SpecificationDto> eligibleSpecifications,
            Map<String, ModelTokenSource> modelTokenSourcesByDeviceId) {
    }

    private record PersistedRunContext(
            PersistedRunData data,
            Map<String, SpecificationDto> specificationsById,
            Set<String> eligibleIds,
            List<RuleDto> rules,
            FrozenTokenSources tokenSources) {
    }

    private record FrozenInputData(
            Map<String, SpecificationDto> specificationsById,
            List<RuleDto> rules,
            ModelPlaybackSceneDto playbackScene,
            FrozenTokenSources tokenSources) {
    }

    private record FrozenTokenSources(
            Map<String, ModelTokenSource> deviceSources,
            Map<String, Set<String>> localVariablesByDevice,
            Map<String, ModelTokenSource> environmentSources) {
    }

    private record PersistedRunWithFindings(
            PersistedRunData data,
            Map<Long, PersistedFindingData> findingsById) {
    }

    private record ValidatedFindingReplay(
            PersistedRunContext context,
            PersistedFindingData findingData) {
    }

    private record ProjectedRunData(
            List<String> targetSpecIds,
            ModelRunSnapshotDto modelSnapshot,
            FuzzEligibilityDto eligibility,
            List<String> limitations) {
    }

    private record ProjectedRunContext(
            ProjectedRunData data,
            Set<String> eligibleIds) {
    }
}
