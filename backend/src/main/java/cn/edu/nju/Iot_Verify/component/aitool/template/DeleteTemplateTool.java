package cn.edu.nju.Iot_Verify.component.aitool.template;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDeletionResultDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.TemplateDeletionConflictException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class DeleteTemplateTool extends AbstractAiTool {

    private final BoardStorageService boardStorageService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public DeleteTemplateTool(BoardStorageService boardStorageService,
                              ObjectMapper objectMapper,
                              AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "delete_template";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();
        props.put("templateId", Map.of(
                "type", "integer",
                "description", "The numeric ID of the template to delete (from list_templates result)."
        ));
        props.put("confirmed", Map.of(
                "type", "boolean",
                "description", "Use false to preview the exact template without changing it. Use true only in a later turn after the user explicitly confirms that preview."
        ));
        props.put("impactToken", Map.of(
                "type", "string",
                "description", "Exact impactToken returned by the latest deletion preview. Required when confirmed=true."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("templateId", "confirmed")
        );

        return LlmToolSpec.of(getName(), "Preview or, after explicit two-turn confirmation, delete a device template. Use list_templates first. Deleting a template clears the board edit history (undo/redo), because device snapshots taken earlier depend on the manifest semantics it removes.", schema);
    }

    protected String doExecute(Long userId, String argsJson) {
        Long requestedTemplateId = null;
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of("templateId", "confirmed", "impactToken"));
            long templateId = positiveLongArg(args, "templateId");
            requestedTemplateId = templateId;
            boolean confirmed = booleanArg(args, "confirmed", false);
            DeviceTemplateDeletionResultDto preview =
                    boardStorageService.previewDeviceTemplateDeletion(userId, templateId);
            Map<String, Object> previewView = previewView(preview);
            String domainImpactToken = trimToNull(preview.getImpactToken());
            if (!preview.isCanDelete()) {
                /*
                 * No `clearSession` here, deliberately.
                 *
                 * This branch is a *preview* that declares itself read-only (`readOnlySuccessJson`,
                 * `requiresUserConfirmation: false`) and issues no confirmation of its own — so clearing
                 * could only ever discard one belonging to a different tool. The store is keyed
                 * `(userId, sessionId, Kind.DESTRUCTIVE_ACTION)` with no tool name and holds one row per
                 * session, so "ask whether this template can be deleted" silently invalidated a pending
                 * `delete_device` or `clear_board` the user was about to approve, and the model then got a
                 * `CONFIRMATION_MISSING` it had no way to explain.
                 *
                 * `ApplyFixTool` is the contrasting case and is correct: it clears a confirmation *it just
                 * issued*, because the response carrying the preview could not be delivered and a
                 * confirmation is only valid once the user can inspect what they are approving. Clearing
                 * what you issued is hygiene; clearing what someone else issued is a side effect.
                 */
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("message", "No changes were made. This device type cannot be deleted while listed device instances use it.");
                response.put("requiresUserConfirmation", false);
                response.put("preview", previewView);
                return readOnlySuccessJson(response, "Template deletion preview unavailable.");
            }
            if (domainImpactToken == null) {
                return errorJson("Template deletion preview did not provide an impact token. No changes were made; retry the preview.",
                        "RESULT_UNAVAILABLE", 503);
            }
            Map<String, Object> bindingSnapshot = bindingSnapshot(previewView, domainImpactToken);
            if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
                String confirmationToken = destructiveActionGuard.issue(
                        userId, getName(), Long.toString(templateId),
                        preview.getTemplate().getName(), bindingSnapshot, domainImpactToken);
                previewView.put("impactToken", confirmationToken);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("message", "No changes were made. Confirm this exact preview to delete the device type.");
                response.put("requiresUserConfirmation", true);
                response.put("preview", previewView);
                return readOnlySuccessJson(response, "Template deletion preview unavailable.");
            }

            String impactToken = requiredTextField(args, "impactToken", "arguments");
            AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consume(
                    userId, getName(), Long.toString(templateId), impactToken, bindingSnapshot);
            if (!confirmation.approved()) {
                String freshToken = destructiveActionGuard.issue(
                        userId, getName(), Long.toString(templateId),
                        preview.getTemplate().getName(), bindingSnapshot, domainImpactToken);
                previewView.put("impactToken", freshToken);
                return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                        "requiresUserConfirmation", true,
                        "currentPreview", previewView));
            }
            DeviceTemplateDeletionResultDto deleted =
                    boardStorageService.deleteDeviceTemplate(
                            userId, templateId, confirmation.domainImpactToken());
            return successJson(Map.of(
                    "message", "Template deleted successfully.",
                    "operation", "deleted",
                    "result", deleted
            ), "Template deleted successfully.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("delete_template busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (TemplateDeletionConflictException e) {
            log.warn("delete_template conflict [{}]", e.getReasonCode());
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("requiresUserConfirmation", false);
            DeviceTemplateDeletionResultDto currentPreview = e.getCurrentPreview();
            if (currentPreview != null && requestedTemplateId != null) {
                Map<String, Object> currentView = previewView(currentPreview);
                String currentDomainToken = trimToNull(currentPreview.getImpactToken());
                if (currentPreview.isCanDelete() && currentDomainToken != null) {
                    String freshToken = destructiveActionGuard.issue(
                            userId,
                            getName(),
                            Long.toString(requestedTemplateId),
                            currentPreview.getTemplate().getName(),
                            bindingSnapshot(currentView, currentDomainToken),
                            currentDomainToken);
                    currentView.put("impactToken", freshToken);
                    extras.put("requiresUserConfirmation", true);
                }
                extras.put("currentPreview", currentView);
            }
            return errorJson(e.getMessage(), e.getReasonCode(), 409, extras);
        } catch (BaseException e) {
            log.warn("delete_template business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("delete_template failed", e);
            return errorJson("Failed to delete template.", "INTERNAL_ERROR", 500);
        }
    }

    private Map<String, Object> previewView(DeviceTemplateDeletionResultDto preview) {
        Map<String, Object> view = objectMapper.convertValue(
                preview, new TypeReference<LinkedHashMap<String, Object>>() { });
        view.remove("impactToken");
        return view;
    }

    private Map<String, Object> bindingSnapshot(Map<String, Object> previewView,
                                                String domainImpactToken) {
        return Map.of(
                "preview", previewView,
                "domainImpactToken", domainImpactToken);
    }
}
