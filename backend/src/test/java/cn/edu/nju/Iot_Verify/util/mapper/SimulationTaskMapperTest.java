package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueReasonCode;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskSummaryDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulationTaskMapperTest {

    private final SimulationTaskMapper mapper = new SimulationTaskMapper();
    private static final String MODEL_SNAPSHOT_JSON = "{\"capturedAt\":\"2026-07-12T09:30:00\","
            + "\"deviceCount\":4,\"ruleCount\":2,\"specificationCount\":0,"
            + "\"environmentVariableCount\":1,\"deviceTemplateCount\":2,\"templatesFrozen\":true}";
    private static final String MODEL_SEMANTICS_JSON = JsonUtils.toJson(ModelSemanticsDto.forRun(
            AttackScenarioDto.anyUpToBudget(3), true, 4, 2, 2));

    @Test
    void mapsStructuredGenerationIssuesToTaskAndSummary() {
        SimulationTaskPo po = SimulationTaskPo.builder()
                .id(7L)
                .userId(1L)
                .status(SimulationTaskPo.TaskStatus.COMPLETED)
                .createdAt(LocalDateTime.now())
                .startedAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .requestedSteps(10)
                .steps(3)
                .simulationTraceId(71L)
                .progress(100)
                .progressStage(TaskProgressStage.RUNNING_SIMULATION)
                .isAttack(true)
                .attackBudget(3)
                .modeledDeviceAttackPointCount(4)
                .modeledFalsifiableReadingDeviceCount(2)
                .modeledAutomationLinkAttackPointCount(2)
                .enablePrivacy(true)
                .modelSemanticsJson(MODEL_SEMANTICS_JSON)
                .modelSnapshotJson(MODEL_SNAPSHOT_JSON)
                .generationIssuesJson("[{\"issueType\":\"RULE_DISABLED\","
                        + "\"itemLabel\":\"When motion is detected, turn on the hall light\","
                        + "\"reasonCode\":\"RULE_UNRESOLVABLE_TRIGGER_CONDITION\","
                        + "\"reason\":\"The target action is not available on this device type.\"}]")
                .checkLogsJson("[]")
                .build();

        SimulationTaskDto task = mapper.toDto(po);
        SimulationTaskSummaryDto summary = mapper.toSummaryDto(taskSummary(po));

        assertEquals(1, task.getDisabledRuleCount());
        assertEquals(TaskProgressStage.RUNNING_SIMULATION, task.getProgressStage());
        assertEquals(true, task.getIsAttack());
        assertEquals(3, task.getAttackBudget());
        assertEquals(true, task.getEnablePrivacy());
        assertEquals(6, task.getModelSemantics().getModeledAttackPointCount());
        assertEquals(2, task.getModelSemantics().getModeledFalsifiableReadingDeviceCount());
        assertEquals(4, task.getModelSnapshot().getDeviceCount());
        assertEquals(0, task.getModelSnapshot().getSpecificationCount());
        assertEquals(true, task.getModelSnapshot().isTemplatesFrozen());
        assertFalse(task.getModelComplete());
        assertEquals("When motion is detected, turn on the hall light",
                task.getGenerationIssues().get(0).getItemLabel());
        assertEquals(ModelGenerationIssueReasonCode.RULE_UNRESOLVABLE_TRIGGER_CONDITION,
                task.getGenerationIssues().get(0).getReasonCode());
        assertEquals(task.getGenerationIssues(), summary.getGenerationIssues());
        assertEquals(1, summary.getDisabledRuleCount());
        assertEquals(TaskProgressStage.RUNNING_SIMULATION, summary.getProgressStage());
        assertEquals(true, summary.getIsAttack());
        assertEquals(3, summary.getAttackBudget());
        assertEquals(true, summary.getEnablePrivacy());
        assertEquals(6, summary.getModelSemantics().getModeledAttackPointCount());
        assertEquals(4, summary.getModelSemantics().getModeledDeviceAttackPointCount());
        assertEquals(2, summary.getModelSemantics().getModeledAutomationLinkAttackPointCount());
        assertFalse(summary.getModelComplete());
        assertEquals(task.getModelSnapshot(), summary.getModelSnapshot());
    }

    @Test
    void missingModelSemanticsFailsClosed() {
        SimulationTaskPo po = SimulationTaskPo.builder()
                .id(8L)
                .userId(1L)
                .status(SimulationTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .requestedSteps(10)
                .progress(0)
                .isAttack(true)
                .attackBudget(3)
                .modeledDeviceAttackPointCount(4)
                .modeledFalsifiableReadingDeviceCount(2)
                .modeledAutomationLinkAttackPointCount(2)
                .enablePrivacy(true)
                .modelSnapshotJson(MODEL_SNAPSHOT_JSON)
                .build();

        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po));

        assertEquals("modelSemanticsJson", error.getField());
    }

    @Test
    void emptyOrContradictoryModelContextFailsClosed() {
        SimulationTaskPo emptySemantics = validTask(9L);
        emptySemantics.setModelSemanticsJson("{}");
        assertEquals("modelSemanticsJson", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(emptySemantics)).getField());

        SimulationTaskPo emptySnapshot = validTask(10L);
        emptySnapshot.setModelSnapshotJson("{}");
        assertEquals("modelSnapshotJson", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(emptySnapshot)).getField());

        SimulationTaskPo contradictoryPrivacy = validTask(11L);
        contradictoryPrivacy.setEnablePrivacy(false);
        assertEquals("modelSemanticsJson", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(contradictoryPrivacy)).getField());
    }

    @Test
    void nullPersistedScalarFailsClosed() {
        SimulationTaskPo po = validTask(12L);
        po.setAttackBudget(null);

        assertEquals("attackBudget", assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po)).getField());
    }

    @Test
    void contradictoryTaskLifecycleFailsClosed() {
        SimulationTaskPo missingStatus = validTask(13L);
        missingStatus.setStatus(null);
        assertEquals("status", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(missingStatus)).getField());

        SimulationTaskPo missingRequestedSteps = validTask(14L);
        missingRequestedSteps.setRequestedSteps(null);
        assertEquals("requestedSteps", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toSummaryDto(taskSummary(missingRequestedSteps))).getField());

        SimulationTaskPo tooManySteps = validTask(15L);
        tooManySteps.setSteps(11);
        assertEquals("steps", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(tooManySteps)).getField());

        SimulationTaskPo completedWithoutTrace = validTask(16L);
        completedWithoutTrace.setSimulationTraceId(null);
        assertEquals("simulationTraceId", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(completedWithoutTrace)).getField());

        SimulationTaskPo failedWithoutMessage = validTask(17L);
        failedWithoutMessage.setStatus(SimulationTaskPo.TaskStatus.FAILED);
        failedWithoutMessage.setStartedAt(null);
        failedWithoutMessage.setSteps(null);
        failedWithoutMessage.setSimulationTraceId(null);
        failedWithoutMessage.setErrorMessage(" ");
        assertEquals("errorMessage", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(failedWithoutMessage)).getField());

        SimulationTaskPo pendingWithTrace = validTask(18L);
        pendingWithTrace.setStatus(SimulationTaskPo.TaskStatus.PENDING);
        pendingWithTrace.setStartedAt(null);
        pendingWithTrace.setCompletedAt(null);
        pendingWithTrace.setSteps(null);
        pendingWithTrace.setProgress(0);
        assertEquals("simulationTraceId", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(pendingWithTrace)).getField());

        SimulationTaskPo completionBeforeStart = validTask(19L);
        completionBeforeStart.setCompletedAt(completionBeforeStart.getStartedAt().minusSeconds(1));
        assertEquals("completedAt", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(completionBeforeStart)).getField());

        SimulationTaskPo pendingWithStart = validTask(20L);
        pendingWithStart.setStatus(SimulationTaskPo.TaskStatus.PENDING);
        pendingWithStart.setCompletedAt(null);
        pendingWithStart.setProgress(0);
        pendingWithStart.setSteps(null);
        pendingWithStart.setSimulationTraceId(null);
        assertEquals("startedAt", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toSummaryDto(taskSummary(pendingWithStart))).getField());
    }

    private SimulationTaskSummaryProjection taskSummary(SimulationTaskPo task) {
        return new SpelAwareProxyProjectionFactory().createProjection(SimulationTaskSummaryProjection.class, task);
    }

    private SimulationTaskPo validTask(Long id) {
        return SimulationTaskPo.builder()
                .id(id)
                .userId(1L)
                .status(SimulationTaskPo.TaskStatus.COMPLETED)
                .createdAt(LocalDateTime.now())
                .startedAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .requestedSteps(10)
                .steps(3)
                .simulationTraceId(70L)
                .progress(100)
                .isAttack(true)
                .attackBudget(3)
                .modeledDeviceAttackPointCount(4)
                .modeledFalsifiableReadingDeviceCount(2)
                .modeledAutomationLinkAttackPointCount(2)
                .enablePrivacy(true)
                .modelSemanticsJson(MODEL_SEMANTICS_JSON)
                .modelSnapshotJson(MODEL_SNAPSHOT_JSON)
                .generationIssuesJson("[]")
                .checkLogsJson("[]")
                .build();
    }
}
