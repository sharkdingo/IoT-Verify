package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.CapturingThreadPoolTaskExecutor;
import cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.DirectThreadPoolTaskExecutor;
import cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.FailingThreadPoolTaskExecutor;
import cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.RejectingThreadPoolTaskExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SimulationOutput;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.EnvironmentProvenanceCollector;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.configure.AsyncTaskAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationRequestDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationResultDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.SimulationExecutionException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskQuotaExceededException;
import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;
import cn.edu.nju.Iot_Verify.po.SimulationTracePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.SimulationTaskRepository;
import cn.edu.nju.Iot_Verify.repository.SimulationTraceRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTaskProgressProjection;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTraceSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.SimulationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.service.ChatExecutionLeaseGuard;
import cn.edu.nju.Iot_Verify.service.FormalOperationAdmission;
import cn.edu.nju.Iot_Verify.util.mapper.SimulationTaskMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SimulationTraceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.edu.nju.Iot_Verify.service.impl.ModelRunBoardFixture.playbackNodes;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunBoardFixture.stubBoardSnapshot;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunBoardFixture.stubResolvedGeneration;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.blockWorker;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.createTempModelFile;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.interruptQueuedRun;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.readRequestJson;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.readResultCode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SimulationServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
class SimulationServiceImplTest {
    @TempDir
    Path modelRunTempDir;

    @Test
    void progressUsesOnlyOwnedScalarsAndPreservesTerminalFallbacks() {
        var progress = mock(SimulationTaskProgressProjection.class);
        when(progress.getProgress()).thenReturn(null);
        when(simulationTaskRepository.findProgressByIdAndUserId(7L, 1L)).thenReturn(Optional.of(progress));
        for (var status : SimulationTaskPo.TaskStatus.values()) {
            when(progress.getStatus()).thenReturn(status);
            assertEquals(status == SimulationTaskPo.TaskStatus.PENDING || status == SimulationTaskPo.TaskStatus.RUNNING
                    ? 0 : 100, service.getTaskProgress(1L, 7L));
        }
        when(progress.getProgress()).thenReturn(23);
        assertEquals(23, service.getTaskProgress(1L, 7L));
        when(simulationTaskRepository.findProgressByIdAndUserId(7L, 2L)).thenReturn(Optional.empty());
        assertThrows(cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException.class,
                () -> service.getTaskProgress(2L, 7L));
        verify(simulationTaskRepository, never()).findByIdAndUserId(any(), any());
    }

    @Mock private SmvGenerator smvGenerator;
    @Mock private EnvironmentProvenanceCollector provenanceCollector;
    @Mock private SmvTraceParser smvTraceParser;
    @Mock private NusmvExecutor nusmvExecutor;
    @Mock private NusmvConfig nusmvConfig;
    private SimulationTraceRepository simulationTraceRepository;
    @Mock private SimulationTaskRepository simulationTaskRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatExecutionLeaseGuard chatExecutionLeaseGuard;
    @Mock private FormalOperationAdmission formalOperationAdmission;
    @Mock private SimulationTraceMapper simulationTraceMapper;
    @Mock private SimulationTaskMapper simulationTaskMapper;
    @Mock private BoardDataConverter boardDataConverter;
    private TransactionTemplate transactionTemplate;
    private SimpleTransactionStatus lastTransactionStatus;

