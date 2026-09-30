package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.EnvironmentProvenanceCollector;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.AttackSurface;
import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.component.nusmv.SpecResultAlignment;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.NusmvResult;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.configure.AsyncTaskAdmissionConfig;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.Result;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.EnvironmentValueProvenanceDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.dto.model.RunPersistenceDto;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.model.TemplateSnapshotBundleDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.*;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationResultDto;
import cn.edu.nju.Iot_Verify.dto.verification.SpecResultDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskQuotaExceededException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.TraceRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.repository.projection.CompletedRunDeletionProjection;
import cn.edu.nju.Iot_Verify.repository.projection.TraceSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationRunSummaryProjection;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import cn.edu.nju.Iot_Verify.util.ModelPlaybackSceneSnapshot;
import cn.edu.nju.Iot_Verify.util.RunInitiatorResolver;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import cn.edu.nju.Iot_Verify.util.SpecificationFormulaPreview;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.service.FormalOperationAdmission;
import cn.edu.nju.Iot_Verify.service.ChatExecutionLeaseGuard;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import cn.edu.nju.Iot_Verify.util.mapper.TraceMapper;
import cn.edu.nju.Iot_Verify.util.mapper.VerificationTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verification service implementation.
 *
 * Manages sync/async verification flows, task lifecycle, and trace persistence.
 */
@Slf4j
@Service
public class VerificationServiceImpl extends AbstractAsyncTaskService<VerificationTaskPo> implements VerificationService {

    private static final Duration TASK_LEASE_DURATION = Duration.ofMinutes(2);
    private static final long LEASE_MAINTENANCE_SECONDS = 10L;
    private static final List<VerificationTaskPo.TaskStatus> ACTIVE_STATUSES = List.of(
            VerificationTaskPo.TaskStatus.PENDING,
            VerificationTaskPo.TaskStatus.RUNNING);

