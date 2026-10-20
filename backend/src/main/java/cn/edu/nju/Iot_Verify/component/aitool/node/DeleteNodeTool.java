package cn.edu.nju.Iot_Verify.component.aitool.node;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.device.DeviceDeletionResultDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class DeleteNodeTool extends AbstractAiTool {

    private final BoardStorageService boardStorageService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public DeleteNodeTool(BoardStorageService boardStorageService,
                          ObjectMapper objectMapper,
                          AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "delete_device";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();
        props.put("id", Map.of(
                "type", "string",
                "description", "Canonical board node id of the device to delete."
        ));
        props.put("confirmed", Map.of(
                "type", "boolean",
                "description", "Use false to preview the device and every related rule, specification, and Environment Pool effect. Use true only in a later turn after the user explicitly confirms that exact preview."
        ));
        props.put("impactToken", Map.of(
                "type", "string",
                "description", "Required when confirmed=true. Copy the opaque impactToken from the latest preview; it binds confirmation to the complete deletion impact."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("id", "confirmed")
        );

        return LlmToolSpec.of(getName(),
                "Preview or, after explicit user confirmation, delete a device and every related rule, specification, and Environment Pool effect.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of("id", "confirmed", "impactToken"));
            String id = requiredDeviceIdField(args, "id", "arguments");

            boolean confirmed = booleanArg(args, "confirmed", false);
            DeviceDeletionResultDto preview = boardStorageService.previewNodeDeletion(userId, id);
            String currentImpactToken = trimToNull(preview.getImpactToken());
            if (currentImpactToken == null) {
                return errorJson("Device deletion preview did not provide an impact token. No changes were made; retry the preview.",
                        "RESULT_UNAVAILABLE", 503);
            }
            Map<String, Object> previewSummary = previewSummary(preview);
            Map<String, Object> bindingSnapshot = Map.of(
                    "preview", previewSummary,
                    "domainImpactToken", currentImpactToken);
            if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
                String confirmationToken = destructiveActionGuard.issue(
                        userId, getName(), id,
                        preview.getDeletedDevice().getLabel(), bindingSnapshot, currentImpactToken);
                Map<String, Object> response = previewResponse(previewSummary, confirmationToken);
                return readOnlySuccessJson(response, "Device deletion preview unavailable.");
            }

            String suppliedImpactToken = nullableTextField(args, "impactToken", "arguments");
            if (suppliedImpactToken == null) {
                return errorJson("impactToken is required when confirmed=true. Request a fresh deletion preview first.",
                        "VALIDATION_ERROR", 400);
            }
            AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consume(
                    userId, getName(), id, suppliedImpactToken, bindingSnapshot);
            if (!confirmation.approved()) {
                String freshToken = destructiveActionGuard.issue(
                        userId, getName(), id,
                        preview.getDeletedDevice().getLabel(), bindingSnapshot, currentImpactToken);
                return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                        "requiresUserConfirmation", true,
                        "currentPreview", previewResponse(previewSummary, freshToken)));
            }

            DeviceDeletionResultDto result = boardStorageService.deleteNodeCascade(
                    userId, id, confirmation.domainImpactToken());
            log.info("Executed delete_device, id={}, label={}", id, result.getDeletedDevice().getLabel());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Device deleted successfully.");
            response.put("operation", "deleted");
            response.put("deletedDevice", result.getDeletedDevice());
            response.put("removedRuleCount", result.getRemovedRules().size());
            response.put("removedRules", result.getRemovedRules());
            response.put("removedSpecificationCount", result.getRemovedSpecifications().size());
            response.put("removedSpecifications", result.getRemovedSpecifications());
            response.put("environmentChanges", result.getEnvironmentChanges());
            response.put("remainingDeviceCount", result.getCurrentNodes().size());
            return successJson(response, "Device deleted successfully.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ResourceNotFoundException e) {
            return errorJson(e.getMessage(), "NOT_FOUND", 404);
        } catch (ServiceUnavailableException e) {
            log.warn("delete_device busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("delete_device business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("delete_device failed", e);
            return errorJson("Delete device failed. Please retry.", "INTERNAL_ERROR", 500);
        }
    }

    private Map<String, Object> previewSummary(DeviceDeletionResultDto preview) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("device", preview.getDeletedDevice());
        summary.put("wouldRemoveRuleCount", preview.getRemovedRules().size());
        summary.put("wouldRemoveRules", preview.getRemovedRules());
        summary.put("wouldRemoveSpecificationCount", preview.getRemovedSpecifications().size());
        summary.put("wouldRemoveSpecifications", preview.getRemovedSpecifications());
        summary.put("wouldChangeEnvironmentVariableCount", preview.getEnvironmentChanges().size());
        summary.put("environmentChanges", preview.getEnvironmentChanges());
        return summary;
    }

    private Map<String, Object> previewResponse(Map<String, Object> summary, String impactToken) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "No changes were made. Explicit user confirmation is required before deleting this device and applying every related rule, specification, and Environment Pool consequence.");
        response.put("operation", "preview");
        response.put("requiresUserConfirmation", true);
        response.putAll(summary);
        response.put("impactToken", impactToken);
        return response;
    }

}
