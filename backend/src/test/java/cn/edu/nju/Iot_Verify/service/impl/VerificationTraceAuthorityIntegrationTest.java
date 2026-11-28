package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.RuleFixer;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.configure.AsyncTaskAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.configure.JwtConfig;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsVersion;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.exception.ModelSemanticsChangedException;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.TraceRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.service.ChatExecutionLeaseGuard;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.FixSuggestionTokenService;
import cn.edu.nju.Iot_Verify.service.FormalOperationAdmission;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.EnvironmentProvenanceCollector;
import cn.edu.nju.Iot_Verify.testutil.SqlStatementCapture;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import cn.edu.nju.Iot_Verify.util.mapper.TraceMapper;
import cn.edu.nju.Iot_Verify.util.mapper.VerificationTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=cn.edu.nju.Iot_Verify.testutil.SqlStatementCapture",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VerificationTraceAuthorityIntegrationTest {
    @Autowired private VerificationTaskRepository tasks;
    @Autowired private TraceRepository traces;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private VerificationServiceImpl service;
    private UserPo user;

    @BeforeEach
    void setUp() {
        user = users.save(UserPo.builder().phone("trace-owner-test")
                .username("trace-owner-test").password("test-password").build());
        service = new VerificationServiceImpl(
                mock(SmvGenerator.class), mock(EnvironmentProvenanceCollector.class),
                mock(SmvTraceParser.class), mock(NusmvExecutor.class), new NusmvConfig(),
                tasks, traces, new TraceMapper(), users,
                mock(SpecificationMapper.class), mock(VerificationTaskMapper.class),
                new ObjectMapper().findAndRegisterModules(), mock(ThreadPoolTaskExecutor.class),
                mock(ThreadPoolTaskExecutor.class), new TransactionTemplate(transactionManager),
                mock(ChatExecutionLeaseGuard.class), mock(FormalOperationAdmission.class),
                new AsyncTaskAdmissionConfig(), mock(BoardDataConverter.class));
    }

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            traces.deleteByUserId(user.getId());
            tasks.deleteByUserId(user.getId());
            users.deleteById(user.getId());
        });
    }

    @Test
    void completionStoresTraceEvidenceAndOneAuthoritativeRunContext() {
        VerificationTaskPo task = task(VerificationTaskPo.TaskStatus.RUNNING);

        assertTrue(complete(task, List.of(trace("s1"), trace("s2"))));

        var contexts = tasks.findTraceContextsByUserIdAndIdInAndStatus(
                user.getId(), List.of(task.getId()), VerificationTaskPo.TaskStatus.COMPLETED);
        assertEquals(1, contexts.size());
        assertEquals(2, contexts.get(0).getDisabledRuleCount());
        assertEquals(1, contexts.get(0).getSkippedSpecCount());
        assertEquals(task.getModelSnapshotJson(), contexts.get(0).getModelSnapshotJson());
        assertEquals(task.getModelSemanticsJson(), contexts.get(0).getModelSemanticsJson());
        assertEquals(task.getRequestJson(), contexts.get(0).getRequestJson());
        var repairContext = tasks.findRepairTraceContextByIdAndUserIdAndStatus(
                task.getId(), user.getId(), VerificationTaskPo.TaskStatus.COMPLETED).orElseThrow();
        assertEquals(task.getTemplateSnapshotsJson(), repairContext.getTemplateSnapshotsJson());
        assertTrue(tasks.findRepairTraceContextByIdAndUserIdAndStatus(
                task.getId(), user.getId() + 1, VerificationTaskPo.TaskStatus.COMPLETED).isEmpty());
        List<TracePo> savedTraces = traces.findByUserIdAndVerificationTaskId(user.getId(), task.getId());
        assertEquals(2, savedTraces.size());
        assertTrue(savedTraces.stream().allMatch(trace -> task.getId().equals(trace.getVerificationTaskId())));
        assertTrue(tasks.findTraceContextsByUserIdAndIdInAndStatus(
                user.getId() + 1, List.of(task.getId()), VerificationTaskPo.TaskStatus.COMPLETED).isEmpty());
        for (String field : List.of("modelSnapshotJson", "modelSemanticsJson", "isAttack", "attackBudget",
                "enablePrivacy", "modeledDeviceAttackPointCount", "modeledFalsifiableReadingDeviceCount",
                "modeledAutomationLinkAttackPointCount", "disabledRuleCount", "skippedSpecCount",
                "generationIssuesJson", "requestJson", "templateSnapshotsJson")) {
            assertThrows(NoSuchFieldException.class, () -> TracePo.class.getDeclaredField(field));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 5, ModelSemanticsVersion.CURRENT - 1})
    void historicalCompletedTraceStillReplaysButCannotAuthorizeRepair(int archivedVersion) {
        VerificationTaskPo task = task(VerificationTaskPo.TaskStatus.RUNNING, """
                {
                  "devices":[{"varName":"lamp_1","deviceLabel":"Desk lamp","templateName":"Light","state":"off"}],
                  "playbackNodes":[{"id":"lamp_1","label":"Desk lamp","templateName":"Light","state":"off",
                    "position":{"x":25,"y":30},"width":160,"height":120}],
                  "rules":[],
                  "attackScenario":{"mode":"NONE"}
                }
                """, """
                {"schemaVersion":1,"manifests":{"Light":{"Name":"Light"}},
                 "modelTokenSourcesByDeviceId":{"lamp_1":"CUSTOM"}}
                """);
        TraceDto recorded = trace("s1");
        recorded.setViolatedSpecJson("{\"id\":\"s1\",\"name\":\"Desk lamp must be on\",\"templateId\":\"1\"}");
        TraceDeviceDto lamp = new TraceDeviceDto();
        lamp.setDeviceId("lamp_1");
        lamp.setDeviceLabel("Desk lamp");
        lamp.setTemplateName("Light");
        lamp.setState("off");
        lamp.setModelTokenSource(ModelTokenSource.CUSTOM);
        lamp.setVariables(List.of());
        recorded.getStates().get(0).setDevices(List.of(lamp));
        assertTrue(complete(task, List.of(recorded), 0, 0));
        // Store the evidence documents as JSON values, matching the production database.
        assertEquals(1, jdbc.update("UPDATE trace SET violated_spec_json = ? FORMAT JSON, "
                        + "states_json = ? FORMAT JSON WHERE id = ?",
                recorded.getViolatedSpecJson(), JsonUtils.toJson(recorded.getStates()), recorded.getId()));

        SqlStatementCapture.take();
        TraceDto current = service.getTrace(user.getId(), recorded.getId());
        assertFalse(onlyVerificationTaskSelect().contains("template_snapshots_json"));
        assertEquals(ModelSemanticsVersion.CURRENT, current.getModelSnapshot().getModelSemanticsVersion());
        assertTrue(current.getModelComplete());
        ObjectNode archivedSnapshot = JsonUtils.fromJson(
                tasks.findById(task.getId()).orElseThrow().getModelSnapshotJson(), ObjectNode.class);
        if (archivedVersion == 0) {
            assertNotNull(archivedSnapshot.remove("modelSemanticsVersion"));
        } else {
            archivedSnapshot.put("modelSemanticsVersion", archivedVersion);
        }
        assertEquals(1, jdbc.update("UPDATE verification_task SET model_snapshot_json = ? WHERE id = ?",
                JsonUtils.toJson(archivedSnapshot), task.getId()));

        TraceDto historical = service.getTrace(user.getId(), recorded.getId());
        assertEquals(archivedVersion, historical.getModelSnapshot().getModelSemanticsVersion());
        assertEquals(current.getStates(), historical.getStates());
        assertEquals(current.getPlaybackScene(), historical.getPlaybackScene());
        assertEquals("off", historical.getStates().get(0).getDevices().get(0).getState());

        SmvGenerator generator = mock(SmvGenerator.class);
        when(generator.buildDeviceSmvMapFromTemplateSnapshots(any(), any()))
                .thenThrow(new AssertionError("Historical trace reached model generation"));
        BoardStorageService board = mock(BoardStorageService.class);
        FormalOperationAdmission admission = mock(FormalOperationAdmission.class);
        when(admission.execute(eq(user.getId()), any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("verification-trace-authority-fixture-key");
        var mapper = new ObjectMapper();
        var tokens = new FixSuggestionTokenService(mapper, jwt);
        FixServiceImpl fixes = new FixServiceImpl(traces, tasks, new TraceMapper(), generator,
                mock(RuleFixer.class), new FixConfig(), board, mock(BoardDataConverter.class),
                tokens, admission, new ModelFingerprint(mapper));

        assertThrows(ModelSemanticsChangedException.class,
                () -> fixes.fix(user.getId(), recorded.getId(), List.of("remove"), null));
        assertThrows(ModelSemanticsChangedException.class,
                () -> fixes.localizeFault(user.getId(), recorded.getId()));
        SqlStatementCapture.take();
        assertThrows(ModelSemanticsChangedException.class,
                () -> fixes.requireCurrentVerificationSource(user.getId(), recorded.getId()));
        assertTrue(onlyVerificationTaskSelect().contains("template_snapshots_json"));
        var suggestion = FixSuggestionDto.builder().strategy("remove").removedRuleIndices(List.of(0)).build();
        String signed = tokens.issue(user.getId(), recorded.getId(), suggestion, null);
        assertThrows(ModelSemanticsChangedException.class,
                () -> fixes.applyFix(user.getId(), recorded.getId(), "remove", suggestion, signed, null));
        verifyNoInteractions(generator, board);
    }

    private static String onlyVerificationTaskSelect() {
        List<String> selects = SqlStatementCapture.take().stream()
                .filter(sql -> sql.startsWith("select ") && sql.contains(" from verification_task "))
                .toList();
        assertEquals(1, selects.size());
        return selects.get(0);
    }

    @Test
    void cancelledOwnerCannotCommitOrExposeTraces() {
        VerificationTaskPo task = task(VerificationTaskPo.TaskStatus.CANCELLED);

        assertFalse(complete(task, List.of(trace("s1"))));

        assertEquals(0, traces.countByUserIdAndVerificationTaskId(user.getId(), task.getId()));
        assertTrue(tasks.findTraceContextsByUserIdAndIdInAndStatus(
                user.getId(), List.of(task.getId()), VerificationTaskPo.TaskStatus.COMPLETED).isEmpty());
        assertEquals(VerificationTaskPo.TaskStatus.CANCELLED, tasks.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    void zeroTraceCompletionRetainsRunProvenance() {
        VerificationTaskPo task = task(VerificationTaskPo.TaskStatus.RUNNING);

        assertTrue(complete(task, List.of()));

        var context = tasks.findRepairTraceContextByIdAndUserIdAndStatus(
                task.getId(), user.getId(), VerificationTaskPo.TaskStatus.COMPLETED).orElseThrow();
        assertEquals(task.getRequestJson(), context.getRequestJson());
        assertEquals(task.getTemplateSnapshotsJson(), context.getTemplateSnapshotsJson());
        assertEquals(0, traces.countByUserIdAndVerificationTaskId(user.getId(), task.getId()));
    }

    @Test
    void traceWriteFailureRollsBackEveryEvidenceRowAndCompletion() {
        VerificationTaskPo task = task(VerificationTaskPo.TaskStatus.RUNNING);
        TraceDto first = trace("s1");
        // Force a real database error after the first IDENTITY insert has succeeded.
        TraceDto second = trace("s2");
        second.setViolatedSpecId("s".repeat(101));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> complete(task, List.of(first, second)));

        assertNotNull(first.getId());
        assertEquals(0, traces.countByUserIdAndVerificationTaskId(user.getId(), task.getId()));
        VerificationTaskPo remaining = tasks.findById(task.getId()).orElseThrow();
        assertEquals(VerificationTaskPo.TaskStatus.RUNNING, remaining.getStatus());
        assertEquals(task.getRequestJson(), remaining.getRequestJson());
        assertEquals(task.getTemplateSnapshotsJson(), remaining.getTemplateSnapshotsJson());
    }

    @Test
    void expiredOwnerCannotCommitEvidence() {
        VerificationTaskPo task = task(VerificationTaskPo.TaskStatus.RUNNING);
        task.setLeaseExpiresAt(tasks.currentDatabaseTime().minusMinutes(1));
        tasks.saveAndFlush(task);
        assertTrue(tasks.findById(task.getId()).orElseThrow().getLeaseExpiresAt()
                .isBefore(tasks.currentDatabaseTime()));

        assertFalse(complete(task, List.of(trace("s1"))));

        assertEquals(0, traces.countByUserIdAndVerificationTaskId(user.getId(), task.getId()));
        assertEquals(VerificationTaskPo.TaskStatus.RUNNING, tasks.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    void runDeletionRemovesTracesAndOwnerInTheSameTransaction() {
        VerificationTaskPo task = task(VerificationTaskPo.TaskStatus.RUNNING);
        assertTrue(complete(task, List.of(trace("s1"), trace("s2"))));

        long removed = new TransactionTemplate(transactionManager).execute(
                status -> service.deleteRun(user.getId(), task.getId(), 2));

        assertEquals(2, removed);
        assertFalse(tasks.existsById(task.getId()));
        assertEquals(0, traces.countByUserIdAndVerificationTaskId(user.getId(), task.getId()));
    }

    private VerificationTaskPo task(VerificationTaskPo.TaskStatus status) {
        return task(status, "{\"rules\":[]}",
                "{\"schemaVersion\":1,\"manifests\":{},\"modelTokenSourcesByDeviceId\":{}}");
    }

    private VerificationTaskPo task(VerificationTaskPo.TaskStatus status, String frozenRequest, String frozenTemplates) {
        LocalDateTime now = tasks.currentDatabaseTime();
        VerificationTaskPo task = tasks.saveAndFlush(VerificationTaskPo.builder().userId(user.getId()).status(status)
                .createdAt(now).startedAt(now)
                .workerId((String) ReflectionTestUtils.getField(service, "workerId"))
                .leaseExpiresAt(now.plusMinutes(5))
                .modelSnapshotJson(JsonUtils.toJson(ModelRunSnapshotDto.captured(now, 1, 0, 2, 0, 1)))
                .modelSemanticsJson(JsonUtils.toJson(ModelSemanticsDto.forRun(
                        AttackScenarioDto.none(), false, 1, 0, 0)))
                .requestJson(frozenRequest)
                .templateSnapshotsJson(frozenTemplates)
                .modeledDeviceAttackPointCount(1).build());
        // H2 inserts a JDBC VARCHAR into JSON as a string; FORMAT JSON matches MySQL's object storage.
        jdbc.update("UPDATE verification_task SET request_json = ? FORMAT JSON, "
                + "template_snapshots_json = ? FORMAT JSON WHERE id = ?",
                task.getRequestJson(), task.getTemplateSnapshotsJson(), task.getId());
        return task;
    }

    private TraceDto trace(String specId) {
        return TraceDto.builder().violatedSpecId(specId).violatedSpecJson("{\"id\":\"" + specId + "\"}")
                .states(List.of(TraceStateDto.builder().stateIndex(1).devices(List.of())
                        .triggeredRules(List.of()).compromisedAutomationLinks(List.of()).build()))
                .build();
    }

    private boolean complete(VerificationTaskPo task, List<TraceDto> evidence) {
        return complete(task, evidence, 2, 1);
    }

    private boolean complete(VerificationTaskPo task, List<TraceDto> evidence, int disabledRules, int skippedSpecs) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "completeTaskAndSaveTraces",
                task, evidence, user.getId(), task.getId(), VerificationOutcome.VIOLATED,
                evidence.size(), List.of(), new ArrayList<>(List.of("done")), "", List.of(),
                disabledRules, skippedSpecs, "MODULE main"));
    }
}
