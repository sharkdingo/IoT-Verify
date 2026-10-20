package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.component.board.BoardBatchRequestParser;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneRequestParser;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneDiagnostics;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.Result;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEditHistoryClearPreviewDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEditHistoryClearRequestDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardReplacementPreviewDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardUndoResultDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardModelFingerprintsDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardLayoutDto;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableUpdateRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceLayoutDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeUpdateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceUpdateResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceBatchCreateRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceDeletionResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceDeleteRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRenameRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDeletionRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDeletionResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetResultDto;
import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStage;
import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStatusDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.DeviceRecommendationDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.DeviceRecommendationRequestDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationResponseDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.RuleRecommendationDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.ScenarioRecommendationRequestDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.ScenarioRecommendationResponseDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.StandaloneRecommendationRequestDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.SpecificationRecommendationDto;
import cn.edu.nju.Iot_Verify.dto.rule.DuplicateRuleCheckResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleOrderRequestDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleSimilarityResultDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.BoardContentConflictException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.security.CurrentUser;
import cn.edu.nju.Iot_Verify.service.recommendation.RuleRecommendationService;
import cn.edu.nju.Iot_Verify.service.recommendation.DeviceRecommendationService;
import cn.edu.nju.Iot_Verify.service.recommendation.SpecificationRecommendationService;
import cn.edu.nju.Iot_Verify.service.recommendation.ScenarioRecommendationService;
import cn.edu.nju.Iot_Verify.service.recommendation.RuleCheckService;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.InteractiveAiExecutionService;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import cn.edu.nju.Iot_Verify.util.JsonPointerPath;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/board")
@RequiredArgsConstructor
public class BoardStorageController {

    private final BoardStorageService boardService;
    private final BoardDataConverter boardDataConverter;
    private final ModelFingerprint modelFingerprint;
    private final RuleRecommendationService ruleRecommendationService;
    private final DeviceRecommendationService deviceRecommendationService;
    private final SpecificationRecommendationService specificationRecommendationService;
    private final ScenarioRecommendationService scenarioRecommendationService;
    private final RuleCheckService ruleCheckService;
    private final ObjectMapper objectMapper;
    private final DeviceTemplateSchemaValidator deviceTemplateSchemaValidator;
    private final BoardBatchRequestParser boardBatchRequestParser;
    private final PortableSceneRequestParser portableSceneRequestParser;
    private final InteractiveAiExecutionService interactiveAiExecutionService;

    @GetMapping("/snapshot")
    public Result<BoardSemanticSnapshotDto> getSnapshot(@CurrentUser Long userId) {
        return Result.success(boardService.getSemanticSnapshot(userId));
    }

    @GetMapping("/model-fingerprints")
    public Result<BoardModelFingerprintsDto> getModelFingerprints(@CurrentUser Long userId) {
        var snapshot = boardDataConverter.getModelInputSnapshot(userId);
        return Result.success(modelFingerprint.boardFingerprints(snapshot));
    }

    @GetMapping("/nodes")
    public Result<List<DeviceNodeDto>> getNodes(@CurrentUser Long userId) {
        return Result.success(boardService.getNodes(userId));
    }

    @PostMapping("/nodes")
    public Result<DeviceMutationResultDto> addNodes(
            @CurrentUser Long userId,
            @NotNull @Valid @RequestBody DeviceBatchCreateRequestDto request) {
        return Result.success(boardService.addNodes(
                userId, request.getDevices(), request.getEnvironmentVariablePatches()));
    }

    @PutMapping("/nodes/layout")
    public Result<DeviceUpdateResultDto> updateNodeLayout(
            @CurrentUser Long userId,
            @RequestParam MultiValueMap<String, String> query,
            @NotNull @Valid @RequestBody DeviceLayoutDto layout) {
        return Result.success(boardService.updateNodeLayout(userId, requireSingleNodeId(query), layout));
    }

    @PutMapping("/nodes/runtime")
    public Result<DeviceUpdateResultDto> updateNodeRuntime(
            @CurrentUser Long userId,
            @RequestParam MultiValueMap<String, String> query,
            @NotNull @Valid @RequestBody DeviceRuntimeUpdateDto runtime) {
        return Result.success(boardService.updateNodeRuntime(userId, requireSingleNodeId(query), runtime));
    }