    private SimulationServiceImpl service;
    private DirectThreadPoolTaskExecutor simulationTaskExecutor;
    private ThreadPoolTaskExecutor syncSimulationExecutor;
    private Method doSimulate;
    private long nextSimulationTraceId;
    private final List<SimpleTransactionStatus> transactionStatuses = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        transactionStatuses.clear();
        nextSimulationTraceId = 1L;
        simulationTraceRepository = mock(SimulationTraceRepository.class, withSettings().defaultAnswer(invocation -> {
            if ("save".equals(invocation.getMethod().getName())
                    && invocation.getArguments().length == 1
                    && invocation.getArgument(0) instanceof SimulationTracePo po) {
                if (po.getId() == null) {
                    po.setId(nextSimulationTraceId++);
                }
                return po;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        }));

        simulationTaskExecutor = new DirectThreadPoolTaskExecutor();
        syncSimulationExecutor = new ThreadPoolTaskExecutor();
        syncSimulationExecutor.setCorePoolSize(1);
        syncSimulationExecutor.setMaxPoolSize(1);
        syncSimulationExecutor.setQueueCapacity(10);
        syncSimulationExecutor.setThreadNamePrefix("test-sync-simulation-");
        syncSimulationExecutor.initialize();
        transactionTemplate = inlineTransactionTemplate();
        lenient().when(formalOperationAdmission.execute(anyLong(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get());

        service = new SimulationServiceImpl(
                smvGenerator, provenanceCollector, smvTraceParser, nusmvExecutor, nusmvConfig,
                simulationTraceRepository, simulationTaskRepository, userRepository,
                simulationTraceMapper, simulationTaskMapper, new ObjectMapper().findAndRegisterModules(),
                simulationTaskExecutor, syncSimulationExecutor, transactionTemplate,
                chatExecutionLeaseGuard, formalOperationAdmission, new AsyncTaskAdmissionConfig(), boardDataConverter);
        lenient().when(simulationTaskRepository.currentDatabaseTime())
                .thenAnswer(invocation -> LocalDateTime.now());
        lenient().when(simulationTaskRepository.findByIdForUpdate(anyLong())).thenAnswer(invocation -> Optional.of(
                SimulationTaskPo.builder().id(invocation.getArgument(0)).build()));
        var queuedProgress = mock(SimulationTaskProgressProjection.class);
        lenient().when(queuedProgress.getStatus()).thenReturn(SimulationTaskPo.TaskStatus.PENDING);
        lenient().when(simulationTaskRepository.findProgressByIdAndUserId(anyLong(), anyLong()))
                .thenReturn(Optional.of(queuedProgress));
        lenient().when(simulationTaskRepository.updateProgressIfActive(anyLong(), anyInt(), any(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        lenient().when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(new UserPo()));
        lenient().when(userRepository.existsById(anyLong())).thenReturn(true);
        DeviceManifest templateSnapshot = DeviceManifest.builder().name("light").build();
        lenient().when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(
                        Map.of("light", templateSnapshot), Map.of()));
        lenient().when(smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(Map.of());

        doSimulate = SimulationServiceImpl.class.getDeclaredMethod(
                "doSimulate", Long.class, List.class, List.class,
                int.class, boolean.class, SimulationRequestDto.class,
                Map.class, ModelRunSnapshotDto.class, SmvGenerator.TempModelContext.class);
        doSimulate.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        syncSimulationExecutor.shutdown();
    }

    private SimulationServiceImpl serviceWithSimulationExecutor(ThreadPoolTaskExecutor executor) {
        return new SimulationServiceImpl(
                smvGenerator, provenanceCollector, smvTraceParser, nusmvExecutor, nusmvConfig,
                simulationTraceRepository, simulationTaskRepository, userRepository,
                simulationTraceMapper, simulationTaskMapper, new ObjectMapper().findAndRegisterModules(),
                executor, syncSimulationExecutor, transactionTemplate, chatExecutionLeaseGuard,
                formalOperationAdmission, new AsyncTaskAdmissionConfig(), boardDataConverter);
    }

    private void assignSimulationTaskId(long taskId) {
        when(simulationTaskRepository.save(any(SimulationTaskPo.class))).thenAnswer(invocation -> {
            SimulationTaskPo task = invocation.getArgument(0);
            task.setId(taskId);
            return task;
        });
    }

    private SimulationServiceImpl serviceWithAdmissionConfig(AsyncTaskAdmissionConfig admissionConfig) {
        return new SimulationServiceImpl(
                smvGenerator, provenanceCollector, smvTraceParser, nusmvExecutor, nusmvConfig,
                simulationTraceRepository, simulationTaskRepository, userRepository,
                simulationTraceMapper, simulationTaskMapper, new ObjectMapper().findAndRegisterModules(),
                simulationTaskExecutor, syncSimulationExecutor, transactionTemplate,
                chatExecutionLeaseGuard, formalOperationAdmission, admissionConfig, boardDataConverter);
    }

    @Test
    void requestSnapshotPreservesServerCapturedTokenProvenance() throws Exception {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("bundled_device");
        device.setTemplateName("bundled-template");
        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        SimulationRequestDto request = new SimulationRequestDto();
        request.setDevices(List.of(device));
        Method method = SimulationServiceImpl.class.getDeclaredMethod(
                "snapshotRequest", SimulationRequestDto.class, AttackScenarioDto.class);
        method.setAccessible(true);

        SimulationRequestDto snapshot = (SimulationRequestDto) method.invoke(
                service, request, AttackScenarioDto.none());

        assertNotSame(device, snapshot.getDevices().get(0));
        assertEquals(ModelTokenSource.BUNDLED,
                snapshot.getDevices().get(0).getModelTokenSource());
    }

    private TransactionTemplate inlineTransactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                lastTransactionStatus = new SimpleTransactionStatus();
                transactionStatuses.add(lastTransactionStatus);
                return lastTransactionStatus;
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    private List<DeviceVerificationDto> singleDevice() {
        DeviceVerificationDto d = new DeviceVerificationDto();
        d.setVarName("testdevice");
        d.setTemplateName("light");
        return List.of(d);
    }

    private ModelRunSnapshotDto runSnapshot() {
        return ModelRunSnapshotDto.captured(LocalDateTime.now(), 1, 0, 0, 0, 1);
    }

    // ==================== doSimulate tests ====================

    @Test
    void doSimulate_executorFails_returnsErrorResult() throws Exception {
        File fakeFile = createTempModelFile(modelRunTempDir);
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        stubResolvedGeneration(smvGenerator).thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.error("NuSMV exited with code 1."));

        SimulationResultDto result = (SimulationResultDto) doSimulate.invoke(
                service, 1L, singleDevice(), List.of(), 10, false,
                simRequest(singleDevice(), List.of(), 10, false, 0, false),
                Map.of(), runSnapshot(),
                SmvGenerator.TempModelContext.sync());

        assertTrue(result.getStates().isEmpty());
        assertEquals(0, result.getSteps());
        assertEquals(10, result.getRequestedSteps());
        assertTrue(result.getLogs().stream().anyMatch(l -> l.contains("failed")));
        assertEquals(500, readResultCode(fakeFile));
    }

    @Test
    void doSimulate_executorBusy_throwsServiceUnavailable() throws Exception {
        File fakeFile = createTempModelFile(modelRunTempDir);
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        stubResolvedGeneration(smvGenerator).thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.busy("NuSMV simulation is busy, please retry later"));

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () ->
                doSimulate.invoke(service, 1L, singleDevice(), List.of(), 10, false,
                        simRequest(singleDevice(), List.of(), 10, false, 0, false),
                        Map.of(), runSnapshot(),
                        SmvGenerator.TempModelContext.sync()));

        assertInstanceOf(ServiceUnavailableException.class, ex.getCause());
        assertEquals(503, readResultCode(fakeFile));
    }

    @Test
    void doSimulate_smvGenerationError_propagatesSmvGenerationException() throws Exception {
        stubResolvedGeneration(smvGenerator)
                .thenThrow(SmvGenerationException.ambiguousDeviceReference("Sensor", List.of("sensor_1", "sensor_2")));

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () ->
                doSimulate.invoke(service, 1L, singleDevice(), List.of(), 10, false,
                        simRequest(singleDevice(), List.of(), 10, false, 0, false),
                        Map.of(), runSnapshot(),
                        SmvGenerator.TempModelContext.sync()));

        assertInstanceOf(SmvGenerationException.class, ex.getCause());
        SmvGenerationException cause = (SmvGenerationException) ex.getCause();
        assertEquals("AMBIGUOUS_DEVICE_REFERENCE", cause.getErrorCategory());
    }

    @Test
    void doSimulate_emptyStates_returnsZeroSteps() throws Exception {
        File fakeFile = createTempModelFile(modelRunTempDir);
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        stubResolvedGeneration(smvGenerator).thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("-> State: 1.1 <-\n", "raw"));
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(List.of());

        SimulationResultDto result = (SimulationResultDto) doSimulate.invoke(
                service, 1L, singleDevice(), List.of(), 10, false,
                simRequest(singleDevice(), List.of(), 10, false, 0, false),
                Map.of(), runSnapshot(),
                SmvGenerator.TempModelContext.sync());

