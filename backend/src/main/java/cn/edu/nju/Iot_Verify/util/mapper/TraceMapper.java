package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.trace.*;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTraceContextProjection;
import cn.edu.nju.Iot_Verify.repository.projection.TraceSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.TraceTokenSourceProjection;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.model.ModelPlaybackSceneDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.PersistedModelContextIntegrity;
import cn.edu.nju.Iot_Verify.util.ModelPlaybackSceneSnapshot;
import cn.edu.nju.Iot_Verify.util.TraceStateIntegrity;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import cn.edu.nju.Iot_Verify.util.SmvConstants;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Trace PO 与 DTO 之间的转换映射器
 */
@Component
public class TraceMapper {

    /**
     * TracePo -> TraceDto
     */
    public TraceDto toDto(TracePo tracePo, VerificationTraceContextProjection run) {
        PersistedContext context = readContext(tracePo, run);
        return context != null ? context.trace() : null;
    }

    /** Decodes the frozen request once while retaining every trace-mapping integrity check. */
    public PersistedContext readContext(TracePo tracePo, VerificationTraceContextProjection run) {
        if (tracePo == null) {
            return null;
        }
        requireOwner(tracePo, run);
        return readContext(tracePo, readRunContext(run));
    }

    private PersistedContext readContext(TracePo tracePo, RunContext run) {
        PersistedModelContextIntegrity.ValidatedContext context = run.model();
        VerificationRequestDto frozenRequest = run.request();
        List<RuleDto> frozenRules = run.rules();
        TraceDto dto = new TraceDto();
        dto.setId(tracePo.getId());
        dto.setUserId(tracePo.getUserId());
        dto.setVerificationTaskId(requireVerificationTaskId(tracePo.getId(), tracePo.getVerificationTaskId()));
        dto.setViolatedSpecId(tracePo.getViolatedSpecId());
        dto.setViolatedSpecJson(tracePo.getViolatedSpecJson());
        dto.setViolatedSpec(readSpecification(tracePo.getId(), tracePo.getViolatedSpecJson()));
        dto.setCheckedExpression(tracePo.getCheckedExpression());
        dto.setModelSnapshot(context.modelSnapshot());
        dto.setPlaybackScene(run.playbackScene());
        dto.setDisabledRuleCount(run.disabledRuleCount());
        dto.setSkippedSpecCount(run.skippedSpecCount());
        dto.setGenerationIssues(run.generationIssues());
        dto.setModelComplete(run.disabledRuleCount() == 0 && run.skippedSpecCount() == 0);
        dto.setCreatedAt(tracePo.getCreatedAt());

        applyPersistedModelContext(dto, context);

        dto.setStates(readRequiredStates(
                tracePo.getId(), tracePo.getStatesJson(), tracePo.getStateCount(), frozenRules));

        return new PersistedContext(dto, frozenRequest, run.templateSnapshotsJson());
    }

    public record PersistedContext(TraceDto trace, VerificationRequestDto request, String templateSnapshotsJson) {
    }

    private void applyPersistedModelContext(
            TraceDto dto, PersistedModelContextIntegrity.ValidatedContext context) {
        dto.setAttack(context.isAttack());
        dto.setAttackBudget(context.attackBudget());
        dto.setEnablePrivacy(context.enablePrivacy());
        dto.setModelSemantics(context.modelSemantics());
    }

    /**
     * TraceDto -> TracePo
     */
    public TracePo toEntity(TraceDto traceDto) {
        if (traceDto == null) {
            return null;
        }
        TracePo po = new TracePo();
        po.setId(traceDto.getId());
        po.setUserId(traceDto.getUserId());
        po.setVerificationTaskId(requireVerificationTaskId(traceDto.getId(), traceDto.getVerificationTaskId()));
        po.setViolatedSpecId(traceDto.getViolatedSpecId() != null
                ? traceDto.getViolatedSpecId()
                : SmvConstants.UNKNOWN_VIOLATED_SPEC_ID);
        po.setViolatedSpecJson(traceDto.getViolatedSpecJson() != null
                ? traceDto.getViolatedSpecJson()
                : JsonUtils.toJson(traceDto.getViolatedSpec()));
        po.setCheckedExpression(traceDto.getCheckedExpression());
        po.setCreatedAt(traceDto.getCreatedAt());

        if (traceDto.getStates() != null && !traceDto.getStates().isEmpty()) {
            po.setStatesJson(JsonUtils.toJson(traceDto.getStates()));
            po.setStateCount(traceDto.getStates().size());
        } else {
            po.setStatesJson("[]");
            po.setStateCount(0);
        }

        return po;
    }