    @PatchMapping("/nodes/label")
    public Result<DeviceMutationResultDto> renameNode(
            @CurrentUser Long userId,
            @RequestParam MultiValueMap<String, String> query,
            @NotNull @Valid @RequestBody DeviceRenameRequestDto request) {
        return Result.success(boardService.renameNode(
                userId, requireSingleNodeId(query), request.getLabel(), request.getExpectedLabel()));
    }

    @GetMapping("/nodes/deletion-preview")
    public Result<DeviceDeletionResultDto> previewNodeDeletion(
            @CurrentUser Long userId,
            @RequestParam MultiValueMap<String, String> query) {
        return Result.success(boardService.previewNodeDeletion(userId, requireSingleNodeId(query)));
    }

    @PostMapping("/nodes/delete")
    public Result<DeviceDeletionResultDto> deleteNode(
            @CurrentUser Long userId,
            @RequestParam MultiValueMap<String, String> query,
            @NotNull @Valid @RequestBody DeviceDeleteRequestDto request) {
        return Result.success(boardService.deleteNodeCascade(
                userId,
                requireSingleNodeId(query),
                request.getImpactToken()));
    }

    private String requireSingleNodeId(MultiValueMap<String, String> query) {
        // Scalar binding joins repeated values with commas, which can select a different valid device id.
        List<String> nodeIds = query.get("nodeId");
        if (nodeIds == null || nodeIds.size() != 1) {
            throw new BadRequestException("Exactly one nodeId query parameter is required.");
        }
        return nodeIds.get(0);
    }

    @GetMapping("/environment")
    public Result<List<BoardEnvironmentVariableDto>> getEnvironment(@CurrentUser Long userId) {
        return Result.success(boardService.getEnvironmentVariables(userId));
    }

    @PostMapping("/environment")
    public Result<EnvironmentMutationResultDto> saveEnvironment(
            @CurrentUser Long userId,
            @NotNull @NotEmpty(message = "At least one environment variable update is required")
            @Size(max = RequestLimits.MAX_ENVIRONMENT_VARIABLES,
                    message = "At most 200 environment variables can be updated at once")
            @Valid @RequestBody
            List<@Valid @NotNull(message = "Environment variable update cannot be null")
                    EnvironmentVariableUpdateRequestDto> updates) {
        return Result.success(boardService.saveEnvironmentVariables(userId, updates));
    }

    @GetMapping("/specs")
    public Result<List<SpecificationDto>> getSpecs(@CurrentUser Long userId) {
        return Result.success(boardService.getSpecs(userId));
    }

    @PostMapping("/specs")
    public Result<CollectionMutationResultDto<SpecificationDto>> addSpec(
            @CurrentUser Long userId,
            @NotNull @Valid @RequestBody SpecificationDto spec) {
        return Result.success(boardService.addSpec(userId, spec));
    }

    @DeleteMapping("/specs/{specId}")
    public Result<CollectionMutationResultDto<SpecificationDto>> removeSpec(
            @CurrentUser Long userId,
            @PathVariable String specId,
            @NotNull @Valid @RequestBody SpecificationDto expected) {
        return Result.success(boardService.removeSpecIfUnchanged(userId, specId, expected));
    }

    @GetMapping("/rules")
    public Result<List<RuleDto>> getRules(@CurrentUser Long userId) {
        return Result.success(boardService.getRules(userId));
    }

    @PostMapping("/rules")
    public Result<CollectionMutationResultDto<RuleDto>> addRule(
            @CurrentUser Long userId,
            @NotNull @Valid @RequestBody RuleDto rule) {
        return Result.success(boardService.addRule(userId, rule));
    }

    @PutMapping("/rules/order")
    public Result<CollectionMutationResultDto<RuleDto>> reorderRules(
            @CurrentUser Long userId,
            @NotNull @Valid @RequestBody RuleOrderRequestDto request) {
        return Result.success(boardService.reorderRules(
                userId, request.getExpectedRuleIds(), request.getRuleIds()));
    }

    @DeleteMapping("/rules/{ruleId}")
    public Result<CollectionMutationResultDto<RuleDto>> removeRule(
            @CurrentUser Long userId,
            @PathVariable long ruleId,
            @NotNull @Valid @RequestBody RuleDto expected) {
        return Result.success(boardService.removeRuleIfUnchanged(userId, ruleId, expected));
    }

