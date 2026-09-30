package cn.edu.nju.Iot_Verify.service.impl;

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
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.SpecResultDto;
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
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.TraceRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.repository.projection.CompletedRunDeletionProjection;
import cn.edu.nju.Iot_Verify.repository.projection.TraceSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationRunSummaryProjection;
import cn.edu.nju.Iot_Verify.service.ChatExecutionLeaseGuard;
import cn.edu.nju.Iot_Verify.service.FormalOperationAdmission;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import cn.edu.nju.Iot_Verify.util.mapper.TraceMapper;
import cn.edu.nju.Iot_Verify.util.mapper.VerificationTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import org.mockito.junit.jupiter.MockitoExtension;


import java.lang.reflect.Method;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for VerificationServiceImpl.buildVerificationResult fail-closed logic.
 * Uses reflection to invoke the private method directly.
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceImplBuildResultTest {

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

    private VerificationServiceImpl service;
    private ThreadPoolTaskExecutor verificationTaskExecutor;
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
        lenient().when(smvGenerator.captureDeviceModel(anyLong(), anyList()))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(
                        Map.of("test-template", templateSnapshot), Map.of()));
        lenient().when(smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(Map.of());
        lenient().when(smvGenerator.generateWithResolvedDeviceModel(
                        anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap()))
                .thenAnswer(invocation -> smvGenerator.generateWithEnvironment(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5),
                        invocation.getArgument(6), invocation.getArgument(7), invocation.getArgument(8)));

        buildVerificationResult = VerificationServiceImpl.class.getDeclaredMethod(
                "buildVerificationResult",
                NusmvResult.class, List.class, List.class, List.class,
                Long.class, Long.class, List.class, Map.class, Map.class, String.class,
                List.class, List.class, int.class, int.class, String.class);
        buildVerificationResult.setAccessible(true);
    }

    @Test
    void fullTraceCollectionOrdersLoadedPayloadsWithoutDatabaseSortingJson() {
        LocalDateTime olderTime = LocalDateTime.of(2026, 7, 30, 10, 0);
        TracePo oldest = TracePo.builder().id(11L).createdAt(olderTime).build();
        TracePo sameTimeNewerId = TracePo.builder().id(12L).createdAt(olderTime).build();
        TracePo newest = TracePo.builder().id(13L).createdAt(olderTime.plusSeconds(1)).build();
        List<TraceDto> expected = List.of(TraceDto.builder().id(13L).build());
        when(traceRepository.findByUserId(7L))
                .thenReturn(List.of(oldest, newest, sameTimeNewerId));
        when(traceMapper.toDtoList(anyList())).thenReturn(expected);

        assertSame(expected, service.getUserTraces(7L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TracePo>> traces = ArgumentCaptor.forClass(List.class);
        verify(traceMapper).toDtoList(traces.capture());
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
                service, result, devices, List.of(), specs, 1L, null, checkLogs, Map.of(), Map.of(), null,
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
                service, result, devices, List.of(), specs, 1L, null, checkLogs, Map.of(), Map.of(), null,
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

    private File createTempModelFile() throws Exception {
        Path dir = Files.createTempDirectory("verify-service-test-");
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

    // --- effectiveSpecs = 0: all specs filtered out -> inconclusive ---

    @Test
    void effectiveSpecsEmpty_returnsInconclusiveBecauseNothingWasVerified() throws Exception {
        // Spec with no A/IF conditions -> filtered out
        SpecificationDto emptySpec = new SpecificationDto();
        emptySpec.setId("s1");
        emptySpec.setAConditions(List.of());
        emptySpec.setIfConditions(List.of());

        NusmvResult result = NusmvResult.success("", List.of());
        List<String> logs = new ArrayList<>();

        VerificationResultDto dto = invoke(result, List.of(), List.of(emptySpec), logs);

        assertEquals(VerificationOutcome.INCONCLUSIVE, dto.getOutcome());
        assertFalse(dto.isModelComplete());
        assertTrue(dto.getSpecResults().isEmpty());
        assertTrue(logs.stream().anyMatch(l -> l.contains("No valid specifications")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("no specifications were emitted")));
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
        assertEquals("Always", dto.getSpecResults().get(0).getSpecificationLabel());
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
    void verifyWithTemplateSnapshot_neverReloadsMutableTemplateRepository() {
        Map<String, DeviceManifest> supplied = Map.of(
                "light", DeviceManifest.builder().name("light").build());
        when(smvGenerator.captureDeviceModelFromTemplateSnapshots(anyList(), same(supplied)))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(supplied, Map.of()));
        syncVerificationExecutor.shutdown();

        assertThrows(ServiceUnavailableException.class, () -> service.verifyWithTemplateSnapshot(
                1L,
                makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false),
                supplied));

        verify(smvGenerator).captureDeviceModelFromTemplateSnapshots(anyList(), same(supplied));
        verify(smvGenerator, never()).captureDeviceModel(anyLong(), anyList());
    }

    @Test
    void verify_nusmvBusy_throwsServiceUnavailable() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        File smv = createTempModelFile();
        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any()))
                .thenReturn(generateResult(smv, List.of(makeEffectiveSpec("s1"))));
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
        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any()))
                .thenThrow(SmvGenerationException.ambiguousDeviceReference("Sensor", List.of("sensor_1", "sensor_2")));

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> service.verify(
                        1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));
        assertEquals("AMBIGUOUS_DEVICE_REFERENCE", ex.getErrorCategory());
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
    void createTask_activeUserLimit_rejectsBeforePersisting() {
        when(taskRepository.countByUserId(1L)).thenReturn(1L);
        when(taskRepository.countByUserIdAndStatusIn(eq(1L), anyList())).thenReturn(2L);

        AsyncTaskQuotaExceededException error = assertThrows(
                AsyncTaskQuotaExceededException.class,
                () -> service.createTask(1L, AttackScenarioDto.none(), false, 0, 0, 0, null));

        assertEquals("VERIFICATION_ACTIVE_TASK_LIMIT_REACHED", error.getReasonCode());
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
    }

    @Test
    void verifyAsync_emptySpecs_rejectsBeforeQueueingTask() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.verifyAsync(
                        1L, 7L, makeRequest(singleDevice(), List.of(), List.of(), false, 0, false)));

        assertTrue(ex.getMessage().contains("Add at least one specification to the board before verifying"));
        verify(taskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void verifyAsync_nullTaskId_rejectsBeforeQueueingTask() throws Exception {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.verifyAsync(
                        1L, null, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertTrue(ex.getMessage().contains("taskId"));
        verify(taskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
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
    void verifyAsync_invalidIntensity_marksExistingTaskFailedBeforeQueueing() throws Exception {
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(16L).userId(1L).status(VerificationTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();
        when(taskRepository.findById(16L)).thenReturn(Optional.of(task));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.verifyAsync(
                        1L, 16L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 51, false)));

        assertTrue(ex.getMessage().contains("Attack budget must be omitted or 0 when no attack scenario is selected"));
        verify(taskRepository).failTaskIfActive(
                eq(16L), eq(VerificationTaskPo.TaskStatus.FAILED), any(LocalDateTime.class),
                eq(VerificationOutcome.INCONCLUSIVE),
                eq("attackScenario.budget: Attack budget must be omitted or 0 when no attack scenario is selected"), anyString(), any(),
                eq(List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING)),
                anyString(), any(LocalDateTime.class));
        verify(taskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
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
    void verifyAsync_queueRejected_marksExistingTaskFailedAndThrowsServiceUnavailable() {
        VerificationServiceImpl rejectingService = serviceWithVerificationExecutor(new RejectingThreadPoolTaskExecutor());
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(13L).userId(1L).status(VerificationTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();

        when(taskRepository.findById(13L)).thenReturn(Optional.of(task));

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> rejectingService.verifyAsync(
                        1L, 13L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false)));

        assertTrue(ex.getMessage().contains("busy"));
        verify(taskRepository).failTaskIfActive(
                eq(13L), eq(VerificationTaskPo.TaskStatus.FAILED), any(LocalDateTime.class),
                eq(VerificationOutcome.INCONCLUSIVE),
                eq("Server busy, please try again later"), anyString(), any(),
                eq(List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING)),
                anyString(), any(LocalDateTime.class));
        verify(taskRepository, never()).startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void verifyAsync_usesSubmissionTimeRequestSnapshot() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        VerificationServiceImpl capturingService = serviceWithVerificationExecutor(capturingExecutor);
        File smv = createTempModelFile();
        SpecificationDto spec = makeEffectiveSpec("s1");
        List<DeviceVerificationDto> devices = new ArrayList<>(singleDevice());
        RuleDto rule = makeRule();
        List<RuleDto> rules = new ArrayList<>(List.of(rule));
        List<SpecificationDto> specs = new ArrayList<>(List.of(spec));
        VerificationRequestDto request = makeRequest(devices, rules, specs, false, 0, false);
        Map<String, DeviceSmvData> resolvedSubmissionModel = new LinkedHashMap<>();
        resolvedSubmissionModel.put("testdevice", new DeviceSmvData());

        when(smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(resolvedSubmissionModel);
        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any()))
                .thenReturn(generateResult(smv, List.of(spec)));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", true, null))));
        when(taskRepository.startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(14L).userId(1L).status(VerificationTaskPo.TaskStatus.RUNNING)
                .createdAt(LocalDateTime.now()).build();
        when(taskRepository.findById(14L)).thenReturn(Optional.of(task));

        capturingService.verifyAsync(1L, 14L, request);
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

        verify(smvGenerator).generateWithResolvedDeviceModel(eq(1L), anyList(), anyList(), anyList(), anyList(),
                eq(AttackScenarioDto.none()), eq(false), eq(SmvGenerator.GeneratePurpose.VERIFICATION),
                argThat(ctx -> "task".equals(ctx.scope()) && Objects.equals(14L, ctx.id())),
                same(resolvedSubmissionModel));

        verify(smvGenerator).generateWithEnvironment(eq(1L),
                argThat(sentDevices -> sentDevices.size() == 1
                        && "testdevice".equals(sentDevices.get(0).getVarName())),
                eq(List.of()),
                argThat(sentRules -> sentRules.size() == 1
                        && "testdevice".equals(sentRules.get(0).getConditions().get(0).getDeviceName())),
                argThat(sentSpecs -> sentSpecs.size() == 1
                        && "s1".equals(sentSpecs.get(0).getId())
                        && "on".equals(sentSpecs.get(0).getAConditions().get(0).getValue())),
                eq(AttackScenarioDto.none()), eq(false), eq(SmvGenerator.GeneratePurpose.VERIFICATION),
                argThat(ctx -> "task".equals(ctx.scope()) && Objects.equals(14L, ctx.id())));
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

        service.verifyAsync(1L, 201L, makeRequest(
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

    @Test
    void queuedVerificationCannotStartAfterItsLeaseIsLost() throws Exception {
        CapturingThreadPoolTaskExecutor capturingExecutor = new CapturingThreadPoolTaskExecutor();
        VerificationServiceImpl capturingService = serviceWithVerificationExecutor(capturingExecutor);
        VerificationRequestDto request = makeRequest(
                singleDevice(), List.of(makeRule()), List.of(makeEffectiveSpec("lease-spec")),
                false, 0, false);

        capturingService.verifyAsync(1L, 141L, request);
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
        capturingService.verifyAsync(1L, 142L, request);
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
        capturingService.verifyAsync(
                1L, 143L, makeRequest(singleDevice(), List.of(makeRule()),
                        List.of(makeEffectiveSpec("lease-spec")), false, 0, false));
        when(taskRepository.currentDatabaseTime()).thenThrow(new RuntimeException("database unavailable"));

        capturingService.maintainTaskLeases();

        assertFalse(((Future<?>) capturingExecutor.capturedTask()).isCancelled());
    }

    @Test
    void verifyAsync_success_writesResultJson() throws Exception {
        File smv = createTempModelFile();
        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any()))
                .thenReturn(generateResult(smv, List.of(makeEffectiveSpec("s1"))));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", true, null))));

        when(taskRepository.startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        // findById is still used by verifyAsync after startTaskIfStillPending to load the entity
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(7L).userId(1L).status(VerificationTaskPo.TaskStatus.RUNNING)
                .createdAt(LocalDateTime.now()).build();
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));

        service.verifyAsync(
                1L, 7L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        assertEquals(200, readResultCode(smv));
        JsonNode request = readRequestJson(smv);
        assertFalse(request.path("attack").asBoolean());
        assertEquals(0, request.path("attackBudget").asInt());
        assertEquals(1, request.path("specs").size());
    }

    @Test
    void privacySpecificationEnablesRequiredPropagationEvenWhenCallerOmitsFlag() throws Exception {
        File smv = createTempModelFile();
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("privacy-spec");
        spec.getAConditions().get(0).setTargetType(" Privacy ");
        spec.getAConditions().get(0).setPropertyScope("variable");
        spec.getAConditions().get(0).setKey("status");
        spec.getAConditions().get(0).setValue("private");

        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(), any()))
                .thenReturn(generateResult(smv, List.of(spec)));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", true, null))));

        VerificationResultDto result = service.verify(
                1L, makeRequest(singleDevice(), List.of(), List.of(spec), false, 0, false));

        assertTrue(result.getIsAttack() == Boolean.FALSE);
        assertEquals(0, result.getAttackBudget());
        assertTrue(result.isEnablePrivacy());
        verify(smvGenerator).generateWithEnvironment(eq(1L), anyList(), anyList(), anyList(), anyList(),
                eq(AttackScenarioDto.none()), eq(true), eq(SmvGenerator.GeneratePurpose.VERIFICATION), any());
        assertTrue(readRequestJson(smv).path("enablePrivacy").asBoolean());
    }

    @Test
    void synchronousVerificationPersistsOneCompletedRun() throws Exception {
        File smv = createTempModelFile();
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("history-spec");
        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(), any()))
                .thenReturn(generateResult(smv, List.of(spec)));
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
        assertEquals(VerificationOutcome.SATISFIED, result.getOutcome());
        assertEquals(cn.edu.nju.Iot_Verify.dto.model.RunPersistenceDto.Status.SAVED,
                result.getHistoryPersistence().getStatus());
        assertEquals(1000L, result.getHistoryPersistence().getRunId());
        verify(formalOperationAdmission).execute(eq(1L), any());
        verify(formalOperationAdmission).registerCurrentLeaseCommitFence();
    }

    @Test
    void synchronousVerification_historySaveFailure_keepsFormalResultAndReportsUnknownOutcome() throws Exception {
        File smv = createTempModelFile();
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("history-save-failure-spec");
        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(), any()))
                .thenReturn(generateResult(smv, List.of(spec)));
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
        File smv = createTempModelFile();
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("history-commit-failure-spec");
        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(), any()))
                .thenReturn(generateResult(smv, List.of(spec)));
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
        File smv = createTempModelFile();
        when(nusmvConfig.getTimeoutMs()).thenReturn(1000L);
        SpecificationDto spec = makeEffectiveSpec("ownership-fence-spec");
        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(), any()))
                .thenReturn(generateResult(smv, List.of(spec)));
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
        when(traceMapper.toSummaryDto(corruptTrace)).thenThrow(new PersistedDataIntegrityException(
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
        VerificationTaskPo run = VerificationTaskPo.builder()
                .id(32L)
                .userId(1L)
                .status(VerificationTaskPo.TaskStatus.COMPLETED)
                .checkLogsJson("[]")
                .build();
        TraceSummaryProjection availableTrace = mock(TraceSummaryProjection.class);
        TraceSummaryProjection unknownTrace = mock(TraceSummaryProjection.class);
        TraceSummaryProjection corruptTrace = mock(TraceSummaryProjection.class);
        TraceSummaryDto availableSummary = TraceSummaryDto.builder()
                .id(51L)
                .verificationTaskId(32L)
                .dataAvailable(true)
                .build();
        TraceSummaryDto unknownSummary = TraceSummaryDto.builder()
                .id(53L)
                .verificationTaskId(32L)
                .dataAvailable(null)
                .build();
        when(taskRepository.findByIdAndUserId(32L, 1L)).thenReturn(Optional.of(run));
        when(traceRepository.findSummariesByUserIdAndVerificationTaskIdIn(
                1L, List.of(32L))).thenReturn(List.of(availableTrace, unknownTrace, corruptTrace));
        when(traceMapper.toSummaryDto(availableTrace)).thenReturn(availableSummary);
        when(traceMapper.toSummaryDto(unknownTrace)).thenReturn(unknownSummary);
        when(traceMapper.toSummaryDto(corruptTrace)).thenThrow(new PersistedDataIntegrityException(
                "verification trace", 52L, "stateCount", "trace has no states"));
        VerificationRunDto expected = VerificationRunDto.builder().id(32L).counterexampleCount(1).build();
        when(verificationTaskMapper.toRunDto(run, 1)).thenReturn(expected);

        VerificationRunDto result = service.getRun(1L, 32L);

        assertSame(expected, result);
        verify(verificationTaskMapper).toRunDto(run, 1);
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
        when(taskRepository.findCompletedRunForUpdate(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(run));
        when(traceRepository.countByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(3L);
        when(traceRepository.deleteByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(3);
        when(taskRepository.deleteCompletedRun(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED)).thenReturn(1);

        long deletedCount = service.deleteRun(1L, 32L, 3L);

        assertEquals(3L, deletedCount);
        verify(taskRepository).deleteCompletedRun(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED);
        verifyNoInteractions(traceMapper, verificationTaskMapper);
    }

    @Test
    void confirmedRunDeletionRejectsEvidenceAddedAfterPreview() {
        VerificationTaskPo run = VerificationTaskPo.builder()
                .id(32L).userId(1L).status(VerificationTaskPo.TaskStatus.COMPLETED).build();
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
        when(taskRepository.findCompletedRunForUpdate(
                32L, 1L, VerificationTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(run));
        when(traceRepository.countByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(3L);
        when(traceRepository.deleteByUserIdAndVerificationTaskId(1L, 32L)).thenReturn(4);

        assertThrows(ConflictException.class, () -> service.deleteRun(1L, 32L, 3L));

        verify(taskRepository, never()).deleteCompletedRun(anyLong(), anyLong(), any());
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
        verify(smvGenerator, never()).generateWithEnvironment(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any());
    }

    @Test
    void verifyAsync_failedSpecWithoutTrace_countsViolatedSpecFromSpecResults() throws Exception {
        File smv = createTempModelFile();
        when(smvGenerator.generateWithEnvironment(anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any()))
                .thenReturn(generateResult(smv, List.of(makeEffectiveSpec("s1"))));
        when(nusmvExecutor.execute(any(File.class)))
                .thenReturn(NusmvResult.success("output", List.of(new SpecCheckResult("expr", false, null))));

        when(taskRepository.startTaskIfStillPending(
                anyLong(), any(), any(LocalDateTime.class), anyInt(), anyString(), any(),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(9L).userId(1L).status(VerificationTaskPo.TaskStatus.RUNNING)
                .createdAt(LocalDateTime.now()).build();
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));

        service.verifyAsync(
                1L, 9L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        verify(taskRepository).completeTaskIfRunning(
                eq(9L), eq(VerificationTaskPo.TaskStatus.COMPLETED), any(),
                eq(VerificationOutcome.VIOLATED), eq(1), eq(0), eq(0),
                // specResultsJson, checkLogsJson, generationIssuesJson, nusmvOutput, smvModelContent,
                // errorMessage, processingTimeMs
                any(), any(), any(), any(), any(), any(), any(), any(),
                anyString(), any(LocalDateTime.class));
    }

    @Test
    void verifyAsync_cancelledBeforeRun_skipsGeneration() throws Exception {
        cancelledTaskIds().add(8L);

        service.verifyAsync(
                1L, 8L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void verifyAsync_noLongerPending_skipsGeneration() throws Exception {
        // startTaskIfStillPending returns 0 by default (Mockito int stub),
        // simulating a DB-level race where the task was cancelled or started by another process
        // after the in-memory check passed.
        service.verifyAsync(
                1L, 11L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        // Verify the atomic start was attempted.
        verify(taskRepository).startTaskIfStillPending(
                eq(11L), eq(VerificationTaskPo.TaskStatus.RUNNING),
                any(LocalDateTime.class), eq(0), anyString(),
                eq(VerificationTaskPo.TaskStatus.PENDING), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class));
        // Verify early return: findById for task loading should NOT be called after abort.
        // (updateTaskProgress may call findById(11L) before the atomic check, so we only
        // assert generate() was never reached.)
        verify(smvGenerator, never()).generate(anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void verify_timeout_returnsTimedOutAndPurgesQueuedTask() {
        when(nusmvConfig.getTimeoutMs()).thenReturn(50L);
        Future<?> blocker = syncVerificationExecutor.submit(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        VerificationResultDto result = service.verify(
                1L, makeRequest(singleDevice(), List.of(), List.of(makeEffectiveSpec("s1")), false, 0, false));

        assertEquals(VerificationOutcome.INCONCLUSIVE, result.getOutcome());
        assertTrue(result.getCheckLogs().stream().anyMatch(log -> log.contains("timed out")));

        ThreadPoolExecutor nativeExecutor = syncVerificationExecutor.getThreadPoolExecutor();
        assertNotNull(nativeExecutor);
        assertEquals(0, nativeExecutor.getQueue().size());
        blocker.cancel(true);
    }

    @Test
    void verify_interruptedWaitCancelsNestedFutureAndDoesNotPersistRun() throws Exception {
        when(nusmvConfig.getTimeoutMs()).thenReturn(5000L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        Future<?> blocker = syncVerificationExecutor.submit(() -> {
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
                service.verify(1L, makeRequest(singleDevice(), List.of(),
                        List.of(makeEffectiveSpec("s1")), false, 0, false));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "interrupted-verification-caller");

        caller.start();
        awaitQueueSize(syncVerificationExecutor, 1);
        caller.interrupt();
        caller.join(2000);

        assertFalse(caller.isAlive());
        assertInstanceOf(ServiceUnavailableException.class, failure.get());
        assertEquals(0, syncVerificationExecutor.getThreadPoolExecutor().getQueue().size());
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
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

    private SpecificationDto makeEffectiveSpec(String id) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId(id);
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
    void maintainTaskLeases_recoversExpiredTasksWithoutScanningAndSavingAllActiveRows() {
        when(taskRepository.failExpiredActiveTasks(
                eq(VerificationTaskPo.TaskStatus.FAILED),
                any(LocalDateTime.class),
                eq(VerificationOutcome.INCONCLUSIVE),
                anyString(),
                anyString(),
                eq(List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING)),
                any(LocalDateTime.class)))
                .thenReturn(2);

        service.maintainTaskLeases();

        verify(taskRepository).failExpiredActiveTasks(
                eq(VerificationTaskPo.TaskStatus.FAILED),
                any(LocalDateTime.class),
                eq(VerificationOutcome.INCONCLUSIVE),
                anyString(),
                anyString(),
                eq(List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING)),
                any(LocalDateTime.class));
        verify(taskRepository, never()).save(any(VerificationTaskPo.class));
    }

    @Test
    void cancelTask_pendingTask_usesAtomicCancelUpdate() {
        VerificationTaskPo task = VerificationTaskPo.builder()
                .id(60L).userId(1L).status(VerificationTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now()).build();

        when(taskRepository.findByIdAndUserId(60L, 1L))
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
                        .formulaPreview("CTL AG(\"Hall sensor\".state = \"active\")")
                        .formulaKind("CTL")
                        .outcome(VerificationOutcome.SATISFIED)
                        .expression("expr")
                        .build()),
                List.of("done"), "", List.of(), 1, 2, "MODULE main");

        // Atomic UPDATE was called (returns 0 = no rows affected = already cancelled)
        verify(taskRepository).completeTaskIfRunning(
                eq(70L), any(), any(), eq(VerificationOutcome.SATISFIED), anyInt(),
                eq(1), eq(2), eq("[{\"specId\":\"s1\",\"templateId\":\"1\","
                        + "\"specificationLabel\":\"Always\","
                        + "\"formulaPreview\":\"CTL AG(\\\"Hall sensor\\\".state = \\\"active\\\")\","
                        + "\"formulaKind\":\"CTL\",\"outcome\":\"SATISFIED\","
                        + "\"expression\":\"expr\"}]"),
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
        // Answer, not a fixed value: the real board read happens per call and returns its own
        // immutable lists, so a test that mutates its inputs afterwards must not see the change.
        lenient().when(boardDataConverter.getModelInputSnapshot(anyLong())).thenAnswer(invocation ->
                new BoardDataConverter.ModelInputSnapshot(
                        playbackNodes(devices) == null ? List.of() : List.copyOf(playbackNodes(devices)),
                        devices == null ? List.of() : List.copyOf(devices),
                        List.of(),
                        rules == null ? List.of() : List.copyOf(rules),
                        specs == null ? List.of() : List.copyOf(specs),
                        Map.of()));
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
}
