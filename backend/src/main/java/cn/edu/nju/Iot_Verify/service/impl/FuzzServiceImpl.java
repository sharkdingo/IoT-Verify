package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngine;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineConfig;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineInput;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineOutcome;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineResult;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzFinding;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzInputEvent;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzInputEventSource;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzLimitationContract;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzMetadataPolicy;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzModelFingerprint;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzModelInputSnapshotCodec;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzPaperDomainPreviewer;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzSpecEligibility;
import cn.edu.nju.Iot_Verify.configure.FuzzAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.ThreadPoolConfig;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzEligibilityDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingReplayDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzIneligibleSpecDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzInputEventDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzOutcome;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzPaperDomainPreviewDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzPaperDomainPreviewRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzWorkloadPreviewDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzWorkloadPreviewRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
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
import cn.edu.nju.Iot_Verify.repository.FuzzFindingRepository;
import cn.edu.nju.Iot_Verify.repository.FuzzTaskRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.projection.CompletedRunDeletionProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzFindingSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskProgressProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.RunInitiatorResolver;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import cn.edu.nju.Iot_Verify.util.mapper.FuzzMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FuzzServiceImpl extends AbstractAsyncTaskService<FuzzTaskPo> implements FuzzService {

    private static final long CANCELLATION_DB_POLL_NANOS = 250_000_000L;
    // Wall-clock ceiling for one search, independent of the workload guard. The guard bounds the *nominal*
    // budget, but its calibration is per-step timing measured on one machine: a slower host, a contended
    // worker pool, or a board whose real per-step cost exceeds the estimate can all overrun it. Without this,
    // an admitted run had no upper bound on duration at all — TASK_LEASE_DURATION is a liveness heartbeat
    // that renews, not a deadline. Chosen at 5x the ~60s a maximal admitted run measured, so it never fires
    // on a correctly-estimated search and only catches the cases the static estimate got wrong.
    private static final Duration MAX_SEARCH_WALL_CLOCK = Duration.ofMinutes(5);
    private static final Duration TASK_LEASE_DURATION = Duration.ofMinutes(2);
    private static final long LEASE_MAINTENANCE_SECONDS = 10L;
    // Calibrated against measured throughput rather than chosen. Forced BUDGET_EXHAUSTED runs over the
    // docs/examples scenes (BOARD_SNAPSHOT, one dev box, single-threaded) cost ~119-137 us per state step on
    // the cheapest scene and ~159-168 us on the most expensive. Admissible steps are LIMIT / complexity, and
    // those scenes measure 62-85 units in this mode, so a maximal run lands near a minute at either end.
    // The previous 12,500,000 was *identical* to the raw budget ceiling (5000 x 50 x 50), so the complexity
    // factor had no headroom to trim and consumed the whole budget instead: every shipped example scene
    // refused the product's own default settings, for searches measured at about 16 seconds.
    // No separate wall-clock deadline exists, so this guard is also what bounds a run's duration.
    private static final long MAX_EFFECTIVE_WORK = 30_000_000L;
    private static final long MAX_MODEL_STRUCTURE_UNITS = 10_000L;
    private static final int MAX_FUZZ_COLLECTION_ITEMS = 1_000;
    private static final long MAX_MODEL_INPUT_SNAPSHOT_BYTES = 8L * 1024 * 1024;
    private static final long MAX_FINDING_EVIDENCE_BYTES = 4L * 1024 * 1024;
    private static final long MAX_RUN_EVIDENCE_BYTES = 16L * 1024 * 1024;
    static final long MAX_RUN_METADATA_BYTES = FuzzMetadataPolicy.MAX_RUN_METADATA_BYTES;
    static final int MAX_ELIGIBILITY_LABEL_CHARS = FuzzMetadataPolicy.MAX_ELIGIBILITY_LABEL_CHARS;
    static final int MAX_ELIGIBILITY_REASON_CHARS = FuzzMetadataPolicy.MAX_ELIGIBILITY_REASON_CHARS;
    private static final int MAX_STABLE_CODE_CHARS = FuzzMetadataPolicy.MAX_STABLE_CODE_CHARS;
    private static final Pattern STABLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final Pattern MODEL_FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");
    private static final List<FuzzTaskPo.TaskStatus> ACTIVE_STATUSES = List.of(
            FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING);

    private final FuzzTaskRepository taskRepository;
    private final FuzzFindingRepository findingRepository;
    private final UserRepository userRepository;
    private final BoardDataConverter boardDataConverter;
    private final FuzzEngine fuzzEngine;
    private final FuzzPaperDomainPreviewer fuzzPaperDomainPreviewer;
    private final FuzzMapper fuzzMapper;
    private final ThreadPoolTaskExecutor fuzzTaskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final int maxActiveTasksPerUser;
    private final int maxStoredTasksPerUser;
    private final Semaphore globalTaskCapacity;
    private final FuzzModelFingerprint modelFingerprint;
    private final String workerId = UUID.randomUUID().toString();
    private final ScheduledExecutorService leaseMaintenanceExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fuzz-task-lease-maintenance");
                thread.setDaemon(true);
                return thread;
            });
    private final ConcurrentHashMap<Long, LocalFuzzExecution> localExecutions =
            new ConcurrentHashMap<>();

    public FuzzServiceImpl(FuzzTaskRepository taskRepository,
                           FuzzFindingRepository findingRepository,
                           UserRepository userRepository,
                           BoardDataConverter boardDataConverter,
                           FuzzEngine fuzzEngine,
                           FuzzPaperDomainPreviewer fuzzPaperDomainPreviewer,
                           FuzzModelFingerprint modelFingerprint,
                           FuzzMapper fuzzMapper,
                           @Qualifier("fuzzTaskExecutor") ThreadPoolTaskExecutor fuzzTaskExecutor,
                           FuzzAdmissionConfig fuzzAdmissionConfig,
                           ThreadPoolConfig threadPoolConfig,
                           TransactionTemplate transactionTemplate,
                           ObjectMapper objectMapper) {
        super(objectMapper, "Counterexample search task");
        this.taskRepository = taskRepository;
        this.findingRepository = findingRepository;
        this.userRepository = userRepository;
        this.boardDataConverter = boardDataConverter;
        this.fuzzEngine = fuzzEngine;
        this.fuzzPaperDomainPreviewer = fuzzPaperDomainPreviewer;
        this.modelFingerprint = Objects.requireNonNull(modelFingerprint, "modelFingerprint");
        this.fuzzMapper = fuzzMapper;
        this.fuzzTaskExecutor = fuzzTaskExecutor;
        this.transactionTemplate = transactionTemplate;
        this.maxActiveTasksPerUser = fuzzAdmissionConfig.getMaxActiveTasksPerUser();
        this.maxStoredTasksPerUser = fuzzAdmissionConfig.getMaxStoredTasksPerUser();
        this.globalTaskCapacity = new Semaphore(
                configuredTaskCapacity(threadPoolConfig.getFuzzTask()), true);
    }

    private int configuredTaskCapacity(ThreadPoolConfig.Pool pool) {
        return Math.toIntExact(Math.addExact(
                (long) pool.getMaxPoolSize(), (long) pool.getQueueCapacity()));
    }

    @PostConstruct
    void initializeTaskLeaseMaintenance() {
        maintainTaskLeases();
        leaseMaintenanceExecutor.scheduleWithFixedDelay(
                this::maintainTaskLeases,
                LEASE_MAINTENANCE_SECONDS,
                LEASE_MAINTENANCE_SECONDS,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopTaskLeaseMaintenance() {
        leaseMaintenanceExecutor.shutdownNow();
    }

    void maintainTaskLeases() {
        List<LocalFuzzExecution> executions = List.copyOf(localExecutions.values());
        try {
            databaseNow();
        } catch (RuntimeException e) {
            stopFuzzExecutionsWithExpiredConfirmation(executions);
            log.warn("Could not read database time while maintaining counterexample-search task leases", e);
            return;
        }
        for (LocalFuzzExecution execution : executions) {
            try {
                TaskLeaseRenewal.RenewalResult renewal = TaskLeaseRenewal.renewWithConfirmation(
                        transactionTemplate,
                        () -> taskRepository.findByIdForUpdate(execution.taskId),
                        this::databaseNow,
                        taskRepository::saveAndFlush,
                        workerId,
                        TASK_LEASE_DURATION);
                if (!renewal.renewed()) {
                    execution.requestStop();
                } else {
                    execution.leaseConfirmation.confirmAt(renewal.confirmationStartedNanos());
                }
            } catch (RuntimeException e) {
                if (execution.leaseConfirmation.isUnconfirmedFor(TASK_LEASE_DURATION)) {
                    execution.requestStop();
                    log.warn("Stopped local counterexample-search task {} after its lease could not be confirmed for a full TTL",
                            execution.taskId);
                } else {
                    log.warn("Could not renew counterexample-search task lease {}; the next cycle will retry",
                            execution.taskId, e);
                }
            }
        }
        try {
            LocalDateTime recoveryTime = databaseNow();
            int recovered = taskRepository.failExpiredActiveTasks(
                    FuzzTaskPo.TaskStatus.FAILED,
                    recoveryTime,
                    "The counterexample search stopped before the task completed",
                    serializeCheckLogs(List.of(
                            "Counterexample search task lease expired before completion")),
                    ACTIVE_STATUSES,
                    recoveryTime);
            if (recovered > 0) {
                log.warn("Recovered {} expired fuzz task lease(s)", recovered);
            }
        } catch (RuntimeException e) {
            log.warn("Could not recover expired counterexample-search task leases; the next cycle will retry", e);
        }
    }

    private void stopFuzzExecutionsWithExpiredConfirmation(List<LocalFuzzExecution> executions) {
        for (LocalFuzzExecution execution : executions) {
            if (execution.leaseConfirmation.isUnconfirmedFor(TASK_LEASE_DURATION)) {
                execution.requestStop();
                log.warn("Stopped local counterexample-search task {} after the database could not confirm its lease for a full TTL",
                        execution.taskId);
            }
        }
    }

    @Override
    public FuzzPaperDomainPreviewDto previewPaperDomain(
            Long userId, FuzzPaperDomainPreviewRequestDto request) {
        if (request == null) {
            throw new ValidationException("request", "Random-state input preview request cannot be null");
        }
        int pathLength = requireRange(request.getPathLength(), 1, 50,
                "pathLength", "Path length must be between 1 and 50");
        ModelInputSnapshot snapshot = boardDataConverter.getModelInputSnapshot(userId);
        modelComplexityUnits(snapshot);
        serializeFrozenSnapshot(snapshot, "board");
        try {
            return fuzzPaperDomainPreviewer.preview(
                    snapshot, pathLength, paperDomainFingerprint(snapshot, pathLength));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("board",
                    "Random-state input range cannot be built from the current Board");
        }
    }

    @Override
    public FuzzWorkloadPreviewDto previewWorkload(
            Long userId, FuzzWorkloadPreviewRequestDto request) {
        if (request == null) {
            throw new ValidationException("request", "Workload preview request cannot be null");
        }
        int maxIterations = requireRange(request.getMaxIterations(), 1, 5_000,
                "maxIterations", "Maximum iterations must be between 1 and 5000");
        int pathLength = requireRange(request.getPathLength(), 1, 50,
                "pathLength", "Path length must be between 1 and 50");
        int populationSize = requireRange(request.getPopulationSize(), 1, 50,
                "populationSize", "Population size must be between 1 and 50");
        FuzzExplorationMode explorationMode = request.getExplorationMode() == null
                ? FuzzExplorationMode.BOARD_SNAPSHOT
                : request.getExplorationMode();
        ModelInputSnapshot snapshot = boardDataConverter.getModelInputSnapshot(userId);
        long modelComplexity = modelComplexityUnits(snapshot, explorationMode);
        serializeFrozenSnapshot(snapshot, "board");
        long workload = effectiveWorkload(
                maxIterations, pathLength, populationSize, modelComplexity);
        return FuzzWorkloadPreviewDto.builder()
                .maxIterations(maxIterations)
                .pathLength(pathLength)
                .populationSize(populationSize)
                .explorationMode(explorationMode)
                .modelComplexityUnits(modelComplexity)
                .estimatedWorkload(workload)
                .workloadLimit(MAX_EFFECTIVE_WORK)
                .accepted(workload <= MAX_EFFECTIVE_WORK)
                .maxAcceptedIterations(maxAcceptedIterations(
                        pathLength, populationSize, modelComplexity))
                .build();
    }

    @Override
    public String getCurrentModelFingerprint(Long userId) {
        ModelInputSnapshot snapshot = boardDataConverter.getModelInputSnapshot(userId);
        modelComplexityUnits(snapshot);
        return modelFingerprint(snapshot);
    }

    @Override
    public Long submit(Long userId, FuzzRequestDto request) {
        NormalizedRequest normalized = validateAndNormalize(request);
        SubmissionCapacityPermit capacityPermit = acquireSubmissionCapacity();
        LocalFuzzExecution localExecution = null;
        Long taskId = null;
        boolean executorAccepted = false;
        try {
            ModelInputSnapshot snapshot = boardDataConverter.getModelInputSnapshot(userId);
            modelComplexityUnits(snapshot);
            validateTargetSpecifications(normalized.targetSpecIds(), snapshot.specifications());
            validateEffectiveWorkload(normalized, snapshot);
            LocalDateTime capturedAt = LocalDateTime.now();
            ModelRunSnapshotDto modelSnapshot = ModelRunSnapshotDto.captured(
                    capturedAt,
                    snapshot.devices().size(),
                    snapshot.rules().size(),
                    snapshot.specifications().size(),
                    snapshot.environmentVariables().size(),
                    snapshot.templateManifests().size());
            modelSnapshot.setModelFingerprint(modelFingerprint(snapshot));
            taskId = createTask(userId, normalized, snapshot, modelSnapshot);
            localExecution = new LocalFuzzExecution(taskId, userId, capacityPermit);
            LocalFuzzExecution existing = localExecutions.putIfAbsent(taskId, localExecution);
            if (existing != null) {
                throw new IllegalStateException("Duplicate local fuzz task execution " + taskId);
            }
            if (taskRepository.findStatusById(taskId)
                    .filter(status -> status == FuzzTaskPo.TaskStatus.PENDING)
                    .isEmpty()
                    || !localExecution.isQueued()) {
                localExecution.requestStop();
                return taskId;
            }
            try {
                fuzzTaskExecutor.execute(localExecution.futureTask);
                executorAccepted = true;
                localExecution.purgeIfCancelledAfterDispatch();
            } catch (TaskRejectedException e) {
                localExecution.requestStop();
                throw new ServiceUnavailableException(
                        "Counterexample search is busy, please retry later", e);
            }
            return taskId;
        } catch (RuntimeException e) {
            if (taskId != null && !executorAccepted
                    && !cleanupUndispatchedTask(userId, taskId, e)) {
                throw new AsyncTaskDispatchOutcomeUnknownException("fuzz", taskId, e);
            }
            throw e;
        } finally {
            if (localExecution == null) {
                capacityPermit.release();
            } else if (!executorAccepted && localExecution.isQueued()) {
                localExecution.requestStop();
            }
        }
    }

    private Long createTask(Long userId,
                            NormalizedRequest request,
                            ModelInputSnapshot snapshot,
                            ModelRunSnapshotDto modelSnapshot) {
        return transactionTemplate.execute(status -> {
            requireActiveUserForPersistence(userId);
            long storedTaskCount = taskRepository.countByUserId(userId);
            if (storedTaskCount >= maxStoredTasksPerUser) {
                throw new FuzzTaskStorageQuotaExceededException(
                        storedTaskCount, maxStoredTasksPerUser);
            }
            long activeTaskCount = taskRepository.countByUserIdAndStatusIn(userId, ACTIVE_STATUSES);
            if (activeTaskCount >= maxActiveTasksPerUser) {
                throw new FuzzTaskQuotaExceededException(
                        activeTaskCount, maxActiveTasksPerUser);
            }
            if (request.explorationMode() == FuzzExplorationMode.PAPER_COMPATIBLE
                    && !paperDomainFingerprint(snapshot, request.pathLength())
                    .equals(request.paperDomainFingerprint())) {
                throw new ValidationException(
                        "paperDomainFingerprint",
                        "Board changed after the random-state input preview; refresh the preview and retry");
            }
            String frozenSnapshotJson = serializeFrozenSnapshot(snapshot, "request");
            LocalDateTime createdAt = databaseNow();
            FuzzTaskPo task = FuzzTaskPo.builder()
                    .userId(userId)
                    .initiator(RunInitiatorResolver.current())
                    .status(FuzzTaskPo.TaskStatus.PENDING)
                    .createdAt(createdAt)
                    .progress(0)
                    .progressStage(TaskProgressStage.QUEUED)
                    .targetSpecIdsJson(JsonUtils.toJson(request.targetSpecIds()))
                    .maxIterations(request.maxIterations())
                    .pathLength(request.pathLength())
                    .populationSize(request.populationSize())
                    .seed(request.seed())
                    .explorationMode(request.explorationMode())
                    .modelInputSnapshotJson(frozenSnapshotJson)
                    .modelSnapshotJson(JsonUtils.toJson(modelSnapshot))
                    .findingCount(0)
                    .workerId(workerId)
                    .leaseExpiresAt(createdAt.plus(TASK_LEASE_DURATION))
                    .build();
            return taskRepository.save(Objects.requireNonNull(task)).getId();
        });
    }

    private SubmissionCapacityPermit acquireSubmissionCapacity() {
        if (!globalTaskCapacity.tryAcquire()) {
            throw new ServiceUnavailableException(
                    "Counterexample search is at task capacity; retry after an active task finishes");
        }
        return new SubmissionCapacityPermit(globalTaskCapacity);
    }

    private LocalDateTime databaseNow() {
        return Objects.requireNonNull(
                taskRepository.currentDatabaseTime(),
                "Database current timestamp must not be null");
    }

    private void purgeCancelledFuzzTasks() {
        try {
            ThreadPoolExecutor executor = fuzzTaskExecutor.getThreadPoolExecutor();
            if (executor != null) executor.purge();
        } catch (RuntimeException e) {
            log.warn("Could not purge cancelled fuzz tasks from the local executor queue", e);
        }
    }

    private boolean cleanupUndispatchedTask(Long userId, Long taskId, RuntimeException failure) {
        try {
            int deleted = taskRepository.deleteUndispatchedTask(
                    taskId, userId, workerId, FuzzTaskPo.TaskStatus.PENDING);
            if (deleted == 1) return true;
            boolean absent = taskRepository.findByIdAndUserId(taskId, userId).isEmpty();
            if (!absent) {
                log.error("Could not remove fuzz task {} after failure before dispatch", taskId);
            }
            return absent;
        } catch (RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
            log.error("Could not remove fuzz task {} after failure before dispatch",
                    taskId, cleanupError);
            return false;
        }
    }

    private void runTask(Long userId, Long taskId) {
        FuzzTaskPo task = null;
        try {
            registerRunningTask(taskId, Thread.currentThread());
            updateTaskProgress(taskId, 0, TaskProgressStage.STARTING);
            if (isTaskCancelled(taskId)) return;

            TaskLeaseRenewal.LeaseUpdateResult start = TaskLeaseRenewal.updateWithConfirmation(
                    transactionTemplate,
                    () -> taskRepository.findByIdForUpdate(taskId),
                    this::databaseNow,
                    (lockedTask, currentTime) -> taskRepository.startTaskIfStillPending(
                            taskId,
                            FuzzTaskPo.TaskStatus.RUNNING,
                            currentTime,
                            workerId,
                            currentTime,
                            currentTime.plus(TASK_LEASE_DURATION),
                            serializeCheckLogs(List.of("Counterexample search task started")),
                            FuzzTaskPo.TaskStatus.PENDING));
            if (start.updated() == 0) {
                log.info("Fuzz task {} is no longer pending; skipping execution", taskId);
                return;
            }
            if (!TaskLeaseRenewal.completedBeforeTtl(start.confirmationStartedNanos(), TASK_LEASE_DURATION)) {
                log.warn("Fuzz task {} lease expired before its start was committed", taskId);
                return;
            }
            LocalFuzzExecution localExecution = localExecutions.get(taskId);
            if (localExecution != null) {
                localExecution.leaseConfirmation.confirmAt(start.confirmationStartedNanos());
            }
            task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                log.error("Fuzz task {} disappeared after it started", taskId);
                return;
            }

            NormalizedRequest request = executionRequest(task);
            ModelInputSnapshot frozenSnapshot = FuzzModelInputSnapshotCodec.decode(
                    task.getModelInputSnapshotJson());
            FuzzEngineInput engineInput = new FuzzEngineInput(frozenSnapshot, new FuzzEngineConfig(
                    request.targetSpecIds(), request.maxIterations(), request.pathLength(),
                    request.populationSize(), request.seed(), request.explorationMode()));

            updateTaskProgress(taskId, 1, TaskProgressStage.PREPARING_EXPLORATION);
            BooleanSupplier cancelled = cancellationSignal(taskId);
            FuzzEngineResult result = fuzzEngine.run(
                    engineInput,
                    (percent, message) -> reportEngineProgress(taskId, percent, message),
                    cancelled,
                    // Monotonic clock, not the database clock: this bounds how long *this* worker computes,
                    // which is a local concern, and a wall-clock jump must not shorten or extend a search.
                    // Kept separate from `cancelled` so the engine settles it as a bounded budget rather than
                    // as a user cancellation — see FuzzEngine.run's javadoc.
                    deadlineSignal(System.nanoTime(), MAX_SEARCH_WALL_CLOCK));

            if (cancelled.getAsBoolean() || result != null
                    && result.outcome() == FuzzEngineOutcome.CANCELLED) {
                atomicCancelTask(taskId, currentTaskTime());
                return;
            }

            validateEngineResult(
                    result,
                    request.pathLength(),
                    request.maxIterations(),
                    request.populationSize());
            FuzzEligibilityDto eligibility = toEligibility(
                    result.eligibility(), request.targetSpecIds(), engineInput.snapshot().specifications());
            List<String> limitations = normalizeLimitations(
                    result.limitations(), request.explorationMode());
            FuzzOutcome outcome = toOutcome(result.outcome());
            List<FuzzFindingPo> findings = toFindingEntities(userId, taskId, result);

            updateTaskProgress(taskId, 95, TaskProgressStage.PERSISTING_RESULT);
            boolean completed = completeTaskAndFindings(
                    task, userId, result, outcome, eligibility, limitations, findings);
            if (!completed && !isCancelledOrTerminal(taskId)) {
                failTask(task,
                        "Counterexample search completed, but its result could not be saved");
            }
        } catch (Exception e) {
            if (isTaskCancelled(taskId) || Thread.currentThread().isInterrupted()) {
                log.info("Fuzz task {} cancelled while the engine was stopping", taskId);
            } else {
                log.error("Fuzz task {} failed", taskId, e);
                String message = "Counterexample search failed: " + publicTaskFailureMessage(e);
                markTaskFailedSafely(taskId, task, message);
            }
        } finally {
            if (removeCancelledMark(taskId) && task != null) {
                handleCancellation(task);
            }
            removeRunningTask(taskId);
            removeTaskProgress(taskId);
            try {
                taskRepository.releaseOwnedActiveLease(
                        taskId,
                        workerId,
                        databaseNow().minusSeconds(1),
                        ACTIVE_STATUSES);
            } catch (RuntimeException e) {
                log.warn("Could not release fuzz task {} lease; it will expire naturally", taskId, e);
            }
        }
    }

    private void markTaskFailedSafely(Long taskId, FuzzTaskPo task, String message) {
        try {
            if (task == null) {
                failTaskById(taskId, message);
            } else {
                failTask(task, message);
            }
        } catch (RuntimeException persistenceError) {
            log.error("Could not persist failure state for fuzz task {}; its lease will expire",
                    taskId, persistenceError);
        }
    }

    private void reportEngineProgress(Long taskId, int percent, String message) {
        if (isTaskCancelled(taskId) || Thread.currentThread().isInterrupted()) return;
        try {
            int bounded = Math.min(90, Math.max(1, percent));
            updateTaskProgress(taskId, bounded, TaskProgressStage.EXPLORING_CANDIDATES);
        } catch (RuntimeException e) {
            log.warn("Could not persist progress for fuzz task {}", taskId, e);
        }
    }

    /**
     * A predicate that becomes true once {@code budget} has elapsed since {@code startedAtNanos}.
     *
     * <p>Compares {@link System#nanoTime()} <em>differences</em>, so a system-clock adjustment during a
     * search cannot shorten or extend it. The difference form is also what keeps a large origin from
     * overflowing into a permanent "not expired", which an absolute {@code origin + budget} deadline would;
     * it is not unconditionally overflow-proof, but {@code startedAtNanos} always comes from
     * {@code nanoTime()} moments earlier on this worker, so the pathological origin cannot arise.</p>
     */
    BooleanSupplier deadlineSignal(long startedAtNanos, Duration budget) {
        long budgetNanos = budget.toNanos();
        return () -> System.nanoTime() - startedAtNanos >= budgetNanos;
    }

    BooleanSupplier cancellationSignal(Long taskId) {
        AtomicBoolean databaseCancellation = new AtomicBoolean(false);
        AtomicLong nextDatabasePoll = new AtomicLong(0L);
        return () -> {
            if (isTaskCancelled(taskId) || Thread.currentThread().isInterrupted()
                    || databaseCancellation.get()) {
                return true;
            }
            long now = System.nanoTime();
            long nextPoll = nextDatabasePoll.get();
            if (now < nextPoll || !nextDatabasePoll.compareAndSet(
                    nextPoll, now + CANCELLATION_DB_POLL_NANOS)) {
                return false;
            }
            boolean noLongerRunning = taskRepository.findStatusById(taskId)
                    .map(status -> status != FuzzTaskPo.TaskStatus.RUNNING)
                    .orElse(true);
            if (noLongerRunning) databaseCancellation.set(true);
            return noLongerRunning;
        };
    }

    private boolean completeTaskAndFindings(FuzzTaskPo task,
                                            Long userId,
                                            FuzzEngineResult result,
                                            FuzzOutcome outcome,
                                            FuzzEligibilityDto eligibility,
                                             List<String> limitations,
                                             List<FuzzFindingPo> findings) {
        String eligibilityJson = JsonUtils.toJson(eligibility);
        String limitationsJson = JsonUtils.toJson(limitations);
        requireBoundedRunMetadata(eligibilityJson, limitationsJson);
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (isTaskCancelled(task.getId()) || Thread.currentThread().isInterrupted()) {
                status.setRollbackOnly();
                return false;
            }
            if (userRepository.findByIdForUpdate(userId).isEmpty()) {
                status.setRollbackOnly();
                return false;
            }

            taskRepository.findByIdForUpdate(task.getId());
            LocalDateTime evidenceCreatedAt = databaseNow();
            findings.forEach(finding -> finding.setCreatedAt(evidenceCreatedAt));
            findingRepository.saveAllAndFlush(findings);
            if (isTaskCancelled(task.getId()) || Thread.currentThread().isInterrupted()) {
                status.setRollbackOnly();
                return false;
            }

            LocalDateTime completedAt = databaseNow();
            Long processingTimeMs = task.getStartedAt() == null ? null
                    : Duration.between(task.getStartedAt(), completedAt).toMillis();
            List<String> logs = List.of(
                    "Counterexample search completed",
                    "Outcome: " + outcome,
                    "Generated paths: " + result.generatedPaths(),
                    "Findings: " + findings.size());
            int updated = taskRepository.completeTaskIfRunning(
                    task.getId(),
                    FuzzTaskPo.TaskStatus.COMPLETED,
                    completedAt,
                    processingTimeMs,
                    outcome,
                    result.effectiveSeed(),
                    result.iterations(),
                    result.generatedPaths(),
                    result.elapsedMs(),
                    eligibilityJson,
                    limitationsJson,
                    findings.size(),
                    serializeCheckLogs(logs),
                    FuzzTaskPo.TaskStatus.RUNNING,
                    workerId,
                    completedAt);
            if (updated == 0) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    private List<FuzzFindingPo> toFindingEntities(Long userId,
                                                  Long taskId,
                                                  FuzzEngineResult result) {
        List<FuzzFindingPo> entities = new ArrayList<>();
        long aggregateEvidenceBytes = 0L;
        for (FuzzFinding finding : result.findings()) {
            SpecificationDto specification = finding.specification();
            List<FuzzInputEventDto> events = finding.inputEvents().stream()
                    .map(this::toInputEventDto)
                    .toList();
            String specificationJson = JsonUtils.toJson(specification);
            String statesJson = JsonUtils.toJson(finding.states());
            String inputEventsJson = JsonUtils.toJson(events);
            long findingEvidenceBytes;
            try {
                findingEvidenceBytes = Math.addExact(
                        Math.addExact(utf8Length(specificationJson), utf8Length(statesJson)),
                        utf8Length(inputEventsJson));
                aggregateEvidenceBytes = Math.addExact(
                        aggregateEvidenceBytes, findingEvidenceBytes);
            } catch (ArithmeticException e) {
                throw new EvidenceLimitExceededException();
            }
            if (findingEvidenceBytes > MAX_FINDING_EVIDENCE_BYTES
                    || aggregateEvidenceBytes > MAX_RUN_EVIDENCE_BYTES) {
                throw new EvidenceLimitExceededException();
            }
            entities.add(FuzzFindingPo.builder()
                    .userId(userId)
                    .fuzzTaskId(taskId)
                    .violatedSpecId(specification.getId())
                    .violatedSpecJson(specificationJson)
                    .firstViolationStep(finding.firstViolationStep())
                    .statesJson(statesJson)
                    .inputEventsJson(inputEventsJson)
                    .seed(result.effectiveSeed())
                    .stateCount(finding.states().size())
                    .build());
        }
        return entities;
    }

    private FuzzInputEventDto toInputEventDto(FuzzInputEvent event) {
        return FuzzInputEventDto.builder()
                .step(event.step())
                .kind(event.kind().name())
                .targetId(event.targetId())
                .property(event.property())
                .value(event.value())
                .source(event.source().name())
                .build();
    }

    private FuzzEligibilityDto toEligibility(List<FuzzSpecEligibility> engineEligibility,
                                             List<String> targetSpecIds,
                                             List<SpecificationDto> allSpecifications) {
        LinkedHashSet<String> eligibleIds = new LinkedHashSet<>();
        Map<String, String> eligibleLabels = new LinkedHashMap<>();
        List<FuzzIneligibleSpecDto> ineligible = new ArrayList<>();
        for (FuzzSpecEligibility item : engineEligibility) {
            SpecificationDto specification = item.specification();
            String specId = specification == null ? null : specification.getId();
            String label = boundedSingleLine(
                    specificationLabel(specification), specId, MAX_ELIGIBILITY_LABEL_CHARS);
            if (item.supported()) {
                if (specId != null && eligibleIds.add(specId)) {
                    eligibleLabels.put(specId, label);
                }
            } else {
                ineligible.add(FuzzIneligibleSpecDto.builder()
                        .specId(specId)
                        .specificationLabel(label)
                        .reasonCode(item.reasonCode())
                        .reason(boundedSingleLine(
                                item.reason(), "Unsupported for counterexample exploration",
                                MAX_ELIGIBILITY_REASON_CHARS))
                        .build());
            }
        }
        int requestedCount = targetSpecIds.isEmpty() ? allSpecifications.size() : targetSpecIds.size();
        return FuzzEligibilityDto.builder()
                .eligibleSpecIds(List.copyOf(eligibleIds))
                .eligibleSpecLabels(Map.copyOf(eligibleLabels))
                .ineligibleSpecs(List.copyOf(ineligible))
                .requestedSpecCount(requestedCount)
                .eligibleSpecCount(eligibleIds.size())
                .build();
    }

    private String specificationLabel(SpecificationDto specification) {
        if (specification == null) return null;
        if (specification.getTemplateLabel() != null && !specification.getTemplateLabel().isBlank()) {
            return specification.getTemplateLabel();
        }
        if (specification.getFormula() != null && !specification.getFormula().isBlank()) {
            return specification.getFormula();
        }
        return specification.getId();
    }

    private FuzzOutcome toOutcome(FuzzEngineOutcome outcome) {
        return switch (outcome) {
            case FINDINGS_FOUND -> FuzzOutcome.FOUND_VIOLATION;
            case BUDGET_EXHAUSTED -> FuzzOutcome.BUDGET_EXHAUSTED;
            case NO_ELIGIBLE_SPECIFICATIONS -> FuzzOutcome.INCONCLUSIVE;
            case CANCELLED -> throw new IllegalStateException("Cancelled engine result cannot be completed");
        };
    }

    void validateEngineResult(
            FuzzEngineResult result,
            int pathLength,
            int maxIterations,
            int populationSize) {
        if (pathLength < 1 || pathLength > 50
                || maxIterations < 1 || maxIterations > 5_000
                || populationSize < 1 || populationSize > 50) {
            throw new IllegalStateException("Fuzz engine result has no valid execution budget");
        }
        if (result == null || result.outcome() == null) {
            throw new IllegalStateException("Fuzz engine returned no outcome");
        }
        if (result.effectiveSeed() < 0 || result.effectiveSeed() > FuzzRequestDto.JS_SAFE_SEED_MAX) {
            throw new IllegalStateException("Fuzz engine returned an out-of-range effective seed");
        }
        if (result.iterations() < 0 || result.generatedPaths() < 0 || result.elapsedMs() < 0) {
            throw new IllegalStateException("Fuzz engine returned negative execution statistics");
        }
        long maximumGeneratedPaths;
        try {
            maximumGeneratedPaths = Math.multiplyExact(
                    (long) result.iterations(), populationSize);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("Fuzz engine returned overflowing execution statistics", e);
        }
        if (result.iterations() > maxIterations
                || (result.iterations() == 0) != (result.generatedPaths() == 0)
                || (result.iterations() > 0 && result.generatedPaths() < result.iterations())
                || result.generatedPaths() > maximumGeneratedPaths) {
            throw new IllegalStateException("Fuzz engine returned inconsistent execution statistics");
        }
        if (result.outcome() == FuzzEngineOutcome.FINDINGS_FOUND && result.findings().isEmpty()) {
            throw new IllegalStateException("Fuzz engine reported findings without finding data");
        }
        if (result.outcome() != FuzzEngineOutcome.FINDINGS_FOUND && !result.findings().isEmpty()) {
            throw new IllegalStateException("Fuzz engine returned findings for a non-finding outcome");
        }
        Set<String> findingSpecIds = new LinkedHashSet<>();
        for (FuzzFinding finding : result.findings()) {
            if (finding == null || finding.specification() == null
                    || finding.specification().getId() == null
                    || finding.specification().getId().isBlank()
                    || finding.specification().getId().length() > MAX_STABLE_CODE_CHARS) {
                throw new IllegalStateException("Fuzz engine returned a finding without a specification");
            }
            if (!findingSpecIds.add(finding.specification().getId())) {
                throw new IllegalStateException("Fuzz engine returned duplicate findings for a specification");
            }
            if (finding.firstViolationStep() < 0 || finding.states().isEmpty()
                    || finding.states().size() > pathLength
                    || finding.firstViolationStep() != finding.states().size() - 1) {
                throw new IllegalStateException("Fuzz engine returned incomplete finding trace data");
            }
            validateInputEventSequence(finding);
        }
        if (result.eligibility().stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Fuzz engine returned invalid eligibility data");
        }
        Set<String> eligibilitySpecIds = new LinkedHashSet<>();
        Set<String> eligibleSpecIds = new LinkedHashSet<>();
        for (FuzzSpecEligibility eligibility : result.eligibility()) {
            if (eligibility.specification() == null
                    || eligibility.specification().getId() == null
                    || eligibility.specification().getId().isBlank()
                    || eligibility.specification().getId().length() > MAX_STABLE_CODE_CHARS) {
                throw new IllegalStateException("Fuzz engine returned eligibility without a specification");
            }
            String specId = eligibility.specification().getId();
            if (!eligibilitySpecIds.add(specId)) {
                throw new IllegalStateException("Fuzz engine returned duplicate specification eligibility");
            }
            if (!eligibility.supported()
                    && (eligibility.reasonCode() == null || eligibility.reasonCode().isBlank()
                    || eligibility.reasonCode().length() > MAX_STABLE_CODE_CHARS
                    || !STABLE_CODE.matcher(eligibility.reasonCode()).matches()
                    || eligibility.reason() == null || eligibility.reason().isBlank())) {
                throw new IllegalStateException("Fuzz engine returned incomplete ineligibility details");
            }
            if (eligibility.supported()) eligibleSpecIds.add(specId);
        }
        if (result.outcome() != FuzzEngineOutcome.CANCELLED && eligibilitySpecIds.isEmpty()) {
            throw new IllegalStateException("Fuzz engine returned no specification eligibility");
        }
        if (result.outcome() == FuzzEngineOutcome.NO_ELIGIBLE_SPECIFICATIONS
                && !eligibleSpecIds.isEmpty()) {
            throw new IllegalStateException("Fuzz engine reported no eligible specifications inconsistently");
        }
        if (result.outcome() != FuzzEngineOutcome.NO_ELIGIBLE_SPECIFICATIONS
                && result.outcome() != FuzzEngineOutcome.CANCELLED
                && eligibleSpecIds.isEmpty()) {
            throw new IllegalStateException("Fuzz engine returned a search outcome without eligible specifications");
        }
        if (!eligibleSpecIds.containsAll(findingSpecIds)) {
            throw new IllegalStateException("Fuzz engine returned a finding for an ineligible specification");
        }
    }

    private void validateInputEventSequence(FuzzFinding finding) {
        int previousStep = -1;
        int previousSourceOrder = -1;
        for (FuzzInputEvent event : finding.inputEvents()) {
            if (event == null || event.kind() == null || event.source() == null
                    || !hasText(event.targetId()) || !hasText(event.property()) || !hasText(event.value())) {
                throw new IllegalStateException("Fuzz engine returned an invalid input event");
            }
            if (event.step() < 0 || event.step() > finding.firstViolationStep()) {
                throw new IllegalStateException("Fuzz engine returned an input event outside the finding prefix");
            }
            if (event.source() == FuzzInputEventSource.RANDOM_INITIAL_STATE && event.step() != 0) {
                throw new IllegalStateException("Fuzz engine returned a random initial-state event after step 0");
            }
            int sourceOrder = inputEventSourceOrder(event.source());
            if (event.step() < previousStep
                    || (event.step() == previousStep && sourceOrder < previousSourceOrder)) {
                throw new IllegalStateException("Fuzz engine returned input events out of causal order");
            }
            if (event.step() != previousStep) {
                previousSourceOrder = sourceOrder;
            } else {
                previousSourceOrder = Math.max(previousSourceOrder, sourceOrder);
            }
            previousStep = event.step();
        }
    }

    private int inputEventSourceOrder(FuzzInputEventSource source) {
        return switch (source) {
            case RANDOM_INITIAL_STATE -> 0;
            case SEED_EVENT -> 1;
            case MODEL_CHOICE -> 2;
        };
    }

    List<String> normalizeLimitations(
            Collection<String> limitations, FuzzExplorationMode explorationMode) {
        if (limitations == null) {
            throw new IllegalStateException("Fuzz engine returned no limitation data");
        }
        if (limitations.size() > FuzzMetadataPolicy.MAX_LIMITATION_CODES) {
            throw new IllegalStateException("Fuzz engine returned too many limitation codes");
        }
        List<String> normalized = new ArrayList<>(limitations.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String code : limitations) {
            if (code == null || code.length() > MAX_STABLE_CODE_CHARS
                    || !STABLE_CODE.matcher(code).matches()) {
                throw new IllegalStateException("Fuzz engine returned an invalid limitation code");
            }
            if (!seen.add(code)) {
                throw new IllegalStateException("Fuzz engine returned duplicate limitation codes");
            }
            normalized.add(code);
        }
        if (explorationMode == null
                || !seen.containsAll(FuzzLimitationContract.requiredCodes(explorationMode))) {
            throw new IllegalStateException("Fuzz engine omitted required semantic limitation codes");
        }
        return List.copyOf(normalized);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private NormalizedRequest validateAndNormalize(FuzzRequestDto request) {
        if (request == null) {
            throw new ValidationException(
                    "request", "Counterexample search request cannot be null");
        }
        int maxIterations = requirePositive(request.getMaxIterations(),
                "maxIterations", "Maximum iterations must be at least 1");
        int pathLength = requirePositive(request.getPathLength(),
                "pathLength", "Path length must be at least 1");
        int populationSize = requirePositive(request.getPopulationSize(),
                "populationSize", "Population size must be at least 1");
        // Overflow guard on the raw step count, kept ahead of the per-field range checks on purpose so an
        // absurd combination is reported against the request as a whole rather than against whichever single
        // field happens to be examined first (pinned by submit_rejectsOverflowingSearchBudgetBeforeReadingTheBoard).
        // It deliberately no longer compares against MAX_EFFECTIVE_WORK: that constant now bounds the
        // complexity-weighted workload, and using one value for both left the complexity factor no headroom
        // to trim — it consumed the entire budget, so every realistic board was refused at its own default.
        // Only whether it overflows matters here, so the product itself is unused.
        try {
            Math.multiplyExact(
                    Math.multiplyExact((long) maxIterations, pathLength), populationSize);
        } catch (ArithmeticException e) {
            throw new ValidationException("request",
                    "Iteration, path length, and population budgets are too large in combination");
        }
        maxIterations = requireRange(maxIterations, 1, 5_000,
                "maxIterations", "Maximum iterations must be between 1 and 5000");
        pathLength = requireRange(pathLength, 1, 50,
                "pathLength", "Path length must be between 1 and 50");
        populationSize = requireRange(populationSize, 1, 50,
                "populationSize", "Population size must be between 1 and 50");
        Long seed = request.getSeed();
        if (seed != null && (seed < 0 || seed > FuzzRequestDto.JS_SAFE_SEED_MAX)) {
            throw new ValidationException("seed", "Seed must be between 0 and "
                    + FuzzRequestDto.JS_SAFE_SEED_MAX);
        }
        List<String> targetSpecIds = normalizeTargetSpecIds(request.getTargetSpecIds());
        FuzzExplorationMode explorationMode = request.getExplorationMode() == null
                ? FuzzExplorationMode.BOARD_SNAPSHOT
                : request.getExplorationMode();
        String paperDomainFingerprint = request.getPaperDomainFingerprint();
        if (explorationMode == FuzzExplorationMode.PAPER_COMPATIBLE) {
            if (paperDomainFingerprint == null
                    || !MODEL_FINGERPRINT.matcher(paperDomainFingerprint).matches()) {
                throw new ValidationException(
                        "paperDomainFingerprint",
                        "A current random-state input-range fingerprint is required");
            }
        } else if (paperDomainFingerprint != null) {
            throw new ValidationException(
                    "paperDomainFingerprint",
                    "Input-range data is only valid with the random-state search strategy");
        }
        return new NormalizedRequest(
                targetSpecIds,
                maxIterations,
                pathLength,
                populationSize,
                seed,
                explorationMode,
                paperDomainFingerprint);
    }

    String modelFingerprint(ModelInputSnapshot snapshot) {
        return modelFingerprint.modelFingerprint(snapshot);
    }

    String paperDomainFingerprint(ModelInputSnapshot snapshot, int pathLength) {
        return modelFingerprint.paperDomainFingerprint(snapshot, pathLength);
    }

    private int requireRange(Integer value, int minimum, int maximum, String field, String message) {
        if (value == null || value < minimum || value > maximum) {
            throw new ValidationException(field, message);
        }
        return value;
    }

    private int requirePositive(Integer value, String field, String message) {
        if (value == null || value < 1) {
            throw new ValidationException(field, message);
        }
        return value;
    }

    private List<String> normalizeTargetSpecIds(List<String> targetSpecIds) {
        if (targetSpecIds == null || targetSpecIds.isEmpty()) return List.of();
        if (targetSpecIds.size() > 100) {
            throw new ValidationException("targetSpecIds", "At most 100 target specifications can be selected");
        }
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < targetSpecIds.size(); i++) {
            String value = targetSpecIds.get(i);
            String specId = value == null ? null : value.trim();
            if (specId == null || specId.isEmpty()) {
                throw new ValidationException("targetSpecIds[" + i + "]", "Target specification ID cannot be blank");
            }
            if (specId.length() > 100) {
                throw new ValidationException("targetSpecIds[" + i + "]",
                        "Target specification ID must be at most 100 characters");
            }
            if (!seen.add(specId)) {
                throw new ValidationException("targetSpecIds", "Target specification IDs must be unique");
            }
            normalized.add(specId);
        }
        return List.copyOf(normalized);
    }

    private void validateTargetSpecifications(List<String> targetSpecIds,
                                              List<SpecificationDto> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            throw new ValidationException("targetSpecIds",
                    "The Board must contain at least one specification before starting a counterexample search");
        }
        if (targetSpecIds.isEmpty()) {
            if (specifications.size() > 100) {
                throw new ValidationException("targetSpecIds",
                        "The board has more than 100 specifications; select at most 100 explicitly");
            }
            return;
        }
        Set<String> available = specifications.stream()
                .filter(Objects::nonNull)
                .map(SpecificationDto::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> missing = targetSpecIds.stream().filter(id -> !available.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new ValidationException("targetSpecIds",
                    "Unknown target specification IDs: " + String.join(", ", missing));
        }
    }

    private void validateEffectiveWorkload(
            NormalizedRequest request,
            ModelInputSnapshot snapshot) {
        long modelComplexity = modelComplexityUnits(snapshot, request.explorationMode());
        long effectiveWork = effectiveWorkload(
                request.maxIterations(),
                request.pathLength(),
                request.populationSize(),
                modelComplexity);
        if (effectiveWork > MAX_EFFECTIVE_WORK) {
            // The message carries the multiplier and the computed ceiling because the three numbers the
            // caller chose cannot explain the rejection on their own: a caller seeing only the limit has no
            // way to know the Board contributed a factor, and an AI caller has nothing to correct with.
            int admissibleIterations = maxAcceptedIterations(
                    request.pathLength(), request.populationSize(), modelComplexity);
            throw new ValidationException("request",
                    "Counterexample search workload " + effectiveWork + " exceeds the "
                            + MAX_EFFECTIVE_WORK + " evaluation limit: "
                            + request.maxIterations() + " iterations x " + request.pathLength()
                            + " path length x " + request.populationSize()
                            + " candidate paths x " + Math.max(1L, modelComplexity)
                            + " board complexity. "
                            + (admissibleIterations > 0
                                    ? "At this path length and population size, at most "
                                            + admissibleIterations + " iterations are admissible."
                                    : "Even one iteration exceeds the limit at this path length and "
                                            + "population size; reduce one of those instead."));
        }
    }

    /**
     * Largest admissible {@code maxIterations} for the other three factors, clamped to its field range.
     *
     * <p>Returns 0 when even a single iteration exceeds the limit, which tells the client the path length or
     * population size has to come down instead — a distinction "lower something" cannot convey.</p>
     */
    private int maxAcceptedIterations(int pathLength, int populationSize, long modelComplexity) {
        long perIteration;
        try {
            perIteration = Math.multiplyExact(
                    Math.multiplyExact((long) pathLength, populationSize),
                    Math.max(1L, modelComplexity));
        } catch (ArithmeticException exception) {
            return 0;
        }
        if (perIteration <= 0L) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(5_000L, MAX_EFFECTIVE_WORK / perIteration));
    }

    private long effectiveWorkload(
            int maxIterations,
            int pathLength,
            int populationSize,
            long modelComplexity) {
        try {
            long baseWork = Math.multiplyExact(
                    Math.multiplyExact((long) maxIterations, pathLength),
                    populationSize);
            return Math.multiplyExact(baseWork, Math.max(1L, modelComplexity));
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Validates the frozen snapshot's structure and returns its cost for the default exploration mode.
     *
     * <p>Callers that only need the validation keep using this; the workload paths pass the mode so a
     * {@code BOARD_SNAPSHOT} run is not charged for the paper monitor's predecessor walk.</p>
     */
    long modelComplexityUnits(ModelInputSnapshot snapshot) {
        return modelComplexityUnits(snapshot, FuzzExplorationMode.BOARD_SNAPSHOT);
    }

    long modelComplexityUnits(ModelInputSnapshot snapshot, FuzzExplorationMode explorationMode) {
        if (snapshot == null) {
            throw new ValidationException("request", "Frozen Board snapshot is missing");
        }
        StructureCounter counter = new StructureCounter();
        counter.addCollection("devices", snapshot.devices());
        counter.addCollection("environmentVariables", snapshot.environmentVariables());
        counter.addCollection("rules", snapshot.rules());
        counter.addCollection("specifications", snapshot.specifications());
        long modeTransitionUnits = 0L;
        long apiModeUnits = 0L;
        long ruleConditionUnits = 0L;
        long specificationConditionUnits = 0L;
        Map<String, Long> rulesPerTarget = new LinkedHashMap<>();

        Map<String, DeviceManifest> manifestsByName = new LinkedHashMap<>();
        for (Map.Entry<String, DeviceManifest> entry : snapshot.templateManifests().entrySet()) {
            String key = entry.getKey() == null ? null : entry.getKey().trim().toLowerCase(java.util.Locale.ROOT);
            if (key == null || key.isEmpty() || entry.getValue() == null
                    || manifestsByName.putIfAbsent(key, entry.getValue()) != null) {
                throw new ValidationException("request", "Frozen Board contains invalid template manifests");
            }
        }

        for (DeviceVerificationDto device : snapshot.devices()) {
            if (device == null || device.getTemplateName() == null || device.getTemplateName().isBlank()) {
                throw new ValidationException("request", "Frozen Board contains an invalid device");
            }
            DeviceManifest manifest = manifestsByName.get(
                    device.getTemplateName().trim().toLowerCase(java.util.Locale.ROOT));
            if (manifest == null) {
                throw new ValidationException("request",
                        "Frozen Board device references a missing template manifest");
            }
            counter.addCollection("deviceVariables", device.getVariables());
            // Bounded but not charged: FuzzModel never reads privacies (it writes empty lists at
            // FuzzModel.java:1471 and :1499), so this cannot contribute per-step work.
            counter.boundOnly("devicePrivacies", device.getPrivacies());
            addManifestComplexity(counter, manifest);
            modeTransitionUnits = addOperationalUnits(
                    modeTransitionUnits,
                    multiplyOperationalUnits(
                            safeList(manifest.getModes()).size(),
                            safeList(manifest.getTransitions()).size()));
            apiModeUnits = addOperationalUnits(
                    apiModeUnits,
                    multiplyOperationalUnits(
                            safeList(manifest.getModes()).size(),
                            safeList(manifest.getApis()).size()));
        }

        for (RuleDto rule : snapshot.rules()) {
            if (rule == null) {
                throw new ValidationException("request", "Frozen Board contains a null rule");
            }
            counter.addCollection("ruleConditions", rule.getConditions());
            ruleConditionUnits = addOperationalUnits(
                    ruleConditionUnits, safeList(rule.getConditions()).size());
            String targetId = rule.getCommand() == null || !hasText(rule.getCommand().getDeviceName())
                    ? "<invalid>"
                    : rule.getCommand().getDeviceName().trim();
            rulesPerTarget.put(
                    targetId,
                    addOperationalUnits(rulesPerTarget.getOrDefault(targetId, 0L), 1L));
        }
        for (SpecificationDto specification : snapshot.specifications()) {
            if (specification == null) {
                throw new ValidationException("request", "Frozen Board contains a null specification");
            }
            counter.addCollection("aConditions", specification.getAConditions());
            counter.addCollection("ifConditions", specification.getIfConditions());
            counter.addCollection("thenConditions", specification.getThenConditions());
            specificationConditionUnits = addOperationalUnits(
                    specificationConditionUnits,
                    addOperationalUnits(
                            safeList(specification.getAConditions()).size(),
                            addOperationalUnits(
                                    safeList(specification.getIfConditions()).size(),
                                    safeList(specification.getThenConditions()).size())));
        }

        // The additive guard above bounds stored structure. These products account for
        // the nested loops that dominate each generated state and paper-distance pass.
        long environmentDeviceUnits = multiplyOperationalUnits(
                snapshot.environmentVariables().size(), snapshot.devices().size());
        long ruleCount = snapshot.rules().size();
        long orderedRuleArbitrationUnits = 0L;
        for (long targetRuleCount : rulesPerTarget.values()) {
            orderedRuleArbitrationUnits = addOperationalUnits(
                    orderedRuleArbitrationUnits,
                    multiplyOperationalUnits(targetRuleCount, targetRuleCount));
        }
        // Paper mode only. `previousConditions` is reached solely through
        // PaperMonitorFsm.distanceToViolation, which only FuzzModel.evaluatePaper calls, so a BOARD_SNAPSHOT
        // run performs none of this work — and it was the largest term for every shipped example scene,
        // which is why the default mode's estimate bore almost no relation to what it executes.
        long predecessorRuleUnits = explorationMode == FuzzExplorationMode.PAPER_COMPATIBLE
                ? multiplyOperationalUnits(
                        specificationConditionUnits,
                        addOperationalUnits(ruleCount, ruleConditionUnits))
                : 0L;
        long operationalUnits = counter.total();
        operationalUnits = addOperationalUnits(operationalUnits, environmentDeviceUnits);
        operationalUnits = addOperationalUnits(operationalUnits, modeTransitionUnits);
        operationalUnits = addOperationalUnits(operationalUnits, apiModeUnits);
        operationalUnits = addOperationalUnits(operationalUnits, orderedRuleArbitrationUnits);
        return addOperationalUnits(operationalUnits, predecessorRuleUnits);
    }

    private long multiplyOperationalUnits(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ValidationException(
                    "request", "Counterexample-search operational model complexity is too large");
        }
    }

    private long addOperationalUnits(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ValidationException(
                    "request", "Counterexample-search operational model complexity is too large");
        }
    }

    private void addManifestComplexity(StructureCounter counter, DeviceManifest manifest) {
        counter.addCollection("templateModes", manifest.getModes());
        counter.addCollection("templateInternalVariables", manifest.getInternalVariables());
        counter.addCollection("templateImpactedVariables", manifest.getImpactedVariables());
        counter.addCollection("templateWorkingStates", manifest.getWorkingStates());
        counter.addCollection("templateTransitions", manifest.getTransitions());
        counter.addCollection("templateApis", manifest.getApis());

        for (DeviceManifest.InternalVariable variable : safeList(manifest.getInternalVariables())) {
            if (variable == null) {
                throw new ValidationException("request", "Template contains a null internal variable");
            }
            counter.addCollection("templateVariableValues", variable.getValues());
        }
        for (DeviceManifest.WorkingState state : safeList(manifest.getWorkingStates())) {
            if (state == null) {
                throw new ValidationException("request", "Template contains a null working state");
            }
            counter.addCollection("templateStateDynamics", state.getDynamics());
        }
        for (DeviceManifest.Transition transition : safeList(manifest.getTransitions())) {
            if (transition == null) {
                throw new ValidationException("request", "Template contains a null transition");
            }
            counter.addCollection("templateTransitionAssignments", transition.getAssignments());
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * Bounds every counted collection and, separately, accumulates the ones the engine actually reads.
     *
     * <p>The two jobs are deliberately distinct. Every collection must be bounded, because the whole frozen
     * snapshot is persisted regardless of what the search touches. But a collection the engine never reads
     * must not inflate the cost estimate — {@code devicePrivacies} did, and {@code FuzzModel} only ever
     * writes empty privacy lists. Folding both jobs into one counter meant the only way to stop charging a
     * field was to stop bounding it, trading a cost defect for a missing persistence guard.</p>
     */
    private static final class StructureCounter {
        private long bounded;
        private long charged;

        /** Bounds and charges: the engine's per-step work scales with this collection. */
        private void addCollection(String field, Collection<?> values) {
            charged = sum(charged, bound(field, values), field);
        }

        /** Bounds only: persisted and size-limited, but never read by the search. */
        private void boundOnly(String field, Collection<?> values) {
            bound(field, values);
        }

        private int bound(String field, Collection<?> values) {
            int size = values == null ? 0 : values.size();
            if (size > MAX_FUZZ_COLLECTION_ITEMS) {
                throw new ValidationException("request",
                        field + " exceeds the counterexample-search collection limit of "
                                + MAX_FUZZ_COLLECTION_ITEMS);
            }
            bounded = sum(bounded, size, field);
            return size;
        }

        private long sum(long running, int size, String field) {
            long updated;
            try {
                updated = Math.addExact(running, size);
            } catch (ArithmeticException e) {
                throw new ValidationException(
                        "request", "Counterexample-search model structure is too large");
            }
            if (updated > MAX_MODEL_STRUCTURE_UNITS) {
                throw new ValidationException("request",
                        "Counterexample-search model structure exceeds the "
                                + MAX_MODEL_STRUCTURE_UNITS + " unit limit");
            }
            return updated;
        }

        private long total() {
            return charged;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FuzzTaskDto getTask(Long userId, Long taskId) {
        FuzzTaskSummaryProjection task = taskRepository.findSummaryByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Counterexample search task", taskId));
        return fuzzMapper.toTaskDtoProjection(task);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuzzTaskSummaryDto> getTasks(
            Long userId, List<Long> excludedTaskIds, int page, int size) {
        List<Long> exclusions = normalizeExcludedTaskIds(excludedTaskIds);
        Pageable pageable = pageRequest(page, size, 200);
        List<FuzzTaskSummaryProjection> tasks = exclusions.isEmpty()
                ? taskRepository.findSummaryByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(
                        userId, FuzzTaskPo.TaskStatus.COMPLETED, pageable)
                : taskRepository.findSummaryByUserIdAndStatusNotAndIdNotInOrderByCreatedAtDescIdDesc(
                        userId, FuzzTaskPo.TaskStatus.COMPLETED, exclusions, pageable);
        return tasks.stream().map(fuzzMapper::toTaskSummaryDtoProjection).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int getTaskProgress(Long userId, Long taskId) {
        FuzzTaskProgressProjection task = taskRepository.findProgressByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Counterexample search task", taskId));
        if (task.getProgress() != null) return task.getProgress();
        FuzzTaskPo.TaskStatus status = task.getStatus();
        return status == FuzzTaskPo.TaskStatus.COMPLETED
                || status == FuzzTaskPo.TaskStatus.FAILED
                || status == FuzzTaskPo.TaskStatus.CANCELLED ? 100 : 0;
    }

    @Override
    @Transactional
    public TaskCancellationResultDto cancelTask(Long userId, Long taskId) {
        return super.cancelTask(userId, taskId);
    }

    @Override
    @Transactional
    public void deleteTask(Long userId, Long taskId) {
        FuzzTaskPo task = requireOwnedTask(userId, taskId);
        if (task.getStatus() == FuzzTaskPo.TaskStatus.PENDING
                || task.getStatus() == FuzzTaskPo.TaskStatus.RUNNING) {
            throw new BadRequestException(
                    "An active counterexample search must be cancelled before it can be removed");
        }
        if (task.getStatus() == FuzzTaskPo.TaskStatus.COMPLETED) {
            throw new BadRequestException(
                    "Completed counterexample-search results must be removed from run history");
        }
        taskRepository.delete(task);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuzzRunSummaryDto> getRuns(Long userId, int page, int size) {
        Pageable pageable = pageRequest(page, size, 100);
        List<FuzzTaskSummaryProjection> runs =
                taskRepository.findSummaryByUserIdAndStatusOrderByCreatedAtDescIdDesc(
                userId, FuzzTaskPo.TaskStatus.COMPLETED, pageable);
        if (runs.isEmpty()) return List.of();
        List<Long> runIds = runs.stream().map(FuzzTaskSummaryProjection::getId).toList();
        Map<Long, List<FuzzFindingSummaryProjection>> findingsByRun = findingRepository
                .findSummariesByUserIdAndFuzzTaskIdIn(userId, runIds)
                .stream()
                .collect(Collectors.groupingBy(
                        FuzzFindingSummaryProjection::getFuzzTaskId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return runs.stream()
                .map(run -> toRunSummaryOrUnavailable(
                        run, findingsByRun.getOrDefault(run.getId(), List.of())))
                .toList();
    }

    private FuzzRunSummaryDto toRunSummaryOrUnavailable(
            FuzzTaskSummaryProjection run, List<FuzzFindingSummaryProjection> findings) {
        try {
            return fuzzMapper.toRunSummaryDtoFromTaskProjection(run, findings);
        } catch (PersistedDataIntegrityException e) {
            log.error("Fuzz history item {} is unavailable because persisted data is invalid",
                    run != null ? run.getId() : null, e);
            return FuzzRunSummaryDto.builder()
                    .id(run != null ? run.getId() : null)
                    .initiator(run != null && run.getInitiator() != null
                            ? run.getInitiator() : RunInitiator.UNKNOWN)
                    .createdAt(run != null ? run.getCreatedAt() : null)
                    .completedAt(run != null ? run.getCompletedAt() : null)
                    .findingCount(run != null && run.getFindingCount() != null
                            && run.getFindingCount() >= 0 ? run.getFindingCount() : null)
                    .findings(List.of())
                    .dataAvailable(false)
                    .unavailableReasonCode("PERSISTED_SEMANTIC_DATA_INVALID")
                    .build();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public FuzzRunDto getRun(Long userId, Long runId) {
        FuzzTaskPo run = requireOwnedRun(userId, runId);
        return fuzzMapper.toRunDto(run, loadOrderedFindings(userId, runId));
    }

    @Override
    @Transactional(readOnly = true)
    public RunDeletionImpactDto getRunDeletionImpact(Long userId, Long runId) {
        CompletedRunDeletionProjection run = taskRepository.findDeletionProjection(
                        runId, userId, FuzzTaskPo.TaskStatus.COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Counterexample search run", runId));
        long findingCount = findingRepository.countByUserIdAndFuzzTaskId(userId, runId);
        return RunDeletionImpactDto.builder()
                .runId(run.getId())
                .evidenceCount(findingCount)
                .createdAt(run.getCreatedAt())
                .completedAt(run.getCompletedAt())
                .build();
    }

    @Override
    @Transactional
    public void deleteRun(Long userId, Long runId) {
        deleteRunInternal(userId, runId, null);
    }

    @Override
    @Transactional
    public long deleteRun(Long userId, Long runId, long expectedFindingCount) {
        if (expectedFindingCount < 0) {
            throw new IllegalArgumentException("Expected finding count must not be negative");
        }
        return deleteRunInternal(userId, runId, expectedFindingCount);
    }

    private long deleteRunInternal(Long userId, Long runId, Long expectedFindingCount) {
        taskRepository.findCompletedRunForUpdate(
                        runId, userId, FuzzTaskPo.TaskStatus.COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Counterexample search run", runId));
        long actualFindingCount = findingRepository.countByUserIdAndFuzzTaskId(userId, runId);
        if (expectedFindingCount != null && actualFindingCount != expectedFindingCount) {
            throw staleDeletionImpact(expectedFindingCount, actualFindingCount);
        }
        int deletedFindingCount = findingRepository.deleteByUserIdAndFuzzTaskId(userId, runId);
        if (deletedFindingCount != actualFindingCount) {
            throw staleDeletionImpact(actualFindingCount, deletedFindingCount);
        }
        int deletedRunCount = taskRepository.deleteCompletedRun(
                runId, userId, FuzzTaskPo.TaskStatus.COMPLETED);
        if (deletedRunCount != 1) {
            throw new ConflictException(
                    "Counterexample search run changed during deletion; preview the deletion again before confirming");
        }
        return actualFindingCount;
    }

    private ConflictException staleDeletionImpact(long expected, long actual) {
        return new ConflictException("Run deletion impact changed from " + expected + " to " + actual
                + " finding rows; preview the deletion again before confirming");
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuzzFindingDto> getFindings(Long userId, Long runId) {
        FuzzTaskPo run = requireOwnedRun(userId, runId);
        return fuzzMapper.toRunDto(run, loadOrderedFindings(userId, runId)).getFindings();
    }

    @Override
    @Transactional(readOnly = true)
    public FuzzFindingDto getFinding(Long userId, Long findingId) {
        FuzzFindingPo finding = findingRepository.findByIdAndUserId(findingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Counterexample search finding", findingId));
        FuzzTaskPo run = requireOwnedRun(userId, finding.getFuzzTaskId());
        long actualFindingCount = findingRepository.countByUserIdAndFuzzTaskId(
                userId, finding.getFuzzTaskId());
        return fuzzMapper.toFindingDto(run, finding, actualFindingCount);
    }

    @Override
    @Transactional(readOnly = true)
    public FuzzFindingReplayDto getFindingReplay(Long userId, Long findingId) {
        FuzzFindingPo finding = findingRepository.findByIdAndUserId(findingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Counterexample search finding", findingId));
        FuzzTaskPo run = requireOwnedRun(userId, finding.getFuzzTaskId());
        long actualFindingCount = findingRepository.countByUserIdAndFuzzTaskId(
                userId, finding.getFuzzTaskId());
        return fuzzMapper.toFindingReplayDto(run, finding, actualFindingCount);
    }

    private FuzzTaskPo requireOwnedTask(Long userId, Long taskId) {
        return taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Counterexample search task", taskId));
    }

    private FuzzTaskPo requireOwnedRun(Long userId, Long runId) {
        return taskRepository.findByIdAndUserIdAndStatus(
                        runId, userId, FuzzTaskPo.TaskStatus.COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Counterexample search run", runId));
    }

    private List<FuzzFindingPo> loadOrderedFindings(Long userId, Long runId) {
        return findingRepository.findByUserIdAndFuzzTaskId(userId, runId).stream()
                .sorted(Comparator
                        .comparing(FuzzFindingPo::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(FuzzFindingPo::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<Long> normalizeExcludedTaskIds(List<Long> excludedTaskIds) {
        if (excludedTaskIds == null || excludedTaskIds.isEmpty()) return List.of();
        if (excludedTaskIds.size() > 100) {
            throw new ValidationException("excludeTaskIds", "At most 100 task IDs can be excluded");
        }
        if (excludedTaskIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ValidationException("excludeTaskIds", "Excluded task IDs must be positive");
        }
        return excludedTaskIds.stream().distinct().toList();
    }

    private Pageable pageRequest(int page, int size, int maximumSize) {
        if (page < 0 || page > 10_000) {
            throw new ValidationException("page", "Page must be between 0 and 10000");
        }
        if (size < 1 || size > maximumSize) {
            throw new ValidationException("size", "Page size must be between 1 and " + maximumSize);
        }
        return PageRequest.of(page, size);
    }

    private void failTaskById(Long taskId, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            taskRepository.findByIdForUpdate(taskId);
            LocalDateTime completedAt = databaseNow();
            taskRepository.failTaskIfActive(
                    taskId,
                    FuzzTaskPo.TaskStatus.FAILED,
                    completedAt,
                    null,
                    truncateOutput(message),
                    serializeCheckLogs(List.of(message)),
                    ACTIVE_STATUSES,
                    workerId,
                    completedAt);
        });
    }

    private void failTask(FuzzTaskPo task, String message) {
        if (task == null) return;
        transactionTemplate.executeWithoutResult(status -> {
            taskRepository.findByIdForUpdate(task.getId());
            LocalDateTime completedAt = databaseNow();
            Long processingTimeMs = task.getStartedAt() == null ? null
                    : Duration.between(task.getStartedAt(), completedAt).toMillis();
            taskRepository.failTaskIfActive(
                    task.getId(),
                    FuzzTaskPo.TaskStatus.FAILED,
                    completedAt,
                    processingTimeMs,
                    truncateOutput(message),
                    serializeCheckLogs(List.of(message)),
                    ACTIVE_STATUSES,
                    workerId,
                    completedAt);
        });
    }

    private boolean isCancelledOrTerminal(Long taskId) {
        if (isTaskCancelled(taskId) || Thread.currentThread().isInterrupted()) return true;
        return taskRepository.findById(taskId)
                .map(FuzzTaskPo::isTerminalStatus)
                .orElse(true);
    }

    private long utf8Length(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String serializeFrozenSnapshot(ModelInputSnapshot snapshot, String errorField) {
        String snapshotJson = FuzzModelInputSnapshotCodec.encode(snapshot);
        if (utf8Length(snapshotJson) > MAX_MODEL_INPUT_SNAPSHOT_BYTES) {
            throw new ValidationException(
                    errorField, "Frozen Board snapshot exceeds the 8 MiB persistence limit");
        }
        return snapshotJson;
    }

    private NormalizedRequest executionRequest(FuzzTaskPo task) {
        return new NormalizedRequest(
                JsonUtils.fromJsonToStringList(task.getTargetSpecIdsJson()),
                Objects.requireNonNull(task.getMaxIterations(), "maxIterations is missing"),
                Objects.requireNonNull(task.getPathLength(), "pathLength is missing"),
                Objects.requireNonNull(task.getPopulationSize(), "populationSize is missing"),
                task.getSeed(),
                Objects.requireNonNull(task.getExplorationMode(), "explorationMode is missing"),
                null);
    }

    void requireBoundedRunMetadata(String eligibilityJson, String limitationsJson) {
        long bytes;
        try {
            bytes = Math.addExact(utf8Length(eligibilityJson), utf8Length(limitationsJson));
        } catch (ArithmeticException e) {
            throw new EvidenceLimitExceededException();
        }
        if (bytes > MAX_RUN_METADATA_BYTES) {
            throw new EvidenceLimitExceededException();
        }
    }

    String boundedSingleLine(String value, String fallback, int maxChars) {
        return FuzzMetadataPolicy.boundedSingleLine(value, fallback, maxChars);
    }

    private String publicTaskFailureMessage(Exception error) {
        if (error instanceof EvidenceLimitExceededException) {
            return "Candidate evidence exceeded the persistence safety limit";
        }
        return "Internal counterexample exploration error";
    }

    private void requireActiveUserForPersistence(Long userId) {
        if (userId == null) throw new ValidationException("userId", "User id cannot be null");
        if (userRepository.findByIdForUpdate(userId).isEmpty()) {
            throw ResourceNotFoundException.user(userId);
        }
    }

    @Override
    protected Optional<FuzzTaskPo> findTaskByIdAndUserId(Long id, Long userId) {
        return taskRepository.findByIdAndUserId(id, userId);
    }

    @Override
    protected int atomicCancelTask(Long taskId, LocalDateTime completedAt) {
        return taskRepository.cancelTaskIfStillActive(
                taskId, FuzzTaskPo.TaskStatus.CANCELLED, completedAt, ACTIVE_STATUSES);
    }

    @Override
    protected LocalDateTime currentTaskTime() {
        return databaseNow();
    }

    @Override
    protected int atomicUpdateProgress(Long taskId, int progress, TaskProgressStage stage) {
        return TaskLeaseRenewal.updateWithConfirmation(
                transactionTemplate,
                () -> taskRepository.findByIdForUpdate(taskId),
                this::databaseNow,
                (lockedTask, currentTime) -> taskRepository.updateProgressIfActive(
                        taskId, progress, stage, workerId, currentTime)).updated();
    }

    @Override
    protected LocalExecutionStopResult stopAdditionalLocalExecution(Long taskId) {
        LocalFuzzExecution execution = localExecutions.get(taskId);
        return execution == null
                ? LocalExecutionStopResult.NONE
                : execution.requestStop();
    }

    private record NormalizedRequest(List<String> targetSpecIds,
                                     int maxIterations,
                                     int pathLength,
                                     int populationSize,
                                     Long seed,
                                     FuzzExplorationMode explorationMode,
                                     String paperDomainFingerprint) {
    }

    private enum LocalExecutionState {
        QUEUED,
        RUNNING,
        CANCELLED_BEFORE_START,
        FINISHED
    }

    private final class LocalFuzzExecution implements Runnable {
        private final Long taskId;
        private final Long userId;
        private final SubmissionCapacityPermit capacityPermit;
        private final AtomicReference<LocalExecutionState> state =
                new AtomicReference<>(LocalExecutionState.QUEUED);
        private final LeaseConfirmation leaseConfirmation = new LeaseConfirmation();
        private final FutureTask<Void> futureTask;

        private LocalFuzzExecution(Long taskId,
                                   Long userId,
                                   SubmissionCapacityPermit capacityPermit) {
            this.taskId = taskId;
            this.userId = userId;
            this.capacityPermit = capacityPermit;
            this.futureTask = new FutureTask<>(this, null);
        }

        @Override
        public void run() {
            if (!state.compareAndSet(LocalExecutionState.QUEUED, LocalExecutionState.RUNNING)) {
                return;
            }
            try {
                runTask(userId, taskId);
            } finally {
                removeCancelledMark(taskId);
                state.set(LocalExecutionState.FINISHED);
                releaseAndForget();
            }
        }

        private boolean isQueued() {
            return state.get() == LocalExecutionState.QUEUED;
        }

        private LocalExecutionStopResult requestStop() {
            while (true) {
                LocalExecutionState current = state.get();
                if (current == LocalExecutionState.QUEUED) {
                    if (!state.compareAndSet(
                            LocalExecutionState.QUEUED,
                            LocalExecutionState.CANCELLED_BEFORE_START)) {
                        continue;
                    }
                    futureTask.cancel(false);
                    purgeCancelledFuzzTasks();
                    releaseAndForget();
                    return LocalExecutionStopResult.STOPPED_BEFORE_START;
                }
                if (current == LocalExecutionState.RUNNING) {
                    futureTask.cancel(true);
                    return LocalExecutionStopResult.STOP_REQUESTED;
                }
                return LocalExecutionStopResult.NONE;
            }
        }

        private void purgeIfCancelledAfterDispatch() {
            if (futureTask.isCancelled()) purgeCancelledFuzzTasks();
        }

        private void releaseAndForget() {
            capacityPermit.release();
            localExecutions.remove(taskId, this);
        }
    }

    private static final class SubmissionCapacityPermit {
        private final Semaphore capacity;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private SubmissionCapacityPermit(Semaphore capacity) {
            this.capacity = capacity;
        }

        private void release() {
            if (released.compareAndSet(false, true)) capacity.release();
        }
    }

    private static final class EvidenceLimitExceededException extends IllegalStateException {
        private EvidenceLimitExceededException() {
            super("Candidate evidence exceeds the configured persistence safety limit");
        }
    }
}