    /**
     * Current undo availability for this account.
     *
     * <p>Separate from the board snapshot so a page load can restore the undo affordance without
     * the client inferring it from actions it happens to remember. Undo history is server state,
     * so it survives reload, a second tab, and a different device.
     */
    @GetMapping("/edits/availability")
    public Result<BoardUndoResultDto> boardEditAvailability(@CurrentUser Long userId) {
        return Result.success(boardService.boardEditAvailability(userId));
    }

    /**
     * Reverses the newest reversible board edit for this account.
     *
     * <p>Undo is a board *edit* command, not a navigation or dialog action: it is deliberately not
     * bound to browser history, deep links, or run cancellation. The journal on the server is the
     * authority for what is reversible, so the client sends no local history.
     */
    @PostMapping("/edits/undo")
    public Result<BoardUndoResultDto> undoLastEdit(@CurrentUser Long userId) {
        return Result.success(boardService.undoLastEdit(userId));
    }

    /** Re-applies the oldest undone edit. */
    @PostMapping("/edits/redo")
    public Result<BoardUndoResultDto> redoLastUndoneEdit(@CurrentUser Long userId) {
        return Result.success(boardService.redoLastUndoneEdit(userId));
    }

    /** Exact edit-history impact the client must present for explicit confirmation. */
    @GetMapping("/edits/clear-preview")
    public Result<BoardEditHistoryClearPreviewDto> previewBoardEditHistoryClear(
            @CurrentUser Long userId) {
        return Result.success(boardService.previewBoardEditHistoryClear(userId));
    }

    /** Discards only the edit history represented by the confirmed preview token. */
    @PostMapping("/edits/clear")
    public Result<BoardUndoResultDto> clearBoardEditHistory(
            @CurrentUser Long userId,
            @Valid @RequestBody BoardEditHistoryClearRequestDto request) {
        return Result.success(boardService.clearBoardEditHistory(userId, request.getImpactToken()));
    }

    /**
     * Explicit atomic replacement of scene collections. Ordinary device/rule/spec mutations use
     * targeted endpoints; this command is reserved for confirmed scene import and scene clear.
     */
    @GetMapping("/replacement-preview")
    public Result<BoardReplacementPreviewDto> previewReplacement(@CurrentUser Long userId) {
        return Result.success(boardService.previewBoardReplacement(userId));
    }

    @PostMapping("/batch")
    public Result<BoardBatchDto> saveBatch(@CurrentUser Long userId, @NotNull @RequestBody JsonNode body) {
        return Result.success(boardService.saveBoardBatch(userId, boardBatchRequestParser.parse(body)));
    }

    /**
     * Imports a portable scene file, replacing the whole board atomically.
     *
     * <p>This is the import contract clients use: it takes the exported file shape verbatim plus the
     * confirmed {@code impactToken}, so no client has to know how portable rules and specifications
     * map onto the internal write DTOs. That mapping lives once in {@code PortableSceneBatchMapper},
     * shared with {@code apply_scenario}. Previously each client reimplemented it, and the same
     * dropped field produced three separate user-visible failures.</p>
     *
     * <p>{@code /batch} remains the lower-level command for a caller that already holds internal
     * collections — scene clear, which has no portable file to send.</p>
     */
    @PostMapping("/scene")
    public Result<BoardBatchDto> importScene(@CurrentUser Long userId,
                                             @NotNull @RequestBody JsonNode body) {
        BoardBatchDto batch = portableSceneRequestParser.parse(body);
        try {
            return Result.success(boardService.saveBoardBatch(userId, batch));
        } catch (ValidationException error) {
            throw PortableSceneDiagnostics.portable(error);
        } catch (BadRequestException error) {
            throw PortableSceneDiagnostics.portable(error);
        } catch (BoardContentConflictException error) {
            throw PortableSceneDiagnostics.portable(error);
        }
    }

    @GetMapping("/layout")
    public Result<BoardLayoutDto> getLayout(@CurrentUser Long userId) {
        return Result.success(boardService.getLayout(userId));
    }

    @PostMapping("/layout")
    public Result<BoardLayoutDto> saveLayout(@CurrentUser Long userId, @NotNull @Valid @RequestBody BoardLayoutDto layout) {
        return Result.success(boardService.saveLayout(userId, layout));
    }

    @GetMapping("/templates")
    public Result<List<DeviceTemplateDto>> getTemplates(@CurrentUser Long userId) {
        return Result.success(boardService.getDeviceTemplates(userId));
    }

    @GetMapping("/templates/schema")
    public Result<JsonNode> getTemplateSchema() {
        return Result.success(deviceTemplateSchemaValidator.getSchemaNode());
    }

