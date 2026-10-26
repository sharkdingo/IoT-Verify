package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.util.TemplateNameRule;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateNuSmvValidator;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEditHistoryClearPreviewDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardLayoutDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardReplacementPreviewDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardUndoResultDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableChangeDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariablePatchResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableUpdateRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceLayoutDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeConfigDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeUpdateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceUpdateResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateAffectedDeviceDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetBlockerDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceDeletionResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceJournalSnapshot;
import cn.edu.nju.Iot_Verify.dto.device.DeviceMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDeletionBlockerDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDeletionResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.BoardReplacementStaleException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.BoardContentConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceLabelConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceLayoutConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceRuntimeConflictException;
import cn.edu.nju.Iot_Verify.exception.EnvironmentVariableConflictException;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.exception.TemplateDeletionConflictException;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import cn.edu.nju.Iot_Verify.util.EnvironmentDomainUtils;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.SpecConditionNormalization;
import cn.edu.nju.Iot_Verify.po.*;
import cn.edu.nju.Iot_Verify.repository.*;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.ChatExecutionLeaseGuard;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import cn.edu.nju.Iot_Verify.service.board.BoardEditJournal;
import cn.edu.nju.Iot_Verify.service.board.BoardEditHistoryState;
import cn.edu.nju.Iot_Verify.service.board.BoardUndoAvailability;
import cn.edu.nju.Iot_Verify.service.board.EnvironmentJournalSnapshot;
import cn.edu.nju.Iot_Verify.service.board.RuleCollectionJournalSnapshot;
import cn.edu.nju.Iot_Verify.service.board.RuleOrderSnapshot;
import cn.edu.nju.Iot_Verify.po.BoardEditEntityType;
import cn.edu.nju.Iot_Verify.po.BoardEditJournalPo;
import cn.edu.nju.Iot_Verify.po.BoardEditOperation;
import cn.edu.nju.Iot_Verify.util.RuleSemanticSignature;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import cn.edu.nju.Iot_Verify.util.SpecificationSemanticSignature;
import cn.edu.nju.Iot_Verify.util.SpecificationFormulaPreview;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import cn.edu.nju.Iot_Verify.util.mapper.BoardLayoutMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics.findApi;
import static cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics.hasSignalApi;

@Slf4j
@Service
@RequiredArgsConstructor
public class BoardStorageServiceImpl implements BoardStorageService {

    private final DeviceNodeRepository nodeRepo;
    private final BoardEnvironmentVariableRepository environmentRepo;
    private final SpecificationRepository specRepo;
    private final RuleRepository ruleRepo;
    private final BoardLayoutRepository layoutRepo;
    private final DeviceTemplateRepository deviceTemplateRepo;
    private final DeviceTemplateService deviceTemplateService;
    private final TransactionTemplate transactionTemplate;
    private final DeviceTemplateNuSmvValidator templateNuSmvValidator;
    private final SpecificationMapper specificationMapper;
    private final RuleMapper ruleMapper;
    private final DeviceNodeMapper deviceNodeMapper;
    private final BoardLayoutMapper boardLayoutMapper;
    private final DeviceTemplateMapper deviceTemplateMapper;
    private final DeviceTemplateSchemaValidator deviceTemplateSchemaValidator;
    private final UserRepository userRepository;
    private final BoardEditJournal editJournal;

    /**
     * Rule ordering is a single per-user concern rather than one record, so every reorder entry
     * shares this key.
     */
    private static final String RULE_ORDER_ENTITY_KEY = "rule-order";
    private static final String ENVIRONMENT_ENTITY_KEY = "environment-pool";
    private static final String RULE_SET_ENTITY_KEY = "rule-set";

    /**
     * Reported whenever a journal entry cannot be trusted to describe a reversible edit: an unreadable
     * payload, a non-numeric entity key, or a missing recorded position. All are the same thing to the
     * user — the edit's saved details no longer support reversing it — and none may be confused with the
     * legal "record already gone" case, which skips the drift check.
     */
    private static final String UNREADABLE_JOURNAL_ENTRY_MESSAGE =
            "This edit can no longer be reversed because its saved details are unreadable.";

    private ChatExecutionLeaseGuard chatExecutionLeaseGuard;

    @Autowired
    void setChatExecutionLeaseGuard(ChatExecutionLeaseGuard chatExecutionLeaseGuard) {
        this.chatExecutionLeaseGuard = chatExecutionLeaseGuard;
    }

