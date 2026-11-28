package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.CapturingThreadPoolTaskExecutor;
import cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.DirectThreadPoolTaskExecutor;
import cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.FailingThreadPoolTaskExecutor;
import cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.RejectingThreadPoolTaskExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.NusmvResult;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.EnvironmentProvenanceCollector;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;

import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.configure.AsyncTaskAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.SpecResultDto;
import cn.edu.nju.Iot_Verify.dto.verification.SpecificationSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationResultDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskQuotaExceededException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.TraceRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.repository.projection.CompletedRunDeletionProjection;
import cn.edu.nju.Iot_Verify.repository.projection.TraceSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTaskProgressProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationRunSummaryProjection;
import cn.edu.nju.Iot_Verify.service.ChatExecutionLeaseGuard;
import cn.edu.nju.Iot_Verify.service.FormalOperationAdmission;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.mapper.TraceMapper;
import cn.edu.nju.Iot_Verify.util.mapper.VerificationTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import org.mockito.junit.jupiter.MockitoExtension;


import java.lang.reflect.Method;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static cn.edu.nju.Iot_Verify.service.impl.ModelRunBoardFixture.playbackNodes;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunBoardFixture.stubBoardSnapshot;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunBoardFixture.stubResolvedGeneration;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.blockWorker;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.createTempModelFile;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.interruptQueuedRun;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.readRequestJson;
import static cn.edu.nju.Iot_Verify.service.impl.ModelRunFixtures.readResultCode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for VerificationServiceImpl.buildVerificationResult fail-closed logic.
 * Uses reflection to invoke the private method directly.
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceImplBuildResultTest {
    @TempDir
    Path modelRunTempDir;

    @Test
    void satisfiedResponseReportsUnreachableTriggerWithoutCreatingAnAuxiliaryViolation() throws Exception {
        SpecificationDto spec = makeEffectiveSpec("response");
        spec.setName("Safety requirement");
        spec.setTemplateId("4");
        var emitted = new SmvGenerationContext.EmittedSpec(spec, "response", "CTLSPEC AG(a -> AX b)", "CTLSPEC !EF(a)");
        var result = invoke(NusmvResult.success("", List.of(
                new SpecCheckResult("!(EF a)", true, "auxiliary diagnostic text"),
                new SpecCheckResult("AG (a -> AX b)", true, null))), singleDevice(), List.of(spec), new ArrayList<>(), List.of(emitted));
        assertEquals(VerificationOutcome.SATISFIED, result.getOutcome());
        assertEquals(1, result.getSpecResults().size());
        assertEquals(cn.edu.nju.Iot_Verify.dto.verification.AntecedentReachability.UNREACHABLE,
                result.getSpecResults().get(0).getAntecedentReachability());
        assertTrue(result.getTraces().isEmpty());
        verifyNoInteractions(smvTraceParser);
    }

    @Test
    void missingResponseDiagnosticMakesTheRunInconclusive() throws Exception {
        SpecificationDto spec = makeEffectiveSpec("response");
        spec.setName("Safety requirement");
        spec.setTemplateId("6");
        var emitted = new SmvGenerationContext.EmittedSpec(spec, "response", "LTLSPEC G(a -> F G b)", "CTLSPEC !EF(a)");
        var result = invoke(NusmvResult.success("", List.of(new SpecCheckResult("G(a -> F G b)", true, null))),
                singleDevice(), List.of(spec), new ArrayList<>(), List.of(emitted));
        assertEquals(VerificationOutcome.INCONCLUSIVE, result.getOutcome());
        assertEquals(VerificationOutcome.SATISFIED, result.getSpecResults().get(0).getOutcome());
        assertEquals(cn.edu.nju.Iot_Verify.dto.verification.AntecedentReachability.INCONCLUSIVE,
                result.getSpecResults().get(0).getAntecedentReachability());
    }
    @Test
    void progressUsesOnlyOwnedScalarsAndPreservesTerminalFallbacks() {
        var progress = mock(VerificationTaskProgressProjection.class);
        when(progress.getProgress()).thenReturn(null);
        when(taskRepository.findProgressByIdAndUserId(7L, 1L)).thenReturn(Optional.of(progress));
        for (var status : VerificationTaskPo.TaskStatus.values()) {
            when(progress.getStatus()).thenReturn(status);
            assertEquals(status == VerificationTaskPo.TaskStatus.PENDING || status == VerificationTaskPo.TaskStatus.RUNNING
                    ? 0 : 100, service.getTaskProgress(1L, 7L));
        }
        when(progress.getProgress()).thenReturn(37);
        assertEquals(37, service.getTaskProgress(1L, 7L));
        when(taskRepository.findProgressByIdAndUserId(7L, 2L)).thenReturn(Optional.empty());
        assertThrows(cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException.class,
                () -> service.getTaskProgress(2L, 7L));
        verify(taskRepository, never()).findByIdAndUserId(any(), any());
    }

    @Mock private SmvGenerator smvGenerator;
    @Mock private EnvironmentProvenanceCollector provenanceCollector;
    @Mock private SmvTraceParser smvTraceParser;
    @Mock private NusmvExecutor nusmvExecutor;
    @Mock private NusmvConfig nusmvConfig;
    @Mock private VerificationTaskRepository taskRepository;
    @Mock private ChatExecutionLeaseGuard chatExecutionLeaseGuard;
    @Mock private FormalOperationAdmission formalOperationAdmission;
    @Mock private TraceRepository traceRepository;
    @Mock private TraceMapper traceMapper;
    @Mock private UserRepository userRepository;
    @Mock private SpecificationMapper specificationMapper;
    @Mock private VerificationTaskMapper verificationTaskMapper;
    @Mock private BoardDataConverter boardDataConverter;

    private VerificationServiceImpl service;
    private DirectThreadPoolTaskExecutor verificationTaskExecutor;
    private ThreadPoolTaskExecutor syncVerificationExecutor;
    private TransactionTemplate transactionTemplate;
    private Method buildVerificationResult;
    private SimpleTransactionStatus lastTransactionStatus;

    @BeforeEach
    void setUp() throws Exception {
        verificationTaskExecutor = new DirectThreadPoolTaskExecutor();
        syncVerificationExecutor = new ThreadPoolTaskExecutor();
        syncVerificationExecutor.setCorePoolSize(1);
        syncVerificationExecutor.setMaxPoolSize(1);
        syncVerificationExecutor.setQueueCapacity(10);
        syncVerificationExecutor.setThreadNamePrefix("test-sync-verify-");
        syncVerificationExecutor.initialize();
        transactionTemplate = inlineTransactionTemplate();
        lenient().when(formalOperationAdmission.execute(anyLong(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get());

        service = new VerificationServiceImpl(
                smvGenerator, provenanceCollector, smvTraceParser, nusmvExecutor, nusmvConfig,
                taskRepository, traceRepository, traceMapper, userRepository,
                specificationMapper, verificationTaskMapper, new ObjectMapper().findAndRegisterModules(),
                verificationTaskExecutor, syncVerificationExecutor, transactionTemplate,
                chatExecutionLeaseGuard, formalOperationAdmission, new AsyncTaskAdmissionConfig(), boardDataConverter);
        lenient().when(taskRepository.currentDatabaseTime()).thenAnswer(invocation -> LocalDateTime.now());
        lenient().when(taskRepository.findByIdForUpdate(anyLong())).thenAnswer(invocation -> Optional.of(
                VerificationTaskPo.builder().id(invocation.getArgument(0)).build()));
        var queuedProgress = mock(VerificationTaskProgressProjection.class);
        lenient().when(queuedProgress.getStatus()).thenReturn(VerificationTaskPo.TaskStatus.PENDING);
        lenient().when(taskRepository.findProgressByIdAndUserId(anyLong(), anyLong()))
                .thenReturn(Optional.of(queuedProgress));
        lenient().when(taskRepository.updateProgressIfActive(anyLong(), anyInt(), any(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        lenient().when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(new UserPo()));
        lenient().when(userRepository.existsById(anyLong())).thenReturn(true);
        lenient().when(taskRepository.save(any(VerificationTaskPo.class))).thenAnswer(invocation -> {
            VerificationTaskPo po = invocation.getArgument(0);
            if (po.getId() == null) po.setId(1000L);
            return po;
        });
        DeviceManifest templateSnapshot = DeviceManifest.builder().name("test-template").build();
        lenient().when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(
                        Map.of("test-template", templateSnapshot), Map.of()));
        lenient().when(smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(Map.of());

        buildVerificationResult = VerificationServiceImpl.class.getDeclaredMethod(
                "buildVerificationResult",
                NusmvResult.class, List.class, List.class, List.class,
                Long.class, Long.class, List.class, Map.class, Map.class,
                List.class, List.class, int.class, int.class, String.class);
        buildVerificationResult.setAccessible(true);
    }

    @Test
    void fullTraceCollectionOrdersLoadedPayloadsWithoutDatabaseSortingJson() {
        LocalDateTime olderTime = LocalDateTime.of(2026, 7, 30, 10, 0);
        TracePo oldest = TracePo.builder().id(11L).verificationTaskId(9L).createdAt(olderTime).build();
        TracePo sameTimeNewerId = TracePo.builder().id(12L).verificationTaskId(9L).createdAt(olderTime).build();
        TracePo newest = TracePo.builder().id(13L).verificationTaskId(10L).createdAt(olderTime.plusSeconds(1)).build();
        List<TraceDto> expected = List.of(TraceDto.builder().id(13L).build());
        when(traceRepository.findByUserId(7L))
                .thenReturn(List.of(oldest, newest, sameTimeNewerId));
        when(traceMapper.toDtoList(anyList(), anyMap())).thenReturn(expected);

        assertSame(expected, service.getUserTraces(7L));
        verify(taskRepository).findTraceContextsByUserIdAndIdInAndStatus(
                7L, List.of(10L, 9L), VerificationTaskPo.TaskStatus.COMPLETED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TracePo>> traces = ArgumentCaptor.forClass(List.class);
        verify(traceMapper).toDtoList(traces.capture(), anyMap());
        assertEquals(List.of(13L, 12L, 11L),
                traces.getValue().stream().map(TracePo::getId).toList());
    }

    @Test
    void templateSnapshotEnvelopeFreezesPerDeviceTokenProvenance() throws Exception {
        DeviceSmvData bundled = new DeviceSmvData();
        bundled.setVarName("bundled_device");
        bundled.setModelTokenSource(ModelTokenSource.BUNDLED);
        DeviceSmvData custom = new DeviceSmvData();
        custom.setVarName("custom_device");
        custom.setModelTokenSource(ModelTokenSource.CUSTOM);
        Method method = VerificationServiceImpl.class.getDeclaredMethod(
                "buildTemplateSnapshotsJson", Map.class, Map.class);
        method.setAccessible(true);

        String json = (String) method.invoke(service,
                Map.of("template", DeviceManifest.builder().name("template").build()),
                Map.of("bundled_device", bundled, "custom_device", custom));
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals(1, root.path("schemaVersion").asInt());
        assertTrue(root.path("manifests").has("template"));
        assertEquals("BUNDLED", root.path("modelTokenSourcesByDeviceId")
                .path("bundled_device").asText());
        assertEquals("CUSTOM", root.path("modelTokenSourcesByDeviceId")
                .path("custom_device").asText());
    }

    @Test
    void requestSnapshotPreservesServerCapturedTokenProvenance() throws Exception {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("custom_device");
        device.setTemplateName("custom-template");
        device.setModelTokenSource(ModelTokenSource.CUSTOM);
        VerificationRequestDto request = new VerificationRequestDto();
        request.setDevices(List.of(device));
        Method method = VerificationServiceImpl.class.getDeclaredMethod(
                "snapshotRequest", VerificationRequestDto.class, AttackScenarioDto.class);
        method.setAccessible(true);

        VerificationRequestDto snapshot = (VerificationRequestDto) method.invoke(
                service, request, AttackScenarioDto.none());

        assertNotSame(device, snapshot.getDevices().get(0));
        assertEquals(ModelTokenSource.CUSTOM,
                snapshot.getDevices().get(0).getModelTokenSource());
    }

    private VerificationServiceImpl serviceWithVerificationExecutor(ThreadPoolTaskExecutor executor) {
        return new VerificationServiceImpl(
                smvGenerator, provenanceCollector, smvTraceParser, nusmvExecutor, nusmvConfig,
                taskRepository, traceRepository, traceMapper, userRepository,
                specificationMapper, verificationTaskMapper, new ObjectMapper().findAndRegisterModules(),
                executor, syncVerificationExecutor, transactionTemplate, chatExecutionLeaseGuard,
                formalOperationAdmission, new AsyncTaskAdmissionConfig(), boardDataConverter);
    }

    private VerificationServiceImpl serviceWithTransactionTemplate(TransactionTemplate transactionTemplate) {
        return new VerificationServiceImpl(
                smvGenerator, provenanceCollector, smvTraceParser, nusmvExecutor, nusmvConfig,
                taskRepository, traceRepository, traceMapper, userRepository,
                specificationMapper, verificationTaskMapper, new ObjectMapper().findAndRegisterModules(),
                verificationTaskExecutor, syncVerificationExecutor, transactionTemplate,
                chatExecutionLeaseGuard, formalOperationAdmission, new AsyncTaskAdmissionConfig(), boardDataConverter);
    }

    private void assignVerificationTaskId(long taskId) {
        when(taskRepository.save(any(VerificationTaskPo.class))).thenAnswer(invocation -> {
            VerificationTaskPo task = invocation.getArgument(0);
            task.setId(taskId);
            return task;
        });
    }

    private VerificationServiceImpl serviceWithAdmissionConfig(AsyncTaskAdmissionConfig admissionConfig) {
        return new VerificationServiceImpl(
                smvGenerator, provenanceCollector, smvTraceParser, nusmvExecutor, nusmvConfig,
                taskRepository, traceRepository, traceMapper, userRepository,
                specificationMapper, verificationTaskMapper, new ObjectMapper().findAndRegisterModules(),
                verificationTaskExecutor, syncVerificationExecutor, transactionTemplate,
                chatExecutionLeaseGuard, formalOperationAdmission, admissionConfig, boardDataConverter);
    }

    private TransactionTemplate inlineTransactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                lastTransactionStatus = new SimpleTransactionStatus();
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

    private TransactionTemplate commitFailingTransactionTemplate(AtomicBoolean failCommit) {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                if (failCommit.getAndSet(false)) {
                    throw new RuntimeException("commit outcome unknown");
                }
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    @AfterEach
    void tearDown() {
        syncVerificationExecutor.shutdown();
    }

    private VerificationResultDto invoke(NusmvResult result,
                                         List<DeviceVerificationDto> devices,
                                         List<SpecificationDto> specs,
                                         List<String> checkLogs) throws Exception {
        return (VerificationResultDto) buildVerificationResult.invoke(
                service, result, devices, List.of(), specs, 1L, null, checkLogs, Map.of(), Map.of(),
                emittedSpecsFor(specs), List.of(), 0, 0, null);
    }

    private VerificationResultDto invoke(NusmvResult result,
                                         List<DeviceVerificationDto> devices,
                                         List<SpecificationDto> specs,
                                         List<String> checkLogs,
                                         List<SmvGenerationContext.EmittedSpec> emittedSpecs) throws Exception {
        return invoke(result, devices, specs, checkLogs, emittedSpecs, List.of(), 0, 0);
    }

    private VerificationResultDto invoke(NusmvResult result,
                                         List<DeviceVerificationDto> devices,
                                         List<SpecificationDto> specs,
                                         List<String> checkLogs,
                                         List<SmvGenerationContext.EmittedSpec> emittedSpecs,
                                         List<ModelGenerationIssueDto> generationIssues,
                                         int disabledRuleCount,
                                         int skippedSpecCount) throws Exception {
        return (VerificationResultDto) buildVerificationResult.invoke(
                service, result, devices, List.of(), specs, 1L, null, checkLogs, Map.of(), Map.of(),
                emittedSpecs, generationIssues, disabledRuleCount, skippedSpecCount, null);
    }

    private List<SmvGenerationContext.EmittedSpec> emittedSpecsFor(List<SpecificationDto> specs) {
        if (specs == null) {
            return List.of();
        }
        return specs.stream()
                .filter(Objects::nonNull)
                .filter(s -> (s.getAConditions() != null && !s.getAConditions().isEmpty()) ||
                             (s.getIfConditions() != null && !s.getIfConditions().isEmpty()))
                .map(s -> new SmvGenerationContext.EmittedSpec(s, s.getId(), ""))
                .toList();
    }

    private SmvGenerator.GenerateResult generateResult(File smvFile, List<SpecificationDto> emittedSpecs) {
        return new SmvGenerator.GenerateResult(smvFile, Map.of(), List.of(), 0, 0, emittedSpecsFor(emittedSpecs));
    }

    private List<DeviceVerificationDto> singleDevice() {
        DeviceVerificationDto d = new DeviceVerificationDto();
        d.setVarName("testdevice");
        d.setDeviceLabel("Hall sensor");
        d.setTemplateName("light");
        return List.of(d);
    }

    // --- effectiveSpecs = 0: all specs filtered out -> inconclusive ---

    @Test
    void verifyWithNoEmittedSpecs_returnsInconclusiveBecauseNothingWasVerified() throws Exception {
        File smv = createTempModelFile(modelRunTempDir);
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        stubResolvedGeneration(smvGenerator)
                .thenReturn(new SmvGenerator.GenerateResult(smv, Map.of(), List.of(), 0, 1, List.of()));
        when(nusmvExecutor.execute(smv)).thenReturn(NusmvResult.success("", List.of()));

        VerificationResultDto result = service.verify(1L, makeRequest(
                singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        assertEquals(VerificationOutcome.INCONCLUSIVE, result.getOutcome());
        assertFalse(result.isModelComplete());
        assertTrue(result.getSpecResults().isEmpty());
        assertEquals(1, result.getSkippedSpecCount());
        assertTrue(result.getCheckLogs().stream().anyMatch(l -> l.contains("No valid specifications")));
        assertTrue(result.getCheckLogs().stream().anyMatch(l -> l.contains("no specifications were emitted")));
    }

    // --- specCheckResults empty but effectiveSpecs > 0 -> inconclusive ---

    @Test
    void emptySpecResults_withEffectiveSpecs_returnsInconclusive() throws Exception {
        SpecificationDto spec = makeEffectiveSpec("s1");
        NusmvResult result = NusmvResult.success("some output", List.of());
        List<String> logs = new ArrayList<>();

        VerificationResultDto dto = invoke(result, singleDevice(), List.of(spec), logs);

        assertEquals(VerificationOutcome.INCONCLUSIVE, dto.getOutcome());
        assertEquals(1, dto.getSpecResults().size());
        assertSpecResult(dto.getSpecResults().get(0), "s1", VerificationOutcome.INCONCLUSIVE, "");
        assertEquals("1", dto.getSpecResults().get(0).getTemplateId());
        assertEquals("Safety requirement", dto.getSpecResults().get(0).getSpecificationLabel());
        assertEquals("CTL", dto.getSpecResults().get(0).getFormulaKind());
        assertTrue(dto.getSpecResults().get(0).getFormulaPreview().contains("Hall sensor"));
        assertFalse(dto.getSpecResults().get(0).getFormulaPreview().contains("testdevice"));
        assertTrue(logs.stream().anyMatch(l -> l.contains("no satisfied/violated conclusion")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("incomplete or unreliable")));
    }

    // --- mismatch: fewer results than specs -> missing result is inconclusive ---

    @Test
    void fewerResultsThanSpecs_reportsMissingResultAsInconclusive() throws Exception {
        SpecificationDto spec1 = makeEffectiveSpec("s1");
        SpecificationDto spec2 = makeEffectiveSpec("s2");

        // NuSMV only returned 1 result for 2 specs
        SpecCheckResult scr = new SpecCheckResult("expr1", true, null);
        NusmvResult result = NusmvResult.success("output", List.of(scr));

        List<String> logs = new ArrayList<>();

        VerificationResultDto dto = invoke(result, List.of(), List.of(spec1, spec2), logs);

        assertEquals(VerificationOutcome.INCONCLUSIVE, dto.getOutcome());
        assertEquals(2, dto.getSpecResults().size());
        assertSpecResult(dto.getSpecResults().get(0), "s1", VerificationOutcome.SATISFIED, "expr1");
        assertSpecResult(dto.getSpecResults().get(1), "s2", VerificationOutcome.INCONCLUSIVE, "");
        assertTrue(logs.stream().anyMatch(l -> l.contains("mismatch")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("missing")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("incomplete or unreliable")));
    }

    // --- mismatch: more results than specs -> inconclusive, extras discarded ---

    @Test
    void moreResultsThanSpecs_returnsInconclusiveAndTruncates() throws Exception {
        SpecificationDto spec1 = makeEffectiveSpec("s1");

        // NuSMV returned 2 results for 1 spec
        SpecCheckResult scr1 = new SpecCheckResult("expr1", true, null);
        SpecCheckResult scr2 = new SpecCheckResult("expr2", false, null);
        NusmvResult result = NusmvResult.success("output", List.of(scr1, scr2));

        List<String> logs = new ArrayList<>();

        VerificationResultDto dto = invoke(result, List.of(), List.of(spec1), logs);

        assertEquals(VerificationOutcome.INCONCLUSIVE, dto.getOutcome());
        // specResults should be exactly effectiveSpecs.size() = 1, not 2
        assertEquals(1, dto.getSpecResults().size());
        assertTrue(logs.stream().anyMatch(l -> l.contains("extra")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("incomplete or unreliable")));
    }

    // --- exact match, all pass -> satisfied ---

    @Test
    void exactMatch_allPass_returnsSatisfied() throws Exception {
        SpecificationDto spec1 = makeEffectiveSpec("s1");
        SpecCheckResult scr = new SpecCheckResult("expr1", true, null);
        NusmvResult result = NusmvResult.success("output", List.of(scr));

        List<String> logs = new ArrayList<>();

        VerificationResultDto dto = invoke(result, List.of(), List.of(spec1), logs);

        assertEquals(VerificationOutcome.SATISFIED, dto.getOutcome());
        assertTrue(dto.isModelComplete());
        assertEquals(1, dto.getSpecResults().size());
        assertSpecResult(dto.getSpecResults().get(0), "s1", VerificationOutcome.SATISFIED, "expr1");
    }

    @Test
    void verdictCapturesConditionsAndTokenProvenanceForHistory() throws Exception {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setId("reported_temperature");
        condition.setSide("a");
        condition.setDeviceId("testdevice");
        condition.setDeviceLabel("Outdated label");
        condition.setTargetType("variable");
        condition.setVariableSource("reported");
        condition.setKey("temperature");
        condition.setRelation(">");
        condition.setValue("30");
        SpecificationDto spec = new SpecificationDto();
        spec.setId("captured_spec");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(condition));
        DeviceSmvData device = new DeviceSmvData();
        device.setDeviceLabel("Hall sensor");
        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        device.setModes(List.of("MachineState"));
        NusmvResult result = NusmvResult.success("output", List.of(new SpecCheckResult("expr1", true, null)));
        VerificationResultDto dto = (VerificationResultDto) buildVerificationResult.invoke(
                service, result, singleDevice(), List.of(), List.of(spec), 1L, null, new ArrayList<>(),
                Map.of("testdevice", device), Map.of(), emittedSpecsFor(List.of(spec)), List.of(), 0, 0, null);
        SpecResultDto verdict = dto.getSpecResults().get(0);
        var snapshot = verdict.getSpecificationSnapshot();
        assertNotNull(snapshot);
        assertEquals("captured_spec", snapshot.id());
        assertEquals("reported", snapshot.aConditions().get(0).getVariableSource());
        assertEquals("Hall sensor", snapshot.devices().get("testdevice").label());
        assertEquals(ModelTokenSource.BUNDLED, snapshot.devices().get("testdevice").modelTokenSource());
        assertEquals(1, snapshot.devices().get("testdevice").modeCount());

        condition.setValue("99");
        condition.setVariableSource("environment");
        device.setDeviceLabel("Renamed sensor");
        device.setModelTokenSource(ModelTokenSource.CUSTOM);
        assertEquals("30", snapshot.aConditions().get(0).getValue());
        assertEquals("reported", snapshot.aConditions().get(0).getVariableSource());
        assertEquals("Hall sensor", snapshot.devices().get("testdevice").label());
        SpecResultDto reopened = new ObjectMapper().readValue(JsonUtils.toJson(verdict), SpecResultDto.class);
        assertEquals(snapshot, reopened.getSpecificationSnapshot());
    }

    @Test
    void exactMatch_checksOnlyEmittedSpecsAndReportsSkippedItem() throws Exception {
        SpecificationDto specA = makeEffectiveSpec("a");
        SpecificationDto specB = makeEffectiveSpec("b");
        specB.setTemplateLabel("Bedroom privacy requirement");
        SpecificationDto specC = makeEffectiveSpec("c");
        List<SmvGenerationContext.EmittedSpec> emittedSpecs = List.of(
                new SmvGenerationContext.EmittedSpec(specA, "a", "CTLSPEC AG(a_ok)"),
                new SmvGenerationContext.EmittedSpec(specC, "c", "CTLSPEC AG(c_ok)"));
        List<ModelGenerationIssueDto> issues = List.of(ModelGenerationIssueDto.builder()
                .issueType("SPECIFICATION_SKIPPED")
                .itemLabel("Bedroom privacy requirement")
                .reason("Privacy-state modeling was not enabled for this run.")
                .build());

        NusmvResult result = NusmvResult.success("output", List.of(
                new SpecCheckResult("CTLSPEC AG(a_ok)", true, null),
                new SpecCheckResult("CTLSPEC AG(c_ok)", false, null)));
        List<String> logs = new ArrayList<>();

        VerificationResultDto dto = invoke(result, List.of(), List.of(specA, specB, specC), logs,
                emittedSpecs, issues, 0, 1);

        assertEquals(VerificationOutcome.VIOLATED, dto.getOutcome());
        assertFalse(dto.isModelComplete());
        assertEquals(2, dto.getSpecResults().size());
        assertSpecResult(dto.getSpecResults().get(0), "a", VerificationOutcome.SATISFIED, "CTLSPEC AG(a_ok)");
        assertSpecResult(dto.getSpecResults().get(1), "c", VerificationOutcome.VIOLATED, "CTLSPEC AG(c_ok)");
        assertEquals(issues, dto.getGenerationIssues());
        assertEquals(1, dto.getSkippedSpecCount());
    }

    @Test
    void nuSmvResultsReturnedOutOfOrder_areMatchedBackToGeneratedSpecsByExpression() throws Exception {
        SpecificationDto cameraSpec = makeEffectiveSpec("camera_spec");
        SpecificationDto motionSpec = makeEffectiveSpec("motion_spec");
        SpecificationDto tempSpec = makeEffectiveSpec("temp_spec");
        List<SmvGenerationContext.EmittedSpec> emittedSpecs = List.of(
                new SmvGenerationContext.EmittedSpec(cameraSpec, "camera_spec",
                        "CTLSPEC AG !(hall_camera.MachineState=takingphoto)"),
                new SmvGenerationContext.EmittedSpec(motionSpec, "motion_spec",
                        "CTLSPEC AG((a_motion=active) -> AF(night_alarm.AlertState=siren))"),
                new SmvGenerationContext.EmittedSpec(tempSpec, "temp_spec",
                        "CTLSPEC AG((a_temperature>28) -> AF(main_thermostat.ThermostatMode=cool))"));

        when(smvTraceParser.parseCounterexampleStates(eq("trace"), anyMap(), anyList()))
                .thenReturn(List.of(TraceStateDto.builder().stateIndex(1).devices(List.of()).build()));
        when(specificationMapper.toJson(cameraSpec)).thenReturn("{\"id\":\"camera_spec\"}");

        NusmvResult result = NusmvResult.success("output", List.of(
                new SpecCheckResult("AG (a_motion = active -> AF night_alarm.AlertState = siren)", true, null),
                new SpecCheckResult("AG (a_temperature > 28 -> AF main_thermostat.ThermostatMode = cool)", true, null),
                new SpecCheckResult("AG !(hall_camera.MachineState = takingphoto)", false, "trace")));

        VerificationResultDto dto = invoke(result, List.of(), List.of(cameraSpec, motionSpec, tempSpec),
                new ArrayList<>(), emittedSpecs);

        assertEquals(VerificationOutcome.VIOLATED, dto.getOutcome());
        assertEquals(3, dto.getSpecResults().size());
        assertSpecResult(dto.getSpecResults().get(0), "camera_spec", VerificationOutcome.VIOLATED,
                "AG !(hall_camera.MachineState = takingphoto)");
        assertSpecResult(dto.getSpecResults().get(1), "motion_spec", VerificationOutcome.SATISFIED,
                "AG (a_motion = active -> AF night_alarm.AlertState = siren)");
        assertSpecResult(dto.getSpecResults().get(2), "temp_spec", VerificationOutcome.SATISFIED,
                "AG (a_temperature > 28 -> AF main_thermostat.ThermostatMode = cool)");
        assertEquals(1, dto.getTraces().size());
        assertEquals("camera_spec", dto.getTraces().get(0).getViolatedSpecId());
        assertEquals("{\"id\":\"camera_spec\"}", dto.getTraces().get(0).getViolatedSpecJson());
    }

    @Test
    void verify_executorRejected_throwsServiceUnavailable() {
        syncVerificationExecutor.shutdown();

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> service.verify(
                        1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));
        assertTrue(ex.getMessage().contains("busy"));
    }

    @Test
    void verify_usesOneBoardCaptureAndNeverReloadsMutableTemplates() {
        var request = makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false);
        var initial = boardDataConverter.getModelInputSnapshot(1L);
        Map<String, DeviceManifest> templates = Map.of(
                "light", DeviceManifest.builder().name("light").build());
        var board = new BoardDataConverter.ModelInputSnapshot(initial.nodes(), initial.devices(),
                initial.environmentVariables(), initial.rules(), initial.specifications(), templates);
        when(boardDataConverter.getModelInputSnapshot(1L)).thenReturn(board);
        org.mockito.Mockito.clearInvocations(boardDataConverter);
        when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), same(board.templateManifests())))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(board.templateManifests(), Map.of()));
        syncVerificationExecutor.shutdown();

        assertThrows(ServiceUnavailableException.class, () -> service.verify(1L, request));

        verify(boardDataConverter).getModelInputSnapshot(1L);
        verify(smvGenerator).captureDeviceModelFromTemplateSnapshots(anyList(), same(board.templateManifests()));
    }

    @Test
    void acceptedTaskFreezesTheSameFingerprintAsTheCapturedBoard() {
        var request = makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false);
        var board = boardDataConverter.getModelInputSnapshot(1L);
        var templates = Map.of("light", DeviceManifest.builder().name("light").build());
        board = new BoardDataConverter.ModelInputSnapshot(board.nodes(), board.devices(),
                board.environmentVariables(), board.rules(), board.specifications(), templates);
        when(boardDataConverter.getModelInputSnapshot(1L)).thenReturn(board);
        when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), eq(templates)))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(templates, Map.of()));

        serviceWithVerificationExecutor(new CapturingThreadPoolTaskExecutor()).submitVerification(1L, request);

        var saved = ArgumentCaptor.forClass(VerificationTaskPo.class);
        verify(taskRepository).save(saved.capture());
        var fingerprint = JsonUtils.fromJson(saved.getValue().getModelSnapshotJson(),
                cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto.class).getModelFingerprint();
        var canonical = new cn.edu.nju.Iot_Verify.util.ModelFingerprint(new ObjectMapper().findAndRegisterModules());
        assertEquals(canonical.modelFingerprint(board), fingerprint);
        String requestJson = saved.getValue().getRequestJson();
        String templatesJson = saved.getValue().getTemplateSnapshotsJson();
        assertNotNull(requestJson);
        assertNotNull(templatesJson);
        assertEquals(board.devices().get(0).getState(), JsonUtils.fromJson(
                requestJson, VerificationRequestDto.class).getDevices().get(0).getState());
        assertEquals("light", JsonUtils.fromJson(templatesJson,
                cn.edu.nju.Iot_Verify.dto.model.TemplateSnapshotBundleDto.class).getManifests().get("light").getName());
        board.devices().get(0).setState("changed-after-submission");
        templates.get("light").setDescription("changed-after-submission");
        assertNotEquals(canonical.modelFingerprint(board), fingerprint);
        assertEquals(requestJson, saved.getValue().getRequestJson());
        assertEquals(templatesJson, saved.getValue().getTemplateSnapshotsJson());
    }

    @Test
    void verify_nusmvBusy_throwsServiceUnavailable() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        File smv = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(makeEffectiveSpec("s1"))));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.busy("NuSMV execution is busy, please retry later"));

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> service.verify(
                        1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));
        assertTrue(ex.getMessage().contains("busy"));
        assertEquals(503, readResultCode(smv));
        JsonNode request = readRequestJson(smv);
        assertFalse(request.path("attack").asBoolean());
        assertEquals(0, request.path("attackBudget").asInt());
        assertEquals(1, request.path("specs").size());
        verify(formalOperationAdmission, never()).registerCurrentLeaseCommitFence();
    }

    @Test
    void verify_smvGenerationError_rethrowsSmvGenerationException() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        stubResolvedGeneration(smvGenerator)
                .thenThrow(SmvGenerationException.ambiguousDeviceReference("Sensor", List.of("sensor_1", "sensor_2")));

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> service.verify(
                        1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));
        assertEquals("AMBIGUOUS_DEVICE_REFERENCE", ex.getErrorCategory());
    }

    @Test
    void submitVerification_reportsRangeAndEnumRelationBeforeDispatching() throws Exception {
        var device = singleDevice().get(0);
        device.setTemplateName("Oven");
        var manifest = JsonUtils.fromJson(
                Files.readString(Path.of("src/main/resources/deviceTemplate/Oven.json")), DeviceManifest.class);
        var captured = new DeviceSmvData();
        captured.setManifest(manifest);
        captured.setVariables(manifest.getInternalVariables());
        when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(Map.of("Oven", manifest), Map.of("testdevice", captured)));
        var spec = makeEffectiveSpec("domains");
        var range = spec.getAConditions().get(0);
        range.setTargetType("variable");
        range.setVariableSource("reported");
        range.setKey("operationTime");
        range.setRelation(" LTE ");
        range.setValue("101");
        var enumCondition = new SpecConditionDto();
        enumCondition.setDeviceId("testdevice");
        enumCondition.setTargetType("variable");
        enumCondition.setVariableSource("reported");
        enumCondition.setKey("ovenJobState");
        enumCondition.setRelation(" GT ");
        enumCondition.setValue("missing");
        spec.setAConditions(List.of(range, enumCondition));

        var failure = assertThrows(ValidationException.class, () -> service.submitVerification(
                1L, makeRequest(List.of(device), List.of(), List.of(spec), false, 0, false)));

        assertEquals(Map.of(
                "specs[0].aConditions[0].value", "Variable value out of range for 'operationTime': 101 (allowed 0..100)",
                "specs[0].aConditions[1].value", "enum variable conditions support only =, !=, IN, and NOT_IN"),
                failure.getErrors());
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verifyNoInteractions(nusmvExecutor);
    }

    @Test
    void submitVerificationDistinguishesReadableValuesFromDeclaredLabels() {
        var device = singleDevice().get(0);
        var manifest = DeviceManifest.builder().name(device.getTemplateName()).build();
        var firstDeclaration = DeviceManifest.InternalVariable.builder()
                .name("temperature").isInside(false).reads(false).lowerBound(15).upperBound(35).build();
        var laterDeclaration = DeviceManifest.InternalVariable.builder()
                .name("temperature").isInside(true).lowerBound(15).upperBound(35).build();
        var captured = new DeviceSmvData();
        captured.setManifest(manifest);
        captured.setVariables(Arrays.asList(null, firstDeclaration, laterDeclaration));
        when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(
                        Map.of(device.getTemplateName(), manifest), Map.of("testdevice", captured)));

        var spec = makeEffectiveSpec("capabilities");
        var reading = spec.getAConditions().get(0);
        reading.setTargetType("variable");
        reading.setVariableSource("reported");
        reading.setKey(" temperature ");
        reading.setValue("20");
        var label = makeEffectiveSpec("label").getAConditions().get(0);
        label.setTargetType("privacy");
        label.setPropertyScope("variable");
        label.setKey(" temperature ");
        label.setValue("public");
        var wrongCase = makeEffectiveSpec("wrong-case").getAConditions().get(0);
        wrongCase.setTargetType("privacy");
        wrongCase.setPropertyScope("variable");
        wrongCase.setKey("Temperature");
        wrongCase.setValue("public");
        spec.setAConditions(List.of(reading, label, wrongCase));

        var failure = assertThrows(ValidationException.class, () -> service.submitVerification(
                1L, makeRequest(List.of(device), List.of(), List.of(spec), false, 0, false)));
        assertEquals(Map.of(
                "specs[0].aConditions[0].key", "Unknown internal or environment variable for specification: temperature",
                "specs[0].aConditions[2].key", "Unknown property variable for specification: Temperature"),
                failure.getErrors());
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
    }

    @Test
    void submitVerification_emptySpecs_rejectsBeforeCreatingTask() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(), List.of(), false, 0, false)));

        assertTrue(ex.getMessage().contains("Add at least one specification to the board before verifying"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
    }

    @Test
    void submitVerification_activeUserLimit_rejectsBeforePersisting() {
        when(taskRepository.countByUserId(1L)).thenReturn(1L);
        when(taskRepository.countByUserIdAndStatusIn(eq(1L), anyList())).thenReturn(2L);

        AsyncTaskQuotaExceededException error = assertThrows(
                AsyncTaskQuotaExceededException.class,
                () -> service.submitVerification(1L, makeRequest(
                        singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertEquals("VERIFICATION_ACTIVE_TASK_LIMIT_REACHED", error.getReasonCode());
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
    }

    @Test
    void submitVerification_invalidIntensity_rejectsBeforeCreatingTask() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 51, false)));

        assertTrue(ex.getMessage().contains("Attack budget must be omitted or 0 when no attack scenario is selected"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_attackBudgetAboveModeledPointCount_rejectsBeforeCreatingTask() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(makeRule()),
                                List.of(makeEffectiveSpec("s1")), true, 2, false)));

        assertTrue(ex.getMessage().contains(
                "Attack budget cannot exceed the behavior-changing device and automation-link points (1)"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_attackEnabledWithZeroBudget_rejectsBeforeCreatingTask() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), true, 0, false)));

        assertTrue(ex.getMessage().contains("Attack budget must be between 1 and 50 for exhaustive verification"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_attackDisabledWithPositiveBudget_rejectsInsteadOfDroppingIt() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(),
                                List.of(makeEffectiveSpec("s1")), false, 3, false)));

        assertTrue(ex.getMessage().contains("Attack budget must be omitted or 0 when no attack scenario is selected"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void verify_invalidIntensity_rejectsBeforeSubmittingSyncExecutor() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.verify(
                        1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, -1, false)));

        assertTrue(ex.getMessage().contains("Attack budget must be omitted or 0 when no attack scenario is selected"));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_invalidNestedDevice_rejectsBeforeCreatingTask() throws Exception {
        DeviceVerificationDto invalidDevice = new DeviceVerificationDto();
        invalidDevice.setVarName(" ");
        invalidDevice.setTemplateName("light");

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(List.of(invalidDevice), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertTrue(ex.getMessage().contains("Device varName is required"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_ruleWithNullCommand_rejectsBeforeCreatingTask() throws Exception {
        RuleDto invalidRule = makeRule();
        invalidRule.setCommand(null);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(invalidRule), List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertEquals("Command cannot be null", ex.getErrors().get("rules[0].command"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_ruleWithBlankCommandFields_rejectsBeforeCreatingTask() throws Exception {
        RuleDto invalidRule = makeRule();
        invalidRule.setCommand(RuleDto.Command.builder()
                .deviceName(" ")
                .action("")
                .build());

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(invalidRule), List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertEquals("Command device name is required", ex.getErrors().get("rules[0].command.deviceName"));
        assertEquals("Command action is required", ex.getErrors().get("rules[0].command.action"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_duplicatePersistedRuleIds_rejectsBeforeCreatingTask() throws Exception {
        RuleDto firstRule = makeRule();
        firstRule.setId(41L);
        RuleDto duplicateRule = makeRule();
        duplicateRule.setId(41L);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(firstRule, duplicateRule),
                                List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertEquals("Rule id must be unique within one model request", ex.getErrors().get("rules[1].id"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_invalidNestedSpec_rejectsBeforeCreatingTask() throws Exception {
        SpecificationDto invalidSpec = makeEffectiveSpec("s1");
        invalidSpec.setName("Safety requirement");
        invalidSpec.setTemplateId("8");

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(), List.of(invalidSpec), false, 0, false)));

        assertTrue(ex.getMessage().contains("Template ID must be one of"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_nullSpecCondition_rejectsBeforeCreatingTask() throws Exception {
        SpecificationDto invalidSpec = makeEffectiveSpec("s1");
        invalidSpec.setAConditions(Collections.singletonList(null));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(), List.of(invalidSpec), false, 0, false)));

        assertTrue(ex.getMessage().contains("A-condition item cannot be null"));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_missingDisplayOnlyTemplateLabel_isAllowedByRuntimeValidation() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        VerificationServiceImpl capturingService = serviceWithVerificationExecutor(capturingExecutor);
        VerificationTaskPo savedTask = VerificationTaskPo.builder()
                .id(17L).userId(1L).status(VerificationTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();
        SpecificationDto spec = makeEffectiveSpec("s1");
        spec.setTemplateLabel(null);

        when(taskRepository.save(any(VerificationTaskPo.class))).thenReturn(savedTask);

        Long taskId = capturingService.submitVerification(
                1L, makeRequest(singleDevice(), List.of(), List.of(spec), false, 0, false));

        assertEquals(17L, taskId);
        assertNotNull(capturingExecutor.capturedTask());
        verify(taskRepository).save(any(VerificationTaskPo.class));
        verify(chatExecutionLeaseGuard).requireCurrentExecutionLease();
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void submitVerification_doesNotEnqueueTaskCancelledAfterCreation() {
        CapturingThreadPoolTaskExecutor executor = new CapturingThreadPoolTaskExecutor();
        VerificationServiceImpl queuedService = serviceWithVerificationExecutor(executor);
        VerificationTaskProgressProjection cancelled = mock(VerificationTaskProgressProjection.class);
        when(cancelled.getStatus()).thenReturn(VerificationTaskPo.TaskStatus.CANCELLED);
        when(taskRepository.findProgressByIdAndUserId(1000L, 1L)).thenReturn(Optional.of(cancelled));

        Long taskId = queuedService.submitVerification(
                1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        assertEquals(1000L, taskId);
        assertNull(executor.capturedTask());
    }

    @Test
    void submitVerification_queueRejected_removesUndispatchedTaskAndThrowsServiceUnavailable() {
        VerificationServiceImpl rejectingService = serviceWithVerificationExecutor(new RejectingThreadPoolTaskExecutor());
        VerificationTaskPo savedTask = VerificationTaskPo.builder()
                .id(12L).userId(1L).status(VerificationTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();

        when(taskRepository.save(any(VerificationTaskPo.class))).thenReturn(savedTask);
        when(taskRepository.deleteUndispatchedTask(
                eq(12L), eq(1L), anyString(), eq(VerificationTaskPo.TaskStatus.PENDING)))
                .thenReturn(1);

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> rejectingService.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertTrue(ex.getMessage().contains("busy"));
        verify(taskRepository).save(any(VerificationTaskPo.class));
        verify(taskRepository).deleteUndispatchedTask(
                eq(12L), eq(1L), anyString(), eq(VerificationTaskPo.TaskStatus.PENDING));
        verify(taskRepository, never()).failTaskIfActive(anyLong(), any(), any(), any(), any(),
                any(), any(), anyList(), anyString(), any());
        verify(taskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void submitVerification_runtimeDispatchFailure_removesUndispatchedTask() {
        VerificationServiceImpl failingService = serviceWithVerificationExecutor(new FailingThreadPoolTaskExecutor());
        VerificationTaskPo savedTask = VerificationTaskPo.builder()
                .id(18L).userId(1L).status(VerificationTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();
        when(taskRepository.save(any(VerificationTaskPo.class))).thenReturn(savedTask);
        when(taskRepository.deleteUndispatchedTask(
                eq(18L), eq(1L), anyString(), eq(VerificationTaskPo.TaskStatus.PENDING)))
                .thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> failingService.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(),
                                List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertEquals("executor failed", error.getMessage());
        verify(taskRepository).deleteUndispatchedTask(
                eq(18L), eq(1L), anyString(), eq(VerificationTaskPo.TaskStatus.PENDING));
    }

    @Test
    void submitVerification_cleanupUnconfirmed_preservesTaskIdInOutcomeUnknownError() {
        VerificationServiceImpl rejectingService = serviceWithVerificationExecutor(new RejectingThreadPoolTaskExecutor());
        VerificationTaskPo savedTask = VerificationTaskPo.builder()
                .id(19L).userId(1L).status(VerificationTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();
        when(taskRepository.save(any(VerificationTaskPo.class))).thenReturn(savedTask);
        when(taskRepository.deleteUndispatchedTask(
                eq(19L), eq(1L), anyString(), eq(VerificationTaskPo.TaskStatus.PENDING)))
                .thenReturn(0);
        when(taskRepository.findByIdAndUserId(19L, 1L)).thenReturn(Optional.of(savedTask));

        AsyncTaskDispatchOutcomeUnknownException error = assertThrows(
                AsyncTaskDispatchOutcomeUnknownException.class,
                () -> rejectingService.submitVerification(
                        1L, makeRequest(singleDevice(), List.of(),
                                List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertEquals(19L, error.getTaskId());
        assertTrue(error.getMessage().contains("cleanup"));
    }

    @Test
    void submitVerification_usesSubmissionTimeRequestSnapshot() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        VerificationServiceImpl capturingService = serviceWithVerificationExecutor(capturingExecutor);
        File smv = createTempModelFile(modelRunTempDir);
        SpecificationDto spec = makeEffectiveSpec("s1");
        spec.getAConditions().get(0).setKey("state");
        List<DeviceVerificationDto> devices = new ArrayList<>(singleDevice());
        RuleDto rule = makeRule();
        List<RuleDto> rules = new ArrayList<>(List.of(rule));
        List<SpecificationDto> specs = new ArrayList<>(List.of(spec));
        VerificationRequestDto request = makeRequest(devices, rules, specs, false, 0, false);
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
        doReturn(generateResult(smv, List.of(spec))).when(smvGenerator).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", true, null))));
        when(taskRepository.startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(14L).userId(1L).status(VerificationTaskPo.TaskStatus.RUNNING)
                .createdAt(LocalDateTime.now()).build();
        when(taskRepository.findById(14L)).thenReturn(Optional.of(task));

        assignVerificationTaskId(14L);
        capturingService.submitVerification(1L, request);
        assertNotNull(capturingExecutor.capturedTask());

        devices.get(0).setVarName("mutateddevice");
        rule.getConditions().get(0).setDeviceName("mutatedRuleDevice");
        spec.setId("mutatedSpec");
        spec.getAConditions().get(0).setValue("off");
        devices.clear();
        rules.clear();
        specs.clear();
        request.setSpecs(List.of(makeEffectiveSpec("mutated")));

        capturingExecutor.capturedTask().run();

        verify(smvGenerator).generateWithResolvedDeviceModel(eq(1L),
                argThat(sentDevices -> sentDevices.size() == 1
                        && "testdevice".equals(sentDevices.get(0).getVarName())),
                eq(List.of()),
                argThat(sentRules -> sentRules.size() == 1
                        && "testdevice".equals(sentRules.get(0).getConditions().get(0).getDeviceName())),
                argThat(sentSpecs -> sentSpecs.size() == 1
                        && "s1".equals(sentSpecs.get(0).getId())
                        && "on".equals(sentSpecs.get(0).getAConditions().get(0).getValue())),
                eq(AttackScenarioDto.none()), eq(false), eq(SmvGenerator.GeneratePurpose.VERIFICATION),
                argThat(ctx -> "task".equals(ctx.scope()) && Objects.equals(14L, ctx.id())),
                same(capturedModel.deviceSmvMap()));
        JsonNode requestJson = readRequestJson(smv);
        assertEquals(0, requestJson.path("attackBudget").asInt());
        assertEquals("testdevice", requestJson.path("devices").get(0).path("varName").asText());
        assertEquals("testdevice", requestJson.path("rules").get(0).path("conditions").get(0).path("deviceName").asText());
        assertEquals("s1", requestJson.path("specs").get(0).path("id").asText());
        assertEquals("on", requestJson.path("specs").get(0).path("aConditions").get(0).path("value").asText());
    }

    @Test
    void verificationStartRejectsLeaseThatExpiredWhileWaitingForItsRowLock() throws Exception {
        LocalDateTime beforeLock = LocalDateTime.of(2026, 9, 30, 10, 0);
        LocalDateTime afterLock = beforeLock.plusSeconds(2);
        LocalDateTime expiry = beforeLock.plusSeconds(1);
        AtomicReference<LocalDateTime> databaseTime = new AtomicReference<>(beforeLock);
        AtomicInteger locks = new AtomicInteger();
        when(taskRepository.currentDatabaseTime()).thenAnswer(invocation -> databaseTime.get());
        when(taskRepository.findByIdForUpdate(201L)).thenAnswer(invocation -> {
            if (locks.incrementAndGet() == 2) databaseTime.set(afterLock);
            return Optional.of(VerificationTaskPo.builder().id(201L).build());
        });
        when(taskRepository.startTaskIfStillPending(
                eq(201L), any(), any(), anyInt(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<LocalDateTime>getArgument(7).isBefore(expiry) ? 1 : 0);

        assignVerificationTaskId(201L);
        service.submitVerification(1L, makeRequest(
                singleDevice(), List.of(), List.of(makeEffectiveSpec("lease-spec")), false, 0, false));

        verify(taskRepository).startTaskIfStillPending(
                eq(201L), any(), eq(afterLock), anyInt(), anyString(), any(), anyString(),
                eq(afterLock), eq(afterLock.plusMinutes(2)));
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
    }

    @Test
    void verificationProgressRejectsLeaseThatExpiredWhileWaitingForItsRowLock() {
        LocalDateTime beforeLock = LocalDateTime.of(2026, 9, 30, 10, 0);
        LocalDateTime afterLock = beforeLock.plusSeconds(2);
        AtomicReference<LocalDateTime> databaseTime = new AtomicReference<>(beforeLock);
        when(taskRepository.currentDatabaseTime()).thenAnswer(invocation -> databaseTime.get());
        when(taskRepository.findByIdForUpdate(202L)).thenAnswer(invocation -> {
            databaseTime.set(afterLock);
            return Optional.of(VerificationTaskPo.builder().id(202L).build());
        });
        when(taskRepository.updateProgressIfActive(eq(202L), eq(50), any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.<LocalDateTime>getArgument(4)
                        .isBefore(beforeLock.plusSeconds(1)) ? 1 : 0);

        assertEquals(0, service.atomicUpdateProgress(202L, 50, TaskProgressStage.EXECUTING_MODEL_CHECKER));
        InOrder clocks = inOrder(taskRepository);
        clocks.verify(taskRepository).findByIdForUpdate(202L);
        clocks.verify(taskRepository).currentDatabaseTime();
        clocks.verify(taskRepository).updateProgressIfActive(
                eq(202L), eq(50), eq(TaskProgressStage.EXECUTING_MODEL_CHECKER), anyString(), eq(afterLock));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TaskProgressStage.class,
            names = {"GENERATING_MODEL", "EXECUTING_MODEL_CHECKER", "PARSING_RESULTS"})
    void submitVerification_stopsAtUnconfirmedProgress(TaskProgressStage stage) throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        VerificationServiceImpl capturingService = serviceWithVerificationExecutor(capturingExecutor);
        prepareRunningTask(203L);
        when(taskRepository.updateProgressIfActive(eq(203L), anyInt(), eq(stage), anyString(), any()))
                .thenReturn(0);
        if (stage != TaskProgressStage.GENERATING_MODEL) {
            stubResolvedGeneration(smvGenerator).thenReturn(
                    generateResult(createTempModelFile(modelRunTempDir), List.of(makeEffectiveSpec("s1"))));
        }
        if (stage == TaskProgressStage.PARSING_RESULTS) {
            when(nusmvExecutor.execute(any(File.class))).thenReturn(
                    NusmvResult.success("output", List.of(new SpecCheckResult("expr", false, "trace"))));
        }

        assertEquals(203L, capturingService.submitVerification(1L,
                makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));
        Runnable execution = capturingExecutor.capturedTask();
        Thread worker = new Thread(execution, "verification-lease-loss-test");
        worker.start();
        worker.join(2000);

        assertFalse(worker.isAlive());
        assertTrue(assertInstanceOf(Future.class, execution).isCancelled());
        if (stage == TaskProgressStage.GENERATING_MODEL) {
            verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                    anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
        }
        verify(nusmvExecutor, times(stage == TaskProgressStage.PARSING_RESULTS ? 1 : 0)).execute(any(File.class));
        verify(smvTraceParser, never()).parseCounterexampleStates(anyString(), anyMap(), anyList());
        verifyNoInteractions(traceRepository);
        verify(taskRepository, never()).completeTaskIfRunning(anyLong(), any(), any(), any(), anyInt(),
                anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyString(), any());
        verify(taskRepository, never()).failTaskIfActive(anyLong(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any());
    }

    @Test
    void queuedVerificationCannotStartAfterItsLeaseIsLost() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        VerificationServiceImpl capturingService = serviceWithVerificationExecutor(capturingExecutor);
        VerificationRequestDto request = makeRequest(
                singleDevice(), List.of(makeRule()), List.of(makeEffectiveSpec("lease-spec")),
                false, 0, false);

        assignVerificationTaskId(141L);
        capturingService.submitVerification(1L, request);
        assertNotNull(capturingExecutor.capturedTask());

        capturingService.maintainTaskLeases();
        capturingExecutor.capturedTask().run();

        verify(taskRepository).findByIdForUpdate(141L);
        verify(taskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
    }

    @Test
    void queuedVerificationStopsAfterDatabaseCannotConfirmItsLeaseForFullTtl() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        VerificationServiceImpl capturingService = serviceWithVerificationExecutor(capturingExecutor);
        VerificationRequestDto request = makeRequest(
                singleDevice(), List.of(makeRule()), List.of(makeEffectiveSpec("lease-spec")),
                false, 0, false);
        assignVerificationTaskId(142L);
        capturingService.submitVerification(1L, request);
        LeaseConfirmationTestSupport.ageExecutionLease(
                capturingService, "localExecutions", 142L, Duration.ofMinutes(3));
        when(taskRepository.currentDatabaseTime()).thenThrow(new RuntimeException("database unavailable"));

        capturingService.maintainTaskLeases();

        assertTrue(((Future<?>) capturingExecutor.capturedTask()).isCancelled());
        verify(taskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void queuedVerificationToleratesTransientLeaseDatabaseFailure() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        VerificationServiceImpl capturingService = serviceWithVerificationExecutor(capturingExecutor);
        assignVerificationTaskId(143L);
        capturingService.submitVerification(
                1L, makeRequest(singleDevice(), List.of(makeRule()),
                        List.of(makeEffectiveSpec("lease-spec")), false, 0, false));
        when(taskRepository.currentDatabaseTime()).thenThrow(new RuntimeException("database unavailable"));

        capturingService.maintainTaskLeases();

        assertFalse(((Future<?>) capturingExecutor.capturedTask()).isCancelled());
    }

    @Test
    void submitVerification_success_writesResultJson() throws Exception {
        File smv = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(makeEffectiveSpec("s1"))));
        AtomicBoolean requestWrittenBeforeRun = new AtomicBoolean();
        AtomicBoolean resultWrittenBeforeRun = new AtomicBoolean();
        when(nusmvExecutor.execute(any(File.class)))
                .thenAnswer(invocation -> {
                    requestWrittenBeforeRun.set(new File(smv.getParentFile(), "request.json").isFile());
                    resultWrittenBeforeRun.set(new File(smv.getParentFile(), "result.json").exists());
                    return NusmvResult.success("output", List.of(new SpecCheckResult("expr", true, null)));
                });

        prepareRunningTask(7L);
        assertEquals(7L, service.submitVerification(
                1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertAll(
                () -> assertTrue(requestWrittenBeforeRun.get(), "request.json must be available when NuSMV starts"),
                () -> assertFalse(resultWrittenBeforeRun.get(), "result.json must wait until the run settles"));
        assertEquals(200, readResultCode(smv));
        JsonNode request = readRequestJson(smv);
        assertFalse(request.path("attack").asBoolean());
        assertEquals(0, request.path("attackBudget").asInt());
        assertEquals(1, request.path("specs").size());
        assertTrue(smv.isFile());
    }

    @Test
    void submitVerification_cancelledDuringGeneration_doesNotLaunchNuSmv() throws Exception {
        prepareRunningTask(71L);
        File smv = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenAnswer(invocation -> {
            assertTrue(service.requestLocalExecutionStop(71L));
            Thread.interrupted();
            return generateResult(smv, List.of(makeEffectiveSpec("s1")));
        });

        service.submitVerification(1L, makeRequest(
                singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        verify(nusmvExecutor, never()).execute(any(File.class));
        verify(taskRepository, never()).completeTaskIfRunning(anyLong(), any(), any(), any(), anyInt(),
                anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyString(), any());
    }

    @Test
    void submitVerification_cancelledDuringExecution_doesNotParseOrCommitResult() throws Exception {
        prepareRunningTask(72L);
        File smv = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(makeEffectiveSpec("s1"))));
        when(nusmvExecutor.execute(any(File.class))).thenAnswer(invocation -> {
            assertTrue(service.requestLocalExecutionStop(72L));
            Thread.interrupted();
            return NusmvResult.success("output", List.of(new SpecCheckResult("expr", false, "trace")));
        });

        service.submitVerification(1L, makeRequest(
                singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        verify(smvTraceParser, never()).parseCounterexampleStates(anyString(), anyMap(), anyList());
        verify(taskRepository, never()).completeTaskIfRunning(anyLong(), any(), any(), any(), anyInt(),
                anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyString(), any());
    }

    @Test
    void submitVerification_executorFailure_marksFailedInsteadOfCompletingInconclusiveRun() throws Exception {
        prepareRunningTask(73L);
        File smv = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(makeEffectiveSpec("s1"))));
        when(nusmvExecutor.execute(any(File.class))).thenReturn(NusmvResult.error("invalid model"));

        service.submitVerification(1L, makeRequest(
                singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        verify(taskRepository).failTaskIfActive(eq(73L), eq(VerificationTaskPo.TaskStatus.FAILED), any(),
                eq(VerificationOutcome.INCONCLUSIVE), eq("NuSMV execution failed: invalid model"),
                anyString(), any(), anyList(), anyString(), any());
        verify(taskRepository, never()).completeTaskIfRunning(anyLong(), any(), any(), any(), anyInt(),
                anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyString(), any());
        assertEquals(500, readResultCode(smv));
    }

    @Test
    void submitVerification_unparsedSuccessfulOutput_completesWithInconclusiveVerdict() throws Exception {
        prepareRunningTask(74L);
        File smv = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(makeEffectiveSpec("s1"))));
        when(nusmvExecutor.execute(any(File.class))).thenReturn(NusmvResult.success("unparsed output", List.of()));
        when(taskRepository.completeTaskIfRunning(anyLong(), any(), any(), any(), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyString(), any())).thenReturn(1);

        service.submitVerification(1L, makeRequest(
                singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        verify(taskRepository).completeTaskIfRunning(eq(74L), eq(VerificationTaskPo.TaskStatus.COMPLETED), any(),
                eq(VerificationOutcome.INCONCLUSIVE), eq(0), eq(0), eq(0), any(), any(), any(),
                eq("unparsed output"), any(), any(), any(), eq(VerificationTaskPo.TaskStatus.RUNNING), anyString(), any());
        verify(taskRepository, never()).failTaskIfActive(anyLong(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any());
    }

    private void prepareRunningTask(Long taskId) {
        assignVerificationTaskId(taskId);
        when(taskRepository.startTaskIfStillPending(anyLong(), any(), any(LocalDateTime.class), anyInt(),
                anyString(), any(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(VerificationTaskPo.builder()
                .id(taskId).userId(1L).status(VerificationTaskPo.TaskStatus.RUNNING)
                .createdAt(LocalDateTime.now()).build()));
    }

    @Test
    void synchronousCounterexamplesRetainTheFrozenRunContextAndReplayScene() throws Exception {
        File smv = createTempModelFile(modelRunTempDir);
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto first = makeEffectiveSpec("first-spec");
        SpecificationDto second = makeEffectiveSpec("second-spec");
        var request = makeRequest(singleDevice(), List.of(), List.of(first, second), false, 0, false);
        var board = boardDataConverter.getModelInputSnapshot(1L);
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(first, second)));
        when(nusmvExecutor.execute(any(File.class))).thenReturn(NusmvResult.success("output", List.of(
                new SpecCheckResult("first", false, "first trace"),
                new SpecCheckResult("second", false, "second trace"))));
        when(smvTraceParser.parseCounterexampleStates(anyString(), anyMap(), anyList()))
                .thenReturn(List.of(TraceStateDto.builder().stateIndex(1).devices(List.of()).build()));

        VerificationResultDto result = service.verify(1L, request);

        assertEquals(VerificationOutcome.VIOLATED, result.getOutcome());
        assertEquals(2, result.getTraces().size());
        assertNotNull(result.getModelSemantics());
        assertNotNull(result.getModelSnapshot());
        for (TraceDto trace : result.getTraces()) {
            assertEquals(result.getModelSemantics(), trace.getModelSemantics());
            assertEquals(result.getModelSnapshot(), trace.getModelSnapshot());
            assertEquals(board.nodes().get(0).getId(), trace.getPlaybackScene().nodes().get(0).getId());
            assertEquals(board.nodes().get(0).getLabel(), trace.getPlaybackScene().nodes().get(0).getLabel());
        }
        var saved = ArgumentCaptor.forClass(VerificationTaskPo.class);
        verify(taskRepository).save(saved.capture());
        assertEquals(JsonUtils.toJson(result.getModelSemantics()), saved.getValue().getModelSemanticsJson());
        assertEquals(200, readResultCode(smv));
    }

    @Test
    void privacySpecificationEnablesRequiredPropagationEvenWhenCallerOmitsFlag() throws Exception {
        File smv = createTempModelFile(modelRunTempDir);
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("privacy-spec");
        spec.getAConditions().get(0).setTargetType(" Privacy ");
        spec.getAConditions().get(0).setPropertyScope("variable");
        spec.getAConditions().get(0).setKey("status");
        spec.getAConditions().get(0).setValue("private");

        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(spec)));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", true, null))));

        VerificationResultDto result = service.verify(
                1L, makeRequest(singleDevice(), List.of(), List.of(spec), false, 0, false));

        assertTrue(result.getIsAttack() == Boolean.FALSE);
        assertEquals(0, result.getAttackBudget());
        assertTrue(result.isEnablePrivacy());
        verify(smvGenerator).generateWithResolvedDeviceModel(eq(1L), anyList(), anyList(), anyList(), anyList(),
                eq(AttackScenarioDto.none()), eq(true), eq(SmvGenerator.GeneratePurpose.VERIFICATION), any(), anyMap());
        assertTrue(readRequestJson(smv).path("enablePrivacy").asBoolean());
    }

    @Test
    void synchronousVerificationPersistsOneCompletedRun() throws Exception {
        File smv = createTempModelFile(modelRunTempDir);
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("history-spec");
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(spec)));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", true, null))));

        VerificationResultDto result = service.verify(
                1L, makeRequest(singleDevice(), List.of(), List.of(spec), false, 0, false));

        ArgumentCaptor<VerificationTaskPo> captor = ArgumentCaptor.forClass(VerificationTaskPo.class);
        verify(taskRepository).save(captor.capture());
        VerificationTaskPo saved = captor.getValue();
        assertEquals(VerificationTaskPo.TaskStatus.COMPLETED, saved.getStatus());
        assertEquals(VerificationOutcome.SATISFIED, saved.getOutcome());
        assertEquals(0, saved.getViolatedSpecCount());
        assertEquals(100, saved.getProgress());
        assertNotNull(saved.getCompletedAt());
        assertNotNull(saved.getRequestJson());
        assertNotNull(saved.getTemplateSnapshotsJson());
        assertEquals("history-spec", JsonUtils.fromJson(saved.getRequestJson(),
                VerificationRequestDto.class).getSpecs().get(0).getId());
        assertEquals(1, JsonUtils.fromJson(saved.getTemplateSnapshotsJson(),
                cn.edu.nju.Iot_Verify.dto.model.TemplateSnapshotBundleDto.class).getSchemaVersion());
        assertEquals(VerificationOutcome.SATISFIED, result.getOutcome());
        assertEquals(cn.edu.nju.Iot_Verify.dto.model.RunPersistenceDto.Status.SAVED,
                result.getHistoryPersistence().getStatus());
        assertEquals(1000L, result.getHistoryPersistence().getRunId());
        verify(formalOperationAdmission).execute(eq(1L), any());
        verify(formalOperationAdmission).registerCurrentLeaseCommitFence();
    }

    @Test
    void synchronousVerification_historySaveFailure_keepsFormalResultAndReportsUnknownOutcome() throws Exception {
        File smv = createTempModelFile(modelRunTempDir);
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("history-save-failure-spec");
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(spec)));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", true, null))));
        when(taskRepository.save(any(VerificationTaskPo.class)))
                .thenThrow(new RuntimeException("database unavailable"));

        VerificationResultDto result = service.verify(
                1L, makeRequest(singleDevice(), List.of(), List.of(spec), false, 0, false));

        assertEquals(VerificationOutcome.SATISFIED, result.getOutcome());
        assertEquals(cn.edu.nju.Iot_Verify.dto.model.RunPersistenceDto.Status.OUTCOME_UNKNOWN,
                result.getHistoryPersistence().getStatus());
        assertEquals("RUN_HISTORY_SAVE_OUTCOME_UNKNOWN",
                result.getHistoryPersistence().getReasonCode());
        assertNull(result.getHistoryPersistence().getRunId());
        assertTrue(result.getCheckLogs().stream()
                .anyMatch(log -> log.contains("[history-save-unknown]")));
    }

    @Test
    void synchronousVerification_commitFailureClearsTracePersistenceIdentity() throws Exception {
        AtomicBoolean failTraceTransactionCommit = new AtomicBoolean();
        service = serviceWithTransactionTemplate(commitFailingTransactionTemplate(failTraceTransactionCommit));
        File smv = createTempModelFile(modelRunTempDir);
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("history-commit-failure-spec");
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(spec)));
        when(nusmvExecutor.execute(any(File.class))).thenReturn(NusmvResult.success("output", List.of(
                new SpecCheckResult("expr", false, "counterexample"))));
        when(smvTraceParser.parseCounterexampleStates(eq("counterexample"), anyMap(), anyList()))
                .thenReturn(List.of(TraceStateDto.builder().stateIndex(1).devices(List.of()).build()));
        when(specificationMapper.toJson(spec)).thenReturn("{\"id\":\"history-commit-failure-spec\"}");
        TracePo savedTrace = TracePo.builder()
                .id(2000L)
                .userId(1L)
                .verificationTaskId(1000L)
                .violatedSpecId(spec.getId())
                .statesJson("[]")
                .createdAt(LocalDateTime.now())
                .build();
        when(traceMapper.toEntity(any(TraceDto.class))).thenReturn(savedTrace);
        when(traceRepository.save(savedTrace)).thenAnswer(invocation -> {
            failTraceTransactionCommit.set(true);
            return savedTrace;
        });

        VerificationResultDto result = service.verify(
                1L, makeRequest(singleDevice(), List.of(), List.of(spec), false, 0, false));

        assertEquals(VerificationOutcome.VIOLATED, result.getOutcome());
        assertEquals(cn.edu.nju.Iot_Verify.dto.model.RunPersistenceDto.Status.OUTCOME_UNKNOWN,
                result.getHistoryPersistence().getStatus());
        assertEquals(1, result.getTraces().size());
        TraceDto trace = result.getTraces().get(0);
        assertNull(trace.getId());
        assertNull(trace.getVerificationTaskId());
        assertNull(trace.getUserId());
        verify(traceRepository).save(savedTrace);
    }

    @Test
    void synchronousVerification_ownershipFenceFailureIsNotReportedAsAFormalResult() throws Exception {
        File smv = createTempModelFile(modelRunTempDir);
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("ownership-fence-spec");
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(spec)));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", true, null))));
        ServiceUnavailableException ownershipLost =
                new ServiceUnavailableException("formal operation ownership changed");
        doThrow(ownershipLost).when(formalOperationAdmission).registerCurrentLeaseCommitFence();

        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> service.verify(
                        1L, makeRequest(singleDevice(), List.of(), List.of(spec), false, 0, false)));

        assertSame(ownershipLost, error);
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
    }

    @Test
    void taskInboxQueryExcludesCompletedRuns() {
        VerificationTaskSummaryProjection row = mock(VerificationTaskSummaryProjection.class);
        List<VerificationTaskSummaryProjection> rows = List.of(row);
        when(taskRepository.findSummaryByUserIdAndStatusNotOrderByCreatedAtDesc(
                1L, VerificationTaskPo.TaskStatus.COMPLETED)).thenReturn(rows);
        when(verificationTaskMapper.toSummaryProjectionDtoList(rows)).thenReturn(List.of());

        service.getTasks(1L, List.of());

        verify(taskRepository).findSummaryByUserIdAndStatusNotOrderByCreatedAtDesc(
                1L, VerificationTaskPo.TaskStatus.COMPLETED);
    }

    @Test
    void runHistory_keepsCorruptRunAsUnavailablePlaceholder() {
        VerificationRunSummaryProjection good = mock(VerificationRunSummaryProjection.class);
        VerificationRunSummaryProjection corrupt = mock(VerificationRunSummaryProjection.class);
        when(good.getId()).thenReturn(21L);
        when(corrupt.getId()).thenReturn(22L);
        when(corrupt.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(taskRepository.findCompletedRunSummaries(
                eq(1L), eq(VerificationTaskPo.TaskStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of(good, corrupt));
        when(traceRepository.findSummariesByUserIdAndVerificationTaskIdIn(
                1L, List.of(21L, 22L))).thenReturn(List.of());
        when(verificationTaskMapper.toRunSummaryDto(good, 0))
                .thenReturn(VerificationRunSummaryDto.builder().id(21L).build());
        when(verificationTaskMapper.toRunSummaryDto(corrupt, 0))
                .thenThrow(new PersistedDataIntegrityException(
                        "verification run", 22L, "specResultsJson", "malformed JSON"));

        List<VerificationRunSummaryDto> runs = service.getRuns(1L);

        assertEquals(2, runs.size());
        assertTrue(runs.get(0).getDataAvailable());
        assertFalse(runs.get(1).getDataAvailable());
        assertEquals(22L, runs.get(1).getId());
        assertEquals("PERSISTED_SEMANTIC_DATA_INVALID", runs.get(1).getUnavailableReasonCode());
    }

    @Test
    void runHistory_doesNotMislabelProgrammingErrorsAsPersistedDataDamage() {
        VerificationRunSummaryProjection run = mock(VerificationRunSummaryProjection.class);
        when(run.getId()).thenReturn(23L);
        when(taskRepository.findCompletedRunSummaries(
                eq(1L), eq(VerificationTaskPo.TaskStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of(run));
        when(traceRepository.findSummariesByUserIdAndVerificationTaskIdIn(
                1L, List.of(23L))).thenReturn(List.of());
        when(verificationTaskMapper.toRunSummaryDto(run, 0))
                .thenThrow(new IllegalStateException("mapper bug"));

        assertThrows(IllegalStateException.class, () -> service.getRuns(1L));
    }

    @Test
    void runHistory_keepsCorruptTracePlaceholderWithoutCountingItAsReplayable() {
        VerificationRunSummaryProjection run = mock(VerificationRunSummaryProjection.class);
        TraceSummaryProjection corruptTrace = mock(TraceSummaryProjection.class);
        when(run.getId()).thenReturn(31L);
        when(corruptTrace.getId()).thenReturn(41L);
        when(corruptTrace.getVerificationTaskId()).thenReturn(31L);
        when(taskRepository.findCompletedRunSummaries(
                eq(1L), eq(VerificationTaskPo.TaskStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of(run));
        when(traceRepository.findSummariesByUserIdAndVerificationTaskIdIn(
                1L, List.of(31L))).thenReturn(List.of(corruptTrace));
        when(traceMapper.toSummaryDto(corruptTrace, corruptTrace)).thenThrow(new PersistedDataIntegrityException(
                "verification trace", 41L, "violatedSpecJson", "malformed JSON"));
        when(verificationTaskMapper.toRunSummaryDto(run, 0)).thenReturn(
                VerificationRunSummaryDto.builder().id(31L).counterexampleCount(0).build());

        VerificationRunSummaryDto result = service.getRuns(1L).get(0);

        assertEquals(0, result.getCounterexampleCount());
        assertEquals(1, result.getCounterexamples().size());
        assertFalse(result.getCounterexamples().get(0).getDataAvailable());
        verify(verificationTaskMapper).toRunSummaryDto(run, 0);
    }

    @Test
    void runDetailUsesTheSameReplayableCounterexampleCountAsHistory() {
        var run = mock(cn.edu.nju.Iot_Verify.repository.projection.VerificationResultProjection.class);
        TraceSummaryProjection availableTrace = mock(TraceSummaryProjection.class);
        TraceSummaryProjection unknownTrace = mock(TraceSummaryProjection.class);
        TraceSummaryProjection corruptTrace = mock(TraceSummaryProjection.class);
        TraceSummaryDto availableSummary = TraceSummaryDto.builder()
                .id(51L)
                .createdAt(LocalDateTime.of(2026, 10, 18, 12, 0))
                .verificationTaskId(32L)
                .dataAvailable(true)
                .build();
        TraceSummaryDto unknownSummary = TraceSummaryDto.builder()
                .id(53L)
                .createdAt(LocalDateTime.of(2026, 10, 17, 12, 0))
                .verificationTaskId(32L)
                .dataAvailable(null)
                .build();
        when(taskRepository.findCompletedResultView(32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED,
                VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS))
                .thenReturn(Optional.of(run));
        when(traceRepository.findSummariesByUserIdAndVerificationTaskIdIn(
                1L, List.of(32L))).thenReturn(List.of(availableTrace, unknownTrace, corruptTrace));
        when(traceMapper.toSummaryDto(availableTrace, availableTrace)).thenReturn(availableSummary);
        when(traceMapper.toSummaryDto(unknownTrace, unknownTrace)).thenReturn(unknownSummary);
        when(traceMapper.toSummaryDto(corruptTrace, corruptTrace)).thenThrow(new PersistedDataIntegrityException(
                "verification trace", 52L, "stateCount", "trace has no states"));
        VerificationRunDto expected = VerificationRunDto.builder().id(32L).counterexampleCount(1).build();
        when(verificationTaskMapper.toRunDto(run, 1)).thenReturn(expected);

        VerificationRunDto result = service.getRun(1L, 32L);

        assertSame(expected, result);
        assertEquals(3, result.getCounterexamples().size());
        assertSame(availableSummary, result.getCounterexamples().get(0));
        assertFalse(result.getCounterexamples().get(2).getDataAvailable());
        verify(verificationTaskMapper).toRunDto(run, 1);
        verify(taskRepository, never()).findByIdAndUserId(anyLong(), anyLong());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void historyAndDetailSortLightweightSummariesByTimestampThenId(boolean history) {
        var rows = new ArrayList<TraceSummaryProjection>();
        LocalDateTime now = LocalDateTime.of(2026, 10, 18, 12, 0);
        for (long id : List.of(51L, 53L, 52L)) {
            TraceSummaryProjection row = mock(TraceSummaryProjection.class);
            if (history) when(row.getVerificationTaskId()).thenReturn(32L);
            when(traceMapper.toSummaryDto(row, row)).thenReturn(TraceSummaryDto.builder()
                    .id(id).verificationTaskId(32L).createdAt(id == 51L ? now.minusDays(1) : now)
                    .dataAvailable(true).build());
            rows.add(row);
        }
        when(traceRepository.findSummariesByUserIdAndVerificationTaskIdIn(1L, List.of(32L)))
                .thenReturn(rows);
        List<TraceSummaryDto> result;
        if (history) {
            var run = mock(VerificationRunSummaryProjection.class);
            when(run.getId()).thenReturn(32L);
            when(taskRepository.findCompletedRunSummaries(eq(1L), eq(VerificationTaskPo.TaskStatus.COMPLETED), any(Pageable.class)))
                    .thenReturn(List.of(run));
            when(verificationTaskMapper.toRunSummaryDto(run, 3)).thenReturn(VerificationRunSummaryDto.builder().id(32L).build());
            result = service.getRuns(1L).get(0).getCounterexamples();
        } else {
            var run = mock(cn.edu.nju.Iot_Verify.repository.projection.VerificationResultProjection.class);
            when(taskRepository.findCompletedResultView(32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED,
                    VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS)).thenReturn(Optional.of(run));
            when(verificationTaskMapper.toRunDto(run, 3)).thenReturn(VerificationRunDto.builder().id(32L).build());
            result = service.getRun(1L, 32L).getCounterexamples();
        }
        assertEquals(List.of(53L, 52L, 51L), result.stream().map(TraceSummaryDto::getId).toList());
    }

    @Test
    void taskDetailUsesOnlyThePresentationRead() {
        var view = mock(cn.edu.nju.Iot_Verify.repository.projection.VerificationResultProjection.class);
        var expected = cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskDto.builder().id(32L).build();
        when(taskRepository.findResultViewByIdAndUserId(
                32L, 1L, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS)).thenReturn(Optional.of(view));
        when(verificationTaskMapper.toDto(view)).thenReturn(expected);

        assertSame(expected, service.getTask(1L, 32L));

        verify(taskRepository, never()).findByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void missingPresentationReadRetainsTypedNotFoundForBothEntrypoints() {
        assertThrows(cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException.class,
                () -> service.getTask(1L, 32L));
        assertThrows(cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException.class,
                () -> service.getRun(1L, 32L));
        verify(taskRepository, never()).findByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void deletionImpactCountsEveryPersistedTraceWithoutParsingEvidence() {
        CompletedRunDeletionProjection projection = mock(CompletedRunDeletionProjection.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 25, 12, 0);
        LocalDateTime completedAt = createdAt.plusSeconds(2);
        when(projection.getId()).thenReturn(32L);
        when(projection.getCreatedAt()).thenReturn(createdAt);
        when(projection.getCompletedAt()).thenReturn(completedAt);
        when(taskRepository.findDeletionProjection(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(projection));
        when(traceRepository.countByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(3L);

        RunDeletionImpactDto impact = service.getRunDeletionImpact(1L, 32L);

        assertEquals(32L, impact.getRunId());
        assertEquals(3L, impact.getEvidenceCount());
        assertEquals(createdAt, impact.getCreatedAt());
        assertEquals(completedAt, impact.getCompletedAt());
        verify(traceRepository, never()).findByUserIdAndVerificationTaskId(anyLong(), anyLong());
        verifyNoInteractions(traceMapper, verificationTaskMapper);
    }

    @Test
    void confirmedRunDeletionUsesOwnedLockAndExactPersistedTraceCount() {
        VerificationTaskPo run = VerificationTaskPo.builder()
                .id(32L)
                .userId(1L)
                .status(VerificationTaskPo.TaskStatus.COMPLETED)
                .specResultsJson("not valid json")
                .build();
        when(userRepository.findByIdForShare(1L)).thenReturn(Optional.of(new UserPo()));
        when(taskRepository.findCompletedRunForUpdate(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(run));
        when(traceRepository.countByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(3L);
        when(traceRepository.deleteByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(3);
        when(taskRepository.deleteCompletedRun(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED)).thenReturn(1);

        long deletedCount = service.deleteRun(1L, 32L, 3L);

        assertEquals(3L, deletedCount);
        InOrder deletion = inOrder(userRepository, taskRepository, traceRepository);
        deletion.verify(userRepository).findByIdForShare(1L);
        deletion.verify(taskRepository).findCompletedRunForUpdate(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED);
        deletion.verify(traceRepository).deleteByUserIdAndVerificationTaskId(1L, 32L);
        deletion.verify(taskRepository).deleteCompletedRun(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED);
        verifyNoInteractions(traceMapper, verificationTaskMapper);
    }

    @Test
    void confirmedRunDeletionRejectsEvidenceAddedAfterPreview() {
        VerificationTaskPo run = VerificationTaskPo.builder()
                .id(32L).userId(1L).status(VerificationTaskPo.TaskStatus.COMPLETED).build();
        when(userRepository.findByIdForShare(1L)).thenReturn(Optional.of(new UserPo()));
        when(taskRepository.findCompletedRunForUpdate(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(run));
        when(traceRepository.countByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(3L);

        ConflictException error = assertThrows(
                ConflictException.class, () -> service.deleteRun(1L, 32L, 2L));

        assertTrue(error.getMessage().contains("preview the deletion again"));
        verify(traceRepository, never()).deleteByUserIdAndVerificationTaskId(anyLong(), anyLong());
        verify(taskRepository, never()).deleteCompletedRun(anyLong(), anyLong(), any());
    }

    @Test
    void confirmedRunDeletionRejectsConcurrentEvidenceChangeDuringDelete() {
        VerificationTaskPo run = VerificationTaskPo.builder()
                .id(32L).userId(1L).status(VerificationTaskPo.TaskStatus.COMPLETED).build();
        when(userRepository.findByIdForShare(1L)).thenReturn(Optional.of(new UserPo()));
        when(taskRepository.findCompletedRunForUpdate(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(run));
        when(traceRepository.countByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(3L);
        when(traceRepository.deleteByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(4);

        assertThrows(ConflictException.class, () -> service.deleteRun(1L, 32L, 3L));

        verify(taskRepository, never()).deleteCompletedRun(anyLong(), anyLong(), any());
    }

    @Test
    void runDeletionReturnsNotFoundWithoutAccessingRunRowsWhenOwnerIsGone() {
        when(userRepository.findByIdForShare(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deleteRun(1L, 32L));

        verifyNoInteractions(taskRepository, traceRepository);
    }

    @Test
    void runHistory_usesConfiguredStoredTaskLimitAsQueryBound() {
        AsyncTaskAdmissionConfig admissionConfig = new AsyncTaskAdmissionConfig();
        admissionConfig.getVerification().setMaxStoredTasksPerUser(137);
        VerificationServiceImpl configuredService = serviceWithAdmissionConfig(admissionConfig);
        when(taskRepository.findCompletedRunSummaries(
                eq(1L), eq(VerificationTaskPo.TaskStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of());

        assertTrue(configuredService.getRuns(1L).isEmpty());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findCompletedRunSummaries(
                eq(1L), eq(VerificationTaskPo.TaskStatus.COMPLETED), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(137, pageable.getValue().getPageSize());
    }

    @Test
    void synchronousVerificationRejectsKnownFullHistoryBeforeStartingNusmv() throws Exception {
        when(taskRepository.countByUserId(1L)).thenReturn(100L);

        AsyncTaskQuotaExceededException error = assertThrows(
                AsyncTaskQuotaExceededException.class,
                () -> service.verify(1L, makeRequest(
                        singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertEquals("VERIFICATION_STORED_TASK_LIMIT_REACHED", error.getReasonCode());
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
        verify(smvGenerator, never()).generateWithEnvironment(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any());
    }

    @Test
    void submitVerification_failedSpecWithoutTrace_countsViolatedSpecFromSpecResults() throws Exception {
        File smv = createTempModelFile(modelRunTempDir);
        stubResolvedGeneration(smvGenerator).thenReturn(generateResult(smv, List.of(makeEffectiveSpec("s1"))));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", false, null))));

        prepareRunningTask(9L);
        service.submitVerification(
                1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        verify(taskRepository).completeTaskIfRunning(
                eq(9L), eq(VerificationTaskPo.TaskStatus.COMPLETED), any(),
                eq(VerificationOutcome.VIOLATED), eq(1), eq(0), eq(0),
                // specResultsJson, checkLogsJson, generationIssuesJson, nusmvOutput, smvModelContent,
                // errorMessage, processingTimeMs
                any(), any(), any(), any(), any(), any(), any(), any(),
                anyString(), any(LocalDateTime.class));
    }

    @Test
    void submitVerification_cancelledBeforeRun_skipsGeneration() {
        verificationTaskExecutor.beforeExecution(() -> assertTrue(service.requestLocalExecutionStop(8L)));
        assignVerificationTaskId(8L);

        service.submitVerification(
                1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        verify(taskRepository, never()).startTaskIfStillPending(anyLong(), any(), any(), anyInt(),
                anyString(), any(), anyString(), any(), any());
    }

    @Test
    void submitVerification_noLongerPending_skipsGeneration() throws Exception {
        assignVerificationTaskId(11L);
        // startTaskIfStillPending returns 0 by default (Mockito int stub),
        // simulating a DB-level race where the task was cancelled or started by another process
        // after the in-memory check passed.
        service.submitVerification(
                1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        // Verify the atomic start was attempted.
        verify(taskRepository).startTaskIfStillPending(
                eq(11L), eq(VerificationTaskPo.TaskStatus.RUNNING),
                any(LocalDateTime.class), eq(0), anyString(),
                eq(VerificationTaskPo.TaskStatus.PENDING), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap());
    }

    @Test
    void verify_timeout_returnsTimedOutAndPurgesQueuedTask() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(50L);
        try (var blocker = blockWorker(syncVerificationExecutor)) {
            VerificationResultDto result = service.verify(
                    1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

            assertEquals(VerificationOutcome.INCONCLUSIVE, result.getOutcome());
            assertTrue(result.getCheckLogs().stream().anyMatch(log -> log.contains("timed out")));
            ThreadPoolExecutor nativeExecutor = syncVerificationExecutor.getThreadPoolExecutor();
            assertNotNull(nativeExecutor);
            assertEquals(0, nativeExecutor.getQueue().size());
        }
    }

    @Test
    void verify_interruptedWaitCancelsNestedFutureAndDoesNotPersistRun() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(5000L);
        try (var blocker = blockWorker(syncVerificationExecutor)) {
            Throwable failure = interruptQueuedRun(syncVerificationExecutor, "interrupted-verification-caller",
                    () -> service.verify(1L, makeRequest(singleDevice(), List.of(),
                            List.of(makeEffectiveSpec("s1")), false, 0, false)));

            assertInstanceOf(ServiceUnavailableException.class, failure);
            assertEquals(0, syncVerificationExecutor.getThreadPoolExecutor().getQueue().size());
            verify(taskRepository, never()).save(any(VerificationTaskPo.class));
        }
    }

    private SpecificationDto makeEffectiveSpec(String id) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId(id);
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setTemplateLabel("Always");
        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("testdevice");
        cond.setTargetType("state");
        cond.setKey("status");
        cond.setRelation("=");
        cond.setValue("on");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());
        return spec;
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

    // ==================== terminal-state progress tests ====================

    @Test
    void maintainTaskLeases_locksExpiryCandidateBeforeConditionalFailure() {
        var activeStatuses = List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING);
        when(taskRepository.findExpiredActiveTaskIds(eq(activeStatuses), any(LocalDateTime.class)))
                .thenReturn(List.of(60L));
        when(taskRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(new VerificationTaskPo()));

        service.maintainTaskLeases();

        InOrder recovery = inOrder(taskRepository);
        recovery.verify(taskRepository).findExpiredActiveTaskIds(eq(activeStatuses), any(LocalDateTime.class));
        recovery.verify(taskRepository).findByIdForUpdate(60L);
        recovery.verify(taskRepository).failExpiredTask(eq(60L), eq(VerificationTaskPo.TaskStatus.FAILED),
                any(LocalDateTime.class), eq(VerificationOutcome.INCONCLUSIVE), anyString(), anyString(),
                eq(activeStatuses), any(LocalDateTime.class));
    }

    @Test
    void cancelTask_pendingTask_usesAtomicCancelUpdate() {
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(60L).userId(1L).status(VerificationTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();

        when(taskRepository.findByIdAndUserIdForUpdate(60L, 1L))
                .thenReturn(Optional.of(task));
        when(taskRepository.cancelTaskIfStillActive(
                eq(60L), eq(VerificationTaskPo.TaskStatus.CANCELLED), any(LocalDateTime.class),
                eq(VerificationOutcome.INCONCLUSIVE), anyList()))
                .thenReturn(1);

        var result = service.cancelTask(1L, 60L);

        assertTrue(result.isCancellationAccepted());
        assertEquals("CANCELLED", result.getTaskStatus());
        verify(chatExecutionLeaseGuard).requireCurrentExecutionLeaseAndLock();
        verify(taskRepository).cancelTaskIfStillActive(
                eq(60L), eq(VerificationTaskPo.TaskStatus.CANCELLED), any(LocalDateTime.class),
                eq(VerificationOutcome.INCONCLUSIVE), anyList());
        assertFalse(wasTaskSaveCalled());
    }

    @Test
    void completeTask_skipsWhenAlreadyCancelledInDb() throws Exception {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("sensor");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("active");
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(70L).userId(1L).status(VerificationTaskPo.TaskStatus.RUNNING)
                .startedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build();

        // Atomic UPDATE returns 0 — task was already cancelled in DB
        when(taskRepository.completeTaskIfRunning(
                eq(70L), any(), any(), any(VerificationOutcome.class), anyInt(),
                // …specResultsJson through processingTimeMs, now including smvModelContent
                anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyString(), any(LocalDateTime.class)))
                .thenReturn(0);

        Method completeTask = VerificationServiceImpl.class.getDeclaredMethod(
                "completeTask", VerificationTaskPo.class, VerificationOutcome.class, int.class,
                List.class, List.class, String.class, List.class, int.class, int.class,
                String.class);
        completeTask.setAccessible(true);
        completeTask.invoke(service, task, VerificationOutcome.SATISFIED, 0,
                List.of(SpecResultDto.builder()
                        .specId("s1")
                        .templateId("1")
                        .specificationLabel("Always")
                        .specificationSnapshot(new SpecificationSnapshotDto("s1", "Always", "1",
                                List.of(condition),
                                List.of(), List.of(), Map.of("sensor", new SpecificationSnapshotDto.Device(
                                        "Hall sensor", ModelTokenSource.BUNDLED, 1))))
                        .formulaPreview("CTL AG(\"Hall sensor\".state = \"active\")")
                        .formulaKind("CTL")
                        .outcome(VerificationOutcome.SATISFIED)
                        .antecedentReachability(cn.edu.nju.Iot_Verify.dto.verification.AntecedentReachability.NOT_APPLICABLE)
                        .expression("expr")
                        .build()),
                List.of("done"), "", List.of(), 1, 2, "MODULE main");

        // Atomic UPDATE was called (returns 0 = no rows affected = already cancelled)
        verify(taskRepository).completeTaskIfRunning(
                eq(70L), any(), any(), eq(VerificationOutcome.SATISFIED), anyInt(),
                eq(1), eq(2), eq("[{\"specId\":\"s1\",\"templateId\":\"1\","
                        + "\"specificationLabel\":\"Always\","
                        + "\"formulaPreview\":\"CTL AG(\\\"Hall sensor\\\".state = \\\"active\\\")\","
                        + "\"specificationSnapshot\":{\"id\":\"s1\",\"name\":\"Always\",\"templateId\":\"1\","
                        + "\"aConditions\":[{\"id\":null,\"side\":null,\"deviceId\":\"sensor\",\"deviceLabel\":null,"
                        + "\"targetType\":\"state\",\"key\":\"state\",\"propertyScope\":null,\"variableSource\":null,"
                        + "\"relation\":\"=\",\"value\":\"active\"}],"
                        + "\"ifConditions\":[],\"thenConditions\":[],\"devices\":{\"sensor\":{"
                        + "\"label\":\"Hall sensor\",\"modelTokenSource\":\"BUNDLED\",\"modeCount\":1}}},"
                        + "\"formulaKind\":\"CTL\",\"outcome\":\"SATISFIED\","
                        + "\"antecedentReachability\":\"NOT_APPLICABLE\",\"expression\":\"expr\"}]"),
                // checkLogsJson, generationIssuesJson, nusmvOutput, then the model, then errorMessage,
                // processingTimeMs, runningStatus. The model slot sits between nusmvOutput and
                // errorMessage, so it is inserted here rather than appended.
                any(), eq("[]"), any(), eq("MODULE main"), any(), any(), any(),
                anyString(), any(LocalDateTime.class));
        // save() should NOT be called — atomic UPDATE replaces it
        assertFalse(wasTaskSaveCalled());
    }

    @Test
    void completeTaskAndSaveTraces_cancelledInDb_rollsBackTraceTransaction() throws Exception {
        VerificationServiceImpl txService = serviceWithTransactionTemplate(inlineTransactionTemplate());
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(72L).userId(1L).status(VerificationTaskPo.TaskStatus.RUNNING)
                .startedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build();
        TraceDto trace = TraceDto.builder()
                .violatedSpecId("spec_1")
                .states(List.of())
                .build();
        TracePo po = TracePo.builder()
                .id(100L)
                .userId(1L)
                .verificationTaskId(72L)
                .violatedSpecId("spec_1")
                .statesJson("[]")
                .createdAt(LocalDateTime.now())
                .build();
        when(traceMapper.toEntity(any(TraceDto.class))).thenReturn(po);
        when(taskRepository.completeTaskIfRunning(
                eq(72L), eq(VerificationTaskPo.TaskStatus.COMPLETED), any(LocalDateTime.class),
                eq(VerificationOutcome.VIOLATED), eq(1), eq(0), eq(0),
                // The `isNull()` pins errorMessage, so the model matcher goes BEFORE it: appending
                // instead would silently move that assertion onto the model slot.
                any(), any(), any(), any(), any(), isNull(), any(),
                eq(VerificationTaskPo.TaskStatus.RUNNING),
                anyString(), any(LocalDateTime.class))).thenReturn(0);

        Method method = VerificationServiceImpl.class.getDeclaredMethod(
                "completeTaskAndSaveTraces",
                VerificationTaskPo.class, List.class, Long.class, Long.class,
                VerificationOutcome.class, int.class, List.class, List.class, String.class, List.class,
                int.class, int.class, String.class);
        method.setAccessible(true);
        Boolean completed = (Boolean) method.invoke(txService, task, List.of(trace), 1L, 72L,
                VerificationOutcome.VIOLATED, 1, List.of(), new ArrayList<>(List.of("done")), "",
                List.<ModelGenerationIssueDto>of(), 0, 0, "MODULE main");

        assertFalse(completed);
        assertTrue(lastTransactionStatus.isRollbackOnly());
        verify(traceRepository).save(any(TracePo.class));
    }

    @Test
    void failTask_skipsWhenAlreadyCancelledInDb() throws Exception {
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(71L).userId(1L).status(VerificationTaskPo.TaskStatus.RUNNING)
                .startedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build();

        // Atomic UPDATE returns 0 — task was already cancelled in DB
        when(taskRepository.failTaskIfActive(
                eq(71L), any(), any(), any(), any(), any(), any(), any(),
                anyString(), any(LocalDateTime.class)))
                .thenReturn(0);

        Method failTask = VerificationServiceImpl.class.getDeclaredMethod(
                "failTask", VerificationTaskPo.class, String.class);
        failTask.setAccessible(true);
        failTask.invoke(service, task, "some error");

        // Atomic UPDATE was called (returns 0 = no rows affected = already cancelled)
        verify(taskRepository).failTaskIfActive(
                eq(71L), any(), any(), any(), any(), any(), any(), any(),
                anyString(), any(LocalDateTime.class));
        // save() should NOT be called — atomic UPDATE replaces it
        assertFalse(wasTaskSaveCalled());
    }

    private boolean wasTaskSaveCalled() {
        return mockingDetails(taskRepository).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals("save"));
    }

    private void assertSpecResult(SpecResultDto result, String specId, VerificationOutcome outcome, String expression) {
        assertEquals(specId, result.getSpecId());
        assertEquals(outcome, result.getOutcome());
        assertEquals(expression, result.getExpression());
    }

    private VerificationRequestDto makeRequest(List<DeviceVerificationDto> devices, List<RuleDto> rules,
                                                List<SpecificationDto> specs, boolean isAttack,
                                                int attackBudget, boolean enablePrivacy) {
        VerificationRequestDto r = new VerificationRequestDto();
        // The service reads the scene from the board, not from the request, so the scene a test
        // wants verified has to be the board it presents. The request fields are still populated
        // because they are the frozen snapshot the service overwrites and then persists.
        r.setDevices(devices);
        r.setPlaybackNodes(playbackNodes(devices));
        r.setRules(rules);
        r.setSpecs(specs);
        stubBoardSnapshot(boardDataConverter, devices, rules, specs);
        r.setAttackScenario(AttackScenarioDto.builder()
                .mode(isAttack ? AttackScenarioDto.Mode.ANY_UP_TO_BUDGET : AttackScenarioDto.Mode.NONE)
                .budget(attackBudget)
                .points(List.of())
                .build());
        r.setEnablePrivacy(enablePrivacy);
        return r;
    }

}
