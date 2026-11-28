package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationRequestDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.RunPersistenceDto;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.SimulationTracePo;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTraceSummaryProjection;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.ModelGenerationDiagnostics;
import cn.edu.nju.Iot_Verify.util.ModelPlaybackSceneSnapshot;
import cn.edu.nju.Iot_Verify.util.PersistedModelContextIntegrity;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * SimulationTrace PO 与 DTO 之间的转换映射器
 */
@Component
@Slf4j
public class SimulationTraceMapper {

    /**
     * SimulationTracePo -> SimulationTraceDto（详情）
     */
    public SimulationTraceDto toDto(SimulationTracePo po) {
        if (po == null) return null;
        requireInitiator(po.getId(), po.getInitiator());

        List<String> logs = JsonUtils.readPersisted("simulation trace", po.getId(), "logsJson",
                () -> JsonUtils.fromJsonToStringList(po.getLogsJson()));
        List<ModelGenerationIssueDto> generationIssues = JsonUtils.readPersisted(
                "simulation trace", po.getId(), "generationIssuesJson",
                () -> readGenerationIssues(po.getGenerationIssuesJson()));
        List<TraceStateDto> states = PersistedTraceEvidence.readStates(
                "simulation trace", po.getId(), po.getStatesJson(), "state list is empty");
        requireStateMetadata(po.getId(), po.getStateCount(), po.getSteps(), po.getRequestedSteps());
        PersistedTraceEvidence.requireStateCount("simulation trace", po.getId(), states, po.getStateCount());
        int disabledRuleCount = ModelGenerationDiagnostics.disabledRuleCount(generationIssues, logs);
        PersistedModelContextIntegrity.ValidatedContext context = modelContext(po);
        SimulationRequestDto frozenRequest = readFrozenRequest(po.getId(), po.getRequestJson());
        List<RuleDto> frozenRules = PersistedTraceEvidence.readFrozenRules(
                "simulation trace", po.getId(), frozenRequest::getRules, context.modelSnapshot().getRuleCount());
        states = PersistedTraceEvidence.validateRuleEvidence("simulation trace", po.getId(), states, frozenRules);
        SimulationTraceDto dto = SimulationTraceDto.builder()
                .id(po.getId())
                .initiator(po.getInitiator())
                .userId(po.getUserId())
                .requestedSteps(po.getRequestedSteps())
                .steps(po.getSteps())
                .modelComplete(disabledRuleCount == 0)
                .disabledRuleCount(disabledRuleCount)
                .generationIssues(generationIssues)
                .states(states)
                .logs(logs)
                .nusmvOutput(po.getNusmvOutput())
                .requestJson(po.getRequestJson())
                .templateSnapshotsJson(po.getTemplateSnapshotsJson())
                .modelSnapshot(context.modelSnapshot())
                .playbackScene(JsonUtils.readPersisted(
                        "simulation trace", po.getId(), "requestJson",
                        () -> ModelPlaybackSceneSnapshot.canonicalize(
                                frozenRequest.getPlaybackNodes(), frozenRequest.getDevices(), frozenRules)))
                .createdAt(po.getCreatedAt())
                // Read back for GET /api/simulate/traces/{id}/smv. Omitting it made that endpoint
                // return 500 on every saved trajectory, because the controller treats a blank model
                // as a persistence defect — which it could not distinguish from this mapper's gap.
                .smvModelContent(po.getSmvModelContent())
                .historyPersistence(RunPersistenceDto.saved(po.getId()))
                .build();
        applyPersistedContext(dto, context);
        return dto;
    }

    public SimulationTraceSummaryDto toSummaryProjectionDto(SimulationTraceSummaryProjection projection) {
        if (projection == null) return null;
        requireInitiator(projection.getId(), projection.getInitiator());

        requireStateMetadata(projection.getId(), projection.getStateCount(),
                projection.getSteps(), projection.getRequestedSteps());
        List<ModelGenerationIssueDto> generationIssues = JsonUtils.readPersisted(
                "simulation trace", projection.getId(), "generationIssuesJson",
                () -> readGenerationIssues(projection.getGenerationIssuesJson()));
        int disabledRuleCount = ModelGenerationDiagnostics.disabledRuleCount(
                generationIssues, JsonUtils.readPersisted("simulation trace", projection.getId(), "logsJson",
                        () -> JsonUtils.fromJsonToStringList(projection.getLogsJson())));
        PersistedModelContextIntegrity.ValidatedContext context = modelContext(projection);
        SimulationTraceSummaryDto dto = SimulationTraceSummaryDto.builder()
                .id(projection.getId())
                .initiator(projection.getInitiator())
                .requestedSteps(projection.getRequestedSteps())
                .steps(projection.getSteps())
                .modelComplete(disabledRuleCount == 0)
                .disabledRuleCount(disabledRuleCount)
                .generationIssues(generationIssues)
                .modelSnapshot(context.modelSnapshot())
                .createdAt(projection.getCreatedAt())
                // Presence only; the model is fetched through /api/simulate/traces/{id}/smv.
                .hasSmvModel(Boolean.TRUE.equals(projection.getHasSmvModel()))
                .dataAvailable(true)
                .build();
        dto.setAttack(context.isAttack());
        dto.setAttackBudget(context.attackBudget());
        dto.setEnablePrivacy(context.enablePrivacy());
        return dto;
    }