    /**
     * The in-process user lock prevents same-instance read-modify-write races. Every write
     * transaction then acquires the user's database row with findByIdForUpdate, which serializes
     * the same user's writes across application instances as well.
     *
     * Uses striped locks (fixed-size array) to bound memory while preserving correctness:
     * - 1024 lock stripes (sufficient for most deployments, ~4KB memory overhead)
     * - userId % stripes → deterministic lock assignment
     * - No eviction → same userId always maps to same lock (correctness preserved)
     */
    private static final int LOCK_STRIPES = 1024;
    private final Object[] userWriteLocks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            userWriteLocks[i] = new Object();
        }
    }

    private Object getUserWriteLock(Long userId) {
        int stripe = Math.abs((int) (userId % LOCK_STRIPES));
        return userWriteLocks[stripe];
    }

    private cn.edu.nju.Iot_Verify.po.UserPo requireActiveUserForWrite(Long userId) {
        if (userId == null) throw UnauthorizedException.invalidToken();
        var owner = userRepository.findByIdForUpdate(userId).orElseThrow(UnauthorizedException::invalidToken);
        if (chatExecutionLeaseGuard != null) {
            chatExecutionLeaseGuard.requireCurrentExecutionLease();
        }
        return owner;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceNodeDto> getNodes(Long userId) {
        return nodeRepo.findByUserId(userId).stream()
                .map(deviceNodeMapper::toDto)
                .toList();
    }

    @Override
    public BoardSemanticSnapshotDto getSemanticSnapshot(Long userId) {
        boolean enclosingTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionCallback<SemanticSnapshotRows> capture = status -> {
            if (enclosingTransaction) {
                // The caller may write later; retain exclusive ownership instead of upgrading a shared lock.
                requireActiveUserForWrite(userId);
            } else {
                if (userId == null || userRepository.findByIdForShare(userId).isEmpty()) {
                    throw UnauthorizedException.invalidToken();
                }
                if (chatExecutionLeaseGuard != null) {
                    chatExecutionLeaseGuard.requireCurrentExecutionLease();
                }
            }
            return readSemanticSnapshotRows(userId);
        };
        SemanticSnapshotRows rows;
        if (enclosingTransaction) {
            synchronized (getUserWriteLock(userId)) {
                rows = transactionTemplate.execute(capture);
            }
        } else {
            // Shared user-row ownership admits parallel captures and still excludes every Board writer.
            rows = transactionTemplate.execute(capture);
        }
        // All scalar/JSON columns were captured under the user lock. Standalone reads release
        // their lock and connection before decoding; an enclosing transaction keeps its ownership.
        return mapSemanticSnapshot(rows);
    }

    /** Package-private full replacement used by semantic precheck tests; not an external service contract. */
    List<DeviceNodeDto> saveNodes(Long userId, List<DeviceNodeDto> nodes) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                requireTotalCapacity("devices", safeList(nodes).size(), RequestLimits.MAX_DEVICES);
                List<DeviceNodeDto> canonicalNodes = canonicalizeNodes(userId, nodes);
                validateBoardReferences(userId, canonicalNodes, getRulesInternal(userId), getSpecsInternal(userId));
                return saveNodesInternal(userId, canonicalNodes);
            });
        }
    }

    @Override
    public DeviceMutationResultDto addNodes(Long userId,
                                            List<DeviceNodeDto> nodes,
                                            List<BoardEnvironmentVariableDto> environmentVariablePatches) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                if (nodes == null || nodes.isEmpty()) {
                    throw new BadRequestException("At least one device is required");
                }

                List<DeviceNodeDto> current = new ArrayList<>(getNodesInternal(userId));
                requireAdditionalCapacity(
                        "devices", current.size(), nodes.size(), RequestLimits.MAX_DEVICES);
                List<BoardEnvironmentVariableDto> previousEnvironment = getEnvironmentVariablesInternal(userId);
                List<DeviceNodeDto> next = new ArrayList<>(current);
                next.addAll(nodes);
                List<DeviceNodeDto> canonicalNodes = canonicalizeNodes(userId, next);
                List<RuleDto> currentRules = getRulesInternal(userId);
                List<SpecificationDto> currentSpecs = getSpecsInternal(userId);
                validateBoardReferences(userId, canonicalNodes, currentRules, currentSpecs);

                List<DeviceNodeDto> savedNodes = saveNodesInternal(userId, canonicalNodes);
                List<BoardEnvironmentVariableDto> savedEnvironment = environmentVariablePatches != null
                        && !environmentVariablePatches.isEmpty()
                        ? patchEnvironmentVariablesInternal(userId, savedNodes, environmentVariablePatches)
                        : getEnvironmentVariablesInternal(userId);
                requireTotalCapacity("environment variables", savedEnvironment.size(),
                        RequestLimits.MAX_ENVIRONMENT_VARIABLES);
                List<String> createdIds = nodes.stream()
                        .map(DeviceNodeDto::getId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
                List<DeviceNodeDto> created = savedNodes.stream()
                        .filter(node -> createdIds.contains(node.getId()))
                        .toList();
                if (created.size() != createdIds.size()) {
                    throw new InternalServerException(
                            "Device creation did not return every persisted device in the batch");
                }

                DeviceJournalSnapshot before = deviceJournalSnapshot(
                        createdIds, List.of(), List.of(), List.of(), previousEnvironment);
                DeviceJournalSnapshot after = deviceJournalSnapshot(
                        createdIds,
                        positionedDevices(savedNodes, new LinkedHashSet<>(createdIds)),
                        List.of(), List.of(), savedEnvironment);
                // One request is one user action, including its Environment Pool patches.
                editJournal.record(userId, BoardEditEntityType.DEVICE, BoardEditOperation.CREATE,
                        createdIds.get(0), before, after);

                BoardUndoAvailability availability = editJournal.availability(userId);
                return DeviceMutationResultDto.builder()
                        .operation("created")
                        .affectedDevices(created)
                        .currentNodes(savedNodes)
                        .environmentVariables(savedEnvironment)
                        .environmentChanges(diffEnvironmentVariables(previousEnvironment, savedEnvironment))
                        .currentSpecifications(currentSpecs)
                        .currentCount(savedNodes.size())
                        .canUndo(availability.canUndo())
                        .canRedo(availability.canRedo())
                        .build();
            });
        }
    }

    @Override
    public DeviceUpdateResultDto updateNodeLayout(Long userId, String nodeId, DeviceLayoutDto layout) {
        return updateNodeLayout(userId, nodeId, null, layout);
    }

    @Override
    public DeviceUpdateResultDto updateNodeLayoutIfUnchanged(
            Long userId, String nodeId, DeviceLayoutDto expected, DeviceLayoutDto desired) {
        if (expected == null) {
            throw new BadRequestException("Expected device layout is required");
        }
        return updateNodeLayout(userId, nodeId, expected, desired);
    }

    private DeviceUpdateResultDto updateNodeLayout(
            Long userId, String nodeId, DeviceLayoutDto expected, DeviceLayoutDto desired) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                String targetId = nodeIdForLookup(nodeId);
                if (targetId == null || desired == null) {
                    throw new BadRequestException("Device id and layout are required");
                }
                if (expected != null) {
                    validateDeviceLayout(expected);
                }
                validateDeviceLayout(desired);
                List<DeviceNodeDto> current = new ArrayList<>(getNodesInternal(userId));
                int index = indexOfNode(current, targetId);
                if (index < 0) {
                    throw new ResourceNotFoundException("Device", targetId);
                }
                DeviceNodeDto previous = copyDeviceNode(current.get(index));
                if (expected != null && !sameLayout(previous, expected)) {
                    throw new DeviceLayoutConflictException(previous);
                }
                DeviceNodeDto next = copyDeviceNode(previous);
                next.setPosition(copyPosition(desired.getPosition()));
                next.setWidth(desired.getWidth());
                next.setHeight(desired.getHeight());
                return persistTargetedNodeUpdate(userId, current, index, previous, next, "layout");
            });
        }
    }

    @Override
    public DeviceUpdateResultDto updateNodeRuntime(
            Long userId, String nodeId, DeviceRuntimeUpdateDto runtime) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                String targetId = nodeIdForLookup(nodeId);
                if (targetId == null || runtime == null
                        || runtime.getExpected() == null || runtime.getDesired() == null) {
                    throw new BadRequestException(
                            "Device id, expected runtime, and desired runtime are required");
                }
                List<DeviceNodeDto> current = new ArrayList<>(getNodesInternal(userId));
                int index = indexOfNode(current, targetId);
                if (index < 0) {
                    throw new ResourceNotFoundException("Device", targetId);
                }
                DeviceNodeDto persistedPrevious = copyDeviceNode(current.get(index));
                DeviceNodeDto previous = copyDeviceNode(persistedPrevious);
                Map<String, DeviceManifest> templates = loadTemplateManifestMap(userId);
                DeviceManifest manifest = manifestForTemplateName(
                        previous.getTemplateName(), templates);
                canonicalizeRuntime(previous, manifest);

                DeviceNodeDto expected = copyDeviceNode(previous);
                applyRuntime(expected, runtime.getExpected(), manifest);
                if (!sameRuntime(previous, expected)) {
                    throw new DeviceRuntimeConflictException(previous);
                }

                DeviceNodeDto next = copyDeviceNode(previous);
                applyRuntime(next, runtime.getDesired(), manifest);
                List<DeviceNodeDto> validationNodes = current.stream()
                        .map(this::copyDeviceNode)
                        .collect(Collectors.toCollection(ArrayList::new));
                validationNodes.set(index, next);
                List<DeviceNodeDto> canonicalNodes = canonicalizeNodes(validationNodes, templates);
                validateBoardReferences(
                        userId, canonicalNodes, getRulesInternal(userId), getSpecsInternal(userId), templates);
                DeviceNodeDto canonicalNext = requireNode(canonicalNodes, targetId);
                return persistTargetedNodeUpdate(
                        userId, current, index, persistedPrevious, canonicalNext, "runtime");
            });
        }
    }

    @Override
    public DeviceMutationResultDto renameNode(
            Long userId, String nodeId, String newLabel, String expectedLabel) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                String targetId = nodeIdForLookup(nodeId);
                String label = trimToNull(newLabel);
                if (targetId == null || label == null || trimToNull(expectedLabel) == null) {
                    throw new BadRequestException(
                            "Device id, name, and expected current name are required");
                }

                List<DeviceNodeDto> currentNodes = new ArrayList<>(getNodesInternal(userId));
                List<BoardEnvironmentVariableDto> previousEnvironment = getEnvironmentVariablesInternal(userId);
                DeviceNodeDto target = requireNode(currentNodes, targetId);
                int targetPosition = indexOfNode(currentNodes, targetId);
                DeviceNodeDto previousDevice = copyDeviceNode(target);
                String previousLabel = target.getLabel();
                if (!Objects.equals(expectedLabel, previousLabel)) {
                    throw new ConflictException(
                            "The device name changed after the rename dialog was opened. "
                                    + "Review the current device name before renaming it.");
                }
                if (Objects.equals(label, previousLabel)) {
                    throw new BadRequestException("The new device name must differ from the current name");
                }
                List<DeviceNodeDto> otherNodes = currentNodes.stream()
                        .filter(Objects::nonNull)
                        .filter(node -> !targetId.equals(trimToNull(node.getId())))
                        .toList();
                String suggestedLabel = NodeServiceImpl.chooseAvailableLabel(label, otherNodes);
                if (!label.equals(suggestedLabel)) {
                    throw new DeviceLabelConflictException(label, suggestedLabel);
                }
                target.setLabel(label);

                List<RuleDto> currentRules = getRulesInternal(userId);
                List<SpecificationDto> previousSpecs = getSpecsInternal(userId);
                int updatedSpecCount = countSpecificationDeviceLabelChanges(previousSpecs, targetId, label);
                List<DeviceNodeDto> canonicalNodes = canonicalizeNodes(userId, currentNodes);
                validateBoardReferences(userId, canonicalNodes, currentRules, previousSpecs);

                DeviceNodePo entity = nodeRepo.findById(new DeviceNodeId(targetId, userId))
                        .orElseThrow(() -> new ResourceNotFoundException("Device", targetId));
                entity.setLabel(label);
                DeviceNodeDto renamed = Objects.requireNonNull(
                        deviceNodeMapper.toDto(nodeRepo.save(entity)),
                        "renamed device must not be null");
                List<DeviceNodeDto> savedNodes = new ArrayList<>(currentNodes);
                savedNodes.set(targetPosition, renamed);
                savedNodes = List.copyOf(savedNodes);
                List<SpecificationDto> savedSpecs = updatedSpecCount > 0
                        ? saveSpecsInternal(userId, previousSpecs, savedNodes)
                        : previousSpecs;
                List<BoardEnvironmentVariableDto> savedEnvironment = getEnvironmentVariablesInternal(userId);
                Set<String> updatedSpecIds = savedSpecs.stream()
                        .filter(spec -> previousSpecs.stream()
                                .filter(previous -> Objects.equals(previous.getId(), spec.getId()))
                                .findFirst()
                                .map(previous -> !Objects.equals(previous, spec))
                                .orElse(false))
                        .map(SpecificationDto::getId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                DeviceJournalSnapshot before = deviceJournalSnapshot(
                        List.of(targetId),
                        List.of(new DeviceJournalSnapshot.DeviceWithPosition(previousDevice, targetPosition)),
                        List.of(), positionedSpecs(previousSpecs, updatedSpecIds), previousEnvironment);
                DeviceJournalSnapshot after = deviceJournalSnapshot(
                        List.of(targetId),
                        List.of(new DeviceJournalSnapshot.DeviceWithPosition(renamed, targetPosition)),
                        List.of(), positionedSpecs(savedSpecs, updatedSpecIds), savedEnvironment);
                editJournal.record(userId, BoardEditEntityType.DEVICE, BoardEditOperation.UPDATE,
                        targetId, before, after);
                BoardUndoAvailability availability = editJournal.availability(userId);
                return DeviceMutationResultDto.builder()
                        .operation("renamed")
                        .affectedDevices(List.of(renamed))
                        .currentNodes(savedNodes)
                        .environmentVariables(savedEnvironment)
                        .environmentChanges(diffEnvironmentVariables(previousEnvironment, savedEnvironment))
                        .currentSpecifications(savedSpecs)
                        .previousLabel(previousLabel)
                        .updatedSpecificationCount(updatedSpecCount)
                        .currentCount(savedNodes.size())
                        .canUndo(availability.canUndo())
                        .canRedo(availability.canRedo())
                        .build();
            });
        }
    }

    @Override
    public DeviceDeletionResultDto previewNodeDeletion(Long userId, String nodeId) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                String targetId = nodeIdForLookup(nodeId);
                if (targetId == null) {
                    throw new BadRequestException("Device id is required");
                }

                List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
                DeviceNodeDto target = requireNode(currentNodes, targetId);
                List<RuleDto> currentRules = getRulesInternal(userId);
                List<SpecificationDto> currentSpecs = getSpecsInternal(userId);
                List<BoardEnvironmentVariableDto> currentEnvironment = getEnvironmentVariablesInternal(userId);
                List<DeviceNodeDto> projectedNodes = currentNodes.stream()
                        .filter(node -> !targetId.equals(node.getId()))
                        .toList();
                List<BoardEnvironmentVariableDto> projectedEnvironment =
                        projectEnvironmentVariablesForNodes(userId, projectedNodes);
                List<RuleDto> removedRules = currentRules.stream()
                        .filter(rule -> ruleReferencesNode(rule, targetId))
                        .toList();
                List<SpecificationDto> removedSpecifications = currentSpecs.stream()
                        .filter(spec -> specificationReferencesNode(spec, targetId))
                        .toList();
                List<EnvironmentVariableChangeDto> environmentChanges =
                        diffEnvironmentVariables(currentEnvironment, projectedEnvironment);

                return DeviceDeletionResultDto.builder()
                        .operation("preview")
                        .impactToken(deletionImpactToken(
                                target, removedRules, removedSpecifications, environmentChanges))
                        .deletedDevice(target)
                        .removedRules(removedRules)
                        .removedSpecifications(removedSpecifications)
                        .currentNodes(currentNodes)
                        .environmentVariables(currentEnvironment)
                        .environmentChanges(environmentChanges)
                        .currentRules(currentRules)
                        .currentSpecifications(currentSpecs)
                        .build();
            });
        }
    }

    @Override
    public DeviceDeletionResultDto deleteNodeCascade(Long userId,
                                                     String nodeId,
                                                     String expectedImpactToken) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                String targetId = nodeIdForLookup(nodeId);
                if (targetId == null) {
                    throw new BadRequestException("Device id is required");
                }

                List<DeviceNodeDto> currentNodes = new ArrayList<>(getNodesInternal(userId));
                List<BoardEnvironmentVariableDto> previousEnvironment = getEnvironmentVariablesInternal(userId);
                DeviceNodeDto deleted = requireNode(currentNodes, targetId);
                int deviceIndex = indexOfNode(currentNodes, targetId);
                currentNodes.removeIf(node -> targetId.equals(node.getId()));

                List<RuleDto> currentRules = getRulesInternal(userId);
                List<RuleDto> removedRules = currentRules.stream()
                        .filter(rule -> ruleReferencesNode(rule, targetId))
                        .toList();
                List<RuleDto> nextRules = currentRules.stream()
                        .filter(rule -> !ruleReferencesNode(rule, targetId))
                        .toList();

                List<SpecificationDto> currentSpecs = getSpecsInternal(userId);
                List<SpecificationDto> removedSpecs = currentSpecs.stream()
                        .filter(spec -> specificationReferencesNode(spec, targetId))
                        .toList();
                List<SpecificationDto> nextSpecs = currentSpecs.stream()
                        .filter(spec -> !specificationReferencesNode(spec, targetId))
                        .toList();

                List<DeviceNodeDto> canonicalNodes = canonicalizeNodes(userId, currentNodes);
                validateBoardReferences(userId, canonicalNodes, nextRules, nextSpecs);
                List<BoardEnvironmentVariableDto> projectedEnvironment =
                        projectEnvironmentVariablesForNodes(userId, canonicalNodes);
                List<EnvironmentVariableChangeDto> projectedEnvironmentChanges =
                        diffEnvironmentVariables(previousEnvironment, projectedEnvironment);
                String actualImpactToken = deletionImpactToken(
                        deleted, removedRules, removedSpecs, projectedEnvironmentChanges);
                if (!hasText(expectedImpactToken)
                        || !MessageDigest.isEqual(
                                actualImpactToken.getBytes(StandardCharsets.UTF_8),
                                expectedImpactToken.trim().getBytes(StandardCharsets.UTF_8))) {
                    throw new ConflictException(
                            "Device deletion impact changed after confirmation; review a fresh preview before deleting.");
                }
                List<DeviceNodeDto> savedNodes = saveNodesInternal(userId, canonicalNodes);
                List<RuleDto> savedRules = saveRulesInternal(userId, nextRules);
                List<SpecificationDto> savedSpecs = saveSpecsInternal(userId, nextSpecs, savedNodes);
                List<BoardEnvironmentVariableDto> savedEnvironment = getEnvironmentVariablesInternal(userId);

                Set<Long> removedRuleIds = removedRules.stream()
                        .map(RuleDto::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                Set<String> removedSpecIds = removedSpecs.stream()
                        .map(SpecificationDto::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                DeviceNodeDto restorableDeleted = copyDeviceNode(deleted);
                canonicalizeNodes(userId, List.of(restorableDeleted));
                // The mapper persists absent local collections as JSON arrays. Snapshot that same
                // observable shape so an immediate redo does not mistake null -> [] for newer work.
                restorableDeleted = deviceNodeMapper.toDto(
                        deviceNodeMapper.toEntity(restorableDeleted, userId));
                DeviceJournalSnapshot before = deviceJournalSnapshot(
                        List.of(targetId),
                        List.of(new DeviceJournalSnapshot.DeviceWithPosition(restorableDeleted, deviceIndex)),
                        positionedRules(currentRules, removedRuleIds),
                        positionedSpecs(currentSpecs, removedSpecIds),
                        previousEnvironment);
                DeviceJournalSnapshot after = deviceJournalSnapshot(
                        List.of(targetId), List.of(), List.of(), List.of(), savedEnvironment);
                editJournal.record(userId, BoardEditEntityType.DEVICE, BoardEditOperation.DELETE,
                        targetId, before, after);

                BoardUndoAvailability availability = editJournal.availability(userId);
                return DeviceDeletionResultDto.builder()
                        .operation("deleted")
                        .impactToken(actualImpactToken)
                        .deletedDevice(deleted)
                        .removedRules(removedRules)
                        .removedSpecifications(removedSpecs)
                        .currentNodes(savedNodes)
                        .environmentVariables(savedEnvironment)
                        .environmentChanges(diffEnvironmentVariables(previousEnvironment, savedEnvironment))
                        .currentRules(savedRules)
                        .currentSpecifications(savedSpecs)
                        .canUndo(availability.canUndo())
                        .canRedo(availability.canRedo())
                        .build();
            });
        }
    }

    private String nodeIdForLookup(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return null;
        if (!nodeId.matches(DeviceNodeDto.ID_PATTERN)) {
            throw new ValidationException("nodeId", "Node ID must not have leading or trailing whitespace");
        }
        return nodeId;
    }

    private int indexOfNode(List<DeviceNodeDto> nodes, String nodeId) {
        for (int index = 0; index < nodes.size(); index++) {
            DeviceNodeDto node = nodes.get(index);
            if (node != null && nodeId.equals(node.getId())) {
                return index;
            }
        }
        return -1;
    }

    private DeviceJournalSnapshot deviceJournalSnapshot(
            List<String> deviceIds,
            List<DeviceJournalSnapshot.DeviceWithPosition> devices,
            List<DeviceJournalSnapshot.RuleWithPosition> rules,
            List<DeviceJournalSnapshot.SpecWithPosition> specs,
            List<BoardEnvironmentVariableDto> environmentVariables) {
        return DeviceJournalSnapshot.builder()
                .deviceIds(List.copyOf(deviceIds))
                .devices(List.copyOf(devices))
                .rules(List.copyOf(rules))
                .specs(List.copyOf(specs))
                .environmentVariables(List.copyOf(environmentVariables))
                .build();
    }

    private List<DeviceJournalSnapshot.DeviceWithPosition> positionedDevices(
            List<DeviceNodeDto> devices, Set<String> includedIds) {
        List<DeviceJournalSnapshot.DeviceWithPosition> positioned = new ArrayList<>();
        for (int index = 0; index < devices.size(); index++) {
            DeviceNodeDto device = devices.get(index);
            if (device != null && includedIds.contains(device.getId())) {
                positioned.add(new DeviceJournalSnapshot.DeviceWithPosition(device, index));
            }
        }
        return List.copyOf(positioned);
    }

    private List<DeviceJournalSnapshot.RuleWithPosition> positionedRules(
            List<RuleDto> rules, Set<Long> includedIds) {
        List<DeviceJournalSnapshot.RuleWithPosition> positioned = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            RuleDto rule = rules.get(index);
            if (rule != null && includedIds.contains(rule.getId())) {
                positioned.add(new DeviceJournalSnapshot.RuleWithPosition(rule, index));
            }
        }
        return List.copyOf(positioned);
    }

    private List<DeviceJournalSnapshot.SpecWithPosition> positionedSpecs(
            List<SpecificationDto> specs, Set<String> includedIds) {
        List<DeviceJournalSnapshot.SpecWithPosition> positioned = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            SpecificationDto spec = specs.get(index);
            if (spec != null && includedIds.contains(spec.getId())) {
                positioned.add(new DeviceJournalSnapshot.SpecWithPosition(spec, index));
            }
        }
        return List.copyOf(positioned);
    }

    private void validateDeviceLayout(DeviceLayoutDto layout) {
        Map<String, String> errors = new LinkedHashMap<>();
        DeviceNodeDto.Position position = layout.getPosition();
        Double x = position != null ? position.getX() : null;
        Double y = position != null ? position.getY() : null;
        if (x == null || !Double.isFinite(x) || Math.abs(x) > DeviceLayoutDto.MAX_ABS_POSITION) {
            errors.put("position.x", "X coordinate must be finite and within -1000000..1000000");
        }
        if (y == null || !Double.isFinite(y) || Math.abs(y) > DeviceLayoutDto.MAX_ABS_POSITION) {
            errors.put("position.y", "Y coordinate must be finite and within -1000000..1000000");
        }
        if (layout.getWidth() == null || layout.getWidth() < DeviceLayoutDto.MIN_WIDTH
                || layout.getWidth() > DeviceLayoutDto.MAX_WIDTH) {
            errors.put("width", "Width must be within 80..2000");
        }
        if (layout.getHeight() == null || layout.getHeight() < DeviceLayoutDto.MIN_HEIGHT
                || layout.getHeight() > DeviceLayoutDto.MAX_HEIGHT) {
            errors.put("height", "Height must be within 60..2000");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private boolean sameLayout(DeviceNodeDto node, DeviceLayoutDto layout) {
        DeviceNodeDto.Position position = node.getPosition();
        DeviceNodeDto.Position expectedPosition = layout.getPosition();
        return position != null
                && Objects.equals(position.getX(), expectedPosition.getX())
                && Objects.equals(position.getY(), expectedPosition.getY())
                && Objects.equals(node.getWidth(), layout.getWidth())
                && Objects.equals(node.getHeight(), layout.getHeight());
    }

    private DeviceUpdateResultDto persistTargetedNodeUpdate(
            Long userId,
            List<DeviceNodeDto> current,
            int index,
            DeviceNodeDto previous,
            DeviceNodeDto requested,
            String mutationType) {
        List<String> changedFields = changedDeviceUpdateFields(previous, requested, mutationType);
        DeviceNodeDto saved = previous;
        if (!changedFields.isEmpty()) {
            DeviceNodePo entity = nodeRepo.findById(new DeviceNodeId(previous.getId(), userId))
                    .orElseThrow(() -> new ResourceNotFoundException("Device", previous.getId()));
            if ("layout".equals(mutationType)) {
                entity.setPosX(requested.getPosition().getX());
                entity.setPosY(requested.getPosition().getY());
                entity.setWidth(requested.getWidth());
                entity.setHeight(requested.getHeight());
            } else {
                DeviceNodePo runtimeValues = Objects.requireNonNull(
                        deviceNodeMapper.toEntity(requested, userId),
                        "device runtime patch entity must not be null");
                entity.setState(runtimeValues.getState());
                entity.setCurrentStateTrust(runtimeValues.getCurrentStateTrust());
                entity.setCurrentStatePrivacy(runtimeValues.getCurrentStatePrivacy());
                entity.setVariablesJson(runtimeValues.getVariablesJson());
                entity.setPrivaciesJson(runtimeValues.getPrivaciesJson());
            }
            saved = Objects.requireNonNull(
                    deviceNodeMapper.toDto(nodeRepo.save(entity)), "saved device patch must not be null");
            changedFields = changedDeviceUpdateFields(previous, saved, mutationType);
        }
        current.set(index, saved);
        BoardUndoAvailability availability = null;
        if (!changedFields.isEmpty()) {
            List<BoardEnvironmentVariableDto> environment = getEnvironmentVariablesInternal(userId);
            DeviceJournalSnapshot before = deviceJournalSnapshot(
                    List.of(previous.getId()),
                    List.of(new DeviceJournalSnapshot.DeviceWithPosition(previous, index)),
                    List.of(), List.of(), environment);
            DeviceJournalSnapshot after = deviceJournalSnapshot(
                    List.of(saved.getId()),
                    List.of(new DeviceJournalSnapshot.DeviceWithPosition(saved, index)),
                    List.of(), List.of(), environment);
            editJournal.record(userId, BoardEditEntityType.DEVICE, BoardEditOperation.UPDATE,
                    saved.getId(), before, after);
            availability = editJournal.availability(userId);
        }
        return DeviceUpdateResultDto.builder()
                .operation(changedFields.isEmpty() ? "unchanged" : "updated")
                .mutationType(mutationType)
                .changedFields(changedFields)
                .previousDevice(previous)
                .currentDevice(saved)
                .currentNodes(List.copyOf(current))
                .currentCount(current.size())
                .canUndo(availability != null ? availability.canUndo() : null)
                .canRedo(availability != null ? availability.canRedo() : null)
                .build();
    }

    private List<String> changedDeviceUpdateFields(
            DeviceNodeDto previous, DeviceNodeDto current, String mutationType) {
        List<String> fields = new ArrayList<>();
        if ("layout".equals(mutationType)) {
            DeviceNodeDto.Position before = previous != null ? previous.getPosition() : null;
            DeviceNodeDto.Position after = current != null ? current.getPosition() : null;
            if (!Objects.equals(before != null ? before.getX() : null, after != null ? after.getX() : null)) {
                fields.add("position.x");
            }
            if (!Objects.equals(before != null ? before.getY() : null, after != null ? after.getY() : null)) {
                fields.add("position.y");
            }
            if (!Objects.equals(previous != null ? previous.getWidth() : null,
                    current != null ? current.getWidth() : null)) {
                fields.add("width");
            }
            if (!Objects.equals(previous != null ? previous.getHeight() : null,
                    current != null ? current.getHeight() : null)) {
                fields.add("height");
            }
        } else {
            if (!Objects.equals(previous != null ? previous.getState() : null,
                    current != null ? current.getState() : null)) {
                fields.add("state");
            }
            if (!Objects.equals(previous != null ? previous.getCurrentStateTrust() : null,
                    current != null ? current.getCurrentStateTrust() : null)) {
                fields.add("currentStateTrust");
            }
            if (!Objects.equals(previous != null ? previous.getCurrentStatePrivacy() : null,
                    current != null ? current.getCurrentStatePrivacy() : null)) {
                fields.add("currentStatePrivacy");
            }
            if (!Objects.equals(
                    previous != null ? safeList(previous.getVariables()) : List.of(),
                    current != null ? safeList(current.getVariables()) : List.of())) {
                fields.add("variables");
            }
            if (!Objects.equals(
                    previous != null ? safeList(previous.getPrivacies()) : List.of(),
                    current != null ? safeList(current.getPrivacies()) : List.of())) {
                fields.add("privacies");
            }
        }
        return List.copyOf(fields);
    }

    private DeviceNodeDto copyDeviceNode(DeviceNodeDto source) {
        DeviceNodeDto copy = new DeviceNodeDto();
        copy.setId(source.getId());
        copy.setTemplateName(source.getTemplateName());
        copy.setLabel(source.getLabel());
        copy.setPosition(copyPosition(source.getPosition()));
        copy.setState(source.getState());
        copy.setWidth(source.getWidth());
        copy.setHeight(source.getHeight());
        copy.setCurrentStateTrust(source.getCurrentStateTrust());
        copy.setCurrentStatePrivacy(source.getCurrentStatePrivacy());
        copy.setVariables(copyVariables(source.getVariables()));
        copy.setPrivacies(copyPrivacies(source.getPrivacies()));
        return copy;
    }

    private DeviceNodeDto.Position copyPosition(DeviceNodeDto.Position source) {
        if (source == null) return null;
        DeviceNodeDto.Position copy = new DeviceNodeDto.Position();
        copy.setX(source.getX());
        copy.setY(source.getY());
        return copy;
    }

    private List<VariableStateDto> copyVariables(List<VariableStateDto> values) {
        if (values == null) return null;
        return values.stream()
                .map(value -> value == null ? null : new VariableStateDto(
                        value.getName(), value.getValue(), value.getTrust()))
                .toList();
    }

    private void applyRuntime(DeviceNodeDto target,
                              DeviceRuntimeConfigDto runtime,
                              DeviceManifest manifest) {
        target.setState(trimToNull(runtime.getState()));
        target.setCurrentStateTrust(normalizeSecurityLabel(runtime.getCurrentStateTrust()));
        target.setCurrentStatePrivacy(normalizeSecurityLabel(runtime.getCurrentStatePrivacy()));
        target.setVariables(canonicalizeVariables(runtime.getVariables(), manifest, target.getState()));
        target.setPrivacies(canonicalizePrivacies(runtime.getPrivacies(), manifest));
    }

    private void canonicalizeRuntime(DeviceNodeDto node, DeviceManifest manifest) {
        node.setState(trimToNull(node.getState()));
        node.setCurrentStateTrust(normalizeSecurityLabel(node.getCurrentStateTrust()));
        node.setCurrentStatePrivacy(normalizeSecurityLabel(node.getCurrentStatePrivacy()));
        node.setVariables(canonicalizeVariables(node.getVariables(), manifest, node.getState()));
        node.setPrivacies(canonicalizePrivacies(node.getPrivacies(), manifest));
    }

    private boolean sameRuntime(DeviceNodeDto first, DeviceNodeDto second) {
        return changedDeviceUpdateFields(first, second, "runtime").isEmpty();
    }

    private List<VariableStateDto> canonicalizeVariables(
            List<VariableStateDto> values, DeviceManifest manifest, String state) {
        if (values == null) return null;
        return values.stream()
                .map(value -> {
                    if (value == null) return null;
                    String rawName = trimToNull(value.getName());
                    DeviceManifest.InternalVariable definition = internalVariable(manifest, rawName);
                    String name = definition != null ? definition.getName() : rawName;
                    // A value the device's state determines is corrected here rather than refused. It is a
                    // derived fact — the generator gives `next(<device>.<var>)` a branch per state, so a
                    // stored value survives only step 0 — and refusing it in the shared revalidation would
                    // let one legacy row block every unrelated write to the board, the failure shape
                    // `requireResolvedVariableSource` is deliberately scoped to avoid. Canonicalising keeps
                    // the write working and leaves the model self-consistent.
                    String declared = DeviceManifestModes.stateDeclaredValue(manifest, state, name);
                    return new VariableStateDto(
                            name,
                            declared != null ? declared : trimToNull(value.getValue()),
                            normalizeSecurityLabel(value.getTrust()));
                })
                .toList();
    }

    private List<PrivacyStateDto> canonicalizePrivacies(
            List<PrivacyStateDto> values, DeviceManifest manifest) {
        if (values == null) return null;
        return values.stream()
                .map(value -> {
                    if (value == null) return null;
                    String rawName = trimToNull(value.getName());
                    DeviceManifest.InternalVariable definition = internalVariable(manifest, rawName);
                    String name = definition != null ? definition.getName() : rawName;
                    return new PrivacyStateDto(name, normalizeSecurityLabel(value.getPrivacy()));
                })
                .toList();
    }

    private String normalizeSecurityLabel(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private List<PrivacyStateDto> copyPrivacies(List<PrivacyStateDto> values) {
        if (values == null) return null;
        return values.stream()
                .map(value -> value == null ? null : new PrivacyStateDto(
                        value.getName(), value.getPrivacy()))
                .toList();
    }

    private DeviceNodeDto requireNode(List<DeviceNodeDto> nodes, String nodeId) {
        int index = indexOfNode(nodes, nodeId);
        if (index < 0) {
            throw new ResourceNotFoundException("Device", nodeId);
        }
        return nodes.get(index);
    }

    private int countSpecificationDeviceLabelChanges(List<SpecificationDto> specs,
                                                     String nodeId,
                                                     String newLabel) {
        int updated = 0;
        for (SpecificationDto spec : specs) {
            boolean changed = hasConditionLabelChange(spec.getAConditions(), nodeId, newLabel)
                    || hasConditionLabelChange(spec.getIfConditions(), nodeId, newLabel)
                    || hasConditionLabelChange(spec.getThenConditions(), nodeId, newLabel);
            for (SpecificationDto.DeviceRefDto ref : safeList(spec.getDevices())) {
                if (ref != null && nodeId.equals(ref.getDeviceId())
                        && !Objects.equals(newLabel, ref.getDeviceLabel())) {
                    changed = true;
                }
            }
            if (changed) updated++;
        }
        return updated;
    }

    private boolean hasConditionLabelChange(List<SpecConditionDto> conditions,
                                             String nodeId,
                                             String newLabel) {
        return safeList(conditions).stream().anyMatch(condition -> condition != null
                && nodeId.equals(condition.getDeviceId())
                && !Objects.equals(newLabel, condition.getDeviceLabel()));
    }

    private boolean ruleReferencesNode(RuleDto rule, String nodeId) {
        if (rule == null) return false;
        RuleDto.Command command = rule.getCommand();
        if (command != null && (nodeId.equals(command.getDeviceName())
                || nodeId.equals(command.getContentDevice()))) {
            return true;
        }
        return safeList(rule.getConditions()).stream()
                .anyMatch(condition -> condition != null && nodeId.equals(condition.getDeviceName()));
    }

    /**
     * Whether deleting {@code nodeId} takes this specification with it.
     *
     * <p>Matches on {@code deviceId} for every condition, including a {@code variableSource=environment}
     * one whose compiled formula is {@code a_<key>} and names no device. That looks over-eager and is not:
     * the device supplies the <em>declaration</em> that gives the key its domain, which is what validates
     * the condition's value and what makes {@code environment} legal for that key at all
     * ({@code SmvSpecificationBuilder} resolves the shared declaration through the selected device, and
     * refuses the condition when that device declares the key device-local). A specification whose anchor
     * is gone can no longer be checked, so removing it is the honest outcome rather than leaving a
     * condition that would be refused at the next run.
     *
     * <p>Deletion is previewed with the affected specifications listed, confirmed against an impact token,
     * and journaled for undo, so the removal is neither silent nor final. What the product does <em>not</em>
     * do is offer to re-anchor such a condition onto another device declaring the same shared key — that is
     * well-defined but is a product decision about editing a specification on the user's behalf.
     */
    private boolean specificationReferencesNode(SpecificationDto spec, String nodeId) {
        if (spec == null) return false;
        if (safeList(spec.getDevices()).stream()
                .anyMatch(ref -> ref != null && nodeId.equals(ref.getDeviceId()))) {
            return true;
        }
        return Stream.of(spec.getAConditions(), spec.getIfConditions(), spec.getThenConditions())
                .flatMap(conditions -> safeList(conditions).stream())
                .anyMatch(condition -> condition != null && nodeId.equals(condition.getDeviceId()));
    }

    private <T> List<T> safeList(List<T> values) {
        return values != null ? values : List.of();
    }

    @Override
    public List<BoardEnvironmentVariableDto> getEnvironmentVariables(Long userId) {
        if (environmentRepo == null) {
            return List.of();
        }
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
                return refreshEnvironmentVariablesInternal(userId, currentNodes);
            });
        }
    }

    @Override
    public EnvironmentMutationResultDto saveEnvironmentVariables(
            Long userId,
            List<EnvironmentVariableUpdateRequestDto> updates) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
                return compareAndSetEnvironmentVariablesInternal(userId, currentNodes, updates);
            });
        }
    }

    @Override
    public List<BoardEnvironmentVariableDto> updateEnvironmentVariables(
            Long userId,
            java.util.function.UnaryOperator<List<BoardEnvironmentVariableDto>> mutator) {
        Objects.requireNonNull(mutator, "environment mutator must not be null");
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
                List<BoardEnvironmentVariableDto> current = new ArrayList<>(
                        refreshEnvironmentVariablesInternal(userId, currentNodes));
                List<BoardEnvironmentVariableDto> mutated = mutator.apply(current);
                List<BoardEnvironmentVariableDto> next = mutated != null ? mutated : current;
                List<BoardEnvironmentVariableDto> saved =
                        saveEnvironmentVariablesInternal(userId, currentNodes, next, true);
                recordEnvironmentUpdate(userId, current, saved);
                return saved;
            });
        }
    }

    @Override
    public DeviceMutationResultDto createNode(
            Long userId,
            java.util.function.Function<List<DeviceNodeDto>, DeviceNodeDto> nodeFactory) {
        Objects.requireNonNull(nodeFactory, "node factory must not be null");
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                List<DeviceNodeDto> current = new ArrayList<>(getNodesInternal(userId));
                requireAdditionalCapacity("devices", current.size(), 1, RequestLimits.MAX_DEVICES);
                List<BoardEnvironmentVariableDto> previousEnvironment = getEnvironmentVariablesInternal(userId);
                DeviceNodeDto createdDraft = Objects.requireNonNull(
                        nodeFactory.apply(List.copyOf(current)), "node factory result must not be null");
                List<DeviceNodeDto> nextNodes = new ArrayList<>(current);
                nextNodes.add(createdDraft);
                List<DeviceNodeDto> canonicalNodes = canonicalizeNodes(userId, nextNodes);
                List<RuleDto> currentRules = getRulesInternal(userId);
                List<SpecificationDto> currentSpecs = getSpecsInternal(userId);
                validateBoardReferences(userId, canonicalNodes, currentRules, currentSpecs);
                List<DeviceNodeDto> savedNodes = saveNodesInternal(userId, canonicalNodes);
                List<BoardEnvironmentVariableDto> savedEnvironment = getEnvironmentVariablesInternal(userId);
                requireTotalCapacity("environment variables", savedEnvironment.size(),
                        RequestLimits.MAX_ENVIRONMENT_VARIABLES);
                DeviceNodeDto created = requireNode(savedNodes, createdDraft.getId());
                DeviceJournalSnapshot before = deviceJournalSnapshot(
                        List.of(created.getId()), List.of(), List.of(), List.of(), previousEnvironment);
                DeviceJournalSnapshot after = deviceJournalSnapshot(
                        List.of(created.getId()),
                        positionedDevices(savedNodes, Set.of(created.getId())),
                        List.of(), List.of(), savedEnvironment);
                editJournal.record(userId, BoardEditEntityType.DEVICE, BoardEditOperation.CREATE,
                        created.getId(), before, after);
                BoardUndoAvailability availability = editJournal.availability(userId);
                return DeviceMutationResultDto.builder()
                        .operation("created")
                        .affectedDevices(List.of(created))
                        .currentNodes(savedNodes)
                        .environmentVariables(savedEnvironment)
                        .environmentChanges(diffEnvironmentVariables(previousEnvironment, savedEnvironment))
                        .currentSpecifications(currentSpecs)
                        .currentCount(savedNodes.size())
                        .canUndo(availability.canUndo())
                        .canRedo(availability.canRedo())
                        .build();
            });
        }
    }

    /** Lock-free/transaction-free node save; safe to call inside transactionTemplate lambdas. */
    private List<DeviceNodeDto> saveNodesInternal(Long userId, List<DeviceNodeDto> nodes) {
        List<DeviceNodeDto> saved = persistNodesInternal(userId, nodes);
        syncEnvironmentPoolForNodes(userId, nodes);
        return saved;
    }

    private List<DeviceNodeDto> persistNodesInternal(Long userId, List<DeviceNodeDto> nodes) {
        Set<String> retainedIds = nodes.stream().map(DeviceNodeDto::getId).collect(Collectors.toSet());
        List<DeviceNodePo> removed = nodeRepo.findByUserId(userId).stream()
                .filter(node -> !retainedIds.contains(node.getId()))
                .toList();
        if (!removed.isEmpty()) {
            nodeRepo.deleteAll(removed);
        }
        List<DeviceNodePo> pos = nodes.stream()
                .map(dto -> deviceNodeMapper.toEntity(dto, userId))
                .toList();
        List<DeviceNodePo> saved = nodeRepo.saveAll(Objects.requireNonNull(pos, "nodes to save must not be null"));
        return saved.stream().map(deviceNodeMapper::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpecificationDto> getSpecs(Long userId) {
        return getSpecsInternal(userId);
    }

    /** Non-transactional read; safe to call inside transactionTemplate lambdas. */
    private List<SpecificationDto> getSpecsInternal(Long userId) {
        return specRepo.findByUserId(userId).stream()
                .map(specificationMapper::toDto)
                .toList();
    }

    @Override
    public CollectionMutationResultDto<SpecificationDto> addSpec(Long userId, SpecificationDto spec) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
                List<SpecificationDto> existing = new ArrayList<>(getSpecsInternal(userId));
                requireAdditionalCapacity(
                        "specifications", existing.size(), 1, RequestLimits.MAX_SPECS);
                // Demanded on the specification being authored, not on the whole stored collection: this is
                // the point where a choice is actually being made, so refusing here names a field the
                // request contains. The shared revalidation below deliberately tolerates an absent reading
                // on already-stored specifications, or a legacy row would block this write too.
                requireResolvedVariableSource(spec);
                requireSpecWithinDeclaredBounds(spec);
                existing.add(spec);
                validateBoardReferences(userId, currentNodes, null, existing);
                List<SpecificationDto> saved = saveSpecsInternal(userId, existing, currentNodes);
                SpecificationDto created = saved.stream()
                        .filter(candidate -> Objects.equals(candidate.getId(), spec.getId()))
                        .findFirst()
                        .orElse(spec);
                editJournal.record(userId, BoardEditEntityType.SPECIFICATION,
                        BoardEditOperation.CREATE, created.getId(), null, created,
                        indexOfSpecification(saved, created.getId()));
                return withUndoAvailability(
                        userId, CollectionMutationResultDto.of("created", created, saved));
            });
        }
    }

    @Override
    public CollectionMutationResultDto<SpecificationDto> updateSpecIfUnchanged(
            Long userId, String specId, SpecificationDto expected, SpecificationDto desired) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                if (!hasText(specId) || expected == null || desired == null
                        || !specId.equals(expected.getId()) || !specId.equals(desired.getId())) {
                    throw new BadRequestException(
                            "Expected and desired specification snapshots must identify the specification being edited.");
                }
                List<SpecificationPo> records = specRepo.findByUserId(userId);
                List<SpecificationDto> current = records.stream().map(specificationMapper::toDto).toList();
                int position = indexOfSpecification(current, specId);
                if (position == current.size()) throw new ResourceNotFoundException("Specification", specId);
                SpecificationDto before = current.get(position);
                if (!sameSpecificationDeletionSnapshot(expected, before)) {
                    String message = "The specification changed before this edit. Refresh it before saving your changes.";
                    throw new BoardContentConflictException("SPEC_EDIT_STALE", message, Map.of("expected", message));
                }
                requireResolvedVariableSource(desired);
                requireSpecWithinDeclaredBounds(desired);
                List<DeviceNodeDto> nodes = getNodesInternal(userId);
                List<SpecificationDto> next = new ArrayList<>(current);
                next.set(position, desired);
                validateNoIdenticalSpecifications(next, nodes);
                validateBoardReferences(userId, nodes, null, next);
                if (sameSpecificationDeletionSnapshot(before, desired)) {
                    return withUndoAvailability(userId,
                            CollectionMutationResultDto.of("unchanged", before, current));
                }
                SpecificationFormulaPreview.Context previewContext = SpecificationFormulaPreview.context(
                        nodes, getDeviceTemplatesInternal(userId));
                SpecificationDto canonical = canonicalizeSpecificationForStorage(desired, previewContext);
                SpecificationPo updated = specificationMapper.toEntity(canonical, userId);
                updated.setListOrder(records.get(position).getListOrder());
                SpecificationDto saved = specificationMapper.toDto(specRepo.save(updated));
                editJournal.record(userId, BoardEditEntityType.SPECIFICATION, BoardEditOperation.UPDATE,
                        specId, before, saved, position);
                return withUndoAvailability(userId,
                        CollectionMutationResultDto.of("updated", saved, getSpecsInternal(userId)));
            });
        }
    }

    @Override
    public CollectionMutationResultDto<SpecificationDto> removeSpecIfUnchanged(
            Long userId, String specId, SpecificationDto expected) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                if (expected == null) {
                    throw new BadRequestException(
                            "The confirmed specification snapshot is required for deletion.");
                }
                List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
                List<SpecificationDto> existing = new ArrayList<>(getSpecsInternal(userId));
                SpecificationDto removed = existing.stream()
                        .filter(candidate -> specId.equals(candidate.getId()))
                        .findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Specification", specId));
                if (!sameSpecificationDeletionSnapshot(expected, removed)) {
                    throw new ConflictException(
                            "The specification changed after confirmation. Review the current specification before deleting it.");
                }
                // Record the list position too, for the same reason rule deletion does: saveSpecsInternal
                // assigns list_order from list index, so restoring by appending puts the specification
                // somewhere the user did not delete it from.
                int removedAt = indexOfSpecification(existing, specId);
                existing.removeIf(candidate -> specId.equals(candidate.getId()));
                List<SpecificationDto> saved = saveSpecsInternal(userId, existing, currentNodes);
                editJournal.record(userId, BoardEditEntityType.SPECIFICATION,
                        BoardEditOperation.DELETE, specId, removed, null, removedAt);
                return withUndoAvailability(
                        userId, CollectionMutationResultDto.of("deleted", removed, saved));
            });
        }
    }

    private boolean sameSpecificationDeletionSnapshot(
            SpecificationDto expected, SpecificationDto current) {
        return Objects.equals(expected.getId(), current.getId())
                && Objects.equals(expected.getName().trim(), current.getName().trim())
                && SpecificationSemanticSignature.exactlyMatches(expected, current);
    }

    private boolean hasValidSpecificationName(SpecificationDto spec) {
        return spec != null && hasText(spec.getName()) && spec.getName().length() <= 100;
    }

    /** Internal save without re-acquiring the lock. */
    private List<SpecificationDto> saveSpecsInternal(Long userId,
                                                     List<SpecificationDto> specs,
                                                     List<DeviceNodeDto> nodes) {
        validateNoIdenticalSpecifications(specs, nodes);
        Set<String> retainedIds = specs.stream().map(SpecificationDto::getId).collect(Collectors.toSet());
        List<SpecificationPo> removed = specRepo.findByUserId(userId).stream()
                .filter(spec -> !retainedIds.contains(spec.getId()))
                .toList();
        if (!removed.isEmpty()) {
            specRepo.deleteAll(removed);
        }
        SpecificationFormulaPreview.Context previewContext = SpecificationFormulaPreview.context(
                nodes,
                deviceTemplateRepo.findByUserId(userId).stream()
                        .map(deviceTemplateMapper::toDto)
                        .toList());
        List<SpecificationPo> pos = new ArrayList<>(specs.size());
        for (int listOrder = 0; listOrder < specs.size(); listOrder++) {
            SpecificationDto canonical = canonicalizeSpecificationForStorage(
                    specs.get(listOrder), previewContext);
            SpecificationPo po = specificationMapper.toEntity(canonical, userId);
            po.setListOrder(listOrder);
            pos.add(po);
        }
        specRepo.saveAll(Objects.requireNonNull(pos, "specs to save must not be null"));
        return pos.stream()
                .map(specificationMapper::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleDto> getRules(Long userId) {
        return getRulesInternal(userId);
    }

    /** Non-transactional read; safe to call inside transactionTemplate lambdas. */
    private List<RuleDto> getRulesInternal(Long userId) {
        // This order is user-controlled model semantics, not incidental database iteration order.
        return ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(userId).stream()
                .map(ruleMapper::toDto)
                .toList();
    }

    @Override
    public CollectionMutationResultDto<RuleDto> updateRulesAgainstSnapshot(
            Long userId,
            java.util.function.Function<BoardSemanticSnapshotDto, List<RuleDto>> mutator) {
        Objects.requireNonNull(mutator, "rule snapshot mutator must not be null");
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                BoardSemanticSnapshotDto snapshot = getSemanticSnapshotInternal(userId);
                List<RuleDto> next = Objects.requireNonNull(
                        mutator.apply(snapshot), "rule snapshot mutator result must not be null");
                Map<Long, RuleDto> existingRules = snapshot.rules().stream()
                        .collect(Collectors.toMap(RuleDto::getId, rule -> rule));
                for (RuleDto rule : next) {
                    RuleDto existing = rule == null ? null : existingRules.get(rule.getId());
                    // Authoring limits belong to changed rules; an untouched stored row must not block
                    // a targeted edit or a fix of another rule.
                    if (existing == null || !sameRuleDeletionSnapshot(existing, rule)) {
                        requireRuleWithinDeclaredBounds(rule);
                    }
                }
                validateBoardReferences(
                        userId,
                        snapshot.nodes(),
                        next,
                        snapshot.specifications(),
                        templateManifestMap(snapshot.deviceTemplates()));
                List<RuleDto> saved = saveRulesInternal(userId, next);
                if (sameOrderedRuleCollection(snapshot.rules(), saved)) {
                    throw new BadRequestException("The rule update did not change any rule");
                }
                editJournal.record(userId, BoardEditEntityType.RULE_SET, BoardEditOperation.UPDATE,
                        RULE_SET_ENTITY_KEY,
                        new RuleCollectionJournalSnapshot(snapshot.rules()),
                        new RuleCollectionJournalSnapshot(saved));
                return withUndoAvailability(
                        userId, CollectionMutationResultDto.of("updated", null, saved));
            });
        }
    }

    /**
     * Lock-free/transaction-free identity-preserving full-list rule save.
     * Existing ids in the request are updated in place; missing existing ids are deleted.
     */
    private List<RuleDto> saveRulesInternal(Long userId, List<RuleDto> rules) {
        validateNoIdenticalRules(rules, getNodesInternal(userId));
        // Read existing rules so surviving ids keep createdAt and omitted ids can be deleted.
        Map<Long, RulePo> existingRules = ruleRepo.findByUserId(userId).stream()
                .collect(Collectors.toMap(RulePo::getId, r -> r));

        // Rule ids present in this complete desired rule list.
        Set<Long> newRuleIds = new HashSet<>();

        for (int executionOrder = 0; executionOrder < rules.size(); executionOrder++) {
            RuleDto r = rules.get(executionOrder);
            Long ruleId = r.getId();
            if (ruleId != null) {
                newRuleIds.add(ruleId);
            }

            RuleDto canonicalRule = canonicalizeRuleRelationsForStorage(r);
            RulePo po = ruleMapper.toEntity(canonicalRule, userId);
            po.setExecutionOrder(executionOrder);
            if (ruleId == null) {
                ruleRepo.save(Objects.requireNonNull(po, "rule to save must not be null"));
            } else if (existingRules.containsKey(ruleId)) {
                po.setId(ruleId);
                po.setCreatedAt(existingRules.get(ruleId).getCreatedAt());
                ruleRepo.save(Objects.requireNonNull(po, "rule to save must not be null"));
            } else {
                log.warn("Rule id {} does not belong to user {}, inserting as new rule", ruleId, userId);
                po.setId(null);
                ruleRepo.save(Objects.requireNonNull(po, "rule to save must not be null"));
            }
        }

        // Existing rules omitted from the request are intentionally deleted.
        for (Long existingId : existingRules.keySet()) {
            if (!newRuleIds.contains(existingId)) {
                ruleRepo.deleteById(Objects.requireNonNull(existingId, "rule id to delete must not be null"));
            }
        }

        return getRulesInternal(userId);
    }

    private RuleDto canonicalizeRuleRelationsForStorage(RuleDto source) {
        if (source == null) {
            return null;
        }
        RuleDto target = new RuleDto();
        target.setId(source.getId());
        target.setUserId(source.getUserId());
        target.setRuleString(source.getRuleString());
        target.setCreatedAt(source.getCreatedAt());
        if (source.getConditions() == null) {
            target.setConditions(Collections.emptyList());
        } else {
            target.setConditions(source.getConditions().stream()
                    .filter(Objects::nonNull)
                    .map(condition -> RuleDto.Condition.builder()
                            .deviceName(condition.getDeviceName())
                            // Trimmed for the same reason as the command action below: admission
                            // resolves the attribute trimmed, generation matches manifest variable,
                            // mode, and signal-API names by equals().
                            .attribute(trimToNull(condition.getAttribute()))
                            .targetType(canonicalRuleTargetTypeOrOriginal(condition.getTargetType()))
                            .relation(canonicalRelationOrOriginal(condition.getRelation()))
                            .value(condition.getValue())
                            .build())
                    .toList());
        }
        RuleDto.Command command = source.getCommand();
        if (command != null) {
            target.setCommand(RuleDto.Command.builder()
                    .deviceName(command.getDeviceName())
                    // Stored trimmed: the action is cross-referenced against template API names by
                    // equals() at generation time, and admission compares trimmed. Persisting the
                    // untrimmed form left those two disagreeing.
                    .action(trimToNull(command.getAction()))
                    .contentDevice(command.getContentDevice())
                    .content(command.getContent())
                    .build());
        }
        return target;
    }

    private SpecificationDto canonicalizeSpecificationForStorage(
            SpecificationDto source,
            SpecificationFormulaPreview.Context previewContext) {
        if (source == null) {
            return null;
        }
        Map<String, String> labelsById = previewContext.labelsById();
        SpecificationDto target = new SpecificationDto();
        target.setId(source.getId());
        target.setName(source.getName().trim());
        target.setTemplateId(source.getTemplateId());
        target.setTemplateLabel(SpecificationFormulaPreview.templateLabel(source.getTemplateId()));
        target.setAConditions(canonicalizeSpecConditionsForStorage(source.getAConditions(), labelsById));
        target.setIfConditions(canonicalizeSpecConditionsForStorage(source.getIfConditions(), labelsById));
        target.setThenConditions(canonicalizeSpecConditionsForStorage(source.getThenConditions(), labelsById));
        target.setDevices(buildSpecificationDeviceRefs(target, labelsById));
        target.setFormula(SpecificationFormulaPreview.format(target, previewContext));
        return target;
    }

    /**
     * Demands that a specification <em>being authored</em> says which value each variable condition means.
     *
     * <p>Runs only on what the caller is writing, which is why it can require a choice at all — see
     * {@link #validateSpecConditionSemanticsForAnyOrigin} for why the shared validation cannot. Validity of a
     * chosen value stays there, being a reference error wherever it appears.
     */
    private void requireResolvedVariableSource(SpecificationDto spec) {
        if (spec == null) {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        requireResolvedVariableSource(errors, "aConditions", spec.getAConditions());
        requireResolvedVariableSource(errors, "ifConditions", spec.getIfConditions());
        requireResolvedVariableSource(errors, "thenConditions", spec.getThenConditions());
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    /**
     * Enforces the collection-size bounds that {@code RuleDto} and {@code SpecificationDto} declare as
     * {@code @Size}, on the record <em>being authored</em>.
     *
     * <p>Only Spring's {@code @Valid} REST path applies those annotations: the AI tools call this service
     * straight from a chat turn with no {@code Validator} in between, and {@code ManageRuleTool} /
     * {@code ManageSpecTool} loop the JSON arrays uncapped, so an assistant could store a record the UI
     * cannot submit.
     *
     * <p>Deliberately <em>not</em> in {@link #validateBoardReferences}, unlike the device-label length:
     * that column is {@code length = 255}, so an over-long label cannot already be stored and the check
     * can only ever fire on the request. These lists have no such floor — {@code rule_string} is
     * {@code TEXT} and the condition arrays are JSON — so an account already holding an oversized row
     * would have every later device add, layout move and undo rejected over a record its request never
     * mentions, with no way to get unstuck. Same reasoning as {@link #requireResolvedVariableSource}.
     */
    private void requireRuleWithinDeclaredBounds(RuleDto rule) {
        if (rule == null) {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        List<RuleDto.Condition> conditions = rule.getConditions();
        if (conditions != null && conditions.size() > RequestLimits.MAX_RULE_CONDITIONS) {
            errors.put("conditions",
                    "At most " + RequestLimits.MAX_RULE_CONDITIONS
                            + " conditions are allowed per rule, but was " + conditions.size() + ".");
        }
        // Checked independently of `conditions`: the preview does not depend on them, so guarding it
        // behind a null-conditions check would let a request omitting `conditions` persist an unbounded
        // preview — exactly the case this bound exists to stop.
        String preview = rule.getRuleString();
        if (preview != null && preview.length() > RequestLimits.MAX_DESCRIPTION_LENGTH) {
            errors.put("ruleString",
                    "Rule preview must be at most " + RequestLimits.MAX_DESCRIPTION_LENGTH
                            + " characters, but was " + preview.length() + ".");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    /** Spec counterpart of {@link #requireRuleWithinDeclaredBounds}; same authoring-only reasoning. */
    private void requireSpecWithinDeclaredBounds(SpecificationDto spec) {
        if (spec == null) {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (spec.getName() == null || spec.getName().isBlank() || spec.getName().trim().length() > 100) {
            errors.put("name", "A specification name of 1 to 100 characters is required.");
        }
        requireSpecConditionCount(errors, "aConditions", spec.getAConditions());
        requireSpecConditionCount(errors, "ifConditions", spec.getIfConditions());
        requireSpecConditionCount(errors, "thenConditions", spec.getThenConditions());
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void requireSpecConditionCount(Map<String, String> errors,
                                           String field,
                                           List<SpecConditionDto> conditions) {
        if (conditions == null || conditions.size() <= RequestLimits.MAX_SPEC_CONDITIONS) {
            return;
        }
        // `.size` rather than the bare field name, which `validateSpecTemplateShape` already claims for
        // its shape errors on `aConditions` / `ifConditions`. Nothing collides today — this runs first
        // and throws — but a size complaint and a shape complaint are different answers to "what is
        // wrong with this list", so they should not be able to overwrite each other under one key.
        errors.put(field + ".size",
                "At most " + RequestLimits.MAX_SPEC_CONDITIONS
                        + " conditions are allowed, but was " + conditions.size() + ".");
    }

    private void requireResolvedVariableSource(Map<String, String> errors,
                                               String field,
                                               List<SpecConditionDto> conditions) {
        if (conditions == null) {
            return;
        }
        for (int i = 0; i < conditions.size(); i++) {
            SpecConditionDto condition = conditions.get(i);
            if (condition == null
                    || !"variable".equals(normalizeTargetType(condition.getTargetType()))) {
                continue;
            }
            if (normalizeVariableSource(condition.getVariableSource()) == null) {
                errors.putIfAbsent(field + "[" + i + "].variableSource",
                        "variableSource is required for a variable condition and must be environment "
                                + "(the value in the home) or reported (what this device said). The two differ "
                                + "when a device is compromised, so there is no safe default");
            }
        }
    }

    private List<SpecConditionDto> canonicalizeSpecConditionsForStorage(
            List<SpecConditionDto> conditions,
            Map<String, String> labelsById) {
        if (conditions == null) {
            return Collections.emptyList();
        }
        return conditions.stream()
                .filter(Objects::nonNull)
                .map(condition -> {
                    String targetType = canonicalSpecTargetTypeOrOriginal(condition.getTargetType());
                    String relation = canonicalRelationOrOriginal(condition.getRelation());
                    SpecConditionDto copy = new SpecConditionDto();
                    copy.setId(condition.getId());
                    copy.setSide(condition.getSide());
                    copy.setDeviceId(condition.getDeviceId());
                    copy.setDeviceLabel(labelsById.getOrDefault(
                            condition.getDeviceId(), condition.getDeviceLabel()));
                    copy.setTargetType(targetType);
                    // Trimmed for the same reason as a rule condition's attribute: generation matches
                    // manifest variable, mode, and signal-API names by equals(), and some spec paths
                    // (buildVariableCondition, resolveModeName) trim while others
                    // (validateApiSignalExists, resolveApiUntrustedSource) compare raw. Storing the
                    // untrimmed key let a padded API condition pass admission and then fail
                    // generation with InvalidConditionException.
                    copy.setKey(trimToNull(condition.getKey()));
                    copy.setPropertyScope(("trust".equals(targetType) || "privacy".equals(targetType))
                            ? normalizePropertyScope(condition.getPropertyScope())
                            : null);
                    // Normalized here for the same reason as propertyScope above: this is the one spec write
                    // path, so a field absent here is validated on the request and then persisted blank —
                    // the spec would reload unresolved and block the next run, for every variable condition
                    // the user authored.
                    copy.setVariableSource("variable".equals(targetType)
                            ? normalizeVariableSource(condition.getVariableSource())
                            : null);
                    copy.setRelation(relation);
                    copy.setValue(canonicalSpecLabelValue(
                            targetType, relation, condition.getValue()));
                    return copy;
                })
                .toList();
    }

    private String canonicalSpecLabelValue(String targetType, String relation, String rawValue) {
        if (!("trust".equals(targetType) || "privacy".equals(targetType)) || !hasText(rawValue)) {
            return rawValue;
        }
        if ("in".equals(relation) || "not in".equals(relation)) {
            return SmvRelationUtils.splitRuleValues(rawValue).stream()
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
        }
        return rawValue.trim().toLowerCase(Locale.ROOT);
    }

    private List<SpecificationDto.DeviceRefDto> buildSpecificationDeviceRefs(
            SpecificationDto specification,
            Map<String, String> labelsById) {
        Map<String, SpecificationDto.DeviceRefDto> refs = new LinkedHashMap<>();
        for (SpecConditionDto condition : allSpecificationConditions(specification)) {
            if (condition == null || !hasText(condition.getDeviceId())) {
                continue;
            }
            String deviceId = condition.getDeviceId().trim();
            SpecificationDto.DeviceRefDto ref = refs.computeIfAbsent(deviceId,
                    id -> new SpecificationDto.DeviceRefDto(
                            id,
                            labelsById.getOrDefault(id, id),
                            new ArrayList<>()));
            if ("api".equalsIgnoreCase(condition.getTargetType()) && hasText(condition.getKey())
                    && !ref.getSelectedApis().contains(condition.getKey().trim())) {
                ref.getSelectedApis().add(condition.getKey().trim());
            }
        }
        return new ArrayList<>(refs.values());
    }

    private List<SpecConditionDto> allSpecificationConditions(SpecificationDto specification) {
        List<SpecConditionDto> conditions = new ArrayList<>();
        if (specification.getAConditions() != null) conditions.addAll(specification.getAConditions());
        if (specification.getIfConditions() != null) conditions.addAll(specification.getIfConditions());
        if (specification.getThenConditions() != null) conditions.addAll(specification.getThenConditions());
        return conditions;
    }

    private String canonicalRelationOrOriginal(String rawRelation) {
        String normalized = SmvRelationUtils.normalizeRelation(rawRelation);
        return SmvRelationUtils.isSupportedRelation(normalized) ? normalized : rawRelation;
    }

    private String canonicalRuleTargetTypeOrOriginal(String rawTargetType) {
        String normalized = normalizeTargetType(rawTargetType);
        return normalized != null ? normalized : rawTargetType;
    }

    private String canonicalSpecTargetTypeOrOriginal(String rawTargetType) {
        String normalized = normalizeSpecTargetType(rawTargetType);
        return normalized != null ? normalized : rawTargetType;
    }

    @Override
    public BoardReplacementPreviewDto previewBoardReplacement(Long userId) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                return currentBoardReplacementPreview(userId);
            });
        }
    }

    @Override
    public BoardBatchDto saveBoardBatch(Long userId, BoardBatchDto batch) {
        return saveBoardBatch(userId, batch, () -> {});
    }

    @Override
    public BoardBatchDto saveBoardBatch(Long userId, BoardBatchDto batch, Runnable afterReplacement) {
        Objects.requireNonNull(afterReplacement, "Scene replacement completion is required");
        // Atomic save of nodes + environment + rules + specs in ONE transaction under the user write lock.
        // Scene import depends on this so a new topology cannot be persisted with an old environment pool.
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                var owner = requireActiveUserForWrite(userId);
                validateCompleteSceneBatch(batch);
                BoardReplacementPreviewDto currentPreview = currentBoardReplacementPreview(userId);
                if (!hasText(batch.getImpactToken()) || !MessageDigest.isEqual(
                        currentPreview.getImpactToken().getBytes(StandardCharsets.UTF_8),
                        batch.getImpactToken().trim().getBytes(StandardCharsets.UTF_8))) {
                    throw new BoardReplacementStaleException(currentPreview);
                }
                List<DeviceTemplateDto> createdTemplates = batch.getTemplateSnapshots() != null
                        ? resolveSceneTemplateSnapshots(userId, batch.getTemplateSnapshots(), batch.getNodes())
                        : List.of();
                validateAuthoredSceneVariableValues(batch.getNodes(), loadTemplateManifestMap(userId));
                List<DeviceNodeDto> nextNodes = canonicalizeNodes(userId, batch.getNodes());
                List<RuleDto> nextRules = batch.getRules();
                List<SpecificationDto> nextSpecs = batch.getSpecs();
                validateNoIdenticalRules(nextRules, nextNodes);
                validateNoIdenticalSpecifications(nextSpecs, nextNodes);
                // Every specification here is being written by this request (batch is a full replacement),
                // so each must state its reading — unlike the shared revalidation below, which also sees
                // already-stored specifications and tolerates an absent one.
                safeList(nextSpecs).forEach(this::requireResolvedVariableSource);
                safeList(nextSpecs).forEach(this::requireSpecWithinDeclaredBounds);
                safeList(nextRules).forEach(this::requireRuleWithinDeclaredBounds);
                validateBoardReferences(userId, nextNodes, nextRules, nextSpecs);
                if (batch.getTemplateSnapshots() != null) {
                    validateCompleteSceneEnvironment(userId, nextNodes, batch.getEnvironmentVariables());
                }

                // A full replacement supplies its own pool. Projecting old values into the new
                // domains here would reject a valid scene before its submitted pool is saved.
                List<DeviceNodeDto> savedNodes = persistNodesInternal(userId, nextNodes);
                List<BoardEnvironmentVariableDto> savedEnvironment = saveEnvironmentVariablesInternal(
                        userId, savedNodes, batch.getEnvironmentVariables(), batch.getTemplateSnapshots() == null);
                List<RuleDto> savedRules = saveRulesInternal(userId, batch.getRules());
                List<SpecificationDto> savedSpecs = saveSpecsInternal(userId, batch.getSpecs(), savedNodes);
                BoardBatchDto result = new BoardBatchDto(savedNodes, savedEnvironment, savedRules, savedSpecs);
                result.setCreatedTemplates(createdTemplates);
                // Scene replacement rewrites every collection at once, so no per-record inverse
                // recorded before it still describes a reachable state. Dropping the journal is
                // safer than leaving entries that would "restore" a rule into a different scene.
                editJournal.clear(userId);
                // Ordinary edits retain task intent; only explicit full-scene replacement changes its scope.
                owner.setSceneRevision(Math.incrementExact(owner.getSceneRevision()));
                // A confirmed AI draft is consumed with its scene, so cleanup failure cannot
                // turn a committed replacement into an ordinary tool failure.
                afterReplacement.run();
                return result;
            });
        }
    }

    private void validateCompleteSceneBatch(BoardBatchDto batch) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (batch == null) {
            throw new BadRequestException("Complete scene replacement request is required; no board data was changed.");
        }
        if (batch.getNodes() == null) {
            errors.put("nodes", "Scene replacement must explicitly provide the complete nodes collection");
        }
        if (batch.getEnvironmentVariables() == null) {
            errors.put("environmentVariables",
                    "Scene replacement must explicitly provide the complete environmentVariables collection");
        }
        if (batch.getRules() == null) {
            errors.put("rules", "Scene replacement must explicitly provide the complete rules collection");
        }
        if (batch.getSpecs() == null) {
            errors.put("specs", "Scene replacement must explicitly provide the complete specs collection");
        }
        addCollectionLimitError(errors, "nodes", batch.getNodes(), RequestLimits.MAX_DEVICES);
        addCollectionLimitError(errors, "environmentVariables", batch.getEnvironmentVariables(),
                RequestLimits.MAX_ENVIRONMENT_VARIABLES);
        addCollectionLimitError(errors, "rules", batch.getRules(), RequestLimits.MAX_RULES);
        addCollectionLimitError(errors, "specs", batch.getSpecs(), RequestLimits.MAX_SPECS);
        addCollectionLimitError(errors, "templateSnapshots", batch.getTemplateSnapshots(),
                RequestLimits.MAX_TEMPLATES);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private BoardReplacementPreviewDto currentBoardReplacementPreview(Long userId) {
        List<DeviceNodeDto> nodes = new ArrayList<>(getNodesInternal(userId));
        nodes.sort(Comparator.comparing(
                node -> node == null || node.getId() == null ? "" : node.getId(),
                String.CASE_INSENSITIVE_ORDER));
        List<BoardEnvironmentVariableDto> environmentVariables = getEnvironmentVariablesInternal(userId);
        List<RuleDto> rules = getRulesInternal(userId);
        List<SpecificationDto> specifications = getSpecsInternal(userId);
        BoardEditHistoryState editHistory = editJournal.historyState(userId);

        List<DeviceTemplateDto> templates = new ArrayList<>(getDeviceTemplatesInternal(userId));
        templates.sort(Comparator.comparing(DeviceTemplateDto::getName, String.CASE_INSENSITIVE_ORDER));
        Map<String, Integer> templateDeviceCounts = new HashMap<>();
        for (DeviceNodeDto node : nodes) {
            if (node != null && hasText(node.getTemplateName())) {
                templateDeviceCounts.merge(templateAliasKey(node.getTemplateName()), 1, Integer::sum);
            }
        }

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("contract", "board-replacement-v3");
        impact.put("nodes", nodes);
        impact.put("environmentVariables", environmentVariables);
        impact.put("rules", rules);
        impact.put("specifications", specifications);
        impact.put("editHistoryImpactToken", editHistory.impactToken());
        // Confirmation includes retained library contents and import provenance, even on an empty board.
        impact.put("templates", templates);
        String source = JsonUtils.toJson(impact);
        try {
            String impactToken = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            return BoardReplacementPreviewDto.builder()
                    .impactToken(impactToken)
                    .deviceCount(nodes.size())
                    .environmentVariableCount(environmentVariables.size())
                    .ruleCount(rules.size())
                    .specificationCount(specifications.size())
                    .editHistoryEntryCount(editHistory.entryCount())
                    .templates(templates.stream().map(template -> new BoardReplacementPreviewDto.TemplateUsage(
                            template.getName(), template.getDefaultTemplate(),
                            templateDeviceCounts.getOrDefault(templateAliasKey(template.getName()), 0))).toList())
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new InternalServerException("Unable to create board-replacement impact token", e);
        }
    }

    private void validateCompleteSceneEnvironment(Long userId,
                                                  List<DeviceNodeDto> nodes,
                                                  List<BoardEnvironmentVariableDto> variables) {
        Map<String, DeviceManifest> templateManifests = loadTemplateManifestMap(userId);
        requireActiveEnvironmentDomainConsistency(nodes, templateManifests);
        Map<String, DeviceManifest.InternalVariable> required = collectRequiredEnvironmentVariables(
                nodes, templateManifests);
        validateSubmittedEnvironmentNames(variables, required);
        validateCompleteEnvironmentSubmission(variables, required);
        validateEnvironmentVariables(variables, required);
    }

    /**
     * Resolve scene-import template dependencies inside the same transaction as the board replacement.
     * A snapshot is never silently ignored: a mismatched existing template is a conflict, and a missing
     * referenced template without a snapshot rejects the whole batch before board collections are saved.
     */
    private List<DeviceTemplateDto> resolveSceneTemplateSnapshots(Long userId,
                                                                    List<DeviceTemplateDto> snapshots,
                                                                    List<DeviceNodeDto> nodes) {
        if (nodes == null) {
            throw new BadRequestException("Scene import requires a complete nodes collection.");
        }
        Map<String, DeviceTemplateDto> snapshotsByAlias = sceneTemplateSnapshotsByAlias(snapshots);
        Map<String, DeviceTemplateDto> existingByAlias = currentTemplatesByAlias(userId);
        Set<String> referencedTemplateNames = new LinkedHashSet<>();
        for (DeviceNodeDto node : nodes == null ? List.<DeviceNodeDto>of() : nodes) {
            if (node != null && hasText(node.getTemplateName())) {
                referencedTemplateNames.add(node.getTemplateName().trim());
            }
        }

        List<DeviceTemplateDto> created = new ArrayList<>();
        Set<DeviceTemplateDto> usedSnapshots = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String requestedName : referencedTemplateNames) {
            String key = templateAliasKey(requestedName);
            DeviceTemplateDto existing = existingByAlias.get(key);
            DeviceTemplateDto snapshot = snapshotsByAlias.get(key);
            if (snapshot == null) {
                throw new BadRequestException("Scene references device template '" + requestedName
                        + "' without a matching template snapshot. Scene files must be self-contained.");
            }
            usedSnapshots.add(snapshot);
            if (existing != null) {
                if (!sameTemplateManifest(existing, snapshot)) {
                    String reason = "Scene template snapshot does not match the existing template: " + requestedName
                            + ". Match the existing manifest, or give the imported template and its device references a new name.";
                    throw new BoardContentConflictException("SCENE_TEMPLATE_MISMATCH", reason,
                            Map.of("templateSnapshots[" + snapshots.indexOf(snapshot) + "].manifest", reason), requestedName);
                }
                continue;
            }
            DeviceTemplateDto added = addDeviceTemplate(userId, snapshot);
            created.add(added);
            addTemplateAliases(existingByAlias, added);
        }
        for (DeviceTemplateDto snapshot : snapshots) {
            if (!usedSnapshots.contains(snapshot)) {
                String name = trimToNull(snapshot.getName());
                if (name == null && snapshot.getManifest() != null) {
                    name = trimToNull(snapshot.getManifest().getName());
                }
                throw new BadRequestException("Scene contains an unreferenced template snapshot: "
                        + (name != null ? name : "<unnamed>"));
            }
        }
        return created;
    }

    private void validateAuthoredSceneVariableValues(List<DeviceNodeDto> nodes, Map<String, DeviceManifest> templates) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            DeviceNodeDto node = nodes.get(nodeIndex);
            if (node == null) continue;
            DeviceManifest manifest = manifestForTemplateName(node.getTemplateName(), templates);
            List<VariableStateDto> variables = safeList(node.getVariables());
            for (int variableIndex = 0; variableIndex < variables.size(); variableIndex++) {
                VariableStateDto variable = variables.get(variableIndex);
                if (variable == null) continue;
                String declared = DeviceManifestModes.stateDeclaredValue(manifest, node.getState(), trimToNull(variable.getName()));
                if (declared != null && !Objects.equals(declared, trimToNull(variable.getValue()))) {
                    errors.put("nodes[" + nodeIndex + "].variables[" + variableIndex + "].value",
                            "State '" + node.getState() + "' requires " + variable.getName() + " = '" + declared
                                    + "'. Review this value before importing the complete scene.");
                }
            }
        }
        // Ordinary device edits derive these values; a complete scene must not silently rewrite authored input.
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private Map<String, DeviceTemplateDto> sceneTemplateSnapshotsByAlias(List<DeviceTemplateDto> snapshots) {
        Map<String, DeviceTemplateDto> byAlias = new LinkedHashMap<>();
        for (DeviceTemplateDto snapshot : snapshots == null ? List.<DeviceTemplateDto>of() : snapshots) {
            if (snapshot == null) {
                throw new BadRequestException("Scene template snapshot item cannot be null.");
            }
            String name = trimToNull(snapshot.getName());
            String manifestName = snapshot.getManifest() == null ? null : trimToNull(snapshot.getManifest().getName());
            if (name == null || manifestName == null) {
                throw new BadRequestException(
                        "Scene template snapshot requires both name and manifest.Name.");
            }
            if (!name.equals(manifestName)) {
                throw new BadRequestException("Scene template snapshot name '" + name
                        + "' must exactly match manifest.Name '" + manifestName
                        + "'. Portable scenes cannot rename a template while importing it.");
            }
            // Creation trims both names; reuse must compare the same canonical manifest.
            snapshot.setName(name);
            snapshot.getManifest().setName(name);
            addSceneTemplateSnapshotAlias(byAlias, name, snapshot);
        }
        return byAlias;
    }

    private void addSceneTemplateSnapshotAlias(Map<String, DeviceTemplateDto> snapshots,
                                               String alias,
                                               DeviceTemplateDto snapshot) {
        if (!hasText(alias)) {
            return;
        }
        String key = templateAliasKey(alias);
        DeviceTemplateDto previous = snapshots.putIfAbsent(key, snapshot);
        if (previous != null && previous != snapshot && !sameTemplateManifest(previous, snapshot)) {
            throw new BadRequestException("Scene contains conflicting template snapshots for '" + alias + "'.");
        }
    }

    private Map<String, DeviceTemplateDto> currentTemplatesByAlias(Long userId) {
        Map<String, DeviceTemplateDto> byAlias = new LinkedHashMap<>();
        List<DeviceTemplatePo> currentTemplates = deviceTemplateRepo.findByUserId(userId);
        for (DeviceTemplatePo template : currentTemplates == null ? List.<DeviceTemplatePo>of() : currentTemplates) {
            if (template != null) {
                addTemplateAliases(byAlias, deviceTemplateMapper.toDto(template));
            }
        }
        return byAlias;
    }

    private void addTemplateAliases(Map<String, DeviceTemplateDto> aliases, DeviceTemplateDto template) {
        if (template == null) {
            return;
        }
        if (hasText(template.getName())) {
            aliases.putIfAbsent(templateAliasKey(template.getName()), template);
        }
        if (template.getManifest() != null && hasText(template.getManifest().getName())) {
            aliases.putIfAbsent(templateAliasKey(template.getManifest().getName()), template);
        }
    }

    private String templateAliasKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean sameTemplateManifest(DeviceTemplateDto first, DeviceTemplateDto second) {
        if (first == null || second == null || first.getManifest() == null || second.getManifest() == null) {
            return first == second;
        }
        return Objects.equals(JsonUtils.toJson(first.getManifest()), JsonUtils.toJson(second.getManifest()));
    }

    /** Non-transactional node read; safe to call inside transactionTemplate lambdas. */
    private List<DeviceNodeDto> getNodesInternal(Long userId) {
        return nodeRepo.findByUserId(userId).stream()
                .map(deviceNodeMapper::toDto)
                .toList();
    }

    private BoardSemanticSnapshotDto getSemanticSnapshotInternal(Long userId) {
        return mapSemanticSnapshot(readSemanticSnapshotRows(userId));
    }

    private record SemanticSnapshotRows(List<DeviceNodePo> nodes,
                                        List<BoardEnvironmentVariablePo> environmentVariables,
                                        List<RulePo> rules,
                                        List<SpecificationPo> specifications,
                                        List<DeviceTemplatePo> deviceTemplates) {}

    private SemanticSnapshotRows readSemanticSnapshotRows(Long userId) {
        return new SemanticSnapshotRows(
                nodeRepo.findByUserId(userId),
                environmentRepo != null ? environmentRepo.findByUserIdOrderByNameAsc(userId) : List.of(),
                ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(userId),
                specRepo.findByUserId(userId),
                deviceTemplateRepo.findByUserId(userId));
    }

    private BoardSemanticSnapshotDto mapSemanticSnapshot(SemanticSnapshotRows rows) {
        return new BoardSemanticSnapshotDto(
                rows.nodes().stream().map(deviceNodeMapper::toDto).toList(),
                rows.environmentVariables().stream().map(this::environmentToDto).toList(),
                rows.rules().stream().map(ruleMapper::toDto).toList(),
                rows.specifications().stream().map(specificationMapper::toDto).toList(),
                rows.deviceTemplates().stream().map(deviceTemplateMapper::toDto).toList());
    }

    private List<BoardEnvironmentVariableDto> getEnvironmentVariablesInternal(Long userId) {
        if (environmentRepo == null) {
            return List.of();
        }
        return environmentRepo.findByUserIdOrderByNameAsc(userId).stream()
                .map(this::environmentToDto)
                .toList();
    }

    private BoardEnvironmentVariableDto environmentToDto(BoardEnvironmentVariablePo po) {
        if (po == null) {
            return null;
        }
        return new BoardEnvironmentVariableDto(
                po.getName(),
                po.getValue(),
                po.getTrust(),
                po.getPrivacy()
        );
    }

    private BoardEnvironmentVariablePo environmentToEntity(Long userId, BoardEnvironmentVariableDto dto) {
        return BoardEnvironmentVariablePo.builder()
                .userId(userId)
                .name(trimToNull(dto.getName()))
                .value(trimToNull(dto.getValue()))
                .trust(normalizeTrustPrivacyOrDefault(dto.getTrust(), "untrusted"))
                .privacy(normalizeTrustPrivacyOrDefault(dto.getPrivacy(), "public"))
                .build();
    }

    private List<BoardEnvironmentVariableDto> replaceEnvironmentVariablesInternal(
            Long userId,
            List<BoardEnvironmentVariableDto> variables) {
        if (environmentRepo == null) {
            return variables == null ? List.of() : variables;
        }
        List<BoardEnvironmentVariablePo> existing = environmentRepo.findByUserIdOrderByNameAsc(userId);
        Map<String, BoardEnvironmentVariableDto> existingByName = existing.stream()
                .collect(Collectors.toMap(BoardEnvironmentVariablePo::getName, this::environmentToDto));
        List<BoardEnvironmentVariablePo> entities = (variables == null ? List.<BoardEnvironmentVariableDto>of() : variables)
                .stream()
                .map(dto -> environmentToEntity(userId, dto))
                .filter(po -> hasText(po.getName()))
                .toList();
        Set<String> retainedNames = entities.stream().map(BoardEnvironmentVariablePo::getName)
                .collect(Collectors.toSet());
        List<BoardEnvironmentVariablePo> removed = existing.stream()
                .filter(variable -> !retainedNames.contains(variable.getName()))
                .toList();
        if (!removed.isEmpty()) {
            environmentRepo.deleteAll(removed);
        }
        List<BoardEnvironmentVariablePo> changed = entities.stream()
                .filter(variable -> !Objects.equals(existingByName.get(variable.getName()), environmentToDto(variable)))
                .toList();
        if (!changed.isEmpty()) {
            environmentRepo.saveAll(changed);
        }
        return entities.stream()
                .map(this::environmentToDto)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<BoardEnvironmentVariableDto> refreshEnvironmentVariablesInternal(
            Long userId,
            List<DeviceNodeDto> nodes) {
        List<BoardEnvironmentVariableDto> existing = getEnvironmentVariablesInternal(userId);
        List<BoardEnvironmentVariableDto> merged = projectEnvironmentVariablesForNodes(
                existing, nodes, loadTemplateManifestMap(userId), false);
        if (environmentDtoMap(existing).equals(environmentDtoMap(merged))) {
            return merged;
        }
        return replaceEnvironmentVariablesInternal(userId, merged);
    }

    private List<BoardEnvironmentVariableDto> projectEnvironmentVariablesForNodes(
            Long userId,
            List<DeviceNodeDto> nodes) {
        return projectEnvironmentVariablesForNodes(
                getEnvironmentVariablesInternal(userId), nodes, loadTemplateManifestMap(userId), false);
    }

    private List<BoardEnvironmentVariableDto> projectEnvironmentVariablesForNodes(
            List<BoardEnvironmentVariableDto> existing,
            List<DeviceNodeDto> nodes,
            Map<String, DeviceManifest> templateManifests,
            boolean resetValuesOutsideNewDomains) {
        requireActiveEnvironmentDomainConsistency(nodes, templateManifests);
        Map<String, DeviceManifest.InternalVariable> required = collectRequiredEnvironmentVariables(
                nodes, templateManifests);
        List<BoardEnvironmentVariableDto> merged = mergeEnvironmentVariables(
                List.of(), existing, required);
        if (resetValuesOutsideNewDomains) {
            merged = merged.stream().map(variable -> {
                DeviceManifest.InternalVariable definition = required.get(variable.getName());
                if (isLegalEnvironmentValue(variable.getValue(), definition)) {
                    return variable;
                }
                return new BoardEnvironmentVariableDto(
                        variable.getName(),
                        defaultEnvironmentValue(definition),
                        variable.getTrust(),
                        variable.getPrivacy());
            }).toList();
        }
        validateEnvironmentVariables(merged, required);
        return merged;
    }

    private boolean isLegalEnvironmentValue(String value, DeviceManifest.InternalVariable definition) {
        if (!hasText(value) || definition == null) {
            return false;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        validateVariableValues(errors, "value", definition, "=", value);
        return errors.isEmpty();
    }

    private List<EnvironmentVariableChangeDto> diffEnvironmentVariables(
            List<BoardEnvironmentVariableDto> previous,
            List<BoardEnvironmentVariableDto> current) {
        Map<String, BoardEnvironmentVariableDto> previousByName = environmentDtoMap(previous);
        Map<String, BoardEnvironmentVariableDto> currentByName = environmentDtoMap(current);
        Set<String> names = new TreeSet<>();
        names.addAll(previousByName.keySet());
        names.addAll(currentByName.keySet());

        List<EnvironmentVariableChangeDto> changes = new ArrayList<>();
        for (String name : names) {
            BoardEnvironmentVariableDto before = previousByName.get(name);
            BoardEnvironmentVariableDto after = currentByName.get(name);
            EnvironmentVariableChangeDto.ChangeType changeType;
            if (before == null) {
                changeType = EnvironmentVariableChangeDto.ChangeType.ADDED;
            } else if (after == null) {
                changeType = EnvironmentVariableChangeDto.ChangeType.REMOVED;
            } else if (!before.equals(after)) {
                changeType = EnvironmentVariableChangeDto.ChangeType.UPDATED;
            } else {
                continue;
            }
            changes.add(EnvironmentVariableChangeDto.builder()
                    .changeType(changeType)
                    .name(name)
                    .previousValue(before)
                    .currentValue(after)
                    .build());
        }
        return List.copyOf(changes);
    }

    private String deletionImpactToken(DeviceNodeDto device,
                                       List<RuleDto> removedRules,
                                       List<SpecificationDto> removedSpecifications,
                                       List<EnvironmentVariableChangeDto> environmentChanges) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("device", device);
        impact.put("removedRules", removedRules != null ? removedRules : List.of());
        impact.put("removedSpecifications",
                removedSpecifications != null ? removedSpecifications : List.of());
        impact.put("environmentChanges", environmentChanges != null ? environmentChanges : List.of());
        String source = JsonUtils.toJson(impact);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new InternalServerException("Unable to create device-deletion impact token", e);
        }
    }

    /**
     * A complete internal read-modify-write may use null fields to restore template defaults. A
     * scene import is stricter: every required environment variable and label must be explicit.
     */
    private List<BoardEnvironmentVariableDto> saveEnvironmentVariablesInternal(
            Long userId,
            List<DeviceNodeDto> nodes,
            List<BoardEnvironmentVariableDto> variables,
            boolean preserveExistingValues) {
        Map<String, DeviceManifest> templateManifests = loadTemplateManifestMap(userId);
        requireActiveEnvironmentDomainConsistency(nodes, templateManifests);
        Map<String, DeviceManifest.InternalVariable> required = collectRequiredEnvironmentVariables(
                nodes, templateManifests);
        validateSubmittedEnvironmentNames(variables, required);
        if (!preserveExistingValues) {
            validateCompleteEnvironmentSubmission(variables, required);
        }
        List<BoardEnvironmentVariableDto> merged = mergeEnvironmentVariables(
                variables, preserveExistingValues ? getEnvironmentVariablesInternal(userId) : List.of(), required);
        validateEnvironmentVariables(merged, required);
        return replaceEnvironmentVariablesInternal(userId, merged);
    }

    /**
     * Applies only non-null fields from each submitted item. Omitted fields retain the materialized
     * current value, so a UI edit of one label cannot reset the value or the other label.
     */
    /** The environment pool after the patches. Its one caller, {@code addNodes}, needs nothing else. */
    private List<BoardEnvironmentVariableDto> patchEnvironmentVariablesInternal(
            Long userId,
            List<DeviceNodeDto> nodes,
            List<BoardEnvironmentVariableDto> patches) {
        if (patches == null || patches.isEmpty()) {
            throw new BadRequestException("At least one environment variable patch is required");
        }
        Map<String, DeviceManifest> templateManifests = loadTemplateManifestMap(userId);
        requireActiveEnvironmentDomainConsistency(nodes, templateManifests);
        Map<String, DeviceManifest.InternalVariable> required = collectRequiredEnvironmentVariables(
                nodes, templateManifests);
        validateSubmittedEnvironmentNames(patches, required);
        validateEnvironmentPatchFields(patches);

        List<BoardEnvironmentVariableDto> materializedBefore = mergeEnvironmentVariables(
                List.of(), getEnvironmentVariablesInternal(userId), required);
        validateEnvironmentVariables(materializedBefore, required);

        return persistEnvironmentVariablePatches(userId, patches, materializedBefore, required);
    }

    private EnvironmentMutationResultDto compareAndSetEnvironmentVariablesInternal(
            Long userId,
            List<DeviceNodeDto> nodes,
            List<EnvironmentVariableUpdateRequestDto> updates) {
        validateEnvironmentUpdateRequests(updates);
        requireTotalCapacity(
                "environment variable updates", updates.size(), RequestLimits.MAX_ENVIRONMENT_VARIABLES);

        List<BoardEnvironmentVariableDto> expectedBaselines = new ArrayList<>();
        List<BoardEnvironmentVariableDto> patches = new ArrayList<>();
        for (EnvironmentVariableUpdateRequestDto update : updates) {
            String name = update.getName().trim();
            EnvironmentVariableUpdateRequestDto.ExpectedValue expected = update.getExpected();
            EnvironmentVariableUpdateRequestDto.DesiredPatch desired = update.getDesired();
            expectedBaselines.add(new BoardEnvironmentVariableDto(
                    name,
                    trimToNull(expected.getValue()),
                    normalizeSecurityLabel(expected.getTrust()),
                    normalizeSecurityLabel(expected.getPrivacy())));
            patches.add(new BoardEnvironmentVariableDto(
                    name,
                    trimToNull(desired.getValue()),
                    normalizeSecurityLabel(desired.getTrust()),
                    normalizeSecurityLabel(desired.getPrivacy())));
        }

        Map<String, DeviceManifest> templateManifests = loadTemplateManifestMap(userId);
        requireActiveEnvironmentDomainConsistency(nodes, templateManifests);
        Map<String, DeviceManifest.InternalVariable> required = collectRequiredEnvironmentVariables(
                nodes, templateManifests);
        List<BoardEnvironmentVariableDto> persistedBefore = getEnvironmentVariablesInternal(userId);
        List<BoardEnvironmentVariableDto> materializedBefore = mergeEnvironmentVariables(
                List.of(), persistedBefore, required);
        validateEnvironmentVariables(materializedBefore, required);

        Map<String, BoardEnvironmentVariableDto> currentByName = environmentDtoMap(materializedBefore);
        for (BoardEnvironmentVariableDto expected : expectedBaselines) {
            BoardEnvironmentVariableDto current = currentByName.get(expected.getName());
            if (!Objects.equals(expected, current)) {
                throw new EnvironmentVariableConflictException(expected.getName(), current);
            }
        }

        // A complete CAS comparison must finish for every item before any semantic validation
        // can reach persistence. This preserves a deterministic stale response for a removed
        // variable (currentVariable=null) while keeping the final write all-or-nothing.
        validateSubmittedEnvironmentNames(patches, required);
        validateEnvironmentVariables(expectedBaselines, required);
        return persistEnvironmentVariableCasPatches(
                userId, updates, persistedBefore, materializedBefore, required);
    }

    private void validateEnvironmentUpdateRequests(List<EnvironmentVariableUpdateRequestDto> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new BadRequestException("At least one environment variable update is required");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < updates.size(); index++) {
            EnvironmentVariableUpdateRequestDto update = updates.get(index);
            String prefix = "environmentUpdates[" + index + "]";
            if (update == null) {
                errors.put(prefix, "Environment variable update cannot be null");
                continue;
            }
            String name = trimToNull(update.getName());
            if (name == null) {
                errors.put(prefix + ".name", "Environment variable name is required");
            } else if (!names.add(name)) {
                errors.put(prefix + ".name", "Duplicate environment variable: " + name);
            }
            EnvironmentVariableUpdateRequestDto.ExpectedValue expected = update.getExpected();
            if (expected == null) {
                errors.put(prefix + ".expected", "Expected environment variable configuration is required");
            } else {
                if (!hasText(expected.getValue())) {
                    errors.put(prefix + ".expected.value", "Expected environment variable value is required");
                }
                if (!hasText(expected.getTrust())) {
                    errors.put(prefix + ".expected.trust", "Expected environment variable trust is required");
                }
                if (!hasText(expected.getPrivacy())) {
                    errors.put(prefix + ".expected.privacy", "Expected environment variable privacy is required");
                }
            }
            EnvironmentVariableUpdateRequestDto.DesiredPatch desired = update.getDesired();
            if (desired == null) {
                errors.put(prefix + ".desired", "Desired environment variable patch is required");
            } else {
                if (desired.getValue() != null && !hasText(desired.getValue())) {
                    errors.put(prefix + ".desired.value",
                            "Desired environment variable value cannot be blank when provided");
                }
                if (desired.getTrust() != null && !hasText(desired.getTrust())) {
                    errors.put(prefix + ".desired.trust",
                            "Desired environment variable trust cannot be blank when provided");
                }
                if (desired.getPrivacy() != null && !hasText(desired.getPrivacy())) {
                    errors.put(prefix + ".desired.privacy",
                            "Desired environment variable privacy cannot be blank when provided");
                }
                if (!desired.isFieldProvided()) {
                    errors.put(prefix + ".desired", "At least one desired environment variable field is required");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    /**
     * The environment pool after applying the patches — which is all its one caller reads.
     *
     * This used to build a whole {@code EnvironmentMutationResultDto}: an operation label, a change diff, a count,
     * and a per-patch {@code patchResults} list. `addNodes` (`:238`) takes `.getEnvironmentVariables()` and drops
     * the rest, so every device-creation request carrying environment patches paid for a
     * {@code describeEnvironmentVariablePatches} walk whose only destination was the garbage collector.
     *
     * Deliberately still does **not** call {@code recordEnvironmentUpdate}, unlike its CAS twin: `addNodes`
     * records the environment inside its own DEVICE journal entry, because one request is one user action. Adding
     * a second journal write here would give the user two undo steps for one edit.
     */
    private List<BoardEnvironmentVariableDto> persistEnvironmentVariablePatches(
            Long userId,
            List<BoardEnvironmentVariableDto> patches,
            List<BoardEnvironmentVariableDto> materializedBefore,
            Map<String, DeviceManifest.InternalVariable> required) {

        List<BoardEnvironmentVariableDto> merged = mergeEnvironmentVariablePatches(
                patches, materializedBefore, required);
        validateEnvironmentVariables(merged, required);
        return replaceEnvironmentVariablesInternal(userId, merged);
    }

    private EnvironmentMutationResultDto persistEnvironmentVariableCasPatches(
            Long userId,
            List<EnvironmentVariableUpdateRequestDto> updates,
            List<BoardEnvironmentVariableDto> persistedBefore,
            List<BoardEnvironmentVariableDto> materializedBefore,
            Map<String, DeviceManifest.InternalVariable> required) {

        List<BoardEnvironmentVariableDto> merged = mergeEnvironmentVariableCasPatches(
                updates, materializedBefore, required);
        validateEnvironmentVariables(merged, required);
        List<BoardEnvironmentVariableDto> saved = replaceEnvironmentVariablesInternal(userId, merged);
        List<EnvironmentVariableChangeDto> changes = diffEnvironmentVariables(materializedBefore, saved);
        BoardUndoAvailability availability = recordEnvironmentUpdate(userId, materializedBefore, saved);

        return EnvironmentMutationResultDto.builder()
                .operation(availability == null ? "unchanged" : "updated")
                .patchResults(describeEnvironmentVariableCasPatches(updates, materializedBefore, saved))
                .environmentVariables(saved)
                .environmentChanges(changes)
                .currentCount(saved.size())
                .canUndo(availability != null ? availability.canUndo() : null)
                .canRedo(availability != null ? availability.canRedo() : null)
                .build();
    }

    /** Records a direct Environment Pool edit only when its materialized semantic state changed. */
    private BoardUndoAvailability recordEnvironmentUpdate(
            Long userId,
            List<BoardEnvironmentVariableDto> before,
            List<BoardEnvironmentVariableDto> after) {
        if (environmentDtoMap(before).equals(environmentDtoMap(after))) {
            return null;
        }
        editJournal.record(userId, BoardEditEntityType.ENVIRONMENT, BoardEditOperation.UPDATE,
                ENVIRONMENT_ENTITY_KEY,
                new EnvironmentJournalSnapshot(before),
                new EnvironmentJournalSnapshot(after));
        return editJournal.availability(userId);
    }

    private void validateEnvironmentPatchFields(List<BoardEnvironmentVariableDto> patches) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < patches.size(); i++) {
            BoardEnvironmentVariableDto patch = patches.get(i);
            if (patch != null
                    && patch.getValue() == null
                    && patch.getTrust() == null
                    && patch.getPrivacy() == null) {
                errors.put("environmentVariables[" + i + "]",
                        "At least one of value, trust or privacy must be provided");
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void syncEnvironmentPoolForNodes(Long userId, List<DeviceNodeDto> nodes) {
        if (environmentRepo == null) {
            return;
        }
        refreshEnvironmentVariablesInternal(userId, nodes);
    }

    private Map<String, DeviceManifest.InternalVariable> collectRequiredEnvironmentVariables(
            List<DeviceNodeDto> nodes,
            Map<String, DeviceManifest> templateManifests) {
        Map<String, DeviceManifest.InternalVariable> required = new TreeMap<>();
        if (nodes == null || nodes.isEmpty() || templateManifests == null || templateManifests.isEmpty()) {
            return required;
        }
        for (DeviceNodeDto node : nodes) {
            if (node == null || !hasText(node.getTemplateName())) {
                continue;
            }
            DeviceManifest manifest = manifestForTemplateName(node.getTemplateName(), templateManifests);
            if (manifest == null) {
                continue;
            }
            if (manifest.getInternalVariables() != null) {
                for (DeviceManifest.InternalVariable variable : manifest.getInternalVariables()) {
                    if (variable == null || !hasText(variable.getName()) || !isEnvironmentVariable(variable)) {
                        continue;
                    }
                    required.putIfAbsent(variable.getName(), variable);
                }
            }
            if (manifest.getImpactedVariables() != null) {
                for (String impacted : manifest.getImpactedVariables()) {
                    DeviceManifest.InternalVariable definition =
                            EnvironmentDomainUtils.resolveImpactDomain(manifest, impacted);
                    if (definition != null) {
                        required.putIfAbsent(impacted, definition);
                    }
                }
            }
        }
        return required;
    }

    private List<BoardEnvironmentVariableDto> mergeEnvironmentVariables(
            List<BoardEnvironmentVariableDto> submitted,
            List<BoardEnvironmentVariableDto> existing,
            Map<String, DeviceManifest.InternalVariable> required) {
        Map<String, BoardEnvironmentVariableDto> existingByName = environmentDtoMap(existing);
        Map<String, BoardEnvironmentVariableDto> submittedByName = environmentDtoMap(submitted);
        List<BoardEnvironmentVariableDto> merged = new ArrayList<>();
        for (Map.Entry<String, DeviceManifest.InternalVariable> entry : required.entrySet()) {
            String name = entry.getKey();
            DeviceManifest.InternalVariable definition = entry.getValue();
            BoardEnvironmentVariableDto candidate = submittedByName.get(name);
            if (candidate == null) {
                candidate = existingByName.get(name);
            }
            String value = candidate != null ? candidate.getValue() : defaultEnvironmentValue(definition);
            if (!hasText(value) && hasText(defaultEnvironmentValue(definition))) {
                value = defaultEnvironmentValue(definition);
            }
            String trust = candidate != null ? candidate.getTrust() : definition.getTrust();
            String privacy = candidate != null ? candidate.getPrivacy() : definition.getPrivacy();
            merged.add(new BoardEnvironmentVariableDto(
                    name,
                    value,
                    normalizeTrustPrivacyOrDefault(trust, normalizeTrustPrivacyOrDefault(definition.getTrust(), "untrusted")),
                    normalizeTrustPrivacyOrDefault(privacy, normalizeTrustPrivacyOrDefault(definition.getPrivacy(), "public"))
            ));
        }
        return merged;
    }

    private List<BoardEnvironmentVariableDto> mergeEnvironmentVariablePatches(
            List<BoardEnvironmentVariableDto> patches,
            List<BoardEnvironmentVariableDto> existing,
            Map<String, DeviceManifest.InternalVariable> required) {
        Map<String, BoardEnvironmentVariableDto> existingByName = environmentDtoMap(existing);
        Map<String, BoardEnvironmentVariableDto> patchByName = environmentDtoMap(patches);
        List<BoardEnvironmentVariableDto> merged = new ArrayList<>();
        for (Map.Entry<String, DeviceManifest.InternalVariable> entry : required.entrySet()) {
            String name = entry.getKey();
            DeviceManifest.InternalVariable definition = entry.getValue();
            BoardEnvironmentVariableDto current = existingByName.get(name);
            if (current == null) {
                current = new BoardEnvironmentVariableDto(
                        name,
                        defaultEnvironmentValue(definition),
                        normalizeTrustPrivacyOrDefault(definition.getTrust(), "untrusted"),
                        normalizeTrustPrivacyOrDefault(definition.getPrivacy(), "public"));
            }
            BoardEnvironmentVariableDto patch = patchByName.get(name);
            merged.add(new BoardEnvironmentVariableDto(
                    name,
                    /*
                     * Normalised the same way as the CAS twin, which applied `trimToNull` and
                     * `normalizeSecurityLabel` while this path took the raw value. Nothing broke, because two
                     * later stages independently repaired it — `validateLiteralValues` compares trimmed and
                     * case-insensitively, and `environmentToEntity` normalises again before persisting — but
                     * `backend/CLAUDE.md` is explicit that admission and generation must normalise the same
                     * string the same way. Relying on a downstream repair means the next case-sensitive
                     * comparison added between the two paths reproduces only through `POST /api/board/nodes`.
                     */
                    patch != null && patch.getValue() != null
                            ? trimToNull(patch.getValue()) : current.getValue(),
                    patch != null && patch.getTrust() != null
                            ? normalizeSecurityLabel(patch.getTrust()) : current.getTrust(),
                    patch != null && patch.getPrivacy() != null
                            ? normalizeSecurityLabel(patch.getPrivacy()) : current.getPrivacy()));
        }
        return merged;
    }

    private List<BoardEnvironmentVariableDto> mergeEnvironmentVariableCasPatches(
            List<EnvironmentVariableUpdateRequestDto> updates,
            List<BoardEnvironmentVariableDto> existing,
            Map<String, DeviceManifest.InternalVariable> required) {
        Map<String, BoardEnvironmentVariableDto> existingByName = environmentDtoMap(existing);
        Map<String, EnvironmentVariableUpdateRequestDto.DesiredPatch> desiredByName = new LinkedHashMap<>();
        for (EnvironmentVariableUpdateRequestDto update : updates) {
            desiredByName.put(update.getName().trim(), update.getDesired());
        }

        List<BoardEnvironmentVariableDto> merged = new ArrayList<>();
        for (Map.Entry<String, DeviceManifest.InternalVariable> entry : required.entrySet()) {
            String name = entry.getKey();
            DeviceManifest.InternalVariable definition = entry.getValue();
            BoardEnvironmentVariableDto current = existingByName.get(name);
            if (current == null) {
                current = new BoardEnvironmentVariableDto(
                        name,
                        defaultEnvironmentValue(definition),
                        normalizeTrustPrivacyOrDefault(definition.getTrust(), "untrusted"),
                        normalizeTrustPrivacyOrDefault(definition.getPrivacy(), "public"));
            }
            EnvironmentVariableUpdateRequestDto.DesiredPatch desired = desiredByName.get(name);
            merged.add(new BoardEnvironmentVariableDto(
                    name,
                    desired != null && desired.getValue() != null
                            ? trimToNull(desired.getValue()) : current.getValue(),
                    desired != null && desired.getTrust() != null
                            ? normalizeSecurityLabel(desired.getTrust()) : current.getTrust(),
                    desired != null && desired.getPrivacy() != null
                            ? normalizeSecurityLabel(desired.getPrivacy()) : current.getPrivacy()));
        }
        return merged;
    }


    private List<EnvironmentVariablePatchResultDto> describeEnvironmentVariableCasPatches(
            List<EnvironmentVariableUpdateRequestDto> updates,
            List<BoardEnvironmentVariableDto> previous,
            List<BoardEnvironmentVariableDto> current) {
        Map<String, BoardEnvironmentVariableDto> previousByName = environmentDtoMap(previous);
        Map<String, BoardEnvironmentVariableDto> currentByName = environmentDtoMap(current);
        List<EnvironmentVariablePatchResultDto> results = new ArrayList<>();
        for (EnvironmentVariableUpdateRequestDto update : updates) {
            String name = update.getName().trim();
            BoardEnvironmentVariableDto before = previousByName.get(name);
            BoardEnvironmentVariableDto after = currentByName.get(name);
            EnvironmentVariableUpdateRequestDto.DesiredPatch desired = update.getDesired();
            List<String> suppliedFields = new ArrayList<>();
            if (desired.getValue() != null) suppliedFields.add("value");
            if (desired.getTrust() != null) suppliedFields.add("trust");
            if (desired.getPrivacy() != null) suppliedFields.add("privacy");
            List<String> changedFields = suppliedFields.stream()
                    .filter(field -> !Objects.equals(
                            environmentFieldValue(before, field), environmentFieldValue(after, field)))
                    .toList();
            List<String> preservedFields = List.of("value", "trust", "privacy").stream()
                    .filter(field -> !suppliedFields.contains(field))
                    .toList();
            results.add(EnvironmentVariablePatchResultDto.builder()
                    .name(name)
                    .suppliedFields(List.copyOf(suppliedFields))
                    .changedFields(changedFields)
                    .preservedFields(preservedFields)
                    .previousValue(before)
                    .currentValue(after)
                    .build());
        }
        return List.copyOf(results);
    }


    private String environmentFieldValue(BoardEnvironmentVariableDto variable, String field) {
        if (variable == null) return null;
        return switch (field) {
            case "value" -> variable.getValue();
            case "trust" -> variable.getTrust();
            case "privacy" -> variable.getPrivacy();
            default -> null;
        };
    }

    private Map<String, BoardEnvironmentVariableDto> environmentDtoMap(List<BoardEnvironmentVariableDto> values) {
        Map<String, BoardEnvironmentVariableDto> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (BoardEnvironmentVariableDto value : values) {
            if (value == null || !hasText(value.getName())) {
                continue;
            }
            String name = value.getName().trim();
            result.putIfAbsent(name, new BoardEnvironmentVariableDto(
                    name,
                    trimToNull(value.getValue()),
                    trimToNull(value.getTrust()),
                    trimToNull(value.getPrivacy())
            ));
        }
        return result;
    }

    private void validateEnvironmentVariables(List<BoardEnvironmentVariableDto> variables,
                                              Map<String, DeviceManifest.InternalVariable> required) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < (variables == null ? 0 : variables.size()); i++) {
            BoardEnvironmentVariableDto variable = variables.get(i);
            String prefix = "environmentVariables[" + i + "]";
            if (variable == null) {
                errors.putIfAbsent(prefix, "Environment variable item cannot be null");
                continue;
            }
            String name = trimToNull(variable.getName());
            if (name == null) {
                errors.putIfAbsent(prefix + ".name", "Environment variable name is required");
                continue;
            }
            if (!seen.add(name)) {
                errors.putIfAbsent(prefix + ".name", "Duplicate environment variable: " + name);
            }
            DeviceManifest.InternalVariable definition = required.get(name);
            if (definition == null) {
                errors.putIfAbsent(prefix + ".name", "Environment variable is not required by the current board: " + name);
                continue;
            }
            validateNodeTrust(errors, prefix + ".trust", variable.getTrust());
            validatePrivacyValues(errors, prefix + ".privacy", "=", variable.getPrivacy());
            String value = trimToNull(variable.getValue());
            if (hasEnvironmentValueDomain(definition) && value == null) {
                errors.putIfAbsent(prefix + ".value", "Environment variable value is required for " + name);
            } else if (value != null) {
                validateVariableValues(errors, prefix + ".value", definition, "=", value);
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void validateSubmittedEnvironmentNames(List<BoardEnvironmentVariableDto> submitted,
                                                   Map<String, DeviceManifest.InternalVariable> required) {
        if (submitted == null || submitted.isEmpty()) {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < submitted.size(); i++) {
            BoardEnvironmentVariableDto variable = submitted.get(i);
            String prefix = "environmentVariables[" + i + "]";
            if (variable == null) {
                errors.putIfAbsent(prefix, "Environment variable item cannot be null");
                continue;
            }
            String name = trimToNull(variable.getName());
            if (name == null) {
                errors.putIfAbsent(prefix + ".name", "Environment variable name is required");
                continue;
            }
            if (!seen.add(name)) {
                errors.putIfAbsent(prefix + ".name", "Duplicate environment variable: " + name);
            }
            if (!required.containsKey(name)) {
                errors.putIfAbsent(prefix + ".name",
                        "Environment variable is not required by the current board: " + name);
                continue;
            }
            validateNodeTrust(errors, prefix + ".trust", variable.getTrust());
            if (variable.getPrivacy() != null) {
                validatePrivacyValues(errors, prefix + ".privacy", "=", variable.getPrivacy());
            }
            if (variable.getValue() != null && !hasText(variable.getValue())) {
                errors.putIfAbsent(prefix + ".value",
                        "Environment variable value cannot be blank when provided");
            } else if (hasText(variable.getValue())) {
                validateVariableValues(errors, prefix + ".value", required.get(name), "=", variable.getValue());
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void validateCompleteEnvironmentSubmission(
            List<BoardEnvironmentVariableDto> submitted,
            Map<String, DeviceManifest.InternalVariable> required) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> provided = new HashSet<>();
        List<BoardEnvironmentVariableDto> values = submitted == null ? List.of() : submitted;
        for (int index = 0; index < values.size(); index++) {
            BoardEnvironmentVariableDto variable = values.get(index);
            if (variable == null) {
                continue;
            }
            String name = trimToNull(variable.getName());
            if (name != null) {
                provided.add(name);
            }
            String prefix = "environmentVariables[" + index + "]";
            if (!hasText(variable.getValue())) {
                errors.putIfAbsent(prefix + ".value",
                        "Scene environment variable value must be explicit and non-blank");
            }
            if (!hasText(variable.getTrust())) {
                errors.putIfAbsent(prefix + ".trust",
                        "Scene environment variable trust must be explicit (trusted or untrusted)");
            }
            if (!hasText(variable.getPrivacy())) {
                errors.putIfAbsent(prefix + ".privacy",
                        "Scene environment variable privacy must be explicit (public or private)");
            }
        }
        Set<String> missing = new TreeSet<>(required.keySet());
        missing.removeAll(provided);
        if (!missing.isEmpty()) {
            errors.put("environmentVariables",
                    "Scene is missing required environment variables: " + String.join(", ", missing));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private boolean hasEnvironmentValueDomain(DeviceManifest.InternalVariable variable) {
        if (variable == null) {
            return false;
        }
        return (variable.getValues() != null && !variable.getValues().isEmpty())
                || (variable.getLowerBound() != null && variable.getUpperBound() != null);
    }

    private boolean isEnvironmentVariable(DeviceManifest.InternalVariable variable) {
        return variable != null && !Boolean.TRUE.equals(variable.getIsInside());
    }

    private String defaultEnvironmentValue(DeviceManifest.InternalVariable variable) {
        if (variable == null) {
            return null;
        }
        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            return trimToNull(variable.getValues().get(0));
        }
        if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
            return String.valueOf(variable.getLowerBound());
        }
        return null;
    }

    private String normalizeTrustPrivacyOrDefault(String value, String fallback) {
        String normalized = DeviceSmvDataFactory.normalizeTrustPrivacy(value);
        if (normalized != null) {
            return normalized;
        }
        return fallback;
    }

    private void validateBoardReferences(Long userId,
                                         List<DeviceNodeDto> nodes,
                                         List<RuleDto> rules,
                                         List<SpecificationDto> specs) {
        validateBoardReferences(userId, nodes, rules, specs, loadTemplateManifestMap(userId));
    }

    private void validateBoardReferences(Long userId,
                                         List<DeviceNodeDto> nodes,
                                         List<RuleDto> rules,
                                         List<SpecificationDto> specs,
                                         Map<String, DeviceManifest> templateManifests) {
        Set<String> nodeIds = nodeIds(nodes);
        Map<String, DeviceNodeDto> nodesById = nodesById(nodes);
        Map<String, String> errors = new LinkedHashMap<>();
        validateNodeLayouts(nodes, errors);
        validateNodeTemplateRefs(nodes, errors, templateManifests);
        validateNodeRuntimeSemantics(errors, nodes, templateManifests);
        validateActiveEnvironmentDomainConsistency(errors, nodes, templateManifests);
        validateActiveDiscreteWriterAgreement(errors, nodes, templateManifests);
        validateGeneratedMainNamespace(errors, nodes, rules, templateManifests);

        if (rules != null) {
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                RuleDto rule = rules.get(ruleIndex);
                if (rule == null) {
                    continue;
                }
                RuleDto.Command command = rule.getCommand();
                if (command != null) {
                    requireBoardNodeRef(errors, "rules[" + ruleIndex + "].command.deviceName",
                            command.getDeviceName(), nodeIds, "Unknown command device");
                    if (hasText(command.getContentDevice())) {
                        requireBoardNodeRef(errors, "rules[" + ruleIndex + "].command.contentDevice",
                                command.getContentDevice(), nodeIds, "Unknown content device");
                    }
                }
                List<RuleDto.Condition> conditions = rule.getConditions();
                if (conditions == null) {
                    continue;
                }
                for (int conditionIndex = 0; conditionIndex < conditions.size(); conditionIndex++) {
                    RuleDto.Condition condition = conditions.get(conditionIndex);
                    if (condition == null) {
                        continue;
                    }
                    requireBoardNodeRef(errors,
                            "rules[" + ruleIndex + "].conditions[" + conditionIndex + "].deviceName",
                            condition.getDeviceName(), nodeIds, "Unknown condition device");
                }
                validateRuleSemantics(errors, "rules[" + ruleIndex + "]",
                        rule, nodesById, templateManifests);
            }
        }

        if (specs != null) {
            for (int specIndex = 0; specIndex < specs.size(); specIndex++) {
                SpecificationDto spec = specs.get(specIndex);
                if (spec == null) {
                    continue;
                }
                validateSpecTemplateShape(errors, "specs[" + specIndex + "]", spec);
                validateSpecConditionRefs(errors, "specs[" + specIndex + "].aConditions",
                        spec.getAConditions(), nodeIds, nodesById, templateManifests);
                validateSpecConditionRefs(errors, "specs[" + specIndex + "].ifConditions",
                        spec.getIfConditions(), nodeIds, nodesById, templateManifests);
                validateSpecConditionRefs(errors, "specs[" + specIndex + "].thenConditions",
                        spec.getThenConditions(), nodeIds, nodesById, templateManifests);
                validateSafetySourceCapabilities(errors, "specs[" + specIndex + "]",
                        spec, nodesById, templateManifests);
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void validateNodeLayouts(List<DeviceNodeDto> nodes, Map<String, String> errors) {
        if (nodes == null) {
            return;
        }
        for (int index = 0; index < nodes.size(); index++) {
            DeviceNodeDto node = nodes.get(index);
            if (node == null) {
                continue;
            }
            String prefix = "nodes[" + index + "]";
            DeviceNodeDto.Position position = node.getPosition();
            Double x = position != null ? position.getX() : null;
            Double y = position != null ? position.getY() : null;
            if (x == null || !Double.isFinite(x) || Math.abs(x) > DeviceLayoutDto.MAX_ABS_POSITION) {
                errors.putIfAbsent(prefix + ".position.x",
                        "X coordinate must be finite and within -1000000..1000000");
            }
            if (y == null || !Double.isFinite(y) || Math.abs(y) > DeviceLayoutDto.MAX_ABS_POSITION) {
                errors.putIfAbsent(prefix + ".position.y",
                        "Y coordinate must be finite and within -1000000..1000000");
            }
            if (node.getWidth() == null || node.getWidth() < DeviceLayoutDto.MIN_WIDTH
                    || node.getWidth() > DeviceLayoutDto.MAX_WIDTH) {
                errors.putIfAbsent(prefix + ".width", "Width must be within 80..2000");
            }
            if (node.getHeight() == null || node.getHeight() < DeviceLayoutDto.MIN_HEIGHT
                    || node.getHeight() > DeviceLayoutDto.MAX_HEIGHT) {
                errors.putIfAbsent(prefix + ".height", "Height must be within 60..2000");
            }
        }
    }

    private List<DeviceNodeDto> canonicalizeNodes(Long userId, List<DeviceNodeDto> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return nodes;
        }
        return canonicalizeNodes(nodes, loadTemplateManifestMap(userId));
    }

    private List<DeviceNodeDto> canonicalizeNodes(List<DeviceNodeDto> nodes, Map<String, DeviceManifest> templates) {
        for (DeviceNodeDto node : nodes) {
            if (node == null) {
                continue;
            }
            DeviceManifest manifest = manifestForTemplateName(node.getTemplateName(), templates);
            if (hasText(node.getTemplateName())) {
                String raw = node.getTemplateName().trim();
                node.setTemplateName(manifest != null ? manifest.getName().trim() : raw);
            }
            canonicalizeRuntime(node, manifest);
        }
        return nodes;
    }

    private void validateSpecTemplateShape(Map<String, String> errors,
                                           String field,
                                           SpecificationDto spec) {
        String templateId = spec.getTemplateId();
        if (!hasText(templateId) || !templateId.matches("^[1-7]$")) {
            return;
        }
        int aCount = sizeOf(spec.getAConditions());
        int ifCount = sizeOf(spec.getIfConditions());
        int thenCount = sizeOf(spec.getThenConditions());
        if (Set.of("1", "2", "3", "7").contains(templateId)) {
            if (aCount == 0) {
                errors.putIfAbsent(field + ".aConditions",
                        "Template " + templateId + " requires at least one A condition");
            }
            if (ifCount > 0 || thenCount > 0) {
                errors.putIfAbsent(field + ".templateId",
                        "Template " + templateId + " uses A conditions only");
            }
            if ("7".equals(templateId)) {
                validateUntrustedSourceSafetyConditions(errors, field, spec.getAConditions());
            }
            return;
        }
        if (aCount > 0) {
            errors.putIfAbsent(field + ".aConditions",
                    "Template " + templateId + " uses IF/THEN conditions only");
        }
        if (ifCount == 0 || thenCount == 0) {
            errors.putIfAbsent(field + ".ifConditions",
                    "Template " + templateId + " requires both IF and THEN conditions");
        }
    }

    private int sizeOf(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private void validateUntrustedSourceSafetyConditions(Map<String, String> errors,
                                                          String field,
                                                          List<SpecConditionDto> conditions) {
        if (conditions == null) {
            return;
        }
        for (int index = 0; index < conditions.size(); index++) {
            SpecConditionDto condition = conditions.get(index);
            if (condition == null) {
                continue;
            }
            String prefix = field + ".aConditions[" + index + "]";
            String targetType = condition.getTargetType() == null
                    ? "" : condition.getTargetType().trim().toLowerCase(Locale.ROOT);
            String relation = SmvRelationUtils.normalizeRelation(condition.getRelation());
            if ("trust".equals(targetType) || "privacy".equals(targetType)) {
                errors.putIfAbsent(prefix + ".targetType",
                        "Template 7 associates the MEDIC control-source label automatically; use template 3 for explicit trust/privacy predicates");
            } else if (("state".equals(targetType) || "mode".equals(targetType))
                    && !"=".equals(relation)) {
                errors.putIfAbsent(prefix + ".relation",
                        "Template 7 state and mode conditions require '=' so the protected event has one source label");
            } else if ("api".equals(targetType)
                    && (!"=".equals(relation) || !"TRUE".equalsIgnoreCase(condition.getValue()))) {
                errors.putIfAbsent(prefix + ".value",
                        "Template 7 API conditions require '= TRUE'");
            }
        }
    }

    private void validateSafetySourceCapabilities(Map<String, String> errors,
                                                  String field,
                                                  SpecificationDto spec,
                                                  Map<String, DeviceNodeDto> nodesById,
                                                  Map<String, DeviceManifest> templateManifests) {
        if (spec == null || !"7".equals(spec.getTemplateId()) || spec.getAConditions() == null) {
            return;
        }
        for (int index = 0; index < spec.getAConditions().size(); index++) {
            SpecConditionDto condition = spec.getAConditions().get(index);
            if (condition == null || !"api".equals(normalizeSpecTargetType(condition.getTargetType()))) {
                continue;
            }
            DeviceManifest manifest = manifestForDeviceRef(condition.getDeviceId(), nodesById, templateManifests);
            DeviceManifest.API api = signalApi(manifest, condition.getKey());
            if (api != null && !hasText(api.getEndState())) {
                errors.putIfAbsent(field + ".aConditions[" + index + "].key",
                        "Template 7 cannot derive a MEDIC control-source label for API '"
                                + condition.getKey() + "' because its device template has no EndState");
            }
        }
    }

    private DeviceManifest.API signalApi(DeviceManifest manifest, String apiName) {
        if (manifest == null || manifest.getApis() == null || !hasText(apiName)) return null;
        return manifest.getApis().stream()
                .filter(api -> api != null && Boolean.TRUE.equals(api.getSignal())
                        && apiName.trim().equals(api.getName()))
                .findFirst()
                .orElse(null);
    }

    private void validateSpecConditionRefs(Map<String, String> errors,
                                           String field,
                                           List<SpecConditionDto> conditions,
                                           Set<String> nodeIds,
                                           Map<String, DeviceNodeDto> nodesById,
                                           Map<String, DeviceManifest> templateManifests) {
        if (conditions == null) {
            return;
        }
        for (int i = 0; i < conditions.size(); i++) {
            SpecConditionDto condition = conditions.get(i);
            if (condition == null) {
                continue;
            }
            requireBoardNodeRef(errors, field + "[" + i + "].deviceId",
                    condition.getDeviceId(), nodeIds, "Unknown spec condition device");
            validateSpecConditionSemanticsForAnyOrigin(errors, field + "[" + i + "]",
                    condition, nodesById, templateManifests);
        }
    }

    private void validateNodeTemplateRefs(List<DeviceNodeDto> nodes,
                                          Map<String, String> errors,
                                          Map<String, DeviceManifest> templateManifests) {
        if (nodes == null || nodes.isEmpty() || deviceTemplateRepo == null) {
            return;
        }

        Map<Integer, String> nodeTemplateRefs = new LinkedHashMap<>();
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            DeviceNodeDto node = nodes.get(nodeIndex);
            if (node == null || !hasText(node.getTemplateName())) {
                continue;
            }
            String templateName = node.getTemplateName().trim();
            nodeTemplateRefs.put(nodeIndex, templateName);
        }
        if (nodeTemplateRefs.isEmpty()) {
            return;
        }

        for (Map.Entry<Integer, String> entry : nodeTemplateRefs.entrySet()) {
            int nodeIndex = entry.getKey();
            String templateName = entry.getValue();
            if (templateManifests == null
                    || !templateManifests.containsKey(templateName.toLowerCase(Locale.ROOT))) {
                errors.putIfAbsent("nodes[" + nodeIndex + "].templateName",
                        "Unknown device template: " + templateName);
            }
        }
    }

    private void validateNodeRuntimeSemantics(Map<String, String> errors,
                                               List<DeviceNodeDto> nodes,
                                               Map<String, DeviceManifest> templateManifests) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        Set<String> seenIds = new HashSet<>();
        Set<String> seenModelIds = new HashSet<>();
        Set<String> seenLabels = new HashSet<>();
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            DeviceNodeDto node = nodes.get(nodeIndex);
            if (node == null) {
                continue;
            }
            String field = "nodes[" + nodeIndex + "]";

            String nodeId = trimToNull(node.getId());
            if (nodeId != null && !node.getId().matches(DeviceNodeDto.ID_PATTERN)) {
                errors.putIfAbsent(field + ".id", "Node ID must not have leading or trailing whitespace");
            }
            if (nodeId != null && !seenIds.add(nodeId)) {
                errors.putIfAbsent(field + ".id", "Duplicate device ID: " + nodeId);
            }
            if (nodeId != null) {
                String modelId = DeviceNameNormalizer.normalize(nodeId);
                if (!seenModelIds.add(modelId)) {
                    errors.putIfAbsent(field + ".id",
                            "Device ID collides after NuSMV normalization: " + nodeId + " -> " + modelId);
                }
            }

            String label = trimToNull(node.getLabel());
            // Length belongs here rather than only on the DTO. `DeviceNodeDto.label` carries
            // `@Size(max = 255)`, which Spring applies on the `@Valid` REST path — but the AI tools call
            // this service directly from a chat turn, so no `Validator` ever runs on their input (no
            // service class carries `@Validated`, and `AbstractAiTool`'s field helpers only trim). The
            // column is `length = 255`, so an over-long label reached the insert and came back as a
            // `DataIntegrityViolationException` → generic 500 "please retry", which invites the model to
            // repeat the identical failing call. Reject it here, where the message can name the field.
            if (label != null && label.length() > RequestLimits.MAX_DEVICE_LABEL_LENGTH) {
                errors.putIfAbsent(field + ".label",
                        "Device label must be at most " + RequestLimits.MAX_DEVICE_LABEL_LENGTH
                                + " characters, but was " + label.length() + ".");
            }
            if (label != null && !seenLabels.add(label.toLowerCase(Locale.ROOT))) {
                errors.putIfAbsent(field + ".label", "Duplicate device label: " + label);
            }

            DeviceManifest manifest = manifestForTemplateName(node.getTemplateName(), templateManifests);
            if (manifest == null) {
                continue;
            }

            validateImpactedEnvironmentDomains(errors, field, node.getTemplateName(), manifest);
            boolean hasModeStateMachine = hasModeStateMachine(manifest);
            validateNodeState(errors, field, node, manifest);
            validateStatelessNodeFields(errors, field, node, hasModeStateMachine);
            if (hasModeStateMachine) {
                validateNodeTrust(errors, field + ".currentStateTrust", node.getCurrentStateTrust());
                if (node.getCurrentStatePrivacy() != null) {
                    validatePrivacyValues(errors, field + ".currentStatePrivacy", "=", node.getCurrentStatePrivacy());
                }
            }
            validateNodeVariables(errors, field + ".variables", node.getVariables(), manifest);
            validateNodePrivacies(errors, field + ".privacies", node.getPrivacies(), manifest);
        }
    }

    private void validateGeneratedMainNamespace(Map<String, String> errors,
                                                List<DeviceNodeDto> nodes,
                                                List<RuleDto> rules,
                                                Map<String, DeviceManifest> templateManifests) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        // The fix generator's reserved prefixes, checked here as well as at request time.
        // `SmvConstants.FIX_GENERATED_NAME_PREFIXES` had exactly one consumer,
        // `NusmvRequestValidator.rejectFixGeneratedPrefix`, which rejects by *prefix* on every verify and
        // simulate request. This pass registers only *concrete* generated names — `lambda_r{i}_c{j}` and
        // `param_r{i}_c{j}` for the rules and conditions that exist right now — and never looked at
        // `condition_value_` at all. So a device named `condition_value_r0_c1` (or `param_`/`lambda_`
        // with any index the current board does not happen to produce) was saved and then rejected on
        // every verification until renamed.
        //
        // Cheaper to refuse the name once, at the boundary that owns it, than to persist a board no
        // verification can run against.
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            DeviceNodeDto node = nodes.get(nodeIndex);
            if (node == null || !hasText(node.getId())) continue;
            String normalized = node.getId().trim().toLowerCase(Locale.ROOT);
            for (String prefix : SmvConstants.FIX_GENERATED_NAME_PREFIXES) {
                if (normalized.startsWith(prefix)) {
                    // The remedy must name something the user can actually do. A device *id* is
                    // immutable — `renameNode` changes only the label — so the request-time wording
                    // "Rename the device" sends a user into a loop: renaming the label re-runs this
                    // check, which fails on the id again. Ids are caller-supplied only on scene import
                    // (`createNode` mints `device_<uuid>`), so that is the one place to change it.
                    errors.putIfAbsent("nodes[" + nodeIndex + "].id",
                            "Device id '" + node.getId() + "' starts with '" + prefix
                                    + "', which the automatic-fix generator reserves for its own "
                                    + "variables. A device id cannot be changed after creation: use a "
                                    + "different id in the imported scene, or delete this device and "
                                    + "add it again.");
                    break;
                }
            }
        }

        Map<String, String> generated = new LinkedHashMap<>();
        Map<String, DeviceManifest.InternalVariable> environment =
                collectRequiredEnvironmentVariables(nodes, templateManifests);
        for (String name : environment.keySet()) {
            registerGeneratedMainIdentifier(generated, "a_" + name,
                    "shared environment value '" + name + "'");
        }

        registerGeneratedMainIdentifier(generated, SmvConstants.NUSMV_COMPROMISED_POINT_COUNT,
                "attack-budget accounting");
        if (rules != null) {
            Map<String, DeviceNodeDto> nodeById = nodesById(nodes);
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                RuleDto rule = rules.get(ruleIndex);
                String ruleLabel = ruleDisplayName(rule, ruleIndex);
                registerGeneratedMainIdentifier(generated,
                        SmvConstants.RULE_EXECUTION_PROBE_PREFIX + ruleIndex,
                        "rule playback tracking for '" + ruleLabel + "'");
                registerGeneratedMainIdentifier(generated,
                        SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX + ruleIndex,
                        "attack analysis for automation rule '" + ruleLabel + "'");

                List<RuleDto.Condition> conditions = rule == null ? null : rule.getConditions();
                if (conditions == null) {
                    continue;
                }
                for (int conditionIndex = 0; conditionIndex < conditions.size(); conditionIndex++) {
                    RuleDto.Condition condition = conditions.get(conditionIndex);
                    if (condition == null) {
                        continue;
                    }
                    registerGeneratedMainIdentifier(generated,
                            "lambda_r" + ruleIndex + "_c" + conditionIndex,
                            "automatic-fix condition selection for '" + ruleLabel + "'");
                    if (isParameterizableBoardCondition(condition, nodeById, templateManifests)) {
                        registerGeneratedMainIdentifier(generated,
                                "param_r" + ruleIndex + "_c" + conditionIndex,
                                "automatic-fix parameter adjustment for '" + ruleLabel + "'");
                    }
                }
            }
        }

        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            DeviceNodeDto node = nodes.get(nodeIndex);
            if (node == null || !hasText(node.getId())) {
                continue;
            }
            String modelId = DeviceNameNormalizer.normalize(node.getId().trim());
            String conflict = generated.get(modelId.toLowerCase(Locale.ROOT));
            if (conflict == null) {
                continue;
            }
            errors.putIfAbsent("nodes[" + nodeIndex + "].id",
                    "Device '" + deviceDisplayName(node) + "' uses a scene reference that conflicts with "
                            + conflict + ". Change this device's devices[].id and matching rule/spec references "
                            + "in the scene JSON, or recreate the device through the UI. Its display name may stay unchanged.");
        }
    }

    private void registerGeneratedMainIdentifier(Map<String, String> generated,
                                                 String identifier,
                                                 String description) {
        if (hasText(identifier)) {
            generated.putIfAbsent(identifier.trim().toLowerCase(Locale.ROOT), description);
        }
    }

    private String ruleDisplayName(RuleDto rule, int ruleIndex) {
        return rule != null && hasText(rule.getRuleString())
                ? rule.getRuleString().trim()
                : "rule " + (ruleIndex + 1);
    }

    private boolean isParameterizableBoardCondition(RuleDto.Condition condition,
                                                     Map<String, DeviceNodeDto> nodeById,
                                                     Map<String, DeviceManifest> templateManifests) {
        String relation = SmvRelationUtils.normalizeRelation(condition.getRelation());
        if (relation == null
                || !Set.of(">", ">=", "<", "<=").contains(relation)
                || !hasText(condition.getValue())) {
            return false;
        }
        try {
            Double.parseDouble(condition.getValue().trim());
        } catch (NumberFormatException e) {
            return false;
        }
        DeviceNodeDto node = nodeById.get(condition.getDeviceName());
        if (node == null) {
            return false;
        }
        DeviceManifest manifest = manifestForTemplateName(node.getTemplateName(), templateManifests);
        if (manifest == null || !hasText(condition.getAttribute())) {
            return false;
        }
        DeviceManifest.InternalVariable variable = internalVariable(manifest, condition.getAttribute());
        if (variable == null) {
            variable = EnvironmentDomainUtils.resolveImpactDomain(manifest, condition.getAttribute());
        }
        return variable != null && variable.getLowerBound() != null && variable.getUpperBound() != null;
    }

    private void validateImpactedEnvironmentDomains(Map<String, String> errors,
                                                    String field,
                                                    String templateName,
                                                    DeviceManifest manifest) {
        if (manifest == null || manifest.getImpactedVariables() == null || manifest.getImpactedVariables().isEmpty()) {
            return;
        }
        for (String impacted : manifest.getImpactedVariables()) {
            if (!hasText(impacted)) {
                continue;
            }
            DeviceManifest.InternalVariable sameName = internalVariable(manifest, impacted);
            if (sameName != null && !isEnvironmentVariable(sameName)) {
                errors.putIfAbsent(field + ".templateName",
                        "Template '" + templateName + "' uses ImpactedVariable '" + impacted
                                + "' with a local InternalVariable of the same name. "
                                + "Use WorkingStates.Dynamics for local device state and reserve "
                                + "ImpactedVariables for shared environment variables.");
                continue;
            }
            if (EnvironmentDomainUtils.resolveImpactDomain(manifest, impacted) == null) {
                errors.putIfAbsent(field + ".templateName",
                        "Template '" + templateName + "' impacts environment variable '" + impacted
                                + "', but its own manifest does not define that domain. Declare an InternalVariable named '"
                                + impacted + "' with IsInside=false carrying its type and domain, and Reads=false if this "
                                + "device only affects the value without observing it.");
            }
        }
    }

    private void validateActiveEnvironmentDomainConsistency(Map<String, String> errors,
                                                            List<DeviceNodeDto> nodes,
                                                            Map<String, DeviceManifest> templateManifests) {
        if (nodes == null || templateManifests == null) {
            return;
        }
        Map<String, EnvironmentDomainSource> seen = new LinkedHashMap<>();
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            DeviceNodeDto node = nodes.get(nodeIndex);
            if (node == null) continue;
            DeviceManifest manifest = manifestForTemplateName(node.getTemplateName(), templateManifests);
            if (manifest == null) continue;
            Set<String> readableNames = new HashSet<>();
            if (manifest.getInternalVariables() != null) {
                for (DeviceManifest.InternalVariable variable : manifest.getInternalVariables()) {
                    if (variable == null || !hasText(variable.getName()) || !isEnvironmentVariable(variable)) continue;
                    readableNames.add(variable.getName());
                    registerActiveEnvironmentDomain(errors, seen, nodeIndex, node, variable.getName(), variable);
                }
            }
            if (manifest.getImpactedVariables() != null) {
                for (String impacted : manifest.getImpactedVariables()) {
                    if (!hasText(impacted) || readableNames.contains(impacted)) continue;
                    DeviceManifest.InternalVariable definition =
                            EnvironmentDomainUtils.resolveImpactDomain(manifest, impacted);
                    if (definition != null) {
                        registerActiveEnvironmentDomain(errors, seen, nodeIndex, node, impacted, definition);
                    }
                }
            }
        }
    }

    /**
     * Two devices must not declare different values for one shared discrete value.
     *
     * <p>`SmvModelValidator.validateDiscreteWriterAgreement` already refuses this at generation time,
     * because there is no defined way to combine two different values and the verdict otherwise
     * depended on device iteration order. But nothing checked it at admission: the sibling
     * domain-consistency pass above compares *declarations* (type, range, enum values,
     * `NaturalChangeRate`, default labels) and never reads `WorkingStates[].Dynamics[].Value`.
     *
     * <p>So each template passed `addDeviceTemplate` on its own — the template gate is inherently
     * single-manifest and cannot see a pair — both devices persisted through `addNodes` and through
     * scene import, and every verification afterwards returned **HTTP 500**
     * (`SmvGenerationException` maps to `INTERNAL_SERVER_ERROR`) until one device was deleted.
     * Measured: two templates differing only in `Dynamics.Value` (`good` vs `bad`) over the same
     * `{good, bad}` domain were both admitted, and generation then threw
     * `Env variable 'airQuality' conflict: … device 'writer_good_1' sets it to 'good' while device
     * 'writer_bad_1' sets it to 'bad'`.
     *
     * <p>Kept deliberately narrow, matching the generator: discrete domains only (numeric effects sum
     * and are combined, so disagreement there is not a conflict), and a device never conflicts with
     * itself.
     */
    private void validateActiveDiscreteWriterAgreement(Map<String, String> errors,
                                                       List<DeviceNodeDto> nodes,
                                                       Map<String, DeviceManifest> templateManifests) {
        if (nodes == null || templateManifests == null) {
            return;
        }
        // shared value -> declared value -> the device that declared it
        Map<String, Map<String, String>> assignments = new LinkedHashMap<>();
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            DeviceNodeDto node = nodes.get(nodeIndex);
            if (node == null) continue;
            DeviceManifest manifest = manifestForTemplateName(node.getTemplateName(), templateManifests);
            if (manifest == null || manifest.getWorkingStates() == null) continue;
            String deviceLabel = deviceDisplayName(node);
            for (DeviceManifest.WorkingState state : manifest.getWorkingStates()) {
                if (state == null || state.getDynamics() == null) continue;
                for (DeviceManifest.Dynamic dynamic : state.getDynamics()) {
                    if (dynamic == null || !hasText(dynamic.getVariableName())
                            || !hasText(dynamic.getValue())) {
                        continue;
                    }
                    String name = dynamic.getVariableName().trim();
                    DeviceManifest.InternalVariable domain =
                            EnvironmentDomainUtils.resolveImpactDomain(manifest, name);
                    if (domain == null || domain.getValues() == null || domain.getValues().isEmpty()) {
                        // Numeric or device-local: not this check's business.
                        continue;
                    }
                    String value = dynamic.getValue().replace(" ", "");
                    Map<String, String> byValue = assignments.computeIfAbsent(
                            name.toLowerCase(Locale.ROOT), key -> new LinkedHashMap<>());
                    String owner = byValue.putIfAbsent(value, deviceLabel);
                    if (owner != null && owner.equals(deviceLabel)) {
                        continue;
                    }
                    for (Map.Entry<String, String> other : byValue.entrySet()) {
                        if (other.getKey().equals(value) || other.getValue().equals(deviceLabel)) {
                            continue;
                        }
                        errors.putIfAbsent("nodes[" + nodeIndex + "].templateName",
                                "Shared environment variable '" + name + "' would receive conflicting "
                                        + "declared effects: device '" + other.getValue() + "' sets it to '"
                                        + other.getKey() + "' while device '" + deviceLabel + "' sets it to '"
                                        + value + "'. There is no defined way to combine two different "
                                        + "values, so change one template declaration or keep only one of "
                                        + "these devices.");
                        return;
                    }
                }
            }
        }
    }

    private void requireActiveEnvironmentDomainConsistency(
            List<DeviceNodeDto> nodes,
            Map<String, DeviceManifest> templateManifests) {
        Map<String, String> errors = new LinkedHashMap<>();
        validateActiveEnvironmentDomainConsistency(errors, nodes, templateManifests);
        // Deliberately NOT the pair-wise discrete-writer check. It lives only in
        // `validateBoardReferences`, which every write route reaches — including scene replacement,
        // via `saveBoardBatch` at the `validateBoardReferences(userId, nextNodes, nextRules, nextSpecs)`
        // call. An earlier version of this method also ran it, justified by a claim that scene
        // replacement reached only here; that claim was false, and it named `applySceneReplacement`,
        // a method that does not exist.
        //
        // The redundancy was not harmless. Of this method's five call sites, one is inside
        // `projectEnvironmentVariablesForNodes`, reached from `getEnvironmentVariables` — i.e.
        // `GET /api/board/environment`. A per-declaration consistency check is idempotent and safe to
        // repeat on a read; a *pair-wise* conflict check is not, because a board persisted before that
        // check existed then fails a plain read of its own environment pool. Keep pair-wise invariants
        // on the write path only.
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void registerActiveEnvironmentDomain(Map<String, String> errors,
                                                 Map<String, EnvironmentDomainSource> seen,
                                                 int nodeIndex,
                                                 DeviceNodeDto node,
                                                 String name,
                                                 DeviceManifest.InternalVariable definition) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        EnvironmentDomainSource current = new EnvironmentDomainSource(
                name.trim(), deviceDisplayName(node), node.getTemplateName(), definition);
        EnvironmentDomainSource previous = seen.putIfAbsent(normalized, current);
        if (previous == null) {
            return;
        }
        String mismatch = !previous.literalName().equals(current.literalName())
                ? "name/case mismatch ('" + previous.literalName() + "' versus '" + current.literalName() + "')"
                : EnvironmentDomainUtils.incompatibility(previous.definition(), current.definition());
        if (mismatch != null) {
            errors.putIfAbsent("nodes[" + nodeIndex + "].templateName",
                    "Shared environment variable '" + previous.literalName() + "' has a " + mismatch
                            + " between device '" + previous.deviceLabel() + "' (template '"
                            + previous.templateName() + "') and device '" + current.deviceLabel()
                            + "' (template '" + current.templateName() + "'). Align the two template declarations.");
        }
    }

    private String deviceDisplayName(DeviceNodeDto node) {
        if (hasText(node.getLabel())) return node.getLabel().trim();
        if (hasText(node.getId())) return node.getId().trim();
        return hasText(node.getTemplateName()) ? node.getTemplateName().trim() : "unnamed device";
    }

    private record EnvironmentDomainSource(String literalName,
                                           String deviceLabel,
                                           String templateName,
                                           DeviceManifest.InternalVariable definition) {
    }

    private DeviceManifest manifestForTemplateName(String templateName,
                                                   Map<String, DeviceManifest> templateManifests) {
        if (!hasText(templateName) || templateManifests == null) {
            return null;
        }
        return templateManifests.get(templateName.trim().toLowerCase(Locale.ROOT));
    }


    private void validateNodeState(Map<String, String> errors,
                                   String field,
                                   DeviceNodeDto node,
                                   DeviceManifest manifest) {
        if (!hasModeStateMachine(manifest)) {
            return;
        }
        if (!hasText(node.getState())) {
            errors.putIfAbsent(field + ".state", "State is required for device templates with modes");
            return;
        }
        validateStateValues(errors, field + ".state", manifest, "=", node.getState());
    }

    private void validateStatelessNodeFields(Map<String, String> errors,
                                             String field,
                                             DeviceNodeDto node,
                                             boolean hasModeStateMachine) {
        if (hasModeStateMachine) {
            return;
        }
        if (hasText(node.getCurrentStateTrust())) {
            errors.putIfAbsent(field + ".currentStateTrust",
                    "currentStateTrust is only valid for device templates with modes");
        }
        if (hasText(node.getCurrentStatePrivacy())) {
            errors.putIfAbsent(field + ".currentStatePrivacy",
                    "currentStatePrivacy is only valid for device templates with modes");
        }
        String state = trimToNull(node.getState());
        if (state != null && !"Working".equalsIgnoreCase(state)) {
            errors.putIfAbsent(field + ".state",
                    "No-mode device state must be omitted or the UI placeholder 'Working'");
        }
    }

    private boolean hasModeStateMachine(DeviceManifest manifest) {
        return !DeviceManifestModes.modeNames(manifest).isEmpty() && !DeviceManifestModes.modeStates(manifest).isEmpty();
    }

    private void validateNodeTrust(Map<String, String> errors, String field, String value) {
        if (value == null) {
            return;
        }
        if (!hasText(value)) {
            errors.putIfAbsent(field, "Trust value cannot be blank when provided");
            return;
        }
        validateTrustValues(errors, field, "=", value);
    }

    private void validateNodeVariables(Map<String, String> errors,
                                       String field,
                                       List<VariableStateDto> variables,
                                       DeviceManifest manifest) {
        if (variables == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < variables.size(); i++) {
            VariableStateDto variable = variables.get(i);
            String prefix = field + "[" + i + "]";
            if (variable == null) {
                errors.putIfAbsent(prefix, "Variable item cannot be null");
                continue;
            }
            String name = trimToNull(variable.getName());
            String value = trimToNull(variable.getValue());
            if (name == null) {
                errors.putIfAbsent(prefix + ".name", "Variable name is required");
                continue;
            }
            if (!seen.add(name)) {
                errors.putIfAbsent(prefix + ".name", "Duplicate variable override: " + name);
            }
            if (value == null) {
                errors.putIfAbsent(prefix + ".value", "Variable value is required");
            }
            validateNodeTrust(errors, prefix + ".trust", variable.getTrust());

            DeviceManifest.InternalVariable manifestVariable = internalVariable(manifest, name);
            if (manifestVariable == null) {
                errors.putIfAbsent(prefix + ".name", "Unknown runtime variable for device template: " + name);
            } else if (isEnvironmentVariable(manifestVariable)) {
                errors.putIfAbsent(prefix + ".name",
                        "Environment variable belongs to the board environment pool, not a device instance: " + name);
            } else if (value != null) {
                validateVariableValues(errors, prefix + ".value", manifestVariable, "=", value);
            }
        }
    }

    private void validateNodePrivacies(Map<String, String> errors,
                                       String field,
                                       List<PrivacyStateDto> privacies,
                                       DeviceManifest manifest) {
        if (privacies == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < privacies.size(); i++) {
            PrivacyStateDto privacy = privacies.get(i);
            String prefix = field + "[" + i + "]";
            if (privacy == null) {
                errors.putIfAbsent(prefix, "Privacy item cannot be null");
                continue;
            }
            String name = trimToNull(privacy.getName());
            String value = trimToNull(privacy.getPrivacy());
            if (name == null) {
                errors.putIfAbsent(prefix + ".name", "Privacy target name is required");
                continue;
            }
            if (!seen.add(name)) {
                errors.putIfAbsent(prefix + ".name", "Duplicate privacy override: " + name);
            }
            if (value == null) {
                errors.putIfAbsent(prefix + ".privacy", "Privacy value is required");
            } else {
                validatePrivacyValues(errors, prefix + ".privacy", "=", value);
            }
            DeviceManifest.InternalVariable manifestVariable = internalVariable(manifest, name);
            if (manifestVariable == null) {
                errors.putIfAbsent(prefix + ".name",
                        "Unknown device-local variable privacy target: " + name);
            } else if (isEnvironmentVariable(manifestVariable)) {
                errors.putIfAbsent(prefix + ".name",
                        "Environment variable privacy belongs to the board environment pool, not a device instance: " + name);
            }
        }
    }

    private void validateRuleSemantics(Map<String, String> errors,
                                       String field,
                                       RuleDto rule,
                                       Map<String, DeviceNodeDto> nodesById,
                                       Map<String, DeviceManifest> templateManifests) {
        if (rule == null) {
            return;
        }

        RuleDto.Command command = rule.getCommand();
        if (command != null) {
            DeviceManifest commandManifest = manifestForDeviceRef(command.getDeviceName(), nodesById, templateManifests);
            DeviceManifest.API actionApi = findApi(commandManifest, command.getAction());
            if (commandManifest != null && hasText(command.getAction()) && actionApi == null) {
                errors.putIfAbsent(field + ".command.action",
                        "Unknown command API for device template: " + command.getAction());
            }
            if (hasText(command.getContentDevice()) != hasText(command.getContent())) {
                errors.putIfAbsent(field + ".command.content",
                        "contentDevice and content must be provided together");
            } else if (hasText(command.getContent())) {
                if (actionApi != null && !Boolean.TRUE.equals(actionApi.getAcceptsContent())) {
                    errors.putIfAbsent(field + ".command.content",
                            "Command API '" + actionApi.getName()
                                    + "' does not accept a content-sensitivity input");
                }
                DeviceManifest contentManifest = manifestForDeviceRef(command.getContentDevice(), nodesById, templateManifests);
                if (contentManifest != null && !hasContent(contentManifest, command.getContent())) {
                    errors.putIfAbsent(field + ".command.content",
                            "Unknown content for device template: " + command.getContent());
                }
            }
        }

        List<RuleDto.Condition> conditions = rule.getConditions();
        if (conditions == null) {
            return;
        }
        for (int conditionIndex = 0; conditionIndex < conditions.size(); conditionIndex++) {
            validateRuleConditionSemantics(errors,
                    field + ".conditions[" + conditionIndex + "]",
                    conditions.get(conditionIndex), nodesById, templateManifests);
        }
    }

    private void validateRuleConditionSemantics(Map<String, String> errors,
                                                String field,
                                                RuleDto.Condition condition,
                                                Map<String, DeviceNodeDto> nodesById,
                                                Map<String, DeviceManifest> templateManifests) {
        if (condition == null) {
            return;
        }
        DeviceManifest manifest = manifestForDeviceRef(condition.getDeviceName(), nodesById, templateManifests);
        if (manifest == null) {
            return;
        }

        String targetType = normalizeTargetType(condition.getTargetType());
        String attribute = trimToNull(condition.getAttribute());
        if (targetType == null) {
            errors.putIfAbsent(field + ".targetType",
                    "Condition targetType must be one of api, variable, mode, state");
            return;
        }
        if (attribute == null) {
            errors.putIfAbsent(field + ".attribute", "Condition attribute is required");
            return;
        }

        if ("api".equals(targetType)) {
            if (hasText(condition.getRelation()) || hasText(condition.getValue())) {
                errors.putIfAbsent(field + ".relation",
                        "API signal conditions must not include relation or value");
            }
            if (!hasSignalApi(manifest, attribute)) {
                errors.putIfAbsent(field + ".attribute",
                        "Unknown or non-signal API for rule condition: " + attribute);
            }
            return;
        }

        String relation = validateRelationValue(errors, field, condition.getRelation(), condition.getValue(), targetType);
        if (relation == null) {
            return;
        }

        switch (targetType) {
            case "variable" -> {
                DeviceManifest.InternalVariable variable = conditionSourceVariable(manifest, attribute);
                if (variable == null) {
                    errors.putIfAbsent(field + ".attribute",
                            "Unknown internal variable for rule condition: " + attribute);
                } else {
                    validateVariableValues(errors, field + ".value", variable, relation, condition.getValue());
                }
            }
            case "mode" -> {
                if (!hasMode(manifest, attribute)) {
                    errors.putIfAbsent(field + ".attribute", "Unknown mode for rule condition: " + attribute);
                } else {
                    validateModeValues(errors, field + ".value", manifest, attribute, relation, condition.getValue());
                }
            }
            case "state" -> {
                if (!"state".equalsIgnoreCase(attribute)) {
                    errors.putIfAbsent(field + ".attribute", "State rule conditions must use attribute 'state'");
                } else {
                    validateStateValues(errors, field + ".value", manifest, relation, condition.getValue());
                }
            }
            default -> errors.putIfAbsent(field + ".targetType",
                    "Condition targetType must be one of api, variable, mode, state");
        }
    }

    /**
     * Validates a spec condition against the resolved manifest, for conditions of ANY origin.
     *
     * <p>The name says {@code ForAnyOrigin} because this runs over the specifications <em>already in
     * storage</em> as part of the whole-board revalidation that guards device, rule, and layout writes — not
     * only over what a request is writing. So it may only reject what is wrong for a specification that
     * already exists: a dangling device reference, an out-of-domain value, a reading that is illegal for its
     * declaration.
     *
     * <p>It must NOT reject a newly-required field for being absent. Doing that for {@code variableSource}
     * made one specification written before the field existed block every unrelated mutation: adding a
     * device returned {@code specs[0].aConditions[0].variableSource is required}, naming a specification the
     * request did not contain, and the only operation left was deleting it. Requirements on newly-authored
     * data belong in {@code requireResolvedVariableSource}, which the authoring paths call.
     */
    private void validateSpecConditionSemanticsForAnyOrigin(Map<String, String> errors,
                                                String field,
                                                SpecConditionDto condition,
                                                Map<String, DeviceNodeDto> nodesById,
                                                Map<String, DeviceManifest> templateManifests) {
        if (condition == null) {
            return;
        }
        DeviceManifest manifest = manifestForDeviceRef(condition.getDeviceId(), nodesById, templateManifests);
        if (manifest == null) {
            return;
        }

        String targetType = normalizeSpecTargetType(condition.getTargetType());
        String key = trimToNull(condition.getKey());
        if (targetType == null) {
            errors.putIfAbsent(field + ".targetType",
                    "targetType must be one of: state, mode, variable, api, trust, privacy");
            return;
        }
        if (key == null) {
            errors.putIfAbsent(field + ".key", "Specification condition key is required");
            return;
        }
        String propertyScope = normalizePropertyScope(condition.getPropertyScope());
        if ("trust".equals(targetType) || "privacy".equals(targetType)) {
            if (propertyScope == null) {
                errors.putIfAbsent(field + ".propertyScope",
                        "propertyScope is required for trust/privacy and must be state or variable");
            }
        } else if (hasText(condition.getPropertyScope())) {
            errors.putIfAbsent(field + ".propertyScope",
                    "propertyScope is only valid for trust/privacy conditions");
        }

        // Which of two questions a variable condition asks, and whether that question even exists for this
        // declaration. `environment` reads the shared pool value — "did this happen in the home" — and
        // `reported` reads what this device said. They diverge only when the device is compromised.
        //
        // A device-local declaration (IsInside=true) has no pool value at all, so `environment` would name
        // an identifier the generated model never declares. Refusing it here, where the manifest is
        // resolved, turns that into a field error naming the declaration instead of a NuSMV parse failure
        // or a silently skipped specification.
        if ("variable".equals(targetType)) {
            String variableSource = normalizeVariableSource(condition.getVariableSource());
            if (variableSource == null) {
                // Absent is not rejected here: this also revalidates specifications already in storage, so a
                // pre-existing one would block unrelated device and rule writes. `requireResolvedVariableSource`
                // demands the choice on the authoring paths. See shared-value-semantics.md.
                return;
            } else if ("environment".equals(variableSource)
                    && !isEnvironmentVariable(internalVariable(manifest, key))) {
                errors.putIfAbsent(field + ".variableSource",
                        "variableSource=environment needs a shared variable, but '" + key
                                + "' is declared IsInside=true and exists only inside this device. Ask "
                                + "reported instead, or declare the variable as shared");
            }
        } else if (hasText(condition.getVariableSource())) {
            errors.putIfAbsent(field + ".variableSource",
                    "variableSource is only valid for variable conditions");
        }

        String relation = validateRelationValue(errors, field, condition.getRelation(), condition.getValue(), targetType);
        if (relation == null) {
            return;
        }

        switch (targetType) {
            case "api" -> {
                validateEnumRelation(errors, field + ".relation", relation, "API signal");
                if (!hasSignalApi(manifest, key)) {
                    errors.putIfAbsent(field + ".key", "Unknown or non-signal API for specification: " + key);
                }
                validateBooleanValues(errors, field + ".value", relation, condition.getValue());
            }
            case "state" -> {
                validateEnumRelation(errors, field + ".relation", relation, "state");
                if (!"state".equalsIgnoreCase(key)) {
                    errors.putIfAbsent(field + ".key", "State specification conditions must use key 'state'");
                }
                validateStateValues(errors, field + ".value", manifest, relation, condition.getValue());
            }
            case "mode" -> {
                validateEnumRelation(errors, field + ".relation", relation, "mode");
                if (!hasMode(manifest, key)) {
                    errors.putIfAbsent(field + ".key", "Unknown mode for specification: " + key);
                } else {
                    validateModeValues(errors, field + ".value", manifest, key, relation, condition.getValue());
                }
            }
            case "variable" -> {
                DeviceManifest.InternalVariable variable = variableOrEnvKey(manifest, key);
                if (variable == null) {
                    errors.putIfAbsent(field + ".key",
                            "Unknown internal or environment variable for specification: " + key);
                } else {
                    validateVariableValues(errors, field + ".value", variable, relation, condition.getValue());
                }
            }
            case "trust" -> {
                validateEnumRelation(errors, field + ".relation", relation, "trust");
                validatePropertyReference(errors, field, propertyScope, key, manifest);
                validateTrustValues(errors, field + ".value", relation, condition.getValue());
            }
            case "privacy" -> {
                validateEnumRelation(errors, field + ".relation", relation, "privacy");
                validatePropertyReference(errors, field, propertyScope, key, manifest);
                validatePrivacyValues(errors, field + ".value", relation, condition.getValue());
            }
            default -> errors.putIfAbsent(field + ".targetType",
                    "targetType must be one of: state, mode, variable, api, trust, privacy");
        }
    }

    private Map<String, DeviceManifest> loadTemplateManifestMap(Long userId) {
        if (deviceTemplateRepo == null) {
            return Collections.emptyMap();
        }
        List<DeviceTemplatePo> templates = deviceTemplateRepo.findByUserId(userId);
        if (templates == null || templates.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, DeviceManifest> manifests = new HashMap<>();
        for (DeviceTemplatePo template : templates) {
            if (template == null || !hasText(template.getName())) {
                continue;
            }
            String canonicalName = template.getName().trim();
            DeviceManifest manifest = deviceTemplateMapper.toDto(template).getManifest();
            manifests.put(canonicalName.toLowerCase(Locale.ROOT), manifest);
        }
        return manifests;
    }

    private Map<String, DeviceNodeDto> nodesById(List<DeviceNodeDto> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, DeviceNodeDto> result = new HashMap<>();
        for (DeviceNodeDto node : nodes) {
            if (node != null && hasText(node.getId())) {
                result.put(node.getId().trim(), node);
            }
        }
        return result;
    }

    private DeviceManifest manifestForDeviceRef(String deviceRef,
                                                Map<String, DeviceNodeDto> nodesById,
                                                Map<String, DeviceManifest> templateManifests) {
        if (!hasText(deviceRef) || nodesById == null || templateManifests == null) {
            return null;
        }
        DeviceNodeDto node = nodesById.get(deviceRef.trim());
        if (node == null || !hasText(node.getTemplateName())) {
            return null;
        }
        return templateManifests.get(node.getTemplateName().trim().toLowerCase(Locale.ROOT));
    }

    private String validateRelationValue(Map<String, String> errors,
                                         String field,
                                         String rawRelation,
                                         String rawValue,
                                         String targetType) {
        if (!hasText(rawRelation) || !hasText(rawValue)) {
            errors.putIfAbsent(field + ".relation",
                    "Condition relation and value are required for targetType '" + targetType + "'");
            return null;
        }
        String relation = SmvRelationUtils.normalizeRelation(rawRelation);
        if (!SmvRelationUtils.isSupportedRelation(relation)) {
            errors.putIfAbsent(field + ".relation", "Unsupported relation: " + rawRelation);
            return null;
        }
        if (("in".equals(relation) || "not in".equals(relation))
                && SmvRelationUtils.splitRuleValues(rawValue).isEmpty()) {
            errors.putIfAbsent(field + ".value", "Condition value list cannot be empty");
            return null;
        }
        return relation;
    }

    private void validateEnumRelation(Map<String, String> errors,
                                      String field,
                                      String relation,
                                      String label) {
        if (!SmvRelationUtils.isEnumRelation(relation)) {
            errors.putIfAbsent(field,
                    label + " conditions support only =, !=, IN, and NOT_IN");
        }
    }

    private void validateStateValues(Map<String, String> errors,
                                     String field,
                                     DeviceManifest manifest,
                                     String relation,
                                     String rawValue) {
        Map<String, List<String>> modeStates = DeviceManifestModes.modeStates(manifest);
        List<String> modes = DeviceManifestModes.modeNames(manifest);
        if (modes.isEmpty() || modeStates.isEmpty()) {
            errors.putIfAbsent(field, "Device template has no legal states");
            return;
        }
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitStateRuleValues(rawValue, modes.size())
                : List.of(rawValue.trim());
        if (candidates.isEmpty()) {
            errors.putIfAbsent(field, "State condition value cannot be empty");
            return;
        }
        if (("=".equals(relation) || "!=".equals(relation)) && candidates.size() != 1) {
            errors.putIfAbsent(field, "State relation '" + relation + "' requires exactly one value");
            return;
        }
        for (String candidate : candidates) {
            if (!ManifestConditionSemantics.isLegalState(candidate, modes, modeStates)) {
                errors.putIfAbsent(field, "Illegal state value for device template: " + candidate);
                return;
            }
        }
    }

    private void validateModeValues(Map<String, String> errors,
                                    String field,
                                    DeviceManifest manifest,
                                    String mode,
                                    String relation,
                                    String rawValue) {
        Map<String, List<String>> modeStates = DeviceManifestModes.modeStates(manifest);
        List<String> legalStates = modeStates.get(mode);
        if (legalStates == null || legalStates.isEmpty()) {
            errors.putIfAbsent(field, "Mode has no legal values: " + mode);
            return;
        }
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitRuleValues(rawValue)
                : List.of(rawValue.trim());
        if (candidates.isEmpty()) {
            errors.putIfAbsent(field, "Mode condition value cannot be empty");
            return;
        }
        if (("=".equals(relation) || "!=".equals(relation)) && candidates.size() != 1) {
            errors.putIfAbsent(field, "Mode relation '" + relation + "' requires exactly one value");
            return;
        }
        for (String candidate : candidates) {
            String cleaned = DeviceSmvDataFactory.cleanStateName(candidate);
            if (cleaned == null || !legalStates.contains(cleaned)) {
                errors.putIfAbsent(field, "Illegal mode value '" + candidate + "' for mode '" + mode + "'");
                return;
            }
        }
    }

    private void validateBooleanValues(Map<String, String> errors,
                                       String field,
                                       String relation,
                                       String rawValue) {
        validateLiteralValues(errors, field, relation, rawValue, Set.of("TRUE", "FALSE"), "TRUE or FALSE");
    }

    private void validateTrustValues(Map<String, String> errors,
                                     String field,
                                     String relation,
                                     String rawValue) {
        validateLiteralValues(errors, field, relation, rawValue,
                Set.of("trusted", "untrusted"), "trusted or untrusted");
    }

    private void validatePrivacyValues(Map<String, String> errors,
                                       String field,
                                       String relation,
                                       String rawValue) {
        validateLiteralValues(errors, field, relation, rawValue,
                Set.of("public", "private"), "public or private");
    }

    private void validateLiteralValues(Map<String, String> errors,
                                       String field,
                                       String relation,
                                       String rawValue,
                                       Set<String> allowed,
                                       String label) {
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitRuleValues(rawValue)
                : List.of(rawValue.trim());
        for (String candidate : candidates) {
            boolean found = ManifestConditionSemantics.matchesLiteral(candidate, allowed);
            if (!found) {
                errors.putIfAbsent(field, "Value must be " + label + ": " + candidate);
                return;
            }
        }
    }

    private void validateVariableValues(Map<String, String> errors,
                                        String field,
                                        DeviceManifest.InternalVariable variable,
                                        String relation,
                                        String rawValue) {
        if (variable == null || !hasText(rawValue)) {
            return;
        }
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitRuleValues(rawValue)
                : List.of(rawValue.trim());
        if (candidates.isEmpty()) {
            errors.putIfAbsent(field, "Variable condition value cannot be empty");
            return;
        }
        if (("=".equals(relation) || "!=".equals(relation)) && candidates.size() != 1) {
            errors.putIfAbsent(field, "Variable relation '" + relation + "' requires exactly one value");
            return;
        }

        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            validateEnumRelation(errors, field, relation, "enum variable");
        }
        ManifestConditionSemantics.ValueIssue issue =
                ManifestConditionSemantics.firstInvalidVariableValue(variable, candidates);
        if (issue == null) return;
        String message = switch (issue.error()) {
            case UNKNOWN_ENUM -> "Illegal enum value for variable '" + variable.getName() + "': " + issue.candidate();
            case OUT_OF_RANGE -> "Variable value out of range for '" + variable.getName() + "': " + issue.candidate()
                    + " (allowed " + variable.getLowerBound() + ".." + variable.getUpperBound() + ")";
            case NOT_INTEGER -> "Variable value must be an integer for '" + variable.getName() + "': " + issue.candidate();
        };
        errors.putIfAbsent(field, message);
    }

    private boolean hasApi(DeviceManifest manifest, String apiName) {
        return findApi(manifest, apiName) != null;
    }

    private boolean hasContent(DeviceManifest manifest, String contentName) {
        if (manifest == null || manifest.getContents() == null || !hasText(contentName)) {
            return false;
        }
        for (DeviceManifest.Content content : manifest.getContents()) {
            if (content != null && contentName.trim().equals(content.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Existence of a variable a specification may reference by <em>property</em> (trust or privacy).
     *
     * <p>Deliberately capability-blind, unlike {@link #conditionSourceVariable}: the device module
     * declares {@code trust_<name>} and {@code privacy_<name>} for every declared variable, so a trust
     * label on an affect-only value is something the generated model really permits. Asking about a
     * label is not reading the value. Narrowing this would reject a specification the model would have
     * checked, which is the "stricter than the model" failure the backend rules call out.
     */
    private boolean hasInternalVariable(DeviceManifest manifest, String variableName) {
        return internalVariable(manifest, variableName) != null;
    }

    private boolean hasVariableOrEnvKey(DeviceManifest manifest, String key) {
        return variableOrEnvKey(manifest, key) != null;
    }

    private DeviceManifest.InternalVariable variableOrEnvKey(DeviceManifest manifest, String key) {
        if (!hasText(key)) {
            return null;
        }
        String normalized = key.trim();
        return conditionSourceVariable(manifest, normalized);
    }

    /**
     * Resolves a variable a rule or specification may name as a condition source.
     *
     * <p>Deliberately narrower than {@link #internalVariable}: it withholds an affect-only shared
     * declaration ({@code Reads=false}), because the generator emits no {@code device.name := a_name}
     * mirror for one, so a condition naming it would reference a value the device never observes.
     * {@code internalVariable} stays capability-blind on purpose — its other callers ask whether a
     * declaration <em>exists</em> (domain resolution, runtime overrides), where an affect-only
     * declaration is a legitimate answer.
     *
     * <p>This is the persist-time gate, so it holds for every writer: the REST board endpoints, the
     * assistant's rule and specification tools, and scene import alike. Without it a rule the UI
     * refuses to build and the verification request validator refuses to run could still be stored.
     */
    private DeviceManifest.InternalVariable conditionSourceVariable(DeviceManifest manifest, String variableName) {
        DeviceManifest.InternalVariable variable = internalVariable(manifest, variableName);
        if (variable == null) {
            return null;
        }
        boolean affectOnlyShared = !Boolean.TRUE.equals(variable.getIsInside())
                && Boolean.FALSE.equals(variable.getReads());
        return affectOnlyShared ? null : variable;
    }

    private DeviceManifest.InternalVariable internalVariable(DeviceManifest manifest, String variableName) {
        if (manifest == null || manifest.getInternalVariables() == null || !hasText(variableName)) {
            return null;
        }
        String normalized = variableName.trim();
        for (DeviceManifest.InternalVariable variable : manifest.getInternalVariables()) {
            if (variable != null && normalized.equals(variable.getName())) {
                return variable;
            }
        }
        return null;
    }

    private boolean hasMode(DeviceManifest manifest, String modeName) {
        if (!hasText(modeName)) {
            return false;
        }
        return DeviceManifestModes.modeNames(manifest).contains(modeName.trim());
    }

    private void validatePropertyReference(Map<String, String> errors,
                                           String field,
                                           String scope,
                                           String key,
                                           DeviceManifest manifest) {
        if (scope == null) {
            return;
        }
        if ("variable".equals(scope)) {
            if (!hasInternalVariable(manifest, key)) {
                errors.putIfAbsent(field + ".key", "Unknown property variable for specification: " + key);
            }
            return;
        }
        if (!hasMode(manifest, key)) {
            errors.putIfAbsent(field + ".key", "Unknown state-label mode for specification: " + key);
        }
    }

    private String normalizePropertyScope(String value) {
        return SpecConditionNormalization.propertyScope(value);
    }

    /**
     * Null for absent or unrecognised, so a caller cannot tell "the author chose nothing" from "the author
     * chose something invalid" — both are refused with the same message, and neither is silently repaired.
     */
    // Delegated so this boundary and NusmvRequestValidator cannot fold a value differently; the error text
    // stays local because each boundary reports to a different audience.
    private String normalizeVariableSource(String value) {
        return SpecConditionNormalization.variableSource(value);
    }

    private String normalizeTargetType(String targetType) {
        if (!hasText(targetType)) {
            return null;
        }
        String normalized = targetType.trim().toLowerCase(Locale.ROOT);
        return Set.of("api", "variable", "mode", "state").contains(normalized) ? normalized : null;
    }

    private String normalizeSpecTargetType(String targetType) {
        return SpecConditionNormalization.knownSpecTargetType(targetType);
    }

    private String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private Set<String> nodeIds(List<DeviceNodeDto> nodes) {
        Set<String> ids = new HashSet<>();
        if (nodes == null) {
            return ids;
        }
        for (DeviceNodeDto node : nodes) {
            if (node == null || !hasText(node.getId())) {
                continue;
            }
            ids.add(node.getId().trim());
        }
        return ids;
    }

    private void requireBoardNodeRef(Map<String, String> errors,
                                     String field,
                                     String value,
                                     Set<String> nodeIds,
                                     String message) {
        if (!hasText(value)) {
            return;
        }
        String ref = value.trim();
        if (!nodeIds.contains(ref)) {
            errors.putIfAbsent(field, message + ": " + ref);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void requireAdditionalCapacity(String resource, int current, int additional, int maximum) {
        long requested = (long) current + Math.max(0, additional);
        if (requested > maximum) {
            throw new BadRequestException(
                    "The Board supports at most " + maximum + " " + resource
                            + "; delete existing items before adding more.");
        }
    }

    private void requireTotalCapacity(String resource, int total, int maximum) {
        if (total > maximum) {
            throw new BadRequestException(
                    "The Board supports at most " + maximum + " " + resource + ".");
        }
    }

    private void addCollectionLimitError(
            Map<String, String> errors, String field, Collection<?> values, int maximum) {
        if (values != null && values.size() > maximum) {
            errors.put(field, "At most " + maximum + " items are allowed");
        }
    }

    @Override
    public CollectionMutationResultDto<RuleDto> addRule(Long userId, RuleDto rule) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                RuleDto canonicalRule = canonicalizeRuleRelationsForStorage(rule);
                requireRuleWithinDeclaredBounds(canonicalRule);
                canonicalRule.setId(null); // new rule, let DB assign ID
                List<RuleDto> nextRules = new ArrayList<>(getRulesInternal(userId));
                requireAdditionalCapacity("rules", nextRules.size(), 1, RequestLimits.MAX_RULES);
                nextRules.add(canonicalRule);
                List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
                validateNoIdenticalRules(nextRules, currentNodes);
                validateBoardReferences(userId, currentNodes, nextRules, null);
                RulePo po = ruleMapper.toEntity(canonicalRule, userId);
                // After deletions, execution_order has gaps — the list size is no longer the next available
                // slot. Finding the maximum ensures the new rule sorts last, which is what the journal entry
                // at nextRules.size() - 1 describes. Without this, a rule added after two deletions could sort
                // before an existing rule, inverting their evaluation order — semantic, not cosmetic.
                int maxOrder = ruleRepo.findByUserId(userId).stream()
                        .filter(r -> r.getExecutionOrder() != null)
                        .mapToInt(RulePo::getExecutionOrder)
                        .max().orElse(-1);
                po.setExecutionOrder(maxOrder + 1);
                RulePo saved = ruleRepo.save(Objects.requireNonNull(po, "rule to save must not be null"));
                RuleDto created = ruleMapper.toDto(saved);
                editJournal.record(userId, BoardEditEntityType.RULE, BoardEditOperation.CREATE,
                        String.valueOf(saved.getId()), null, created, nextRules.size() - 1);
                return withUndoAvailability(userId,
                        CollectionMutationResultDto.of("created", created, getRulesInternal(userId)));
            });
        }
    }

    @Override
    public CollectionMutationResultDto<RuleDto> updateRuleIfUnchanged(
            Long userId, long ruleId, RuleDto expected, RuleDto desired) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                if (ruleId <= 0 || expected == null || desired == null
                        || !Objects.equals(expected.getId(), ruleId) || !Objects.equals(desired.getId(), ruleId)) {
                    throw new BadRequestException(
                            "Expected and desired rule snapshots must identify the rule being edited.");
                }
                List<RulePo> records = ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(userId);
                RulePo record = records.stream().filter(rule -> Objects.equals(rule.getId(), ruleId))
                        .findFirst().orElseThrow(() -> new ResourceNotFoundException("Rule", ruleId));
                RuleDto before = ruleMapper.toDto(record);
                if (!sameRuleDeletionSnapshot(expected, before)) {
                    String message = "The rule changed before this edit. Refresh it before saving your changes.";
                    throw new BoardContentConflictException("RULE_EDIT_STALE", message, Map.of("expected", message));
                }
                RuleDto canonical = canonicalizeRuleRelationsForStorage(desired);
                requireRuleWithinDeclaredBounds(canonical);
                List<RuleDto> current = records.stream().map(ruleMapper::toDto).toList();
                int position = records.indexOf(record);
                List<RuleDto> next = new ArrayList<>(current);
                next.set(position, canonical);
                List<DeviceNodeDto> nodes = getNodesInternal(userId);
                validateNoIdenticalRules(next, nodes);
                validateBoardReferences(userId, nodes, next, null);
                if (sameRuleDeletionSnapshot(before, canonical)) {
                    return withUndoAvailability(userId,
                            CollectionMutationResultDto.of("unchanged", before, current));
                }
                RulePo updated = ruleMapper.toEntity(canonical, userId);
                updated.setCreatedAt(record.getCreatedAt());
                updated.setExecutionOrder(record.getExecutionOrder());
                RuleDto saved = ruleMapper.toDto(ruleRepo.save(updated));
                editJournal.record(userId, BoardEditEntityType.RULE, BoardEditOperation.UPDATE,
                        String.valueOf(ruleId), before, saved, position);
                return withUndoAvailability(userId,
                        CollectionMutationResultDto.of("updated", saved, getRulesInternal(userId)));
            });
        }
    }

    @Override
    public CollectionMutationResultDto<RuleDto> reorderRules(
            Long userId, List<Long> expectedRuleIds, List<Long> ruleIds) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                List<RulePo> currentRecords = ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(userId);
                List<RuleDto> current = currentRecords.stream().map(ruleMapper::toDto).toList();
                List<Long> currentIds = current.stream().map(RuleDto::getId).toList();
                List<Long> expected = validatedRuleOrder(expectedRuleIds, "Expected rule execution order");
                if (!currentIds.equals(expected)) {
                    throw new ConflictException(
                            "The rule execution order changed before this update. Refresh the board and try again.");
                }

                List<Long> requested = validatedRuleOrder(ruleIds, "Rule execution order");
                Set<Long> uniqueRequested = new LinkedHashSet<>(requested);

                Map<Long, RuleDto> currentById = current.stream()
                        .filter(Objects::nonNull)
                        .filter(rule -> rule.getId() != null)
                        .collect(Collectors.toMap(RuleDto::getId, rule -> rule, (first, ignored) -> first,
                                LinkedHashMap::new));
                if (requested.size() != current.size()
                        || !currentById.keySet().equals(uniqueRequested)) {
                    throw new ConflictException(
                            "The rule list changed before its execution order was saved. Refresh the board and try again.");
                }
                if (requested.equals(expected)) {
                    throw new BadRequestException(
                            "Rule execution order must change at least one position.");
                }

                renumberSurvivingRules(currentRecords, requested, null);
                List<RuleDto> saved = requested.stream().map(currentById::get).toList();
                // Reversing a reorder means restoring the previous ordering, so the journal stores
                // the id lists rather than a record snapshot. Same transaction as the write.
                editJournal.record(userId, BoardEditEntityType.RULE_ORDER,
                        BoardEditOperation.UPDATE, RULE_ORDER_ENTITY_KEY,
                        new RuleOrderSnapshot(expected), new RuleOrderSnapshot(requested));
                return withUndoAvailability(userId,
                        CollectionMutationResultDto.of("reordered", null, saved));
            });
        }
    }

    private List<Long> validatedRuleOrder(List<Long> ruleIds, String field) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            throw new BadRequestException(field + " is required.");
        }
        List<Long> validated = new ArrayList<>(ruleIds.size());
        for (Long ruleId : ruleIds) {
            if (ruleId == null || ruleId <= 0) {
                throw new BadRequestException(field + " must contain only positive rule ids.");
            }
            validated.add(ruleId);
        }
        if (new LinkedHashSet<>(validated).size() != validated.size()) {
            throw new BadRequestException(field + " contains duplicate rule ids.");
        }
        return List.copyOf(validated);
    }

    private void validateNoIdenticalRules(List<RuleDto> rules, List<DeviceNodeDto> nodes) {
        List<RuleDto> safeRules = rules == null ? List.of() : rules;
        for (int left = 0; left < safeRules.size(); left++) {
            RuleDto candidate = safeRules.get(left);
            for (int right = 0; right < left; right++) {
                RuleDto existing = safeRules.get(right);
                if (RuleSemanticSignature.exactlyMatches(candidate, existing)) {
                    String reason = "The board cannot contain two identical automation rules: "
                                    + describeRuleForUser(existing, nodes)
                                    + ". Change the trigger or action, or remove the duplicate.";
                    throw new BoardContentConflictException("DUPLICATE_RULE", reason,
                            Map.of("rules[" + left + "]", reason));
                }
            }
        }
    }

    private String describeRuleForUser(RuleDto rule, List<DeviceNodeDto> nodes) {
        Map<String, String> labelsById = (nodes == null ? List.<DeviceNodeDto>of() : nodes).stream()
                .filter(Objects::nonNull)
                .filter(node -> hasText(node.getId()))
                .collect(Collectors.toMap(
                        node -> node.getId().trim(),
                        node -> hasText(node.getLabel()) ? node.getLabel().trim() : "device",
                        (first, ignored) -> first));
        String conditions = (rule == null || rule.getConditions() == null
                ? List.<RuleDto.Condition>of()
                : rule.getConditions()).stream()
                .filter(Objects::nonNull)
                .map(condition -> {
                    String device = labelsById.getOrDefault(condition.getDeviceName(), condition.getDeviceName());
                    String key = hasText(condition.getAttribute()) ? condition.getAttribute().trim() : "condition";
                    if ("api".equalsIgnoreCase(condition.getTargetType())) {
                        return device + " triggers " + key;
                    }
                    return device + "." + key + " "
                            + (hasText(condition.getRelation()) ? condition.getRelation().trim() : "=") + " "
                            + (hasText(condition.getValue()) ? condition.getValue().trim() : "value");
                })
                .collect(Collectors.joining(" AND "));
        RuleDto.Command command = rule == null ? null : rule.getCommand();
        String target = command == null
                ? "device.action"
                : labelsById.getOrDefault(command.getDeviceName(), command.getDeviceName()) + "."
                    + (hasText(command.getAction()) ? command.getAction().trim() : "action");
        /*
         * Every label lookup here falls back to the device **id**, not to the word "device".
         *
         * A message naming the rule the user collided with is only useful if it identifies the devices, and the id
         * is the one thing that always does. The old fallbacks — `"device"` here, `"Unknown device"` in
         * `RuleToolPresenter.displayLabel` — discarded the only identifier available at exactly the moment the
         * label was missing, which is when the reader most needs it. A label is nicer when present; the id is
         * never worse than a placeholder.
         */
        /*
         * The content clause, which this message used to drop.
         *
         * `RuleSemanticSignature` compares `contentDevice` and `content` (`:72-73`), so they are part of a rule's
         * identity — but this description rendered only `device.action`. The message names the *existing* rule the
         * new one collided with, so a board holding two rules that differ only in their content showed the user a
         * description matching both, and no way to tell which one it meant.
         *
         * Same wording as `RuleToolPresenter.describeCommand`, so the rejection and the AI assistant describe the
         * same rule the same way. Both require *both* halves before rendering the clause: a content device with no
         * content names a source for nothing.
         */
        if (command != null && hasText(command.getContentDevice()) && hasText(command.getContent())) {
            target = target + " using "
                    + labelsById.getOrDefault(command.getContentDevice(), command.getContentDevice()) + "."
                    + command.getContent().trim();
        }
        return "IF " + (conditions.isBlank() ? "condition" : conditions) + " THEN " + target;
    }

    private void validateNoIdenticalSpecifications(List<SpecificationDto> specs, List<DeviceNodeDto> nodes) {
        List<SpecificationDto> safeSpecs = specs == null ? List.of() : specs;
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < safeSpecs.size(); index++) {
            SpecificationDto spec = safeSpecs.get(index);
            if (spec != null && !ids.add(spec.getId())) {
                String reason = "The board cannot contain two specifications with the same ID: " + spec.getId();
                throw new BoardContentConflictException("DUPLICATE_SPECIFICATION", reason,
                        Map.of("specs[" + index + "].id", reason));
            }
        }
        for (int left = 0; left < safeSpecs.size(); left++) {
            SpecificationDto candidate = safeSpecs.get(left);
            for (int right = 0; right < left; right++) {
                SpecificationDto existing = safeSpecs.get(right);
                if (SpecificationSemanticSignature.exactlyMatches(candidate, existing)) {
                    String reason = "The board cannot contain two identical specifications: "
                                    + describeSpecificationForUser(existing, nodes)
                                    + ". Change a checked condition or remove the duplicate.";
                    throw new BoardContentConflictException("DUPLICATE_SPECIFICATION", reason,
                            Map.of("specs[" + left + "]", reason));
                }
            }
        }
    }

    private String describeSpecificationForUser(SpecificationDto spec, List<DeviceNodeDto> nodes) {
        Map<String, String> labelsById = (nodes == null ? List.<DeviceNodeDto>of() : nodes).stream()
                .filter(Objects::nonNull)
                .filter(node -> hasText(node.getId()))
                .collect(Collectors.toMap(
                        node -> node.getId().trim(),
                        node -> hasText(node.getLabel()) ? node.getLabel().trim() : "device",
                        (first, ignored) -> first));
        List<String> descriptions = new ArrayList<>();
        appendSpecificationConditionDescriptions(descriptions, "A", spec.getAConditions(), labelsById);
        appendSpecificationConditionDescriptions(descriptions, "IF", spec.getIfConditions(), labelsById);
        appendSpecificationConditionDescriptions(descriptions, "THEN", spec.getThenConditions(), labelsById);
        String template = spec.getName();
        return descriptions.isEmpty() ? template : template + " [" + String.join("; ", descriptions) + "]";
    }

    private void appendSpecificationConditionDescriptions(List<String> output,
                                                           String side,
                                                           List<SpecConditionDto> conditions,
                                                           Map<String, String> labelsById) {
        for (SpecConditionDto condition : conditions == null ? List.<SpecConditionDto>of() : conditions) {
            if (condition == null) {
                continue;
            }
            String device = labelsById.getOrDefault(condition.getDeviceId(), condition.getDeviceId());
            output.add(side + ": " + device + "."
                    + (hasText(condition.getKey()) ? condition.getKey().trim() : "condition") + " "
                    + (hasText(condition.getRelation()) ? condition.getRelation().trim() : "=") + " "
                    + (hasText(condition.getValue()) ? condition.getValue().trim() : "value"));
        }
    }

    @Override
    public CollectionMutationResultDto<RuleDto> removeRuleIfUnchanged(
            Long userId, long ruleId, RuleDto expected) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                if (expected == null) {
                    throw new BadRequestException(
                            "The confirmed rule snapshot is required for deletion.");
                }
                List<RulePo> existing = ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(userId);
                RulePo removed = existing.stream()
                        .filter(rule -> rule.getId() != null && rule.getId() == ruleId)
                        .findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Rule", ruleId));
                RuleDto current = ruleMapper.toDto(removed);
                if (!sameRuleDeletionSnapshot(expected, current)) {
                    throw new ConflictException(
                            "The rule changed after confirmation. Review the current rule before deleting it.");
                }
                ruleRepo.deleteById(ruleId);
                // Same transaction as the delete: an undo entry that could commit without its
                // edit (or the reverse) would describe a state that never existed.
                // The position goes in the entry, not in RuleDto: undo must restore where the rule sat,
                // because execution order decides which rule wins when guards overlap.
                editJournal.record(userId, BoardEditEntityType.RULE, BoardEditOperation.DELETE,
                        String.valueOf(ruleId), current, null, existing.indexOf(removed));
                List<RuleDto> remaining = getRulesInternal(userId);
                return withUndoAvailability(
                        userId, CollectionMutationResultDto.of("deleted", current, remaining));
            });
        }
    }

    @Override
    public BoardUndoResultDto undoLastEdit(Long userId) {
        return applyJournalDirection(userId, true);
    }

    @Override
    @Transactional(readOnly = true)
    public BoardUndoResultDto boardEditAvailability(Long userId) {
        // Availability only. Deliberately does not return board collections: this is a query, and
        // shipping collections here would invite a client to treat a read as a board update.
        BoardUndoAvailability availability = editJournal.availability(userId);
        // Not NOTHING_TO_APPLY: that means "you pressed undo and there was no history", which would
        // contradict canUndo=true in this same object. Nothing was attempted here.
        return journalStatusResult("AVAILABILITY_ONLY", availability);
    }

    @Override
    public BoardUndoResultDto redoLastUndoneEdit(Long userId) {
        return applyJournalDirection(userId, false);
    }

    @Override
    public BoardEditHistoryClearPreviewDto previewBoardEditHistoryClear(Long userId) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                BoardEditHistoryState state = editJournal.historyState(userId);
                return BoardEditHistoryClearPreviewDto.builder()
                        .impactToken(state.impactToken())
                        .entryCount(state.entryCount())
                        .canUndo(state.availability().canUndo())
                        .canRedo(state.availability().canRedo())
                        .build();
            });
        }
    }

    @Override
    public BoardUndoResultDto clearBoardEditHistory(Long userId, String expectedImpactToken) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                BoardEditHistoryState state = editJournal.historyState(userId);
                if (!hasText(expectedImpactToken) || !MessageDigest.isEqual(
                        state.impactToken().getBytes(StandardCharsets.UTF_8),
                        expectedImpactToken.trim().getBytes(StandardCharsets.UTF_8))) {
                    throw new ConflictException(
                            "Undo history changed after confirmation. Review it before clearing.");
                }
                editJournal.clear(userId);
                return journalStatusResult(
                        "HISTORY_CLEARED", new BoardUndoAvailability(false, false));
            });
        }
    }

    private BoardUndoResultDto journalStatusResult(
            String reasonCode, BoardUndoAvailability availability) {
        BoardUndoResultDto result = new BoardUndoResultDto();
        result.setApplied(false);
        result.setReasonCode(reasonCode);
        result.setNodes(List.of());
        result.setEnvironmentVariables(List.of());
        result.setRules(List.of());
        result.setSpecs(List.of());
        result.setCanUndo(availability.canUndo());
        result.setCanRedo(availability.canRedo());
        return result;
    }

    /**
     * Applies one journal entry in the requested direction.
     *
     * <p>Runs under the same per-user write lock and transaction as ordinary board mutations, so
     * the inverse edit, its validation, and the journal flag commit together. Undo reaches the
     * entry's {@code before} state; redo reaches its {@code after} state — which means both are
     * expressed as "make this record look like this snapshot", and a single code path can serve
     * them.
     */
    private BoardUndoResultDto applyJournalDirection(Long userId, boolean undo) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);

                BoardEditJournalPo entry = (undo
                        ? editJournal.nextToUndo(userId)
                        : editJournal.nextToRedo(userId)).orElse(null);
                if (entry == null) {
                    // Nothing left in that direction. Not an error: the user simply pressed the
                    // shortcut once more than there is history.
                    return journalResult(userId, false, null, null, "NOTHING_TO_APPLY");
                }

                String targetJson = undo ? entry.getBeforeJson() : entry.getAfterJson();
                String expectedJson = undo ? entry.getAfterJson() : entry.getBeforeJson();

                if (entry.getEntityType() == null || entry.getOperation() == null) {
                    throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
                }
                switch (entry.getEntityType()) {
                    case DEVICE -> applyDeviceJournalEntry(userId, entry, undo);
                    case ENVIRONMENT -> applyEnvironmentJournalEntry(userId, entry, undo);
                    case RULE -> applyRuleJournalEntry(userId, entry, targetJson, expectedJson);
                    case SPECIFICATION ->
                            applySpecJournalEntry(userId, entry, targetJson, expectedJson);
                    case RULE_ORDER -> applyRuleOrderJournalEntry(userId, entry, undo);
                    case RULE_SET -> applyRuleCollectionJournalEntry(userId, entry, undo);
                }

                editJournal.markUndone(entry, undo);
                return journalResult(userId, true, entry, undo, undo ? "UNDONE" : "REDONE");
            });
        }
    }

    private BoardUndoResultDto journalResult(
            Long userId, boolean applied, BoardEditJournalPo entry, Boolean undo, String reasonCode) {
        BoardUndoAvailability availability = editJournal.availability(userId);
        BoardUndoResultDto result = new BoardUndoResultDto();
        result.setApplied(applied);
        result.setReasonCode(reasonCode);
        if (entry != null) {
            result.setEntityType(entry.getEntityType().name());
            result.setOriginalOperation(entry.getOperation().name());
        }
        result.setNodes(getNodesInternal(userId));
        result.setEnvironmentVariables(getEnvironmentVariablesInternal(userId));
        result.setRules(getRulesInternal(userId));
        result.setSpecs(getSpecsInternal(userId));
        result.setCanUndo(availability.canUndo());
        result.setCanRedo(availability.canRedo());
        return result;
    }

    /**
     * Restores a rule execution ordering.
     *
     * <p>Refuses when the current ordering is not the one this edit produced, or when the rule set
     * itself changed — in either case some later work intervened and re-imposing the old order
     * would discard it.
     */
    private void applyRuleOrderJournalEntry(
            Long userId, BoardEditJournalPo entry, boolean undo) {
        RuleOrderSnapshot before = editJournal.readJson(
                entry.getBeforeJson(), RuleOrderSnapshot.class);
        RuleOrderSnapshot after = editJournal.readJson(
                entry.getAfterJson(), RuleOrderSnapshot.class);
        if (!validRuleOrderJournalTransition(entry, before, after)) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        RuleOrderSnapshot target = undo ? before : after;
        RuleOrderSnapshot expected = undo ? after : before;

        List<RulePo> current = ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(userId);
        List<Long> currentIds = current.stream().map(RulePo::getId).toList();
        if (!currentIds.equals(expected.ruleIds())) {
            throw new ConflictException(
                    "The rule order changed after this edit, so reversing it would discard newer work.");
        }

        Map<Long, RulePo> byId = current.stream()
                .filter(rule -> rule != null && rule.getId() != null)
                .collect(Collectors.toMap(RulePo::getId, rule -> rule, (first, ignored) -> first,
                        LinkedHashMap::new));
        // Defence in depth: the order check above already fails whenever the rule set differs, because
        // journal entries are applied newest-first and any create/delete since this reorder is a newer
        // entry that must be undone first. Keep this so a future change to that walk order cannot
        // silently re-impose an ordering that drops or resurrects a rule.
        if (!byId.keySet().equals(new LinkedHashSet<>(target.ruleIds()))) {
            throw new ConflictException(
                    "The rule list changed after this edit, so its order can no longer be reversed.");
        }

        renumberSurvivingRules(current, target.ruleIds(), null);
    }

    private boolean validRuleOrderJournalTransition(
            BoardEditJournalPo entry,
            RuleOrderSnapshot before,
            RuleOrderSnapshot after) {
        if (entry.getOperation() != BoardEditOperation.UPDATE
                || !RULE_ORDER_ENTITY_KEY.equals(entry.getEntityKey())
                || entry.getEntityOrder() != null
                || !validRuleOrderSnapshot(before)
                || !validRuleOrderSnapshot(after)
                || before.ruleIds().equals(after.ruleIds())) {
            return false;
        }
        return new LinkedHashSet<>(before.ruleIds())
                .equals(new LinkedHashSet<>(after.ruleIds()));
    }

    private boolean validRuleOrderSnapshot(RuleOrderSnapshot snapshot) {
        if (snapshot == null || snapshot.ruleIds().isEmpty()) return false;
        return snapshot.ruleIds().stream().allMatch(id -> id != null && id > 0)
                && new LinkedHashSet<>(snapshot.ruleIds()).size() == snapshot.ruleIds().size();
    }

    private void applyEnvironmentJournalEntry(
            Long userId, BoardEditJournalPo entry, boolean undo) {
        EnvironmentJournalSnapshot before = editJournal.readJson(
                entry.getBeforeJson(), EnvironmentJournalSnapshot.class);
        EnvironmentJournalSnapshot after = editJournal.readJson(
                entry.getAfterJson(), EnvironmentJournalSnapshot.class);
        if (entry.getOperation() != BoardEditOperation.UPDATE
                || !ENVIRONMENT_ENTITY_KEY.equals(entry.getEntityKey())
                || entry.getEntityOrder() != null
                || !validEnvironmentJournalSnapshot(before)
                || !validEnvironmentJournalSnapshot(after)
                || environmentDtoMap(before.environmentVariables())
                        .equals(environmentDtoMap(after.environmentVariables()))) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        EnvironmentJournalSnapshot target = undo ? before : after;
        EnvironmentJournalSnapshot expected = undo ? after : before;
        List<BoardEnvironmentVariableDto> current = getEnvironmentVariablesInternal(userId);
        if (!environmentDtoMap(current).equals(environmentDtoMap(expected.environmentVariables()))) {
            throw new ConflictException(
                    "The Environment Pool changed after this edit, so reversing it would discard newer work.");
        }
        List<BoardEnvironmentVariableDto> saved = saveEnvironmentVariablesInternal(
                userId, getNodesInternal(userId), target.environmentVariables(), false);
        if (!environmentDtoMap(saved).equals(environmentDtoMap(target.environmentVariables()))) {
            throw new ConflictException(
                    "The saved Environment Pool no longer supports this edit, so it cannot be reversed safely.");
        }
    }

    private boolean validEnvironmentJournalSnapshot(EnvironmentJournalSnapshot snapshot) {
        if (snapshot == null || snapshot.environmentVariables() == null) return false;
        Set<String> names = new HashSet<>();
        for (BoardEnvironmentVariableDto variable : snapshot.environmentVariables()) {
            if (variable == null || !hasText(variable.getName()) || !hasText(variable.getValue())
                    || !("trusted".equals(variable.getTrust()) || "untrusted".equals(variable.getTrust()))
                    || !("public".equals(variable.getPrivacy()) || "private".equals(variable.getPrivacy()))
                    || !names.add(variable.getName())) {
                return false;
            }
        }
        return true;
    }

    private void applyRuleCollectionJournalEntry(
            Long userId, BoardEditJournalPo entry, boolean undo) {
        RuleCollectionJournalSnapshot before = editJournal.readJson(
                entry.getBeforeJson(), RuleCollectionJournalSnapshot.class);
        RuleCollectionJournalSnapshot after = editJournal.readJson(
                entry.getAfterJson(), RuleCollectionJournalSnapshot.class);
        if (entry.getOperation() != BoardEditOperation.UPDATE
                || !RULE_SET_ENTITY_KEY.equals(entry.getEntityKey())
                || entry.getEntityOrder() != null
                || !validRuleCollectionJournalSnapshot(before)
                || !validRuleCollectionJournalSnapshot(after)
                || sameOrderedRuleCollection(before.rules(), after.rules())) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        RuleCollectionJournalSnapshot target = undo ? before : after;
        RuleCollectionJournalSnapshot expected = undo ? after : before;
        if (!sameOrderedRuleCollection(getRulesInternal(userId), expected.rules())) {
            throw new ConflictException(
                    "The rule list changed after this automatic fix, so reversing it would discard newer work.");
        }
        List<RuleDto> saved = saveRulesForJournal(userId, target.rules());
        if (!sameOrderedRuleCollection(saved, target.rules())) {
            throw new ConflictException(
                    "The original rule identities are no longer available, so this automatic fix cannot be reversed.");
        }
    }

    private boolean validRuleCollectionJournalSnapshot(RuleCollectionJournalSnapshot snapshot) {
        if (snapshot == null || snapshot.rules() == null) return false;
        Set<Long> ids = new HashSet<>();
        for (RuleDto rule : snapshot.rules()) {
            if (rule == null || rule.getId() == null || rule.getId() <= 0
                    || rule.getCreatedAt() == null
                    || !ids.add(rule.getId()) || rule.getConditions() == null
                    || rule.getConditions().isEmpty() || rule.getCommand() == null) {
                return false;
            }
        }
        return true;
    }

    private boolean sameOrderedRuleCollection(List<RuleDto> first, List<RuleDto> second) {
        if (first == null || second == null || first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            if (!sameRuleDeletionSnapshot(first.get(index), second.get(index))) return false;
        }
        return true;
    }

    /** Applies either side of a compound device edit after proving the expected side still exists. */
    private void applyDeviceJournalEntry(Long userId, BoardEditJournalPo entry, boolean undo) {
        DeviceJournalSnapshot before = editJournal.readJson(
                entry.getBeforeJson(), DeviceJournalSnapshot.class);
        DeviceJournalSnapshot after = editJournal.readJson(
                entry.getAfterJson(), DeviceJournalSnapshot.class);
        if (!validDeviceJournalTransition(entry, before, after)) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        DeviceJournalSnapshot target = undo ? before : after;
        DeviceJournalSnapshot expected = undo ? after : before;

        List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
        List<RuleDto> currentRules = getRulesInternal(userId);
        List<SpecificationDto> currentSpecs = getSpecsInternal(userId);
        List<BoardEnvironmentVariableDto> currentEnvironment = getEnvironmentVariablesInternal(userId);
        Set<String> affectedDeviceIds = new LinkedHashSet<>(target.getDeviceIds());
        Set<Long> affectedRuleIds = unionRuleIds(target, expected);
        Set<String> affectedSpecIds = unionSpecIds(target, expected);

        if (!matchesExpectedDevices(currentNodes, expected, affectedDeviceIds)
                || !matchesExpectedRules(currentRules, expected, affectedRuleIds)
                || !matchesExpectedSpecs(currentSpecs, expected, affectedSpecIds)
                || !environmentDtoMap(currentEnvironment)
                        .equals(environmentDtoMap(expected.getEnvironmentVariables()))) {
            throw new ConflictException(
                    "The Board changed after this device edit, so reversing it would discard newer work.");
        }

        List<DeviceNodeDto> nextNodes = replacePositionedDevices(
                currentNodes, affectedDeviceIds, target.getDevices());
        List<RuleDto> nextRules = replacePositionedRules(
                currentRules, affectedRuleIds, target.getRules());
        List<SpecificationDto> nextSpecs = replacePositionedSpecs(
                currentSpecs, affectedSpecIds, target.getSpecs());

        requireTotalCapacity("devices", nextNodes.size(), RequestLimits.MAX_DEVICES);
        requireTotalCapacity("rules", nextRules.size(), RequestLimits.MAX_RULES);
        requireTotalCapacity("specifications", nextSpecs.size(), RequestLimits.MAX_SPECS);
        requireTotalCapacity("environment variables", target.getEnvironmentVariables().size(),
                RequestLimits.MAX_ENVIRONMENT_VARIABLES);

        List<DeviceNodeDto> canonicalNodes = canonicalizeNodes(userId, nextNodes);
        validateBoardReferences(userId, canonicalNodes, nextRules, nextSpecs);
        if (entry.getOperation() == BoardEditOperation.UPDATE) {
            applyTargetedDeviceJournalUpdate(
                    userId, canonicalNodes, target, nextSpecs, affectedSpecIds);
            return;
        }
        List<DeviceNodeDto> savedNodes = saveNodesInternal(userId, canonicalNodes);
        if (!affectedRuleIds.isEmpty()) {
            saveRulesForJournal(userId, nextRules);
        }
        if (!affectedSpecIds.isEmpty()) {
            saveSpecsInternal(userId, nextSpecs, savedNodes);
        }
        saveEnvironmentVariablesInternal(
                userId, savedNodes, target.getEnvironmentVariables(), false);
    }

    /** Restores one device update without normalizing untouched nullable JSON fields. */
    private void applyTargetedDeviceJournalUpdate(
            Long userId,
            List<DeviceNodeDto> canonicalNodes,
            DeviceJournalSnapshot target,
            List<SpecificationDto> nextSpecs,
            Set<String> affectedSpecIds) {
        String deviceId = target.getDeviceIds().get(0);
        DeviceNodeDto desired = requireNode(canonicalNodes, deviceId);
        DeviceNodePo entity = nodeRepo.findById(new DeviceNodeId(deviceId, userId))
                .orElseThrow(() -> new ConflictException(
                        "The device no longer exists, so this edit cannot be reversed."));
        DeviceNodePo mapped = Objects.requireNonNull(
                deviceNodeMapper.toEntity(desired, userId),
                "device journal target entity must not be null");
        entity.setTemplateName(mapped.getTemplateName());
        entity.setLabel(mapped.getLabel());
        entity.setPosX(mapped.getPosX());
        entity.setPosY(mapped.getPosY());
        entity.setState(mapped.getState());
        entity.setWidth(mapped.getWidth());
        entity.setHeight(mapped.getHeight());
        entity.setCurrentStateTrust(mapped.getCurrentStateTrust());
        entity.setCurrentStatePrivacy(mapped.getCurrentStatePrivacy());
        entity.setVariablesJson(desired.getVariables() == null ? null : mapped.getVariablesJson());
        entity.setPrivaciesJson(desired.getPrivacies() == null ? null : mapped.getPrivaciesJson());
        nodeRepo.save(entity);

        if (!affectedSpecIds.isEmpty()) {
            saveSpecsInternal(userId, nextSpecs, getNodesInternal(userId));
        }
    }

    private boolean validDeviceJournalTransition(
            BoardEditJournalPo entry,
            DeviceJournalSnapshot before,
            DeviceJournalSnapshot after) {
        if (!validDeviceJournalSnapshot(before) || !validDeviceJournalSnapshot(after)
                || !before.getDeviceIds().equals(after.getDeviceIds())
                || !Objects.equals(entry.getEntityKey(), before.getDeviceIds().get(0))
                || entry.getEntityOrder() != null) {
            return false;
        }
        List<String> deviceIds = before.getDeviceIds();
        return switch (entry.getOperation()) {
            case CREATE -> affectedDeviceStateIsEmpty(before)
                    && after.getDevices().size() == deviceIds.size()
                    && after.getRules().isEmpty()
                    && after.getSpecs().isEmpty();
            case DELETE -> before.getDevices().size() == deviceIds.size()
                    && affectedDeviceStateIsEmpty(after)
                    && before.getRules().stream().allMatch(item -> deviceIds.stream()
                            .anyMatch(id -> ruleReferencesNode(item.getRule(), id)))
                    && before.getSpecs().stream().allMatch(item -> deviceIds.stream()
                            .anyMatch(id -> specificationReferencesNode(item.getSpec(), id)));
            case UPDATE -> before.getDevices().size() == deviceIds.size()
                    && deviceIds.size() == 1
                    && after.getDevices().size() == deviceIds.size()
                    && before.getRules().isEmpty()
                    && after.getRules().isEmpty()
                    && positionedDeviceOrder(before).equals(positionedDeviceOrder(after))
                    && positionedSpecOrder(before).equals(positionedSpecOrder(after))
                    && environmentDtoMap(before.getEnvironmentVariables())
                            .equals(environmentDtoMap(after.getEnvironmentVariables()))
                    && validDeviceUpdateJournalTransition(before, after);
        };
    }

    /** Proves a device UPDATE is exactly one supported user action, never a template rewrite. */
    private boolean validDeviceUpdateJournalTransition(
            DeviceJournalSnapshot before,
            DeviceJournalSnapshot after) {
        DeviceNodeDto previous = before.getDevices().get(0).getDevice();
        DeviceNodeDto current = after.getDevices().get(0).getDevice();
        if (!Objects.equals(previous.getId(), current.getId())
                || !Objects.equals(previous.getTemplateName(), current.getTemplateName())) {
            return false;
        }

        boolean labelChanged = !Objects.equals(previous.getLabel(), current.getLabel());
        boolean layoutChanged = !Objects.equals(previous.getPosition(), current.getPosition())
                || !Objects.equals(previous.getWidth(), current.getWidth())
                || !Objects.equals(previous.getHeight(), current.getHeight());
        boolean runtimeChanged = !Objects.equals(previous.getState(), current.getState())
                || !Objects.equals(previous.getCurrentStateTrust(), current.getCurrentStateTrust())
                || !Objects.equals(previous.getCurrentStatePrivacy(), current.getCurrentStatePrivacy())
                || !Objects.equals(previous.getVariables(), current.getVariables())
                || !Objects.equals(previous.getPrivacies(), current.getPrivacies());
        if ((labelChanged ? 1 : 0) + (layoutChanged ? 1 : 0) + (runtimeChanged ? 1 : 0) != 1) {
            return false;
        }

        if (!labelChanged) {
            return before.getSpecs().isEmpty() && after.getSpecs().isEmpty();
        }
        for (int index = 0; index < before.getSpecs().size(); index++) {
            SpecificationDto previousSpec = before.getSpecs().get(index).getSpec();
            SpecificationDto currentSpec = after.getSpecs().get(index).getSpec();
            if (!sameSpecificationDeletionSnapshot(previousSpec, currentSpec)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Integer> positionedDeviceOrder(DeviceJournalSnapshot snapshot) {
        return snapshot.getDevices().stream().collect(Collectors.toMap(
                item -> item.getDevice().getId(),
                DeviceJournalSnapshot.DeviceWithPosition::getPosition));
    }

    private Map<String, Integer> positionedSpecOrder(DeviceJournalSnapshot snapshot) {
        return snapshot.getSpecs().stream().collect(Collectors.toMap(
                item -> item.getSpec().getId(),
                DeviceJournalSnapshot.SpecWithPosition::getPosition));
    }

    private boolean affectedDeviceStateIsEmpty(DeviceJournalSnapshot snapshot) {
        return snapshot.getDevices().isEmpty()
                && snapshot.getRules().isEmpty()
                && snapshot.getSpecs().isEmpty();
    }

    private boolean validDeviceJournalSnapshot(DeviceJournalSnapshot snapshot) {
        if (snapshot == null || snapshot.getDeviceIds() == null || snapshot.getDeviceIds().isEmpty()
                || snapshot.getDevices() == null || snapshot.getRules() == null
                || snapshot.getSpecs() == null || snapshot.getEnvironmentVariables() == null) {
            return false;
        }
        Set<String> deviceIds = new LinkedHashSet<>();
        for (String id : snapshot.getDeviceIds()) {
            if (!hasText(id) || !deviceIds.add(id)) return false;
        }
        Set<String> presentDeviceIds = new HashSet<>();
        Set<Integer> devicePositions = new HashSet<>();
        for (DeviceJournalSnapshot.DeviceWithPosition item : snapshot.getDevices()) {
            if (item == null || item.getDevice() == null || item.getPosition() < 0
                    || !deviceIds.contains(item.getDevice().getId())
                    || !presentDeviceIds.add(item.getDevice().getId())
                    || !devicePositions.add(item.getPosition())) return false;
        }
        Set<Long> ruleIds = new HashSet<>();
        Set<Integer> rulePositions = new HashSet<>();
        for (DeviceJournalSnapshot.RuleWithPosition item : snapshot.getRules()) {
            if (item == null || item.getRule() == null || item.getRule().getId() == null
                    || item.getRule().getCreatedAt() == null
                    || item.getPosition() < 0 || !ruleIds.add(item.getRule().getId())
                    || !rulePositions.add(item.getPosition())) return false;
        }
        Set<String> specIds = new HashSet<>();
        Set<Integer> specPositions = new HashSet<>();
        for (DeviceJournalSnapshot.SpecWithPosition item : snapshot.getSpecs()) {
            if (item == null || !hasValidSpecificationName(item.getSpec()) || !hasText(item.getSpec().getId())
                    || item.getPosition() < 0 || !specIds.add(item.getSpec().getId())
                    || !specPositions.add(item.getPosition())) return false;
        }
        Set<String> environmentNames = new HashSet<>();
        for (BoardEnvironmentVariableDto variable : snapshot.getEnvironmentVariables()) {
            if (variable == null || !hasText(variable.getName())
                    || !environmentNames.add(variable.getName())) return false;
        }
        return true;
    }

    private boolean matchesExpectedDevices(
            List<DeviceNodeDto> current,
            DeviceJournalSnapshot expected,
            Set<String> affectedIds) {
        Map<String, DeviceNodeDto> currentById = current.stream()
                .filter(Objects::nonNull)
                .filter(node -> affectedIds.contains(node.getId()))
                .collect(Collectors.toMap(DeviceNodeDto::getId, node -> node));
        Map<String, DeviceNodeDto> expectedById = expected.getDevices().stream()
                .collect(Collectors.toMap(item -> item.getDevice().getId(),
                        DeviceJournalSnapshot.DeviceWithPosition::getDevice));
        if (!currentById.keySet().equals(expectedById.keySet())) return false;
        return expectedById.entrySet().stream()
                .allMatch(item -> sameDeviceForUndo(item.getValue(), currentById.get(item.getKey())));
    }

    private boolean matchesExpectedRules(
            List<RuleDto> current,
            DeviceJournalSnapshot expected,
            Set<Long> affectedIds) {
        Map<Long, DeviceJournalSnapshot.RuleWithPosition> expectedById = expected.getRules().stream()
                .collect(Collectors.toMap(item -> item.getRule().getId(), item -> item));
        for (Long id : affectedIds) {
            int currentPosition = indexOfRule(current, id);
            DeviceJournalSnapshot.RuleWithPosition expectedItem = expectedById.get(id);
            if (expectedItem == null) {
                if (currentPosition >= 0) return false;
            } else if (currentPosition != expectedItem.getPosition()
                    || !sameRuleDeletionSnapshot(expectedItem.getRule(), current.get(currentPosition))) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesExpectedSpecs(
            List<SpecificationDto> current,
            DeviceJournalSnapshot expected,
            Set<String> affectedIds) {
        Map<String, DeviceJournalSnapshot.SpecWithPosition> expectedById = expected.getSpecs().stream()
                .collect(Collectors.toMap(item -> item.getSpec().getId(), item -> item));
        for (String id : affectedIds) {
            int currentPosition = indexOfSpecOrMissing(current, id);
            DeviceJournalSnapshot.SpecWithPosition expectedItem = expectedById.get(id);
            if (expectedItem == null) {
                if (currentPosition >= 0) return false;
            } else if (currentPosition != expectedItem.getPosition()
                    || !Objects.equals(expectedItem.getSpec(), current.get(currentPosition))) {
                return false;
            }
        }
        return true;
    }

    private List<DeviceNodeDto> replacePositionedDevices(
            List<DeviceNodeDto> current,
            Set<String> affectedIds,
            List<DeviceJournalSnapshot.DeviceWithPosition> target) {
        List<DeviceNodeDto> next = current.stream()
                .filter(node -> !affectedIds.contains(node.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
        target.stream().sorted(Comparator.comparingInt(DeviceJournalSnapshot.DeviceWithPosition::getPosition))
                .forEach(item -> insertAtRecordedPosition(next, item.getPosition(), item.getDevice()));
        return next;
    }

    private List<RuleDto> replacePositionedRules(
            List<RuleDto> current,
            Set<Long> affectedIds,
            List<DeviceJournalSnapshot.RuleWithPosition> target) {
        List<RuleDto> next = current.stream()
                .filter(rule -> !affectedIds.contains(rule.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
        target.stream().sorted(Comparator.comparingInt(DeviceJournalSnapshot.RuleWithPosition::getPosition))
                .forEach(item -> insertAtRecordedPosition(next, item.getPosition(), item.getRule()));
        return next;
    }

    private List<SpecificationDto> replacePositionedSpecs(
            List<SpecificationDto> current,
            Set<String> affectedIds,
            List<DeviceJournalSnapshot.SpecWithPosition> target) {
        List<SpecificationDto> next = current.stream()
                .filter(spec -> !affectedIds.contains(spec.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
        target.stream().sorted(Comparator.comparingInt(DeviceJournalSnapshot.SpecWithPosition::getPosition))
                .forEach(item -> insertAtRecordedPosition(next, item.getPosition(), item.getSpec()));
        return next;
    }

    /** Rejects a journal position that cannot reconstruct the recorded collection exactly. */
    private <T> void insertAtRecordedPosition(List<T> collection, int position, T item) {
        if (position < 0 || position > collection.size()) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        collection.add(position, item);
    }

    private List<RuleDto> saveRulesForJournal(Long userId, List<RuleDto> desired) {
        validateNoIdenticalRules(desired, getNodesInternal(userId));
        Map<Long, RulePo> existing = ruleRepo.findByUserId(userId).stream()
                .collect(Collectors.toMap(RulePo::getId, rule -> rule));
        Set<Long> desiredIds = desired.stream().map(RuleDto::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (desiredIds.contains(null) || desiredIds.size() != desired.size()) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        for (Long id : desiredIds) {
            if (!existing.containsKey(id) && ruleRepo.existsById(id)) {
                throw new ConflictException(
                        "A rule's original id is no longer available, so this edit cannot be reversed.");
            }
        }

        for (int position = 0; position < desired.size(); position++) {
            RuleDto canonical = canonicalizeRuleRelationsForStorage(desired.get(position));
            RulePo persisted = existing.get(canonical.getId());
            if (persisted == null) continue;
            RulePo replacement = ruleMapper.toEntity(canonical, userId);
            replacement.setExecutionOrder(position);
            replacement.setCreatedAt(persisted.getCreatedAt());
            ruleRepo.save(replacement);
        }
        existing.keySet().stream().filter(id -> !desiredIds.contains(id)).forEach(ruleRepo::deleteById);
        for (int position = 0; position < desired.size(); position++) {
            RuleDto canonical = canonicalizeRuleRelationsForStorage(desired.get(position));
            if (existing.containsKey(canonical.getId())) continue;
            RulePo po = ruleMapper.toEntity(canonical, userId);
            ruleRepo.insertWithId(canonical.getId(), userId, po.getConditionsJson(), po.getCommandJson(),
                    po.getRuleString(), position, canonical.getCreatedAt());
        }
        return getRulesInternal(userId);
    }

    private boolean sameDeviceForUndo(DeviceNodeDto expected, DeviceNodeDto current) {
        return Objects.equals(expected.getId(), current.getId())
                && Objects.equals(expected.getTemplateName(), current.getTemplateName())
                && Objects.equals(expected.getLabel(), current.getLabel())
                && Objects.equals(expected.getPosition(), current.getPosition())
                && Objects.equals(expected.getState(), current.getState())
                && Objects.equals(expected.getWidth(), current.getWidth())
                && Objects.equals(expected.getHeight(), current.getHeight())
                && Objects.equals(expected.getCurrentStateTrust(), current.getCurrentStateTrust())
                && Objects.equals(expected.getCurrentStatePrivacy(), current.getCurrentStatePrivacy())
                && Objects.equals(expected.getVariables(), current.getVariables())
                && Objects.equals(expected.getPrivacies(), current.getPrivacies());
    }

    private int indexOfRule(List<RuleDto> rules, Long ruleId) {
        for (int index = 0; index < rules.size(); index++) {
            if (Objects.equals(rules.get(index).getId(), ruleId)) return index;
        }
        return -1;
    }

    private int indexOfSpecOrMissing(List<SpecificationDto> specs, String specId) {
        for (int index = 0; index < specs.size(); index++) {
            if (Objects.equals(specs.get(index).getId(), specId)) return index;
        }
        return -1;
    }

    private Set<Long> unionRuleIds(DeviceJournalSnapshot first, DeviceJournalSnapshot second) {
        return Stream.concat(first.getRules().stream(), second.getRules().stream())
                .map(item -> item.getRule().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> unionSpecIds(DeviceJournalSnapshot first, DeviceJournalSnapshot second) {
        return Stream.concat(first.getSpecs().stream(), second.getSpecs().stream())
                .map(item -> item.getSpec().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Moves one rule to the state the journal entry describes.
     *
     * <p>{@code expectedJson} is what the record must currently look like for the entry to still
     * be meaningful. If reality differs, something else edited (or already restored) that rule
     * since, and applying the inverse would discard that work — so this refuses with a conflict.
     * A null target means "must not exist"; a null expected means "did not exist".
     */
    private void applyRuleJournalEntry(
            Long userId, BoardEditJournalPo entry, String targetJson, String expectedJson) {
        RuleDto target = editJournal.readJson(targetJson, RuleDto.class);
        RuleDto expected = editJournal.readJson(expectedJson, RuleDto.class);
        // An unreadable payload on either side is an unknown prior state, not a verified one. Without
        // this, a null `expected` from a parse failure reads as the legal "record already gone" case
        // and skips the drift check below entirely.
        if (!validSingleRecordJournalTransition(entry)
                || (targetJson != null && target == null)
                || (expectedJson != null && expected == null)
                || (target != null && target.getCreatedAt() == null)
                || (expected != null && expected.getCreatedAt() == null)
                || (entry.getOperation() == BoardEditOperation.UPDATE && target != null && expected != null
                        && (sameRuleDeletionSnapshot(target, expected)
                            || !Objects.equals(target.getCreatedAt(), expected.getCreatedAt())))) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }

        long ruleId;
        try {
            ruleId = Long.parseLong(entry.getEntityKey());
        } catch (NumberFormatException e) {
            // Same class as an unreadable payload: the entry does not identify a rule, so the inverse
            // cannot be applied. A typed conflict, not the NumberFormatException 500 this used to throw.
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        if (ruleId <= 0
                || (target != null && !Objects.equals(target.getId(), ruleId))
                || (expected != null && !Objects.equals(expected.getId(), ruleId))) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        RuleDto current = ruleRepo.findById(ruleId)
                .filter(rule -> Objects.equals(rule.getUserId(), userId))
                .map(ruleMapper::toDto)
                .orElse(null);

        if (expected == null) {
            if (current != null) {
                throw new ConflictException(
                        "This rule was recreated after the change, so it can no longer be reversed.");
            }
        } else if (current == null || !sameRuleDeletionSnapshot(expected, current)) {
            throw new ConflictException(
                    "This rule changed after the edit, so reversing it would discard newer work.");
        }

        if (target == null) {
            ruleRepo.deleteById(ruleId);
            return;
        }

        // Restoring keeps the original id, so anything referencing it — including later journal
        // entries naming the same rule — stays valid.
        RuleDto restored = canonicalizeRuleRelationsForStorage(target);
        restored.setId(ruleId);
        List<RuleDto> nextRules = new ArrayList<>(getRulesInternal(userId));
        if (entry.getOperation() == BoardEditOperation.UPDATE
                && (entry.getEntityOrder() >= nextRules.size()
                    || !Objects.equals(nextRules.get(entry.getEntityOrder()).getId(), ruleId))) {
            throw new ConflictException("This rule moved after the edit, so its recorded position no longer matches.");
        }
        nextRules.removeIf(rule -> Objects.equals(rule.getId(), ruleId));
        // Reinsert at the recorded position. getRulesInternal returns execution order, so the index is
        // a list position. A position that no longer fits is drift or malformed history, not permission
        // to append the rule to a semantically different execution order.
        // Every DELETE entry records the position, so a missing one is a journal row written before the
        // column existed. Appending instead would silently restore the rule to a different board —
        // execution order decides which rule wins when guards overlap — so reject it rather than
        // guessing (there is no released compatibility contract for pre-change development data).
        if (entry.getEntityOrder() == null) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        int restoreAt = entry.getEntityOrder();
        insertAtRecordedPosition(nextRules, restoreAt, restored);
        List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
        requireAdditionalCapacity("rules", nextRules.size() - 1, 1, RequestLimits.MAX_RULES);
        validateNoIdenticalRules(nextRules, currentNodes);
        validateBoardReferences(userId, currentNodes, nextRules, null);

        RulePo po = ruleMapper.toEntity(restored, userId);
        po.setId(ruleId);
        // Restore the recorded position rather than appending: execution order decides which rule wins
        // when guards overlap, so the end of the list is a different board.
        po.setExecutionOrder(restoreAt);
        // Deletion leaves a gap in execution_order, so the restored index can collide with a survivor.
        // A collision is resolved by the id tiebreak in the ordering query rather than by intent, which
        // would give a third ordering. Renumbering the survivors contiguously removes the ambiguity.
        if (entry.getOperation() == BoardEditOperation.UPDATE) {
            RulePo existing = ruleRepo.findById(ruleId).orElseThrow();
            po.setExecutionOrder(existing.getExecutionOrder());
            po.setCreatedAt(existing.getCreatedAt());
        } else {
            renumberSurvivingRules(ruleRepo.findByUserId(userId), nextRules.stream().map(RuleDto::getId).toList(), ruleId);
        }
        if (current == null) {
            // `rules.id` is a single global primary key, unlike device_node's composite (id, user_id),
            // but the drift check above only sees this account's rows. If another account now holds the
            // id, insertWithId would fail on the primary key and surface as a generic conflict. Say what
            // actually happened instead; the transaction rolls back either way and the entry stays.
            if (ruleRepo.existsById(ruleId)) {
                throw new ConflictException(
                        "This rule's original id is no longer available, so it cannot be restored under "
                                + "the id its references depend on.");
            }
            // IDENTITY-generated, so neither save() nor persist() can write an explicit one.
            ruleRepo.insertWithId(
                    ruleId,
                    userId,
                    po.getConditionsJson(),
                    po.getCommandJson(),
                    po.getRuleString(),
                    po.getExecutionOrder(),
                    // The server-recorded journal is authoritative; ordinary DTO writes still use RuleMapper.
                    restored.getCreatedAt());
            return;
        }
        ruleRepo.save(po);
    }

    /**
     * Changes ordering without reserializing or recanonicalizing existing rule semantics.
     *
     * <p>{@code deleteById} leaves a gap in {@code execution_order}, so reinserting at the recorded
     * index can duplicate a survivor's order. The ordering query then breaks the tie by id, which is
     * not the user's intent. Writing contiguous positions from the intended list removes the tie.
     */
    private void renumberSurvivingRules(List<RulePo> current, List<Long> intendedOrder, Long restoredRuleId) {
        Map<Long, RulePo> existing = current.stream()
                .filter(rule -> rule.getId() != null)
                .collect(Collectors.toMap(RulePo::getId, rule -> rule, (a, b) -> a));
        for (int index = 0; index < intendedOrder.size(); index++) {
            Long id = intendedOrder.get(index);
            if (id == null || Objects.equals(id, restoredRuleId)) continue;
            RulePo survivor = existing.get(id);
            if (survivor != null && !Objects.equals(survivor.getExecutionOrder(), index)) {
                survivor.setExecutionOrder(index);
                ruleRepo.save(survivor);
            }
        }
    }

    /**
     * Moves one specification to the state the journal entry describes, reconciling its collection
     * through the same validated path an ordinary mutation uses.
     */
    private void applySpecJournalEntry(
            Long userId, BoardEditJournalPo entry, String targetJson, String expectedJson) {
        SpecificationDto target = editJournal.readJson(targetJson, SpecificationDto.class);
        SpecificationDto expected = editJournal.readJson(expectedJson, SpecificationDto.class);
        if ((target != null && !hasValidSpecificationName(target))
                || (expected != null && !hasValidSpecificationName(expected))) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        // See applyRuleJournalEntry: an unreadable `expected` is an unknown prior state, and must not
        // pass for the legal "record already gone" case that skips the drift check.
        if (!validSingleRecordJournalTransition(entry)
                || (targetJson != null && target == null)
                || (expectedJson != null && expected == null)
                || (entry.getOperation() == BoardEditOperation.UPDATE && target != null && expected != null
                        && sameSpecificationDeletionSnapshot(target, expected))) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }

        String specId = entry.getEntityKey();
        if (!hasText(specId)
                || (target != null && !Objects.equals(target.getId(), specId))
                || (expected != null && !Objects.equals(expected.getId(), specId))) {
            throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
        }
        List<DeviceNodeDto> currentNodes = getNodesInternal(userId);
        List<SpecificationDto> existing = new ArrayList<>(getSpecsInternal(userId));
        SpecificationDto current = existing.stream()
                .filter(candidate -> specId.equals(candidate.getId()))
                .findFirst()
                .orElse(null);

        if (expected == null) {
            if (current != null) {
                throw new ConflictException(
                        "This specification was recreated after the change, so it can no longer be reversed.");
            }
        } else if (current == null || !sameSpecificationDeletionSnapshot(expected, current)) {
            throw new ConflictException(
                    "This specification changed after the edit, so reversing it would discard newer work.");
        }

        if (entry.getOperation() == BoardEditOperation.UPDATE
                && indexOfSpecification(existing, specId) != entry.getEntityOrder()) {
            throw new ConflictException(
                    "This specification moved after the edit, so its recorded position no longer matches.");
        }
        existing.removeIf(candidate -> specId.equals(candidate.getId()));
        if (target != null) {
            target.setId(specId);
            requireAdditionalCapacity(
                    "specifications", existing.size(), 1, RequestLimits.MAX_SPECS);
            // Reinsert at the recorded list position. As in applyRuleJournalEntry, a missing or
            // impossible position is not safe to reinterpret as append: that would report success for
            // a different ordered collection.
            if (entry.getEntityOrder() == null) {
                throw new ConflictException(UNREADABLE_JOURNAL_ENTRY_MESSAGE);
            }
            insertAtRecordedPosition(existing, entry.getEntityOrder(), target);
            validateBoardReferences(userId, currentNodes, null, existing);
        }
        saveSpecsInternal(userId, existing, currentNodes);
    }

    private boolean validSingleRecordJournalTransition(BoardEditJournalPo entry) {
        if (entry.getEntityOrder() == null || entry.getEntityOrder() < 0) return false;
        boolean hasBefore = hasText(entry.getBeforeJson());
        boolean hasAfter = hasText(entry.getAfterJson());
        return switch (entry.getOperation()) {
            case CREATE -> !hasBefore && hasAfter;
            case DELETE -> hasBefore && !hasAfter;
            case UPDATE -> hasBefore && hasAfter;
        };
    }

    /** List position of a specification, or the end when it is absent. */
    private int indexOfSpecification(List<SpecificationDto> specs, String specId) {
        for (int index = 0; index < specs.size(); index++) {
            SpecificationDto candidate = specs.get(index);
            if (candidate != null && specId.equals(candidate.getId())) return index;
        }
        return specs.size();
    }

    /**
     * Stamps a reversible mutation's result with the account's current undo availability.
     *
     * Read from the journal inside the same transaction, so the client's undo affordance reflects
     * exactly the history that just committed rather than a guess made on either side.
     */
    private <T> CollectionMutationResultDto<T> withUndoAvailability(
            Long userId, CollectionMutationResultDto<T> result) {
        BoardUndoAvailability availability = editJournal.availability(userId);
        return result.withUndoAvailability(availability.canUndo(), availability.canRedo());
    }

    private boolean sameRuleDeletionSnapshot(RuleDto expected, RuleDto current) {
        return Objects.equals(expected.getId(), current.getId())
                && Objects.equals(trimToNull(expected.getRuleString()), trimToNull(current.getRuleString()))
                && RuleSemanticSignature.exactlyMatches(expected, current);
    }

    @Override
    @Transactional
    public BoardLayoutDto getLayout(Long userId) {
        requireActiveUserForWrite(userId);
        BoardLayoutPo po = layoutRepo.findByUserId(userId).orElseGet(() -> {
            BoardLayoutPo created = BoardLayoutPo.builder()
                    .userId(userId)
                    .canvasPanX(0.0).canvasPanY(0.0).canvasZoom(1.0)
                    .controlPanelCollapsed(false).controlPanelWidth(320.0)
                    .controlPanelActiveSection("templates")
                    .inspectorPanelCollapsed(false).inspectorPanelWidth(320.0)
                    .inspectorPanelActiveSection("devices")
                    .build();
            return layoutRepo.save(Objects.requireNonNull(created, "layout to save must not be null"));
        });

        return boardLayoutMapper.toDto(po);
    }

    @Transactional
    @Override
    public BoardLayoutDto saveLayout(Long userId, BoardLayoutDto layout) {
        requireActiveUserForWrite(userId);
        BoardLayoutPo existing = layoutRepo.findByUserId(userId).orElse(null);
        Long id = existing != null ? existing.getId() : null;

        BoardLayoutPo po = boardLayoutMapper.toEntity(layout, id, userId);
        layoutRepo.save(Objects.requireNonNull(po, "layout to save must not be null"));

        return getLayout(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceTemplateDto> getDeviceTemplates(Long userId) {
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            throw UnauthorizedException.invalidToken();
        }
        return getDeviceTemplatesInternal(userId);
    }

    private List<DeviceTemplateDto> getDeviceTemplatesInternal(Long userId) {
        return deviceTemplateRepo.findByUserId(userId).stream()
                .map(deviceTemplateMapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public DeviceTemplateDto addDeviceTemplate(Long userId, DeviceTemplateDto dto) {
        requireActiveUserForWrite(userId);
        DeviceTemplateDto safeDto = Objects.requireNonNull(dto, "template dto must not be null");
        if (safeDto.getManifest() == null) {
            throw new BadRequestException("Template manifest is required");
        }
        if (deviceTemplateRepo.countByUserId(userId) >= RequestLimits.MAX_TEMPLATES) {
            throw new BadRequestException("At most 100 device types can be stored per user.");
        }

        String rawName = safeDto.getName() != null ? safeDto.getName().trim() : null;
        if (rawName == null || rawName.isBlank()) {
            throw new BadRequestException("Template name is required");
        }
        if (rawName.length() > 100) {
            throw new BadRequestException("Template name must be at most 100 characters");
        }

        final String canonicalName = rawName;
        String nameRejection = TemplateNameRule.rejectionReason(canonicalName);
        if (nameRejection != null) {
            throw new BadRequestException(
                    "Template name '" + canonicalName + "' " + nameRejection + ".");
        }
        safeDto.setName(canonicalName);
        safeDto.getManifest().setName(canonicalName);
        deviceTemplateSchemaValidator.validateManifest(canonicalName, safeDto.getManifest());
        templateNuSmvValidator.validateTemplateManifestForNuSmv(canonicalName, safeDto.getManifest());

        boolean duplicated = deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(userId, canonicalName);
        if (duplicated) {
            throw ConflictException.duplicateTemplate(canonicalName);
        }

        String json = deviceTemplateSchemaValidator.toCanonicalJson(safeDto.getManifest());

        DeviceTemplatePo po = DeviceTemplatePo.builder()
                .userId(userId)
                .name(canonicalName)
                .manifestJson(json)
                .defaultTemplate(false)
                .build();

        DeviceTemplatePo saved;
        try {
            saved = deviceTemplateRepo.saveAndFlush(Objects.requireNonNull(po, "template to save must not be null"));
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.duplicateTemplate(canonicalName);
        }
        templateNuSmvValidator.runTemplateNuSmvPrecheck(userId, canonicalName, safeDto.getManifest());

        return deviceTemplateMapper.toDto(saved);
    }

    @Override
    public DeviceTemplateDeletionResultDto previewDeviceTemplateDeletion(Long userId, Long templateId) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                return buildTemplateDeletionPlan(userId, templateId).preview();
            });
        }
    }

    @Override
    public DeviceTemplateDeletionResultDto deleteDeviceTemplate(
            Long userId, Long templateId, String expectedImpactToken) {
        if (!hasText(expectedImpactToken)) {
            throw new BadRequestException("Template-deletion impact token is required.");
        }
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                DeviceTemplateDeletionPlan plan = buildTemplateDeletionPlan(userId, templateId);
                DeviceTemplateDeletionResultDto preview = plan.preview();
                if (!MessageDigest.isEqual(
                        preview.getImpactToken().getBytes(StandardCharsets.UTF_8),
                        expectedImpactToken.trim().getBytes(StandardCharsets.UTF_8))) {
                    throw new TemplateDeletionConflictException(
                            "TEMPLATE_DELETION_PREVIEW_STALE",
                            "The device type, its usage, or undo history changed after preview. "
                                    + "Review the current impact before deleting.",
                            preview);
                }
                if (!preview.isCanDelete()) {
                    throw new TemplateDeletionConflictException(
                            "TEMPLATE_DELETION_BLOCKED",
                            "This device type is still used by canvas devices.",
                            preview);
                }

                deviceTemplateRepo.delete(plan.templatePo());
                deviceTemplateRepo.flush();
                editJournal.clear(userId);
                return DeviceTemplateDeletionResultDto.builder()
                        .operation("deleted")
                        .impactToken(preview.getImpactToken())
                        .canDelete(true)
                        .editHistoryEntryCount(preview.getEditHistoryEntryCount())
                        .template(preview.getTemplate())
                        .deletedTemplate(preview.getTemplate())
                        .blockers(List.of())
                        .currentTemplates(getDeviceTemplatesInternal(userId))
                        .build();
            });
        }
    }

    private DeviceTemplateDeletionPlan buildTemplateDeletionPlan(Long userId, Long templateId) {
        if (templateId == null || templateId <= 0) {
            throw new BadRequestException("Template id must be a positive integer");
        }
        DeviceTemplatePo po = deviceTemplateRepo.findByIdAndUserId(templateId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Template", templateId));

        DeviceTemplateDto target = deviceTemplateMapper.toDto(po);
        List<DeviceTemplateDeletionBlockerDto> blockers = nodeRepo.findByUserId(userId).stream()
                .filter(Objects::nonNull)
                .filter(node -> hasText(node.getTemplateName())
                        && node.getTemplateName().trim().equalsIgnoreCase(po.getName()))
                .sorted(Comparator.comparing(DeviceNodePo::getId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(node -> DeviceTemplateDeletionBlockerDto.builder()
                        .reasonCode("DEVICE_INSTANCE_USES_TEMPLATE")
                        .itemId(node.getId())
                        .itemLabel(firstText(node.getLabel(), node.getId()))
                        .reason("This canvas device instance uses the device type.")
                        .build())
                .toList();
        List<DeviceTemplateDto> currentTemplates = getDeviceTemplatesInternal(userId);
        BoardEditHistoryState editHistory = editJournal.historyState(userId);
        String impactToken = templateDeletionImpactToken(target, blockers, editHistory);
        DeviceTemplateDeletionResultDto preview = DeviceTemplateDeletionResultDto.builder()
                .operation("preview")
                .impactToken(impactToken)
                .canDelete(blockers.isEmpty())
                .editHistoryEntryCount(editHistory.entryCount())
                .template(target)
                .blockers(blockers)
                .currentTemplates(currentTemplates)
                .build();
        return new DeviceTemplateDeletionPlan(po, preview);
    }

    private String templateDeletionImpactToken(
            DeviceTemplateDto template,
            List<DeviceTemplateDeletionBlockerDto> blockers,
            BoardEditHistoryState editHistory) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("contract", "device-template-deletion-v2");
        impact.put("template", template);
        impact.put("blockers", blockers != null ? blockers : List.of());
        impact.put("editHistoryImpactToken", editHistory.impactToken());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtils.toJson(impact).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new InternalServerException("Unable to create template-deletion impact token", e);
        }
    }

    private record DeviceTemplateDeletionPlan(
            DeviceTemplatePo templatePo, DeviceTemplateDeletionResultDto preview) {
    }

    @Override
    public DefaultTemplateResetResultDto previewDefaultTemplateReset(Long userId) {
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                return buildDefaultTemplateResetPlan(userId).preview();
            });
        }
    }

    @Override
    public DefaultTemplateResetResultDto resetDefaultTemplates(Long userId, String expectedImpactToken) {
        if (!hasText(expectedImpactToken)) {
            throw new BadRequestException("Default-template reset impact token is required.");
        }
        synchronized (getUserWriteLock(userId)) {
            return transactionTemplate.execute(status -> {
                requireActiveUserForWrite(userId);
                DefaultTemplateResetPlan plan = buildDefaultTemplateResetPlan(userId);
                if (!Objects.equals(plan.preview().getImpactToken(), expectedImpactToken.trim())) {
                    throw new ConflictException(
                            "The device types, board, or undo history changed after the reset preview. "
                                    + "Review the current impact before resetting.");
                }
                if (!plan.validationErrors().isEmpty()) {
                    throw new ValidationException(plan.validationErrors());
                }

                Set<String> defaultNames = plan.defaultDefinitions().stream()
                        .map(DeviceTemplatePo::getName)
                        .filter(this::hasText)
                        .map(name -> name.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                int deleted = deviceTemplateRepo.deleteDefaultsForReset(userId, List.copyOf(defaultNames));
                List<DeviceTemplatePo> savedDefaults = deviceTemplateRepo.saveAllAndFlush(
                        plan.defaultDefinitions());
                if (savedDefaults.size() != plan.defaultDefinitions().size()) {
                    throw new InternalServerException(
                            "Default device type reset was incomplete; the transaction was rolled back.");
                }
                log.info("Reset default templates for user {}: deleted {}, imported {}",
                        userId, deleted, savedDefaults.size());

                for (DeviceTemplatePo saved : savedDefaults) {
                    DeviceTemplateDto dto = deviceTemplateMapper.toDto(saved);
                    templateNuSmvValidator.validateTemplateManifestForNuSmv(dto.getName(), dto.getManifest());
                    templateNuSmvValidator.runTemplateNuSmvPrecheck(userId, dto.getName(), dto.getManifest());
                }

                List<DeviceNodeDto> nodes = getNodesInternal(userId);
                validateBoardReferences(userId, nodes, getRulesInternal(userId), getSpecsInternal(userId));
                List<BoardEnvironmentVariableDto> savedEnvironment =
                        replaceEnvironmentVariablesInternal(userId, plan.projectedEnvironment());
                List<DeviceTemplateDto> currentTemplates = deviceTemplateRepo.findByUserId(userId).stream()
                        .map(deviceTemplateMapper::toDto)
                        .toList();
                editJournal.clear(userId);

                DefaultTemplateResetResultDto preview = plan.preview();
                return DefaultTemplateResetResultDto.builder()
                        .operation("reset")
                        .impactToken(preview.getImpactToken())
                        .canApply(true)
                        .editHistoryEntryCount(preview.getEditHistoryEntryCount())
                        .templateChanges(preview.getTemplateChanges())
                        .affectedDevices(preview.getAffectedDevices())
                        .blockers(List.of())
                        .environmentChanges(preview.getEnvironmentChanges())
                        .currentTemplates(currentTemplates)
                        .environmentVariables(savedEnvironment)
                        .build();
            });
        }
    }

    private DefaultTemplateResetPlan buildDefaultTemplateResetPlan(Long userId) {
        List<DeviceTemplatePo> defaultDefinitions = new ArrayList<>(
                deviceTemplateService.getDefaultTemplateDefinitions(userId));
        if (defaultDefinitions.isEmpty()) {
            throw new InternalServerException("No bundled default device types are available to reset.");
        }
        defaultDefinitions.sort(Comparator.comparing(
                template -> firstText(template.getName(), ""), String.CASE_INSENSITIVE_ORDER));
        List<DeviceTemplateDto> defaultDtos = defaultDefinitions.stream()
                .map(deviceTemplateMapper::toDto)
                .toList();
        for (DeviceTemplateDto dto : defaultDtos) {
            templateNuSmvValidator.validateTemplateManifestForNuSmv(dto.getName(), dto.getManifest());
        }

        List<DeviceTemplatePo> currentEntities = new ArrayList<>(deviceTemplateRepo.findByUserId(userId));
        currentEntities.sort(Comparator.comparing(
                template -> firstText(template != null ? template.getName() : null, ""),
                String.CASE_INSENSITIVE_ORDER));
        List<DeviceTemplateDto> currentTemplates = currentEntities.stream()
                .map(deviceTemplateMapper::toDto)
                .toList();
        Map<String, DeviceTemplatePo> currentByName = currentEntities.stream()
                .filter(Objects::nonNull)
                .filter(template -> hasText(template.getName()))
                .collect(Collectors.toMap(
                        template -> template.getName().trim().toLowerCase(Locale.ROOT),
                        template -> template,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Set<String> defaultNames = defaultDefinitions.stream()
                .map(DeviceTemplatePo::getName)
                .filter(this::hasText)
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<DefaultTemplateResetChangeDto> changes = new ArrayList<>();
        Map<String, Boolean> semanticsChangedByName = new HashMap<>();
        for (int index = 0; index < defaultDefinitions.size(); index++) {
            DeviceTemplatePo definition = defaultDefinitions.get(index);
            DeviceTemplateDto replacement = defaultDtos.get(index);
            String key = definition.getName().trim().toLowerCase(Locale.ROOT);
            DeviceTemplatePo existing = currentByName.get(key);
            DefaultTemplateResetChangeDto.ChangeType changeType;
            boolean semanticsChanged;
            if (existing == null) {
                changeType = DefaultTemplateResetChangeDto.ChangeType.RESTORE_MISSING;
                semanticsChanged = true;
            } else {
                DeviceTemplateDto current = deviceTemplateMapper.toDto(existing);
                semanticsChanged = !sameTemplateManifest(current, replacement);
                changeType = Boolean.TRUE.equals(existing.getDefaultTemplate())
                        ? DefaultTemplateResetChangeDto.ChangeType.REFRESH_DEFAULT
                        : DefaultTemplateResetChangeDto.ChangeType.REPLACE_CUSTOM_NAME_COLLISION;
            }
            changes.add(DefaultTemplateResetChangeDto.builder()
                    .templateName(definition.getName())
                    .changeType(changeType)
                    .semanticsChanged(semanticsChanged)
                    .build());
            semanticsChangedByName.put(key, semanticsChanged);
        }
        for (DeviceTemplatePo current : currentEntities) {
            if (current != null && Boolean.TRUE.equals(current.getDefaultTemplate())
                    && hasText(current.getName())
                    && !defaultNames.contains(current.getName().trim().toLowerCase(Locale.ROOT))) {
                String key = current.getName().trim().toLowerCase(Locale.ROOT);
                changes.add(DefaultTemplateResetChangeDto.builder()
                        .templateName(current.getName())
                        .changeType(DefaultTemplateResetChangeDto.ChangeType.REMOVE_OBSOLETE_DEFAULT)
                        .semanticsChanged(true)
                        .build());
                semanticsChangedByName.put(key, true);
            }
        }
        changes.sort(Comparator.comparing(DefaultTemplateResetChangeDto::getTemplateName,
                String.CASE_INSENSITIVE_ORDER));

        List<DeviceTemplateDto> prospectiveTemplates = new ArrayList<>();
        currentEntities.stream()
                .filter(Objects::nonNull)
                .filter(template -> !Boolean.TRUE.equals(template.getDefaultTemplate()))
                .filter(template -> hasText(template.getName()))
                .filter(template -> !defaultNames.contains(template.getName().trim().toLowerCase(Locale.ROOT)))
                .map(deviceTemplateMapper::toDto)
                .forEach(prospectiveTemplates::add);
        prospectiveTemplates.addAll(defaultDtos);
        Map<String, DeviceManifest> prospectiveManifests = templateManifestMap(prospectiveTemplates);

        List<DeviceNodeDto> nodes = getNodesInternal(userId).stream()
                .sorted(Comparator.comparing(node -> firstText(node != null ? node.getId() : null, "")))
                .toList();
        List<RuleDto> rules = getRulesInternal(userId);
        List<SpecificationDto> specs = getSpecsInternal(userId).stream()
                .sorted(Comparator.comparing(spec -> firstText(spec != null ? spec.getId() : null, "")))
                .toList();
        List<BoardEnvironmentVariableDto> previousEnvironment = getEnvironmentVariablesInternal(userId);
        List<BoardEnvironmentVariableDto> projectedEnvironment = projectEnvironmentVariablesForNodes(
                previousEnvironment, nodes, prospectiveManifests, true);
        List<EnvironmentVariableChangeDto> environmentChanges = withEnvironmentModelTokenSources(
                diffEnvironmentVariables(previousEnvironment, projectedEnvironment),
                environmentModelTokenSources(nodes, currentTemplates),
                environmentModelTokenSources(nodes, prospectiveTemplates));

        List<DefaultTemplateAffectedDeviceDto> affectedDevices = nodes.stream()
                .filter(Objects::nonNull)
                .filter(node -> hasText(node.getTemplateName()))
                .filter(node -> Boolean.TRUE.equals(semanticsChangedByName.get(
                        node.getTemplateName().trim().toLowerCase(Locale.ROOT))))
                .map(node -> DefaultTemplateAffectedDeviceDto.builder()
                        .deviceId(node.getId())
                        .deviceLabel(hasText(node.getLabel()) ? node.getLabel() : node.getTemplateName())
                        .templateName(node.getTemplateName())
                        .build())
                .toList();

        Map<String, String> validationErrors = new LinkedHashMap<>();
        if (prospectiveTemplates.size() > RequestLimits.MAX_TEMPLATES) {
            validationErrors.put("templates",
                    "Resetting bundled defaults would exceed the limit of 100 device types. Delete unused custom types first.");
        }
        try {
            validateBoardReferences(userId, nodes, rules, specs, prospectiveManifests);
        } catch (ValidationException e) {
            validationErrors.putAll(e.getErrors());
        }
        List<DefaultTemplateResetBlockerDto> blockers = resetBlockers(
                validationErrors, nodes, rules, specs);
        BoardEditHistoryState editHistory = editJournal.historyState(userId);
        String impactToken = defaultTemplateResetImpactToken(
                currentTemplates, defaultDtos, nodes, rules, specs, previousEnvironment,
                changes, environmentChanges, validationErrors, editHistory);

        DefaultTemplateResetResultDto preview = DefaultTemplateResetResultDto.builder()
                .operation("preview")
                .impactToken(impactToken)
                .canApply(validationErrors.isEmpty())
                .editHistoryEntryCount(editHistory.entryCount())
                .templateChanges(List.copyOf(changes))
                .affectedDevices(affectedDevices)
                .blockers(blockers)
                .environmentChanges(environmentChanges)
                .currentTemplates(currentTemplates)
                .environmentVariables(previousEnvironment)
                .build();
        return new DefaultTemplateResetPlan(
                preview, List.copyOf(defaultDefinitions), projectedEnvironment,
                Map.copyOf(validationErrors));
    }

    Map<String, ModelTokenSource> environmentModelTokenSources(
            List<DeviceNodeDto> nodes,
            List<DeviceTemplateDto> templates) {
        Map<String, DeviceTemplateDto> templatesByName = new HashMap<>();
        for (DeviceTemplateDto template : safeList(templates)) {
            if (template == null || template.getManifest() == null) continue;
            registerTemplateAlias(templatesByName, template.getName(), template);
            registerTemplateAlias(templatesByName, template.getManifest().getName(), template);
        }

        Map<String, ModelTokenSource> sources = new TreeMap<>();
        for (DeviceNodeDto node : safeList(nodes)) {
            if (node == null || !hasText(node.getTemplateName())) continue;
            DeviceTemplateDto template = templatesByName.get(
                    node.getTemplateName().trim().toLowerCase(Locale.ROOT));
            if (template == null) continue;
            ModelTokenSource providerSource = ModelTokenSource.fromDefaultTemplate(
                    template.getDefaultTemplate());
            for (String name : templateEnvironmentVariableNames(template.getManifest())) {
                sources.merge(name, providerSource, this::mergeEnvironmentModelTokenSources);
            }
        }
        return Map.copyOf(sources);
    }

    private void registerTemplateAlias(
            Map<String, DeviceTemplateDto> templatesByName,
            String name,
            DeviceTemplateDto template) {
        if (!hasText(name)) return;
        templatesByName.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), template);
    }

    private Set<String> templateEnvironmentVariableNames(DeviceManifest manifest) {
        Set<String> names = new TreeSet<>();
        if (manifest == null) return names;
        for (DeviceManifest.InternalVariable variable : safeList(manifest.getInternalVariables())) {
            if (variable != null && hasText(variable.getName()) && isEnvironmentVariable(variable)) {
                names.add(variable.getName().trim());
            }
        }
        for (String impacted : safeList(manifest.getImpactedVariables())) {
            if (hasText(impacted)
                    && EnvironmentDomainUtils.resolveImpactDomain(manifest, impacted) != null) {
                names.add(impacted.trim());
            }
        }
        return names;
    }

    private ModelTokenSource mergeEnvironmentModelTokenSources(
            ModelTokenSource first,
            ModelTokenSource second) {
        if (first == ModelTokenSource.BUNDLED && second == ModelTokenSource.BUNDLED) {
            return ModelTokenSource.BUNDLED;
        }
        if (first == ModelTokenSource.UNKNOWN || second == ModelTokenSource.UNKNOWN) {
            return ModelTokenSource.UNKNOWN;
        }
        return ModelTokenSource.CUSTOM;
    }

    private List<EnvironmentVariableChangeDto> withEnvironmentModelTokenSources(
            List<EnvironmentVariableChangeDto> changes,
            Map<String, ModelTokenSource> previousSources,
            Map<String, ModelTokenSource> currentSources) {
        return safeList(changes).stream()
                .filter(Objects::nonNull)
                .map(change -> EnvironmentVariableChangeDto.builder()
                        .changeType(change.getChangeType())
                        .name(change.getName())
                        .previousValue(change.getPreviousValue())
                        .currentValue(change.getCurrentValue())
                        .previousModelTokenSource(change.getPreviousValue() == null
                                ? ModelTokenSource.UNKNOWN
                                : previousSources.getOrDefault(change.getName(), ModelTokenSource.UNKNOWN))
                        .currentModelTokenSource(change.getCurrentValue() == null
                                ? ModelTokenSource.UNKNOWN
                                : currentSources.getOrDefault(change.getName(), ModelTokenSource.UNKNOWN))
                        .build())
                .toList();
    }

    private Map<String, DeviceManifest> templateManifestMap(List<DeviceTemplateDto> templates) {
        Map<String, DeviceManifest> manifests = new HashMap<>();
        for (DeviceTemplateDto template : templates == null ? List.<DeviceTemplateDto>of() : templates) {
            if (template == null || template.getManifest() == null || !hasText(template.getName())) {
                continue;
            }
            manifests.put(template.getName().trim().toLowerCase(Locale.ROOT), template.getManifest());
            if (hasText(template.getManifest().getName())) {
                manifests.put(template.getManifest().getName().trim().toLowerCase(Locale.ROOT),
                        template.getManifest());
            }
        }
        return manifests;
    }

    private List<DefaultTemplateResetBlockerDto> resetBlockers(
            Map<String, String> errors,
            List<DeviceNodeDto> nodes,
            List<RuleDto> rules,
            List<SpecificationDto> specs) {
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, DefaultTemplateResetBlockerDto> blockers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            String label = resetBlockerLabel(entry.getKey(), nodes, rules, specs);
            String key = label + "\n" + entry.getValue();
            blockers.putIfAbsent(key, DefaultTemplateResetBlockerDto.builder()
                    .itemLabel(label)
                    .reasonCode(resetBlockerReasonCode(entry.getKey()))
                    .reason(entry.getValue())
                    .build());
        }
        return List.copyOf(blockers.values());
    }

    private String resetBlockerReasonCode(String field) {
        String normalized = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("nodes[")) return "DEVICE_INSTANCE_INCOMPATIBLE";
        if (normalized.startsWith("rules[")) return "AUTOMATION_RULE_INCOMPATIBLE";
        if (normalized.startsWith("specs[") || normalized.startsWith("specifications[")) {
            return "SPECIFICATION_INCOMPATIBLE";
        }
        if (normalized.startsWith("environmentvariables[") || normalized.startsWith("environment.")) {
            return "ENVIRONMENT_POOL_INCOMPATIBLE";
        }
        return "BOARD_MODEL_INCOMPATIBLE";
    }

    private String resetBlockerLabel(String field,
                                     List<DeviceNodeDto> nodes,
                                     List<RuleDto> rules,
                                     List<SpecificationDto> specs) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(nodes|rules|specs)\\[(\\d+)]")
                .matcher(field == null ? "" : field);
        if (!matcher.find()) {
            return "Board model";
        }
        int index = Integer.parseInt(matcher.group(2));
        return switch (matcher.group(1)) {
            case "nodes" -> index < nodes.size() && nodes.get(index) != null
                    ? "Device: " + firstText(nodes.get(index).getLabel(), nodes.get(index).getTemplateName())
                    : "Device";
            case "rules" -> index < rules.size() && rules.get(index) != null
                    ? "Rule: " + firstText(rules.get(index).getRuleString(), "Rule " + (index + 1))
                    : "Rule " + (index + 1);
            case "specs" -> index < specs.size() && specs.get(index) != null
                    ? "Specification: " + specs.get(index).getName()
                    : "Specification " + (index + 1);
            default -> "Board model";
        };
    }

    private String firstText(String preferred, String fallback) {
        return hasText(preferred) ? preferred.trim() : (hasText(fallback) ? fallback.trim() : "Unknown item");
    }

    private String defaultTemplateResetImpactToken(
            List<DeviceTemplateDto> currentTemplates,
            List<DeviceTemplateDto> defaultTemplates,
            List<DeviceNodeDto> nodes,
            List<RuleDto> rules,
            List<SpecificationDto> specs,
            List<BoardEnvironmentVariableDto> environment,
            List<DefaultTemplateResetChangeDto> changes,
            List<EnvironmentVariableChangeDto> environmentChanges,
            Map<String, String> validationErrors,
            BoardEditHistoryState editHistory) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("contract", "default-template-reset-v2");
        impact.put("currentTemplates", currentTemplates);
        impact.put("defaultTemplates", defaultTemplates);
        impact.put("nodes", nodes);
        impact.put("rules", rules);
        impact.put("specifications", specs);
        impact.put("environmentVariables", environment);
        impact.put("templateChanges", changes);
        impact.put("environmentChanges", environmentChanges);
        impact.put("validationErrors", validationErrors);
        impact.put("editHistoryImpactToken", editHistory.impactToken());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtils.toJson(impact).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new InternalServerException("Unable to create default-template reset impact token", e);
        }
    }

    private record DefaultTemplateResetPlan(
            DefaultTemplateResetResultDto preview,
            List<DeviceTemplatePo> defaultDefinitions,
            List<BoardEnvironmentVariableDto> projectedEnvironment,
            Map<String, String> validationErrors) {
    }

}
