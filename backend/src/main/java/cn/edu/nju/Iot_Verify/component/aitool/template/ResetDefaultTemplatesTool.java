package cn.edu.nju.Iot_Verify.component.aitool.template;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateAffectedDeviceDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetBlockerDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetResultDto;
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

/** Exposes the existing atomic default-template reset authority to the AI assistant. */
@Slf4j
@Component
public class ResetDefaultTemplatesTool extends AbstractAiTool {

    private static final String TARGET_KEY = "bundled-default-template-catalog";

    private final BoardStorageService boardStorageService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public ResetDefaultTemplatesTool(BoardStorageService boardStorageService,
                                     AiDestructiveActionGuard destructiveActionGuard,
                                     ObjectMapper objectMapper) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "reset_default_templates";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("confirmed", Map.of(
                "type", "boolean",
                "description", "Use false to preview exact type, device, and Environment Pool effects. Use true only after the user explicitly confirms that preview in a later turn."));
        properties.put("impactToken", Map.of(
                "type", "string",
                "description", "Opaque token returned by the latest preview. Required when confirmed=true."));
        return LlmToolSpec.of(
                getName(),
                "Preview or, after explicit confirmation, atomically refresh the user's bundled default device templates and reconcile the Environment Pool. Custom templates with other names are preserved. This also clears the board edit history (undo/redo), because a refreshed manifest can change the semantics earlier device snapshots were taken against.",
                new FunctionParameterSchema("object", properties, List.of("confirmed")));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("confirmed", "impactToken"));
            boolean confirmed = booleanArg(args, "confirmed", false);
            DefaultTemplateResetResultDto preview = boardStorageService.previewDefaultTemplateReset(userId);
            Map<String, Object> previewView = previewView(preview);
            String domainImpactToken = trimToNull(preview.getImpactToken());
            if (!preview.isCanApply()) {
                return readOnlySuccessJson(Map.of(
                        "message", "No changes were made. The current board is incompatible with the bundled default definitions; resolve the listed blockers first.",
                        "requiresUserConfirmation", false,
                        "preview", previewView),
                        "Default-template reset preview unavailable.");
            }
            if (domainImpactToken == null) {
                return errorJson("The reset preview did not provide an impact token. No changes were made.",
                        "RESULT_UNAVAILABLE", 503);
            }

            Map<String, Object> bindingSnapshot = Map.of(
                    "preview", previewView,
                    "domainImpactToken", domainImpactToken);
            if (!confirmed || !UserContextHolder.isDefaultTemplateResetConfirmed()) {
                String confirmationToken = destructiveActionGuard.issue(
                        userId, getName(), TARGET_KEY,
                        "Bundled default templates", bindingSnapshot, domainImpactToken);
                previewView.put("impactToken", confirmationToken);
                return readOnlySuccessJson(Map.of(
                        "message", "No changes were made. Confirm this exact preview to refresh the bundled default templates.",
                        "requiresUserConfirmation", true,
                        "preview", previewView),
                        "Default-template reset preview unavailable.");
            }

            String suppliedToken = requiredTextField(args, "impactToken", "arguments");
            AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consume(
                    userId, getName(), TARGET_KEY, suppliedToken, bindingSnapshot);
            if (!confirmation.approved()) {
                String freshToken = destructiveActionGuard.issue(
                        userId, getName(), TARGET_KEY,
                        "Bundled default templates", bindingSnapshot, domainImpactToken);
                previewView.put("impactToken", freshToken);
                return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                        "requiresUserConfirmation", true,
                        "currentPreview", previewView));
            }

            DefaultTemplateResetResultDto result = boardStorageService.resetDefaultTemplates(
                    userId, confirmation.domainImpactToken());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Bundled default templates were refreshed atomically. Existing rules and specifications remain unverified until verification is run again.");
            response.put("operation", "reset");
            response.put("templateChangeCount", safeList(result.getTemplateChanges()).size());
            response.put("affectedDeviceCount", safeList(result.getAffectedDevices()).size());
            response.put("environmentChangeCount", safeList(result.getEnvironmentChanges()).size());
            response.put("verificationStatus", "NOT_VERIFIED");
            return successJson(response, "Default templates refreshed.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("reset_default_templates business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("reset_default_templates failed", e);
            return errorJson("Failed to refresh the bundled default templates. No confirmed result is available.",
                    "INTERNAL_ERROR", 500);
        }
    }

    private Map<String, Object> previewView(DefaultTemplateResetResultDto preview) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canApply", preview.isCanApply());
        result.put("editHistoryEntryCount", preview.getEditHistoryEntryCount());
        result.put("templateChanges", safeList(preview.getTemplateChanges()).stream()
                .map(this::templateChangeView)
                .toList());
        result.put("affectedDevices", safeList(preview.getAffectedDevices()).stream()
                .map(this::affectedDeviceView)
                .toList());
        result.put("blockers", safeList(preview.getBlockers()).stream()
                .map(this::blockerView)
                .toList());
        result.put("environmentChanges", safeList(preview.getEnvironmentChanges()).stream()
                .map(this::environmentChangeView)
                .toList());
        return result;
    }

    private Map<String, Object> templateChangeView(DefaultTemplateResetChangeDto change) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateName", change.getTemplateName());
        result.put("changeType", change.getChangeType());
        result.put("semanticsChanged", change.isSemanticsChanged());
        return result;
    }

    private Map<String, Object> affectedDeviceView(DefaultTemplateAffectedDeviceDto device) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceLabel", device.getDeviceLabel());
        result.put("templateName", device.getTemplateName());
        return result;
    }

    private Map<String, Object> blockerView(DefaultTemplateResetBlockerDto blocker) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemLabel", blocker.getItemLabel());
        result.put("reasonCode", blocker.getReasonCode());
        result.put("reason", blocker.getReason());
        return result;
    }

    private Map<String, Object> environmentChangeView(EnvironmentVariableChangeDto change) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changeType", change.getChangeType());
        result.put("name", change.getName());
        result.put("previousValue", environmentValueView(change.getPreviousValue()));
        result.put("currentValue", environmentValueView(change.getCurrentValue()));
        return result;
    }

    private Map<String, Object> environmentValueView(BoardEnvironmentVariableDto value) {
        if (value == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", value.getValue());
        result.put("trust", value.getTrust());
        result.put("privacy", value.getPrivacy());
        return result;
    }
}
