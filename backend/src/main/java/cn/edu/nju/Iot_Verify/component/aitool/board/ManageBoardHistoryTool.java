package cn.edu.nju.Iot_Verify.component.aitool.board;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.board.BoardEditHistoryClearPreviewDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardUndoResultDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
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

/** Exposes the same authoritative board edit journal used by the UI undo/redo controls. */
@Slf4j
@Component
public class ManageBoardHistoryTool extends AbstractAiTool {

    private static final String CLEAR_TARGET_KEY = "board-edit-history";

    private final BoardStorageService boardStorageService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public ManageBoardHistoryTool(BoardStorageService boardStorageService,
                                  AiDestructiveActionGuard destructiveActionGuard,
                                  ObjectMapper objectMapper) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "manage_board_history";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string",
                "enum", List.of("availability", "undo", "redo", "clear"),
                "description", "History operation. clear discards undo/redo entries without changing Board data and requires a two-turn preview confirmation."));
        properties.put("confirmed", Map.of("type", "boolean",
                "description", "For action=clear only. Use false for the exact no-write preview; true only after the user confirms it in a later turn."));
        properties.put("impactToken", Map.of("type", "string",
                "description", "For confirmed action=clear only. Copy the opaque token from the latest preview."));
        return LlmToolSpec.of(getName(),
                "Read server-authoritative undo/redo availability, undo or redo one Board edit, or preview/confirm clearing the undo/redo edit history without changing the Board. Use undo, redo, or clear only when the user explicitly asks; conflicts are rejected instead of overwriting newer work.",
                new FunctionParameterSchema("object", properties, List.of("action")));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("action", "confirmed", "impactToken"));
            String action = optionalEnumArg(args, "action", "availability",
                    Set.of("availability", "undo", "redo", "clear"));
            if ("clear".equals(action)) {
                return executeClear(userId, args);
            }
            requireOnlyFields(args, "arguments", Set.of("action"));
            BoardUndoResultDto result = switch (action) {
                case "undo" -> boardStorageService.undoLastEdit(userId);
                case "redo" -> boardStorageService.redoLastUndoneEdit(userId);
                default -> boardStorageService.boardEditAvailability(userId);
            };

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", message(action, result.isApplied()));
            response.put("operation", switch (action) {
                case "undo" -> "undone";
                case "redo" -> "redone";
                default -> "availability";
            });
            response.put("applied", result.isApplied());
            if (result.getEntityType() != null) response.put("entityType", result.getEntityType());
            if (result.getOriginalOperation() != null) {
                response.put("originalOperation", result.getOriginalOperation());
            }
            if (result.getReasonCode() != null) response.put("reasonCode", result.getReasonCode());
            response.put("deviceCount", safeList(result.getNodes()).size());
            response.put("environmentVariableCount", safeList(result.getEnvironmentVariables()).size());
            response.put("ruleCount", safeList(result.getRules()).size());
            response.put("specificationCount", safeList(result.getSpecs()).size());
            response.put("canUndo", result.isCanUndo());
            response.put("canRedo", result.isCanRedo());
            return "availability".equals(action)
                    ? readOnlySuccessJson(response, "Board history availability loaded.")
                    : successJson(response, "Board history operation completed.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("manage_board_history failed", e);
            return errorJson("Failed to manage Board edit history.", "INTERNAL_ERROR", 500);
        }
    }

    private String executeClear(Long userId, JsonNode args) throws ArgValidationException {
        requireOnlyFields(args, "arguments", Set.of("action", "confirmed", "impactToken"));
        boolean confirmed = booleanArg(args, "confirmed", false);
        BoardEditHistoryClearPreviewDto preview = boardStorageService.previewBoardEditHistoryClear(userId);
        String domainImpactToken = trimToNull(preview.getImpactToken());
        if (preview.getEntryCount() == 0) {
            Map<String, Object> response = clearPreviewView(preview);
            response.put("operation", "history_empty");
            response.put("requiresUserConfirmation", false);
            response.put("message", "Undo and redo history is already empty; no Board data changed.");
            return readOnlySuccessJson(response, "Board edit-history state unavailable.");
        }
        if (domainImpactToken == null) {
            return errorJson("The edit-history preview did not provide an impact token. No changes were made.",
                    "RESULT_UNAVAILABLE", 503);
        }

        Map<String, Object> bindingSnapshot = clearBindingSnapshot(preview);
        if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
            String confirmationToken = destructiveActionGuard.issue(
                    userId, getName(), CLEAR_TARGET_KEY,
                    "Undo/redo history", bindingSnapshot, domainImpactToken);
            Map<String, Object> response = clearPreviewView(preview);
            response.put("operation", "clear_preview");
            response.put("requiresUserConfirmation", true);
            response.put("impactToken", confirmationToken);
            response.put("message", "No Board data changed. Confirm this exact preview to discard the current undo and redo history.");
            return readOnlySuccessJson(response, "Board edit-history clear preview unavailable.");
        }

        String suppliedToken = requiredTextField(args, "impactToken", "arguments");
        AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consume(
                userId, getName(), CLEAR_TARGET_KEY, suppliedToken, bindingSnapshot);
        if (!confirmation.approved()) {
            return staleClearResponse(userId, confirmation.message(), confirmation.errorCode(), preview);
        }

        try {
            BoardUndoResultDto result = boardStorageService.clearBoardEditHistory(
                    userId, confirmation.domainImpactToken());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("operation", "history_cleared");
            response.put("clearedEntryCount", preview.getEntryCount());
            response.put("canUndo", result.isCanUndo());
            response.put("canRedo", result.isCanRedo());
            response.put("message", "Undo and redo history was cleared. Current Board data was not changed.");
            return successJson(response, "Board edit history cleared.");
        } catch (ConflictException conflict) {
            return staleClearResponse(userId, conflict.getMessage(), "CONFIRMATION_STALE", null);
        }
    }

    private String staleClearResponse(Long userId,
                                      String message,
                                      String errorCode,
                                      BoardEditHistoryClearPreviewDto knownPreview) {
        BoardEditHistoryClearPreviewDto current = knownPreview != null
                ? knownPreview : boardStorageService.previewBoardEditHistoryClear(userId);
        Map<String, Object> currentPreview = clearPreviewView(current);
        currentPreview.put("operation", current.getEntryCount() == 0 ? "history_empty" : "clear_preview");
        boolean canConfirm = current.getEntryCount() > 0 && trimToNull(current.getImpactToken()) != null;
        currentPreview.put("requiresUserConfirmation", canConfirm);
        if (canConfirm) {
            currentPreview.put("impactToken", destructiveActionGuard.issue(
                    userId, getName(), CLEAR_TARGET_KEY,
                    "Undo/redo history",
                    clearBindingSnapshot(current), current.getImpactToken().trim()));
        }
        return errorJson(message, errorCode, 409, Map.of(
                "requiresUserConfirmation", canConfirm,
                "currentPreview", currentPreview));
    }

    private Map<String, Object> clearBindingSnapshot(BoardEditHistoryClearPreviewDto preview) {
        Map<String, Object> binding = clearPreviewView(preview);
        binding.put("domainImpactToken", preview.getImpactToken());
        return binding;
    }

    private Map<String, Object> clearPreviewView(BoardEditHistoryClearPreviewDto preview) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("entryCount", preview.getEntryCount());
        view.put("canUndo", preview.isCanUndo());
        view.put("canRedo", preview.isCanRedo());
        return view;
    }

    private String message(String action, boolean applied) {
        if ("availability".equals(action)) return "Board edit-history availability loaded.";
        if (!applied) return "undo".equals(action)
                ? "There is no reversible Board edit to undo."
                : "There is no undone Board edit to redo.";
        return "undo".equals(action)
                ? "The latest reversible Board edit was undone."
                : "The previously undone Board edit was redone.";
    }
}
