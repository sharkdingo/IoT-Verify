package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.RuleFixer;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyApplier;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceReferenceResolver;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.fix.FaultLocalizationResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixApplyResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixStrategyAttemptDto;
import cn.edu.nju.Iot_Verify.dto.fix.ConditionAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterTarget;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.dto.fix.TemplateSnapshotComparison;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.model.TemplateSnapshotBundleDto;
import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStage;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.FixApplyPreflightUnavailableException;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.repository.TraceRepository;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.FormalOperationAdmission;
import cn.edu.nju.Iot_Verify.service.FixService;
import cn.edu.nju.Iot_Verify.service.FixSuggestionTokenService;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.BoardSemanticFingerprint;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import cn.edu.nju.Iot_Verify.util.mapper.TraceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class FixServiceImpl implements FixService {

    private static final List<String> DEFAULT_FIX_STRATEGIES = List.of("parameter", "condition", "remove");
    private static final Set<String> SUPPORTED_FIX_STRATEGIES = Set.copyOf(DEFAULT_FIX_STRATEGIES);

    private final TraceRepository traceRepository;
    private final TraceMapper traceMapper;
    private final SmvGenerator smvGenerator;
    private final RuleFixer ruleFixer;
    private final FixConfig fixConfig;
    private final BoardStorageService boardStorageService;
    private final BoardDataConverter boardDataConverter;
    private final FixSuggestionTokenService fixSuggestionTokenService;
    private final FormalOperationAdmission formalOperationAdmission;

    @Override
    @Transactional(readOnly = true)
    public FaultLocalizationResultDto localizeFault(Long userId, Long traceId) {
        VerificationContext ctx = loadContext(userId, traceId);
        ModelBoundaryInput modelInput = modelBoundaryInput(
                ctx.request, ctx.templateManifests, ctx.modelTokenSourcesByDeviceId);
        List<FaultRuleDto> faultRules = ruleFixer.localizeFaults(
                ctx.trace.getStates(), ctx.request.getRules(), ctx.trace.getViolatedSpecId(),
                ctx.request.getSpecs(), modelInput.deviceSmvMap());
        attachModelTokenSources(faultRules, modelInput.deviceSmvMap());
        boolean modelComplete = sourceModelComplete(ctx.trace);
        String summary = faultRules.isEmpty()
                ? "No automation rule that fired in the counterexample can influence the violated property. "
                    + "The violation depends on device or environment evolution."
                : faultRules.size() + " automation rule(s) fired in the counterexample and can influence the "
                    + "violated property. This narrows review but does not prove that every listed rule "
                    + "independently caused the violation.";
        List<String> warnings = modelComplete
                ? List.of()
                : List.of(incompleteSourceModelWarning(ctx.trace));
        return FaultLocalizationResultDto.builder()
                .traceId(traceId)
                .violatedSpecId(ctx.trace.getViolatedSpecId())
                .sourceModelComplete(modelComplete)
                .sourceDisabledRuleCount(sourceDisabledRuleCount(ctx.trace))
                .sourceSkippedSpecCount(sourceSkippedSpecCount(ctx.trace))
                .sourceGenerationIssues(sourceGenerationIssues(ctx.trace))
                .faultRules(faultRules)
                .summary(summary)
                .warnings(warnings)
                .build();
    }

    @Override
    public FixResultDto fix(Long userId, Long traceId, List<String> strategies,
                            Map<String, PreferredRange> preferredRanges) {
        return fix(userId, traceId, strategies, preferredRanges, stage -> { });
    }

    @Override
    public FixResultDto fix(Long userId, Long traceId, List<String> strategies,
                            Map<String, PreferredRange> preferredRanges,
                            Consumer<InteractiveOperationStage> progressListener) {
        return formalOperationAdmission.execute(userId,
                () -> fixWithoutAdmission(userId, traceId, strategies, preferredRanges, progressListener));
    }

    private FixResultDto fixWithoutAdmission(Long userId, Long traceId, List<String> strategies,
                                             Map<String, PreferredRange> preferredRanges,
                                             Consumer<InteractiveOperationStage> progressListener) {
        Consumer<InteractiveOperationStage> progress = Objects.requireNonNull(progressListener);
        progress.accept(InteractiveOperationStage.PREPARING_CONTEXT);
        strategies = validateRequestedStrategies(strategies);
        validatePreferredRanges(preferredRanges);
        VerificationContext ctx = loadContext(userId, traceId);
        VerificationRequestDto req = ctx.request;
        progress.accept(InteractiveOperationStage.PREPARING_MODEL);
        ModelBoundaryInput modelInput = modelBoundaryInput(
                req, ctx.templateManifests, ctx.modelTokenSourcesByDeviceId);
        Map<String, DeviceSmvData> deviceSmvMap = modelInput.deviceSmvMap();

        if (!sourceModelComplete(ctx.trace)) {
            progress.accept(InteractiveOperationStage.FINALIZING);
            return incompleteSourceModelResult(
                    traceId, ctx, strategies, deviceSmvMap, preferredRanges);
        }

        progress.accept(InteractiveOperationStage.SEARCHING_AND_VERIFYING);
        FixResultDto result = runFixer(
                traceId,
                ctx.trace.getViolatedSpecId(),
                ctx.trace.getStates(),
                req.getRules(),
                modelInput.devices(),
                modelInput.environmentVariables(),
                req.getSpecs(),
                deviceSmvMap,
                userId,
                req.resolvedAttackScenario(),
                req.isEnablePrivacy(),
                strategies,
                fixConfig.getMaxAttempts(),
                preferredRanges
        );

        progress.accept(InteractiveOperationStage.FINALIZING);
        appendDriftWarningIfNeeded(result, userId, ctx);
        applySourceModelMetadata(result, ctx.trace);
        if (result.getSuggestions() != null) {
            result.getSuggestions().stream()
                    .filter(Objects::nonNull)
                    .forEach(suggestion -> suggestion.setSuggestionToken(
                            fixSuggestionTokenService.issue(
                                    userId, traceId, suggestion, preferredRanges)));
        }

        return result;
    }

    @Override
    public FixApplyResultDto applyFix(Long userId, Long traceId, String strategy,
                                      FixSuggestionDto suggestion, String suggestionToken,
                                      Map<String, PreferredRange> preferredRanges) {
        return formalOperationAdmission.execute(userId, () -> {
            String validatedStrategy = validateApplyStrategy(strategy);
            validatePreferredRanges(preferredRanges);
            if (suggestion == null || suggestion.getStrategy() == null
                    || !validatedStrategy.equals(suggestion.getStrategy())) {
                throw new BadRequestException("The submitted suggestion does not match the selected strategy.");
            }
            // The token is issued only for suggestions the fixer listed, and it lists only suggestions
            // that forward verification accepted; a valid token is therefore the proof of verification.
            FixSuggestionDto trusted = fixSuggestionTokenService.verify(
                    userId, traceId, validatedStrategy, suggestion, suggestionToken, preferredRanges);
            return applyFixInternal(userId, traceId, validatedStrategy, trusted, preferredRanges);
        });
    }

    private FixApplyResultDto applyFixInternal(Long userId, Long traceId, String strategy,
                                               FixSuggestionDto trusted,
                                               Map<String, PreferredRange> preferredRanges) {
        String validatedStrategy = validateApplyStrategy(strategy);
        validatePreferredRanges(preferredRanges);
        // Load the trace's verification-time snapshot (normalized rules) for index/fingerprint alignment.
        VerificationContext ctx = loadContext(userId, traceId);
        assertCompleteSourceForApply(ctx.trace);
        List<RuleDto> snapshotRules = ctx.request.getRules();
        if (snapshotRules == null || snapshotRules.isEmpty()) {
            throw new BadRequestException("Verification context has no rules; cannot apply fix.");
        }

        ModelBoundaryInput modelInput = modelBoundaryInput(
                ctx.request, ctx.templateManifests, ctx.modelTokenSourcesByDeviceId);
        Map<String, DeviceSmvData> deviceSmvMap = modelInput.deviceSmvMap();

        FixSuggestionDto suggestionToApply = trusted;

        // Read current rules → drift check → apply → save, all inside ONE per-user lock + transaction.
        // This closes the race where a concurrent save could interleave between an unlocked read and the
        // locked write, letting apply overwrite freshly-saved rules with a stale list. The drift check
        // runs on the exact snapshot that gets written.
        int[] before = {0};
        CollectionMutationResultDto<RuleDto> mutation =
                boardStorageService.updateRulesAgainstSnapshot(userId, boardSnapshot -> {
                    // The rule write happens in BoardStorageService's transactionTemplate. Register the
                    // fence from inside that transaction so a lease loss cannot commit this public REST
                    // entry after another formal operation has claimed the user.
                    formalOperationAdmission.registerCurrentLeaseCommitFence();
                    List<RuleDto> boardRules = boardSnapshot.rules();
                    before[0] = boardRules.size();
                    ModelInputSnapshot currentSnapshot = boardDataConverter.toModelInputSnapshot(boardSnapshot);
                    assertTemplatesUnchanged(ctx, currentSnapshot.templateManifests());
                    // Spec/device drift guard: run inside the same per-user write lock as the final save, so a
                    // concurrent spec/device edit cannot slip in after the check but before persistence.
                    CurrentBoardSemanticContext currentBoard = assertSpecsAndDevicesUnchanged(
                            currentSnapshot, ctx, deviceSmvMap);
                    // Guard against board drift: the snapshot the fix was computed against must still line up
                    // with the current board rules by ordered fingerprint. Internal fix coordinates must never
                    // be allowed to target a different rule or condition after the board changes.
                    assertBoardAlignedWithSnapshot(boardRules, snapshotRules, deviceSmvMap);
                    // Apply the exact signed suggestion shown to the user. Snapshot checks above ensure its
                    // existing NuSMV evidence still describes the model being changed.
                    Map<String, String> persistenceDeviceRefs = buildPersistenceDeviceRefAliases(
                            currentSnapshot.nodes(), deviceSmvMap, currentBoard.currentDeviceSmvMap());
                    Map<String, String> displayDeviceNames = "remove".equals(validatedStrategy)
                            ? Map.of()
                            : buildDisplayDeviceNames(currentSnapshot.nodes());
                    return FixStrategyApplier.apply(
                            validatedStrategy, suggestionToApply, boardRules, deviceSmvMap,
                            persistenceDeviceRefs, displayDeviceNames);
                });
        List<RuleDto> saved = mutation.getCurrentItems();
        log.info("Applied '{}' fix for trace {} (user {}): {} rule(s) -> {} rule(s)",
                validatedStrategy, traceId, userId, before[0], saved.size());

        return FixApplyResultDto.builder()
                .applied(true)
                .strategy(validatedStrategy)
                .verificationEvidenceReused(true)
                .appliedSuggestion(suggestionToApply)
                .previousRuleCount(before[0])
                .currentRuleCount(saved.size())
                .message(buildApplyMessage(validatedStrategy, suggestionToApply, before[0], saved.size()))
                .rules(saved)
                .canUndo(Boolean.TRUE.equals(mutation.getCanUndo()))
                .canRedo(Boolean.TRUE.equals(mutation.getCanRedo()))
                .build();
    }

    /**
     * Ensure the current board rules still align with the trace snapshot the fix was computed against.
     * Alignment = same rule count AND each rule's semantic fingerprint matches position-by-position.
     * If the user edited/added/removed rules after verifying, internal suggestion coordinates would be
     * stale, so we reject rather than risk editing the wrong rule.
     */
    private void assertBoardAlignedWithSnapshot(List<RuleDto> boardRules, List<RuleDto> snapshotRules,
                                                Map<String, DeviceSmvData> deviceSmvMap) {
        if (boardRules.size() != snapshotRules.size()) {
            throw new BadRequestException("Board rules changed since verification ("
                    + snapshotRules.size() + " rules verified, " + boardRules.size()
                    + " now). Please re-run verification before applying a fix.");
        }
        for (int i = 0; i < boardRules.size(); i++) {
            String boardFp = ruleFingerprint(boardRules.get(i), deviceSmvMap);
            String snapFp = ruleFingerprint(snapshotRules.get(i), deviceSmvMap);
            if (!Objects.equals(boardFp, snapFp)) {
                throw new BadRequestException(ruleDriftLabel(boardRules.get(i), i)
                        + " changed since verification. "
                        + "Please re-run verification before applying a fix.");
            }
        }
    }

    /** Reject apply unless the current manifests can be proven equal to the frozen run snapshot. */
    private void assertTemplatesUnchanged(
            VerificationContext ctx,
            Map<String, DeviceManifest> currentTemplateManifests) {
        TemplateSnapshotComparison comparison = compareTemplateSnapshots(
                ctx.templateManifests, currentTemplateManifests);
        if (comparison == TemplateSnapshotComparison.CHANGED) {
            throw new BadRequestException("Device template(s) were modified after this trace was recorded. "
                    + "The fix may no longer match the verification model. "
                    + "Please re-run verification before applying a fix.");
        }
        if (comparison == TemplateSnapshotComparison.UNAVAILABLE) {
            throw new FixApplyPreflightUnavailableException("Could not confirm whether the current device templates "
                    + "still match this verification run. Please retry applying the fix later.");
        }
    }

    /**
     * Reject the apply if the user edited specs or device instance state (variables, privacies, initial
     * state, trust) after the trace was recorded. The server recompute replays the trace's stored
     * context, so it cannot detect these edits on its own; without this guard a fix verified against a
     * stale spec/device model would be silently persisted.
     *
     * <p>Compares a canonical SEMANTIC fingerprint of the trace's snapshot against the current board.
     * Both sides are canonicalized identically ({@link BoardSemanticFingerprint}) — device names
     * normalized, effective variable/trust/privacy values derived from the same manifests NuSMV uses,
     * values de-quoted — so an untouched board matches its model-boundary normalized snapshot instead of
     * misfiring (the failure mode of the earlier byte-equality attempt). {@code snapshotDeviceSmvMap}
     * was built from the snapshot devices; the current board gets its own map so omitted instance
     * overrides resolve against the same manifests.</p>
     */
    private CurrentBoardSemanticContext assertSpecsAndDevicesUnchanged(
            ModelInputSnapshot currentBoard,
            VerificationContext ctx,
            Map<String, DeviceSmvData> snapshotDeviceSmvMap) {
        List<DeviceVerificationDto> currentDevices = currentBoard.devices();
        List<SpecificationDto> currentSpecs = currentBoard.specifications();
        assertCompleteSourceForApply(ctx.trace);

        Map<String, DeviceSmvData> currentDeviceSmvMap;
        List<BoardEnvironmentVariableDto> currentEnvironmentVariables = currentBoard.environmentVariables();
        try {
            currentDeviceSmvMap = smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(
                    currentDevices, currentBoard.templateManifests());
        } catch (SmvGenerationException e) {
            if (isTransientCurrentDeviceModelFailure(e)) {
                log.warn("Spec/device drift check: current board device model could not be confirmed; "
                        + "failing closed [{}]: {}", e.getErrorCategory(), e.getMessage());
                throw new FixApplyPreflightUnavailableException("Could not confirm the current board device model. "
                        + "Please retry applying the fix later.", e);
            }
            throw currentBoardDeviceModelChanged(e);
        } catch (BadRequestException e) {
            throw currentBoardDeviceModelChanged(e);
        } catch (Exception e) {
            // We cannot tell whether the current board still matches the verified snapshot. Fail closed,
            // but report a retryable service issue instead of telling the user their board changed.
            log.warn("Spec/device drift check: current board device model check failed unexpectedly; "
                            + "failing closed: {}",
                    e.getMessage());
            throw new FixApplyPreflightUnavailableException("Could not confirm the current board device model. "
                    + "Please retry applying the fix later.", e);
        }

        String snapshotFp = BoardSemanticFingerprint.of(
                ctx.request.getDevices(), ctx.request.getSpecs(), ctx.request.getEnvironmentVariables(),
                snapshotDeviceSmvMap);
        String currentFp = BoardSemanticFingerprint.of(
                currentDevices, currentSpecs, currentEnvironmentVariables, currentDeviceSmvMap);

        if (!snapshotFp.equals(currentFp)) {
            log.info("Spec/device drift detected for trace {}", ctx.trace.getId());
            throw new BadRequestException("Specifications or device state changed since verification. "
                    + "The fix was verified against the earlier model. "
                    + "Please re-run verification before applying a fix.");
        }
        return new CurrentBoardSemanticContext(currentDevices, currentDeviceSmvMap);
    }

    private String ruleDriftLabel(RuleDto rule, int index) {
        String description = rule == null ? null : rule.getRuleString();
        if (description != null && !description.isBlank()) {
            return "Automation rule \"" + description.trim() + "\" (position " + (index + 1) + ")";
        }
        return "The automation rule at position " + (index + 1);
    }

    private boolean isTransientCurrentDeviceModelFailure(SmvGenerationException e) {
        return e != null
                && SmvGenerationException.ErrorCategories.TEMPLATE_LOAD_ERROR.equals(e.getErrorCategory());
    }

    private BadRequestException currentBoardDeviceModelChanged(Exception e) {
        log.warn("Spec/device drift check: current board failed to build a valid device model; "
                + "failing closed: {}", e.getMessage());
        return new BadRequestException("The board's devices changed since verification and no longer "
                + "form a valid model. Please re-run verification before applying a fix.", e);
    }

    /**
     * Build aliases from model-boundary device references back to persisted board node ids.
     *
     * <p>{@link BoardDataConverter} intentionally normalizes raw node ids to NuSMV-safe
     * {@code varName}s. Those names are valid for model generation, but they are not necessarily
     * valid values for the board rules table (for example, a UUID with '-' becomes a value with
     * '_'). The raw node snapshot is therefore the persistence authority for fix application.</p>
     */
    private Map<String, String> buildPersistenceDeviceRefAliases(
            List<DeviceNodeDto> currentNodes,
            Map<String, DeviceSmvData> snapshotDeviceSmvMap,
            Map<String, DeviceSmvData> currentDeviceSmvMap) {
        Map<String, String> aliases = new LinkedHashMap<>();
        if (currentNodes == null || currentNodes.isEmpty()) {
            return aliases;
        }
        for (DeviceNodeDto node : currentNodes) {
            if (node == null) continue;
            String persistedRef = trimToNull(node.getId());
            if (persistedRef == null) continue;
            putAlias(aliases, persistedRef, persistedRef);
            putAlias(aliases, DeviceNameNormalizer.normalize(persistedRef), persistedRef);
            putResolvedAlias(aliases, persistedRef, snapshotDeviceSmvMap);
            putResolvedAlias(aliases, persistedRef, currentDeviceSmvMap);
        }
        return aliases;
    }

    private Map<String, String> buildDisplayDeviceNames(List<DeviceNodeDto> nodes) {
        Map<String, String> displayNames = new LinkedHashMap<>();
        for (DeviceNodeDto node : nodes == null ? List.<DeviceNodeDto>of() : nodes) {
            if (node == null) continue;
            String id = trimToNull(node.getId());
            String label = trimToNull(node.getLabel());
            if (id == null || label == null) continue;
            displayNames.putIfAbsent(id, label);
            displayNames.putIfAbsent(DeviceNameNormalizer.normalize(id), label);
        }
        return displayNames;
    }

    private void putResolvedAlias(Map<String, String> aliases, String persistedRef,
                                  Map<String, DeviceSmvData> deviceSmvMap) {
        try {
            DeviceSmvData smv = DeviceReferenceResolver.resolve(
                    persistedRef, deviceSmvMap);
            if (smv != null) {
                putAlias(aliases, smv.getVarName(), persistedRef);
            }
        } catch (Exception e) {
            log.debug("Could not build persistence alias for device '{}': {}", persistedRef, e.getMessage());
        }
    }

    private void putAlias(Map<String, String> aliases, String alias, String persistedRef) {
        String key = trimToNull(alias);
        String value = trimToNull(persistedRef);
        if (key != null && value != null) {
            aliases.putIfAbsent(key, value);
        }
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Fingerprint of a rule = command(deviceName|action) + ORDER-PRESERVING condition fingerprints.
     * Uses the same SMV varName resolution the fixer uses, so normalized snapshot names and raw board
     * names compare equal when they denote the same device.
     *
     * <p>Condition order is preserved (not sorted): the fix's {@code conditionIndex} is positional,
     * so a board rule whose conditions were merely reordered must NOT pass the drift check — otherwise
     * apply would edit/remove the wrong condition.</p>
     */
    private String ruleFingerprint(RuleDto rule, Map<String, DeviceSmvData> deviceSmvMap) {
        StringBuilder sb = new StringBuilder();
        RuleDto.Command cmd = rule.getCommand();
        if (cmd != null) {
            sb.append(resolveVar(cmd.getDeviceName(), deviceSmvMap))
              .append('#').append(cmd.getAction() == null ? "" : cmd.getAction())
              // contentDevice/content drive privacy content migration in SMV generation, so a change to
              // them is a real rule change that must fail the drift check (resolve the device ref so a
              // renamed contentDevice compares equal only when it denotes the same device).
              .append('#').append(cmd.getContentDevice() == null ? ""
                      : resolveVar(cmd.getContentDevice(), deviceSmvMap))
              .append('#').append(cmd.getContent() == null ? "" : cmd.getContent());
        }
        sb.append("=>");
        List<String> condFps = new ArrayList<>();
        if (rule.getConditions() != null) {
            for (RuleDto.Condition c : rule.getConditions()) {
                condFps.add(FixStrategyApplier.conditionFingerprint(normalizeConditionDeviceName(c), deviceSmvMap));
            }
        }
        // Positional join — do NOT sort; conditionIndex alignment depends on order.
        sb.append(String.join(",", condFps));
        return sb.toString();
    }

    /**
     * Resolve a raw board node id to the SMV-safe varName used by the trace snapshot.
     */
    private String resolveVar(String deviceName, Map<String, DeviceSmvData> deviceSmvMap) {
        return FixStrategyApplier.resolveVarName(DeviceNameNormalizer.normalize(deviceName), deviceSmvMap);
    }

    /**
     * Return a copy of the condition with its deviceName normalized to the SMV-safe varName, so
     * fingerprints from raw board rules line up with the normalized snapshot. The original condition
     * is not mutated.
     */
    private RuleDto.Condition normalizeConditionDeviceName(RuleDto.Condition c) {
        if (c == null) return null;
        String normalized = DeviceNameNormalizer.normalize(c.getDeviceName());
        if (Objects.equals(normalized, c.getDeviceName())) {
            return c;
        }
        return RuleDto.Condition.builder()
                .deviceName(normalized)
                .attribute(c.getAttribute())
                .targetType(c.getTargetType())
                .relation(c.getRelation())
                .value(c.getValue())
                .build();
    }

    private String buildApplyMessage(String strategy, FixSuggestionDto suggestion,
                                     int before, int after) {
        String evidence = " using the signed verification evidence after drift checks.";
        switch (strategy) {
            case "parameter":
                int pCount = suggestion.getParameterAdjustments() == null ? 0
                        : suggestion.getParameterAdjustments().size();
                return "Applied " + pCount + " parameter adjustment(s)" + evidence;
            case "condition":
                int cCount = suggestion.getConditionAdjustments() == null ? 0
                        : (int) suggestion.getConditionAdjustments().stream()
                                .filter(a -> !"keep".equals(a.getAction())).count();
                return "Applied " + cCount + " condition change(s)" + evidence;
            case "remove":
                return "Permanently removed " + (before - after) + " automation rule(s)" + evidence;
            default:
                return "Fix applied" + evidence;
        }
    }

    private void appendDriftWarningIfNeeded(FixResultDto result, Long userId,
                                            VerificationContext ctx) {
        TemplateSnapshotComparison comparison;
        try {
            comparison = compareTemplateSnapshots(
                    ctx.templateManifests,
                    boardDataConverter.getModelInputSnapshot(userId).templateManifests());
        } catch (Exception e) {
            log.warn("Template snapshot comparison is unavailable: {}", e.getMessage());
            comparison = TemplateSnapshotComparison.UNAVAILABLE;
        }
        result.setTemplateSnapshotComparison(comparison);
        if (comparison == TemplateSnapshotComparison.UNCHANGED) return;

        String warning = comparison == TemplateSnapshotComparison.CHANGED
                ? "WARNING: Current device template(s) differ from the run snapshot. Suggestions were "
                    + "generated from the earlier frozen model and cannot be applied until verification "
                    + "is run again on the current board."
                : "WARNING: Current device templates could not be compared with the run snapshot. "
                    + "Suggestions were generated from the earlier frozen model, but apply will remain "
                    + "blocked until the comparison can be completed.";
        String base = result.getSummary() != null ? result.getSummary() : "";
        result.setSummary(base.isEmpty() ? warning : base + " " + warning);
        addResultWarning(result, warning);
    }

    private FixResultDto incompleteSourceModelResult(Long traceId,
                                                     VerificationContext ctx,
                                                     List<String> strategies,
                                                     Map<String, DeviceSmvData> deviceSmvMap,
                                                     Map<String, PreferredRange> preferredRanges) {
        int disabledRules = sourceDisabledRuleCount(ctx.trace);
        int skippedSpecs = sourceSkippedSpecCount(ctx.trace);
        String warning = incompleteSourceModelWarning(ctx.trace);
        List<String> effectiveStrategies = strategies != null && !strategies.isEmpty()
                ? strategies : DEFAULT_FIX_STRATEGIES;
        List<FixStrategyAttemptDto> attempts = effectiveStrategies.stream()
                .map(strategy -> FixStrategyAttemptDto.builder()
                        .strategy(strategy)
                        .status("SKIPPED_INCOMPLETE_SOURCE_MODEL")
                        .reason(warning)
                        .build())
                .toList();
        List<FaultRuleDto> faultRules = ruleFixer.localizeFaults(
                ctx.trace.getStates(), ctx.request.getRules(), ctx.trace.getViolatedSpecId(),
                ctx.request.getSpecs(), deviceSmvMap);
        attachModelTokenSources(faultRules, deviceSmvMap);
        return FixResultDto.builder()
                .traceId(traceId)
                .violatedSpecId(ctx.trace.getViolatedSpecId())
                .faultRules(faultRules)
                .suggestions(List.of())
                .strategyAttempts(attempts)
                .fixable(false)
                .sourceModelComplete(false)
                .sourceDisabledRuleCount(disabledRules)
                .sourceSkippedSpecCount(skippedSpecs)
                .sourceGenerationIssues(sourceGenerationIssues(ctx.trace))
                .summary(warning)
                .warnings(List.of(warning))
                // The search never ran, so no selection was honoured. Reporting none would drop a
                // threshold the user explicitly pinned without saying so.
                .unusedPreferredRangeSelections(
                        RuleFixer.unusedPreferredRangeSelections(preferredRanges, Set.of()))
                .build();
    }

    private void applySourceModelMetadata(FixResultDto result, TraceDto trace) {
        if (result == null) return;
        result.setSourceModelComplete(sourceModelComplete(trace));
        result.setSourceDisabledRuleCount(sourceDisabledRuleCount(trace));
        result.setSourceSkippedSpecCount(sourceSkippedSpecCount(trace));
        result.setSourceGenerationIssues(sourceGenerationIssues(trace));
    }

    private boolean sourceModelComplete(TraceDto trace) {
        return trace != null && Boolean.TRUE.equals(trace.getModelComplete());
    }

    private int sourceDisabledRuleCount(TraceDto trace) {
        return trace != null && trace.getDisabledRuleCount() != null ? trace.getDisabledRuleCount() : 0;
    }

    private int sourceSkippedSpecCount(TraceDto trace) {
        return trace != null && trace.getSkippedSpecCount() != null ? trace.getSkippedSpecCount() : 0;
    }

    private List<ModelGenerationIssueDto> sourceGenerationIssues(TraceDto trace) {
        return trace != null && trace.getGenerationIssues() != null
                ? List.copyOf(trace.getGenerationIssues())
                : List.of();
    }

    private String incompleteSourceModelWarning(TraceDto trace) {
        if (trace == null || trace.getModelComplete() == null) {
            return "Automatic fix was not attempted because the source verification does not contain explicit "
                    + "model-completeness metadata. Verify the current board again before requesting a fix.";
        }
        return "Automatic fix was not attempted because the source verification used an incomplete generated "
                + "model (" + sourceDisabledRuleCount(trace) + " rule(s) disabled, "
                + sourceSkippedSpecCount(trace) + " specification(s) skipped). Resolve the itemized generation "
                + "issues and verify again first.";
    }

    private void assertCompleteSourceForApply(TraceDto trace) {
        if (sourceModelComplete(trace)) {
            return;
        }
        if (trace == null || trace.getModelComplete() == null) {
            throw new BadRequestException("Cannot apply an automatic fix because the source verification does not "
                    + "contain explicit model-completeness metadata. Verify the current board again first.");
        }
        throw new BadRequestException("Cannot apply an automatic fix from this trace because its source verification "
                + "used an incomplete generated model ("
                + sourceDisabledRuleCount(trace) + " rule(s) disabled, "
                + sourceSkippedSpecCount(trace) + " specification(s) skipped). Resolve the itemized generation "
                + "issues and verify the current board again first.");
    }

    private void addResultWarning(FixResultDto result, String warning) {
        List<String> warnings = new ArrayList<>(result.getWarnings() != null ? result.getWarnings() : List.of());
        if (!warnings.contains(warning)) warnings.add(warning);
        result.setWarnings(warnings);
    }

    /** Compare the persisted manifest projection rather than Java object identity. */
    private TemplateSnapshotComparison compareTemplateSnapshots(
            Map<String, DeviceManifest> verificationTemplateSnapshots,
            Map<String, DeviceManifest> currentTemplateSnapshots) {
        if (verificationTemplateSnapshots == null || verificationTemplateSnapshots.isEmpty()
                || currentTemplateSnapshots == null) {
            return TemplateSnapshotComparison.UNAVAILABLE;
        }
        boolean drifted = !verificationTemplateSnapshots.keySet().equals(currentTemplateSnapshots.keySet())
                || verificationTemplateSnapshots.entrySet().stream()
                        .anyMatch(entry -> !sameTemplateManifest(
                                entry.getValue(), currentTemplateSnapshots.get(entry.getKey())));
        if (drifted) {
            log.warn("Template drift detected by manifest comparison");
        }
        return drifted ? TemplateSnapshotComparison.CHANGED : TemplateSnapshotComparison.UNCHANGED;
    }

    private boolean sameTemplateManifest(DeviceManifest first, DeviceManifest second) {
        if (first == null || second == null) {
            return first == second;
        }
        return Objects.equals(JsonUtils.toJson(first), JsonUtils.toJson(second));
    }

    private List<String> validateRequestedStrategies(List<String> strategies) {
        if (strategies == null) {
            return null;
        }
        if (strategies.isEmpty()) {
            throw new BadRequestException(
                    "strategies must be non-empty when provided; omit it to use the default order");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < strategies.size(); i++) {
            String strategy = strategies.get(i);
            if (strategy == null || strategy.isBlank()) {
                throw new BadRequestException("strategies[" + i + "] must not be blank");
            }
            if (!SUPPORTED_FIX_STRATEGIES.contains(strategy)) {
                throw new BadRequestException("Unsupported strategy '" + strategy
                        + "'. Allowed: parameter, condition, remove.");
            }
            if (!seen.add(strategy)) {
                throw new BadRequestException("Duplicate strategy '" + strategy + "'.");
            }
        }
        return List.copyOf(strategies);
    }

    private String validateApplyStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            throw new BadRequestException("strategy must not be blank");
        }
        if (!SUPPORTED_FIX_STRATEGIES.contains(strategy)) {
            throw new BadRequestException("Unsupported strategy '" + strategy
                    + "'. Allowed: parameter, condition, remove.");
        }
        return strategy;
    }

    private void validatePreferredRanges(Map<String, PreferredRange> ranges) {
        if (ranges == null) return;
        for (Map.Entry<String, PreferredRange> entry : ranges.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                throw new BadRequestException("preferred range targetId must not be null");
            }
            PreferredRange pr = entry.getValue();
            if (!PreferredRangeSelection.isValidTargetId(key)) {
                throw new BadRequestException("Invalid preferred range targetId '" + key + "'");
            }
            if (pr == null) {
                throw new BadRequestException("preferred range value for targetId '" + key + "' must not be null");
            }
            if (pr.getLower() == null || pr.getUpper() == null) {
                throw new BadRequestException("preferred range entry for targetId '" + key
                        + "': lower and upper must not be null");
            }
            if (pr.getLower() > pr.getUpper()) {
                throw new BadRequestException("Invalid preferred range for targetId '" + key
                        + "': lower(" + pr.getLower() + ") > upper(" + pr.getUpper() + ")");
            }
        }
    }

    private void attachModelTokenSources(
            FixResultDto result,
            List<RuleDto> rules,
            Map<String, DeviceSmvData> deviceSmvMap) {
        if (result == null) {
            return;
        }
        attachModelTokenSources(result.getFaultRules(), deviceSmvMap);
        if (result.getParameterTargets() != null) {
            for (ParameterTarget target : result.getParameterTargets()) {
                if (target != null) {
                    target.setModelTokenSource(modelTokenSourceForCondition(
                            rules, target.getRuleIndex(), target.getConditionIndex(), deviceSmvMap));
                }
            }
        }
        if (result.getSuggestions() == null) {
            return;
        }
        for (FixSuggestionDto suggestion : result.getSuggestions()) {
            if (suggestion == null) {
                continue;
            }
            if (suggestion.getParameterAdjustments() != null) {
                for (ParameterAdjustment adjustment : suggestion.getParameterAdjustments()) {
                    if (adjustment != null) {
                        adjustment.setModelTokenSource(modelTokenSourceForCondition(
                                rules, adjustment.getRuleIndex(), adjustment.getConditionIndex(), deviceSmvMap));
                    }
                }
            }
            if (suggestion.getConditionAdjustments() != null) {
                for (ConditionAdjustment adjustment : suggestion.getConditionAdjustments()) {
                    if (adjustment == null) {
                        continue;
                    }
                    String deviceName = trimToNull(adjustment.getDeviceName());
                    ModelTokenSource source = deviceName != null
                            ? modelTokenSourceForDevice(deviceName, deviceSmvMap)
                            : modelTokenSourceForCondition(
                                    rules, adjustment.getRuleIndex(), adjustment.getConditionIndex(), deviceSmvMap);
                    adjustment.setModelTokenSource(source);
                }
            }
        }
    }

    private void attachModelTokenSources(
            List<FaultRuleDto> faultRules,
            Map<String, DeviceSmvData> deviceSmvMap) {
        if (faultRules == null) {
            return;
        }
        for (FaultRuleDto faultRule : faultRules) {
            if (faultRule != null) {
                ModelTokenSource source = modelTokenSourceForDevice(
                        faultRule.getTargetDeviceId(), deviceSmvMap);
                faultRule.setModelTokenSource(source);
                if (source == ModelTokenSource.BUNDLED
                        && trimToNull(faultRule.getTargetActionId()) != null) {
                    faultRule.setTargetActionLabel(faultRule.getTargetActionId());
                }
            }
        }
    }

    private ModelTokenSource modelTokenSourceForCondition(
            List<RuleDto> rules,
            int ruleIndex,
            int conditionIndex,
            Map<String, DeviceSmvData> deviceSmvMap) {
        if (rules == null || ruleIndex < 0 || ruleIndex >= rules.size()) {
            return ModelTokenSource.UNKNOWN;
        }
        RuleDto rule = rules.get(ruleIndex);
        List<RuleDto.Condition> conditions = rule != null ? rule.getConditions() : null;
        if (conditions == null || conditionIndex < 0 || conditionIndex >= conditions.size()) {
            return ModelTokenSource.UNKNOWN;
        }
        RuleDto.Condition condition = conditions.get(conditionIndex);
        return modelTokenSourceForDevice(
                condition != null ? condition.getDeviceName() : null, deviceSmvMap);
    }

    private ModelTokenSource modelTokenSourceForDevice(
            String deviceName,
            Map<String, DeviceSmvData> deviceSmvMap) {
        DeviceSmvData device = DeviceReferenceResolver.resolve(deviceName, deviceSmvMap);
        return device != null && device.getModelTokenSource() != null
                ? device.getModelTokenSource()
                : ModelTokenSource.UNKNOWN;
    }

    private FixResultDto runFixer(
            Long traceId,
            String violatedSpecId,
            List<cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto> states,
            List<RuleDto> rules,
            List<DeviceVerificationDto> devices,
            List<BoardEnvironmentVariableDto> environmentVariables,
            List<SpecificationDto> specs,
            Map<String, DeviceSmvData> deviceSmvMap,
            Long userId,
            AttackScenarioDto attackScenario,
            boolean enablePrivacy,
            List<String> strategies,
            int maxAttempts,
            Map<String, PreferredRange> preferredRanges) {
        AttackScenarioDto scenario = Objects.requireNonNull(
                attackScenario, "attackScenario is required");
        FixResultDto result = ruleFixer.fix(
                traceId, violatedSpecId, states, rules, devices, environmentVariables, specs,
                deviceSmvMap, userId, scenario, enablePrivacy, strategies, maxAttempts, preferredRanges);
        attachModelTokenSources(result, rules, deviceSmvMap);
        return result;
    }

    private FrozenTemplateSnapshots readFrozenTemplateSnapshots(
            TracePo trace, List<DeviceVerificationDto> devices) {
        String json = trace.getTemplateSnapshotsJson();
        JsonNode shape = JsonUtils.readPersistedJsonRequired(
                "verification trace", trace.getId(), "templateSnapshotsJson", json,
                () -> JsonUtils.fromJson(json, JsonNode.class));
        if (!shape.isObject()
                || !shape.path("schemaVersion").isInt()
                || shape.path("schemaVersion").intValue() != TemplateSnapshotBundleDto.CURRENT_SCHEMA_VERSION
                || !shape.path("manifests").isObject()
                || !shape.path("modelTokenSourcesByDeviceId").isObject()) {
            throw invalidTraceContext(trace, "templateSnapshotsJson",
                    "snapshot must use the current versioned manifest and token-source format");
        }
        TemplateSnapshotBundleDto bundle = JsonUtils.readPersistedRequired(
                "verification trace", trace.getId(), "templateSnapshotsJson",
                () -> JsonUtils.fromJson(json, TemplateSnapshotBundleDto.class));
        if (bundle.getSchemaVersion() != TemplateSnapshotBundleDto.CURRENT_SCHEMA_VERSION
                || bundle.getManifests() == null || bundle.getModelTokenSourcesByDeviceId() == null) {
            throw invalidTraceContext(trace, "templateSnapshotsJson",
                    "snapshot does not contain the current complete format");
        }

        Set<String> deviceIds = new LinkedHashSet<>();
        Set<String> templateNames = new LinkedHashSet<>();
        for (DeviceVerificationDto device : devices) {
            if (device == null || !isCanonicalNonBlank(device.getVarName())
                    || !isCanonicalNonBlank(device.getTemplateName())) {
                throw invalidTraceContext(trace, "requestJson",
                        "every device must have a canonical varName and templateName");
            }
            if (!deviceIds.add(device.getVarName())) {
                throw invalidTraceContext(trace, "requestJson",
                        "device varName values must be unique");
            }
            templateNames.add(device.getTemplateName());
        }

        if (!bundle.getManifests().keySet().equals(templateNames)
                || bundle.getManifests().entrySet().stream()
                        .anyMatch(entry -> !isCanonicalNonBlank(entry.getKey()) || entry.getValue() == null)) {
            throw invalidTraceContext(trace, "templateSnapshotsJson",
                    "manifest keys must exactly match the verification request's template names");
        }
        Set<String> persistedSourceKeys = new LinkedHashSet<>();
        shape.path("modelTokenSourcesByDeviceId").fieldNames().forEachRemaining(persistedSourceKeys::add);
        if (!persistedSourceKeys.equals(deviceIds)
                || !bundle.getModelTokenSourcesByDeviceId().keySet().equals(deviceIds)) {
            throw invalidTraceContext(trace, "templateSnapshotsJson",
                    "token-source keys must exactly match the verification request's device varNames");
        }
        for (String deviceId : deviceIds) {
            JsonNode persistedSource = shape.path("modelTokenSourcesByDeviceId").get(deviceId);
            ModelTokenSource source = bundle.getModelTokenSourcesByDeviceId().get(deviceId);
            if (!isCanonicalNonBlank(deviceId) || !persistedSource.isTextual()
                    || !isResolvedModelTokenSource(source)
                    || !source.name().equals(persistedSource.textValue())) {
                throw invalidTraceContext(trace, "templateSnapshotsJson",
                        "every device must carry an explicit BUNDLED or CUSTOM token source");
            }
        }
        return new FrozenTemplateSnapshots(
                Map.copyOf(bundle.getManifests()), Map.copyOf(bundle.getModelTokenSourcesByDeviceId()));
    }

    private void applyFrozenModelTokenSources(
            List<DeviceVerificationDto> devices,
            Map<String, ModelTokenSource> modelTokenSourcesByDeviceId) {
        for (DeviceVerificationDto device : devices) {
            ModelTokenSource source = modelTokenSourcesByDeviceId.get(device.getVarName());
            if (!isResolvedModelTokenSource(source)) {
                throw new IllegalStateException("Validated frozen template context lost a device token source");
            }
            device.setModelTokenSource(source);
        }
    }

    private VerificationContext loadContext(Long userId, Long traceId) {
        TracePo po = traceRepository.findByIdAndUserId(traceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Trace", traceId));
        TraceDto trace = traceMapper.toDto(po);

        String requestJson = po.getRequestJson();
        VerificationRequestDto request = JsonUtils.readPersistedJsonRequired(
                "verification trace", po.getId(), "requestJson", requestJson,
                () -> JsonUtils.fromJson(requestJson, VerificationRequestDto.class));
        if (request == null || request.getDevices() == null || request.getDevices().isEmpty()) {
            throw invalidTraceContext(po, "requestJson", "verification request has no devices");
        }
        try {
            request.setAttackScenario(AttackScenarioValidator.canonicalizeForVerification(
                    request.getAttackScenario()));
        } catch (ValidationException e) {
            throw invalidTraceContext(po, "requestJson", e.getMessage());
        }

        FrozenTemplateSnapshots templateSnapshots = readFrozenTemplateSnapshots(po, request.getDevices());

        log.debug("Loaded verification context for trace {}", traceId);

        return new VerificationContext(
                trace,
                request,
                Map.copyOf(templateSnapshots.manifests()),
                Map.copyOf(templateSnapshots.modelTokenSourcesByDeviceId()));
    }

    private boolean isCanonicalNonBlank(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim());
    }

    private boolean isResolvedModelTokenSource(ModelTokenSource source) {
        return source == ModelTokenSource.BUNDLED || source == ModelTokenSource.CUSTOM;
    }

    private PersistedDataIntegrityException invalidTraceContext(
            TracePo trace, String field, String detail) {
        return new PersistedDataIntegrityException("verification trace", trace.getId(), field, detail);
    }

    private ModelBoundaryInput modelBoundaryInput(VerificationRequestDto request,
                                                   Map<String, DeviceManifest> templateManifests,
                                                   Map<String, ModelTokenSource> modelTokenSourcesByDeviceId) {
        List<DeviceVerificationDto> devices = request.getDevices() == null ? List.of() : request.getDevices();
        applyFrozenModelTokenSources(devices, modelTokenSourcesByDeviceId);
        Map<String, DeviceSmvData> rawDeviceSmvMap =
                smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(devices, templateManifests);
        List<BoardEnvironmentVariableDto> environmentVariables = NusmvEnvironmentPool.mergeWithDefaults(
                request.getEnvironmentVariables(), rawDeviceSmvMap);
        request.setEnvironmentVariables(environmentVariables);
        List<DeviceVerificationDto> expandedDevices = NusmvEnvironmentPool.expandDevices(
                devices, environmentVariables, rawDeviceSmvMap);
        Map<String, DeviceSmvData> expandedDeviceSmvMap =
                smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(expandedDevices, templateManifests);
        return new ModelBoundaryInput(expandedDevices, environmentVariables, expandedDeviceSmvMap);
    }

    private record VerificationContext(TraceDto trace,
                                       VerificationRequestDto request,
                                       Map<String, DeviceManifest> templateManifests,
                                       Map<String, ModelTokenSource> modelTokenSourcesByDeviceId) {}

    private record FrozenTemplateSnapshots(
            Map<String, DeviceManifest> manifests,
            Map<String, ModelTokenSource> modelTokenSourcesByDeviceId) {}

    private record ModelBoundaryInput(List<DeviceVerificationDto> devices,
                                      List<BoardEnvironmentVariableDto> environmentVariables,
                                      Map<String, DeviceSmvData> deviceSmvMap) {}

    private record CurrentBoardSemanticContext(List<DeviceVerificationDto> currentDevices,
                                               Map<String, DeviceSmvData> currentDeviceSmvMap) {}
}