    @PostMapping("/templates")
    public Result<DeviceTemplateDto> addTemplate(@CurrentUser Long userId, @NotNull @RequestBody JsonNode body) {
        if (!body.isObject()) {
            throw new BadRequestException("Template request body must be a JSON object.",
                    Map.of("request", "A JSON object containing name and manifest is required"));
        }
        String name = body.path("name").asText(null);
        deviceTemplateSchemaValidator.validateRawManifest(name, body.get("manifest"));
        DeviceTemplateDto dto;
        try {
            dto = objectMapper.treeToValue(body, DeviceTemplateDto.class);
        } catch (JsonProcessingException e) {
            String path = e instanceof JsonMappingException mapping ? JsonPointerPath.of(mapping) : "request";
            String reason = "Value does not match the declared JSON field type";
            throw new BadRequestException("Invalid template value at '" + path + "': " + reason,
                    Map.of(path, reason));
        }
        return Result.success(boardService.addDeviceTemplate(userId, dto));
    }

    @GetMapping("/templates/{id}/deletion-preview")
    public Result<DeviceTemplateDeletionResultDto> previewTemplateDeletion(
            @CurrentUser Long userId, @PathVariable Long id) {
        return Result.success(boardService.previewDeviceTemplateDeletion(userId, id));
    }

    @PostMapping("/templates/{id}/delete")
    public Result<DeviceTemplateDeletionResultDto> deleteTemplate(
            @CurrentUser Long userId,
            @PathVariable Long id,
            @NotNull @Valid @RequestBody DeviceTemplateDeletionRequestDto request) {
        return Result.success(boardService.deleteDeviceTemplate(userId, id, request.getImpactToken()));
    }

    @GetMapping("/templates/defaults/reset-preview")
    public Result<DefaultTemplateResetResultDto> previewDefaultTemplateReset(
            @CurrentUser Long userId) {
        return Result.success(boardService.previewDefaultTemplateReset(userId));
    }

    @PostMapping("/templates/defaults/reset")
    public Result<DefaultTemplateResetResultDto> resetDefaultTemplates(
            @CurrentUser Long userId,
            @NotNull @Valid @RequestBody DefaultTemplateResetRequestDto request) {
        return Result.success(boardService.resetDefaultTemplates(userId, request.getImpactToken()));
    }

