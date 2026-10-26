package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.configure.AsyncTaskAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.FuzzAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.ThreadPoolConfig;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunDto;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HistoryReadTransactionMySqlIntegrationTest {
    @Autowired private FuzzTaskRepository fuzzTasks;
    @Autowired private FuzzFindingRepository findings;
    @Autowired private VerificationTaskRepository verificationTasks;
    @Autowired private TraceRepository traces;
    @Autowired private SimulationTraceRepository simulationTraces;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;

    @Test
    void explorationHistoryReleasesItsConnectionBeforeDecodingCapturedProjections() {
        var transaction = new TransactionTemplate(transactions);
        var saved = transaction.execute(status -> fuzzTasks.saveAndFlush(FuzzTaskPo.builder()
                .userId(7L).status(FuzzTaskPo.TaskStatus.COMPLETED).targetSpecIdsJson("[]")
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
            assertEquals(7L, run.getUserId());
            assertEquals(1, run.getMaxIterations());
            assertEquals(saved.getId(), presentation.getId());
            assertEquals("[]", presentation.getSpecificationsJson());
            assertEquals("{}", presentation.getModelTokenSourcesJson());
            assertEquals(List.of(), invocation.getArgument(1));
            return FuzzRunSummaryDto.builder().id(run.getId()).dataAvailable(true).build();
        });
        var target = new FuzzServiceImpl(fuzzTasks, findings, null, null, null, null,
                new ModelFingerprint(new ObjectMapper()), mapper, null, new FuzzAdmissionConfig(),
                new ThreadPoolConfig(), transaction, new ObjectMapper());
        try {
            FuzzService service = (FuzzService) transactionalProxy(target);
            assertCallerRollsBack(transaction,
                    () -> fuzzTasks.findById(saved.getId()).orElseThrow().setMaxIterations(2),
                    () -> service.getRuns(7L, 0, 25), decodeFailure);
            assertEquals(1, fuzzTasks.findById(saved.getId()).orElseThrow().getMaxIterations());
            callerTransaction.set(false);
            assertEquals(List.of(saved.getId()), service.getRuns(7L, 0, 25).stream()
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
            var run = verificationTasks.saveAndFlush(VerificationTaskPo.builder().userId(7L)
                    .status(VerificationTaskPo.TaskStatus.COMPLETED).createdAt(timestamp)
                    .completedAt(timestamp).smvModelContent("MODULE main").build());
            traces.saveAndFlush(TracePo.builder().userId(7L).verificationTaskId(run.getId())
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
                        traces.deleteByUserIdAndVerificationTaskId(7L, saved.getId());
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
                    () -> service.getRuns(7L), decodeFailure);
            assertNull(verificationTasks.findById(saved.getId()).orElseThrow().getProcessingTimeMs());
            callerTransaction.set(false);
            var result = service.getRuns(7L).get(0);
            assertEquals(saved.getId(), result.getId());
            assertTrue(result.getDataAvailable());
            assertEquals(1, result.getCounterexamples().size());
            assertTrue(result.getCounterexamples().get(0).getDataAvailable());
            assertFalse(verificationTasks.existsById(saved.getId()));
        } finally {
            target.stopTaskLeaseMaintenance();
            transaction.executeWithoutResult(status -> {
                traces.deleteByUserIdAndVerificationTaskId(7L, saved.getId());
                verificationTasks.deleteById(saved.getId());
            });
        }
    }

    @Test
    void explorationDetailCapturesFindingEvidenceBeforeReleasingItsConnection() {
        var transaction = new TransactionTemplate(transactions);
        var saved = transaction.execute(status -> {
            var run = fuzzTasks.saveAndFlush(FuzzTaskPo.builder().userId(7L)
                    .status(FuzzTaskPo.TaskStatus.COMPLETED).targetSpecIdsJson("[]")
                    .maxIterations(1).pathLength(2).populationSize(1).modelSnapshotJson("{}")
                    .modelInputSnapshotJson("{}").build());
            findings.saveAndFlush(FuzzFindingPo.builder().userId(7L).fuzzTaskId(run.getId())
                    .violatedSpecId("spec-1").violatedSpecJson("{\"id\":\"spec-1\"}")
                    .firstViolationStep(0).statesJson("[{\"devices\":[]}]")
                    .inputEventsJson("[]").seed(17L).stateCount(1).build());
            return run;
        });
        var mapper = mock(FuzzMapper.class);
        var callerTransaction = new AtomicBoolean(true);
        var decodeFailure = new IllegalStateException("detail decoding failed");
        when(mapper.toRunDto(any(), anyList())).thenAnswer(invocation -> {
            requireStandaloneDecoder(callerTransaction, decodeFailure);
            transaction.executeWithoutResult(status -> {
                findings.deleteByUserIdAndFuzzTaskId(7L, saved.getId());
                fuzzTasks.deleteById(saved.getId());
            });
            FuzzTaskPo run = invocation.getArgument(0);
            List<FuzzFindingPo> evidence = invocation.getArgument(1);
            assertEquals(saved.getId(), run.getId());
            assertEquals("{}", run.getModelSnapshotJson());
            assertEquals(1, evidence.size());
            assertEquals(new ObjectMapper().readTree("[{\"devices\":[]}]"),
                    new ObjectMapper().readTree(evidence.get(0).getStatesJson()));
            assertEquals(17L, evidence.get(0).getSeed());
            return FuzzRunDto.builder().id(run.getId()).build();
        });
        var target = new FuzzServiceImpl(fuzzTasks, findings, null, null, null, null,
                new ModelFingerprint(new ObjectMapper()), mapper, null, new FuzzAdmissionConfig(),
                new ThreadPoolConfig(), transaction, new ObjectMapper());
        try {
            FuzzService service = (FuzzService) transactionalProxy(target);
            assertCallerRollsBack(transaction,
                    () -> fuzzTasks.findById(saved.getId()).orElseThrow().setMaxIterations(2),
                    () -> service.getRun(7L, saved.getId()), decodeFailure);
            assertEquals(1, fuzzTasks.findById(saved.getId()).orElseThrow().getMaxIterations());
            callerTransaction.set(false);
            assertEquals(saved.getId(), service.getRun(7L, saved.getId()).getId());
            assertFalse(fuzzTasks.existsById(saved.getId()));
        } finally {
            target.stopTaskLeaseMaintenance();
            transaction.executeWithoutResult(status -> {
                findings.deleteByUserIdAndFuzzTaskId(7L, saved.getId());
                fuzzTasks.deleteById(saved.getId());
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void simulationHistoryDecodesAfterCaptureAndRetainsCallerRollback(boolean detail) {
        var transaction = new TransactionTemplate(transactions);
        var saved = transaction.execute(status -> simulationTraces.saveAndFlush(SimulationTracePo.builder()
                .userId(7L).requestedSteps(1).steps(0).stateCount(1).statesJson("[{\"devices\":[]}]")
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
                if (detail) service.getSimulation(7L, saved.getId());
                else service.getUserSimulations(7L);
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
            var run = verificationTasks.saveAndFlush(VerificationTaskPo.builder().userId(7L)
                    .status(VerificationTaskPo.TaskStatus.COMPLETED).smvModelContent("MODULE main").build());
            traces.saveAndFlush(TracePo.builder().userId(7L).verificationTaskId(run.getId())
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
                traces.deleteByUserIdAndVerificationTaskId(7L, saved.getId());
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
                if (summary) service.getRun(7L, saved.getId());
                else service.getRunTraces(7L, saved.getId());
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
                traces.deleteByUserIdAndVerificationTaskId(7L, saved.getId());
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
