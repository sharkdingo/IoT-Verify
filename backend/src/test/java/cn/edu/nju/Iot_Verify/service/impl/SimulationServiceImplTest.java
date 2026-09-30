package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SimulationOutput;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.EnvironmentProvenanceCollector;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.configure.AsyncTaskAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SimulationServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
class SimulationServiceImplTest {

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

    private static class DirectThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }

    private static class RejectingThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
        @Override
        public void execute(Runnable task) {
            throw new TaskRejectedException("rejected");
        }
    }

    private static class FailingThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
        @Override
        public void execute(Runnable task) {
            throw new IllegalStateException("executor failed");
        }
    }

    private static class CapturingThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
        private Runnable capturedTask;

        @Override
        public void execute(Runnable task) {
            capturedTask = task;
        }

        Runnable capturedTask() {
            return capturedTask;
        }
    }

    private SimulationServiceImpl service;
    private ThreadPoolTaskExecutor simulationTaskExecutor;
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
        lenient().when(simulationTaskRepository.updateProgressIfActive(anyLong(), anyInt(), any(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        lenient().when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(new UserPo()));
        lenient().when(userRepository.existsById(anyLong())).thenReturn(true);
        DeviceManifest templateSnapshot = DeviceManifest.builder().name("light").build();
        lenient().when(smvGenerator.captureDeviceModel(anyLong(), anyList()))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(
                        Map.of("light", templateSnapshot), Map.of()));
        lenient().when(smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(Map.of());
        lenient().when(smvGenerator.generateWithResolvedDeviceModel(
                        anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap()))
                .thenAnswer(invocation -> smvGenerator.generateWithEnvironment(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5),
                        invocation.getArgument(6), invocation.getArgument(7), invocation.getArgument(8)));

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

    private File createTempModelFile() throws Exception {
        Path dir = Files.createTempDirectory("sim-service-test-");
        File smvFile = dir.resolve("model.smv").toFile();
        assertTrue(smvFile.createNewFile());
        smvFile.deleteOnExit();
        dir.toFile().deleteOnExit();
        return smvFile;
    }

    private int readResultCode(File smvFile) throws Exception {
        File jsonFile = new File(smvFile.getParentFile(), "result.json");
        assertTrue(jsonFile.exists());
        JsonNode node = new ObjectMapper().readTree(jsonFile);
        return node.path("code").asInt();
    }

    private JsonNode readRequestJson(File smvFile) throws Exception {
        File jsonFile = new File(smvFile.getParentFile(), "request.json");
        assertTrue(jsonFile.exists());
        return new ObjectMapper().readTree(jsonFile);
    }

    @SuppressWarnings("unchecked")
    private Set<Long> cancelledTaskIds() throws Exception {
        Field f = AbstractAsyncTaskService.class.getDeclaredField("cancelledTasks");
        f.setAccessible(true);
        return (Set<Long>) f.get(service);
    }

    private SimulationRequestDto simulationRequest(List<DeviceVerificationDto> devices,
                                                   List<RuleDto> rules,
                                                   int steps,
                                                   boolean attack,
                                                   int attackBudget,
                                                   boolean enablePrivacy) {
        SimulationRequestDto request = new SimulationRequestDto();
        // The service reads the scene from the board, so the scene a test wants simulated has to be
        // the board it presents. An answer (not a value) mirrors the real per-call immutable read.
        lenient().when(boardDataConverter.getModelInputSnapshot(anyLong())).thenAnswer(invocation ->
                new BoardDataConverter.ModelInputSnapshot(
                        playbackNodes(devices) == null ? List.of() : List.copyOf(playbackNodes(devices)),
                        devices == null ? List.of() : List.copyOf(devices),
                        List.of(),
                        rules == null ? List.of() : List.copyOf(rules),
                        List.of(),
                        Map.of()));
        request.setDevices(devices);
        request.setPlaybackNodes(playbackNodes(devices));
        request.setRules(rules);
        request.setSteps(steps);
        request.setAttackScenario(AttackScenarioDto.builder()
                .mode(attack ? AttackScenarioDto.Mode.ANY_UP_TO_BUDGET : AttackScenarioDto.Mode.NONE)
                .budget(attackBudget)
                .points(List.of())
                .build());
        request.setEnablePrivacy(enablePrivacy);
        return request;
    }

    // ==================== doSimulate tests ====================

    @Test
    void doSimulate_executorFails_returnsErrorResult() throws Exception {
        File fakeFile = createTempModelFile();
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.error("NuSMV exited with code 1."));

        SimulationResultDto result = (SimulationResultDto) doSimulate.invoke(
                service, 1L, singleDevice(), List.of(), 10, false,
                simulationRequest(singleDevice(), List.of(), 10, false, 0, false),
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
        File fakeFile = createTempModelFile();
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.busy("NuSMV simulation is busy, please retry later"));

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () ->
                doSimulate.invoke(service, 1L, singleDevice(), List.of(), 10, false,
                        simulationRequest(singleDevice(), List.of(), 10, false, 0, false),
                        Map.of(), runSnapshot(),
                        SmvGenerator.TempModelContext.sync()));

        assertInstanceOf(ServiceUnavailableException.class, ex.getCause());
        assertEquals(503, readResultCode(fakeFile));
    }

    @Test
    void doSimulate_smvGenerationError_propagatesSmvGenerationException() throws Exception {
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(SmvGenerationException.ambiguousDeviceReference("Sensor", List.of("sensor_1", "sensor_2")));

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () ->
                doSimulate.invoke(service, 1L, singleDevice(), List.of(), 10, false,
                        simulationRequest(singleDevice(), List.of(), 10, false, 0, false),
                        Map.of(), runSnapshot(),
                        SmvGenerator.TempModelContext.sync()));

        assertInstanceOf(SmvGenerationException.class, ex.getCause());
        SmvGenerationException cause = (SmvGenerationException) ex.getCause();
        assertEquals("AMBIGUOUS_DEVICE_REFERENCE", cause.getErrorCategory());
    }

    @Test
    void doSimulate_emptyStates_returnsZeroSteps() throws Exception {
        File fakeFile = createTempModelFile();
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("-> State: 1.1 <-\n", "raw"));
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(List.of());

        SimulationResultDto result = (SimulationResultDto) doSimulate.invoke(
                service, 1L, singleDevice(), List.of(), 10, false,
                simulationRequest(singleDevice(), List.of(), 10, false, 0, false),
                Map.of(), runSnapshot(),
                SmvGenerator.TempModelContext.sync());

        assertTrue(result.getStates().isEmpty());
        assertEquals(0, result.getSteps());
        assertEquals(10, result.getRequestedSteps());
        assertTrue(result.getLogs().stream().anyMatch(l -> l.contains("No valid states")));
    }

    @Test
    void doSimulate_success_stepsEqualsStatesMinusOne() throws Exception {
        File fakeFile = createTempModelFile();
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(genResult);
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
                simulationRequest(singleDevice(), List.of(), 10, false, 0, false),
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
    void simulateAsync_success_writesResultJson() throws Exception {
        File fakeFile = createTempModelFile();
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(genResult);
        when(nusmvExecutor.executeInteractiveSimulation(any(), anyInt()))
                .thenReturn(SimulationOutput.success("trace", "raw"));
        when(smvTraceParser.parseCounterexampleStates(any(), any(), anyList()))
                .thenReturn(List.of(new TraceStateDto(), new TraceStateDto()));
        when(simulationTaskRepository.startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        // findById is still used by simulateAsync after startTaskIfStillPending to load the entity
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(9L).userId(1L).status(SimulationTaskPo.TaskStatus.RUNNING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();
        when(simulationTaskRepository.findById(9L)).thenReturn(Optional.of(task));
        when(simulationTaskRepository.completeTaskIfRunning(
                eq(9L), eq(SimulationTaskPo.TaskStatus.COMPLETED), any(LocalDateTime.class),
                eq(1), anyLong(), isNull(), anyString(), anyString(), any(),
                eq(SimulationTaskPo.TaskStatus.RUNNING),
                anyString(), any(LocalDateTime.class))).thenReturn(1);

        service.simulateAsync(1L, 9L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

        assertEquals(200, readResultCode(fakeFile));
        assertFalse(lastTransactionStatus.isRollbackOnly());
        InOrder completionLocks = inOrder(userRepository, simulationTaskRepository);
        completionLocks.verify(userRepository).findByIdForUpdate(1L);
        completionLocks.verify(simulationTaskRepository, times(2)).findByIdForUpdate(9L);
        verify(formalOperationAdmission, never()).registerCurrentLeaseCommitFence();
    }

    @Test
    void simulateAsync_cancelledBetweenTraceSaveAndCompletion_rollsBackTraceTransaction() throws Exception {
        File fakeFile = createTempModelFile();
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(genResult);
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

        service.simulateAsync(1L, 12L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

        assertEquals(200, readResultCode(fakeFile));
        assertTrue(transactionStatuses.stream().anyMatch(SimpleTransactionStatus::isRollbackOnly));
        verify(simulationTraceRepository).save(any(SimulationTracePo.class));
    }

    @Test
    void simulateAsync_cancelledBeforeRun_skipsGeneration() throws Exception {
        cancelledTaskIds().add(10L);

        service.simulateAsync(1L, 10L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void simulateAsync_noLongerPending_skipsGeneration() throws Exception {
        // startTaskIfStillPending returns 0 by default (Mockito int stub),
        // simulating a DB-level race where the task was cancelled or started by another process
        // after the in-memory check passed.
        service.simulateAsync(1L, 11L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

        // Verify the atomic start was attempted.
        verify(simulationTaskRepository).startTaskIfStillPending(
                eq(11L), eq(SimulationTaskPo.TaskStatus.RUNNING),
                any(LocalDateTime.class), eq(0), anyString(),
                eq(SimulationTaskPo.TaskStatus.PENDING), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class));
        // Verify early return: generation should never be reached.
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
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
    void createTask_storedUserLimit_rejectsBeforePersisting() {
        when(simulationTaskRepository.countByUserId(1L)).thenReturn(100L);

        AsyncTaskQuotaExceededException error = assertThrows(
                AsyncTaskQuotaExceededException.class,
                () -> service.createTask(
                        1L, 10, AttackScenarioDto.none(), false, 0, 0, 0, runSnapshot()));

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
    void submitSimulation_ruleWithNullCommand_rejectsBeforeCreatingTask() throws Exception {
        RuleDto invalidRule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("testdevice")
                        .attribute("state")
                        .targetType("state")
                        .relation("=")
                        .value("on")
                        .build()))
                .command(null)
                .build();

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(invalidRule), 10, false, 0, false)));

        assertEquals("Command cannot be null", ex.getErrors().get("rules[0].command"));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void submitSimulation_ruleWithBlankCommandFields_rejectsBeforeCreatingTask() throws Exception {
        RuleDto invalidRule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("testdevice")
                        .attribute("state")
                        .targetType("state")
                        .relation("=")
                        .value("on")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName(" ")
                        .action("")
                        .build())
                .build();

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitSimulation(
                        1L, simRequest(singleDevice(), List.of(invalidRule), 10, false, 0, false)));

        assertEquals("Command device name is required", ex.getErrors().get("rules[0].command.deviceName"));
        assertEquals("Command action is required", ex.getErrors().get("rules[0].command.action"));
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
    void simulateAsync_emptyDevices_rejectsBeforeQueueingTask() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.simulateAsync(
                        1L, 12L, simRequest(List.of(), List.of(), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("Add at least one device to the board before simulating"));
        verify(simulationTaskRepository).findById(12L);
        verify(simulationTaskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void simulateAsync_nullTaskId_rejectsBeforeQueueingTask() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.simulateAsync(
                        1L, null, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("taskId"));
        verify(simulationTaskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void simulateAsync_invalidIntensity_marksExistingTaskFailedBeforeQueueing() throws Exception {
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(16L).userId(1L).status(SimulationTaskPo.TaskStatus.PENDING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();
        when(simulationTaskRepository.findById(16L)).thenReturn(Optional.of(task));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.simulateAsync(
                        1L, 16L, simRequest(singleDevice(), List.of(), 10, false, 51, false)));

        assertTrue(ex.getMessage().contains("Attack budget must be omitted or 0 when no attack scenario is selected"));
        verify(simulationTaskRepository).failTaskIfActive(
                eq(16L), eq(SimulationTaskPo.TaskStatus.FAILED), any(LocalDateTime.class),
                eq("attackScenario.budget: Attack budget must be omitted or 0 when no attack scenario is selected"), anyString(), any(),
                eq(List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING)),
                anyString(), any(LocalDateTime.class));
        verify(simulationTaskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(smvGenerator, never()).generate(any(), any(), any(), any(), any(), anyBoolean(), any());
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
    void simulateAsync_queueRejected_marksExistingTaskFailedAndThrowsServiceUnavailable() {
        SimulationServiceImpl rejectingService = serviceWithSimulationExecutor(new RejectingThreadPoolTaskExecutor());
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(14L).userId(1L).status(SimulationTaskPo.TaskStatus.PENDING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();

        when(simulationTaskRepository.findById(14L)).thenReturn(Optional.of(task));

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> rejectingService.simulateAsync(
                        1L, 14L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

        assertTrue(ex.getMessage().contains("busy"));
        verify(simulationTaskRepository).failTaskIfActive(
                eq(14L), eq(SimulationTaskPo.TaskStatus.FAILED), any(LocalDateTime.class),
                eq("Server busy, please try again later"), anyString(), any(),
                eq(List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING)),
                anyString(), any(LocalDateTime.class));
        verify(simulationTaskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void simulateAsync_usesSubmissionTimeRequestSnapshot() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        SimulationServiceImpl capturingService = serviceWithSimulationExecutor(capturingExecutor);
        File fakeFile = createTempModelFile();
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(fakeFile, Map.of());
        List<DeviceVerificationDto> devices = new ArrayList<>(singleDevice());
        RuleDto rule = makeRule();
        List<RuleDto> rules = new ArrayList<>(List.of(rule));
        SimulationRequestDto request = simRequest(devices, rules, 10, false, 0, false);
        Map<String, DeviceSmvData> resolvedSubmissionModel = new java.util.LinkedHashMap<>();
        resolvedSubmissionModel.put("testdevice", new DeviceSmvData());

        when(smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(resolvedSubmissionModel);
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(genResult);
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

        capturingService.simulateAsync(1L, 15L, request);
        assertNotNull(capturingExecutor.capturedTask());

        devices.get(0).setVarName("mutateddevice");
        rule.getConditions().get(0).setDeviceName("mutatedRuleDevice");
        devices.clear();
        rules.clear();
        request.setSteps(20);
        request.setDevices(List.of());

        capturingExecutor.capturedTask().run();

        verify(smvGenerator).generateWithResolvedDeviceModel(eq(1L), anyList(), anyList(), anyList(), eq(List.of()),
                eq(AttackScenarioDto.none()), eq(false), eq(SmvGenerator.GeneratePurpose.SIMULATION),
                argThat(ctx -> "task".equals(ctx.scope()) && Objects.equals(15L, ctx.id())),
                same(resolvedSubmissionModel));

        verify(smvGenerator).generateWithEnvironment(eq(1L),
                argThat(sentDevices -> sentDevices.size() == 1
                        && "testdevice".equals(sentDevices.get(0).getVarName())),
                eq(List.of()),
                argThat(sentRules -> sentRules.size() == 1
                        && "testdevice".equals(sentRules.get(0).getConditions().get(0).getDeviceName())),
                eq(List.of()), eq(AttackScenarioDto.none()), eq(false), eq(SmvGenerator.GeneratePurpose.SIMULATION),
                argThat(ctx -> "task".equals(ctx.scope()) && Objects.equals(15L, ctx.id())));
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

        service.simulateAsync(1L, 201L, simRequest(singleDevice(), List.of(), 10, false, 0, false));

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
    void queuedSimulationCannotStartAfterItsLeaseIsLost() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        SimulationServiceImpl capturingService = serviceWithSimulationExecutor(capturingExecutor);

        capturingService.simulateAsync(
                1L, 151L, simRequest(singleDevice(), List.of(makeRule()), 10, false, 0, false));
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

    @Test
    void queuedSimulationStopsAfterDatabaseCannotConfirmItsLeaseForFullTtl() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        SimulationServiceImpl capturingService = serviceWithSimulationExecutor(capturingExecutor);
        capturingService.simulateAsync(
                1L, 152L, simRequest(singleDevice(), List.of(makeRule()), 10, false, 0, false));
        LeaseConfirmationTestSupport.ageExecutionLease(
                capturingService, "localExecutions", 152L, Duration.ofMinutes(3));
        when(simulationTaskRepository.currentDatabaseTime())
                .thenThrow(new RuntimeException("database unavailable"));

        capturingService.maintainTaskLeases();

        assertTrue(((Future<?>) capturingExecutor.capturedTask()).isCancelled());
        verify(simulationTaskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
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
    void simulateWithTemplateSnapshot_neverReloadsMutableTemplateRepository() {
        Map<String, DeviceManifest> supplied = Map.of(
                "light", DeviceManifest.builder().name("light").build());
        when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), same(supplied)))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(supplied, Map.of()));
        syncSimulationExecutor.shutdown();

        assertThrows(ServiceUnavailableException.class, () -> service.simulateWithTemplateSnapshot(
                1L, simRequest(singleDevice(), List.of(), 10, false, 0, false), supplied));

        verify(smvGenerator).captureDeviceModelFromTemplateSnapshots(anyList(), same(supplied));
        verify(smvGenerator, never()).captureDeviceModel(anyLong(), anyList());
    }

    @Test
    void simulate_timeout_throwsStructuredTimeoutAndPurgesQueuedTask() {
        when(nusmvConfig.getTimeoutMs()).thenReturn(50L);
        Future<?> blocker = syncSimulationExecutor.submit(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        SimulationExecutionException ex = assertThrows(SimulationExecutionException.class,
                () -> service.simulate(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false)));

        assertEquals(504, ex.getCode());
        assertEquals("TIMED_OUT", ex.getReasonCode());

        ThreadPoolExecutor nativeExecutor = syncSimulationExecutor.getThreadPoolExecutor();
        assertNotNull(nativeExecutor);
        assertEquals(0, nativeExecutor.getQueue().size());
        blocker.cancel(true);
    }

    @Test
    void simulate_interruptedWaitCancelsNestedFutureAndPurgesQueue() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(5000L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        Future<?> blocker = syncSimulationExecutor.submit(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                service.simulate(1L, simRequest(singleDevice(), List.of(), 10, false, 0, false));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "interrupted-simulation-caller");

        caller.start();
        awaitQueueSize(syncSimulationExecutor, 1);
        caller.interrupt();
        caller.join(2000);

        assertFalse(caller.isAlive());
        SimulationExecutionException exception = assertInstanceOf(
                SimulationExecutionException.class, failure.get());
        assertEquals("INTERRUPTED", exception.getReasonCode());
        assertEquals(0, syncSimulationExecutor.getThreadPoolExecutor().getQueue().size());
        releaseBlocker.countDown();
        blocker.cancel(true);
    }

    private void awaitQueueSize(ThreadPoolTaskExecutor executor, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (executor.getThreadPoolExecutor().getQueue().size() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, executor.getThreadPoolExecutor().getQueue().size());
    }

    @Test
    void simulate_executorFailure_throwsStructuredFailureInsteadOfEmptySuccess() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        File fakeFile = createTempModelFile();
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new SmvGenerator.GenerateResult(fakeFile, Map.of()));
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
        File fakeFile = createTempModelFile();
        SmvGenerator.GenerateResult genResult = new SmvGenerator.GenerateResult(
                fakeFile, Map.of(), List.of("Generation warning [rule-disabled]: invalid rule"),
                1, 0, List.of());
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(genResult);
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
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
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
        File fakeFile = createTempModelFile();
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
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenAnswer(invocation -> {
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
        File fakeFile = createTempModelFile();
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(),
                any(), anyBoolean(), any(), any()))
                .thenReturn(new SmvGenerator.GenerateResult(fakeFile, Map.of()));
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
        File fakeFile = createTempModelFile();
        when(smvGenerator.generateWithEnvironment(any(), any(), anyList(), any(), any(),
                any(), anyBoolean(), any(), any()))
                .thenReturn(new SmvGenerator.GenerateResult(fakeFile, Map.of()));
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
        when(simulationTraceRepository.findByIdAndUserId(5L, 1L))
                .thenReturn(Optional.of(po));

        service.deleteSimulation(1L, 5L);

        verify(chatExecutionLeaseGuard).requireCurrentExecutionLease();
        verify(simulationTaskRepository).deleteByUserIdAndSimulationTraceId(1L, 5L);
        verify(simulationTraceRepository).delete(Objects.requireNonNull(po));
    }

    @Test
    void deleteSimulation_notFound_throwsResourceNotFound() {
        when(simulationTraceRepository.findByIdAndUserId(99L, 1L))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.deleteSimulation(1L, 99L));
    }

    // ==================== terminal-state progress tests ====================

    @Test
    void maintainTaskLeases_recoversExpiredTasksWithoutScanningAndSavingAllActiveRows() {
        when(simulationTaskRepository.failExpiredActiveTasks(
                eq(SimulationTaskPo.TaskStatus.FAILED),
                any(LocalDateTime.class),
                anyString(),
                anyString(),
                eq(List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING)),
                any(LocalDateTime.class)))
                .thenReturn(2);

        service.maintainTaskLeases();

        verify(simulationTaskRepository).failExpiredActiveTasks(
                eq(SimulationTaskPo.TaskStatus.FAILED),
                any(LocalDateTime.class),
                anyString(),
                anyString(),
                eq(List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING)),
                any(LocalDateTime.class));
        verify(simulationTaskRepository, never()).save(any(SimulationTaskPo.class));
    }

    @Test
    void cancelTask_pendingTask_usesAtomicCancelUpdate() {
        SimulationTaskPo task = SimulationTaskPo.builder()
                .id(60L).userId(1L).status(SimulationTaskPo.TaskStatus.PENDING)
                .requestedSteps(10).createdAt(LocalDateTime.now()).build();

        when(simulationTaskRepository.findByIdAndUserId(60L, 1L))
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
        // The service reads the scene from the board, so the scene a test wants simulated has to be
        // the board it presents. An answer (not a value) mirrors the real per-call immutable read.
        lenient().when(boardDataConverter.getModelInputSnapshot(anyLong())).thenAnswer(invocation ->
                new BoardDataConverter.ModelInputSnapshot(
                        playbackNodes(devices) == null ? List.of() : List.copyOf(playbackNodes(devices)),
                        devices == null ? List.of() : List.copyOf(devices),
                        List.of(),
                        rules == null ? List.of() : List.copyOf(rules),
                        List.of(),
                        Map.of()));
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

    private List<DeviceNodeDto> playbackNodes(List<DeviceVerificationDto> devices) {
        if (devices == null) return null;
        List<DeviceNodeDto> nodes = new ArrayList<>();
        for (int index = 0; index < devices.size(); index++) {
            DeviceVerificationDto device = devices.get(index);
            DeviceNodeDto node = new DeviceNodeDto();
            String id = device != null && device.getVarName() != null && !device.getVarName().isBlank()
                    ? device.getVarName() : "invalid_" + index;
            node.setId(id);
            node.setTemplateName(device != null && device.getTemplateName() != null
                    && !device.getTemplateName().isBlank() ? device.getTemplateName() : "Invalid");
            node.setLabel(device != null && device.getDeviceLabel() != null
                    && !device.getDeviceLabel().isBlank() ? device.getDeviceLabel() : id);
            DeviceNodeDto.Position position = new DeviceNodeDto.Position();
            position.setX((double) index * 240);
            position.setY(0.0);
            node.setPosition(position);
            node.setState("Working");
            node.setWidth(160);
            node.setHeight(120);
            nodes.add(node);
        }
        return nodes;
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
