package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.configure.DatabaseSchemaConstraints;
import cn.edu.nju.Iot_Verify.configure.AsyncTaskAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.FuzzAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.ThreadPoolConfig;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingReplayDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunDto;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.po.FuzzFindingPo;
import cn.edu.nju.Iot_Verify.po.SimulationTracePo;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.po.VerificationResultView;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.FuzzFindingRepository;
import cn.edu.nju.Iot_Verify.repository.FuzzTaskRepository;
import cn.edu.nju.Iot_Verify.repository.SimulationTraceRepository;
import cn.edu.nju.Iot_Verify.repository.TraceRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzSpecificationPresentationProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTraceSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.TraceSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.TraceTokenSourceProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationRunSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTraceContextProjection;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.service.board.MySqlAvailableCondition;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import cn.edu.nju.Iot_Verify.util.mapper.FuzzMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SimulationTraceMapper;
import cn.edu.nju.Iot_Verify.util.mapper.TraceMapper;
import cn.edu.nju.Iot_Verify.util.mapper.VerificationTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ExtendWith(MySqlAvailableCondition.class)
@Import(DatabaseSchemaConstraints.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HistoryReadTransactionMySqlIntegrationTest {
    @Autowired private FuzzTaskRepository fuzzTasks;
    @Autowired private FuzzFindingRepository findings;
    @Autowired private UserRepository users;
    @Autowired private VerificationTaskRepository verificationTasks;
    @Autowired private TraceRepository traces;
    @Autowired private SimulationTraceRepository simulationTraces;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;

    private Long ownerId;

    @BeforeEach
    void createOwner() {
        ownerId = TaskMySqlTestUsers.createOwner(users);
    }

    @AfterEach
    void deleteOwner() {
        if (ownerId != null) users.deleteById(ownerId);
    }

    @Test
    void explorationHistoryReleasesItsConnectionBeforeDecodingCapturedProjections() {
        var transaction = new TransactionTemplate(transactions);
        var saved = transaction.execute(status -> fuzzTasks.saveAndFlush(FuzzTaskPo.builder()
                .userId(ownerId).status(FuzzTaskPo.TaskStatus.COMPLETED).targetSpecIdsJson("[]")
                .maxIterations(1).pathLength(2).populationSize(1).modelSnapshotJson("{}")
                .modelInputSnapshotJson("{\"schemaVersion\":1,\"snapshot\":{\"specifications\":[]},"
                        + "\"modelTokenSourcesByDeviceId\":{}}")
                .build()));
        var mapper = mock(FuzzMapper.class);
        var callerTransaction = new AtomicBoolean(true);
        var decodeFailure = new IllegalStateException("history decoding failed");
        when(mapper.toRunSummaryDtoFromTaskProjection(any(), anyList(), any())).thenAnswer(invocation -> {
            if (callerTransaction.get()) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                throw decodeFailure;
            }
            assertConnectionReleased();
            transaction.executeWithoutResult(status -> fuzzTasks.deleteById(saved.getId()));
            FuzzTaskSummaryProjection run = invocation.getArgument(0);
            FuzzSpecificationPresentationProjection presentation = invocation.getArgument(2);
            assertEquals(saved.getId(), run.getId());
            assertEquals(ownerId, run.getUserId());
            assertEquals(1, run.getMaxIterations());
            assertEquals(saved.getId(), presentation.getId());
            assertEquals("[]", presentation.getSpecificationsJson());
            assertEquals("{}", presentation.getModelTokenSourcesJson());
            assertEquals(List.of(), invocation.getArgument(1));
            return FuzzRunSummaryDto.builder().id(run.getId()).dataAvailable(true).build();
        });
        var target = new FuzzServiceImpl(fuzzTasks, findings, null, null, null, null,
                new ModelFingerprint(new ObjectMapper()), mapper, null, new FuzzAdmissionConfig(),
                new ThreadPoolConfig(), transaction, null, new ObjectMapper());
        try {
            FuzzService service = (FuzzService) transactionalProxy(target);
            assertCallerRollsBack(transaction,
                    () -> fuzzTasks.findById(saved.getId()).orElseThrow().setMaxIterations(2),
                    () -> service.getRuns(ownerId, 0, 25), decodeFailure);
            assertEquals(1, fuzzTasks.findById(saved.getId()).orElseThrow().getMaxIterations());
            callerTransaction.set(false);
            assertEquals(List.of(saved.getId()), service.getRuns(ownerId, 0, 25).stream()
                    .map(FuzzRunSummaryDto::getId).toList());
            assertFalse(fuzzTasks.existsById(saved.getId()));
        } finally {
            target.stopTaskLeaseMaintenance();
            transaction.executeWithoutResult(status -> fuzzTasks.deleteById(saved.getId()));
        }
    }

    @Test
    void verificationHistoryDecodesCapturedTraceAndRunProjectionsAfterReturningItsConnection() {
        var transaction = new TransactionTemplate(transactions);
        var saved = transaction.execute(status -> {
            LocalDateTime timestamp = LocalDateTime.now().withNano(0);
            var run = verificationTasks.saveAndFlush(VerificationTaskPo.builder().userId(ownerId)
                    .status(VerificationTaskPo.TaskStatus.COMPLETED).createdAt(timestamp)
                    .completedAt(timestamp).smvModelContent("MODULE main").build());
            traces.saveAndFlush(TracePo.builder().userId(ownerId).verificationTaskId(run.getId())
                    .violatedSpecId("spec-1").violatedSpecJson("{\"id\":\"spec-1\",\"name\": \"Safety requirement\", \"templateId\":\"1\"}")
                    .statesJson("[{\"devices\":[{\"deviceId\":\"light\",\"modelTokenSource\":\"BUNDLED\"}]}]")
                    .stateCount(1).createdAt(timestamp).build());
            return run;
        });
        var callerTransaction = new AtomicBoolean(true);
        var decodeFailure = new IllegalStateException("history decoding failed");
        var traceMapper = new TraceMapper() {
            @Override
            public TraceSummaryDto toSummaryDto(TraceSummaryProjection trace, TraceTokenSourceProjection tokenSources) {
                if (!callerTransaction.get()) {
                    assertConnectionReleased();
                    transaction.executeWithoutResult(status -> {
                        traces.deleteByUserIdAndVerificationTaskId(ownerId, saved.getId());
                        verificationTasks.deleteById(saved.getId());
                    });
                }
                return super.toSummaryDto(trace, tokenSources);
            }
        };
        var mapper = mock(VerificationTaskMapper.class);
        when(mapper.toRunSummaryDto(any(), eq(1))).thenAnswer(invocation -> {
            if (callerTransaction.get()) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                throw decodeFailure;
            }
            assertConnectionReleased();
            VerificationRunSummaryProjection run = invocation.getArgument(0);
            assertEquals(saved.getId(), run.getId());
            assertEquals(saved.getCompletedAt(), run.getCompletedAt());
            return VerificationRunSummaryDto.builder().id(run.getId()).counterexampleCount(1).build();
        });
        var target = new VerificationServiceImpl(null, null, null, null, null, verificationTasks,
                traces, traceMapper, null, null, mapper, new ObjectMapper(), null, null,
                transaction, null, null, new AsyncTaskAdmissionConfig(), null);
        try {
            VerificationService service = (VerificationService) transactionalProxy(target);
            assertCallerRollsBack(transaction,
                    () -> verificationTasks.findById(saved.getId()).orElseThrow().setProcessingTimeMs(123L),
                    () -> service.getRuns(ownerId), decodeFailure);
            assertNull(verificationTasks.findById(saved.getId()).orElseThrow().getProcessingTimeMs());
            callerTransaction.set(false);
            var result = service.getRuns(ownerId).get(0);
            assertEquals(saved.getId(), result.getId());
            assertTrue(result.getDataAvailable());
            assertEquals(1, result.getCounterexamples().size());
            assertTrue(result.getCounterexamples().get(0).getDataAvailable());
            assertFalse(verificationTasks.existsById(saved.getId()));
        } finally {
            target.stopTaskLeaseMaintenance();
            transaction.executeWithoutResult(status -> {
                traces.deleteByUserIdAndVerificationTaskId(ownerId, saved.getId());
                verificationTasks.deleteById(saved.getId());
            });
        }
    }

    private enum ExplorationDetail { RUN, FINDINGS, FINDING, REPLAY }

    @ParameterizedTest
    @EnumSource(ExplorationDetail.class)
    void explorationDetailCapturesFindingEvidenceBeforeReleasingItsConnection(ExplorationDetail detail) {
        var transaction = new TransactionTemplate(transactions);
        var saved = transaction.execute(status -> {
            var run = fuzzTasks.saveAndFlush(FuzzTaskPo.builder().userId(ownerId)
                    .status(FuzzTaskPo.TaskStatus.COMPLETED).targetSpecIdsJson("[]")
                    .maxIterations(1).pathLength(2).populationSize(1).modelSnapshotJson("{}")
                    .modelInputSnapshotJson("{}").build());
            return findings.saveAndFlush(FuzzFindingPo.builder().userId(ownerId).fuzzTaskId(run.getId())
                    .violatedSpecId("spec-1").violatedSpecJson("{\"id\":\"spec-1\"}")
                    .firstViolationStep(0).statesJson("[{\"devices\":[]}]")
                    .inputEventsJson("[]").seed(17L).stateCount(1).build());
        });
        Long runId = saved.getFuzzTaskId();
        Long findingId = saved.getId();
        var findingDto = FuzzFindingDto.builder().id(findingId).fuzzTaskId(runId).build();
        var mapper = mock(FuzzMapper.class);
        var callerTransaction = new AtomicBoolean(true);
        var decodeFailure = new IllegalStateException("detail decoding failed");
        BiConsumer<FuzzTaskPo, FuzzFindingPo> decode = (run, finding) -> {
            requireStandaloneDecoder(callerTransaction, decodeFailure);
            transaction.executeWithoutResult(status -> {
                findings.deleteByUserIdAndFuzzTaskId(ownerId, runId);
                fuzzTasks.deleteById(runId);
            });
            assertEquals(runId, run.getId());
            assertEquals("{}", run.getModelSnapshotJson());
            assertEquals(findingId, finding.getId());
            assertEquals(saved.getStatesJson(), finding.getStatesJson());
            assertEquals(17L, finding.getSeed());
        };
        when(mapper.toRunDto(any(), anyList())).thenAnswer(invocation -> {
            List<FuzzFindingPo> evidence = invocation.getArgument(1);
            assertEquals(1, evidence.size());
            decode.accept(invocation.getArgument(0), evidence.get(0));
            return FuzzRunDto.builder().id(runId).findings(List.of(findingDto)).build();
        });
        when(mapper.toFindingDto(any(), any(), eq(1L))).thenAnswer(invocation -> {
            decode.accept(invocation.getArgument(0), invocation.getArgument(1));
            return findingDto;
        });
        when(mapper.toFindingReplayDto(any(), any(), eq(1L))).thenAnswer(invocation -> {
            decode.accept(invocation.getArgument(0), invocation.getArgument(1));
            return FuzzFindingReplayDto.builder().finding(findingDto).build();
        });
        var target = new FuzzServiceImpl(fuzzTasks, findings, null, null, null, null,
                new ModelFingerprint(new ObjectMapper()), mapper, null, new FuzzAdmissionConfig(),
                new ThreadPoolConfig(), transaction, null, new ObjectMapper());
        try {
            FuzzService service = (FuzzService) transactionalProxy(target);
            LongFunction<Long> readId = switch (detail) {
                case RUN -> userId -> service.getRun(userId, runId).getId();
                case FINDINGS -> userId -> service.getFindings(userId, runId).get(0).getId();
                case FINDING -> userId -> service.getFinding(userId, findingId).getId();
                case REPLAY -> userId -> service.getFindingReplay(userId, findingId).getFinding().getId();
            };
            assertThrows(ResourceNotFoundException.class, () -> readId.apply(ownerId + 1));
            assertCallerRollsBack(transaction,
                    () -> fuzzTasks.findById(runId).orElseThrow().setMaxIterations(2),
                    () -> readId.apply(ownerId), decodeFailure);
            assertEquals(1, fuzzTasks.findById(runId).orElseThrow().getMaxIterations());
            callerTransaction.set(false);
            assertEquals(detail == ExplorationDetail.RUN ? runId : findingId, readId.apply(ownerId));
            assertFalse(fuzzTasks.existsById(runId));
        } finally {
            target.stopTaskLeaseMaintenance();
            transaction.executeWithoutResult(status -> {
                findings.deleteByUserIdAndFuzzTaskId(ownerId, runId);
                fuzzTasks.deleteById(runId);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void simulationHistoryDecodesAfterCaptureAndRetainsCallerRollback(boolean detail) {
        var transaction = new TransactionTemplate(transactions);
        var saved = transaction.execute(status -> simulationTraces.saveAndFlush(SimulationTracePo.builder()
                .userId(ownerId).requestedSteps(1).steps(0).stateCount(1).statesJson("[{\"devices\":[]}]")
                .requestJson("{}").smvModelContent("MODULE main").build()));
        var mapper = mock(SimulationTraceMapper.class);
        var callerTransaction = new AtomicBoolean(true);
        var decodeFailure = new IllegalStateException("simulation decoding failed");
        Runnable decode = () -> {
            requireStandaloneDecoder(callerTransaction, decodeFailure);
            transaction.executeWithoutResult(status -> simulationTraces.deleteById(saved.getId()));
        };
        if (detail) {
            when(mapper.toDto(any())).thenAnswer(invocation -> {
                decode.run();
                SimulationTracePo row = invocation.getArgument(0);
                assertEquals(new ObjectMapper().readTree("[{\"devices\":[]}]"),
                        new ObjectMapper().readTree(row.getStatesJson()));
                assertEquals("MODULE main", row.getSmvModelContent());
                assertEquals("{}", row.getRequestJson());
                return SimulationTraceDto.builder().id(row.getId()).build();
            });
        } else {
            when(mapper.toSummaryProjectionDtoList(anyList())).thenAnswer(invocation -> {
                decode.run();
                List<SimulationTraceSummaryProjection> rows = invocation.getArgument(0);
                assertEquals(1, rows.size());
                assertEquals(saved.getId(), rows.get(0).getId());
                assertEquals(1, rows.get(0).getStateCount());
                assertTrue(rows.get(0).getHasSmvModel());
                return List.of(SimulationTraceSummaryDto.builder().id(rows.get(0).getId()).build());
            });
        }
        var target = new SimulationServiceImpl(null, null, null, null, null, simulationTraces,
                null, null, mapper, null, new ObjectMapper(), null, null, transaction,
                null, null, new AsyncTaskAdmissionConfig(), null);
        try {
            SimulationService service = (SimulationService) transactionalProxy(target);
            Runnable read = () -> {
                if (detail) service.getSimulation(ownerId, saved.getId());
                else service.getUserSimulations(ownerId);
            };
            assertCallerRollsBack(transaction,
                    () -> simulationTraces.findById(saved.getId()).orElseThrow().setRequestedSteps(2),
                    read, decodeFailure);
            assertEquals(1, simulationTraces.findById(saved.getId()).orElseThrow().getRequestedSteps());
            callerTransaction.set(false);
            read.run();
            assertFalse(simulationTraces.existsById(saved.getId()));
        } finally {
            target.stopTaskLeaseMaintenance();
            transaction.executeWithoutResult(status -> simulationTraces.deleteById(saved.getId()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void verificationDetailsCaptureRelatedRowsBeforeDecodingAndRetainCallerRollback(boolean summary) {
        var transaction = new TransactionTemplate(transactions);
        var saved = transaction.execute(status -> {
            var run = verificationTasks.saveAndFlush(VerificationTaskPo.builder().userId(ownerId)
                    .status(VerificationTaskPo.TaskStatus.COMPLETED).smvModelContent("MODULE main").build());
            traces.saveAndFlush(TracePo.builder().userId(ownerId).verificationTaskId(run.getId())
                    .violatedSpecId("spec-1").violatedSpecJson("{\"id\":\"spec-1\"}")
                    .stateCount(1).statesJson("[{\"devices\":[{\"deviceId\":\"light\",\"modelTokenSource\":\"BUNDLED\"}]}]")
                    .build());
            return run;
        });
        var mapper = mock(VerificationTaskMapper.class);
        var traceMapper = mock(TraceMapper.class);
        var callerTransaction = new AtomicBoolean(true);
        var decodeFailure = new IllegalStateException("verification decoding failed");
        Runnable decode = () -> {
            requireStandaloneDecoder(callerTransaction, decodeFailure);
            transaction.executeWithoutResult(status -> {
                traces.deleteByUserIdAndVerificationTaskId(ownerId, saved.getId());
                verificationTasks.deleteById(saved.getId());
            });
        };
        if (summary) {
            when(traceMapper.toSummaryDto(any(), any())).thenAnswer(invocation -> {
                decode.run();
                TraceSummaryProjection row = invocation.getArgument(0);
                assertEquals(saved.getId(), row.getVerificationTaskId());
                assertEquals("[\"light\"]", row.getDeviceIdsJson());
                return TraceSummaryDto.builder().id(row.getId()).build();
            });
            when(mapper.toRunDto(any(), eq(1))).thenAnswer(invocation -> {
                assertConnectionReleased();
                VerificationResultView row = invocation.getArgument(0);
                assertEquals(saved.getId(), row.getId());
                return VerificationRunDto.builder().id(row.getId()).build();
            });
        } else {
            when(traceMapper.toDtoList(anyList(), anyMap())).thenAnswer(invocation -> {
                decode.run();
                List<TracePo> rows = invocation.getArgument(0);
                Map<Long, VerificationTraceContextProjection> contexts = invocation.getArgument(1);
                assertEquals(1, rows.size());
                assertEquals(saved.getId(), rows.get(0).getVerificationTaskId());
                assertEquals(saved.getId(), contexts.get(saved.getId()).getId());
                assertEquals("spec-1", rows.get(0).getViolatedSpecId());
                return List.of(TraceDto.builder().id(rows.get(0).getId()).build());
            });
        }
        var target = new VerificationServiceImpl(null, null, null, null, null, verificationTasks,
                traces, traceMapper, null, null, mapper, new ObjectMapper(), null, null,
                transaction, null, null, new AsyncTaskAdmissionConfig(), null);
        try {
            VerificationService service = (VerificationService) transactionalProxy(target);
            Runnable read = () -> {
                if (summary) service.getRun(ownerId, saved.getId());
                else service.getRunTraces(ownerId, saved.getId());
            };
            assertCallerRollsBack(transaction,
                    () -> verificationTasks.findById(saved.getId()).orElseThrow().setProcessingTimeMs(123L),
                    read, decodeFailure);
            assertNull(verificationTasks.findById(saved.getId()).orElseThrow().getProcessingTimeMs());
            callerTransaction.set(false);
            read.run();
            assertFalse(verificationTasks.existsById(saved.getId()));
        } finally {
            target.stopTaskLeaseMaintenance();
            transaction.executeWithoutResult(status -> {
                traces.deleteByUserIdAndVerificationTaskId(ownerId, saved.getId());
                verificationTasks.deleteById(saved.getId());
            });
        }
    }

    private void requireStandaloneDecoder(AtomicBoolean callerTransaction, IllegalStateException decodeFailure) {
        if (callerTransaction.get()) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            throw decodeFailure;
        }
        assertConnectionReleased();
    }

    private void assertCallerRollsBack(TransactionTemplate transaction, Runnable edit,
                                     Runnable read, IllegalStateException decodeFailure) {
        assertThrows(UnexpectedRollbackException.class, () -> transaction.executeWithoutResult(status -> {
            edit.run();
            assertSame(decodeFailure, assertThrows(IllegalStateException.class, read::run));
            assertTrue(status.isRollbackOnly(), "a caught read failure must still mark the caller for rollback");
        }));
    }

    private void assertConnectionReleased() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "pure history decoding must not retain a transaction");
        assertEquals(0, assertInstanceOf(HikariDataSource.class, dataSource)
                .getHikariPoolMXBean().getActiveConnections());
    }

    private Object transactionalProxy(Object target) {
        // Exercise annotations too: calling a target directly would miss an accidentally restored outer transaction.
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        interceptor.afterPropertiesSet();
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        return proxy.getProxy();
    }
}
