package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEditHistoryClearPreviewDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardLayoutDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardReplacementPreviewDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardUndoResultDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableUpdateRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceLayoutDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeUpdateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceUpdateResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDeletionResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceDeletionResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;

import java.util.List;
import java.util.Set;

public interface BoardStorageService {
    /**
     * Captures every persisted collection that defines the formal model, including the exact
     * device-template manifests, under one user/database lock and one transaction.
     */
    BoardSemanticSnapshotDto getSemanticSnapshot(Long userId);

    List<DeviceNodeDto> getNodes(Long userId);
    DeviceMutationResultDto addNodes(Long userId, List<DeviceNodeDto> nodes,
                                     List<BoardEnvironmentVariableDto> environmentVariablePatches);
    DeviceUpdateResultDto updateNodeLayout(Long userId, String nodeId, DeviceLayoutDto layout);
    DeviceUpdateResultDto updateNodeLayoutIfUnchanged(
            Long userId, String nodeId, DeviceLayoutDto expected, DeviceLayoutDto desired);
    DeviceUpdateResultDto updateNodeRuntime(Long userId, String nodeId, DeviceRuntimeUpdateDto runtime);
    DeviceMutationResultDto renameNode(
            Long userId, String nodeId, String newLabel, String expectedLabel);
    DeviceDeletionResultDto previewNodeDeletion(Long userId, String nodeId);
    DeviceDeletionResultDto deleteNodeCascade(Long userId, String nodeId, String expectedImpactToken);
    List<BoardEnvironmentVariableDto> getEnvironmentVariables(Long userId);
    /** Applies name-keyed field patches only while each complete expected baseline still matches. */
    EnvironmentMutationResultDto saveEnvironmentVariables(
            Long userId, List<EnvironmentVariableUpdateRequestDto> updates);

    /**
     * Atomic read-modify-write of the board-level environment pool under the per-user write lock.
     * The mutator receives the complete materialized pool, including template defaults.
     */
    List<BoardEnvironmentVariableDto> updateEnvironmentVariables(
            Long userId,
            java.util.function.UnaryOperator<List<BoardEnvironmentVariableDto>> mutator);

    /**
     * Atomically create one node chosen from the current node snapshot. The result includes the
     * authoritative Environment Pool and its exact changes from the same transaction.
     */
    DeviceMutationResultDto createNode(
            Long userId,
            java.util.function.Function<List<DeviceNodeDto>, DeviceNodeDto> nodeFactory);

    List<SpecificationDto> getSpecs(Long userId);
    /** Atomically add a single spec under user-level lock. */
    CollectionMutationResultDto<SpecificationDto> addSpec(Long userId, SpecificationDto spec);
    /** Atomically remove a spec only when its identity and authored semantics are unchanged. */
    CollectionMutationResultDto<SpecificationDto> removeSpecIfUnchanged(
            Long userId, String specId, SpecificationDto expected);

    List<RuleDto> getRules(Long userId);
    /** Atomically add a single rule under user-level lock. */
    CollectionMutationResultDto<RuleDto> addRule(Long userId, RuleDto rule);
    /** Atomically remove a rule only when its identity and authored semantics are unchanged. */
    CollectionMutationResultDto<RuleDto> removeRuleIfUnchanged(
            Long userId, long ruleId, RuleDto expected);
    /**
     * Atomically replace execution order only while the complete expected order still matches.
     *
     * <p>Reversible, so it returns the same mutation envelope as the other reversible edits and
     * carries the resulting undo availability. Users reach reorder through explicit up/down
     * buttons and read one press as one edit, so it must be undoable like any other. A requested
     * order identical to the expected order is rejected rather than recorded as an edit.
     */
    CollectionMutationResultDto<RuleDto> reorderRules(
            Long userId, List<Long> expectedRuleIds, List<Long> ruleIds);

    /**
     * Reverses the newest reversible board edit that is still in effect.
     *
     * <p>Device create/update/rename/delete, direct Environment Pool update, rule/specification
     * create/delete, rule reorder, and automatic-fix rule-set replacement participate. A device
     * deletion entry is compound: it includes its Environment Pool state and every cascaded
     * rule/specification. Refuses when any affected state changed after the edit was recorded, so
     * undo cannot silently discard newer work. Returns {@code applied=false} when there is no
     * history left, which makes repeating the request idempotent.
     */
    BoardUndoResultDto undoLastEdit(Long userId);

    /**
     * Current undo availability, with no side effects. Lets a fresh page load restore the undo
     * affordance from server state instead of guessing.
     */
    BoardUndoResultDto boardEditAvailability(Long userId);

    /** Re-applies the oldest undone edit. Same conflict and idempotency rules as undo. */
    BoardUndoResultDto redoLastUndoneEdit(Long userId);

    /** Returns the exact journal impact token and count for destructive confirmation. */
    BoardEditHistoryClearPreviewDto previewBoardEditHistoryClear(Long userId);

    /** Discards only the undo/redo history matched by the confirmed token; board state is unchanged. */
    BoardUndoResultDto clearBoardEditHistory(Long userId, String expectedImpactToken);

    /**
     * Atomic read-modify-write of rules against the complete current model snapshot. The mutator's
     * decision and the persisted rule list share one lock and transaction; exceptions roll back.
     */
    CollectionMutationResultDto<RuleDto> updateRulesAgainstSnapshot(
            Long userId,
            java.util.function.Function<BoardSemanticSnapshotDto, List<RuleDto>> mutator);

    /** Returns the exact current-board impact token and counts for destructive confirmation. */
    BoardReplacementPreviewDto previewBoardReplacement(Long userId);

    /**
     * Atomically replaces all four scene collections under the user/database write lock after
     * confirming that the preview impact token still matches. Ordinary mutations use targeted
     * methods above.
     */
    BoardBatchDto saveBoardBatch(Long userId, BoardBatchDto batch);

    /**
     * Consumes confirmed application state inside the same scene replacement transaction.
     * A completion failure rolls back both the scene and its undo-history boundary.
     */
    BoardBatchDto saveBoardBatch(Long userId, BoardBatchDto batch, Runnable afterReplacement);

    BoardLayoutDto getLayout(Long userId);
    BoardLayoutDto saveLayout(Long userId, BoardLayoutDto layout);

    List<DeviceTemplateDto> getDeviceTemplates(Long userId);
    DeviceTemplateDto addDeviceTemplate(Long userId, DeviceTemplateDto templateDto);
    DeviceTemplateDeletionResultDto previewDeviceTemplateDeletion(Long userId, Long templateId);
    DeviceTemplateDeletionResultDto deleteDeviceTemplate(
            Long userId, Long templateId, String expectedImpactToken);

    DefaultTemplateResetResultDto previewDefaultTemplateReset(Long userId);

    /** Atomically resets every bundled default after the caller confirms the exact preview. */
    DefaultTemplateResetResultDto resetDefaultTemplates(Long userId, String expectedImpactToken);
}
