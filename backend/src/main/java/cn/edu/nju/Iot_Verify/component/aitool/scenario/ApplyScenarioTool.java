package cn.edu.nju.Iot_Verify.component.aitool.scenario;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneBatchMapper;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneDiagnostics;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardReplacementPreviewDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.BoardContentConflictException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.exception.BoardReplacementStaleException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Atomically applies the latest validated scenario draft from the current chat session. */
@Slf4j
@Component
public class ApplyScenarioTool extends AbstractAiTool {

    /** Provenance prefix for specification ids minted from a chat-generated draft. */
    private static final String CHAT_SPEC_ID_PREFIX = "chat_scene_spec_";

    private final AiScenarioDraftStore draftStore;
    private final PortableSceneBatchMapper batchMapper;
    private final BoardStorageService boardStorageService;

    public ApplyScenarioTool(AiScenarioDraftStore draftStore,
                             PortableSceneBatchMapper batchMapper,
                             BoardStorageService boardStorageService,
                             ObjectMapper objectMapper) {
        super(objectMapper);
        this.draftStore = draftStore;
        this.batchMapper = batchMapper;
        this.boardStorageService = boardStorageService;
    }

    @Override
    public String getName() {
        return "apply_scenario";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("confirmed", Map.of(
                "type", "boolean",
                "description", "Your intent, not the gate: pass false to request a preview, true when a "
                        + "later user turn confirms that exact preview. Whether the replacement actually "
                        + "runs is decided server-side from the user's own confirmation, so true cannot "
                        + "force it and false cannot cancel an already-confirmed one."
        ));
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", properties, List.of("confirmed"));
        return LlmToolSpec.of(
                getName(),
                "Preview or, after explicit later confirmation, atomically replace the board with the latest validated recommend_scenario draft from this chat session. Never delete devices individually to apply a scene draft.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        boolean replacementStarted = false;
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("confirmed"));
            boolean confirmed = booleanArg(args, "confirmed", false);
            String sessionId = requireChatSession();

            boolean userConfirmed = UserContextHolder.isSceneReplacementConfirmed();
            AiScenarioDraftStore.PendingApplication pending = userConfirmed
                    ? draftStore.pendingApplication(userId, sessionId).orElse(null)
                    : null;
            if (!userConfirmed) {
                return preview(userId, sessionId);
            }
            if (pending == null || !pending.confirmationId().equals(
                    UserContextHolder.getConfirmedProtectedActionId())) {
                return errorJson("No changes were made. The confirmed scenario preview is no longer pending; review the current preview.",
                        "CONFIRMATION_MISMATCH", 409);
            }
            if (!confirmed) {
                log.info("Applying pending AI scenario from explicit server-side confirmation despite model confirmed=false: userId={}, sessionId={}",
                        userId, sessionId);
            }

            BoardBatchDto batch = batchMapper.toBatch(
                    pending.scene(), pending.preview().getImpactToken(), CHAT_SPEC_ID_PREFIX);
            replacementStarted = true;
            BoardBatchDto saved = boardStorageService.saveBoardBatch(userId, batch,
                    () -> draftStore.completeApplication(userId, sessionId, pending.confirmationId()));
            if (saved == null || saved.getNodes() == null || saved.getEnvironmentVariables() == null
                    || saved.getRules() == null || saved.getSpecs() == null || saved.getCreatedTemplates() == null) {
                return mutationResultUnavailable();
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "The scenario draft replaced the current board atomically. Its rules and specifications have not been formally verified.");
            response.put("operation", "replaced");
            response.put("scenarioName", pending.scenarioName());
            response.put("deviceCount", safeList(saved.getNodes()).size());
            response.put("environmentVariableCount", safeList(saved.getEnvironmentVariables()).size());
            response.put("ruleCount", safeList(saved.getRules()).size());
            response.put("specificationCount", safeList(saved.getSpecs()).size());
            response.put("createdTemplateCount", safeList(saved.getCreatedTemplates()).size());
            response.put("verificationStatus", "NOT_VERIFIED");
            putVerificationReadiness(response, pending.scene());
            log.info("Applied AI scenario draft atomically: userId={}, sessionId={}, scenarioName={}",
                    userId, sessionId, pending.scenarioName());
            return successJson(response, "Scenario replacement result unavailable.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (BoardReplacementStaleException e) {
            return stalePreview(userId, e);
        } catch (ValidationException e) {
            ValidationException diagnostic = PortableSceneDiagnostics.portable(e);
            return errorJson(diagnostic.getMessage(), "VALIDATION_ERROR", e.getCode(),
                    Map.of("errors", diagnostic.getErrors()));
        } catch (BadRequestException e) {
            BadRequestException diagnostic = PortableSceneDiagnostics.portable(e);
            return errorJson(diagnostic.getMessage(), "VALIDATION_ERROR", e.getCode(),
                    Map.of("errors", diagnostic.getErrors()));
        } catch (BoardContentConflictException e) {
            BoardContentConflictException diagnostic = PortableSceneDiagnostics.portable(e);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reasonCode", diagnostic.getReasonCode());
            details.put("errors", diagnostic.getErrors());
            if (diagnostic.getTemplateName() != null) details.put("templateName", diagnostic.getTemplateName());
            return errorJson(diagnostic.getMessage(), diagnostic.getReasonCode(), e.getCode(), details);
        } catch (ServiceUnavailableException e) {
            if (replacementStarted) return mutationResultUnavailable();
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", e.getCode());
        } catch (BaseException e) {
            log.warn("apply_scenario business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (IllegalArgumentException e) {
            if (replacementStarted) return mutationResultUnavailable();
            return errorJson(e.getMessage(), "SCENARIO_DRAFT_NOT_FOUND", 404);
        } catch (Exception e) {
            log.error("apply_scenario failed", e);
            if (replacementStarted) return mutationResultUnavailable();
            return errorJson("Failed to apply the stored scenario draft. No confirmed replacement result is available.",
                    "INTERNAL_ERROR", 500);
        }
    }

    private String mutationResultUnavailable() {
        return successJson(Map.of(
                "resultStatus", "RESULT_UNAVAILABLE",
                "resultAvailable", false,
                "mutationMayHaveCommitted", true,
                "errorCode", "MUTATION_RESULT_INVALID",
                "message", "The scenario replacement outcome could not be confirmed. Refresh the Board before retrying."),
                "Scenario replacement result unavailable.");
    }

    private String preview(Long userId, String sessionId) {
        AiScenarioDraftStore.DraftSnapshot draft = draftStore.latestDraft(userId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No validated scenario draft is available in this chat session. Generate one before applying it."));
        BoardReplacementPreviewDto current = boardStorageService.previewBoardReplacement(userId);
        AiScenarioDraftStore.PendingApplication pending = draftStore
                .beginApplication(userId, sessionId, current)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No validated scenario draft is available in this chat session. Generate one before applying it."));

        Map<String, Object> response = replacementPreviewResponse(pending, current);
        response.put("message", "No changes were made. Explicit user confirmation is required before atomically replacing the current board with this scenario draft.");
        response.put("operation", "preview");
        response.put("requiresUserConfirmation", true);
        return readOnlySuccessJson(response, "Scenario replacement preview unavailable.");
    }

    private String stalePreview(Long userId, BoardReplacementStaleException exception) {
        String sessionId = requireChatSession();
        BoardReplacementPreviewDto current = exception.getCurrentPreview();
        AiScenarioDraftStore.PendingApplication refreshed = draftStore
                .refreshPendingApplication(userId, sessionId, current)
                .orElse(null);
        if (refreshed == null) {
            return errorJson(exception.getMessage(), "BOARD_REPLACEMENT_STALE", 409);
        }
        Map<String, Object> extras = replacementPreviewResponse(refreshed, current);
        extras.put("requiresUserConfirmation", true);
        return errorJson(exception.getMessage(), "BOARD_REPLACEMENT_STALE", 409, extras);
    }

    private Map<String, Object> replacementPreviewResponse(
            AiScenarioDraftStore.PendingApplication pending,
            BoardReplacementPreviewDto current) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scenarioName", pending.scenarioName());
        response.put("currentDeviceCount", current.getDeviceCount());
        response.put("currentEnvironmentVariableCount", current.getEnvironmentVariableCount());
        response.put("currentRuleCount", current.getRuleCount());
        response.put("currentSpecificationCount", current.getSpecificationCount());
        response.put("editHistoryEntryCount", current.getEditHistoryEntryCount());
        JsonNode scene = pending.scene();
        response.put("replacementDeviceCount", scene.path("devices").size());
        response.put("replacementEnvironmentVariableCount", scene.path("environmentVariables").size());
        response.put("replacementRuleCount", scene.path("rules").size());
        response.put("replacementSpecificationCount", scene.path("specs").size());
        response.put("replacementScene", scene);
        putVerificationReadiness(response, scene);
        return response;
    }

    private void putVerificationReadiness(Map<String, Object> response, JsonNode scene) {
        ScenarioVerificationReadiness.Status readiness = ScenarioVerificationReadiness.assess(scene);
        response.put("verificationReady", readiness.verificationReady());
        response.put("readinessIssues", readiness.readinessIssues());
    }

    private String requireChatSession() {
        String sessionId = UserContextHolder.getChatSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException(
                    "Scenario application requires an active chat session containing a validated draft.");
        }
        return sessionId;
    }
}
