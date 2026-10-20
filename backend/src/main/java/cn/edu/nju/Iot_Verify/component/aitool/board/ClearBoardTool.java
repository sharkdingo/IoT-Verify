package cn.edu.nju.Iot_Verify.component.aitool.board;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardReplacementPreviewDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
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

/** Explicit full-board clear with an exact, two-turn impact confirmation. */
@Slf4j
@Component
public class ClearBoardTool extends AbstractAiTool {

    private static final String TARGET_KEY = "current-board";

    private final BoardStorageService boardStorageService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public ClearBoardTool(BoardStorageService boardStorageService,
                          AiDestructiveActionGuard destructiveActionGuard,
                          ObjectMapper objectMapper) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "clear_board";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("confirmed", Map.of(
                "type", "boolean",
                "description", "Use false to preview exact current Board counts. Use true only in a later turn after the user explicitly confirms that preview."));
        properties.put("impactToken", Map.of(
                "type", "string",
                "description", "Opaque token from the latest preview; required with confirmed=true."));
        return LlmToolSpec.of(getName(),
                "Preview or, after explicit two-turn confirmation, atomically clear every device, Environment Pool value, automation rule, and safety specification from the current Board. This also discards Board edit history.",
                new FunctionParameterSchema("object", properties, List.of("confirmed")));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("confirmed", "impactToken"));
            boolean confirmed = booleanArg(args, "confirmed", false);
            BoardReplacementPreviewDto preview = boardStorageService.previewBoardReplacement(userId);
            Map<String, Object> snapshot = bindingSnapshot(preview);
            if (isAlreadyEmpty(preview)) {
                return readOnlySuccessJson(Map.of(
                        "message", "The Board and its edit history are already empty; no changes were made.",
                        "operation", "unchanged",
                        "requiresUserConfirmation", false),
                        "Board already empty.");
            }
            String domainImpactToken = trimToNull(preview.getImpactToken());
            if (domainImpactToken == null) {
                return errorJson("The Board clear preview did not provide an impact token. No changes were made.",
                        "RESULT_UNAVAILABLE", 503);
            }

            if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
                String token = destructiveActionGuard.issue(
                        userId, getName(), TARGET_KEY,
                        "Whole Board", snapshot, domainImpactToken);
                return readOnlySuccessJson(previewResponse(preview, token),
                        "Board clear preview prepared; no changes were made.");
            }

            String suppliedToken = requiredTextField(args, "impactToken", "arguments");
            AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consume(
                    userId, getName(), TARGET_KEY, suppliedToken, snapshot);
            if (!confirmation.approved()) {
                String freshToken = destructiveActionGuard.issue(
                        userId, getName(), TARGET_KEY,
                        "Whole Board", snapshot, domainImpactToken);
                return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                        "requiresUserConfirmation", true,
                        "currentPreview", previewResponse(preview, freshToken)));
            }

            BoardBatchDto emptyBoard = new BoardBatchDto(List.of(), List.of(), List.of(), List.of());
            emptyBoard.setTemplateSnapshots(List.of());
            emptyBoard.setImpactToken(confirmation.domainImpactToken());
            boardStorageService.saveBoardBatch(userId, emptyBoard);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "The Board was cleared atomically, including its edit history.");
            response.put("operation", "cleared");
            response.put("removedDeviceCount", preview.getDeviceCount());
            response.put("removedEnvironmentVariableCount", preview.getEnvironmentVariableCount());
            response.put("removedRuleCount", preview.getRuleCount());
            response.put("removedSpecificationCount", preview.getSpecificationCount());
            response.put("clearedEditHistoryEntryCount", preview.getEditHistoryEntryCount());
            return successJson(response, "Board cleared.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("clear_board failed", e);
            return errorJson("Failed to clear the Board.", "INTERNAL_ERROR", 500);
        }
    }

    private Map<String, Object> bindingSnapshot(BoardReplacementPreviewDto preview) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("deviceCount", preview.getDeviceCount());
        snapshot.put("environmentVariableCount", preview.getEnvironmentVariableCount());
        snapshot.put("ruleCount", preview.getRuleCount());
        snapshot.put("specificationCount", preview.getSpecificationCount());
        snapshot.put("editHistoryEntryCount", preview.getEditHistoryEntryCount());
        snapshot.put("domainImpactToken", preview.getImpactToken());
        return snapshot;
    }

    private Map<String, Object> previewResponse(BoardReplacementPreviewDto preview, String token) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "No changes were made. Explicit user confirmation is required to clear this exact Board snapshot and its edit history.");
        response.put("operation", "preview");
        response.put("requiresUserConfirmation", true);
        response.put("wouldRemoveDeviceCount", preview.getDeviceCount());
        response.put("wouldRemoveEnvironmentVariableCount", preview.getEnvironmentVariableCount());
        response.put("wouldRemoveRuleCount", preview.getRuleCount());
        response.put("wouldRemoveSpecificationCount", preview.getSpecificationCount());
        response.put("wouldClearEditHistoryEntryCount", preview.getEditHistoryEntryCount());
        response.put("impactToken", token);
        return response;
    }

    private boolean isAlreadyEmpty(BoardReplacementPreviewDto preview) {
        return preview.getDeviceCount() == 0
                && preview.getEnvironmentVariableCount() == 0
                && preview.getRuleCount() == 0
                && preview.getSpecificationCount() == 0
                && preview.getEditHistoryEntryCount() == 0;
    }
}