    /**
     * 获取规则推荐
     * @param userId 用户ID
     * @param requestBody 推荐数量、语言和用户需求
     * @return 规则推荐列表
     */
    @PostMapping("/rules/recommend")
    public Result<RecommendationResponseDto<RuleRecommendationDto>> recommendRules(
            @CurrentUser Long userId,
            @NotNull @Valid @RequestBody StandaloneRecommendationRequestDto requestBody) {
        String requestId = requestBody.getRequestId();
        return interactiveAiExecutionService.execute(userId, requestId, () -> {
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.PREPARING_CONTEXT);
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.REQUESTING_MODEL);
            RecommendationResponseDto<RuleRecommendationDto> response = ruleRecommendationService.recommend(
                    userId, requestBody.getMaxRecommendations(), requestBody.getLanguage(),
                    requestBody.getUserRequirement());
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.VALIDATING_RESULT);
            return Result.success(response);
        });
    }

    /**
     * 根据画布中所有设备推荐新设备
     * @param userId 用户ID
     * @param requestBody 推荐数量、语言和用户需求
     * @return 设备推荐列表
     */
    @PostMapping("/devices/recommend")
    public Result<RecommendationResponseDto<DeviceRecommendationDto>> recommendDevices(
            @CurrentUser Long userId,
            @RequestParam
            @Size(min = RequestLimits.MIN_REQUEST_ID_LENGTH, max = RequestLimits.MAX_REQUEST_ID_LENGTH,
                    message = "Request ID must contain 8 to 80 characters")
            @Pattern(regexp = RequestLimits.REQUEST_ID_PATTERN,
                    message = "Request ID contains unsupported characters") String requestId,
            @NotNull @Valid @RequestBody DeviceRecommendationRequestDto requestBody) {

        return interactiveAiExecutionService.execute(userId, requestId, () -> {
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.PREPARING_CONTEXT);
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.REQUESTING_MODEL);
            RecommendationResponseDto<DeviceRecommendationDto> response = deviceRecommendationService.recommend(
                    userId, requestBody.getMaxRecommendations(), requestBody.getLanguage(),
                    requestBody.getUserRequirement());
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.VALIDATING_RESULT);
            return Result.success(response);
        });
    }

    /**
     * 检查规则是否重复
     * 当用户添加一条规则后，检查该规则是否与现有规则重复
     * @param userId 用户ID
     * @param rule 新添加的规则
     * @return 重复检查结果
     */
    @PostMapping("/rules/check-duplicate")
    public Result<DuplicateRuleCheckResultDto> checkDuplicateRule(
            @CurrentUser Long userId,
            @NotNull @Valid @RequestBody RuleDto rule) {

        return Result.success(ruleCheckService.checkDuplicate(userId, rule));
    }

    /**
     * 获取规约推荐
     * @param userId 用户ID
     * @param requestBody 推荐数量、语言和用户需求
     * @return 规约推荐列表
     */
    @PostMapping("/specs/recommend")
    public Result<RecommendationResponseDto<SpecificationRecommendationDto>> recommendSpecs(
            @CurrentUser Long userId,
            @NotNull @Valid @RequestBody StandaloneRecommendationRequestDto requestBody) {
        String requestId = requestBody.getRequestId();
        return interactiveAiExecutionService.execute(userId, requestId, () -> {
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.PREPARING_CONTEXT);
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.REQUESTING_MODEL);
            RecommendationResponseDto<SpecificationRecommendationDto> response =
                    specificationRecommendationService.recommend(
                            userId, requestBody.getMaxRecommendations(), requestBody.getLanguage(),
                            requestBody.getUserRequirement());
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.VALIDATING_RESULT);
            return Result.success(response);
        });
    }

    /**
     * Run an explicit AI semantic similarity check for a candidate rule.
     * This endpoint may call the configured LLM and is not used as a required save gate.
     */
    @PostMapping("/rules/check-similarity")
    public Result<RuleSimilarityResultDto> checkRuleSimilarity(
            @CurrentUser Long userId,
            @NotNull @Valid @RequestBody RuleDto rule) {

        return Result.success(ruleCheckService.checkSimilarity(userId, rule));
    }

    /**
     * Generate one coupled, importable scene recommendation.
     */
    @PostMapping("/scenario/recommend")
    public Result<ScenarioRecommendationResponseDto> recommendScenario(
            @CurrentUser Long userId,
            @RequestParam
            @Size(min = RequestLimits.MIN_REQUEST_ID_LENGTH, max = RequestLimits.MAX_REQUEST_ID_LENGTH,
                    message = "Request ID must contain 8 to 80 characters")
            @Pattern(regexp = RequestLimits.REQUEST_ID_PATTERN,
                    message = "Request ID contains unsupported characters") String requestId,
            @NotNull @Valid @RequestBody ScenarioRecommendationRequestDto requestBody) {

        return interactiveAiExecutionService.execute(userId, requestId, () -> {
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.PREPARING_CONTEXT);
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.REQUESTING_MODEL);
            ScenarioRecommendationResponseDto response = scenarioRecommendationService.recommend(userId, requestBody);
            interactiveAiExecutionService.markStage(
                    userId, requestId, InteractiveOperationStage.VALIDATING_RESULT);
            return Result.success(response);
        });
    }

    @DeleteMapping("/recommendations/{requestId}")
    public Result<Boolean> cancelRecommendation(
            @CurrentUser Long userId,
            @PathVariable
            @Size(min = RequestLimits.MIN_REQUEST_ID_LENGTH, max = RequestLimits.MAX_REQUEST_ID_LENGTH,
                    message = "Request ID must contain 8 to 80 characters")
            @Pattern(regexp = RequestLimits.REQUEST_ID_PATTERN,
                    message = "Request ID contains unsupported characters") String requestId) {
        return Result.success(interactiveAiExecutionService.cancel(userId, requestId));
    }

    @GetMapping("/recommendations/{requestId}")
    public Result<InteractiveOperationStatusDto> getRecommendationStatus(
            @CurrentUser Long userId,
            @PathVariable
            @Size(min = RequestLimits.MIN_REQUEST_ID_LENGTH, max = RequestLimits.MAX_REQUEST_ID_LENGTH,
                    message = "Request ID must contain 8 to 80 characters")
            @Pattern(regexp = RequestLimits.REQUEST_ID_PATTERN,
                    message = "Request ID contains unsupported characters") String requestId) {
        return Result.success(interactiveAiExecutionService.getStatus(userId, requestId));
    }
}
