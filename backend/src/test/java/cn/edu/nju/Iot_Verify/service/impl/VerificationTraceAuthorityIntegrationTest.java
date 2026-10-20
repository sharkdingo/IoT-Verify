package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.configure.AsyncTaskAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.TraceRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.service.ChatExecutionLeaseGuard;
import cn.edu.nju.Iot_Verify.service.FormalOperationAdmission;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.EnvironmentProvenanceCollector;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import cn.edu.nju.Iot_Verify.util.mapper.TraceMapper;
import cn.edu.nju.Iot_Verify.util.mapper.VerificationTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
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
        assertEquals(task.getTemplateSnapshotsJson(), contexts.get(0).getTemplateSnapshotsJson());
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

        var context = tasks.findTraceContextsByUserIdAndIdInAndStatus(
                user.getId(), List.of(task.getId()), VerificationTaskPo.TaskStatus.COMPLETED).get(0);
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
        LocalDateTime now = tasks.currentDatabaseTime();
        VerificationTaskPo task = tasks.saveAndFlush(VerificationTaskPo.builder().userId(user.getId()).status(status)
                .createdAt(now).startedAt(now)
                .workerId((String) ReflectionTestUtils.getField(service, "workerId"))
                .leaseExpiresAt(now.plusMinutes(5))
                .modelSnapshotJson(JsonUtils.toJson(ModelRunSnapshotDto.captured(now, 1, 0, 2, 0, 1)))
                .modelSemanticsJson(JsonUtils.toJson(ModelSemanticsDto.forRun(
                        AttackScenarioDto.none(), false, 1, 0, 0)))
                .requestJson("{\"rules\":[]}")
                .templateSnapshotsJson("{\"schemaVersion\":1,\"manifests\":{},\"modelTokenSourcesByDeviceId\":{}}")
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
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "completeTaskAndSaveTraces",
                task, evidence, user.getId(), task.getId(), VerificationOutcome.VIOLATED,
                evidence.size(), List.of(), new ArrayList<>(List.of("done")), "", List.of(),
                2, 1, "MODULE main"));
    }
}