    public List<SimulationTraceSummaryDto> toSummaryProjectionDtoList(
            List<SimulationTraceSummaryProjection> projections) {
        if (projections == null) return List.of();
        return projections.stream().map(projection -> {
            try {
                return toSummaryProjectionDto(projection);
            } catch (PersistedDataIntegrityException e) {
                log.error("Simulation history item {} is unavailable because persisted data is invalid",
                        projection != null ? projection.getId() : null, e);
                return SimulationTraceSummaryDto.builder()
                        .id(projection != null ? projection.getId() : null)
                        .initiator(projection != null && projection.getInitiator() != null
                                ? projection.getInitiator() : RunInitiator.UNKNOWN)
                        .createdAt(projection != null ? projection.getCreatedAt() : null)
                        .dataAvailable(false)
                        .unavailableReasonCode("PERSISTED_SEMANTIC_DATA_INVALID")
                        .build();
            }
        }).toList();
    }

    private List<ModelGenerationIssueDto> readGenerationIssues(String json) {
        return JsonUtils.fromJsonList(json, ModelGenerationIssueDto.class);
    }

    private void requireInitiator(Long id, RunInitiator initiator) {
        if (initiator == null) {
            throw new PersistedDataIntegrityException(
                    "simulation trace", id, "initiator", "run initiator is missing");
        }
    }

    private void requireStateMetadata(Long id, Integer stateCount, int steps, int requestedSteps) {
        if (stateCount == null || stateCount < 1) {
            throw new PersistedDataIntegrityException(
                    "simulation trace", id, "stateCount", "state list is empty");
        }
        if (requestedSteps < 1 || steps < 0 || steps > requestedSteps) {
            throw new PersistedDataIntegrityException(
                    "simulation trace", id, "steps", "step metadata is inconsistent");
        }
        if (stateCount != steps + 1) {
            throw new PersistedDataIntegrityException(
                    "simulation trace", id, "stateCount",
                    "state count must equal steps plus the initial state");
        }
    }

    private SimulationRequestDto readFrozenRequest(Long id, String requestJson) {
        return JsonUtils.readPersistedJsonRequired(
                "simulation trace", id, "requestJson", requestJson,
                () -> JsonUtils.fromJson(requestJson, SimulationRequestDto.class));
    }

    private void applyPersistedContext(
            SimulationTraceDto dto, PersistedModelContextIntegrity.ValidatedContext context) {
        dto.setAttack(context.isAttack());
        dto.setAttackBudget(context.attackBudget());
        dto.setEnablePrivacy(context.enablePrivacy());
        dto.setModelSemantics(context.modelSemantics());
    }

    private PersistedModelContextIntegrity.ValidatedContext modelContext(SimulationTracePo po) {
        return PersistedModelContextIntegrity.readSimulation(
                "simulation trace", po.getId(), po.getIsAttack(), po.getAttackBudget(),
                po.getEnablePrivacy(), po.getModeledDeviceAttackPointCount(),
                po.getModeledFalsifiableReadingDeviceCount(),
                po.getModeledAutomationLinkAttackPointCount(), po.getModelSemanticsJson(),
                po.getModelSnapshotJson());
    }

    private PersistedModelContextIntegrity.ValidatedContext modelContext(
            SimulationTraceSummaryProjection projection) {
        return PersistedModelContextIntegrity.readSimulation(
                "simulation trace", projection.getId(), projection.getIsAttack(),
                projection.getAttackBudget(), projection.getEnablePrivacy(),
                projection.getModeledDeviceAttackPointCount(),
                projection.getModeledFalsifiableReadingDeviceCount(),
                projection.getModeledAutomationLinkAttackPointCount(),
                projection.getModelSemanticsJson(), projection.getModelSnapshotJson());
    }
}