    /**
     * List<TracePo> -> List<TraceDto>
     */
    public List<TraceDto> toDtoList(
            List<TracePo> tracePoList, Map<Long, VerificationTraceContextProjection> runs) {
        Map<Long, RunContext> decodedRuns = new HashMap<>();
        return tracePoList.stream().map(trace -> {
            Long runId = requireVerificationTaskId(trace.getId(), trace.getVerificationTaskId());
            VerificationTraceContextProjection owner = runs.get(runId);
            requireOwner(trace, owner);
            RunContext context = decodedRuns.computeIfAbsent(runId, ignored -> readRunContext(owner));
            return readContext(trace, context).trace();
        }).toList();
    }

    public TraceSummaryDto toSummaryDto(TraceSummaryProjection projection, TraceTokenSourceProjection tokenSources) {
        if (projection == null) return null;
        SpecificationDto violatedSpec = readSpecification(projection.getId(), projection.getViolatedSpecJson());
        return TraceSummaryDto.builder()
                .id(projection.getId())
                .verificationTaskId(requireVerificationTaskId(
                        projection.getId(), projection.getVerificationTaskId()))
                .violatedSpecId(projection.getViolatedSpecId())
                .violatedSpec(violatedSpec)
                .stateCount(requiredStateCount(projection.getId(), projection.getStateCount()))
                .modelTokenSourcesByDeviceId(readSummaryTokenSources(projection.getId(), tokenSources))
                .createdAt(projection.getCreatedAt())
                .dataAvailable(true)
                .build();
    }

    private SpecificationDto readSpecification(Long id, String json) {
        return JsonUtils.readPersistedRequired("verification trace", id, "violatedSpecJson", () -> {
            SpecificationDto spec = JsonUtils.fromJson(json, SpecificationDto.class);
            if (spec == null || spec.getName() == null || spec.getName().isBlank() || spec.getName().length() > 100) {
                throw new IllegalArgumentException("Frozen specification name is missing or invalid");
            }
            return spec;
        });
    }

    private Map<String, ModelTokenSource> readSummaryTokenSources(Long id, TraceTokenSourceProjection projection) {
        if (projection == null || !Objects.equals(id, projection.getId())) {
            throw new PersistedDataIntegrityException("verification trace", id, "modelTokenSourcesByDeviceId",
                    "frozen token provenance ownership is missing");
        }
        List<String> deviceIds = JsonUtils.readPersistedJsonRequired("verification trace", id,
                "deviceIdsJson", projection.getDeviceIdsJson(), () -> JsonUtils.fromJson(
                        projection.getDeviceIdsJson(), new TypeReference<List<String>>() {}));
        List<ModelTokenSource> sources = JsonUtils.readPersistedJsonRequired("verification trace", id,
                "tokenSourcesJson", projection.getTokenSourcesJson(), () -> JsonUtils.fromJson(
                        projection.getTokenSourcesJson(), new TypeReference<List<ModelTokenSource>>() {}));
        if (deviceIds.size() > 100 || deviceIds.size() != sources.size()) {
            throw new PersistedDataIntegrityException("verification trace", id, "modelTokenSourcesByDeviceId",
                    "frozen token provenance membership is invalid");
        }
        Map<String, ModelTokenSource> result = new LinkedHashMap<>();
        for (int index = 0; index < deviceIds.size(); index++) {
            String deviceId = deviceIds.get(index);
            ModelTokenSource source = sources.get(index);
            if (deviceId == null || deviceId.isBlank() || deviceId.length() > 200 || source == null
                    || result.putIfAbsent(deviceId, source) != null) {
                throw new PersistedDataIntegrityException("verification trace", id, "modelTokenSourcesByDeviceId",
                        "frozen token provenance is invalid");
            }
        }
        return Map.copyOf(result);
    }

    private List<TraceStateDto> readRequiredStates(
            Long id, String statesJson, Integer stateCount, List<RuleDto> frozenRules) {
        List<TraceStateDto> states = JsonUtils.readPersistedRequired(
                "verification trace", id, "statesJson",
                () -> TraceStateIntegrity.requireValidStates(JsonUtils.fromJson(
                        statesJson, new TypeReference<List<TraceStateDto>>() {})));
        if (states.isEmpty()) {
            throw new cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException(
                    "verification trace", id, "statesJson", "trace has no states");
        }
        int persistedStateCount = requiredStateCount(id, stateCount);
        if (states.size() != persistedStateCount) {
            throw new cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException(
                    "verification trace", id, "stateCount",
                    "state count does not match statesJson");
        }
        return JsonUtils.readPersisted(
                "verification trace", id, "statesJson", () -> {
                    TraceStateIntegrity.requireRuleEvidenceMatchesFrozenRules(states, frozenRules);
                    return states;
                });
    }

