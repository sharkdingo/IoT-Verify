package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngine;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineInput;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineOutcome;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineResult;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzFinding;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzInputEvent;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzInputEventKind;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzInputEventSource;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzLimitationContract;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzModelInputSnapshotCodec;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzModelFingerprint;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzPaperDomainPreviewer;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzProgressListener;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzSpecEligibility;
import cn.edu.nju.Iot_Verify.configure.FuzzAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.ThreadPoolConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingReplayDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzInputEventDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzPaperDomainPreviewDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzPaperDomainPreviewRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzWorkloadPreviewDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzWorkloadPreviewRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.FuzzTaskQuotaExceededException;
import cn.edu.nju.Iot_Verify.exception.FuzzTaskStorageQuotaExceededException;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.po.FuzzFindingPo;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.FuzzFindingRepository;
import cn.edu.nju.Iot_Verify.repository.FuzzTaskRepository;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskProgressProjection;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.projection.CompletedRunDeletionProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzFindingSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import cn.edu.nju.Iot_Verify.util.mapper.FuzzMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FuzzServiceImplTest {

    @Mock private FuzzTaskRepository taskRepository;
    @Mock private FuzzFindingRepository findingRepository;
    @Mock private UserRepository userRepository;
    @Mock private BoardDataConverter boardDataConverter;
    @Mock private FuzzEngine fuzzEngine;
    @Mock private FuzzPaperDomainPreviewer fuzzPaperDomainPreviewer;
    @Mock private FuzzMapper fuzzMapper;
    @Mock private ThreadPoolTaskExecutor fuzzTaskExecutor;
    @Mock private TransactionTemplate transactionTemplate;

    private FuzzServiceImpl service;
    private FuzzAdmissionConfig admissionConfig;
    private ThreadPoolConfig threadPoolConfig;

    @BeforeEach
    void setUp() {
        admissionConfig = new FuzzAdmissionConfig();
        threadPoolConfig = new ThreadPoolConfig();
        rebuildService();
        org.mockito.Mockito.lenient().when(taskRepository.findStatusById(anyLong()))
                .thenReturn(Optional.of(FuzzTaskPo.TaskStatus.PENDING));
        org.mockito.Mockito.lenient().when(taskRepository.currentDatabaseTime())
                .thenAnswer(invocation -> LocalDateTime.now());
        org.mockito.Mockito.lenient().when(taskRepository.findByIdForUpdate(anyLong())).thenAnswer(invocation -> Optional.of(
                FuzzTaskPo.builder().id(invocation.getArgument(0)).build()));
    }

    private void rebuildService() {
        service = new FuzzServiceImpl(
                taskRepository,
                findingRepository,
                userRepository,
                boardDataConverter,
                fuzzEngine,
                fuzzPaperDomainPreviewer,
                new FuzzModelFingerprint(new ObjectMapper().findAndRegisterModules()),
                fuzzMapper,
                fuzzTaskExecutor,
                admissionConfig,
                threadPoolConfig,
                transactionTemplate,
                new ObjectMapper());
    }

    @Test
    void fullRunOrdersLoadedFindingsWithoutDatabaseSortingLongTextPayloads() {
        LocalDateTime firstTime = LocalDateTime.of(2026, 7, 30, 10, 0);
        FuzzTaskPo run = FuzzTaskPo.builder()
                .id(41L)
                .userId(7L)
                .status(FuzzTaskPo.TaskStatus.COMPLETED)
                .build();
        FuzzFindingPo first = FuzzFindingPo.builder().id(11L).createdAt(firstTime).build();
        FuzzFindingPo second = FuzzFindingPo.builder().id(12L).createdAt(firstTime).build();
        FuzzFindingPo last = FuzzFindingPo.builder().id(13L).createdAt(firstTime.plusSeconds(1)).build();
        FuzzRunDto expected = FuzzRunDto.builder().id(41L).findings(List.of()).build();
        when(taskRepository.findByIdAndUserIdAndStatus(
                41L, 7L, FuzzTaskPo.TaskStatus.COMPLETED)).thenReturn(Optional.of(run));
        when(findingRepository.findByUserIdAndFuzzTaskId(7L, 41L))
                .thenReturn(List.of(last, second, first));
        when(fuzzMapper.toRunDto(eq(run), anyList())).thenReturn(expected);

        assertSame(expected, service.getRun(7L, 41L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FuzzFindingPo>> findings = ArgumentCaptor.forClass(List.class);
        verify(fuzzMapper).toRunDto(eq(run), findings.capture());
        assertEquals(List.of(11L, 12L, 13L),
                findings.getValue().stream().map(FuzzFindingPo::getId).toList());
    }

    @Test
    void previewPaperDomainCapturesOneBoardSnapshotWithoutCreatingTaskState() {
        ModelInputSnapshot snapshot = snapshot(List.of());
        FuzzPaperDomainPreviewRequestDto request =
                FuzzPaperDomainPreviewRequestDto.builder().pathLength(12).build();
        FuzzPaperDomainPreviewDto preview = FuzzPaperDomainPreviewDto.builder()
                .pathLength(12)
                .initializationPolicy("RANDOM_LEGAL_PER_SEED")
                .build();
        String fingerprint = service.paperDomainFingerprint(snapshot, 12);
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(fuzzPaperDomainPreviewer.preview(snapshot, 12, fingerprint)).thenReturn(preview);

        FuzzPaperDomainPreviewDto result = service.previewPaperDomain(7L, request);

        assertSame(preview, result);
        verify(boardDataConverter).getModelInputSnapshot(7L);
        verify(fuzzPaperDomainPreviewer).preview(snapshot, 12, fingerprint);
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        verifyNoInteractions(taskRepository, findingRepository, transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void currentModelFingerprintCapturesOneBoardSnapshotWithoutCreatingTaskState() {
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);

        String fingerprint = service.getCurrentModelFingerprint(7L);

        assertEquals(service.modelFingerprint(snapshot), fingerprint);
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        verify(boardDataConverter).getModelInputSnapshot(7L);
        verifyNoInteractions(taskRepository, findingRepository, transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void previewPaperDomainRejectsInvalidLengthBeforeReadingBoard() {
        FuzzPaperDomainPreviewRequestDto request =
                FuzzPaperDomainPreviewRequestDto.builder().pathLength(51).build();

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.previewPaperDomain(7L, request));

        assertTrue(error.getErrors().containsKey("pathLength"));
        verifyNoInteractions(boardDataConverter, fuzzPaperDomainPreviewer, taskRepository);
    }

    @Test
    void previewPaperDomainReturnsInvalidBoardAsValidationError() {
        ModelInputSnapshot snapshot = snapshot(List.of());
        FuzzPaperDomainPreviewRequestDto request = new FuzzPaperDomainPreviewRequestDto();
        String fingerprint = service.paperDomainFingerprint(snapshot, 20);
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(fuzzPaperDomainPreviewer.preview(snapshot, 20, fingerprint))
                .thenThrow(new IllegalArgumentException("the event domain must expose a target"));

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.previewPaperDomain(7L, request));

        assertTrue(error.getErrors().get("board").contains("Random-state input range"));
        verifyNoInteractions(taskRepository, findingRepository, transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void previewPaperDomainRejectsSnapshotsThatCannotBePersisted() {
        SpecificationDto specification = specification("spec-1");
        specification.setTemplateLabel("x".repeat(8 * 1024 * 1024));
        when(boardDataConverter.getModelInputSnapshot(7L))
                .thenReturn(snapshot(List.of(specification)));

        ValidationException error = assertThrows(
                ValidationException.class,
                () -> service.previewPaperDomain(7L, new FuzzPaperDomainPreviewRequestDto()));

        assertTrue(error.getErrors().get("board").contains("8 MiB"));
        verifyNoInteractions(fuzzPaperDomainPreviewer, taskRepository, findingRepository,
                transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    // The invariant that broke silently: the shipped default budget must be admissible on an ordinary
    // household Board. It was not — every scene in docs/examples refused the product's own defaults, and the
    // AI tool's identical default failed on all six, because the complexity ceiling equalled the raw budget
    // ceiling and left the multiplier no headroom. The scenes measure 62-85 units in BOARD_SNAPSHOT and
    // 90-151 in PAPER_COMPATIBLE, so 200 is pinned against the worst of those with the margin stated.
    void deadlineSignalFiresOnlyAfterItsBudgetElapses() {
        // Elapsed-difference comparison rather than an absolute `origin + budget` deadline, which would
        // overflow for a large origin and then read as "never expired". The difference form is not immune
        // either — it underflows for an origin within one budget of Long.MAX_VALUE — but that origin is not
        // reachable here: it comes from System.nanoTime() moments earlier in the same worker.
        assertFalse(service.deadlineSignal(System.nanoTime(), Duration.ofMinutes(5)).getAsBoolean(),
                "a freshly started search must not already be out of time");
        assertTrue(service.deadlineSignal(
                System.nanoTime() - Duration.ofMinutes(6).toNanos(), Duration.ofMinutes(5)).getAsBoolean(),
                "a search started six minutes ago must be past a five-minute budget");
        // Independent of the wall clock, which is the property that matters: a system-clock adjustment during
        // a search must not shorten or extend it. Both readings come from the same monotonic source.
        assertFalse(service.deadlineSignal(System.nanoTime(), Duration.ofDays(1L)).getAsBoolean());
    }

    @Test
    void theDefaultBudgetIsAdmissibleAcrossTheShippedSceneComplexityRange() {
        FuzzRequestDto defaults = FuzzRequestDto.builder().build();
        long defaultProduct = (long) defaults.getMaxIterations()
                * defaults.getPathLength() * defaults.getPopulationSize();

        assertEquals(40_000L, defaultProduct,
                "the default budget product changed; re-check it against the scene complexity range");
        for (long complexity : new long[]{62L, 85L, 90L, 151L}) {
            assertTrue(defaultProduct * complexity <= 30_000_000L,
                    "the default budget is inadmissible at complexity " + complexity
                            + ", which a shipped example scene actually measures");
        }
        // Headroom, so an ordinary Board a little larger than the examples still runs at the defaults.
        assertTrue(defaultProduct * 151L * 4 <= 30_000_000L,
                "the default budget leaves less than 4x headroom over the most complex shipped scene");
        // And the preview default must describe the same request submission would build.
        assertEquals(defaults.getMaxIterations(),
                FuzzWorkloadPreviewRequestDto.builder().build().getMaxIterations());
    }

    @Test
    void previewWorkloadUsesTheSameFrozenModelComplexityAsSubmission() {
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        FuzzWorkloadPreviewRequestDto request = FuzzWorkloadPreviewRequestDto.builder()
                .maxIterations(100)
                .pathLength(5)
                .populationSize(2)
                .build();

        FuzzWorkloadPreviewDto result = service.previewWorkload(7L, request);

        assertEquals(1L, result.getModelComplexityUnits());
        assertEquals(1_000L, result.getEstimatedWorkload());
        assertEquals(30_000_000L, result.getWorkloadLimit());
        assertTrue(result.isAccepted());
        assertEquals(FuzzExplorationMode.BOARD_SNAPSHOT, result.getExplorationMode());
        // 30,000,000 / (5 path length x 2 population x complexity 1), clamped to the field maximum.
        assertEquals(5_000, result.getMaxAcceptedIterations());
        verify(boardDataConverter).getModelInputSnapshot(7L);
        verifyNoInteractions(taskRepository, findingRepository, transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void previewWorkloadRejectsSnapshotsThatSubmissionCannotPersist() {
        SpecificationDto specification = specification("spec-1");
        specification.setTemplateLabel("x".repeat(8 * 1024 * 1024));
        when(boardDataConverter.getModelInputSnapshot(7L))
                .thenReturn(snapshot(List.of(specification)));

        ValidationException error = assertThrows(
                ValidationException.class,
                () -> service.previewWorkload(7L, new FuzzWorkloadPreviewRequestDto()));

        assertTrue(error.getErrors().get("board").contains("8 MiB"));
        verifyNoInteractions(taskRepository, findingRepository, transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void previewWorkloadReportsTheSameLimitRejectionAsSubmission() {
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        FuzzWorkloadPreviewRequestDto request = FuzzWorkloadPreviewRequestDto.builder()
                .maxIterations(5_000)
                .pathLength(50)
                .populationSize(50)
                .build();

        FuzzWorkloadPreviewDto result = service.previewWorkload(7L, request);

        // The maximal raw budget (5000 x 50 x 50 = 12,500,000) at complexity 1 now leaves headroom, which is
        // the point of separating the two ceilings: growing the Board is what consumes it.
        assertEquals(12_500_000L, result.getEstimatedWorkload());
        assertTrue(result.isAccepted());

        ModelInputSnapshot largerSnapshot = snapshot(List.of(
                specification("spec-1"), specification("spec-2"), specification("spec-3")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(largerSnapshot);
        result = service.previewWorkload(7L, request);
        assertEquals(37_500_000L, result.getEstimatedWorkload());
        assertFalse(result.isAccepted());
        // Rejected, and the client is told what would fit instead of being left to guess:
        // 30,000,000 / (50 path length x 50 population x complexity 3) = 4,000.
        assertEquals(4_000, result.getMaxAcceptedIterations());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void submitChecksAtomicPerUserLimitBeforeSerializingTheFrozenSnapshot() {
        SpecificationDto oversized = specification("spec-1");
        oversized.setTemplateLabel("x".repeat(8 * 1024 * 1024));
        when(boardDataConverter.getModelInputSnapshot(7L))
                .thenReturn(snapshot(List.of(oversized)));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        when(taskRepository.countByUserIdAndStatusIn(7L, List.of(
                FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING)))
                .thenReturn(2L);
        runTransactionsInline();

        FuzzTaskQuotaExceededException error = assertThrows(
                FuzzTaskQuotaExceededException.class,
                () -> service.submit(7L, validRequest()));

        assertEquals(429, error.getCode());
        assertEquals(2L, error.getActiveTaskCount());
        assertEquals(2, error.getMaxActiveTasksPerUser());
        InOrder admissionOrder = inOrder(userRepository, taskRepository);
        admissionOrder.verify(userRepository).findByIdForUpdate(7L);
        admissionOrder.verify(taskRepository).countByUserId(7L);
        admissionOrder.verify(taskRepository).countByUserIdAndStatusIn(7L, List.of(
                FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING));
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(fuzzTaskExecutor);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void submitChecksAtomicStoredTaskLimitBeforeSerializingTheFrozenSnapshot() {
        SpecificationDto oversized = specification("spec-1");
        oversized.setTemplateLabel("x".repeat(8 * 1024 * 1024));
        when(boardDataConverter.getModelInputSnapshot(7L))
                .thenReturn(snapshot(List.of(oversized)));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        when(taskRepository.countByUserId(7L)).thenReturn(100L);
        runTransactionsInline();

        FuzzTaskStorageQuotaExceededException error = assertThrows(
                FuzzTaskStorageQuotaExceededException.class,
                () -> service.submit(7L, validRequest()));

        assertEquals(429, error.getCode());
        assertEquals(100L, error.getStoredTaskCount());
        assertEquals(100, error.getMaxStoredTasksPerUser());
        InOrder admissionOrder = inOrder(userRepository, taskRepository);
        admissionOrder.verify(userRepository).findByIdForUpdate(7L);
        admissionOrder.verify(taskRepository).countByUserId(7L);
        verify(taskRepository, never()).countByUserIdAndStatusIn(anyLong(), anyList());
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(fuzzTaskExecutor);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void submitRejectsGlobalCapacityBeforeReadingAnotherBoard() {
        threadPoolConfig.setFuzzTask(new ThreadPoolConfig.Pool(1, 1, 0, 60));
        rebuildService();
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(41L);
            return task;
        });

        assertEquals(41L, service.submit(7L, validRequest()));
        ServiceUnavailableException error = assertThrows(
                ServiceUnavailableException.class,
                () -> service.submit(7L, validRequest()));

        assertTrue(error.getMessage().contains("task capacity"));
        verify(boardDataConverter).getModelInputSnapshot(7L);
        verify(taskRepository).save(any(FuzzTaskPo.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void executorRejectionDeletesUndispatchedSnapshotAndReleasesCapacity() {
        threadPoolConfig.setFuzzTask(new ThreadPoolConfig.Pool(1, 1, 0, 60));
        rebuildService();
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        AtomicLong nextTaskId = new AtomicLong(40L);
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(nextTaskId.incrementAndGet());
            return task;
        });
        when(taskRepository.deleteUndispatchedTask(
                eq(41L), eq(7L), anyString(), eq(FuzzTaskPo.TaskStatus.PENDING)))
                .thenReturn(1);
        doThrow(new TaskRejectedException("full"))
                .doNothing()
                .when(fuzzTaskExecutor).execute(any(Runnable.class));

        assertThrows(ServiceUnavailableException.class,
                () -> service.submit(7L, validRequest()));
        assertEquals(42L, service.submit(7L, validRequest()));

        verify(taskRepository).deleteUndispatchedTask(
                eq(41L), eq(7L), anyString(), eq(FuzzTaskPo.TaskStatus.PENDING));
        verify(taskRepository, never()).failTaskIfActive(
                eq(41L), any(), any(), any(), anyString(), anyString(), anyList(),
                anyString(), any(LocalDateTime.class));
        verify(fuzzTaskExecutor, org.mockito.Mockito.times(2)).execute(any(Runnable.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void executorRejectionWithUnconfirmedCleanupPreservesTaskIdAndReleasesCapacity() {
        threadPoolConfig.setFuzzTask(new ThreadPoolConfig.Pool(1, 1, 0, 60));
        rebuildService();
        when(boardDataConverter.getModelInputSnapshot(7L))
                .thenReturn(snapshot(List.of(specification("spec-1"))));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        AtomicLong nextTaskId = new AtomicLong(40L);
        Map<Long, FuzzTaskPo> savedTasks = new LinkedHashMap<>();
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(nextTaskId.incrementAndGet());
            savedTasks.put(task.getId(), task);
            return task;
        });
        when(taskRepository.deleteUndispatchedTask(
                eq(41L), eq(7L), anyString(), eq(FuzzTaskPo.TaskStatus.PENDING)))
                .thenReturn(0);
        when(taskRepository.findByIdAndUserId(41L, 7L))
                .thenAnswer(invocation -> Optional.of(savedTasks.get(41L)));
        doThrow(new TaskRejectedException("full"))
                .doNothing()
                .when(fuzzTaskExecutor).execute(any(Runnable.class));

        AsyncTaskDispatchOutcomeUnknownException error = assertThrows(
                AsyncTaskDispatchOutcomeUnknownException.class,
                () -> service.submit(7L, validRequest()));

        assertEquals(41L, error.getTaskId());
        assertEquals("fuzz", error.getTaskKind());
        assertEquals(42L, service.submit(7L, validRequest()),
                "unknown database cleanup must not leak the process-local capacity permit");
        verify(taskRepository).findByIdAndUserId(41L, 7L);
        verify(fuzzTaskExecutor, org.mockito.Mockito.times(2)).execute(any(Runnable.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void executorRejectionWhenCleanupQueryFailsPreservesTaskIdAndCleanupCause() {
        when(boardDataConverter.getModelInputSnapshot(7L))
                .thenReturn(snapshot(List.of(specification("spec-1"))));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(41L);
            return task;
        });
        DataAccessResourceFailureException cleanupFailure =
                new DataAccessResourceFailureException("cleanup database unavailable");
        when(taskRepository.deleteUndispatchedTask(
                eq(41L), eq(7L), anyString(), eq(FuzzTaskPo.TaskStatus.PENDING)))
                .thenThrow(cleanupFailure);
        doThrow(new TaskRejectedException("full"))
                .when(fuzzTaskExecutor).execute(any(Runnable.class));

        AsyncTaskDispatchOutcomeUnknownException error = assertThrows(
                AsyncTaskDispatchOutcomeUnknownException.class,
                () -> service.submit(7L, validRequest()));

        assertEquals(41L, error.getTaskId());
        assertEquals(1, error.getCause().getSuppressed().length);
        assertSame(cleanupFailure, error.getCause().getSuppressed()[0]);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void statusRecheckFailureDeletesUndispatchedSnapshotAndReleasesCapacity() {
        threadPoolConfig.setFuzzTask(new ThreadPoolConfig.Pool(1, 1, 0, 60));
        rebuildService();
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        AtomicLong nextTaskId = new AtomicLong(40L);
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(nextTaskId.incrementAndGet());
            return task;
        });
        when(taskRepository.findStatusById(anyLong()))
                .thenThrow(new DataAccessResourceFailureException("temporary read failure"))
                .thenReturn(Optional.of(FuzzTaskPo.TaskStatus.PENDING));
        when(taskRepository.deleteUndispatchedTask(
                eq(41L), eq(7L), anyString(), eq(FuzzTaskPo.TaskStatus.PENDING)))
                .thenReturn(1);

        assertThrows(DataAccessResourceFailureException.class,
                () -> service.submit(7L, validRequest()));
        assertEquals(42L, service.submit(7L, validRequest()));

        verify(taskRepository).deleteUndispatchedTask(
                eq(41L), eq(7L), anyString(), eq(FuzzTaskPo.TaskStatus.PENDING));
        verify(fuzzTaskExecutor).execute(any(Runnable.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cancellingQueuedTaskReleasesGlobalCapacityExactlyOnce() {
        threadPoolConfig.setFuzzTask(new ThreadPoolConfig.Pool(1, 1, 0, 60));
        rebuildService();
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        AtomicLong nextTaskId = new AtomicLong(40L);
        Map<Long, FuzzTaskPo> savedTasks = new LinkedHashMap<>();
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(nextTaskId.incrementAndGet());
            savedTasks.put(task.getId(), task);
            return task;
        });
        when(taskRepository.findByIdAndUserId(anyLong(), eq(7L))).thenAnswer(invocation ->
                Optional.ofNullable(savedTasks.get(invocation.getArgument(0, Long.class))));
        when(taskRepository.cancelTaskIfStillActive(
                eq(41L), eq(FuzzTaskPo.TaskStatus.CANCELLED), any(), anyList()))
                .thenAnswer(invocation -> {
                    savedTasks.get(41L).setStatus(FuzzTaskPo.TaskStatus.CANCELLED);
                    return 1;
                });
        List<Runnable> submittedWorkers = new ArrayList<>();
        doAnswer(invocation -> {
            submittedWorkers.add(invocation.getArgument(0));
            return null;
        }).when(fuzzTaskExecutor).execute(any(Runnable.class));

        assertEquals(41L, service.submit(7L, validRequest()));
        TaskCancellationResultDto cancellation = service.cancelTask(7L, 41L);

        assertTrue(cancellation.isCancellationAccepted());
        assertFalse(cancellation.isExecutionMayStillBeStopping());
        assertTrue(submittedWorkers.get(0) instanceof Future<?>);
        assertTrue(((Future<?>) submittedWorkers.get(0)).isCancelled());
        submittedWorkers.get(0).run();

        assertEquals(42L, service.submit(7L, validRequest()));
        assertThrows(ServiceUnavailableException.class,
                () -> service.submit(7L, validRequest()));
        assertEquals(2, submittedWorkers.size(),
                "running a cancelled Future must not release a second capacity permit");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cancellationCommittedBeforeLocalRegistrationSkipsDispatchAndReleasesCapacity() {
        threadPoolConfig.setFuzzTask(new ThreadPoolConfig.Pool(1, 1, 0, 60));
        rebuildService();
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        AtomicLong nextTaskId = new AtomicLong(40L);
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(nextTaskId.incrementAndGet());
            return task;
        });
        when(taskRepository.findStatusById(anyLong())).thenAnswer(invocation ->
                invocation.getArgument(0, Long.class).equals(41L)
                        ? Optional.of(FuzzTaskPo.TaskStatus.CANCELLED)
                        : Optional.of(FuzzTaskPo.TaskStatus.PENDING));

        assertEquals(41L, service.submit(7L, validRequest()));
        assertEquals(42L, service.submit(7L, validRequest()));

        verify(fuzzTaskExecutor).execute(any(Runnable.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void leaseReconciliationStopsRemotelyCancelledQueuedTaskAndReleasesCapacity() {
        threadPoolConfig.setFuzzTask(new ThreadPoolConfig.Pool(1, 1, 0, 60));
        rebuildService();
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        AtomicLong nextTaskId = new AtomicLong(40L);
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(nextTaskId.incrementAndGet());
            return task;
        });
        List<Runnable> submittedWorkers = new ArrayList<>();
        doAnswer(invocation -> {
            submittedWorkers.add(invocation.getArgument(0));
            return null;
        }).when(fuzzTaskExecutor).execute(any(Runnable.class));
        when(taskRepository.findByIdForUpdate(41L)).thenReturn(Optional.empty());

        assertEquals(41L, service.submit(7L, validRequest()));
        service.maintainTaskLeases();

        assertTrue(((Future<?>) submittedWorkers.get(0)).isCancelled());
        assertEquals(42L, service.submit(7L, validRequest()));
        verify(taskRepository).findByIdForUpdate(41L);
    }

    @Test
    void leaseMaintenanceStopsQueuedTaskAfterRenewalCannotBeConfirmedForFullTtl() throws Exception {
        threadPoolConfig.setFuzzTask(new ThreadPoolConfig.Pool(1, 1, 0, 60));
        rebuildService();
        when(boardDataConverter.getModelInputSnapshot(7L))
                .thenReturn(snapshot(List.of(specification("spec-1"))));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(43L);
            return task;
        });
        List<Runnable> submittedWorkers = new ArrayList<>();
        doAnswer(invocation -> {
            submittedWorkers.add(invocation.getArgument(0));
            return null;
        }).when(fuzzTaskExecutor).execute(any(Runnable.class));
        when(taskRepository.findByIdForUpdate(43L))
                .thenThrow(new RuntimeException("database unavailable"));

        assertEquals(43L, service.submit(7L, validRequest()));
        LeaseConfirmationTestSupport.ageExecutionLease(
                service, "localExecutions", 43L, Duration.ofMinutes(3));
        service.maintainTaskLeases();

        assertTrue(((Future<?>) submittedWorkers.get(0)).isCancelled());
    }

    @Test
    void fuzzStartRejectsLeaseThatExpiredWhileWaitingForItsRowLock() {
        Runnable worker = captureSubmittedWorker();
        LocalDateTime beforeLock = LocalDateTime.of(2026, 9, 30, 10, 0);
        LocalDateTime afterLock = beforeLock.plusSeconds(2);
        LocalDateTime expiry = beforeLock.plusSeconds(1);
        AtomicReference<LocalDateTime> databaseTime = new AtomicReference<>(beforeLock);
        AtomicInteger locks = new AtomicInteger();
        when(taskRepository.currentDatabaseTime()).thenAnswer(invocation -> databaseTime.get());
        when(taskRepository.findByIdForUpdate(41L)).thenAnswer(invocation -> {
            if (locks.incrementAndGet() == 2) databaseTime.set(afterLock);
            return Optional.of(FuzzTaskPo.builder().id(41L).build());
        });
        when(taskRepository.updateProgressIfActive(eq(41L), anyInt(), any(), anyString(), any())).thenReturn(1);
        when(taskRepository.startTaskIfStillPending(eq(41L), any(), any(), anyString(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.<LocalDateTime>getArgument(4).isBefore(expiry) ? 1 : 0);

        worker.run();

        verify(taskRepository).startTaskIfStillPending(
                eq(41L), any(), eq(afterLock), anyString(), eq(afterLock), eq(afterLock.plusMinutes(2)),
                anyString(), any());
        verifyNoInteractions(fuzzEngine);
    }

    @Test
    void fuzzProgressRejectsLeaseThatExpiredWhileWaitingForItsRowLock() {
        runTransactionsInline();
        LocalDateTime beforeLock = LocalDateTime.of(2026, 9, 30, 10, 0);
        LocalDateTime afterLock = beforeLock.plusSeconds(2);
        AtomicReference<LocalDateTime> databaseTime = new AtomicReference<>(beforeLock);
        when(taskRepository.currentDatabaseTime()).thenAnswer(invocation -> databaseTime.get());
        when(taskRepository.findByIdForUpdate(202L)).thenAnswer(invocation -> {
            databaseTime.set(afterLock);
            return Optional.of(FuzzTaskPo.builder().id(202L).build());
        });
        when(taskRepository.updateProgressIfActive(eq(202L), eq(50), any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.<LocalDateTime>getArgument(4)
                        .isBefore(beforeLock.plusSeconds(1)) ? 1 : 0);

        assertEquals(0, service.atomicUpdateProgress(202L, 50, TaskProgressStage.EXPLORING_CANDIDATES));
        InOrder clocks = inOrder(taskRepository);
        clocks.verify(taskRepository).findByIdForUpdate(202L);
        clocks.verify(taskRepository).currentDatabaseTime();
        clocks.verify(taskRepository).updateProgressIfActive(
                eq(202L), eq(50), eq(TaskProgressStage.EXPLORING_CANDIDATES), anyString(), eq(afterLock));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cancellingRunningTaskKeepsCapacityUntilWorkerActuallyStops() throws Exception {
        threadPoolConfig.setFuzzTask(new ThreadPoolConfig.Pool(1, 1, 0, 60));
        rebuildService();
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();
        AtomicLong nextTaskId = new AtomicLong(40L);
        Map<Long, FuzzTaskPo> savedTasks = new LinkedHashMap<>();
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(nextTaskId.incrementAndGet());
            savedTasks.put(task.getId(), task);
            return task;
        });
        when(taskRepository.findStatusById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(savedTasks.get(invocation.getArgument(0, Long.class)))
                        .map(FuzzTaskPo::getStatus));
        when(taskRepository.findByIdAndUserId(anyLong(), eq(7L))).thenAnswer(invocation ->
                Optional.ofNullable(savedTasks.get(invocation.getArgument(0, Long.class))));
        when(taskRepository.updateProgressIfActive(anyLong(), anyInt(), any(TaskProgressStage.class),
                anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(taskRepository.startTaskIfStillPending(
                eq(41L), eq(FuzzTaskPo.TaskStatus.RUNNING), any(), anyString(), any(), any(),
                anyString(), eq(FuzzTaskPo.TaskStatus.PENDING))).thenAnswer(invocation -> {
                    savedTasks.get(41L).setStatus(FuzzTaskPo.TaskStatus.RUNNING);
                    return 1;
                });
        when(taskRepository.findById(41L)).thenAnswer(invocation ->
                Optional.of(savedTasks.get(41L)));
        when(taskRepository.cancelTaskIfStillActive(
                eq(41L), eq(FuzzTaskPo.TaskStatus.CANCELLED), any(), anyList()))
                .thenAnswer(invocation -> {
                    savedTasks.get(41L).setStatus(FuzzTaskPo.TaskStatus.CANCELLED);
                    return 1;
                });
        CountDownLatch engineStarted = new CountDownLatch(1);
        CountDownLatch letEngineStop = new CountDownLatch(1);
        when(fuzzEngine.run(any(FuzzEngineInput.class), any(FuzzProgressListener.class),
                any(BooleanSupplier.class), any(BooleanSupplier.class))).thenAnswer(invocation -> {
                    engineStarted.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = letEngineStop.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            // Keep the worker alive so the test can observe permit ownership.
                        }
                    }
                    throw new IllegalStateException("engine stopped after cancellation");
                });
        AtomicReference<Runnable> submittedWorker = new AtomicReference<>();
        doAnswer(invocation -> {
            submittedWorker.set(invocation.getArgument(0));
            return null;
        }).when(fuzzTaskExecutor).execute(any(Runnable.class));

        assertEquals(41L, service.submit(7L, validRequest()));
        Thread workerThread = new Thread(submittedWorker.get(), "fuzz-cancel-test");
        workerThread.start();
        try {
            assertTrue(engineStarted.await(2, TimeUnit.SECONDS));

            TaskCancellationResultDto cancellation = service.cancelTask(7L, 41L);

            assertTrue(cancellation.isExecutionMayStillBeStopping());
            assertThrows(ServiceUnavailableException.class,
                    () -> service.submit(7L, validRequest()));
        } finally {
            letEngineStop.countDown();
            workerThread.join(2_000);
        }
        assertFalse(workerThread.isAlive());
        assertEquals(42L, service.submit(7L, validRequest()));
    }

    @Test
    // An out-of-range field is rejected on that field, before the Board is read. This used to assert a
    // "request"-keyed error because a combined raw-product check ran first — but that check compared
    // maxIterations x pathLength x populationSize against a ceiling equal to the product of the three field
    // maxima, so it could never reject anything the range checks admit. It was removed as dead code, and
    // this test now pins the behaviour that actually decides the case.
    void submit_rejectsAnOutOfRangeBudgetFieldBeforeReadingTheBoard() {
        FuzzRequestDto request = validRequest();
        request.setMaxIterations(5_000);
        request.setPathLength(50);
        request.setPopulationSize(51);

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, request));

        assertEquals(422, error.getCode());
        assertTrue(error.getErrors().containsKey("populationSize"));
        verifyNoInteractions(boardDataConverter, taskRepository, fuzzTaskExecutor);
    }

    @Test
    void submit_rejectsOverflowingSearchBudgetBeforeReadingTheBoard() {
        FuzzRequestDto request = validRequest();
        request.setMaxIterations(Integer.MAX_VALUE);
        request.setPathLength(Integer.MAX_VALUE);
        request.setPopulationSize(Integer.MAX_VALUE);

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, request));

        assertTrue(error.getErrors().get("request").contains("too large"));
        verifyNoInteractions(boardDataConverter, taskRepository, fuzzTaskExecutor);
    }

    @Test
    void submit_rejectsUnknownTargetBeforeCreatingTask() {
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        FuzzRequestDto request = validRequest();
        request.setTargetSpecIds(List.of("missing-spec"));

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, request));

        assertEquals(422, error.getCode());
        assertTrue(error.getErrors().get("targetSpecIds").contains("missing-spec"));
        verify(boardDataConverter).getModelInputSnapshot(7L);
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void submit_rejectsBoardWithoutSpecificationsBeforeCreatingTask() {
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot(List.of()));

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, validRequest()));

        assertEquals(422, error.getCode());
        assertTrue(error.getErrors().get("targetSpecIds").contains("at least one specification"));
        verify(boardDataConverter).getModelInputSnapshot(7L);
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void submit_rejectsImplicitAllWhenBoardHasMoreThanOneHundredSpecifications() {
        List<SpecificationDto> specifications = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(index -> specification("spec-" + index))
                .toList();
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot(specifications));

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, validRequest()));

        assertEquals(422, error.getCode());
        assertTrue(error.getErrors().get("targetSpecIds").contains("select at most 100"));
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    // Named for what it checks: each captured specification adds one *additive* structure unit, so five
    // specs make complexity 5 and 5000 x 50 x 25 x 5 = 31,250,000 exceeds the 30,000,000 limit while the
    // same budget at complexity 4 (30,000,000 * 4/5 = 25,000,000) would be admitted. There is no
    // target-count factor in effectiveWorkload — the previous name ("includesEffectiveTargetCount")
    // asserted one, and a reader trusting it would look for a multiplication that does not exist.
    void submit_countsEachCapturedSpecificationInWorkloadLimit() {
        ModelInputSnapshot snapshot = snapshot(List.of(
                specification("spec-1"), specification("spec-2"), specification("spec-3"),
                specification("spec-4"), specification("spec-5")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        FuzzRequestDto request = validRequest();
        request.setMaxIterations(5_000);
        request.setPathLength(50);
        request.setPopulationSize(25);

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, request));

        assertEquals(422, error.getCode());
        assertTrue(error.getErrors().containsKey("request"));
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void submit_includesFrozenBoardRulesInEffectiveWorkloadLimit() {
        List<RuleDto> rules = java.util.stream.IntStream.range(0, 62)
                .mapToObj(index -> new RuleDto())
                .toList();
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")), rules);
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, validRequest()));

        assertEquals(422, error.getCode());
        assertTrue(error.getErrors().containsKey("request"));
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void submit_includesTemplateAndConditionStructureInEffectiveWorkloadLimit() {
        int itemCount = 8;
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(java.util.stream.IntStream.range(0, itemCount)
                        .mapToObj(index -> "mode" + index).toList())
                .internalVariables(java.util.stream.IntStream.range(0, itemCount)
                        .mapToObj(index -> DeviceManifest.InternalVariable.builder()
                                .name("local" + index)
                                .values(List.of("off"))
                                .build())
                        .toList())
                .impactedVariables(java.util.stream.IntStream.range(0, itemCount)
                        .mapToObj(index -> "env" + index).toList())
                .workingStates(java.util.stream.IntStream.range(0, itemCount)
                        .mapToObj(index -> DeviceManifest.WorkingState.builder()
                                .name("state" + index)
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("local0").value("off").build()))
                                .build())
                        .toList())
                .transitions(java.util.stream.IntStream.range(0, itemCount)
                        .mapToObj(index -> DeviceManifest.Transition.builder()
                                .name("transition" + index)
                                .assignments(List.of(DeviceManifest.Assignment.builder()
                                        .attribute("local0").value("off").build()))
                                .build())
                        .toList())
                .apis(java.util.stream.IntStream.range(0, itemCount)
                        .mapToObj(index -> DeviceManifest.API.builder().name("api" + index).build())
                        .toList())
                .build();
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("device-1");
        device.setTemplateName("Complex");
        device.setVariables(List.of());
        device.setPrivacies(List.of());
        RuleDto rule = new RuleDto();
        rule.setConditions(java.util.stream.IntStream.range(0, itemCount)
                .mapToObj(index -> RuleDto.Condition.builder().deviceName("device-1").build())
                .toList());
        SpecificationDto specification = specification("spec-1");
        specification.setTemplateId("1");
        specification.setAConditions(List.of(specCondition("condition-1")));
        specification.setAConditions(java.util.stream.IntStream.range(0, itemCount)
                .mapToObj(index -> specCondition("condition-" + index)).toList());
        ModelInputSnapshot snapshot = new ModelInputSnapshot(
                List.of(), List.of(device), List.of(), List.of(rule), List.of(specification),
                Map.of("Complex", manifest));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        // Budget stated explicitly rather than left to validRequest(): this test is about template and
        // condition structure reaching the guard, so it must not pass or fail on a default-value change.
        FuzzRequestDto request = validRequest();
        request.setMaxIterations(5_000);
        request.setPathLength(50);
        request.setPopulationSize(50);

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, request));

        assertTrue(error.getErrors().get("request").contains("30000000"));
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void operationalComplexityCountsNestedModelLoopsAndPreviewMatchesSubmission() {
        int deviceCount = 20;
        int environmentCount = 20;
        List<DeviceManifest.InternalVariable> environmentDomains =
                java.util.stream.IntStream.range(0, environmentCount)
                        .mapToObj(index -> DeviceManifest.InternalVariable.builder()
                                .name("env" + index)
                                .isInside(false)
                                .reads(false)
                                .falsifiableWhenCompromised(false)
                                .values(List.of("low"))
                                .build())
                        .toList();
        DeviceManifest manifest = DeviceManifest.builder()
                .internalVariables(environmentDomains)
                .impactedVariables(environmentDomains.stream()
                        .map(DeviceManifest.InternalVariable::getName)
                        .toList())
                .build();
        List<DeviceVerificationDto> devices = java.util.stream.IntStream.range(0, deviceCount)
                .mapToObj(index -> {
                    DeviceVerificationDto device = new DeviceVerificationDto();
                    device.setVarName("device-" + index);
                    device.setTemplateName("CrossProduct");
                    device.setVariables(List.of());
                    device.setPrivacies(List.of());
                    return device;
                })
                .toList();
        List<BoardEnvironmentVariableDto> environmentVariables =
                java.util.stream.IntStream.range(0, environmentCount)
                        .mapToObj(index -> new BoardEnvironmentVariableDto(
                                "env" + index, "low", "trusted", "public"))
                        .toList();
        ModelInputSnapshot snapshot = new ModelInputSnapshot(
                List.of(), devices, environmentVariables, List.of(),
                List.of(specification("spec-1")), Map.of("CrossProduct", manifest));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        FuzzWorkloadPreviewRequestDto previewRequest = FuzzWorkloadPreviewRequestDto.builder()
                .maxIterations(500)
                .pathLength(4)
                .populationSize(10)
                .build();

        FuzzWorkloadPreviewDto preview = service.previewWorkload(7L, previewRequest);

        // 1,641 is dominated by the 20 environment values x 20 devices cross-product, which is a real
        // per-step loop (FuzzModel.nextState scans every device inside the per-environment loop), so this
        // Board legitimately costs far more per step than a household scene.
        assertEquals(1_641L, preview.getModelComplexityUnits());
        assertEquals(32_820_000L, preview.getEstimatedWorkload());
        assertFalse(preview.isAccepted());
        // 30,000,000 / (4 x 10 x 1,641) = 457 iterations would fit.
        assertEquals(457, preview.getMaxAcceptedIterations());

        FuzzRequestDto submitRequest = validRequest();
        submitRequest.setMaxIterations(previewRequest.getMaxIterations());
        submitRequest.setPathLength(previewRequest.getPathLength());
        submitRequest.setPopulationSize(previewRequest.getPopulationSize());
        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, submitRequest));

        // The rejection names the limit, the Board's own multiplier, and what would fit — the three budget
        // numbers the caller chose cannot explain the refusal on their own.
        String message = error.getErrors().get("request");
        assertTrue(message.contains("30000000"), message);
        assertTrue(message.contains("1641"), message);
        assertTrue(message.contains("457"), message);
        verifyNoInteractions(transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void operationalComplexityCountsModeTransitionsApisAndRulePredecessors() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("power", "lock"))
                .impactedVariables(List.of("env0", "env1", "env2"))
                .transitions(List.of(
                        DeviceManifest.Transition.builder().name("t1").build(),
                        DeviceManifest.Transition.builder().name("t2").build()))
                .apis(List.of(DeviceManifest.API.builder().name("api").build()))
                .build();
        List<DeviceVerificationDto> devices = java.util.stream.IntStream.range(0, 2)
                .mapToObj(index -> {
                    DeviceVerificationDto device = new DeviceVerificationDto();
                    device.setVarName("device-" + index);
                    device.setTemplateName("CrossProduct");
                    return device;
                })
                .toList();
        List<RuleDto> rules = java.util.stream.IntStream.range(0, 2)
                .mapToObj(index -> {
                    RuleDto rule = new RuleDto();
                    rule.setConditions(List.of(
                            RuleDto.Condition.builder().deviceName("device-0").build(),
                            RuleDto.Condition.builder().deviceName("device-1").build()));
                    return rule;
                })
                .toList();
        SpecificationDto specification = specification("spec-1");
        specification.setAConditions(List.of(
                specCondition("a"), specCondition("b"), specCondition("c")));
        ModelInputSnapshot snapshot = new ModelInputSnapshot(
                List.of(), devices,
                java.util.stream.IntStream.range(0, 3)
                        .mapToObj(index -> new BoardEnvironmentVariableDto(
                                "env" + index, "low", "trusted", "public"))
                        .toList(),
                rules, List.of(specification), Map.of("CrossProduct", manifest));

        // Not 83: shared values were counted twice per device, once as a separate
        // "templateEnvironmentDomains" collection and once as templateImpactedVariables. With one
        // declaration array there is one collection to count, so the estimate stops inflating a
        // scene's cost purely because a value was declared in two places.
        //
        // The two modes differ by exactly the rule-predecessor term (18 units here): only evaluatePaper
        // reaches PaperMonitorFsm.distanceToViolation, so a BOARD_SNAPSHOT run performs none of that walk.
        // Charging it in both modes was the largest single term for every scene in docs/examples.
        assertEquals(53L, service.modelComplexityUnits(snapshot));
        assertEquals(53L, service.modelComplexityUnits(
                snapshot, FuzzExplorationMode.BOARD_SNAPSHOT));
        assertEquals(71L, service.modelComplexityUnits(
                snapshot, FuzzExplorationMode.PAPER_COMPATIBLE));
    }

    @Test
    void submit_rejectsPathologicalTemplateCollectionsEvenWithMinimalSearchBudget() {
        DeviceManifest manifest = DeviceManifest.builder()
                .internalVariables(java.util.stream.IntStream.rangeClosed(1, 1_001)
                        .mapToObj(index -> DeviceManifest.InternalVariable.builder()
                                .name("local" + index).values(List.of("off")).build())
                        .toList())
                .build();
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("device-1");
        device.setTemplateName("Pathological");
        ModelInputSnapshot snapshot = new ModelInputSnapshot(
                List.of(), List.of(device), List.of(), List.of(),
                List.of(specification("spec-1")), Map.of("Pathological", manifest));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        FuzzRequestDto request = validRequest();
        request.setMaxIterations(1);
        request.setPathLength(1);
        request.setPopulationSize(1);

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, request));

        assertTrue(error.getErrors().get("request").contains("collection limit"));
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void submit_rejectsPathologicalTotalStructureAcrossDeviceInstances() {
        DeviceManifest manifest = DeviceManifest.builder()
                .internalVariables(java.util.stream.IntStream.range(0, 1_000)
                        .mapToObj(index -> DeviceManifest.InternalVariable.builder()
                                .name("local" + index).values(List.of("off")).build())
                        .toList())
                .build();
        List<DeviceVerificationDto> devices = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> {
                    DeviceVerificationDto device = new DeviceVerificationDto();
                    device.setVarName("device-" + index);
                    device.setTemplateName("LargeShared");
                    return device;
                })
                .toList();
        ModelInputSnapshot snapshot = new ModelInputSnapshot(
                List.of(), devices, List.of(), List.of(),
                List.of(specification("spec-1")), Map.of("LargeShared", manifest));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        FuzzRequestDto request = validRequest();
        request.setMaxIterations(1);
        request.setPathLength(1);
        request.setPopulationSize(1);

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.submit(7L, request));

        assertTrue(error.getErrors().get("request").contains("structure exceeds"));
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(transactionTemplate, fuzzTaskExecutor);
    }

    @Test
    void structureGuardCountsAllFrozenSpecificationsNotOnlyExplicitTargets() {
        List<SpecificationDto> specifications = java.util.stream.IntStream.range(0, 1_001)
                .mapToObj(index -> specification("spec-" + index))
                .toList();
        ModelInputSnapshot snapshot = snapshot(specifications);

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.modelComplexityUnits(snapshot));

        assertTrue(error.getErrors().get("request").contains("collection limit"));
    }

    @Test
    void getRuns_keepsHistoryListAvailableWhenOneRunHasInvalidPersistedData() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 14, 10, 0);
        FuzzTaskSummaryProjection run = mock(FuzzTaskSummaryProjection.class);
        when(run.getId()).thenReturn(51L);
        when(run.getCreatedAt()).thenReturn(createdAt);
        when(run.getCompletedAt()).thenReturn(createdAt.plusSeconds(2));
        when(run.getFindingCount()).thenReturn(1);
        FuzzFindingSummaryProjection finding = mock(FuzzFindingSummaryProjection.class);
        when(finding.getFuzzTaskId()).thenReturn(51L);
        when(taskRepository.findSummaryByUserIdAndStatusOrderByCreatedAtDescIdDesc(
                7L, FuzzTaskPo.TaskStatus.COMPLETED, PageRequest.of(0, 25))).thenReturn(List.of(run));
        when(findingRepository.findSummariesByUserIdAndFuzzTaskIdIn(
                7L, List.of(51L))).thenReturn(List.of(finding));
        when(fuzzMapper.toRunSummaryDtoFromTaskProjection(eq(run), anyList()))
                .thenThrow(new PersistedDataIntegrityException(
                        "FuzzFinding", 61L, "statesJson", "state list is empty"));

        List<FuzzRunSummaryDto> summaries = service.getRuns(7L, 0, 25);

        assertEquals(1, summaries.size());
        FuzzRunSummaryDto unavailable = summaries.get(0);
        assertEquals(51L, unavailable.getId());
        assertFalse(unavailable.isDataAvailable());
        assertEquals("PERSISTED_SEMANTIC_DATA_INVALID", unavailable.getUnavailableReasonCode());
        assertTrue(unavailable.getFindings().isEmpty());
        assertEquals(1, unavailable.getFindingCount());

        when(run.getFindingCount()).thenReturn(-1);
        FuzzRunSummaryDto sanitized = service.getRuns(7L, 0, 25).get(0);
        assertFalse(sanitized.isDataAvailable());
        assertNull(sanitized.getFindingCount());
    }

    @Test
    void getRuns_doesNotMislabelProgrammingErrorsAsPersistedDataDamage() {
        FuzzTaskSummaryProjection run = mock(FuzzTaskSummaryProjection.class);
        when(run.getId()).thenReturn(52L);
        when(taskRepository.findSummaryByUserIdAndStatusOrderByCreatedAtDescIdDesc(
                7L, FuzzTaskPo.TaskStatus.COMPLETED, PageRequest.of(0, 25)))
                .thenReturn(List.of(run));
        when(findingRepository.findSummariesByUserIdAndFuzzTaskIdIn(
                7L, List.of(52L))).thenReturn(List.of());
        when(fuzzMapper.toRunSummaryDtoFromTaskProjection(run, List.of()))
                .thenThrow(new IllegalStateException("mapper bug"));

        assertThrows(IllegalStateException.class, () -> service.getRuns(7L, 0, 25));
    }

    @Test
    void getTasksUsesBoundedUserScopedQueryAndStillExcludesCompletedRuns() {
        FuzzTaskSummaryProjection failed = mock(FuzzTaskSummaryProjection.class);
        FuzzTaskSummaryDto mapped = FuzzTaskSummaryDto.builder()
                .id(71L)
                .status("FAILED")
                .progress(100)
                .explorationMode(FuzzExplorationMode.PAPER_COMPATIBLE)
                .build();
        PageRequest page = PageRequest.of(2, 50);
        when(taskRepository.findSummaryByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(
                7L, FuzzTaskPo.TaskStatus.COMPLETED, page)).thenReturn(List.of(failed));
        when(fuzzMapper.toTaskSummaryDtoProjection(failed)).thenReturn(mapped);

        List<FuzzTaskSummaryDto> tasks = service.getTasks(7L, List.of(), 2, 50);

        assertEquals(List.of(mapped), tasks);
        assertEquals(FuzzExplorationMode.PAPER_COMPATIBLE,
                tasks.get(0).getExplorationMode());
        verify(taskRepository).findSummaryByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(
                7L, FuzzTaskPo.TaskStatus.COMPLETED, page);
    }

    @Test
    void getTasksRejectsOversizedExclusionListsBeforeQueryingPersistence() {
        List<Long> excludedIds = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.getTasks(7L, excludedIds, 0, 100));

        assertEquals(422, error.getCode());
        assertTrue(error.getErrors().containsKey("excludeTaskIds"));
        verifyNoInteractions(taskRepository);
    }

    @Test
    void listQueriesRejectOutOfRangePagesAndSizesBeforePersistence() {
        ValidationException invalidTaskPage = assertThrows(ValidationException.class,
                () -> service.getTasks(7L, List.of(), -1, 100));
        ValidationException invalidRunSize = assertThrows(ValidationException.class,
                () -> service.getRuns(7L, 0, 101));

        assertTrue(invalidTaskPage.getErrors().containsKey("page"));
        assertTrue(invalidRunSize.getErrors().containsKey("size"));
        verifyNoInteractions(taskRepository, findingRepository);
    }

    @Test
    void deletionImpactCountsEveryPersistedFindingWithoutParsingPayloads() {
        CompletedRunDeletionProjection projection = mock(CompletedRunDeletionProjection.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 25, 12, 0);
        LocalDateTime completedAt = createdAt.plusSeconds(2);
        when(projection.getId()).thenReturn(91L);
        when(projection.getCreatedAt()).thenReturn(createdAt);
        when(projection.getCompletedAt()).thenReturn(completedAt);
        when(taskRepository.findDeletionProjection(91L, 7L, FuzzTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(projection));
        when(findingRepository.countByUserIdAndFuzzTaskId(7L, 91L)).thenReturn(4L);

        RunDeletionImpactDto impact = service.getRunDeletionImpact(7L, 91L);

        assertEquals(91L, impact.getRunId());
        assertEquals(4L, impact.getEvidenceCount());
        assertEquals(createdAt, impact.getCreatedAt());
        assertEquals(completedAt, impact.getCompletedAt());
        verify(findingRepository, never())
                .findByUserIdAndFuzzTaskId(anyLong(), anyLong());
        verifyNoInteractions(fuzzMapper);
    }

    @Test
    void confirmedRunDeletionRemovesCorruptFindingPayloadsByOwnedRowCount() {
        FuzzTaskPo run = FuzzTaskPo.builder()
                .id(91L)
                .userId(7L)
                .status(FuzzTaskPo.TaskStatus.COMPLETED)
                .eligibilityJson("not valid json")
                .build();
        when(taskRepository.findCompletedRunForUpdate(91L, 7L, FuzzTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(run));
        when(findingRepository.countByUserIdAndFuzzTaskId(7L, 91L)).thenReturn(4L);
        when(findingRepository.deleteByUserIdAndFuzzTaskId(7L, 91L)).thenReturn(4);
        when(taskRepository.deleteCompletedRun(
                91L, 7L, FuzzTaskPo.TaskStatus.COMPLETED)).thenReturn(1);

        long deletedCount = service.deleteRun(7L, 91L, 4L);

        assertEquals(4L, deletedCount);
        verify(taskRepository).deleteCompletedRun(91L, 7L, FuzzTaskPo.TaskStatus.COMPLETED);
        verify(findingRepository, never())
                .findByUserIdAndFuzzTaskId(anyLong(), anyLong());
        verifyNoInteractions(fuzzMapper);
    }

    @Test
    void confirmedRunDeletionRejectsFindingCountChangedAfterPreview() {
        FuzzTaskPo run = FuzzTaskPo.builder()
                .id(91L).userId(7L).status(FuzzTaskPo.TaskStatus.COMPLETED).build();
        when(taskRepository.findCompletedRunForUpdate(91L, 7L, FuzzTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(run));
        when(findingRepository.countByUserIdAndFuzzTaskId(7L, 91L)).thenReturn(4L);

        ConflictException error = assertThrows(
                ConflictException.class, () -> service.deleteRun(7L, 91L, 3L));

        assertTrue(error.getMessage().contains("preview the deletion again"));
        verify(findingRepository, never()).deleteByUserIdAndFuzzTaskId(anyLong(), anyLong());
        verify(taskRepository, never()).deleteCompletedRun(anyLong(), anyLong(), any());
    }

    @Test
    void confirmedRunDeletionRejectsConcurrentFindingChangeDuringDelete() {
        FuzzTaskPo run = FuzzTaskPo.builder()
                .id(91L).userId(7L).status(FuzzTaskPo.TaskStatus.COMPLETED).build();
        when(taskRepository.findCompletedRunForUpdate(91L, 7L, FuzzTaskPo.TaskStatus.COMPLETED))
                .thenReturn(Optional.of(run));
        when(findingRepository.countByUserIdAndFuzzTaskId(7L, 91L)).thenReturn(4L);
        when(findingRepository.deleteByUserIdAndFuzzTaskId(7L, 91L)).thenReturn(5);

        assertThrows(ConflictException.class, () -> service.deleteRun(7L, 91L, 4L));

        verify(taskRepository, never()).deleteCompletedRun(anyLong(), anyLong(), any());
    }

    @Test
    void findingLookupDoesNotRevealAnotherUsersFinding() {
        when(findingRepository.findByIdAndUserId(81L, 7L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getFinding(7L, 81L));

        verify(findingRepository).findByIdAndUserId(81L, 7L);
        verifyNoInteractions(taskRepository);
    }

    @Test
    void findingLookupPassesTheActualOwnedRunFindingCountToTheMapper() {
        FuzzFindingPo finding = FuzzFindingPo.builder()
                .id(81L)
                .userId(7L)
                .fuzzTaskId(91L)
                .build();
        FuzzTaskPo run = FuzzTaskPo.builder()
                .id(91L)
                .userId(7L)
                .status(FuzzTaskPo.TaskStatus.COMPLETED)
                .build();
        FuzzFindingDto expected = FuzzFindingDto.builder().id(81L).fuzzTaskId(91L).build();
        when(findingRepository.findByIdAndUserId(81L, 7L)).thenReturn(Optional.of(finding));
        when(taskRepository.findByIdAndUserIdAndStatus(
                91L, 7L, FuzzTaskPo.TaskStatus.COMPLETED)).thenReturn(Optional.of(run));
        when(findingRepository.countByUserIdAndFuzzTaskId(7L, 91L)).thenReturn(2L);
        when(fuzzMapper.toFindingDto(run, finding, 2L)).thenReturn(expected);

        assertSame(expected, service.getFinding(7L, 81L));

        verify(findingRepository).countByUserIdAndFuzzTaskId(7L, 91L);
        verify(fuzzMapper).toFindingDto(run, finding, 2L);
    }

    @Test
    void findingReplayLookupUsesOnlyTheSelectedFindingAndItsValidatedRunContext() {
        FuzzFindingPo finding = FuzzFindingPo.builder()
                .id(81L)
                .userId(7L)
                .fuzzTaskId(91L)
                .build();
        FuzzTaskPo run = FuzzTaskPo.builder()
                .id(91L)
                .userId(7L)
                .status(FuzzTaskPo.TaskStatus.COMPLETED)
                .build();
        FuzzFindingReplayDto expected = FuzzFindingReplayDto.builder()
                .finding(FuzzFindingDto.builder().id(81L).fuzzTaskId(91L).build())
                .build();
        when(findingRepository.findByIdAndUserId(81L, 7L)).thenReturn(Optional.of(finding));
        when(taskRepository.findByIdAndUserIdAndStatus(
                91L, 7L, FuzzTaskPo.TaskStatus.COMPLETED)).thenReturn(Optional.of(run));
        when(findingRepository.countByUserIdAndFuzzTaskId(7L, 91L)).thenReturn(2L);
        when(fuzzMapper.toFindingReplayDto(run, finding, 2L)).thenReturn(expected);

        assertSame(expected, service.getFindingReplay(7L, 81L));

        verify(findingRepository).countByUserIdAndFuzzTaskId(7L, 91L);
        verify(fuzzMapper).toFindingReplayDto(run, finding, 2L);
        verify(findingRepository, never()).findByUserIdAndFuzzTaskId(anyLong(), anyLong());
    }

    @Test
    void deleteTaskRequiresAnOwnedNonActiveNonCompletedTask() {
        FuzzTaskPo running = FuzzTaskPo.builder().id(91L).userId(7L)
                .status(FuzzTaskPo.TaskStatus.RUNNING).build();
        when(taskRepository.findByIdAndUserId(91L, 7L)).thenReturn(Optional.of(running));

        assertThrows(BadRequestException.class, () -> service.deleteTask(7L, 91L));
        verify(taskRepository, never()).delete(any());

        FuzzTaskPo failed = FuzzTaskPo.builder().id(92L).userId(7L)
                .status(FuzzTaskPo.TaskStatus.FAILED).build();
        when(taskRepository.findByIdAndUserId(92L, 7L)).thenReturn(Optional.of(failed));

        service.deleteTask(7L, 92L);

        verify(taskRepository).delete(failed);
    }

    @Test
    void cancellationSignalObservesCancellationCommittedByAnotherInstance() {
        FuzzTaskPo cancelled = FuzzTaskPo.builder().id(101L)
                .status(FuzzTaskPo.TaskStatus.CANCELLED).build();
        when(taskRepository.findStatusById(101L))
                .thenReturn(Optional.of(cancelled.getStatus()));

        BooleanSupplier signal = service.cancellationSignal(101L);

        assertTrue(signal.getAsBoolean());
        assertTrue(signal.getAsBoolean());
        verify(taskRepository).findStatusById(101L);
    }

    @Test
    void progressPollingUsesTheScalarProjection() {
        FuzzTaskProgressProjection projection = mock(FuzzTaskProgressProjection.class);
        when(projection.getProgress()).thenReturn(37);
        when(taskRepository.findProgressByIdAndUserId(101L, 7L))
                .thenReturn(Optional.of(projection));

        assertEquals(37, service.getTaskProgress(7L, 101L));

        verify(taskRepository).findProgressByIdAndUserId(101L, 7L);
        verify(taskRepository, never()).findByIdAndUserId(101L, 7L);
    }

    @Test
    void validateEngineResultRejectsViolationAndEventStepsOutsideFindingPrefix() {
        SpecificationDto specification = specification("spec-1");
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        FuzzEngineResult invalidViolationStep = findingResult(new FuzzFinding(
                specification, 1, List.of(state), List.of()));

        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(invalidViolationStep, 1, 10, 10));

        FuzzEngineResult trailingStateAfterViolation = findingResult(new FuzzFinding(
                specification, 0, List.of(state, state), List.of()));
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(trailingStateAfterViolation, 2, 10, 10));

        FuzzEngineResult invalidEventStep = findingResult(new FuzzFinding(
                specification,
                0,
                List.of(state),
                List.of(new FuzzInputEvent(
                        1, FuzzInputEventKind.DEVICE_VARIABLE, "device-1", "temperature", "21",
                        FuzzInputEventSource.MODEL_CHOICE))));
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(invalidEventStep, 1, 10, 10));
    }

    @Test
    void validateEngineResultRejectsFindingLongerThanConfiguredPath() {
        SpecificationDto specification = specification("spec-1");
        TraceStateDto first = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        TraceStateDto second = TraceStateDto.builder()
                .stateIndex(1)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        FuzzEngineResult overBudget = findingResult(new FuzzFinding(
                specification, 1, List.of(first, second), List.of()));

        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(overBudget, 1, 10, 10));
    }

    @Test
    void validateEngineResultRejectsInconsistentExecutionStatistics() {
        SpecificationDto specification = specification("spec-1");
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        FuzzFinding finding = new FuzzFinding(specification, 0, List.of(state), List.of());

        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(findingResult(finding, 0, 1L), 1, 1, 1));
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(findingResult(finding, 1, 0L), 1, 1, 1));
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(findingResult(finding, 2, 1L), 1, 2, 1));
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(findingResult(finding, 1, 2L), 1, 1, 1));
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(findingResult(finding, 2, 2L), 1, 1, 2));
        assertDoesNotThrow(
                () -> service.validateEngineResult(findingResult(finding, 2, 3L), 1, 2, 2));
    }

    @Test
    void validateEngineResultRequiresCausalInputEventOrder() {
        SpecificationDto specification = specification("spec-1");
        TraceStateDto first = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        TraceStateDto second = TraceStateDto.builder()
                .stateIndex(1)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        List<TraceStateDto> states = List.of(first, second);
        FuzzInputEvent randomInitial = inputEvent(0, FuzzInputEventSource.RANDOM_INITIAL_STATE);
        FuzzInputEvent seedAtZero = inputEvent(0, FuzzInputEventSource.SEED_EVENT);
        FuzzInputEvent modelAtZero = inputEvent(0, FuzzInputEventSource.MODEL_CHOICE);
        FuzzInputEvent seedAtOne = inputEvent(1, FuzzInputEventSource.SEED_EVENT);
        FuzzInputEvent modelAtOne = inputEvent(1, FuzzInputEventSource.MODEL_CHOICE);

        assertDoesNotThrow(() -> service.validateEngineResult(findingResult(new FuzzFinding(
                specification, 1, states,
                List.of(randomInitial, seedAtZero, modelAtZero, seedAtOne, modelAtOne))),
                2, 1, 1));
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(findingResult(new FuzzFinding(
                        specification, 1, states, List.of(modelAtOne, modelAtZero))),
                        2, 1, 1));
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(findingResult(new FuzzFinding(
                        specification, 1, states, List.of(modelAtZero, seedAtZero))),
                        2, 1, 1));
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(findingResult(new FuzzFinding(
                        specification, 1, states,
                        List.of(inputEvent(1, FuzzInputEventSource.RANDOM_INITIAL_STATE)))),
                        2, 1, 1));
    }

    @Test
    void eligibilityDiagnosticsAreSingleLineAndBoundedBeforePersistence() {
        String diagnostic = "\n\t" + "x".repeat(700);

        String normalized = service.boundedSingleLine(
                diagnostic, "fallback", FuzzServiceImpl.MAX_ELIGIBILITY_REASON_CHARS);

        assertFalse(normalized.contains("\n"));
        assertFalse(normalized.contains("\t"));
        assertTrue(normalized.length() <= FuzzServiceImpl.MAX_ELIGIBILITY_REASON_CHARS);
        assertTrue(normalized.endsWith("..."));
        String surrogateBoundary = service.boundedSingleLine(
                "x".repeat(196) + "\uD83D\uDE80",
                "fallback",
                FuzzServiceImpl.MAX_ELIGIBILITY_LABEL_CHARS);
        assertTrue(surrogateBoundary.endsWith("..."));
        assertFalse(surrogateBoundary.contains("\uD83D\uDE80"));
        assertTrue(surrogateBoundary.length() <= FuzzServiceImpl.MAX_ELIGIBILITY_LABEL_CHARS);
        assertEquals("fallback", service.boundedSingleLine(
                " \t\n", "fallback", FuzzServiceImpl.MAX_ELIGIBILITY_LABEL_CHARS));
    }

    @Test
    void combinedRunMetadataCannotExceedPersistenceLimit() {
        assertDoesNotThrow(() -> service.requireBoundedRunMetadata("{}", "[]"));

        String oversized = "x".repeat((int) FuzzServiceImpl.MAX_RUN_METADATA_BYTES);
        assertThrows(IllegalStateException.class,
                () -> service.requireBoundedRunMetadata(oversized, "[]"));
    }

    @Test
    void validateEngineResultRejectsIncompleteEventsAndInconsistentSpecificationSets() {
        SpecificationDto specification = specification("spec-1");
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        FuzzEngineResult incompleteEvent = findingResult(new FuzzFinding(
                specification,
                0,
                List.of(state),
                List.of(new FuzzInputEvent(
                        0, FuzzInputEventKind.DEVICE_VARIABLE, " ", "temperature", "21",
                        FuzzInputEventSource.MODEL_CHOICE))));

        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(incompleteEvent, 1, 10, 10));

        FuzzEngineResult ineligibleFinding = new FuzzEngineResult(
                FuzzEngineOutcome.FINDINGS_FOUND,
                42L,
                1,
                1L,
                1L,
                List.of(new FuzzFinding(specification, 0, List.of(state), List.of())),
                List.of(new FuzzSpecEligibility(
                        specification, false, "TEMPLATE_UNSUPPORTED", "Not supported")),
                List.of());
        assertThrows(IllegalStateException.class,
                () -> service.validateEngineResult(ineligibleFinding, 1, 10, 10));
    }

    @Test
    void normalizeLimitationsRejectsMalformedAndDuplicateCodes() {
        assertThrows(IllegalStateException.class,
                () -> service.normalizeLimitations(
                        List.of("Finite-trace falsification only"), FuzzExplorationMode.BOARD_SNAPSHOT));
        assertThrows(IllegalStateException.class,
                () -> service.normalizeLimitations(
                        List.of("FINITE_TRACE_ONLY", "FINITE_TRACE_ONLY"), FuzzExplorationMode.BOARD_SNAPSHOT));
        assertThrows(IllegalStateException.class,
                () -> service.normalizeLimitations(
                        FuzzLimitationContract.baseCodes(), FuzzExplorationMode.PAPER_COMPATIBLE));
        List<String> complete = new java.util.ArrayList<>(FuzzLimitationContract.baseCodes());
        complete.add("FUTURE_DISCLOSURE_CODE");
        assertEquals(complete,
                service.normalizeLimitations(complete, FuzzExplorationMode.BOARD_SNAPSHOT));
    }

    @Test
    void submit_defaultsModeAndHandsItToPersistenceAndWorker() {
        assertSubmittedMode(validRequest(), FuzzExplorationMode.BOARD_SNAPSHOT);
    }

    @Test
    void submit_preservesExplicitPaperCompatibleModeInPersistenceAndWorker() {
        FuzzRequestDto request = validRequest();
        request.setExplorationMode(FuzzExplorationMode.PAPER_COMPATIBLE);

        assertSubmittedMode(request, FuzzExplorationMode.PAPER_COMPATIBLE);
    }

    @Test
    void frozenSnapshotUsesStrictVersionedEnvelopeWithCompleteProvenance()
            throws Exception {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("switch_1");
        device.setTemplateName("Switch");
        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        ModelInputSnapshot snapshot = new ModelInputSnapshot(
                List.of(), List.of(device), List.of(), List.of(), List.of(), Map.of());

        String snapshotJson = FuzzModelInputSnapshotCodec.encode(snapshot);
        ObjectNode snapshotRoot = (ObjectNode) new ObjectMapper().readTree(snapshotJson);
        ModelInputSnapshot restored = FuzzModelInputSnapshotCodec.decode(snapshotJson);

        assertEquals(1, snapshotRoot.path("schemaVersion").asInt());
        assertTrue(snapshotRoot.path("snapshot").has("devices"));
        assertFalse(snapshotRoot.path("snapshot").path("devices").get(0).has("modelTokenSource"));
        assertEquals("BUNDLED", snapshotRoot.path("modelTokenSourcesByDeviceId")
                .path("switch_1").asText());
        assertEquals(ModelTokenSource.BUNDLED,
                restored.devices().get(0).getModelTokenSource());

        assertThrows(IllegalStateException.class,
                () -> FuzzModelInputSnapshotCodec.decode(JsonUtils.toJson(snapshot)));

        String unsupportedSchemaJson = snapshotJson.replace(
                "\"schemaVersion\":1", "\"schemaVersion\":2");
        assertThrows(IllegalStateException.class,
                () -> FuzzModelInputSnapshotCodec.decode(unsupportedSchemaJson));

        ObjectNode missingSourceRoot = snapshotRoot.deepCopy();
        missingSourceRoot.putObject("modelTokenSourcesByDeviceId");
        assertThrows(IllegalStateException.class,
                () -> FuzzModelInputSnapshotCodec.decode(missingSourceRoot.toString()));

        ObjectNode unexpectedFieldRoot = snapshotRoot.deepCopy();
        unexpectedFieldRoot.put("unexpected", true);
        assertThrows(IllegalStateException.class,
                () -> FuzzModelInputSnapshotCodec.decode(unexpectedFieldRoot.toString()));

        ObjectNode missingSnapshotFieldRoot = snapshotRoot.deepCopy();
        ((ObjectNode) missingSnapshotFieldRoot.path("snapshot")).remove("rules");
        assertThrows(IllegalStateException.class,
                () -> FuzzModelInputSnapshotCodec.decode(missingSnapshotFieldRoot.toString()));

        ObjectNode wrongSnapshotTypeRoot = snapshotRoot.deepCopy();
        ((ObjectNode) wrongSnapshotTypeRoot.path("snapshot")).put("devices", "not-an-array");
        assertThrows(IllegalStateException.class,
                () -> FuzzModelInputSnapshotCodec.decode(wrongSnapshotTypeRoot.toString()));
    }

    @Test
    void workerInitializationFailureFailsByTaskIdAndReleasesItsLease() {
        Runnable worker = captureSubmittedWorker();
        runVoidTransactionsInline();
        when(taskRepository.updateProgressIfActive(eq(41L), eq(0), eq(TaskProgressStage.STARTING),
                anyString(), any(LocalDateTime.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("jdbc details"));
        when(taskRepository.failTaskIfActive(
                eq(41L), eq(FuzzTaskPo.TaskStatus.FAILED), any(), any(),
                anyString(), anyString(), any(), anyString(), any(LocalDateTime.class))).thenReturn(1);

        assertDoesNotThrow(worker::run);

        ArgumentCaptor<String> publicError = ArgumentCaptor.forClass(String.class);
        verify(taskRepository).failTaskIfActive(
                eq(41L), eq(FuzzTaskPo.TaskStatus.FAILED), any(), any(),
                publicError.capture(), anyString(), any(), anyString(), any(LocalDateTime.class));
        assertEquals("Counterexample search failed: Internal counterexample exploration error",
                publicError.getValue());
        verify(taskRepository).releaseOwnedActiveLease(
                eq(41L), anyString(), any(), any());
    }

    @Test
    void failurePersistenceErrorStillReleasesLeaseForRecovery() {
        Runnable worker = captureSubmittedWorker();
        runVoidTransactionsInline();
        when(taskRepository.updateProgressIfActive(eq(41L), eq(0), eq(TaskProgressStage.STARTING),
                anyString(), any(LocalDateTime.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("progress failed"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("failure update failed"))
                .when(taskRepository).failTaskIfActive(
                        eq(41L), eq(FuzzTaskPo.TaskStatus.FAILED), any(), any(),
                        anyString(), anyString(), any(), anyString(), any(LocalDateTime.class));

        assertDoesNotThrow(worker::run);

        verify(taskRepository).releaseOwnedActiveLease(
                eq(41L), anyString(), any(), any());
    }

    @Test
    void paperSubmitRequiresCurrentPreviewFingerprintAndRejectsBoardDrift() {
        FuzzRequestDto missing = validRequest();
        missing.setExplorationMode(FuzzExplorationMode.PAPER_COMPATIBLE);

        ValidationException missingError = assertThrows(
                ValidationException.class,
                () -> service.submit(7L, missing));
        assertTrue(missingError.getErrors().containsKey("paperDomainFingerprint"));
        verifyNoInteractions(boardDataConverter);

        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        FuzzRequestDto stale = validRequest();
        stale.setExplorationMode(FuzzExplorationMode.PAPER_COMPATIBLE);
        stale.setPaperDomainFingerprint("0".repeat(64));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000")
                        .username("alice").password("encoded").build()));
        runTransactionsInline();

        ValidationException staleError = assertThrows(
                ValidationException.class,
                () -> service.submit(7L, stale));
        assertTrue(staleError.getErrors().containsKey("paperDomainFingerprint"));
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(fuzzTaskExecutor);
    }

    @Test
    void paperDomainFingerprintTracksSemanticsButIgnoresCanvasLayout() {
        SpecificationDto specification = specification("spec-1");
        ModelInputSnapshot baseline = snapshot(List.of(specification));
        DeviceNodeDto movedNode = new DeviceNodeDto();
        movedNode.setId("visual-only-node");
        ModelInputSnapshot moved = new ModelInputSnapshot(
                List.of(movedNode),
                baseline.devices(),
                baseline.environmentVariables(),
                baseline.rules(),
                baseline.specifications(),
                baseline.templateManifests());
        SpecificationDto changedSpecification = specification("spec-1");
        changedSpecification.setTemplateLabel("Changed safety property");
        ModelInputSnapshot displayOnlySpecChange = snapshot(List.of(changedSpecification));
        SpecificationDto semanticSpecification = specification("spec-1");
        semanticSpecification.setTemplateId("1");
        ModelInputSnapshot semanticChange = snapshot(List.of(semanticSpecification));
        DeviceVerificationDto originalDevice = new DeviceVerificationDto();
        originalDevice.setVarName("device-1");
        originalDevice.setDeviceLabel("Kitchen sensor");
        originalDevice.setTemplateName("Sensor");
        originalDevice.setState("on");
        DeviceVerificationDto renamedDevice = new DeviceVerificationDto();
        renamedDevice.setVarName("device-1");
        renamedDevice.setDeviceLabel("Renamed sensor");
        renamedDevice.setTemplateName("Sensor");
        renamedDevice.setState("on");
        DeviceVerificationDto secondDevice = new DeviceVerificationDto();
        secondDevice.setVarName("device-2");
        secondDevice.setDeviceLabel("Second sensor");
        secondDevice.setTemplateName("Sensor");
        secondDevice.setState("off");
        ModelInputSnapshot originalLabel = new ModelInputSnapshot(
                List.of(), List.of(originalDevice), List.of(), List.of(),
                List.of(specification), Map.of());
        ModelInputSnapshot renamedLabel = new ModelInputSnapshot(
                List.of(), List.of(renamedDevice), List.of(), List.of(),
                List.of(specification), Map.of());
        ModelInputSnapshot deviceOrder = new ModelInputSnapshot(
                List.of(), List.of(originalDevice, secondDevice), List.of(), List.of(),
                List.of(specification), Map.of());
        ModelInputSnapshot reversedDeviceOrder = new ModelInputSnapshot(
                List.of(), List.of(secondDevice, originalDevice), List.of(), List.of(),
                List.of(specification), Map.of());
        RuleDto firstRule = new RuleDto();
        firstRule.setId(1L);
        RuleDto secondRule = new RuleDto();
        secondRule.setId(2L);
        ModelInputSnapshot orderedRules = snapshot(List.of(specification), List.of(firstRule, secondRule));
        ModelInputSnapshot reorderedRules = snapshot(List.of(specification), List.of(secondRule, firstRule));
        DeviceManifest alphaManifest = DeviceManifest.builder().name("Alpha").build();
        DeviceManifest betaManifest = DeviceManifest.builder().name("Beta").build();
        Map<String, DeviceManifest> alphaFirst = new LinkedHashMap<>();
        alphaFirst.put("Alpha", alphaManifest);
        alphaFirst.put("Beta", betaManifest);
        Map<String, DeviceManifest> betaFirst = new LinkedHashMap<>();
        betaFirst.put("Beta", betaManifest);
        betaFirst.put("Alpha", alphaManifest);
        ModelInputSnapshot orderedTemplates = new ModelInputSnapshot(
                List.of(), List.of(), List.of(), List.of(), List.of(specification), alphaFirst);
        ModelInputSnapshot reorderedTemplates = new ModelInputSnapshot(
                List.of(), List.of(), List.of(), List.of(), List.of(specification), betaFirst);

        assertEquals(service.modelFingerprint(baseline), service.modelFingerprint(moved));
        assertEquals(service.modelFingerprint(baseline), service.modelFingerprint(displayOnlySpecChange));
        assertEquals(service.modelFingerprint(originalLabel), service.modelFingerprint(renamedLabel));
        assertNotEquals(service.modelFingerprint(deviceOrder), service.modelFingerprint(reversedDeviceOrder));
        assertNotEquals(service.modelFingerprint(baseline), service.modelFingerprint(semanticChange));
        assertNotEquals(service.modelFingerprint(orderedRules), service.modelFingerprint(reorderedRules));
        assertEquals(service.modelFingerprint(orderedTemplates), service.modelFingerprint(reorderedTemplates));
        assertNotEquals(
                service.paperDomainFingerprint(baseline, 1),
                service.paperDomainFingerprint(baseline, 50));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertSubmittedMode(
            FuzzRequestDto request, FuzzExplorationMode expectedMode) {
        SpecificationDto specification = specification("spec-1");
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("bundled_1");
        device.setTemplateName("Bundled");
        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Bundled")
                .modes(List.of())
                .internalVariables(List.of())
                .impactedVariables(List.of())
                .workingStates(List.of())
                .transitions(List.of())
                .apis(List.of())
                .contents(List.of())
                .build();
        ModelInputSnapshot snapshot = new ModelInputSnapshot(
                List.of(), List.of(device), List.of(), List.of(),
                List.of(specification), Map.of("Bundled", manifest));
        if (expectedMode == FuzzExplorationMode.PAPER_COMPATIBLE) {
            request.setPaperDomainFingerprint(service.paperDomainFingerprint(snapshot, request.getPathLength()));
        }
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000").username("alice").password("encoded").build()));
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        doAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        AtomicReference<FuzzTaskPo> savedTask = new AtomicReference<>();
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(41L);
            savedTask.set(task);
            return task;
        });
        ArgumentCaptor<Runnable> workerCaptor = ArgumentCaptor.forClass(Runnable.class);

        when(taskRepository.updateProgressIfActive(anyLong(), anyInt(), any(TaskProgressStage.class),
                anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(taskRepository.startTaskIfStillPending(
                anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(taskRepository.findById(41L)).thenAnswer(invocation -> {
            FuzzTaskPo task = savedTask.get();
            task.setStatus(FuzzTaskPo.TaskStatus.RUNNING);
            task.setStartedAt(LocalDateTime.now());
            return Optional.of(task);
        });
        when(taskRepository.findStatusById(41L))
                .thenReturn(
                        Optional.of(FuzzTaskPo.TaskStatus.PENDING),
                        Optional.of(FuzzTaskPo.TaskStatus.RUNNING));
        AtomicReference<List<FuzzFindingPo>> savedFindings = new AtomicReference<>();
        when(findingRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<FuzzFindingPo> findings = invocation.getArgument(0);
            savedFindings.set(findings);
            return findings;
        });
        when(taskRepository.completeTaskIfRunning(
                anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyString(), any(LocalDateTime.class)))
                .thenReturn(1);

        AtomicReference<FuzzEngineInput> workerInput = new AtomicReference<>();
        when(fuzzEngine.run(any(FuzzEngineInput.class), any(FuzzProgressListener.class),
                any(BooleanSupplier.class), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    workerInput.set(invocation.getArgument(0));
                    BooleanSupplier cancelled = invocation.getArgument(2);
                    assertFalse(cancelled.getAsBoolean());
                    // A fresh run must not start already out of time.
                    assertFalse(((BooleanSupplier) invocation.getArgument(3)).getAsBoolean());
                    TraceStateDto state = TraceStateDto.builder()
                            .stateIndex(0)
                            .devices(List.of())
                            .triggeredRules(List.of())
                            .compromisedAutomationLinks(List.of())
                            .build();
                    return findingResult(new FuzzFinding(
                            specification,
                            0,
                            List.of(state),
                            List.of(new FuzzInputEvent(
                                    0,
                                    FuzzInputEventKind.ENVIRONMENT_RATE,
                                    "environment",
                                    "temperature",
                                    "rate:1",
                                    FuzzInputEventSource.SEED_EVENT))));
                });

        Long taskId = service.submit(7L, request);
        verify(fuzzTaskExecutor).execute(workerCaptor.capture());

        assertEquals(41L, taskId);
        assertEquals(
                JsonUtils.toJson(snapshot),
                JsonUtils.toJson(FuzzModelInputSnapshotCodec.decode(
                        savedTask.get().getModelInputSnapshotJson())));
        assertEquals(expectedMode, savedTask.get().getExplorationMode());
        verify(boardDataConverter).getModelInputSnapshot(7L);

        workerCaptor.getValue().run();

        assertEquals(JsonUtils.toJson(snapshot), JsonUtils.toJson(workerInput.get().snapshot()));
        assertEquals(ModelTokenSource.BUNDLED,
                workerInput.get().snapshot().devices().get(0).getModelTokenSource());
        assertEquals(expectedMode, workerInput.get().config().explorationMode());
        assertEquals(1, savedFindings.get().size());
        List<FuzzInputEventDto> persistedEvents = JsonUtils.fromJson(
                savedFindings.get().get(0).getInputEventsJson(),
                new TypeReference<List<FuzzInputEventDto>>() {});
        assertEquals("ENVIRONMENT_RATE", persistedEvents.get(0).getKind());
        assertEquals("SEED_EVENT", persistedEvents.get(0).getSource());
        verify(boardDataConverter).getModelInputSnapshot(7L);
        verify(fuzzEngine).run(any(FuzzEngineInput.class), any(FuzzProgressListener.class),
                any(BooleanSupplier.class), any(BooleanSupplier.class));
    }

    private FuzzRequestDto validRequest() {
        return FuzzRequestDto.builder()
                .maxIterations(1_000)
                .pathLength(20)
                .populationSize(10)
                .targetSpecIds(List.of())
                .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void runTransactionsInline() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        doAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));
    }

    private void runVoidTransactionsInline() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Runnable captureSubmittedWorker() {
        ModelInputSnapshot snapshot = snapshot(List.of(specification("spec-1")));
        when(boardDataConverter.getModelInputSnapshot(7L)).thenReturn(snapshot);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(
                UserPo.builder().id(7L).phone("13900000000").username("alice").password("encoded").build()));
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        doAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));
        when(taskRepository.save(any(FuzzTaskPo.class))).thenAnswer(invocation -> {
            FuzzTaskPo task = invocation.getArgument(0);
            task.setId(41L);
            return task;
        });
        ArgumentCaptor<Runnable> workerCaptor = ArgumentCaptor.forClass(Runnable.class);

        assertEquals(41L, service.submit(7L, validRequest()));
        verify(fuzzTaskExecutor).execute(workerCaptor.capture());
        return workerCaptor.getValue();
    }

    private ModelInputSnapshot snapshot(List<SpecificationDto> specifications) {
        return snapshot(specifications, List.of());
    }

    private ModelInputSnapshot snapshot(
            List<SpecificationDto> specifications, List<RuleDto> rules) {
        return new ModelInputSnapshot(
                List.of(), List.of(), List.of(), rules, specifications, Map.of());
    }

    private SpecificationDto specification(String id) {
        SpecificationDto specification = new SpecificationDto();
        specification.setId(id);
        specification.setTemplateId("6");
        specification.setTemplateLabel("Safety property " + id);
        return specification;
    }

    private SpecConditionDto specCondition(String id) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setId(id);
        condition.setSide("a");
        condition.setDeviceId("device-1");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("on");
        return condition;
    }

    private FuzzEngineResult findingResult(FuzzFinding finding) {
        return findingResult(finding, 1, 1L);
    }

    private FuzzEngineResult findingResult(
            FuzzFinding finding, int iterations, long generatedPaths) {
        return new FuzzEngineResult(
                FuzzEngineOutcome.FINDINGS_FOUND,
                42L,
                iterations,
                generatedPaths,
                1L,
                List.of(finding),
                List.of(new FuzzSpecEligibility(finding.specification(), true, null, null)),
                FuzzLimitationContract.requiredCodes(FuzzExplorationMode.PAPER_COMPATIBLE));
    }

    private FuzzInputEvent inputEvent(int step, FuzzInputEventSource source) {
        return new FuzzInputEvent(
                step,
                FuzzInputEventKind.DEVICE_VARIABLE,
                "device-1",
                "temperature",
                "21",
                source);
    }
}