        assertTrue(result.getStates().isEmpty());
        assertEquals(0, result.getSteps());
        assertEquals(10, result.getRequestedSteps());
        assertTrue(result.getLogs().stream().anyMatch(l -> l.contains("No valid states")));
    }

    @Test
    void doSimulate_success_stepsEqualsStatesMinusOne() throws Exception {
        File fakeFile = createTempModelFile(modelRunTempDir);
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        stubResolvedGeneration(smvGenerator).thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("trace", "raw"));

        // 4 states = initial + 3 steps
        List<TraceStateDto> states = List.of(
                new TraceStateDto(), new TraceStateDto(),
                new TraceStateDto(), new TraceStateDto());
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(states);

        SimulationResultDto result = (SimulationResultDto) doSimulate.invoke(
                service, 1L, singleDevice(), List.of(), 10, false,
                simRequest(singleDevice(), List.of(), 10, false, 0, false),
                Map.of(), runSnapshot(),
                SmvGenerator.TempModelContext.sync());

        assertEquals(4, result.getStates().size());
        assertEquals(3, result.getSteps());
        assertEquals(10, result.getRequestedSteps());
        assertEquals(Boolean.FALSE, result.getIsAttack());
        assertEquals(0, result.getAttackBudget());
        assertFalse(result.isEnablePrivacy());
        assertEquals(200, readResultCode(fakeFile));
        JsonNode request = readRequestJson(fakeFile);
        assertEquals(10, request.path("steps").asInt());
        assertFalse(request.path("attack").asBoolean());
        assertEquals(0, request.path("attackBudget").asInt());
    }

    @Test
    void submitSimulation_success_writesResultJson() throws Exception {
        File fakeFile = createTempModelFile(modelRunTempDir);
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        stubResolvedGeneration(smvGenerator).thenReturn(genResult);
        AtomicBoolean requestWrittenBeforeRun = new AtomicBoolean();
        AtomicBoolean resultWrittenBeforeRun = new AtomicBoolean();
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenAnswer(invocation -> {
                    requestWrittenBeforeRun.set(new File(fakeFile.getParentFile(), "request.json").isFile());
                    resultWrittenBeforeRun.set(new File(fakeFile.getParentFile(), "result.json").exists());
                    return SimulationOutput.success("trace", "raw");
                });
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(List.of(new TraceStateDto(), new TraceStateDto()));
        when(simulationTaskRepository.startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        assignSimulationTaskId(9L);
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(9L).userId(1L).status(SimulationTaskPo.TaskStatus.RUNNING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();
        when(simulationTaskRepository.findById(9L)).thenReturn(Optional.of(task));
        when(simulationTaskRepository.completeTaskIfRunning(
                eq(9L), eq(SimulationTaskPo.TaskStatus.COMPLETED), any(LocalDateTime.class),
                eq(1), anyLong(), isNull(), anyString(), anyString(), any(),
                eq(SimulationTaskPo.TaskStatus.RUNNING),
                anyString(), any(LocalDateTime.class))).thenReturn(1);

        assertEquals(9L, service.submitSimulation(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

        assertAll(
                () -> assertTrue(requestWrittenBeforeRun.get(), "request.json must be available when NuSMV starts"),
                () -> assertFalse(resultWrittenBeforeRun.get(), "result.json must wait until the run settles"));
        assertEquals(200, readResultCode(fakeFile));
        JsonNode request = readRequestJson(fakeFile);
        assertEquals(10, request.path("steps").asInt());
        assertTrue(fakeFile.isFile());
        assertFalse(lastTransactionStatus.isRollbackOnly());
        InOrder completionLocks = inOrder(userRepository, simulationTaskRepository, simulationTraceRepository);
        completionLocks.verify(simulationTaskRepository).updateProgressIfActive(
                eq(9L), eq(90), eq(TaskProgressStage.PERSISTING_RESULT), anyString(), any(LocalDateTime.class));
        completionLocks.verify(userRepository).findByIdForUpdate(1L);
        completionLocks.verify(simulationTaskRepository).findByIdForUpdate(9L);
        completionLocks.verify(simulationTraceRepository).save(any(SimulationTracePo.class));
        completionLocks.verify(simulationTaskRepository).findByIdForUpdate(9L);
        verify(formalOperationAdmission, never()).registerCurrentLeaseCommitFence();
    }

    @Test
    void submitSimulation_cancelledBetweenTraceSaveAndCompletion_rollsBackTraceTransaction() throws Exception {
        File fakeFile = createTempModelFile(modelRunTempDir);
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        stubResolvedGeneration(smvGenerator).thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("trace", "raw"));
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(List.of(new TraceStateDto(), new TraceStateDto()));
        when(simulationTaskRepository.startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(12L).userId(1L).status(SimulationTaskPo.TaskStatus.RUNNING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();
        when(simulationTaskRepository.findById(12L)).thenReturn(Optional.of(task));
        when(simulationTaskRepository.completeTaskIfRunning(
                eq(12L), eq(SimulationTaskPo.TaskStatus.COMPLETED), any(LocalDateTime.class),
                eq(1), anyLong(), isNull(), anyString(), anyString(), any(),
                eq(SimulationTaskPo.TaskStatus.RUNNING),
                anyString(), any(LocalDateTime.class))).thenReturn(0);

        assignSimulationTaskId(12L);
        service.submitSimulation(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

        assertEquals(200, readResultCode(fakeFile));
        assertTrue(transactionStatuses.stream().anyMatch(SimpleTransactionStatus::isRollbackOnly));
        verify(simulationTraceRepository).save(any(SimulationTracePo.class));
    }

    @Test
    void submitSimulation_cancelledBeforeRun_skipsGeneration() {
        simulationTaskExecutor.beforeExecution(() -> assertTrue(service.requestLocalExecutionStop(10L)));
        assignSimulationTaskId(10L);

        service.submitSimulation(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

        verify(simulationTaskRepository, never()).startTaskIfStillPending(anyLong(), any(), any(), anyInt(),
                anyString(), any(), anyString(), any(), any());
    }

    @Test
    void submitSimulation_noLongerPending_skipsGeneration() throws Exception {
        assignSimulationTaskId(11L);
        // startTaskIfStillPending returns 0 by default (Mockito int stub),
        // simulating a DB-level race where the task was cancelled or started by another process
        // after the in-memory check passed.
        service.submitSimulation(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

        // Verify the atomic start was attempted.
        verify(simulationTaskRepository).startTaskIfStillPending(
                eq(11L), eq(SimulationTaskPo.TaskStatus.RUNNING),
                any(LocalDateTime.class), eq(0), anyString(),
                eq(SimulationTaskPo.TaskStatus.PENDING), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class));
        // Verify early return: generation should never be reached.
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
    }

    @Test
    void submitSimulation_emptyDevices_rejectsBeforeCreatingTask() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(List.of(), List.of(), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("Add at least one device to the board before simulating"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
    }

    @Test
    void submitSimulation_storedUserLimit_rejectsBeforePersisting() {
        when(simulationTaskRepository.countByUserId(1L)).thenReturn(100L);

        AsyncTaskQuotaExceededException error = assertThrows(
                AsyncTaskQuotaExceededException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

        assertEquals("SIMULATION_STORED_TASK_LIMIT_REACHED", error.getReasonCode());
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
    }

    @Test
    void submitSimulation_invalidSteps_rejectsBeforeCreatingTask() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(), 101, false, 0, false)));

        assertTrue(ex.getMessage().contains("Steps must be between 1 and 100"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_invalidIntensity_rejectsBeforeCreatingTask() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(), 10, false, -1, false)));

        assertTrue(ex.getMessage().contains("Attack budget must be omitted or 0 when no attack scenario is selected"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_budgetSelection_rejectsBeforeCreatingTask() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(makeRule()), 10, true, 2, false)));

        assertTrue(ex.getMessage().contains(
                "Simulation requires explicit attack points; budget-based exhaustive selection is verification-only"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_attackEnabledWithZeroBudget_rejectsBeforeCreatingTask() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(), 10, true, 0, false)));

        assertTrue(ex.getMessage().contains(
                "Simulation requires explicit attack points; budget-based exhaustive selection is verification-only"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_attackDisabledWithPositiveBudget_rejectsInsteadOfDroppingIt() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(), 10, false, 3, false)));

        assertTrue(ex.getMessage().contains("Attack budget must be omitted or 0 when no attack scenario is selected"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(
                any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_invalidNestedDevice_rejectsBeforeCreatingTask() throws Exception {
        DeviceVerificationDto invalidDevice = new DeviceVerificationDto();
        invalidDevice.setVarName("testdevice");
        invalidDevice.setTemplateName(" ");

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(List.of(invalidDevice), List.of(), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("Template name is required"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_invalidNestedRule_rejectsBeforeCreatingTask() throws Exception {
        RuleDto invalidRule = RuleDto.builder()
                .conditions(Collections.singletonList(null))
                .command(RuleDto.Command.builder()
                        .deviceName("light")
                        .action("on")
                        .build())
                .build();

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(invalidRule), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("Condition item cannot be null"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_nonPositivePersistedRuleId_rejectsBeforeCreatingTask() throws Exception {
        RuleDto invalidRule = makeRule();
        invalidRule.setId(0L);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(invalidRule), 10, false, 0, false)));

        assertEquals("Rule id must be positive when provided", ex.getErrors().get("rules[0].id"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_ruleWithEmptyConditions_rejectsBeforeCreatingTask() throws Exception {
        RuleDto emptyConditionRule = RuleDto.builder()
                .conditions(List.of())
                .command(RuleDto.Command.builder()
                        .deviceName("testdevice")
                        .action("on")
                        .build())
                .build();

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(emptyConditionRule), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("Conditions cannot be empty"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_purgesFutureCancelledWhileDispatchWasEnqueuing() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        SimulationServiceImpl queuedService = serviceWithSimulationExecutor(executor);
        when(simulationTaskRepository.save(any(SimulationTaskPo.class))).thenReturn(
                SimulationTaskPo.builder().id(1000L).build());
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        when(executor.getThreadPoolExecutor()).thenReturn(pool);
        doAnswer(invocation -> {
            Runnable work = invocation.getArgument(0);
            assertTrue(queuedService.requestLocalExecutionStop(1000L));
            pool.getQueue().add(work);
            return null;
        }).when(executor).execute(any(Runnable.class));

        try {
            Long taskId = queuedService.submitSimulation(
                    1L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

            assertEquals(1000L, taskId);
            assertTrue(pool.getQueue().isEmpty(), "a cancelled Future must not keep occupying the bounded queue");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void submitSimulation_queueRejected_removesUndispatchedTaskAndThrowsServiceUnavailable() {
        SimulationServiceImpl rejectingService = serviceWithSimulationExecutor(new RejectingThreadPoolTaskExecutor());
        SimulationTaskPo savedTask = SimulationTaskPo.builder()
                .id(13L).userId(1L).status(SimulationTaskPo.TaskStatus.PENDING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();

        when(simulationTaskRepository.save(any(SimulationTaskPo.class))).thenReturn(savedTask);
        when(simulationTaskRepository.deleteUndispatchedTask(
                eq(13L), eq(1L), anyString(), eq(SimulationTaskPo.TaskStatus.PENDING)))
                .thenReturn(1);

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> rejectingService.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("busy"));
        verify(simulationTaskRepository).save(any(SimulationTaskPo.class));
        verify(chatExecutionLeaseGuard).requireCurrentExecutionLease();
        verify(simulationTaskRepository).deleteUndispatchedTask(
                eq(13L), eq(1L), anyString(), eq(SimulationTaskPo.TaskStatus.PENDING));
        verify(simulationTaskRepository, never()).failTaskIfActive(anyLong(), any(), any(), any(),
                any(), any(), anyList(), anyString(), any());
        verify(simulationTaskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void submitSimulation_runtimeDispatchFailure_removesUndispatchedTask() {
        SimulationServiceImpl failingService = serviceWithSimulationExecutor(new FailingThreadPoolTaskExecutor());
        SimulationTaskPo savedTask = SimulationTaskPo.builder()
                .id(15L).userId(1L).status(SimulationTaskPo.TaskStatus.PENDING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();
        when(simulationTaskRepository.save(any(SimulationTaskPo.class))).thenReturn(savedTask);
        when(simulationTaskRepository.deleteUndispatchedTask(
                eq(15L), eq(1L), anyString(), eq(SimulationTaskPo.TaskStatus.PENDING)))
                .thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> failingService.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

        assertEquals("executor failed", error.getMessage());
        verify(simulationTaskRepository).deleteUndispatchedTask(
                eq(15L), eq(1L), anyString(), eq(SimulationTaskPo.TaskStatus.PENDING));
    }

    @Test
    void submitSimulation_cleanupUnconfirmed_preservesTaskIdInOutcomeUnknownError() {
        SimulationServiceImpl rejectingService = serviceWithSimulationExecutor(new RejectingThreadPoolTaskExecutor());
        SimulationTaskPo savedTask = SimulationTaskPo.builder()
                .id(17L).userId(1L).status(SimulationTaskPo.TaskStatus.PENDING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();
        when(simulationTaskRepository.save(any(SimulationTaskPo.class))).thenReturn(savedTask);
        when(simulationTaskRepository.deleteUndispatchedTask(
                eq(17L), eq(1L), anyString(), eq(SimulationTaskPo.TaskStatus.PENDING)))
                .thenReturn(0);
        when(simulationTaskRepository.findByIdAndUserId(17L, 1L)).thenReturn(Optional.of(savedTask));

        AsyncTaskDispatchOutcomeUnknownException error = assertThrows(
                AsyncTaskDispatchOutcomeUnknownException.class,
                () -> rejectingService.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

        assertEquals(17L, error.getTaskId());
        assertTrue(error.getMessage().contains("cleanup"));
    }

    @Test
    void submitSimulation_usesSubmissionTimeRequestSnapshot() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        SimulationServiceImpl capturingService = serviceWithSimulationExecutor(capturingExecutor);
        File fakeFile = createTempModelFile(modelRunTempDir);
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        List<DeviceVerificationDto> devices = new ArrayList<>(singleDevice());
        RuleDto rule = makeRule();
        List<RuleDto> rules = new ArrayList<>(List.of(rule));
        SimulationRequestDto request = simRequest(devices, rules, 10, false, 0, false);
        var manifest = DeviceManifest.builder().name("light").modes(List.of("Power")).initState("on")
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").build()))
                .apis(List.of(DeviceManifest.API.builder().name("turnOn").endState("on").signal(false).build()))
                .build();
        var resolvedDevice = new DeviceSmvData();
        resolvedDevice.setVarName("testdevice");
        resolvedDevice.setManifest(manifest);
        resolvedDevice.getModes().add("Power");
        resolvedDevice.getModeStates().put("Power", List.of("on"));
        var capturedModel = new SmvGenerator.CapturedDeviceModel(
                Map.of("light", manifest), Map.of("testdevice", resolvedDevice));

        when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(capturedModel);
        doReturn(genResult).when(smvGenerator).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("trace", "raw"));
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(List.of(new TraceStateDto(), new TraceStateDto()));
        when(simulationTaskRepository.startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(15L).userId(1L).status(SimulationTaskPo.TaskStatus.RUNNING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();
        when(simulationTaskRepository.findById(15L)).thenReturn(Optional.of(task));

        assignSimulationTaskId(15L);
        capturingService.submitSimulation(1L, request);
        assertNotNull(capturingExecutor.capturedTask());

        devices.get(0).setVarName("mutateddevice");
        rule.getConditions().get(0).setDeviceName("mutatedRuleDevice");
        devices.clear();
        rules.clear();
        request.setSteps(20);
        request.setDevices(List.of());

        capturingExecutor.capturedTask().run();

        verify(smvGenerator).generateWithResolvedDeviceModel(eq(1L),
                argThat(sentDevices -> sentDevices.size() == 1
                        && "testdevice".equals(sentDevices.get(0).getVarName())),
                eq(List.of()),
                argThat(sentRules -> sentRules.size() == 1
                        && "testdevice".equals(sentRules.get(0).getConditions().get(0).getDeviceName())),
                eq(List.of()), eq(AttackScenarioDto.none()), eq(false), eq(SmvGenerator.GeneratePurpose.SIMULATION),
                argThat(ctx -> "task".equals(ctx.scope()) && Objects.equals(15L, ctx.id())),
                same(capturedModel.deviceSmvMap()));
        JsonNode requestJson = readRequestJson(fakeFile);
        assertEquals(10, requestJson.path("steps").asInt());
        assertEquals(0, requestJson.path("attackBudget").asInt());
        assertEquals("testdevice", requestJson.path("devices").get(0).path("varName").asText());
        assertEquals("testdevice", requestJson.path("rules").get(0).path("conditions").get(0).path("deviceName").asText());
        assertEquals(1, requestJson.path("devices").size());
    }

    @Test
    void simulationStartRejectsLeaseThatExpiredWhileWaitingForItsRowLock() throws Exception {
        LocalDateTime beforeLock = LocalDateTime.of(2026, 9, 30, 10, 0);
        LocalDateTime afterLock = beforeLock.plusSeconds(2);
        LocalDateTime expiry = beforeLock.plusSeconds(1);
        AtomicReference<LocalDateTime> databaseTime = new AtomicReference<>(beforeLock);
        AtomicInteger locks = new AtomicInteger();
        when(simulationTaskRepository.currentDatabaseTime()).thenAnswer(invocation -> databaseTime.get());
        when(simulationTaskRepository.findByIdForUpdate(201L)).thenAnswer(invocation -> {
            if (locks.incrementAndGet() == 2) databaseTime.set(afterLock);
            return Optional.of(SimulationTaskPo.builder().id(201L).build());
        });
        when(simulationTaskRepository.startTaskIfStillPending(
                eq(201L), any(), any(), anyInt(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<LocalDateTime>getArgument(7).isBefore(expiry) ? 1 : 0);

        assignSimulationTaskId(201L);
        service.submitSimulation(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

        verify(simulationTaskRepository).startTaskIfStillPending(
                eq(201L), any(), eq(afterLock), anyInt(), anyString(), any(), anyString(),
                eq(afterLock), eq(afterLock.plusMinutes(2)));
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
    }

    @Test
    void simulationProgressRejectsLeaseThatExpiredWhileWaitingForItsRowLock() {
        LocalDateTime beforeLock = LocalDateTime.of(2026, 9, 30, 10, 0);
        LocalDateTime afterLock = beforeLock.plusSeconds(2);
        AtomicReference<LocalDateTime> databaseTime = new AtomicReference<>(beforeLock);
        when(simulationTaskRepository.currentDatabaseTime()).thenAnswer(invocation -> databaseTime.get());
        when(simulationTaskRepository.findByIdForUpdate(202L)).thenAnswer(invocation -> {
            databaseTime.set(afterLock);
            return Optional.of(SimulationTaskPo.builder().id(202L).build());
        });
        when(simulationTaskRepository.updateProgressIfActive(eq(202L), eq(50), any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.<LocalDateTime>getArgument(4)
                        .isBefore(beforeLock.plusSeconds(1)) ? 1 : 0);

        assertEquals(0, service.atomicUpdateProgress(202L, 50, TaskProgressStage.EXECUTING_MODEL_CHECKER));
        InOrder clocks = inOrder(simulationTaskRepository);
        clocks.verify(simulationTaskRepository).findByIdForUpdate(202L);
        clocks.verify(simulationTaskRepository).currentDatabaseTime();
        clocks.verify(simulationTaskRepository).updateProgressIfActive(
                eq(202L), eq(50), eq(TaskProgressStage.EXECUTING_MODEL_CHECKER), anyString(), eq(afterLock));
    }

    @Test
    void submitSimulation_doesNotGenerateAfterLosingItsLeaseAtRunningProgress() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        SimulationServiceImpl capturingService = serviceWithSimulationExecutor(capturingExecutor);
        assignSimulationTaskId(203L);
        when(simulationTaskRepository.startTaskIfStillPending(
                eq(203L), any(), any(), anyInt(), anyString(), any(), anyString(), any(), any())).thenReturn(1);
        when(simulationTaskRepository.findById(203L)).thenReturn(Optional.of(SimulationTaskPo.builder()
                .id(203L).userId(1L).status(SimulationTaskPo.TaskStatus.RUNNING).build()));
        when(simulationTaskRepository.updateProgressIfActive(
                eq(203L), eq(20), eq(TaskProgressStage.RUNNING_SIMULATION), anyString(), any())).thenReturn(0);

        assertEquals(203L, capturingService.submitSimulation(
                1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));
        Runnable execution = capturingExecutor.capturedTask();
        Thread worker = new Thread(execution, "simulation-lease-loss-test");
        worker.start();
        worker.join(2000);

        assertFalse(worker.isAlive());
        assertTrue(assertInstanceOf(Future.class, execution).isCancelled());
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
        verify(simulationTaskRepository, never()).failTaskIfActive(anyLong(), any(), any(), any(), any(), any(),
                any(), anyString(), any());
    }

    @Test
    void queuedSimulationCannotStartAfterItsLeaseIsLost() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        SimulationServiceImpl capturingService = serviceWithSimulationExecutor(capturingExecutor);

        assignSimulationTaskId(151L);
        capturingService.submitSimulation(
                1L, simRequest(singleDevice(), List.of(makeRule()), 10, false, 0, false));
        assertNotNull(capturingExecutor.capturedTask());

        capturingService.maintainTaskLeases();
        capturingExecutor.capturedTask().run();

        verify(simulationTaskRepository).findByIdForUpdate(151L);
        verify(simulationTaskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
    }

    // ==================== simulate (public) tests ====================

    @Test
    void simulate_nullDevices_throwsValidationException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.simulate(1L, simRequest(null, List.of(), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("Add at least one device to the board before simulating"));
    }

    @Test
    void simulate_emptyDevices_throwsValidationException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.simulate(1L, simRequest(List.of(), List.of(), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("Add at least one device to the board before simulating"));
    }

    @Test
    void simulate_executorRejected_throwsServiceUnavailable() {
        syncSimulationExecutor.shutdown();

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> service.simulate(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));
        assertTrue(ex.getMessage().contains("busy"));
    }

    @Test
    void simulate_usesOneBoardCaptureAndNeverReloadsMutableTemplates() {
        var request = simRequest(singleDevice(), List.of(), 10, false, 0, false);
        var initial = boardDataConverter.getModelInputSnapshot(1L);
        Map<String, DeviceManifest> templates = Map.of(
                "light", DeviceManifest.builder().name("light").build());
        var board = new BoardDataConverter.ModelInputSnapshot(initial.nodes(), initial.devices(),
                initial.environmentVariables(), initial.rules(), initial.specifications(), templates);
        when(boardDataConverter.getModelInputSnapshot(1L)).thenReturn(board);
        org.mockito.Mockito.clearInvocations(boardDataConverter);
        when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), same(board.templateManifests())))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(board.templateManifests(), Map.of()));
        syncSimulationExecutor.shutdown();

        assertThrows(ServiceUnavailableException.class, () -> service.simulate(1L, request));

        verify(boardDataConverter).getModelInputSnapshot(1L);
        verify(smvGenerator).captureDeviceModelFromTemplateSnapshots(anyList(), same(board.templateManifests()));
    }

    @Test
    void acceptedSimulationFreezesItsBoardIdentityWithoutSpecifications() {
        var request = simRequest(singleDevice(), List.of(), 10, false, 0, false);
        var initial = boardDataConverter.getModelInputSnapshot(1L);
        var templates = Map.of("light", DeviceManifest.builder().name("light").build());
        var spec = new cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto();
        spec.setId("irrelevant-to-simulation");
        var board = new BoardDataConverter.ModelInputSnapshot(initial.nodes(), initial.devices(),
                initial.environmentVariables(), initial.rules(), List.of(spec), templates);
        when(boardDataConverter.getModelInputSnapshot(1L)).thenReturn(board);
        when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), eq(templates)))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(templates, Map.of()));
        assignSimulationTaskId(1000L);

        serviceWithSimulationExecutor(new CapturingThreadPoolTaskExecutor()).submitSimulation(1L, request);

        var saved = ArgumentCaptor.forClass(SimulationTaskPo.class);
        verify(simulationTaskRepository).save(saved.capture());
        var captured = cn.edu.nju.Iot_Verify.util.JsonUtils.fromJson(saved.getValue().getModelSnapshotJson(), ModelRunSnapshotDto.class);
        var canonical = new cn.edu.nju.Iot_Verify.util.ModelFingerprint(new ObjectMapper().findAndRegisterModules());
        assertEquals(canonical.simulationFingerprint(board), captured.getModelFingerprint());
        spec.setId("changed-specification");
        assertEquals(canonical.simulationFingerprint(board), captured.getModelFingerprint());
        board.devices().get(0).setState("changed-after-submission");
        assertNotEquals(canonical.simulationFingerprint(board), captured.getModelFingerprint());
    }

    @Test
    void simulate_timeout_throwsStructuredTimeoutAndPurgesQueuedTask() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(50L);
        try (var blocker = blockWorker(syncSimulationExecutor)) {
            SimulationExecutionException ex = assertThrows(SimulationExecutionException.class,
                    () -> service.simulate(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

            assertEquals(504, ex.getCode());
            assertEquals("TIMED_OUT", ex.getReasonCode());
            ThreadPoolExecutor nativeExecutor = syncSimulationExecutor.getThreadPoolExecutor();
            assertNotNull(nativeExecutor);
            assertEquals(0, nativeExecutor.getQueue().size());
        }
    }

    @Test
    void simulate_interruptedWaitCancelsNestedFutureAndPurgesQueue() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(5000L);
        try (var blocker = blockWorker(syncSimulationExecutor)) {
            Throwable failure = interruptQueuedRun(syncSimulationExecutor, "interrupted-simulation-caller",
                    () -> service.simulate(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

            SimulationExecutionException exception = assertInstanceOf(SimulationExecutionException.class, failure);
            assertEquals("INTERRUPTED", exception.getReasonCode());
            assertEquals(0, syncSimulationExecutor.getThreadPoolExecutor().getQueue().size());
        }
    }

    @Test
    void simulate_executorFailure_throwsStructuredFailureInsteadOfEmptySuccess() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        File fakeFile = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenReturn(new SmvGenerator.GenerateResult(fakeFile, Map.of()));
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.error("NuSMV exited with code 1."));

        SimulationExecutionException ex = assertThrows(SimulationExecutionException.class,
                () -> service.simulate(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

        assertEquals("EXECUTION_FAILED", ex.getReasonCode());
        assertTrue(ex.getMessage().contains("Simulation failed"));
        assertFalse(ex.getLogs().isEmpty());
    }

    @Test
    void simulate_generationDisabledRule_returnsTraceMarkedIncomplete() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        File fakeFile = createTempModelFile(modelRunTempDir);
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(
                fakeFile, Map.of(), List.of("Generation warning [rule-disabled]: invalid rule"),
                1, 0, List.of());
        stubResolvedGeneration(smvGenerator).thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("trace", "raw"));
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(List.of(new TraceStateDto(), new TraceStateDto()));

        SimulationResultDto result = service.simulate(
                1L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

        assertFalse(result.isModelComplete());
        assertEquals(1, result.getDisabledRuleCount());
        assertTrue(result.getLogs().stream().anyMatch(log -> log.contains("[rule-disabled]")));
        verify(formalOperationAdmission).execute(eq(1L), any());
    }

    @Test
    void simulate_smvGenerationError_rethrowsSmvGenerationException() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        stubResolvedGeneration(smvGenerator)
                .thenThrow(SmvGenerationException.ambiguousDeviceReference("Light", List.of("light_1", "light_2")));

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> service.simulate(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));
        assertEquals("AMBIGUOUS_DEVICE_REFERENCE", ex.getErrorCategory());
    }

    // ==================== simulateAndSave tests ====================

    @Test
    void simulateAndSave_success_savesPoAndReturnsDto() throws Exception {
        // Arrange: make simulate() produce a valid result via doSimulate
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        File fakeFile = createTempModelFile(modelRunTempDir);
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("trace", "raw"));
        List<TraceStateDto> states = List.of(new TraceStateDto(), new TraceStateDto());
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(states);

        SimulationTraceDto expectedDto = SimulationTraceDto.builder()
                .id(1L).steps(1).requestedSteps(5).build();
        when(simulationTraceMapper.toDto(any())).thenReturn(expectedDto);

        List<DeviceVerificationDto> devices = new ArrayList<>(singleDevice());
        RuleDto rule = makeRule();
        List<RuleDto> rules = new ArrayList<>(List.of(rule));
        SimulationRequestDto request = simRequest(devices, rules, 5, false, 0, false);
        stubResolvedGeneration(smvGenerator).thenAnswer(invocation -> {
                    request.setSteps(99);
                    devices.get(0).setVarName("mutateddevice");
                    rule.getConditions().get(0).setDeviceName("mutatedRuleDevice");
                    return genResult;
                });

        // Act
        SimulationTraceDto result = service.simulateAndSave(1L, request);

        // Assert
        assertEquals(expectedDto, result);
        SimulationTracePo savedPo = lastSavedSimulationTrace();
        verify(simulationTraceRepository).save(Objects.requireNonNull(savedPo));
        assertEquals(1L, savedPo.getUserId());
        assertEquals(5, savedPo.getRequestedSteps());
        assertEquals(1, savedPo.getSteps());
        JsonNode requestJson = new ObjectMapper().readTree(savedPo.getRequestJson());
        assertEquals(5, requestJson.path("steps").asInt());
        assertEquals("testdevice", requestJson.path("devices").get(0).path("varName").asText());
        assertEquals("testdevice", requestJson.path("rules").get(0).path("conditions").get(0).path("deviceName").asText());
        verify(formalOperationAdmission).registerCurrentLeaseCommitFence();
    }

    @Test
    void simulateAndSave_historySaveFailure_keepsTrajectoryAndReportsUnknownOutcome() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        File fakeFile = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenReturn(new SmvGenerator.GenerateResult(fakeFile, Map.of()));
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("trace", "raw"));
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(List.of(new TraceStateDto(), new TraceStateDto()));
        when(simulationTraceRepository.save(any(SimulationTracePo.class)))
                .thenThrow(new RuntimeException("database unavailable"));

        SimulationTraceDto result = service.simulateAndSave(
                1L, simRequest(singleDevice(), List.of(), 5, false, 0, false));

        assertEquals(2, result.getStates().size());
        assertNull(result.getId());
        assertEquals(cn.edu.nju.Iot_Verify.dto.model.RunPersistenceDto.Status.OUTCOME_UNKNOWN,
                result.getHistoryPersistence().getStatus());
        assertEquals("RUN_HISTORY_SAVE_OUTCOME_UNKNOWN",
                result.getHistoryPersistence().getReasonCode());
    }

    @Test
    void simulateAndSave_ownershipFenceFailureIsNotReportedAsAUsableResult() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        File fakeFile = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenReturn(new SmvGenerator.GenerateResult(fakeFile, Map.of()));
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("trace", "raw"));
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(List.of(new TraceStateDto(), new TraceStateDto()));
        ServiceUnavailableException ownershipLost =
                new ServiceUnavailableException("formal operation ownership changed");
        doThrow(ownershipLost).when(formalOperationAdmission).registerCurrentLeaseCommitFence();

        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> service.simulateAndSave(
                        1L, simRequest(singleDevice(), List.of(), 5, false, 0, false)));

        assertSame(ownershipLost, error);
        verify(simulationTraceRepository, never()).save(any(SimulationTracePo.class));
    }

    @Test
    void simulateAndSave_invalidRequest_throwsValidationException() {
        SimulationRequestDto request = new SimulationRequestDto();
        request.setAttackScenario(AttackScenarioDto.none());
        // An empty board is the only way to be device-less now: the request cannot supply a scene.
        lenient().when(boardDataConverter.getModelInputSnapshot(anyLong())).thenReturn(
                new BoardDataConverter.ModelInputSnapshot(
                        List.of(), List.of(), List.of(), List.of(), List.of(), Map.of()));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.simulateAndSave(1L, request));
        assertTrue(ex.getMessage().contains("Add at least one device to the board before simulating"));
    }

    // ==================== CRUD tests ====================

    @Test
    void getUserSimulations_delegatesToMapperAndRepo() {
        SimulationTraceSummaryProjection projection = mock(SimulationTraceSummaryProjection.class);
        when(simulationTraceRepository.findSummariesByUserId(
                eq(1L), any(Pageable.class)))
                .thenReturn(List.of(projection));
        SimulationTraceSummaryDto summary = SimulationTraceSummaryDto.builder().id(1L).steps(3).build();
        when(simulationTraceMapper.toSummaryProjectionDtoList(List.of(projection)))
                .thenReturn(List.of(summary));

        List<SimulationTraceSummaryDto> result = service.getUserSimulations(1L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void getUserSimulations_usesConfiguredStoredTaskLimitAsQueryBound() {
        AsyncTaskAdmissionConfig admissionConfig = new AsyncTaskAdmissionConfig();
        admissionConfig.getSimulation().setMaxStoredTasksPerUser(137);
        SimulationServiceImpl configuredService = serviceWithAdmissionConfig(admissionConfig);
        when(simulationTraceRepository.findSummariesByUserId(
                eq(1L), any(Pageable.class))).thenReturn(List.of());
        when(simulationTraceMapper.toSummaryProjectionDtoList(List.of())).thenReturn(List.of());

        assertTrue(configuredService.getUserSimulations(1L).isEmpty());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(simulationTraceRepository).findSummariesByUserId(
                eq(1L), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(137, pageable.getValue().getPageSize());
    }

    @Test
    void savedSimulationRejectsCombinedTaskAndStandaloneTraceQuotaBeforeStartingNusmv() throws Exception {
        when(simulationTaskRepository.countByUserId(1L)).thenReturn(60L);
        when(simulationTraceRepository.countStandaloneByUserId(1L)).thenReturn(40L);

        AsyncTaskQuotaExceededException error = assertThrows(
                AsyncTaskQuotaExceededException.class,
                () -> service.simulateAndSave(
                        1L, simRequest(singleDevice(), List.of(), 5, false, 0, false)));

        assertEquals("SIMULATION_STORED_TASK_LIMIT_REACHED", error.getReasonCode());
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
        verify(smvGenerator, never()).generateWithEnvironment(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any());
    }

    @Test
    void taskStatusQueryExcludesCompletedRuns() {
        SimulationTaskSummaryProjection row = mock(SimulationTaskSummaryProjection.class);
        List<SimulationTaskSummaryProjection> rows = List.of(row);
        when(simulationTaskRepository.findSummaryByUserIdAndStatusNotOrderByCreatedAtDesc(
                1L, SimulationTaskPo.TaskStatus.COMPLETED)).thenReturn(rows);
        when(simulationTaskMapper.toSummaryProjectionDtoList(rows)).thenReturn(List.of());

        service.getTasks(1L, List.of());

        verify(simulationTaskRepository).findSummaryByUserIdAndStatusNotOrderByCreatedAtDesc(
                1L, SimulationTaskPo.TaskStatus.COMPLETED);
    }

    @Test
    void deleteTaskOnlyDismissesNoResultTerminalRows() {
        SimulationTaskPo failed = SimulationTaskPo.builder()
                .id(3L).userId(1L).status(SimulationTaskPo.TaskStatus.FAILED)
                .createdAt(LocalDateTime.now()).build();
        when(simulationTaskRepository.findByIdAndUserId(3L, 1L)).thenReturn(Optional.of(failed));

        service.deleteTask(1L, 3L);

        verify(simulationTaskRepository).delete(failed);

        SimulationTaskPo completed = SimulationTaskPo.builder()
                .id(4L).userId(1L).status(SimulationTaskPo.TaskStatus.COMPLETED)
                .createdAt(LocalDateTime.now()).build();
        when(simulationTaskRepository.findByIdAndUserId(4L, 1L)).thenReturn(Optional.of(completed));
        assertThrows(BadRequestException.class, () -> service.deleteTask(1L, 4L));
    }

    @Test
    void getSimulation_found_returnsDto() {
        SimulationTracePo po = SimulationTracePo.builder().id(5L).userId(1L).build();
        SimulationTraceDto dto = SimulationTraceDto.builder().id(5L).build();
        when(simulationTraceRepository.findByIdAndUserId(5L, 1L))
                .thenReturn(Optional.of(po));
        when(simulationTraceMapper.toDto(po)).thenReturn(dto);

        SimulationTraceDto result = service.getSimulation(1L, 5L);

        assertEquals(5L, result.getId());
    }

    @Test
    void getSimulation_notFound_throwsResourceNotFound() {
        when(simulationTraceRepository.findByIdAndUserId(99L, 1L))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getSimulation(1L, 99L));
    }

    @Test
    void deleteSimulation_found_deletes() {
        SimulationTracePo po = SimulationTracePo.builder().id(5L).userId(1L).build();
        when(userRepository.findByIdForShare(1L)).thenReturn(Optional.of(new UserPo()));
        when(simulationTraceRepository.findByIdAndUserId(5L, 1L))
                .thenReturn(Optional.of(po));

        service.deleteSimulation(1L, 5L);

        InOrder deletion = inOrder(userRepository, chatExecutionLeaseGuard,
                simulationTraceRepository, simulationTaskRepository);
        deletion.verify(userRepository).findByIdForShare(1L);
        deletion.verify(chatExecutionLeaseGuard).requireCurrentExecutionLease();
        deletion.verify(simulationTraceRepository).findByIdAndUserId(5L, 1L);
        deletion.verify(simulationTaskRepository).deleteByUserIdAndSimulationTraceId(1L, 5L);
        deletion.verify(simulationTraceRepository).delete(po);
    }

    @Test
    void deleteSimulation_notFound_throwsResourceNotFound() {
        when(userRepository.findByIdForShare(1L)).thenReturn(Optional.of(new UserPo()));
        when(simulationTraceRepository.findByIdAndUserId(99L, 1L))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.deleteSimulation(1L, 99L));
    }

    @Test
    void deleteSimulation_ownerGone_doesNotAccessRunRows() {
        when(userRepository.findByIdForShare(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deleteSimulation(1L, 99L));

        verifyNoInteractions(simulationTraceRepository, simulationTaskRepository);
    }

    // ==================== terminal-state progress tests ====================

    @Test
    void maintainTaskLeases_locksExpiryCandidateBeforeConditionalFailure() {
        var activeStatuses = List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING);
        when(simulationTaskRepository.findExpiredActiveTaskIds(eq(activeStatuses), any(LocalDateTime.class)))
                .thenReturn(List.of(60L));
        when(simulationTaskRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(new SimulationTaskPo()));

        try {
            service.initializeTaskLeaseMaintenance();
        } finally {
            service.stopTaskLeaseMaintenance();
        }

        InOrder recovery = inOrder(simulationTaskRepository);
        recovery.verify(simulationTaskRepository).findExpiredActiveTaskIds(eq(activeStatuses), any(LocalDateTime.class));
        recovery.verify(simulationTaskRepository).findByIdForUpdate(60L);
        recovery.verify(simulationTaskRepository).failExpiredTask(eq(60L), eq(SimulationTaskPo.TaskStatus.FAILED),
                any(LocalDateTime.class), anyString(), anyString(), eq(activeStatuses), any(LocalDateTime.class));
    }

    @Test
    void cancelTask_pendingTask_usesAtomicCancelUpdate() {
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(60L).userId(1L).status(SimulationTaskPo.TaskStatus.PENDING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();

        when(simulationTaskRepository.findByIdAndUserIdForUpdate(60L, 1L))
                .thenReturn(Optional.of(task));
        when(simulationTaskRepository.cancelTaskIfStillActive(
                eq(60L), eq(SimulationTaskPo.TaskStatus.CANCELLED), any(LocalDateTime.class), anyList()))
                .thenReturn(1);

        var result = service.cancelTask(1L, 60L);

        assertTrue(result.isCancellationAccepted());
        assertEquals("CANCELLED", result.getTaskStatus());
        verify(chatExecutionLeaseGuard).requireCurrentExecutionLeaseAndLock();
        verify(simulationTaskRepository).cancelTaskIfStillActive(
                eq(60L), eq(SimulationTaskPo.TaskStatus.CANCELLED), any(LocalDateTime.class), anyList());
        assertFalse(wasSimulationTaskSaveCalled());
    }

    @Test
    void completeTask_skipsWhenAlreadyCancelledInDb() throws Exception {
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(70L).userId(1L).status(SimulationTaskPo.TaskStatus.RUNNING)
                .requestedSteps(10).startedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).build();

        // Atomic UPDATE returns 0 — task was already cancelled in DB
        when(simulationTaskRepository.completeTaskIfRunning(
                eq(70L), any(), any(), anyInt(), any(),
                any(), any(), any(), any(), any(),
                anyString(), any(LocalDateTime.class)))
                .thenReturn(0);

        Method completeTask = SimulationServiceImpl.class.getDeclaredMethod(
                "completeTask", SimulationTaskPo.class, Long.class, int.class, List.class, List.class);
        completeTask.setAccessible(true);
        completeTask.invoke(service, task, 999L, 10, List.of("done"), List.of());

        // Atomic UPDATE was called (returns 0 = no rows affected = already cancelled)
        verify(simulationTaskRepository).completeTaskIfRunning(
                eq(70L), any(), any(), anyInt(), any(),
                any(), any(), any(), any(), any(),
                anyString(), any(LocalDateTime.class));
        // save() should NOT be called — atomic UPDATE replaces it
        assertFalse(wasSimulationTaskSaveCalled());
    }

    @Test
    void failTask_skipsWhenAlreadyCancelledInDb() throws Exception {
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(71L).userId(1L).status(SimulationTaskPo.TaskStatus.RUNNING)
                .requestedSteps(10).startedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).build();

        // Atomic UPDATE returns 0 — task was already cancelled in DB
        when(simulationTaskRepository.failTaskIfActive(
                eq(71L), any(), any(), any(), any(), any(), any(), anyString(), any(LocalDateTime.class)))
                .thenReturn(0);

        Method failTask = SimulationServiceImpl.class.getDeclaredMethod(
                "failTask", SimulationTaskPo.class, String.class, List.class);
        failTask.setAccessible(true);
        failTask.invoke(service, task, "some error", List.of("some error"));

        // Atomic UPDATE was called (returns 0 = no rows affected = already cancelled)
        verify(simulationTaskRepository).failTaskIfActive(
                eq(71L), any(), any(), any(), any(), any(), any(), anyString(), any(LocalDateTime.class));
        // save() should NOT be called — atomic UPDATE replaces it
        assertFalse(wasSimulationTaskSaveCalled());
    }

    private boolean wasSimulationTaskSaveCalled() {
        return mockingDetails(simulationTaskRepository).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals("save"));
    }

    private SimulationTracePo lastSavedSimulationTrace() {
        SimulationTracePo lastSaved = null;
        for (org.mockito.invocation.Invocation invocation : mockingDetails(simulationTraceRepository).getInvocations()) {
            if ("save".equals(invocation.getMethod().getName())) {
                lastSaved = invocation.getArgument(0, SimulationTracePo.class);
            }
        }
        return Objects.requireNonNull(lastSaved, "simulation trace should be saved");
    }

    private SimulationRequestDto simRequest(List<DeviceVerificationDto> devices, List<RuleDto> rules,
                                            int steps, boolean isAttack, int attackBudget, boolean enablePrivacy) {
        SimulationRequestDto r = new SimulationRequestDto();
        stubBoardSnapshot(boardDataConverter, devices, rules, List.of());
        r.setDevices(devices);
        r.setPlaybackNodes(playbackNodes(devices));
        r.setRules(rules);
        r.setSteps(steps);
        r.setAttackScenario(AttackScenarioDto.builder()
                .mode(isAttack ? AttackScenarioDto.Mode.ANY_UP_TO_BUDGET : AttackScenarioDto.Mode.NONE)
                .budget(attackBudget)
                .points(List.of())
                .build());
        r.setEnablePrivacy(enablePrivacy);
        return r;
    }

    private RuleDto makeRule() {
        return RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("testdevice")
                        .attribute("state")
                        .targetType("state")
                        .relation("=")
                        .value("on")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("testdevice")
                        .action("turnOn")
                        .build())
                .build();
    }
}