    private VerificationRequestDto readFrozenRequest(Long id, String requestJson) {
        return JsonUtils.readPersistedJsonRequired(
                "verification run", id, "requestJson", requestJson,
                () -> JsonUtils.fromJson(requestJson, VerificationRequestDto.class));
    }

    private List<RuleDto> readFrozenRules(
            Long id, VerificationRequestDto request, int expectedRuleCount) {
        return JsonUtils.readPersisted(
                "verification run", id, "requestJson",
                () -> TraceStateIntegrity.requireFrozenRules(request.getRules(), expectedRuleCount));
    }

    private int requiredStateCount(Long id, Integer stateCount) {
        if (stateCount == null || stateCount < 1) {
            throw new cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException(
                    "verification trace", id, "stateCount", "trace has no states");
        }
        return stateCount;
    }

    private Long requireVerificationTaskId(Long traceId, Long verificationTaskId) {
        if (verificationTaskId == null || verificationTaskId < 1) {
            throw new cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException(
                    "verification trace", traceId, "verificationTaskId", "trace owner is missing");
        }
        return verificationTaskId;
    }

    private RunContext readRunContext(VerificationTraceContextProjection run) {
        requireOmissionCount(run.getId(), "disabledRuleCount", run.getDisabledRuleCount());
        requireOmissionCount(run.getId(), "skippedSpecCount", run.getSkippedSpecCount());
        String issuesJson = run.getGenerationIssuesJson();
        List<ModelGenerationIssueDto> issues = JsonUtils.readPersistedJsonRequired(
                "verification run", run.getId(), "generationIssuesJson", issuesJson,
                () -> JsonUtils.fromJsonList(issuesJson, ModelGenerationIssueDto.class));
        PersistedModelContextIntegrity.ValidatedContext model = modelContext(run);
        VerificationRequestDto request = readFrozenRequest(run.getId(), run.getRequestJson());
        List<RuleDto> rules = readFrozenRules(run.getId(), request, model.modelSnapshot().getRuleCount());
        ModelPlaybackSceneDto playbackScene = JsonUtils.readPersisted(
                "verification run", run.getId(), "requestJson",
                () -> ModelPlaybackSceneSnapshot.canonicalize(request.getPlaybackNodes(), request.getDevices(), rules));
        return new RunContext(model, run.getDisabledRuleCount(), run.getSkippedSpecCount(), issues,
                request, rules, playbackScene, run.getTemplateSnapshotsJson());
    }

    private record RunContext(PersistedModelContextIntegrity.ValidatedContext model,
                              int disabledRuleCount, int skippedSpecCount,
                              List<ModelGenerationIssueDto> generationIssues,
                              VerificationRequestDto request, List<RuleDto> rules,
                              ModelPlaybackSceneDto playbackScene, String templateSnapshotsJson) {
    }

    private void requireOwner(TracePo trace, VerificationTraceContextProjection run) {
        Long ownerId = requireVerificationTaskId(trace.getId(), trace.getVerificationTaskId());
        if (run == null || trace.getUserId() == null || !Objects.equals(ownerId, run.getId())
                || !Objects.equals(trace.getUserId(), run.getUserId())
                || run.getStatus() != VerificationTaskPo.TaskStatus.COMPLETED) {
            throw new PersistedDataIntegrityException("verification trace", trace.getId(),
                    "verificationTaskId", "completed run ownership is missing or inconsistent");
        }
    }

    private void requireOmissionCount(Long runId, String field, Integer count) {
        if (count == null || count < 0) {
            throw new PersistedDataIntegrityException("verification run", runId, field,
                    "completed run count is missing or invalid");
        }
    }

    private PersistedModelContextIntegrity.ValidatedContext modelContext(VerificationTraceContextProjection po) {
        return PersistedModelContextIntegrity.readVerification(
                "verification run", po.getId(), po.getIsAttack(), po.getAttackBudget(),
                po.getEnablePrivacy(), po.getModeledDeviceAttackPointCount(),
                po.getModeledFalsifiableReadingDeviceCount(),
                po.getModeledAutomationLinkAttackPointCount(), po.getModelSemanticsJson(),
                po.getModelSnapshotJson());
    }

}