    private final SmvGenerator smvGenerator;
    private final EnvironmentProvenanceCollector provenanceCollector;
    private final SmvTraceParser smvTraceParser;
    private final NusmvExecutor nusmvExecutor;
    private final NusmvConfig nusmvConfig;
    private final VerificationTaskRepository taskRepository;
    private final TraceRepository traceRepository;
    private final TraceMapper traceMapper;
    private final UserRepository userRepository;
    private final SpecificationMapper specificationMapper;
    private final VerificationTaskMapper verificationTaskMapper;
    private final ThreadPoolTaskExecutor verificationTaskExecutor;
    private final ThreadPoolTaskExecutor syncVerificationExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ChatExecutionLeaseGuard chatExecutionLeaseGuard;
    private final FormalOperationAdmission formalOperationAdmission;
    private final AsyncTaskAdmissionConfig.Limits taskAdmissionLimits;
    /** Reads the board that defines what a run verifies; the request only chooses run parameters. */
    private final BoardDataConverter boardDataConverter;
    private final String workerId = UUID.randomUUID().toString();
    private final ScheduledExecutorService leaseMaintenanceExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "verification-task-lease-maintenance");
                thread.setDaemon(true);
                return thread;
            });
    private final ConcurrentHashMap<Long, LocalVerificationExecution> localExecutions =
            new ConcurrentHashMap<>();

    @Autowired
    public VerificationServiceImpl(SmvGenerator smvGenerator,
                                   EnvironmentProvenanceCollector provenanceCollector,
                                   SmvTraceParser smvTraceParser,
                                   NusmvExecutor nusmvExecutor,
                                   NusmvConfig nusmvConfig,
                                   VerificationTaskRepository taskRepository,
                                   TraceRepository traceRepository,
                                   TraceMapper traceMapper,
                                   UserRepository userRepository,
                                   SpecificationMapper specificationMapper,
                                   VerificationTaskMapper verificationTaskMapper,
                                   ObjectMapper objectMapper,
                                   @Qualifier("verificationTaskExecutor") ThreadPoolTaskExecutor verificationTaskExecutor,
                                   @Qualifier("syncVerificationExecutor") ThreadPoolTaskExecutor syncVerificationExecutor,
                                   TransactionTemplate transactionTemplate,
                                   ChatExecutionLeaseGuard chatExecutionLeaseGuard,
                                   FormalOperationAdmission formalOperationAdmission,
                                   AsyncTaskAdmissionConfig taskAdmissionConfig,
                                   BoardDataConverter boardDataConverter) {
        super(objectMapper, "VerificationTask");
        this.boardDataConverter = boardDataConverter;
        this.smvGenerator = smvGenerator;
        this.provenanceCollector = provenanceCollector;
        this.smvTraceParser = smvTraceParser;
        this.nusmvExecutor = nusmvExecutor;
        this.nusmvConfig = nusmvConfig;
        this.taskRepository = taskRepository;
        this.traceRepository = traceRepository;
        this.traceMapper = traceMapper;
        this.userRepository = userRepository;
        this.specificationMapper = specificationMapper;
        this.verificationTaskMapper = verificationTaskMapper;
        this.verificationTaskExecutor = verificationTaskExecutor;
        this.syncVerificationExecutor = syncVerificationExecutor;
        this.transactionTemplate = transactionTemplate;
        this.chatExecutionLeaseGuard = chatExecutionLeaseGuard;
        this.formalOperationAdmission = formalOperationAdmission;
        this.taskAdmissionLimits = taskAdmissionConfig.getVerification();
    }

    VerificationServiceImpl(SmvGenerator smvGenerator,
                            EnvironmentProvenanceCollector provenanceCollector,
                            SmvTraceParser smvTraceParser,
                            NusmvExecutor nusmvExecutor,
                            NusmvConfig nusmvConfig,
                            VerificationTaskRepository taskRepository,
                            TraceRepository traceRepository,
                            TraceMapper traceMapper,
                            UserRepository userRepository,
                            SpecificationMapper specificationMapper,
                            VerificationTaskMapper verificationTaskMapper,
                            ObjectMapper objectMapper,
                            ThreadPoolTaskExecutor verificationTaskExecutor,
                            ThreadPoolTaskExecutor syncVerificationExecutor,
                            TransactionTemplate transactionTemplate,
                            ChatExecutionLeaseGuard chatExecutionLeaseGuard,
                            FormalOperationAdmission formalOperationAdmission,
                            BoardDataConverter boardDataConverter) {
        this(smvGenerator, provenanceCollector, smvTraceParser, nusmvExecutor, nusmvConfig, taskRepository,
                traceRepository, traceMapper, userRepository, specificationMapper,
                verificationTaskMapper, objectMapper, verificationTaskExecutor,
                syncVerificationExecutor, transactionTemplate, chatExecutionLeaseGuard,
                formalOperationAdmission,
                new AsyncTaskAdmissionConfig(),
                boardDataConverter);
    }

    private void requireChatExecutionLease() {
        chatExecutionLeaseGuard.requireCurrentExecutionLease();
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
        List<LocalVerificationExecution> executions = List.copyOf(localExecutions.values());
        try {
            databaseNow();
        } catch (RuntimeException e) {
            stopVerificationExecutionsWithExpiredConfirmation(executions);
            log.warn("Could not read database time while maintaining verification task leases", e);
            return;
        }
        for (LocalVerificationExecution execution : executions) {
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
                    log.warn("Stopped local verification task {} after its lease could not be confirmed for a full TTL",
                            execution.taskId);
                } else {
                    log.warn("Could not renew verification task lease {}; the next cycle will retry",
                            execution.taskId, e);
                }
            }
        }
        try {
            LocalDateTime recoveryTime = databaseNow();
            String message = "The verification worker stopped before the task completed";
            int recovered = taskRepository.failExpiredActiveTasks(
                    VerificationTaskPo.TaskStatus.FAILED,
                    recoveryTime,
                    VerificationOutcome.INCONCLUSIVE,
                    message,
                    serializeCheckLogs(List.of(message)),
                    ACTIVE_STATUSES,
                    recoveryTime);
            if (recovered > 0) {
                log.warn("Recovered {} expired verification task lease(s)", recovered);
            }
        } catch (RuntimeException e) {
            log.warn("Could not recover expired verification task leases; the next cycle will retry", e);
        }
    }

    private void stopVerificationExecutionsWithExpiredConfirmation(
            List<LocalVerificationExecution> executions) {
        for (LocalVerificationExecution execution : executions) {
            if (execution.leaseConfirmation.isUnconfirmedFor(TASK_LEASE_DURATION)) {
                execution.requestStop();
                log.warn("Stopped local verification task {} after the database could not confirm its lease for a full TTL",
                        execution.taskId);
            }
        }
    }

    // ==================== 同步验证 ====================

    @Override
    public VerificationResultDto verify(Long userId, VerificationRequestDto request) {
        return formalOperationAdmission.execute(userId,
                () -> verifyInput(userId, validateAndNormalize(userId, request)));
    }

    @Override
    public VerificationResultDto verifyWithTemplateSnapshot(
            Long userId,
            VerificationRequestDto request,
            Map<String, DeviceManifest> templateManifests) {
        return formalOperationAdmission.execute(userId,
                () -> verifyInput(userId, validateAndNormalize(userId, request, templateManifests)));
    }

    private VerificationResultDto verifyInput(Long userId, VerificationInput input) {

        requireVerificationRunStorageCapacity(userId);

        log.info("Starting sync verification: userId={}, devices={}, specs={}, attack={}, attackBudget={}",
                userId, input.devices().size(), input.specs().size(), input.attack(), input.attackBudget());

        LocalDateTime startedAt = LocalDateTime.now();
        long timeoutMs = nusmvConfig.getTimeoutMs() * 2; // generate + execute total timeout
        Future<VerificationResultDto> future;
        try {
            future = syncVerificationExecutor.submit(() ->
                    doVerify(userId, input.devices(), input.rules(), input.specs(),
                            input.enablePrivacy(), input.request(),
                            input.deviceSmvMap(), input.templateManifests(), input.modelSnapshot(),
                            SmvGenerator.TempModelContext.sync()));
        } catch (RejectedExecutionException e) {
            log.warn("Sync verification request rejected: executor is saturated ({})", syncVerificationExecutorSnapshot());
            throw new ServiceUnavailableException("Verification service is busy, please retry later", e);
        }

        VerificationResultDto result;
        try {
            result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            purgeCancelledSyncTasks();
            log.warn("Sync verification timed out after {}ms", timeoutMs);
            result = applyRunContext(buildErrorResult("", List.of("Verification timed out")),
                    input.attackScenario(), input.enablePrivacy(), input.attackSurface(), input.modelSnapshot(),
                    buildTemplateSnapshotsJson(input.templateManifests(), input.deviceSmvMap()), input.request());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InternalServerException ise) throw ise;
            if (cause instanceof ServiceUnavailableException sue) throw sue;
            if (cause instanceof SmvGenerationException sge) throw sge;
            log.error("Sync verification failed", cause);
            throw new InternalServerException("Verification failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            future.cancel(true);
            purgeCancelledSyncTasks();
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Verification was interrupted before completion", e);
        }
        try {
            Long runId = persistCompletedVerificationRun(userId, input, result, startedAt);
            result.setHistoryPersistence(RunPersistenceDto.saved(runId));
        } catch (AsyncTaskQuotaExceededException e) {
            log.info("Verification completed but run history is full for user {}", userId);
            clearUnconfirmedTracePersistenceIdentity(result);
            result.setHistoryPersistence(RunPersistenceDto.failed(e.getReasonCode()));
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Verification completed but could not be added to run history for user {}", userId, e);
            clearUnconfirmedTracePersistenceIdentity(result);
            result.setHistoryPersistence(RunPersistenceDto.outcomeUnknown("RUN_HISTORY_SAVE_OUTCOME_UNKNOWN"));
            List<String> logs = result.getCheckLogs() != null
                    ? new ArrayList<>(result.getCheckLogs()) : new ArrayList<>();
            logs.add("[history-save-unknown] Verification completed, but whether it entered run history could not be confirmed.");
            result.setCheckLogs(logs);
        }
        return result;
    }

    private void clearUnconfirmedTracePersistenceIdentity(VerificationResultDto result) {
        if (result == null || result.getTraces() == null) {
            return;
        }
        for (TraceDto trace : result.getTraces()) {
            if (trace == null) {
                continue;
            }
            trace.setId(null);
            trace.setVerificationTaskId(null);
            trace.setUserId(null);
        }
    }

    private VerificationResultDto doVerify(Long userId,
                                           List<DeviceVerificationDto> devices,
                                           List<RuleDto> rules,
                                           List<SpecificationDto> specs,
                                           boolean enablePrivacy,
                                            VerificationRequestDto request,
                                            Map<String, DeviceSmvData> resolvedDeviceSmvMap,
                                            Map<String, DeviceManifest> templateManifests,
                                            ModelRunSnapshotDto modelSnapshot,
                                            SmvGenerator.TempModelContext tempModelContext) {
        List<String> checkLogs = new ArrayList<>();
        File smvFile = null;
        Map<String, DeviceSmvData> deviceSmvMap = null;
        AttackSurface attackSurface = new AttackSurface(Set.of(), rules != null ? rules.size() : 0, 0);
        VerificationResultDto finalResult = null;
        String requestJson = buildRequestSnapshot(request);
        String templateSnapshotsJson = buildTemplateSnapshotsJson(
                templateManifests, resolvedDeviceSmvMap);
        String smvModelContent = null;

        try {
            checkLogs.add("Generating NuSMV model...");
            SmvGenerator.GenerateResult genResult = generateResolvedModel(
                    userId, devices, request.getEnvironmentVariables(), rules, specs,
                    request.resolvedAttackScenario(), enablePrivacy, SmvGenerator.GeneratePurpose.VERIFICATION,
                    tempModelContext, resolvedDeviceSmvMap);
            smvFile = genResult.smvFile();
            deviceSmvMap = genResult.deviceSmvMap();
            attackSurface = AttackSurface.analyze(rules, deviceSmvMap);
            appendGenerationWarnings(checkLogs, genResult);
            if (smvFile == null || !smvFile.exists()) {
                checkLogs.add("Failed to generate NuSMV model file");
                finalResult = buildErrorResult("", checkLogs);
                return finalResult;
            }
            checkLogs.add("Model generated: " + smvFile.getName());
            saveRequestJson(smvFile, requestJson);

            // Read SMV content for persistence before NuSMV execution
            smvModelContent = readSmvModelContent(smvFile);

            checkLogs.add("Executing NuSMV verification...");
            NusmvResult result = nusmvExecutor.execute(smvFile);

            if (!result.isSuccess()) {
                if (result.isBusy()) {
                    checkLogs.add("NuSMV execution is busy, please retry later");
                    finalResult = buildErrorResult("", checkLogs);
                    throw new ServiceUnavailableException("NuSMV verification service is busy, please retry later");
                }
                checkLogs.add("NuSMV execution failed: " + result.getErrorMessage());
                finalResult = buildErrorResult("", checkLogs);
                return finalResult;
            }
            checkLogs.add("NuSMV execution completed.");

            // Build per-spec results and reuse deviceSmvMap from generation stage.
            finalResult = buildVerificationResult(result, devices, rules, specs, userId, null, checkLogs, deviceSmvMap,
                    templateManifests,
                    requestJson, genResult.emittedSpecs(), genResult.generationIssues(),
                    genResult.disabledRuleCount(), genResult.skippedSpecCount(), smvModelContent);
            applyRunContext(finalResult, request.resolvedAttackScenario(), enablePrivacy,
                    attackSurface, modelSnapshot, templateSnapshotsJson, request);
            return finalResult;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Verification error", e);
            checkLogs.add("Error: " + e.getMessage());
            finalResult = buildErrorResult("", checkLogs);
            return finalResult;
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (SmvGenerationException e) {
            log.error("SMV generation failed", e);
            checkLogs.add("Error: " + e.getMessage());
            finalResult = buildErrorResult("", checkLogs);
            throw e;
        } catch (Exception e) {
            log.error("Verification failed", e);
            checkLogs.add("Error: " + e.getMessage());
            finalResult = buildErrorResult("", checkLogs);
            throw new InternalServerException("Verification failed: " + e.getMessage());
        } finally {
            // Persist result.json when tempDir exists (both success and failure) for debugging.
            if (finalResult != null) {
                applyRunContext(finalResult, request.resolvedAttackScenario(), enablePrivacy,
                        attackSurface, modelSnapshot, templateSnapshotsJson, request);
                saveResultJson(smvFile, finalResult);
            }
            cleanupTempFile(smvFile);
        }
    }

    private void saveResultJson(File smvFile, VerificationResultDto verificationResult) {
        if (smvFile == null || smvFile.getParentFile() == null) return;
        try {
            File jsonFile = new File(smvFile.getParentFile(), "result.json");
            Result<VerificationResultDto> wrapped = wrapResultForDebugFile(verificationResult);
            byte[] payload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(wrapped);
            java.nio.file.Files.write(jsonFile.toPath(), payload);
            log.debug("Verification result JSON saved to: {}", jsonFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to save result JSON: {}", e.getMessage());
        }
    }

    private void saveRequestJson(File smvFile, String requestJson) {
        if (smvFile == null || smvFile.getParentFile() == null || requestJson == null || requestJson.isBlank()) return;
        try {
            File jsonFile = new File(smvFile.getParentFile(), "request.json");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(jsonFile, objectMapper.readTree(requestJson));
            log.debug("Verification request JSON saved to: {}", jsonFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to save verification request JSON: {}", e.getMessage());
        }
    }

    private void appendGenerationWarnings(List<String> checkLogs, SmvGenerator.GenerateResult genResult) {
        if (checkLogs == null || genResult == null || genResult.generationWarnings() == null
                || genResult.generationWarnings().isEmpty()) {
            return;
        }
        checkLogs.addAll(genResult.generationWarnings());
    }

    private String buildRequestSnapshot(VerificationRequestDto request) {
        return JsonUtils.toJson(request);
    }

    private VerificationInput validateAndNormalize(Long userId, VerificationRequestDto request) {
        return validateAndNormalize(userId, request, null);
    }

    private VerificationInput validateAndNormalize(
            Long userId,
            VerificationRequestDto request,
            Map<String, DeviceManifest> suppliedTemplateManifests) {
        if (request == null) {
            throw new ValidationException("request", "Verification request cannot be null");
        }
        AttackScenarioDto attackScenario = AttackScenarioValidator.canonicalizeForVerification(
                request.getAttackScenario());
        // The board defines WHAT is verified; the request only selects run parameters. Accepting a
        // caller-supplied scene was an authority inversion: an account whose board held no devices
        // could post a fabricated two-device scene and have the VIOLATED verdict persisted into its
        // own run history, where the UI presents it as "this saved scene was checked". Fuzz and the
        // AI tools already read the board here, so this makes one rule out of two.
        //
        // The board read happens BEFORE snapshotRequest so the frozen snapshot deep-copies the board
        // scene too. Assigning it afterwards would leave the run holding the live board objects.
        ModelInputSnapshot board = boardDataConverter.getModelInputSnapshot(userId);
        request.setDevices(board.devices());
        request.setSpecs(board.specifications());
        request.setRules(board.rules());
        request.setEnvironmentVariables(board.environmentVariables());
        request.setPlaybackNodes(board.nodes());

        VerificationRequestDto snapshot = snapshotRequest(request, attackScenario);
        List<DeviceVerificationDto> devices = copyRequiredList(
                snapshot.getDevices(), "devices",
                "Add at least one device to the board before verifying");
        List<SpecificationDto> specs = copyRequiredList(
                snapshot.getSpecs(), "specs",
                "Add at least one specification to the board before verifying");
        List<RuleDto> rules = copyOptionalList(snapshot.getRules(), "rules");
        List<BoardEnvironmentVariableDto> environmentVariables =
                copyOptionalList(snapshot.getEnvironmentVariables(), "environmentVariables");
        snapshot.setDevices(devices);
        snapshot.setRules(rules);
        snapshot.setSpecs(specs);
        snapshot.setEnvironmentVariables(environmentVariables);
        snapshot.setAttackScenario(attackScenario);
        snapshot.setEnablePrivacy(snapshot.isEnablePrivacy() || specificationsRequirePrivacy(specs));

        Map<String, String> errors = NusmvRequestValidator.newErrors();
        NusmvRequestValidator.validateDevices(devices, errors);
        NusmvRequestValidator.validateRules(rules, devices, errors);
        NusmvRequestValidator.validateSpecifications(specs, devices, errors);
        ModelBoundaryInput modelInput = validateModelSemantics(
                userId, devices, rules, specs, environmentVariables, snapshot.isAttack(),
                suppliedTemplateManifests, errors);
        snapshot.setEnvironmentVariables(modelInput.environmentVariables());
        NusmvRequestValidator.throwIfErrors(errors);

        try {
            snapshot.setPlaybackNodes(ModelPlaybackSceneSnapshot.canonicalize(
                    snapshot.getPlaybackNodes(), devices, rules).nodes());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("playbackNodes", e.getMessage());
        }

        AttackSurface attackSurface = modelInput.attackSurface();
        AttackScenarioValidator.validateAgainstSurface(attackScenario, attackSurface, rules);

        return new VerificationInput(modelInput.devices(), rules, specs,
                snapshot.isEnablePrivacy(), attackSurface.deviceCount(), attackSurface.automationLinkCount(),
                attackSurface.falsifiableReadingDeviceCount(), attackScenario, snapshot, modelInput.deviceSmvMap(),
                modelInput.templateManifests(), modelInput.modelSnapshot(), attackSurface);
    }

    private boolean specificationsRequirePrivacy(List<SpecificationDto> specs) {
        if (specs == null) {
            return false;
        }
        return specs.stream()
                .filter(Objects::nonNull)
                .flatMap(spec -> java.util.stream.Stream.of(
                        spec.getAConditions(), spec.getIfConditions(), spec.getThenConditions()))
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .map(SpecConditionDto::getTargetType)
                .anyMatch(type -> type != null && "privacy".equalsIgnoreCase(type.trim()));
    }

    private VerificationResultDto applyModelContext(VerificationResultDto result,
                                                     AttackScenarioDto attackScenario,
                                                     boolean enablePrivacy,
                                                     AttackSurface attackSurface) {
        if (result == null) {
            return null;
        }
        AttackScenarioDto safeAttackScenario = Objects.requireNonNull(
                attackScenario, "attackScenario is required");
        boolean isAttack = safeAttackScenario.isEnabled();
        int attackBudget = safeAttackScenario.effectiveBudget();
        result.setIsAttack(isAttack);
        result.setAttackBudget(isAttack ? attackBudget : 0);
        result.setEnablePrivacy(enablePrivacy);
        ModelSemanticsDto semantics = ModelSemanticsDto.forRun(
                safeAttackScenario, enablePrivacy, attackSurface);
        result.setModelSemantics(semantics);
        if (result.getTraces() != null) {
            for (TraceDto trace : result.getTraces()) {
                trace.setAttack(isAttack);
                trace.setAttackBudget(isAttack ? attackBudget : 0);
                trace.setEnablePrivacy(enablePrivacy);
                trace.setModelSemantics(semantics);
            }
        }
        return result;
    }

    private VerificationResultDto applyRunContext(VerificationResultDto result,
                                                   AttackScenarioDto attackScenario,
                                                   boolean enablePrivacy,
                                                   AttackSurface attackSurface,
                                                   ModelRunSnapshotDto modelSnapshot,
                                                   String templateSnapshotsJson,
                                                   VerificationRequestDto request) {
        applyModelContext(result, attackScenario, enablePrivacy, attackSurface);
        if (result == null) {
            return null;
        }
        result.setModelSnapshot(modelSnapshot);
        if (result.getTraces() != null) {
            for (TraceDto trace : result.getTraces()) {
                trace.setModelSnapshot(modelSnapshot);
                trace.setTemplateSnapshotsJson(templateSnapshotsJson);
                trace.setPlaybackScene(ModelPlaybackSceneSnapshot.canonicalize(
                        request.getPlaybackNodes(), request.getDevices(), request.getRules()));
            }
        }
        return result;
    }

    private String buildTemplateSnapshotsJson(
            Map<String, DeviceManifest> templateManifests,
            Map<String, DeviceSmvData> deviceSmvMap) {
        Map<String, ModelTokenSource> sourcesByDeviceId = new LinkedHashMap<>();
        if (deviceSmvMap != null) {
            for (DeviceSmvData device : deviceSmvMap.values()) {
                if (device == null || device.getVarName() == null || device.getVarName().isBlank()) {
                    continue;
                }
                sourcesByDeviceId.put(device.getVarName(), device.getModelTokenSource() != null
                        ? device.getModelTokenSource()
                        : ModelTokenSource.UNKNOWN);
            }
        }
        return JsonUtils.toJson(TemplateSnapshotBundleDto.captured(
                templateManifests, sourcesByDeviceId));
    }

    private ModelBoundaryInput validateModelSemantics(Long userId,
                                                      List<DeviceVerificationDto> devices,
                                                      List<RuleDto> rules,
                                                      List<SpecificationDto> specs,
                                                      List<BoardEnvironmentVariableDto> environmentVariables,
                                                      boolean isAttack,
                                                      Map<String, DeviceManifest> suppliedTemplateManifests,
                                                      Map<String, String> errors) {
        if (!errors.isEmpty()) {
            return new ModelBoundaryInput(devices, environmentVariables, new AttackSurface(Set.of(), 0, 0),
                    Map.of(), Map.of(), null);
        }
        try {
            SmvGenerator.CapturedDeviceModel capturedDeviceModel = suppliedTemplateManifests == null
                    ? smvGenerator.captureDeviceModel(userId, devices)
                    : smvGenerator.captureDeviceModelFromTemplateSnapshots(
                            devices, suppliedTemplateManifests);
            Map<String, DeviceSmvData> deviceSmvMap = capturedDeviceModel.deviceSmvMap();
            LocalDateTime capturedAt = LocalDateTime.now();
            NusmvRequestValidator.validateDeviceSemantics(devices, deviceSmvMap, errors);
            NusmvRequestValidator.validateEnvironmentVariableOverrides(
                    environmentVariables, deviceSmvMap, errors);
            List<BoardEnvironmentVariableDto> mergedEnvironmentVariables =
                    NusmvEnvironmentPool.mergeWithDefaults(environmentVariables, deviceSmvMap);
            NusmvRequestValidator.validateEnvironmentVariables(mergedEnvironmentVariables, deviceSmvMap, errors);
            NusmvRequestValidator.validateMainNamespace(devices, rules, deviceSmvMap, isAttack, errors);
            NusmvRequestValidator.validateRuleSemantics(rules, deviceSmvMap, errors);
            NusmvRequestValidator.validateSpecificationSemantics(specs, deviceSmvMap, errors);
            NusmvRequestValidator.validateAttackHasModeledEffect(isAttack, rules, deviceSmvMap, errors);
            AttackSurface attackSurface = AttackSurface.analyze(rules, deviceSmvMap);
            if (!errors.isEmpty()) {
                return new ModelBoundaryInput(devices, mergedEnvironmentVariables, attackSurface,
                        Map.of(), capturedDeviceModel.templateManifests(), null);
            }
            List<DeviceVerificationDto> expandedDevices = NusmvEnvironmentPool.expandDevices(
                    devices, mergedEnvironmentVariables, deviceSmvMap);
            Map<String, DeviceSmvData> expandedDeviceSmvMap =
                    smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(
                            expandedDevices, capturedDeviceModel.templateManifests());
            List<EnvironmentValueProvenanceDto> environmentProvenance =
                    provenanceCollector.collectEnvironmentProvenance(
                            mergedEnvironmentVariables, expandedDevices, expandedDeviceSmvMap);
            ModelRunSnapshotDto modelSnapshot = ModelRunSnapshotDto.captured(
                    capturedAt,
                    expandedDevices.size(),
                    rules != null ? rules.size() : 0,
                    specs != null ? specs.size() : 0,
                    mergedEnvironmentVariables.size(),
                    capturedDeviceModel.templateManifests().size())
                    .toBuilder()
                    .environmentProvenance(environmentProvenance)
                    .build();
            return new ModelBoundaryInput(expandedDevices, mergedEnvironmentVariables, attackSurface,
                    expandedDeviceSmvMap, capturedDeviceModel.templateManifests(), modelSnapshot);
        } catch (SmvGenerationException e) {
            errors.putIfAbsent("devices", e.getMessage());
            return new ModelBoundaryInput(devices, environmentVariables, new AttackSurface(Set.of(), 0, 0),
                    Map.of(), Map.of(), null);
        }
    }

    private VerificationRequestDto snapshotRequest(VerificationRequestDto request,
                                                   AttackScenarioDto attackScenario) {
        try {
            VerificationRequestDto snapshot = objectMapper.convertValue(request, VerificationRequestDto.class);
            ModelRequestSnapshotSupport.preserveDeviceMetadata(
                    request.getDevices(), snapshot.getDevices());
            snapshot.setAttackScenario(attackScenario);
            return snapshot;
        } catch (IllegalArgumentException e) {
            throw new ValidationException("request", "Verification request cannot be snapshotted");
        }
    }

    private <T> List<T> copyRequiredList(List<T> values, String field, String emptyMessage) {
        if (values == null || values.isEmpty()) {
            throw new ValidationException(field, emptyMessage);
        }
        return copyList(values, field);
    }

    private <T> List<T> copyOptionalList(List<T> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return copyList(values, field);
    }

    private <T> List<T> copyList(List<T> values, String field) {
        List<T> copy = new ArrayList<>(values);
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i) == null) {
                throw new ValidationException(field + "[" + i + "]", "must not be null");
            }
        }
        return copy;
    }

    private Result<VerificationResultDto> wrapResultForDebugFile(VerificationResultDto verificationResult) {
        Result<VerificationResultDto> wrapped = new Result<>();
        wrapped.setData(verificationResult);
        if (isVerificationFailureResult(verificationResult)) {
            wrapped.setCode(inferVerificationErrorCode(verificationResult));
            wrapped.setMessage(inferVerificationErrorMessage(verificationResult));
        } else {
            wrapped.setCode(200);
            wrapped.setMessage("success");
        }
        return wrapped;
    }

    private boolean isVerificationFailureResult(VerificationResultDto result) {
        if (result == null) return true;
        if (result.getOutcome() == VerificationOutcome.INCONCLUSIVE) return true;
        boolean hasSpecResults = result.getSpecResults() != null && !result.getSpecResults().isEmpty();
        boolean hasTraces = result.getTraces() != null && !result.getTraces().isEmpty();
        if (hasSpecResults || hasTraces) return false;
        return result.getOutcome() != VerificationOutcome.SATISFIED;
    }

    private int inferVerificationErrorCode(VerificationResultDto result) {
        List<String> logs = result != null ? result.getCheckLogs() : null;
        if (logs != null) {
            for (String logLine : logs) {
                if (logLine != null && logLine.toLowerCase().contains("busy")) {
                    return 503;
                }
            }
        }
        return 500;
    }

    private String inferVerificationErrorMessage(VerificationResultDto result) {
        List<String> logs = result != null ? result.getCheckLogs() : null;
        if (logs != null && !logs.isEmpty()) {
            String last = logs.get(logs.size() - 1);
            if (last != null && !last.isBlank()) {
                return last;
            }
        }
        return "verification failed";
    }

    // ==================== 异步验证 ====================

    @Override
    public Long submitVerification(Long userId, VerificationRequestDto request) {
        return submitVerificationInput(userId, validateAndNormalize(userId, request));
    }

    @Override
    public Long submitVerificationWithTemplateSnapshot(
            Long userId,
            VerificationRequestDto request,
            Map<String, DeviceManifest> templateManifests) {
        return submitVerificationInput(
                userId, validateAndNormalize(userId, request, templateManifests));
    }

    private Long submitVerificationInput(Long userId, VerificationInput input) {
        Long taskId = createTask(userId, input.attackScenario(), input.enablePrivacy(),
                input.modeledDeviceAttackPointCount(), input.modeledAutomationLinkAttackPointCount(),
                input.modeledFalsifiableReadingDeviceCount(), input.modelSnapshot(),
                JsonUtils.toJson(ModelSemanticsDto.forRun(
                        input.attackScenario(), input.enablePrivacy(), input.attackSurface())));
        try {
            enqueueVerificationTask(userId, taskId, input);
        } catch (RuntimeException e) {
            if (!cleanupUndispatchedTask(userId, taskId, e)) {
                throw new AsyncTaskDispatchOutcomeUnknownException("verification", taskId, e);
            }
            if (e instanceof TaskRejectedException) {
                log.warn("Verification task {} rejected before dispatch; task record removed", taskId);
                throw new ServiceUnavailableException("Server busy, please try again later", e);
            }
            throw e;
        }
        return taskId;
    }

    // Service-internal: task creation is only reachable through submitVerification and
    // the package-private async path below; it is no longer part of the public interface.
    // Deliberately not @Transactional: self-invocation bypasses the proxy, so the annotation would
    // never start one. The transaction lives in the private overload's transactionTemplate.
    Long createTask(Long userId,
                    AttackScenarioDto attackScenario,
                    boolean enablePrivacy,
                    int devicePointCount,
                    int linkPointCount,
                    int falsifiableReadingDeviceCount,
                    ModelRunSnapshotDto modelSnapshot) {
        AttackScenarioDto requiredScenario = Objects.requireNonNull(
                attackScenario, "attackScenario is required");
        return createTask(userId, requiredScenario, enablePrivacy,
                devicePointCount, linkPointCount, falsifiableReadingDeviceCount, modelSnapshot,
                JsonUtils.toJson(ModelSemanticsDto.forRun(
                        requiredScenario, enablePrivacy, devicePointCount, linkPointCount,
                        falsifiableReadingDeviceCount)));
    }

    private Long createTask(Long userId,
                            AttackScenarioDto attackScenario,
                            boolean enablePrivacy,
                            int devicePointCount,
                            int linkPointCount,
                            int falsifiableReadingDeviceCount,
                            ModelRunSnapshotDto modelSnapshot,
                            String modelSemanticsJson) {
        AttackScenarioDto requiredScenario = Objects.requireNonNull(
                attackScenario, "attackScenario is required");
        return transactionTemplate.execute(status -> {
            requireActiveUserForTracePersistence(userId);
            requireChatExecutionLease();
            long storedTaskCount = taskRepository.countByUserId(userId);
            if (storedTaskCount >= taskAdmissionLimits.getMaxStoredTasksPerUser()) {
                throw new AsyncTaskQuotaExceededException(
                        "verification", AsyncTaskQuotaExceededException.QuotaType.STORED,
                        storedTaskCount, taskAdmissionLimits.getMaxStoredTasksPerUser());
            }
            long activeTaskCount = taskRepository.countByUserIdAndStatusIn(
                    userId, List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING));
            if (activeTaskCount >= taskAdmissionLimits.getMaxActiveTasksPerUser()) {
                throw new AsyncTaskQuotaExceededException(
                        "verification", AsyncTaskQuotaExceededException.QuotaType.ACTIVE,
                        activeTaskCount, taskAdmissionLimits.getMaxActiveTasksPerUser());
            }
            LocalDateTime createdAt = databaseNow();
            VerificationTaskPo task = VerificationTaskPo.builder()
                    .userId(userId)
                    .initiator(RunInitiatorResolver.current())
                    .status(VerificationTaskPo.TaskStatus.PENDING)
                    .isAttack(requiredScenario.isEnabled())
                    .attackBudget(requiredScenario.effectiveBudget())
                    .modeledDeviceAttackPointCount(devicePointCount)
                    .modeledFalsifiableReadingDeviceCount(falsifiableReadingDeviceCount)
                    .modeledAutomationLinkAttackPointCount(linkPointCount)
                    .enablePrivacy(enablePrivacy)
                    .modelSnapshotJson(JsonUtils.toJson(modelSnapshot))
                    .modelSemanticsJson(modelSemanticsJson)
                    .createdAt(createdAt)
                    .progress(0)
                    .progressStage(TaskProgressStage.QUEUED)
                    .workerId(workerId)
                    .leaseExpiresAt(createdAt.plus(TASK_LEASE_DURATION))
                    .build();
            VerificationTaskPo saved = taskRepository.save(Objects.requireNonNull(task));
            log.info("Created verification task: {} for user: {}", saved.getId(), userId);
            return Objects.requireNonNull(saved.getId());
        });
    }

    // Service-internal failure compensation, reachable only from the submit/async paths below.
    // Deliberately not @Transactional: self-invocation bypasses the proxy. failTask owns the
    // transaction.
    void failTaskById(Long taskId, String errorMessage) {
        taskRepository.findById(Objects.requireNonNull(taskId, "taskId must not be null"))
                .ifPresent(task -> failTask(task, errorMessage));
    }

    // Package-private async entry: assumes the caller already created the task and passes a
    // non-null taskId. Production code goes through submitVerification; retained at this
    // visibility so same-package tests can drive the "execute with a fixed taskId" path.
    void verifyAsync(Long userId, Long taskId, VerificationRequestDto request) {
        Long requiredTaskId = requireTaskId(taskId);
        VerificationInput input;
        try {
            input = validateAndNormalize(userId, request);
        } catch (ValidationException e) {
            failTaskById(requiredTaskId, e.getMessage());
            throw e;
        }
        try {
            persistTaskModelContext(requiredTaskId, input.attackScenario(), input.enablePrivacy(),
                    input.modeledDeviceAttackPointCount(), input.modeledAutomationLinkAttackPointCount(),
                    input.modeledFalsifiableReadingDeviceCount(), input.modelSnapshot(),
                    JsonUtils.toJson(ModelSemanticsDto.forRun(
                            input.attackScenario(), input.enablePrivacy(), input.attackSurface())));
            enqueueVerificationTask(userId, requiredTaskId, input);
        } catch (RuntimeException e) {
            String message = e instanceof TaskRejectedException
                    ? "Server busy, please try again later"
                    : "Task dispatch failed before execution: " + e.getClass().getSimpleName();
            failTaskById(requiredTaskId, message);
            if (e instanceof TaskRejectedException) {
                throw new ServiceUnavailableException("Server busy, please try again later", e);
            }
            throw e;
        }
    }

    private boolean cleanupUndispatchedTask(Long userId, Long taskId, RuntimeException failure) {
        try {
            int deleted = taskRepository.deleteUndispatchedTask(
                    taskId, userId, workerId, VerificationTaskPo.TaskStatus.PENDING);
            if (deleted == 1) return true;
            boolean absent = taskRepository.findByIdAndUserId(taskId, userId).isEmpty();
            if (!absent) {
                log.error("Could not remove verification task {} after failure before dispatch", taskId);
            }
            return absent;
        } catch (RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
            log.error("Could not remove verification task {} after failure before dispatch",
                    taskId, cleanupError);
            return false;
        }
    }

    private void enqueueVerificationTask(Long userId, Long taskId, VerificationInput input) {
        Long requiredTaskId = requireTaskId(taskId);
        VerificationInput requiredInput = Objects.requireNonNull(input, "verification input must not be null");
        LocalVerificationExecution execution =
                new LocalVerificationExecution(userId, requiredTaskId, requiredInput);
        if (localExecutions.putIfAbsent(requiredTaskId, execution) != null) {
            throw new IllegalStateException("Duplicate local verification task execution " + requiredTaskId);
        }
        try {
            verificationTaskExecutor.execute(execution.futureTask);
        } catch (RuntimeException e) {
            execution.requestStop();
            throw e;
        }
    }

    private LocalDateTime databaseNow() {
        return Objects.requireNonNull(
                taskRepository.currentDatabaseTime(),
                "Database current timestamp must not be null");
    }

    private void purgeCancelledVerificationTasks() {
        try {
            ThreadPoolExecutor executor = verificationTaskExecutor.getThreadPoolExecutor();
            if (executor != null) executor.purge();
        } catch (RuntimeException e) {
            log.warn("Could not purge cancelled verification tasks from the local executor queue", e);
        }
    }

    private Long requireTaskId(Long taskId) {
        if (taskId == null) {
            throw new ValidationException("taskId", "Task id cannot be null");
        }
        return taskId;
    }

    private void runVerificationTask(Long userId, Long taskId, VerificationInput input) {
        String requestJson = buildRequestSnapshot(input.request());
        String templateSnapshotsJson = buildTemplateSnapshotsJson(
                input.templateManifests(), input.deviceSmvMap());
        log.info("Starting async verification task: {} for user: {}", taskId, userId);

        File smvFile = null;
        VerificationTaskPo task = null;
        VerificationResultDto finalResult = null;
        try {
            // Both of these must be inside the try: `updateTaskProgress` writes the in-memory map
            // before its database write, so a database failure here would otherwise leave this
            // thread registered as running the task. The pooled thread then picks up a different
            // task, and a later cancel of *this* task interrupts that unrelated work.
            registerRunningTask(taskId, Thread.currentThread());
            updateTaskProgress(taskId, 0, TaskProgressStage.STARTING);

            // Check in-memory cancellation marker (fast path for same-instance cancellation).
            if (isTaskCancelled(taskId)) {
                return;
            }

            // Atomically transition PENDING → RUNNING to close the cancel-vs-start race window.
            // A plain findById + save was vulnerable to TOCTOU: a concurrent cancel could set
            // CANCELLED between the read and the save, and the save would overwrite it back to RUNNING.
            String startCheckLogs = serializeCheckLogs(List.of("Task started"));
            TaskLeaseRenewal.LeaseUpdateResult start = TaskLeaseRenewal.updateWithConfirmation(
                    transactionTemplate,
                    () -> taskRepository.findByIdForUpdate(taskId),
                    this::databaseNow,
                    (lockedTask, currentTime) -> taskRepository.startTaskIfStillPending(
                            taskId,
                            VerificationTaskPo.TaskStatus.RUNNING,
                            currentTime, 0, startCheckLogs,
                            VerificationTaskPo.TaskStatus.PENDING,
                            workerId,
                            currentTime,
                            currentTime.plus(TASK_LEASE_DURATION)));
            if (start.updated() == 0) {
                log.info("Task {} is no longer PENDING (cancelled or already started), aborting", taskId);
                return;
            }
            if (!TaskLeaseRenewal.completedBeforeTtl(start.confirmationStartedNanos(), TASK_LEASE_DURATION)) {
                log.warn("Verification task {} lease expired before its start was committed", taskId);
                return;
            }
            LocalVerificationExecution localExecution = localExecutions.get(taskId);
            if (localExecution != null) {
                localExecution.leaseConfirmation.confirmAt(start.confirmationStartedNanos());
            }

            // Load entity for subsequent use (failTask/completeTask only need id and startedAt).
            task = taskRepository.findById(Objects.requireNonNull(taskId)).orElse(null);
            if (task == null) {
                log.error("Task not found after atomic start: {}", taskId);
                return;
            }

            updateTaskProgress(taskId, 20, TaskProgressStage.GENERATING_MODEL);
            SmvGenerator.GenerateResult genResult = generateResolvedModel(
                    userId, input.devices(), input.request().getEnvironmentVariables(), input.rules(), input.specs(),
                    input.attackScenario(), input.enablePrivacy(), SmvGenerator.GeneratePurpose.VERIFICATION,
                    SmvGenerator.TempModelContext.task(taskId), input.deviceSmvMap());
            smvFile = genResult.smvFile();
            Map<String, DeviceSmvData> deviceSmvMap = genResult.deviceSmvMap();
            List<String> checkLogs = new ArrayList<>();
            checkLogs.add("Generating NuSMV model...");
            appendGenerationWarnings(checkLogs, genResult);
            if (isTaskCancelled(taskId) || Thread.currentThread().isInterrupted()) {
                return;
            }
            if (smvFile == null || !smvFile.exists()) {
                String msg = "Failed to generate NuSMV model file";
                failTask(task, msg);
                finalResult = buildErrorResult("", List.of(msg));
                return;
            }
            checkLogs.add("Model generated: " + smvFile.getName());
            saveRequestJson(smvFile, requestJson);

            // Read SMV model content for persistence
            String smvModelContent = readSmvModelContent(smvFile);

            updateTaskProgress(taskId, 50, TaskProgressStage.EXECUTING_MODEL_CHECKER);
            checkLogs.add("Executing NuSMV verification...");
            NusmvResult result = nusmvExecutor.execute(smvFile);

            if (isTaskCancelled(taskId) || Thread.currentThread().isInterrupted()) {
                return;
            }

            if (!result.isSuccess()) {
                String msg = "NuSMV execution failed: " + result.getErrorMessage();
                failTask(task, msg);
                finalResult = buildErrorResult("", List.of(msg));
                return;
            }
            checkLogs.add("NuSMV execution completed.");

            updateTaskProgress(taskId, 80, TaskProgressStage.PARSING_RESULTS);
            finalResult = buildVerificationResult(
                    result, input.devices(), input.rules(), input.specs(), userId, taskId, checkLogs, deviceSmvMap,
                    input.templateManifests(), requestJson,
                    genResult.emittedSpecs(), genResult.generationIssues(),
                    genResult.disabledRuleCount(), genResult.skippedSpecCount(), smvModelContent);
            applyRunContext(finalResult, input.attackScenario(), input.enablePrivacy(),
                    input.attackSurface(), input.modelSnapshot(), templateSnapshotsJson, input.request());

            if (isTaskCancelled(taskId) || Thread.currentThread().isInterrupted()) {
                return;
            }

            boolean completed = completeTaskAndSaveTraces(task, finalResult.getTraces(), userId, taskId, finalResult.getOutcome(),
                    countViolatedSpecs(finalResult.getSpecResults(), finalResult.getTraces()),
                    finalResult.getSpecResults(), finalResult.getCheckLogs(), truncateOutput(result.getOutput()),
                    finalResult.getGenerationIssues(),
                    finalResult.getDisabledRuleCount(), finalResult.getSkippedSpecCount(),
                    finalResult.getSmvModelContent());
            if (!completed && !isCompletionCancelled(taskId)) {
                failTask(task, "RESULT_PERSISTENCE_FAILED: Verification finished, but its result could not be saved.");
            }

        } catch (Exception e) {
            if (isTaskCancelled(taskId)) {
                // Exceptions caused by cancellation are handled in finally.
                log.info("Async verification cancelled for task: {}", taskId);
            } else {
                String msg = "Verification failed: " + e.getMessage();
                log.error("Async verification failed for task: {}", taskId, e);
                failTask(task, msg);
                finalResult = buildErrorResult("", List.of(msg));
            }
        } finally {
            if (finalResult != null) {
                applyRunContext(finalResult, input.attackScenario(), input.enablePrivacy(),
                        input.attackSurface(), input.modelSnapshot(), templateSnapshotsJson, input.request());
                saveResultJson(smvFile, finalResult);
            }
            cleanupTempFile(smvFile);
            // Unified cancellation handling.
            if (removeCancelledMark(taskId)) {
                if (task != null) handleCancellation(task);
            }
            removeRunningTask(taskId);
            removeTaskProgress(taskId);
            try {
                taskRepository.releaseOwnedActiveLease(
                        taskId, workerId, databaseNow().minusSeconds(1), ACTIVE_STATUSES);
            } catch (RuntimeException e) {
                log.warn("Could not release verification task {} lease; it will expire naturally", taskId, e);
            }
        }
    }

    private record VerificationInput(List<DeviceVerificationDto> devices,
                                     List<RuleDto> rules,
                                     List<SpecificationDto> specs,
                                     boolean enablePrivacy,
                                     int modeledDeviceAttackPointCount,
                                     int modeledAutomationLinkAttackPointCount,
                                     int modeledFalsifiableReadingDeviceCount,
                                     AttackScenarioDto attackScenario,
                                     VerificationRequestDto request,
                                     Map<String, DeviceSmvData> deviceSmvMap,
                                     Map<String, DeviceManifest> templateManifests,
                                     ModelRunSnapshotDto modelSnapshot,
                                     AttackSurface attackSurface) {
        private VerificationInput {
            Objects.requireNonNull(attackScenario, "attackScenario is required");
        }

        boolean attack() {
            return attackScenario.isEnabled();
        }

        int attackBudget() {
            return attackScenario.effectiveBudget();
        }
    }

    private record ModelBoundaryInput(List<DeviceVerificationDto> devices,
                                       List<BoardEnvironmentVariableDto> environmentVariables,
                                       AttackSurface attackSurface,
                                       Map<String, DeviceSmvData> deviceSmvMap,
                                       Map<String, DeviceManifest> templateManifests,
                                       ModelRunSnapshotDto modelSnapshot) {}

    // ==================== 查询方法 ====================

    @Override
    @Transactional(readOnly = true)
    public VerificationTaskDto getTask(Long userId, Long taskId) {
        VerificationTaskPo task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("VerificationTask", taskId));
        task.setCheckLogs(readCheckLogs(task));
        return verificationTaskMapper.toDto(task);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VerificationTaskSummaryDto> getTasks(Long userId, List<Long> excludedTaskIds) {
        List<Long> normalizedExcludedIds = normalizeExcludedTaskIds(excludedTaskIds);
        List<VerificationTaskSummaryProjection> tasks = normalizedExcludedIds.isEmpty()
                ? taskRepository.findSummaryByUserIdAndStatusNotOrderByCreatedAtDesc(
                        userId, VerificationTaskPo.TaskStatus.COMPLETED)
                : taskRepository.findSummaryByUserIdAndStatusNotAndIdNotInOrderByCreatedAtDesc(
                        userId, VerificationTaskPo.TaskStatus.COMPLETED, normalizedExcludedIds);
        return verificationTaskMapper.toSummaryProjectionDtoList(
                tasks);
    }

    @Override
    @Transactional
    public void deleteTask(Long userId, Long taskId) {
        VerificationTaskPo task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("VerificationTask", taskId));
        if (task.getStatus() == VerificationTaskPo.TaskStatus.PENDING
                || task.getStatus() == VerificationTaskPo.TaskStatus.RUNNING) {
            throw new BadRequestException("An active verification task must be cancelled before it can be removed");
        }
        if (task.getStatus() == VerificationTaskPo.TaskStatus.COMPLETED) {
            throw new BadRequestException("Completed verification results must be removed from run history");
        }
        taskRepository.delete(Objects.requireNonNull(task));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VerificationRunSummaryDto> getRuns(Long userId) {
        List<VerificationRunSummaryProjection> runs =
                taskRepository.findCompletedRunSummaries(
                        userId, VerificationTaskPo.TaskStatus.COMPLETED,
                        PageRequest.of(0, taskAdmissionLimits.getMaxStoredTasksPerUser()));
        if (runs.isEmpty()) return List.of();
        List<Long> runIds = runs.stream().map(VerificationRunSummaryProjection::getId).toList();
        Map<Long, List<TraceSummaryDto>> tracesByRun = new LinkedHashMap<>();
        for (TraceSummaryProjection trace :
                traceRepository.findSummariesByUserIdAndVerificationTaskIdIn(userId, runIds)) {
            if (trace == null || trace.getVerificationTaskId() == null) continue;
            tracesByRun.computeIfAbsent(trace.getVerificationTaskId(), ignored -> new ArrayList<>())
                    .add(toTraceSummaryOrUnavailable(trace));
        }
        return runs.stream()
                .map(run -> toRunSummaryOrUnavailable(
                        run, tracesByRun.getOrDefault(run.getId(), List.of())))
                .toList();
    }

    private VerificationRunSummaryDto toRunSummaryOrUnavailable(
            VerificationRunSummaryProjection run, List<TraceSummaryDto> counterexamples) {
        int replayableCounterexampleCount = replayableCounterexampleCount(counterexamples);
        try {
            VerificationRunSummaryDto summary = verificationTaskMapper.toRunSummaryDto(
                    run, replayableCounterexampleCount);
            summary.setCounterexamples(List.copyOf(counterexamples));
            summary.setDataAvailable(true);
            return summary;
        } catch (PersistedDataIntegrityException e) {
            log.error("Verification history item {} is unavailable because persisted data is invalid",
                    run != null ? run.getId() : null, e);
            return VerificationRunSummaryDto.builder()
                    .id(run != null ? run.getId() : null)
                    .initiator(run != null && run.getInitiator() != null
                            ? run.getInitiator() : RunInitiator.UNKNOWN)
                    .createdAt(run != null ? run.getCreatedAt() : null)
                    .startedAt(run != null ? run.getStartedAt() : null)
                    .completedAt(run != null ? run.getCompletedAt() : null)
                    .processingTimeMs(run != null ? run.getProcessingTimeMs() : null)
                    .counterexampleCount(replayableCounterexampleCount)
                    .counterexamples(List.copyOf(counterexamples))
                    .dataAvailable(false)
                    .unavailableReasonCode("PERSISTED_SEMANTIC_DATA_INVALID")
                    .build();
        }
    }

    private TraceSummaryDto toTraceSummaryOrUnavailable(TraceSummaryProjection trace) {
        try {
            return traceMapper.toSummaryDto(trace);
        } catch (PersistedDataIntegrityException e) {
            log.error("Verification trace summary {} is unavailable because persisted data is invalid",
                    trace != null ? trace.getId() : null, e);
            return TraceSummaryDto.builder()
                    .id(trace != null ? trace.getId() : null)
                    .verificationTaskId(trace != null ? trace.getVerificationTaskId() : null)
                    .violatedSpecId(trace != null ? trace.getViolatedSpecId() : null)
                    .createdAt(trace != null ? trace.getCreatedAt() : null)
                    .dataAvailable(false)
                    .unavailableReasonCode("PERSISTED_SEMANTIC_DATA_INVALID")
                    .build();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public VerificationRunDto getRun(Long userId, Long runId) {
        VerificationTaskPo run = getCompletedRun(userId, runId);
        run.setCheckLogs(readCheckLogs(run));
        List<TraceSummaryDto> counterexamples = traceRepository
                .findSummariesByUserIdAndVerificationTaskIdIn(userId, List.of(runId))
                .stream()
                .filter(Objects::nonNull)
                .map(this::toTraceSummaryOrUnavailable)
                .toList();
        return verificationTaskMapper.toRunDto(run, replayableCounterexampleCount(counterexamples));
    }

    @Override
    @Transactional(readOnly = true)
    public RunDeletionImpactDto getRunDeletionImpact(Long userId, Long runId) {
        CompletedRunDeletionProjection run = taskRepository.findDeletionProjection(
                        runId, userId, VerificationTaskPo.TaskStatus.COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException("VerificationRun", runId));
        long traceCount = traceRepository.countByUserIdAndVerificationTaskId(userId, runId);
        return RunDeletionImpactDto.builder()
                .runId(run.getId())
                .evidenceCount(traceCount)
                .createdAt(run.getCreatedAt())
                .completedAt(run.getCompletedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TraceDto> getRunTraces(Long userId, Long runId) {
        getCompletedRun(userId, runId);
        return traceMapper.toDtoList(traceRepository.findByUserIdAndVerificationTaskId(userId, runId));
    }

    @Override
    @Transactional
    public void deleteRun(Long userId, Long runId) {
        deleteRunInternal(userId, runId, null);
    }

    @Override
    @Transactional
    public long deleteRun(Long userId, Long runId, long expectedTraceCount) {
        if (expectedTraceCount < 0) {
            throw new IllegalArgumentException("Expected trace count must not be negative");
        }
        return deleteRunInternal(userId, runId, expectedTraceCount);
    }

    private long deleteRunInternal(Long userId, Long runId, Long expectedTraceCount) {
        taskRepository.findCompletedRunForUpdate(
                        runId, userId, VerificationTaskPo.TaskStatus.COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException("VerificationRun", runId));
        long actualTraceCount = traceRepository.countByUserIdAndVerificationTaskId(userId, runId);
        if (expectedTraceCount != null && actualTraceCount != expectedTraceCount) {
            throw staleDeletionImpact("counterexample trace", expectedTraceCount, actualTraceCount);
        }
        int deletedTraceCount = traceRepository.deleteByUserIdAndVerificationTaskId(userId, runId);
        if (deletedTraceCount != actualTraceCount) {
            throw staleDeletionImpact("counterexample trace", actualTraceCount, deletedTraceCount);
        }
        int deletedRunCount = taskRepository.deleteCompletedRun(
                runId, userId, VerificationTaskPo.TaskStatus.COMPLETED);
        if (deletedRunCount != 1) {
            throw new ConflictException(
                    "Verification run changed during deletion; preview the deletion again before confirming");
        }
        return actualTraceCount;
    }

    private ConflictException staleDeletionImpact(String evidenceKind, long expected, long actual) {
        return new ConflictException("Run deletion impact changed from " + expected + " to " + actual
                + " " + evidenceKind + " rows; preview the deletion again before confirming");
    }

    @Override
    public String getRunSmvModel(Long userId, Long runId) {
        // Same ownership and completeness gate as every other run read, so one user cannot fetch
        // another's model and an in-flight run cannot expose a half-written one.
        return getCompletedRun(userId, runId).getSmvModelContent();
    }

    private VerificationTaskPo getCompletedRun(Long userId, Long runId) {
        VerificationTaskPo run = taskRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("VerificationRun", runId));
        if (run.getStatus() != VerificationTaskPo.TaskStatus.COMPLETED) {
            throw new ResourceNotFoundException("VerificationRun", runId);
        }
        return run;
    }

    private int replayableCounterexampleCount(List<TraceSummaryDto> counterexamples) {
        if (counterexamples == null || counterexamples.isEmpty()) return 0;
        long count = counterexamples.stream()
                .filter(Objects::nonNull)
                .filter(trace -> Boolean.TRUE.equals(trace.getDataAvailable()))
                .count();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private static List<Long> normalizeExcludedTaskIds(List<Long> excludedTaskIds) {
        if (excludedTaskIds == null || excludedTaskIds.isEmpty()) {
            return List.of();
        }
        return excludedTaskIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TraceDto> getUserTraces(Long userId) {
        List<TracePo> traces = traceRepository.findByUserId(userId).stream()
                .sorted(Comparator
                        .comparing(TracePo::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TracePo::getId,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return traceMapper.toDtoList(traces);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TraceDto> getTracesByTask(Long userId, Long taskId) {
        return traceMapper.toDtoList(traceRepository.findByUserIdAndVerificationTaskId(userId, taskId));
    }

    @Override
    @Transactional(readOnly = true)
    public TraceDto getTrace(Long userId, Long traceId) {
        return traceRepository.findByIdAndUserId(traceId, userId)
                .map(traceMapper::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Trace", traceId));
    }

    @Override
    @Transactional
    public void deleteTrace(Long userId, Long traceId) {
        requireChatExecutionLease();
        TracePo trace = traceRepository.findByIdAndUserId(traceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Trace", traceId));
        traceRepository.delete(Objects.requireNonNull(trace));
    }

    @Override
    @Transactional
    public TaskCancellationResultDto cancelTask(Long userId, Long taskId) {
        chatExecutionLeaseGuard.requireCurrentExecutionLeaseAndLock();
        return super.cancelTask(userId, taskId);
    }

    private void persistTaskModelContext(Long taskId,
                                         AttackScenarioDto attackScenario,
                                         boolean enablePrivacy,
                                         int devicePointCount,
                                         int linkPointCount,
                                         int falsifiableReadingDeviceCount,
                                         ModelRunSnapshotDto modelSnapshot,
                                         String modelSemanticsJson) {
        AttackScenarioDto requiredScenario = Objects.requireNonNull(
                attackScenario, "attackScenario is required");
        taskRepository.updateModelContext(taskId, requiredScenario.isEnabled(),
                requiredScenario.effectiveBudget(), enablePrivacy,
                devicePointCount, falsifiableReadingDeviceCount, linkPointCount,
                JsonUtils.toJson(modelSnapshot), modelSemanticsJson);
    }

    @Override
    public void updateTaskProgress(Long taskId, int progress, TaskProgressStage stage) {
        super.updateTaskProgress(taskId, progress, stage);
    }

    @Override
    @Transactional(readOnly = true)
    public int getTaskProgress(Long userId, Long taskId) {
        return super.getTaskProgress(userId, taskId);
    }

    // ==================== Core: build per-spec verification result ====================

    private VerificationResultDto buildVerificationResult(NusmvResult result,
                                                          List<DeviceVerificationDto> devices,
                                                          List<RuleDto> rules,
                                                          List<SpecificationDto> specs,
                                                          Long userId, Long taskId,
                                                          List<String> checkLogs,
                                                          Map<String, DeviceSmvData> deviceSmvMap,
                                                          Map<String, DeviceManifest> templateManifests,
                                                          String requestJson,
                                                          List<SmvGenerationContext.EmittedSpec> emittedSpecs,
                                                          List<ModelGenerationIssueDto> generationIssues,
                                                          int disabledRuleCount,
                                                          int skippedSpecCount,
                                                          String smvModelContent) {
        List<SpecResultDto> specResults = new ArrayList<>();
        List<TraceDto> traces = new ArrayList<>();
        List<SpecCheckResult> rawSpecCheckResults = result.getSpecResults();
        List<SmvGenerationContext.EmittedSpec> effectiveSpecs = emittedSpecs != null ? emittedSpecs : List.of();
        SpecificationFormulaPreview.Context formulaPreviewContext =
                SpecificationFormulaPreview.modelContext(devices, templateManifests);

        // effectiveSpecs=0 means all specs were filtered out before SMV emission.
        if (effectiveSpecs.isEmpty()) {
            checkLogs.add("No valid specifications to verify (no specifications were emitted to NuSMV; all filtered out)");
            checkLogs.add(VerificationOutcome.INCONCLUSIVE_LOG_MARKER
                    + " No specifications were emitted, so verification has no conclusion.");
            return VerificationResultDto.builder()
                    .outcome(VerificationOutcome.INCONCLUSIVE)
                    .modelComplete(false)
                    .traces(List.of()).specResults(List.of())
                    .checkLogs(checkLogs)
                    .disabledRuleCount(disabledRuleCount)
                    .skippedSpecCount(skippedSpecCount)
                    .generationIssues(generationIssues)
                    .nusmvOutput(truncateOutput(result.getOutput()))
                    // An inconclusive run still checked a model, and that model is what explains why
                    // nothing was emitted — so it is worth keeping, not only kept on the success path.
                    .smvModelContent(smvModelContent)
                    .build();
        }

        // Fail-closed: mark unsafe when per-spec results cannot be reliably parsed.
        boolean parseIncomplete = false;

        if (rawSpecCheckResults.isEmpty()) {
            checkLogs.add("Warning: could not parse per-spec results from NuSMV output");
            log.warn("No spec results parsed from NuSMV output; reporting all {} emitted specs as inconclusive", effectiveSpecs.size());
            for (SmvGenerationContext.EmittedSpec emittedSpec : effectiveSpecs) {
                specResults.add(toSpecResult(
                        emittedSpec, VerificationOutcome.INCONCLUSIVE, null, formulaPreviewContext));
            }
            parseIncomplete = true;
        } else {
            List<SpecCheckResult> specCheckResults = alignSpecResultsToEmittedSpecs(rawSpecCheckResults, effectiveSpecs, checkLogs);
            boolean hasMissingResult = specCheckResults.stream().anyMatch(Objects::isNull);

            if (rawSpecCheckResults.size() != effectiveSpecs.size() || hasMissingResult) {
            // 数量不一致：结果不可信，不把未知结果误报为通过或违反。
            log.warn("Spec count mismatch: NuSMV returned {} results but {} specs were generated. Reporting the run as inconclusive.",
                    rawSpecCheckResults.size(), effectiveSpecs.size());
            checkLogs.add("Warning: spec result count mismatch (got " + rawSpecCheckResults.size()
                    + ", expected " + effectiveSpecs.size() + ")");
            parseIncomplete = true;

            for (int specIdx = 0; specIdx < effectiveSpecs.size(); specIdx++) {
                SpecCheckResult scr = specCheckResults.get(specIdx);
                SmvGenerationContext.EmittedSpec emittedSpec = effectiveSpecs.get(specIdx);
                if (scr != null) {
                    appendParsedSpecResult(specResults, traces, specIdx, scr, emittedSpec,
                            userId, taskId, checkLogs, deviceSmvMap, rules, requestJson,
                            formulaPreviewContext, smvModelContent);
                } else {
                    specResults.add(toSpecResult(
                            emittedSpec, VerificationOutcome.INCONCLUSIVE, null, formulaPreviewContext));
                    checkLogs.add("Spec " + describeSpec(emittedSpec, specIdx)
                            + " result missing, reported as inconclusive");
                }
            }

            if (rawSpecCheckResults.size() > effectiveSpecs.size()) {
                checkLogs.add("Warning: " + (rawSpecCheckResults.size() - effectiveSpecs.size())
                        + " extra NuSMV result(s) discarded");
            }
            } else {
                int specIdx = 0;
                for (SpecCheckResult scr : specCheckResults) {
                    appendParsedSpecResult(specResults, traces, specIdx, scr, effectiveSpecs.get(specIdx),
                            userId, taskId, checkLogs, deviceSmvMap, rules, requestJson,
                            formulaPreviewContext, smvModelContent);
                    specIdx++;
                }
            }
        }

        boolean allPassed = !parseIncomplete && specResults.stream()
                .allMatch(specResult -> specResult.getOutcome() == VerificationOutcome.SATISFIED);
        if (parseIncomplete) {
            checkLogs.add(VerificationOutcome.INCONCLUSIVE_LOG_MARKER
                    + " Per-spec results are incomplete or unreliable; no satisfied/violated conclusion is reported.");
        } else if (!allPassed) {
            checkLogs.add("Some specifications violated.");
        } else {
            checkLogs.add("All specifications satisfied.");
        }

        VerificationOutcome outcome = parseIncomplete
                ? VerificationOutcome.INCONCLUSIVE
                : (allPassed ? VerificationOutcome.SATISFIED : VerificationOutcome.VIOLATED);
        boolean modelComplete = outcome.isModelComplete(disabledRuleCount, skippedSpecCount);
        for (TraceDto trace : traces) {
            trace.setDisabledRuleCount(disabledRuleCount);
            trace.setSkippedSpecCount(skippedSpecCount);
            trace.setModelComplete(modelComplete);
            trace.setGenerationIssues(generationIssues);
        }

        return VerificationResultDto.builder()
                .outcome(outcome)
                .modelComplete(modelComplete)
                .traces(traces)
                .specResults(specResults)
                .checkLogs(checkLogs)
                .disabledRuleCount(disabledRuleCount)
                .skippedSpecCount(skippedSpecCount)
                .generationIssues(generationIssues)
                .nusmvOutput(truncateOutput(result.getOutput()))
                // Also on the result, not only on each trace: a run with no violated specification has no
                // trace to carry it, and that run's model still has to be storable and downloadable.
                .smvModelContent(smvModelContent)
                .build();
    }

    private List<SpecCheckResult> alignSpecResultsToEmittedSpecs(List<SpecCheckResult> rawSpecResults,
                                                                  List<SmvGenerationContext.EmittedSpec> emittedSpecs,
                                                                  List<String> checkLogs) {
        SpecResultAlignment.Alignment alignment = SpecResultAlignment.align(rawSpecResults, emittedSpecs);
        /*
         * A back-filled result keeps `rawSpecResults.size() == effectiveSpecs.size()`, so `parseIncomplete` stays
         * false and the run reports a definite SATISFIED/VIOLATED whose per-spec attribution was guessed — a user
         * could then fix the rule behind the wrong specification. Silence here was the defect, so it is logged.
         */
        if (alignment.backFilled() > 0 && checkLogs != null) {
            checkLogs.add("[spec-attribution-uncertain] " + alignment.backFilled()
                    + " specification result(s) could not be matched to a submitted specification by expression "
                    + "and were assigned by position; which specification each of those verdicts describes is "
                    + "not certain.");
        }
        if (alignment.reordered() && checkLogs != null) {
            checkLogs.add("NuSMV returned specification results in a different order; results were matched by expression.");
        }
        return alignment.aligned();
    }

    private void appendParsedSpecResult(List<SpecResultDto> specResults,
                                        List<TraceDto> traces,
                                        int specIdx,
                                        SpecCheckResult scr,
                                        SmvGenerationContext.EmittedSpec emittedSpec,
                                        Long userId,
                                        Long taskId,
                                        List<String> checkLogs,
                                        Map<String, DeviceSmvData> deviceSmvMap,
                                        List<RuleDto> rules,
                                        String requestJson,
                                        SpecificationFormulaPreview.Context formulaPreviewContext,
                                        String smvModelContent) {
        specResults.add(toSpecResult(
                emittedSpec,
                scr.isPassed() ? VerificationOutcome.SATISFIED : VerificationOutcome.VIOLATED,
                scr.getSpecExpression(),
                formulaPreviewContext));
        if (!scr.isPassed() && scr.getCounterexample() != null) {
            checkLogs.add("Spec " + describeSpec(emittedSpec, specIdx) + " violated: "
                    + firstText(scr.getSpecExpression(), expression(emittedSpec)));
            List<TraceStateDto> states = smvTraceParser.parseCounterexampleStates(
                    scr.getCounterexample(), deviceSmvMap, rules);
            if (!states.isEmpty()) {
                SpecificationDto violatedSpec = emittedSpec.spec();
                TraceDto trace = TraceDto.builder()
                        .userId(userId)
                        .verificationTaskId(taskId)
                        .violatedSpecId(specId(emittedSpec))
                        .violatedSpecJson(violatedSpec != null ? specificationMapper.toJson(violatedSpec) : null)
                        .violatedSpec(violatedSpec)
                        .checkedExpression(scr.getSpecExpression())
                        .states(states)
                        .requestJson(requestJson)
                        // No `.smvModelContent(...)`: the run row this trace belongs to holds the one
                        // model that was checked, and copying it per counterexample stored the same
                        // bytes N+1 times for N violations. See `TracePo`.
                        .createdAt(LocalDateTime.now())
                        .build();
                traces.add(trace);
            }
        } else if (scr.isPassed()) {
            checkLogs.add("Spec " + describeSpec(emittedSpec, specIdx) + " satisfied");
        } else {
            checkLogs.add("Spec " + describeSpec(emittedSpec, specIdx)
                    + " violated (no counterexample): "
                    + firstText(scr.getSpecExpression(), expression(emittedSpec)));
        }
    }

    private SpecResultDto toSpecResult(SmvGenerationContext.EmittedSpec emittedSpec,
                                       VerificationOutcome outcome,
                                       String parsedExpression,
                                       SpecificationFormulaPreview.Context formulaPreviewContext) {
        SpecificationDto spec = emittedSpec != null ? emittedSpec.spec() : null;
        String templateId = spec != null ? firstText(spec.getTemplateId(), "unknown") : "unknown";
        String checkedExpression = firstText(parsedExpression, expression(emittedSpec));
        return SpecResultDto.builder()
                .specId(specId(emittedSpec))
                .templateId(templateId)
                .specificationLabel(SpecificationFormulaPreview.templateLabel(templateId))
                .formulaPreview(spec != null
                        ? SpecificationFormulaPreview.format(spec, formulaPreviewContext)
                        : "Structured specification")
                .formulaKind(checkedFormulaKind(checkedExpression, templateId))
                .outcome(outcome)
                .expression(checkedExpression)
                .build();
    }

    private String checkedFormulaKind(String expression, String templateId) {
        String normalized = expression == null ? "" : expression.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("LTLSPEC") || normalized.startsWith("LTL SPEC")) {
            return "LTL";
        }
        if (normalized.startsWith("CTLSPEC") || normalized.startsWith("CTL SPEC")) {
            return "CTL";
        }
        return "6".equals(templateId) ? "LTL" : "CTL";
    }

    private String specId(SmvGenerationContext.EmittedSpec emittedSpec) {
        if (emittedSpec != null && emittedSpec.specId() != null && !emittedSpec.specId().isBlank()) {
            return emittedSpec.specId();
        }
        SpecificationDto spec = emittedSpec != null ? emittedSpec.spec() : null;
        return specId(spec);
    }

    private String specId(SpecificationDto spec) {
        if (spec != null && spec.getId() != null && !spec.getId().isBlank()) {
            return spec.getId();
        }
        return SmvConstants.UNKNOWN_VIOLATED_SPEC_ID;
    }

    private String describeSpec(SmvGenerationContext.EmittedSpec emittedSpec, int index) {
        String id = specId(emittedSpec);
        return "#" + (index + 1) + " (" + id + ")";
    }

    private String expression(SmvGenerationContext.EmittedSpec emittedSpec) {
        if (emittedSpec != null && emittedSpec.expression() != null) {
            return emittedSpec.expression();
        }
        return "";
    }

    private String firstText(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return "";
    }

    // ==================== Task Status Management ====================

    private int countViolatedSpecs(List<SpecResultDto> specResults, List<TraceDto> traces) {
        if (specResults != null && !specResults.isEmpty()) {
            return (int) specResults.stream()
                    .filter(Objects::nonNull)
                    .filter(specResult -> specResult.getOutcome() == VerificationOutcome.VIOLATED)
                    .count();
        }
        return traces != null ? traces.size() : 0;
    }

    private boolean completeTask(VerificationTaskPo task, VerificationOutcome outcome, int violatedSpecCount,
                                 List<SpecResultDto> specResults, List<String> checkLogs, String nusmvOutput,
                                 List<ModelGenerationIssueDto> generationIssues,
                                 int disabledRuleCount, int skippedSpecCount,
                                 String smvModelContent) {
        try {
            taskRepository.findByIdForUpdate(task.getId());
            LocalDateTime completedAt = databaseNow();
            Long processingTimeMs = task.getStartedAt() != null
                    ? java.time.Duration.between(task.getStartedAt(), completedAt).toMillis() : null;
            String checkLogsJson = serializeCheckLogs(checkLogs);
            String specResultsJson = JsonUtils.toJsonOrEmpty(specResults);
            String generationIssuesJson = JsonUtils.toJsonOrEmpty(generationIssues);
            int updated = taskRepository.completeTaskIfRunning(
                    task.getId(),
                    VerificationTaskPo.TaskStatus.COMPLETED,
                    completedAt, outcome, violatedSpecCount,
                    disabledRuleCount, skippedSpecCount,
                    specResultsJson,
                    checkLogsJson, generationIssuesJson, truncateOutput(nusmvOutput),
                    smvModelContent,
                    null, processingTimeMs,
                    VerificationTaskPo.TaskStatus.RUNNING,
                    workerId, completedAt);
            if (updated == 0) {
                log.info("Verification task {} was not RUNNING, no longer owned, or already terminal; skipping completion",
                        task.getId());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to complete task: {}", task.getId(), e);
            return false;
        }
    }

    private void failTask(VerificationTaskPo task, String errorMessage) {
        if (task == null) {
            log.warn("failTask called with null task, errorMessage={}", errorMessage);
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                taskRepository.findByIdForUpdate(task.getId());
                LocalDateTime completedAt = databaseNow();
                Long processingTimeMs = task.getStartedAt() != null
                        ? java.time.Duration.between(task.getStartedAt(), completedAt).toMillis() : null;
                String checkLogsJson = serializeCheckLogs(List.of(errorMessage));
                int updated = taskRepository.failTaskIfActive(
                        task.getId(),
                        VerificationTaskPo.TaskStatus.FAILED,
                        completedAt, VerificationOutcome.INCONCLUSIVE, errorMessage,
                        checkLogsJson, processingTimeMs,
                        List.of(VerificationTaskPo.TaskStatus.PENDING,
                                VerificationTaskPo.TaskStatus.RUNNING),
                        workerId, completedAt);
                if (updated == 0) {
                    log.info("Verification task {} was no longer active or owned; skipping fail", task.getId());
                }
            });
        } catch (Exception e) {
            log.error("Failed to mark task as failed: {}", task.getId(), e);
        }
    }

    // ==================== Utilities ====================

    private Long persistCompletedVerificationRun(Long userId,
                                                 VerificationInput input,
                                                 VerificationResultDto result,
                                                 LocalDateTime startedAt) {
        if (transactionTemplate == null) {
            throw new IllegalStateException("transactionTemplate is required to persist verification runs");
        }
        List<TraceDto> traces = result.getTraces() != null ? result.getTraces() : List.of();
        List<String> persistedCheckLogs = result.getCheckLogs() != null
                ? new ArrayList<>(result.getCheckLogs()) : new ArrayList<>();
        appendTracePersistenceLog(persistedCheckLogs, traces);
        Long runId = transactionTemplate.execute(status -> {
            formalOperationAdmission.registerCurrentLeaseCommitFence();
            if (!lockActiveUserForTracePersistence(userId)) {
                throw new InternalServerException("Verification completed after the user account was removed");
            }
            requireChatExecutionLease();
            enforceVerificationRunStorageCapacity(userId);

            LocalDateTime completedAt = LocalDateTime.now();
            VerificationOutcome outcome = result.getOutcome() != null
                    ? result.getOutcome()
                    : VerificationOutcome.INCONCLUSIVE;
            VerificationTaskPo run = VerificationTaskPo.builder()
                    .userId(userId)
                    .initiator(RunInitiatorResolver.current())
                    .status(VerificationTaskPo.TaskStatus.COMPLETED)
                    .createdAt(startedAt)
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .processingTimeMs(java.time.Duration.between(startedAt, completedAt).toMillis())
                    .isAttack(input.attack())
                    .attackBudget(input.attack() ? input.attackBudget() : 0)
                    .modeledDeviceAttackPointCount(input.modeledDeviceAttackPointCount())
                    .modeledFalsifiableReadingDeviceCount(input.modeledFalsifiableReadingDeviceCount())
                    .modeledAutomationLinkAttackPointCount(input.modeledAutomationLinkAttackPointCount())
                    .enablePrivacy(input.enablePrivacy())
                    .modelSnapshotJson(JsonUtils.toJson(input.modelSnapshot()))
                    .modelSemanticsJson(JsonUtils.toJson(result.getModelSemantics()))
                    .outcome(outcome)
                    .violatedSpecCount(countViolatedSpecs(result.getSpecResults(), traces))
                    .disabledRuleCount(result.getDisabledRuleCount())
                    .skippedSpecCount(result.getSkippedSpecCount())
                    .specResultsJson(JsonUtils.toJsonOrEmpty(result.getSpecResults()))
                    .checkLogsJson(serializeCheckLogs(persistedCheckLogs))
                    .generationIssuesJson(JsonUtils.toJsonOrEmpty(result.getGenerationIssues()))
                    .nusmvOutput(truncateOutput(result.getNusmvOutput()))
                    .smvModelContent(result.getSmvModelContent())
                    .progress(100)
                    .build();
            VerificationTaskPo savedRun = taskRepository.save(Objects.requireNonNull(run));
            if (savedRun == null || savedRun.getId() == null) {
                throw new InternalServerException("Verification result could not be added to run history");
            }
            if (!traces.isEmpty()) {
                saveTracesWithoutTransaction(traces, userId, savedRun.getId());
            }
            return savedRun.getId();
        });
        if (runId == null) {
            throw new InternalServerException("Verification result could not be added to run history");
        }
        result.setCheckLogs(persistedCheckLogs);
        log.info("Saved synchronous verification run: id={}, userId={}, outcome={}, counterexamples={}",
                runId, userId, result.getOutcome(), traces.size());
        return runId;
    }

    private void requireVerificationRunStorageCapacity(Long userId) {
        transactionTemplate.executeWithoutResult(status -> {
            requireActiveUserForTracePersistence(userId);
            requireChatExecutionLease();
            enforceVerificationRunStorageCapacity(userId);
        });
    }

    private void enforceVerificationRunStorageCapacity(Long userId) {
        long storedTaskCount = taskRepository.countByUserId(userId);
        if (storedTaskCount >= taskAdmissionLimits.getMaxStoredTasksPerUser()) {
            throw new AsyncTaskQuotaExceededException(
                    "verification", AsyncTaskQuotaExceededException.QuotaType.STORED,
                    storedTaskCount, taskAdmissionLimits.getMaxStoredTasksPerUser());
        }
    }

    private boolean completeTaskAndSaveTraces(VerificationTaskPo task,
                                              List<TraceDto> traces,
                                              Long userId,
                                              Long taskId,
                                              VerificationOutcome outcome,
                                              int violatedSpecCount,
                                              List<SpecResultDto> specResults,
                                              List<String> checkLogs,
                                              String nusmvOutput,
                                              List<ModelGenerationIssueDto> generationIssues,
                                              int disabledRuleCount,
                                              int skippedSpecCount,
                                              String smvModelContent) {
        if (!userRepository.existsById(userId)) {
            log.info("User {} no longer exists, skipping verification task completion/persistence", userId);
            return false;
        }
        if (isCompletionCancelled(taskId)) {
            log.info("Verification task {} was cancelled before completion persistence", taskId);
            return false;
        }
        if (traces == null || traces.isEmpty()) {
            if (transactionTemplate == null) {
                return completeTask(task, outcome, violatedSpecCount, specResults, checkLogs, nusmvOutput,
                        generationIssues, disabledRuleCount, skippedSpecCount, smvModelContent);
            }
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                if (!lockActiveUserForTracePersistence(userId)) {
                    log.info("User {} was deleted before verification task completion, skipping verification result", userId);
                    status.setRollbackOnly();
                    return false;
                }
                if (isCompletionCancelled(taskId)) {
                    log.info("Verification task {} was cancelled before completion", taskId);
                    status.setRollbackOnly();
                    return false;
                }
                return completeTask(task, outcome, violatedSpecCount, specResults, checkLogs, nusmvOutput,
                        generationIssues, disabledRuleCount, skippedSpecCount, smvModelContent);
            }));
        }
        if (transactionTemplate == null) {
            throw new IllegalStateException("transactionTemplate is required to complete verification with traces");
        }
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!lockActiveUserForTracePersistence(userId)) {
                log.info("User {} was deleted before trace persistence, skipping verification result", userId);
                status.setRollbackOnly();
                return false;
            }
            if (isCompletionCancelled(taskId)) {
                log.info("Verification task {} was cancelled before trace persistence", taskId);
                status.setRollbackOnly();
                return false;
            }
            taskRepository.findByIdForUpdate(taskId);
            appendTracePersistenceLog(checkLogs, traces);
            saveTracesWithoutTransaction(traces, userId, taskId);
            if (isCompletionCancelled(taskId)) {
                log.info("Verification task {} was cancelled after trace persistence but before completion; rolling back traces", taskId);
                status.setRollbackOnly();
                return false;
            }
            boolean completed = completeTask(task, outcome, violatedSpecCount, specResults, checkLogs, nusmvOutput,
                    generationIssues, disabledRuleCount, skippedSpecCount, smvModelContent);
            if (!completed) {
                status.setRollbackOnly();
            }
            return completed;
        }));
    }

    private boolean isCompletionCancelled(Long taskId) {
        return taskId != null && (isTaskCancelled(taskId) || Thread.currentThread().isInterrupted());
    }

    private void appendTracePersistenceLog(List<String> checkLogs, List<TraceDto> traces) {
        if (checkLogs != null && traces != null && !traces.isEmpty()) {
            checkLogs.add("Auto-saved " + traces.size() + " violation trace(s).");
        }
    }

    private void saveTracesWithoutTransaction(List<TraceDto> traces, Long userId, Long taskId) {
        if (!lockActiveUserForTracePersistence(userId)) {
            log.info("User {} no longer exists, skipping trace persistence", userId);
            return;
        }
        for (TraceDto trace : traces) {
            trace.setUserId(userId);
            if (taskId != null) trace.setVerificationTaskId(taskId);
            TracePo po = traceMapper.toEntity(trace);
            if (po != null) {
                traceRepository.save(po);
                trace.setId(po.getId());
            }
        }
    }

    private boolean lockActiveUserForTracePersistence(Long userId) {
        return userId != null && userRepository.findByIdForUpdate(userId).isPresent();
    }

    private void requireActiveUserForTracePersistence(Long userId) {
        if (userId == null) {
            throw new ValidationException("userId", "User id cannot be null");
        }
        if (!lockActiveUserForTracePersistence(userId)) {
            throw ResourceNotFoundException.user(userId);
        }
    }

    private VerificationResultDto buildErrorResult(String nusmvOutput, List<String> checkLogs) {
        List<String> outcomeLogs = new ArrayList<>(checkLogs != null ? checkLogs : List.of());
        if (outcomeLogs.stream().noneMatch(log -> log != null
                && log.contains(VerificationOutcome.INCONCLUSIVE_LOG_MARKER))) {
            outcomeLogs.add(VerificationOutcome.INCONCLUSIVE_LOG_MARKER
                    + " Verification did not produce a reliable conclusion.");
        }
        return VerificationResultDto.builder()
                .outcome(VerificationOutcome.INCONCLUSIVE)
                .modelComplete(false)
                .traces(List.of()).specResults(List.of())
                .checkLogs(outcomeLogs).nusmvOutput(truncateOutput(nusmvOutput)).build();
    }

    private SmvGenerator.GenerateResult generateResolvedModel(
            Long userId,
            List<DeviceVerificationDto> devices,
            List<BoardEnvironmentVariableDto> environmentVariables,
            List<RuleDto> rules,
            List<SpecificationDto> specs,
            AttackScenarioDto attackScenario,
            boolean enablePrivacy,
            SmvGenerator.GeneratePurpose purpose,
            SmvGenerator.TempModelContext tempModelContext,
            Map<String, DeviceSmvData> resolvedDeviceSmvMap) throws IOException {
        AttackScenarioDto scenario = Objects.requireNonNull(
                attackScenario, "attackScenario is required");
        return smvGenerator.generateWithResolvedDeviceModel(
                userId, devices, environmentVariables, rules, specs, scenario,
                enablePrivacy, purpose, tempModelContext, resolvedDeviceSmvMap);
    }

    private void cleanupTempFile(File file) {
        // The scheduled artifact cleaner bounds retained model/request/output/result directories.
    }

    private String syncVerificationExecutorSnapshot() {
        try {
            ThreadPoolExecutor nativeExecutor = syncVerificationExecutor.getThreadPoolExecutor();
            return "poolSize=" + nativeExecutor.getPoolSize()
                    + ", active=" + nativeExecutor.getActiveCount()
                    + ", queueSize=" + nativeExecutor.getQueue().size()
                    + ", remainingCapacity=" + nativeExecutor.getQueue().remainingCapacity();
        } catch (IllegalStateException ignored) {
            return "executor=uninitialized";
        }
    }

    private void purgeCancelledSyncTasks() {
        try {
            syncVerificationExecutor.getThreadPoolExecutor().purge();
        } catch (IllegalStateException ignored) {
            // executor may not be initialized yet
        }
    }

    // ==================== AbstractAsyncTaskService hooks ====================

    @Override
    protected Optional<VerificationTaskPo> findTaskByIdAndUserId(Long id, Long userId) {
        return taskRepository.findByIdAndUserId(id, userId);
    }

    @Override
    protected int atomicCancelTask(Long taskId, LocalDateTime completedAt) {
        return taskRepository.cancelTaskIfStillActive(
                taskId,
                VerificationTaskPo.TaskStatus.CANCELLED,
                completedAt,
                VerificationOutcome.INCONCLUSIVE,
                List.of(VerificationTaskPo.TaskStatus.PENDING,
                        VerificationTaskPo.TaskStatus.RUNNING));
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
        LocalVerificationExecution execution = localExecutions.get(taskId);
        return execution == null ? LocalExecutionStopResult.NONE : execution.requestStop();
    }

    private enum LocalExecutionState {
        QUEUED,
        RUNNING,
        CANCELLED_BEFORE_START,
        FINISHED
    }

    private final class LocalVerificationExecution implements Runnable {
        private final Long userId;
        private final Long taskId;
        private final VerificationInput input;
        private final AtomicReference<LocalExecutionState> state =
                new AtomicReference<>(LocalExecutionState.QUEUED);
        private final LeaseConfirmation leaseConfirmation = new LeaseConfirmation();
        private final FutureTask<Void> futureTask = new FutureTask<>(this, null);

        private LocalVerificationExecution(Long userId, Long taskId, VerificationInput input) {
            this.userId = userId;
            this.taskId = taskId;
            this.input = input;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(LocalExecutionState.QUEUED, LocalExecutionState.RUNNING)) {
                return;
            }
            try {
                runVerificationTask(userId, taskId, input);
            } finally {
                removeCancelledMark(taskId);
                state.set(LocalExecutionState.FINISHED);
                localExecutions.remove(taskId, this);
            }
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
                    purgeCancelledVerificationTasks();
                    localExecutions.remove(taskId, this);
                    return LocalExecutionStopResult.STOPPED_BEFORE_START;
                }
                if (current == LocalExecutionState.RUNNING) {
                    futureTask.cancel(true);
                    return LocalExecutionStopResult.STOP_REQUESTED;
                }
                return LocalExecutionStopResult.NONE;
            }
        }
    }
}
