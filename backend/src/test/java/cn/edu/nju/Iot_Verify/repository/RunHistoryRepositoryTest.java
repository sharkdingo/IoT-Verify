package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;
import cn.edu.nju.Iot_Verify.po.SimulationTracePo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTraceSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationRunSummaryProjection;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.mapper.VerificationTaskMapper;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=cn.edu.nju.Iot_Verify.repository.RunHistoryRepositoryTest$SqlCapture",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class RunHistoryRepositoryTest {

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<java.util.ArrayList<String>> STATEMENTS =
                ThreadLocal.withInitial(java.util.ArrayList::new);

        @Override
        public String inspect(String sql) {
            STATEMENTS.get().add(sql);
            return sql;
        }
    }

    private static final String NO_ATTACK_SEMANTICS_JSON = JsonUtils.toJson(
            ModelSemanticsDto.forRun(AttackScenarioDto.none(), false, 1, 0, 0));
    private static final String VERIFICATION_SNAPSHOT_JSON =
            "{\"capturedAt\":\"2026-07-24T09:30:00\",\"deviceCount\":1,\"ruleCount\":0,"
                    + "\"specificationCount\":1,\"environmentVariableCount\":0,"
                    + "\"deviceTemplateCount\":1,\"templatesFrozen\":true}";
    private static final String SIMULATION_SNAPSHOT_JSON =
            "{\"capturedAt\":\"2026-07-24T09:30:00\",\"deviceCount\":1,\"ruleCount\":0,"
                    + "\"specificationCount\":0,\"environmentVariableCount\":0,"
                    + "\"deviceTemplateCount\":1,\"templatesFrozen\":true}";

    @Autowired private SimulationTraceRepository simulationTraceRepository;
    @Autowired private SimulationTaskRepository simulationTaskRepository;
    @Autowired private VerificationTaskRepository verificationTaskRepository;

    @Test
    void simulationStoredRunCountDoesNotDoubleCountTaskOwnedTrace() {
        LocalDateTime now = LocalDateTime.now();
        SimulationTracePo taskOwned = simulationTraceRepository.saveAndFlush(simulationTrace(7L, now));
        SimulationTracePo standalone = simulationTraceRepository.saveAndFlush(
                simulationTrace(7L, now.plusSeconds(1)));
        simulationTaskRepository.saveAndFlush(SimulationTaskPo.builder()
                .userId(7L)
                .status(SimulationTaskPo.TaskStatus.COMPLETED)
                .createdAt(now)
                .completedAt(now)
                .simulationTraceId(taskOwned.getId())
                .build());

        assertEquals(1L, simulationTaskRepository.countByUserId(7L));
        assertEquals(1L, simulationTraceRepository.countStandaloneByUserId(7L));
        List<SimulationTraceSummaryProjection> summaries =
                simulationTraceRepository.findSummariesByUserId(
                        7L, PageRequest.of(0, 10));
        assertEquals(List.of(standalone.getId(), taskOwned.getId()),
                summaries.stream().map(SimulationTraceSummaryProjection::getId).toList());
        assertEquals(2, summaries.get(0).getStateCount());
        assertEquals(RunInitiator.AI_ASSISTANT, summaries.get(0).getInitiator());
        assertEquals(NO_ATTACK_SEMANTICS_JSON, summaries.get(0).getModelSemanticsJson());
        assertEquals(1, summaries.get(0).getModeledDeviceAttackPointCount());
    }

    @Test
    void verificationHistoryProjectionReturnsOnlyRequestedRunSummaries() {
        LocalDateTime now = LocalDateTime.now();
        VerificationTaskPo run = verificationTaskRepository.saveAndFlush(
                VerificationTaskPo.builder()
                        .userId(9L)
                        .initiator(RunInitiator.AI_ASSISTANT)
                        .status(VerificationTaskPo.TaskStatus.COMPLETED)
                        .createdAt(now)
                        .startedAt(now)
                        .completedAt(now.plusSeconds(1))
                        .isAttack(false)
                        .attackBudget(0)
                        .modeledDeviceAttackPointCount(1)
                        .modeledFalsifiableReadingDeviceCount(0)
                        .modeledAutomationLinkAttackPointCount(0)
                        .enablePrivacy(false)
                        .outcome(VerificationOutcome.VIOLATED)
                        .modelSemanticsJson(NO_ATTACK_SEMANTICS_JSON)
                        .modelSnapshotJson(VERIFICATION_SNAPSHOT_JSON)
                        .generationIssuesJson("[]")
                        .requestJson("{\"rules\":[]}")
                        .build());
        List<VerificationRunSummaryProjection> runs =
                verificationTaskRepository.findCompletedRunSummaries(
                        9L, VerificationTaskPo.TaskStatus.COMPLETED, PageRequest.of(0, 10));
        assertEquals(List.of(run.getId()),
                runs.stream().map(VerificationRunSummaryProjection::getId).toList());
        assertEquals(RunInitiator.AI_ASSISTANT, runs.get(0).getInitiator());
    }

    @Test
    void historyAndTaskSummaryQueriesExcludeDetailPayloads() throws Exception {
        Query verificationQuery = AnnotatedElementUtils.findMergedAnnotation(TraceRepository.class
                .getMethod("findSummariesByUserIdAndVerificationTaskIdIn", Long.class, List.class), Query.class);
        Query simulationQuery = SimulationTraceRepository.class
                .getMethod("findSummariesByUserId", Long.class, org.springframework.data.domain.Pageable.class)
                .getAnnotation(Query.class);

        for (Query query : List.of(verificationQuery, simulationQuery)) {
            assertFalse(query.value().contains("statesJson"));
            assertFalse(query.value().contains("requestJson"));
        }

        assertThrows(NoSuchMethodException.class,
                () -> VerificationTaskSummaryProjection.class.getMethod("getNusmvOutput"));
        assertThrows(NoSuchMethodException.class,
                () -> VerificationTaskSummaryProjection.class.getMethod("getSpecResultsJson"));
        assertThrows(NoSuchMethodException.class,
                () -> VerificationTaskSummaryProjection.class.getMethod("getCheckLogsJson"));
        assertThrows(NoSuchMethodException.class,
                () -> SimulationTaskSummaryProjection.class.getMethod("getWorkerId"));
        assertThrows(NoSuchMethodException.class,
                () -> SimulationTaskSummaryProjection.class.getMethod("getLeaseExpiresAt"));
        assertThrows(NoSuchMethodException.class,
                () -> TraceRepository.class.getMethod(
                        "findByUserIdOrderByCreatedAtDesc", Long.class));
    }

    @Test
    void resultReadsProjectPresentationWithoutHydratingFrozenInputOrModelText() {
        VerificationTaskPo run = completedResult();
        SqlCapture.STATEMENTS.get().clear();

        var taskView = verificationTaskRepository.findResultViewByIdAndUserId(
                run.getId(), 9L, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).orElseThrow();
        var runView = verificationTaskRepository.findCompletedResultView(
                run.getId(), 9L, VerificationTaskPo.TaskStatus.COMPLETED, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).orElseThrow();
        var mapper = new VerificationTaskMapper();
        var task = mapper.toDto(taskView);
        var detail = mapper.toRunDto(runView, 0);

        assertEquals(run.getId(), task.getId());
        assertEquals(VerificationOutcome.SATISFIED, task.getOutcome());
        assertEquals(List.of("checked"), task.getCheckLogs());
        assertEquals("solver diagnostic", task.getNusmvOutput());
        assertEquals("spec-1", task.getSpecResults().get(0).getSpecId());
        assertEquals(true, task.isHasSmvModel());
        assertEquals(task.getCheckLogs(), detail.getCheckLogs());
        assertEquals(task.getModelSnapshot(), detail.getModelSnapshot());
        assertEquals(true, detail.isHasSmvModel());
        List<String> selects = SqlCapture.STATEMENTS.get().stream()
                .filter(sql -> sql.startsWith("select ")).toList();
        assertEquals(2, selects.size());
        for (String sql : selects) {
            assertFalse(sql.contains("request_json"), sql);
            assertFalse(sql.contains("template_snapshots_json"), sql);
            // Model text may occur only inside the presence expression, never as a selected column.
            String withoutPresence = sql.replaceAll("case when .*? then true else false end", "presence");
            assertFalse(withoutPresence.contains("smv_model_content"), sql);
        }
    }

    @Test
    void resultViewPreservesOwnershipStatusAndPersistedIntegrityFailures() {
        VerificationTaskPo run = completedResult();
        assertFalse(verificationTaskRepository.findResultViewByIdAndUserId(
                run.getId(), 10L, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).isPresent());
        assertFalse(verificationTaskRepository.findCompletedResultView(
                run.getId(), 10L, VerificationTaskPo.TaskStatus.COMPLETED, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).isPresent());
        run.setStatus(VerificationTaskPo.TaskStatus.CANCELLED);
        verificationTaskRepository.saveAndFlush(run);
        assertFalse(verificationTaskRepository.findCompletedResultView(
                run.getId(), 9L, VerificationTaskPo.TaskStatus.COMPLETED, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).isPresent());
        run.setStatus(VerificationTaskPo.TaskStatus.COMPLETED);
        run.setModelSemanticsJson("{broken");
        verificationTaskRepository.saveAndFlush(run);
        var view = verificationTaskRepository.findResultViewByIdAndUserId(
                run.getId(), 9L, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).orElseThrow();
        var mapper = new VerificationTaskMapper();
        assertEquals("modelSemanticsJson", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toDto(view)).getField());
        assertEquals("modelSemanticsJson", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunDto(view, 0)).getField());
    }

    @ParameterizedTest
    @MethodSource("modelPresenceValues")
    void resultViewModelPresenceMatchesEntityAndDownloadBlankSemantics(String content) {
        VerificationTaskPo run = completedResult();
        run.setSmvModelContent(content);
        verificationTaskRepository.saveAndFlush(run);

        var view = verificationTaskRepository.findResultViewByIdAndUserId(
                run.getId(), 9L, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).orElseThrow();

        assertEquals(content != null && !content.isBlank(), view.getHasSmvModel());
        assertEquals(run.getHasSmvModel(), new VerificationTaskMapper().toDto(view).isHasSmvModel());
    }

    static java.util.stream.Stream<String> modelPresenceValues() {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(null, "", "\t\n\r ", "\u00A0", "\u2007", "\u202F", "MODULE main\n"),
                java.util.stream.IntStream.rangeClosed(0, Character.MAX_VALUE)
                        .filter(Character::isWhitespace).mapToObj(value -> Character.toString((char) value)));
    }

    private VerificationTaskPo completedResult() {
        LocalDateTime created = LocalDateTime.now();
        return verificationTaskRepository.saveAndFlush(VerificationTaskPo.builder()
                .userId(9L).initiator(RunInitiator.AI_ASSISTANT)
                .status(VerificationTaskPo.TaskStatus.COMPLETED)
                .createdAt(created).startedAt(created).completedAt(created.plusSeconds(1))
                .processingTimeMs(1000L).progress(100).isAttack(false).attackBudget(0).enablePrivacy(false)
                .modeledDeviceAttackPointCount(1).modeledFalsifiableReadingDeviceCount(0)
                .modeledAutomationLinkAttackPointCount(0)
                .outcome(VerificationOutcome.SATISFIED).violatedSpecCount(0)
                .disabledRuleCount(0).skippedSpecCount(0)
                .modelSemanticsJson(NO_ATTACK_SEMANTICS_JSON).modelSnapshotJson(VERIFICATION_SNAPSHOT_JSON)
                .generationIssuesJson("[]").checkLogsJson("[\"checked\"]")
                .specResultsJson("""
                        [
                          {
                            "specId": "spec-1",
                            "templateId": "1",
                            "formulaKind": "CTLSPEC",
                            "expression": "AG lamp.state = off",
                            "outcome": "SATISFIED",
                            "antecedentReachability": "NOT_APPLICABLE",
                            "specificationLabel": "Keep lamp off",
                            "specificationSnapshot": {
                              "id": "spec-1",
                              "name": "Keep lamp off",
                              "templateId": "1",
                              "aConditions": [
                                {
                                  "deviceId": "lamp",
                                  "targetType": "state",
                                  "key": "state",
                                  "relation": "=",
                                  "value": "off"
                                }
                              ],
                              "ifConditions": [],
                              "thenConditions": [],
                              "devices": {
                                "lamp": {
                                  "label": "Lamp",
                                  "modelTokenSource": "BUNDLED",
                                  "modeCount": 1
                                }
                              }
                            }
                          }
                        ]
                        """)
                .nusmvOutput("solver diagnostic").smvModelContent("MODULE main\n" + "-- model\n".repeat(5000))
                .requestJson("{\"frozen\":\"" + "x".repeat(10000) + "\"}")
                .templateSnapshotsJson("{\"schemaVersion\":1,\"manifests\":{},\"modelTokenSourcesByDeviceId\":{}}")
                .build());
    }

    private SimulationTracePo simulationTrace(Long userId, LocalDateTime createdAt) {
        return SimulationTracePo.builder()
                .userId(userId)
                .initiator(RunInitiator.AI_ASSISTANT)
                .requestedSteps(3)
                .steps(1)
                .statesJson("[{},{}]")
                .stateCount(2)
                .logsJson("[]")
                .generationIssuesJson("[]")
                .requestJson("{\"rules\":[]}")
                .isAttack(false)
                .attackBudget(0)
                .enablePrivacy(false)
                .modeledDeviceAttackPointCount(1)
                .modeledFalsifiableReadingDeviceCount(0)
                .modeledAutomationLinkAttackPointCount(0)
                .modelSemanticsJson(NO_ATTACK_SEMANTICS_JSON)
                .modelSnapshotJson(SIMULATION_SNAPSHOT_JSON)
                .createdAt(createdAt)
                .build();
    }
}
