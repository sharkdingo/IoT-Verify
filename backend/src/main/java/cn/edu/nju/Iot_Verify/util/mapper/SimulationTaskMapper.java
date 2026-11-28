package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskSummaryDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;
import cn.edu.nju.Iot_Verify.po.SimulationTaskView;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.ModelGenerationDiagnostics;
import cn.edu.nju.Iot_Verify.util.PersistedModelContextIntegrity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SimulationTaskMapper {

    public SimulationTaskDto toDto(SimulationTaskPo po) {
        if (po == null) {
            return null;
        }
        validateLifecycle(po);
        List<String> checkLogs = po.getCheckLogs() != null ? po.getCheckLogs() : readCheckLogs(po);
        List<ModelGenerationIssueDto> generationIssues = po.getGenerationIssues() != null
                ? List.copyOf(po.getGenerationIssues()) : readGenerationIssues(po);
        int disabledRuleCount = ModelGenerationDiagnostics.disabledRuleCount(generationIssues, checkLogs);
        PersistedModelContextIntegrity.ValidatedContext context = modelContext(po);
        return SimulationTaskDto.builder()
                .id(po.getId())
                .initiator(po.getInitiator())
                .status(po.getStatus().name())
                .createdAt(po.getCreatedAt())
                .startedAt(po.getStartedAt())
                .completedAt(po.getCompletedAt())
                .processingTimeMs(po.getProcessingTimeMs())
                .isAttack(context.isAttack())
                .attackBudget(context.attackBudget())
                .enablePrivacy(context.enablePrivacy())
                .modelSemantics(context.modelSemantics())
                .modelSnapshot(context.modelSnapshot())
                .requestedSteps(po.getRequestedSteps())
                .steps(po.getSteps())
                .modelComplete(po.getStatus() == SimulationTaskPo.TaskStatus.COMPLETED
                        ? disabledRuleCount == 0 : null)
                .disabledRuleCount(disabledRuleCount)
                .generationIssues(generationIssues)
                .simulationTraceId(po.getSimulationTraceId())
                .checkLogs(checkLogs)
                .errorMessage(po.getErrorMessage())
                .progress(po.getProgress())
                .progressStage(po.getProgressStage())
                .build();
    }

    public SimulationTaskSummaryDto toSummaryDto(SimulationTaskSummaryProjection task) {
        if (task == null) {
            return null;
        }
        validateLifecycle(task);
        List<String> checkLogs = readCheckLogs(task);
        List<ModelGenerationIssueDto> generationIssues = readGenerationIssues(task);
        int disabledRuleCount = ModelGenerationDiagnostics.disabledRuleCount(generationIssues, checkLogs);
        PersistedModelContextIntegrity.ValidatedContext context = modelContext(task);
        return SimulationTaskSummaryDto.builder()
                .id(task.getId())
                .initiator(task.getInitiator())
                .status(task.getStatus().name())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .processingTimeMs(task.getProcessingTimeMs())
                .isAttack(context.isAttack())
                .attackBudget(context.attackBudget())
                .enablePrivacy(context.enablePrivacy())
                .modelSemantics(context.modelSemantics())
                .modelSnapshot(context.modelSnapshot())
                .requestedSteps(task.getRequestedSteps())
                .steps(task.getSteps())
                .modelComplete(task.getStatus() == SimulationTaskPo.TaskStatus.COMPLETED
                        ? disabledRuleCount == 0 : null)
                .disabledRuleCount(disabledRuleCount)
                .generationIssues(generationIssues)
                .simulationTraceId(task.getSimulationTraceId())
                .errorMessage(task.getErrorMessage())
                .progress(task.getProgress())
                .progressStage(task.getProgressStage())
                .build();
    }

    public List<SimulationTaskSummaryDto> toSummaryProjectionDtoList(
            List<SimulationTaskSummaryProjection> projections) {
        if (projections == null) {
            return List.of();
        }
        return projections.stream()
                .map(this::toSummaryDto)
                .toList();
    }

    private List<String> readCheckLogs(SimulationTaskView task) {
        return JsonUtils.readPersisted("simulation task", task.getId(), "checkLogsJson",
                () -> JsonUtils.fromJsonToStringList(task.getCheckLogsJson()));
    }

    private List<ModelGenerationIssueDto> readGenerationIssues(SimulationTaskView task) {
        return JsonUtils.readPersisted("simulation task", task.getId(), "generationIssuesJson",
                () -> JsonUtils.fromJsonList(task.getGenerationIssuesJson(), ModelGenerationIssueDto.class));
    }

    private PersistedModelContextIntegrity.ValidatedContext modelContext(SimulationTaskView task) {
        return PersistedModelContextIntegrity.readSimulation(
                "simulation task", task.getId(), task.getIsAttack(), task.getAttackBudget(),
                task.getEnablePrivacy(), task.getModeledDeviceAttackPointCount(),
                task.getModeledFalsifiableReadingDeviceCount(),
                task.getModeledAutomationLinkAttackPointCount(), task.getModelSemanticsJson(),
                task.getModelSnapshotJson());
    }

    private void validateLifecycle(SimulationTaskView task) {
        if (task.getId() == null || task.getId() < 1) {
            fail(task, "id", "task id is missing or invalid");
        }
        if (task.getUserId() == null || task.getUserId() < 1) {
            fail(task, "userId", "task owner is missing or invalid");
        }
        if (task.getInitiator() == null) {
            fail(task, "initiator", "task initiator is missing");
        }
        if (task.getStatus() == null) {
            fail(task, "status", "task status is missing");
        }
        if (task.getCreatedAt() == null) {
            fail(task, "createdAt", "task creation time is missing");
        }
        Integer requestedSteps = task.getRequestedSteps();
        if (requestedSteps == null || requestedSteps < 1) {
            fail(task, "requestedSteps", "requested steps must be positive");
        }
        Integer steps = task.getSteps();
        if (steps != null && (steps < 0 || steps > requestedSteps)) {
            fail(task, "steps", "actual steps must be between zero and requested steps");
        }
        if (task.getProcessingTimeMs() != null && task.getProcessingTimeMs() < 0) {
            fail(task, "processingTimeMs", "processing time cannot be negative");
        }
        if (task.getProgress() == null || task.getProgress() < 0 || task.getProgress() > 100) {
            fail(task, "progress", "task progress must be between zero and 100");
        }

        PersistedTaskLifecycle.validateTimeOrder("simulation task", task.getId(), task.getCreatedAt(),
                task.getStartedAt(), task.getCompletedAt(), task.getProcessingTimeMs());

        SimulationTaskPo.TaskStatus status = task.getStatus();
        PersistedTaskLifecycle.validateStatus("simulation task", task.getId(), status.name(),
                task.getStartedAt(), task.getCompletedAt(), task.getProgress(), task::getErrorMessage);
        if (status == SimulationTaskPo.TaskStatus.COMPLETED) {
            if (steps == null) {
                fail(task, "steps", "completed task actual steps are missing");
            }
            if (task.getSimulationTraceId() == null || task.getSimulationTraceId() < 1) {
                fail(task, "simulationTraceId", "completed task trace reference is missing or invalid");
            }
        } else if (task.getSimulationTraceId() != null) {
            fail(task, "simulationTraceId", "non-completed task cannot reference a saved trace");
        }
    }

    private void fail(SimulationTaskView task, String field, String detail) {
        throw new PersistedDataIntegrityException("simulation task", task.getId(), field, detail);
    }
}
