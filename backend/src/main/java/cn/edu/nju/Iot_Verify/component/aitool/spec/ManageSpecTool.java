package cn.edu.nju.Iot_Verify.component.aitool.spec;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.BoardSemanticValidator;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import cn.edu.nju.Iot_Verify.util.SpecificationFormulaPreview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
public class ManageSpecTool extends AbstractAiTool {

    private static final Set<String> TARGET_TYPES = Set.of("state", "mode", "variable", "api", "trust", "privacy");
    private static final Set<String> API_STATE_RELATIONS = Set.of("=", "!=", "in", "not in");
    private static final Set<String> TEMPLATE_IDS = Set.of("1", "2", "3", "4", "5", "6", "7");

    private final BoardStorageService boardStorageService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public ManageSpecTool(BoardStorageService boardStorageService,
                          ObjectMapper objectMapper,
                          AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "manage_spec";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> conditionItemSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "deviceId", Map.of("type", "string", "description", "Canonical device node ID on the board"),
                        "deviceLabel", Map.of("type", "string", "description", "Optional display label; ignored for identity resolution"),
                        "targetType", Map.of("type", "string", "enum", List.of("state", "mode", "variable", "api", "trust", "privacy"),
                                "description", "What to check: full state, mode value, variable, API signal, trust level, or privacy level"),
                        "key", Map.of("type", "string", "description", "The key to check: state for full state; mode name for mode; variable name for variable; Signal=true API name for api; mode name or variable name for trust/privacy according to propertyScope"),
                        "propertyScope", Map.of("type", "string", "enum", List.of("state", "variable"),
                                "description", "Required only for trust/privacy. state checks the label of the currently active state in the mode named by key; variable checks the variable named by key."),
                        "variableSource", Map.of("type", "string", "enum", List.of("environment", "reported"),
                                "description", "Required for variable, rejected otherwise. environment checks the real value in the home, independent of any device's report; reported checks what this device says. They differ when the device is compromised, so there is no safe default. environment needs a shared variable (IsInside=false); a device-local variable accepts only reported."),
                        "relation", Map.of("type", "string", "description", "Comparison. Required for non-API conditions. All value conditions support =, !=, in, not in; numeric variables additionally support >, <, >=, <=. Omit with value for an API condition to materialize '= TRUE'."),
                        "value", Map.of("type", "string", "description", "Expected value. Required for non-API conditions. API uses TRUE/FALSE and defaults with an omitted relation/value pair to TRUE; trust uses trusted/untrusted; privacy uses public/private")
                ),
                "required", List.of("deviceId", "targetType", "key"),
                "additionalProperties", false
        );

        Map<String, Object> props = new HashMap<>();
        props.put("action", Map.of(
                "type", "string",
                "enum", List.of("add", "delete"),
                "description", "Action to perform: add to create a new specification, delete to remove one."
        ));
        props.put("templateId", Map.of(
                "type", "string",
                "description", "Required for add. Template ID 1,2,3,7 use aConditions only; 4,5,6 use ifConditions and thenConditions only."
        ));
        props.put("aConditions", Map.of(
                "type", "array",
                "description", "For add. A-part conditions. Required for templateId 1,2,3,7 and must be empty for 4,5,6.",
                "items", conditionItemSchema
        ));
        props.put("ifConditions", Map.of(
                "type", "array",
                "description", "For add. IF-part conditions. Required for templateId 4,5,6 and must be empty for 1,2,3,7.",
                "items", conditionItemSchema
        ));
        props.put("thenConditions", Map.of(
                "type", "array",
                "description", "For add. THEN-part conditions. Required for templateId 4,5,6 and must be empty for 1,2,3,7.",
                "items", conditionItemSchema
        ));
        props.put("specId", Map.of(
                "type", "string",
                "description", "Required for delete. The ID of the specification to delete."
        ));
        props.put("confirmed", Map.of(
                "type", "boolean",
                "description", "For delete: use false to preview the exact specification without changing it; use true only in a later turn after the user explicitly confirms that preview. Omit it entirely for add, which accepts no confirmation fields."
        ));
        props.put("impactToken", Map.of(
                "type", "string",
                "description", "For delete with confirmed=true, copy the opaque impactToken from the latest preview."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema("object", props, List.of("action"));

        return LlmToolSpec.of(getName(), "Add a formal specification, or preview/delete one through explicit two-turn confirmation. Use list_specs before deleting.", schema);
    }

    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of(
                    "action", "templateId", "aConditions", "ifConditions", "thenConditions",
                    "specId", "confirmed", "impactToken"));
            String action = requiredTextField(args, "action", "arguments").toLowerCase(Locale.ROOT);

            return switch (action) {
                case "add" -> executeAdd(userId, args);
                case "delete" -> executeDelete(userId, args);
                default -> errorJson("Unknown action: " + action + ". Use 'add' or 'delete'.",
                        "VALIDATION_ERROR", 400);
            };
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (IllegalArgumentException e) {
            log.warn("manage_spec validation failed: {}", e.getMessage());
            return errorJson(e.getMessage(), "VALIDATION_ERROR", 400);
        } catch (ServiceUnavailableException e) {
            log.warn("manage_spec busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("manage_spec business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("manage_spec failed", e);
            return errorJson("Spec operation failed. Please check parameters and retry.",
                    "INTERNAL_ERROR", 500);
        }
    }

    private String executeAdd(Long userId, JsonNode args) throws Exception {
        requireOnlyFields(args, "arguments", Set.of(
                "action", "templateId", "aConditions", "ifConditions", "thenConditions"));
        String templateId = requiredTextField(args, "templateId", "arguments");
        if (!TEMPLATE_IDS.contains(templateId)) {
            return errorJson("Unsupported templateId '" + templateId + "'. Allowed: 1,2,3,4,5,6,7.",
                    "VALIDATION_ERROR", 400);
        }
        DeviceLookup deviceLookup = buildDeviceLookup(userId);
        List<SpecConditionDto> aConditions = parseConditions(args.path("aConditions"), "a", deviceLookup);
        List<SpecConditionDto> ifConditions = parseConditions(args.path("ifConditions"), "if", deviceLookup);
        List<SpecConditionDto> thenConditions = parseConditions(args.path("thenConditions"), "then", deviceLookup);

        if (aConditions.isEmpty() && ifConditions.isEmpty() && thenConditions.isEmpty()) {
            return errorJson("At least one valid condition is required in 'aConditions', 'ifConditions', or 'thenConditions'.",
                    "VALIDATION_ERROR", 400);
        }

        String templateCheckError = validateTemplateShape(templateId, aConditions, ifConditions, thenConditions);
        if (templateCheckError != null) {
            return errorJson(templateCheckError, "VALIDATION_ERROR", 400);
        }

        for (ConditionGroup group : List.of(
                new ConditionGroup("a", aConditions),
                new ConditionGroup("if", ifConditions),
                new ConditionGroup("then", thenConditions))) {
            BoardSemanticValidator.GroupValidationIssue groupIssue =
                    BoardSemanticValidator.validateSpecConditionGroup(
                            deviceLookup.semanticContext(), group.conditions(), group.side());
            if (groupIssue != null) {
                return errorJson(groupIssue.message(), groupIssue.reasonCode(), 400);
            }
        }

        SpecificationDto spec = new SpecificationDto();
        spec.setId(UUID.randomUUID().toString());
        spec.setTemplateId(templateId);
        spec.setAConditions(aConditions);
        spec.setIfConditions(ifConditions);
        spec.setThenConditions(thenConditions);

        CollectionMutationResultDto<SpecificationDto> mutation = boardStorageService.addSpec(userId, spec);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Specification created as a structured property. It has not been formally verified.");
        response.put("operation", "created");
        response.put("verificationStatus", "NOT_VERIFIED");
        response.put("specification", SpecificationToolPresenter.present(
                mutation.getAffectedItem(), deviceLookup.presentationContext()));
        response.put("totalSpecs", mutation.getCurrentCount());
        return successJson(response, "Specification created but not formally verified.");
    }

    private String executeDelete(Long userId, JsonNode args) throws Exception {
        requireOnlyFields(args, "arguments", Set.of("action", "specId", "confirmed", "impactToken"));
        String specId = requiredTextField(args, "specId", "arguments");

        SpecificationDto target = safeList(boardStorageService.getSpecs(userId)).stream()
                .filter(spec -> spec != null && specId.equals(spec.getId()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return errorJson("Specification not found: " + specId, "NOT_FOUND", 404);
        }

        Map<String, Object> presentedSpecification = SpecificationToolPresenter.present(
                target, currentPresentationContext(userId));
        Map<String, Object> bindingSnapshot = Map.of(
                "specification", presentedSpecification);
        boolean confirmed = booleanArg(args, "confirmed", false);
        if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
            String impactToken = destructiveActionGuard.issue(
                    userId, getName(), specId,
                    (String) presentedSpecification.get("description"), bindingSnapshot, null);
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("message", "No changes were made. Explicit user confirmation is required before deleting this specification.");
            preview.put("operation", "preview");
            preview.put("requiresUserConfirmation", true);
            preview.put("specification", presentedSpecification);
            preview.put("impactToken", impactToken);
            return readOnlySuccessJson(preview, "Specification deletion preview unavailable.");
        }

        String impactToken = requiredTextField(args, "impactToken", "arguments");
        AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consume(
                userId, getName(), specId, impactToken, bindingSnapshot);
        if (!confirmation.approved()) {
            String freshToken = destructiveActionGuard.issue(
                    userId, getName(), specId,
                    (String) presentedSpecification.get("description"), bindingSnapshot, null);
            return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                    "requiresUserConfirmation", true,
                    "currentPreview", Map.of(
                            "operation", "preview",
                            "specification", presentedSpecification,
                            "impactToken", freshToken)));
        }

        CollectionMutationResultDto<SpecificationDto> mutation;
        try {
            mutation = boardStorageService.removeSpecIfUnchanged(userId, specId, target);
        } catch (ConflictException conflict) {
            SpecificationDto current = safeList(boardStorageService.getSpecs(userId)).stream()
                    .filter(spec -> spec != null && specId.equals(spec.getId()))
                    .findFirst()
                    .orElse(null);
            if (current == null) {
                return errorJson("Specification not found: " + specId, "NOT_FOUND", 404);
            }
            Map<String, Object> currentPresentation = SpecificationToolPresenter.present(
                    current, currentPresentationContext(userId));
            Map<String, Object> currentBinding = Map.of("specification", currentPresentation);
            String freshToken = destructiveActionGuard.issue(
                    userId, getName(), specId,
                    (String) currentPresentation.get("description"), currentBinding, null);
            return errorJson(conflict.getMessage(), "CONFIRMATION_STALE", 409, Map.of(
                    "requiresUserConfirmation", true,
                    "currentPreview", Map.of(
                            "operation", "preview",
                            "specification", currentPresentation,
                            "impactToken", freshToken)));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Specification deleted successfully.");
        response.put("operation", "deleted");
        response.put("deletedSpecification", SpecificationToolPresenter.present(
                mutation.getAffectedItem(),
                currentPresentationContext(userId)));
        response.put("totalSpecs", mutation.getCurrentCount());
        return successJson(response, "Specification deleted successfully.");
    }

    private List<SpecConditionDto> parseConditions(JsonNode node,
                                                   String side,
                                                   DeviceLookup deviceLookup) throws ArgValidationException {
        List<SpecConditionDto> conditions = new ArrayList<>();
        if (node == null || node.isMissingNode()) {
            return conditions;
        }
        String collectionPath = "arguments." + side + "Conditions";
        if (!node.isArray()) {
            throw new ArgValidationException(errorJson(
                    collectionPath + " must be a JSON array when provided.",
                    "VALIDATION_ERROR", 400));
        }

        int index = 0;
        for (JsonNode cn : node) {
            String displaySide = side == null ? "UNKNOWN" : side.trim().toUpperCase(Locale.ROOT);
            String conditionLabel = "Condition " + (index + 1) + " on '" + displaySide + "'";
            String conditionPath = collectionPath + "[" + index + "]";
            requireOnlyFields(cn, conditionPath, Set.of(
                    "deviceId", "deviceLabel", "targetType", "key", "propertyScope", "variableSource",
                    "relation", "value"));
            String inputDeviceId = nullableDeviceIdField(cn, "deviceId", conditionPath);
            String deviceId = resolveDeviceIdById(inputDeviceId, deviceLookup);
            if (deviceId == null) {
                throw new IllegalArgumentException(conditionLabel
                        + " must include an existing deviceId.");
            }
            String targetType = nullableTextField(cn, "targetType", conditionPath);
            String key = nullableTextField(cn, "key", conditionPath);

            if (deviceId == null || targetType == null || key == null) {
                throw new IllegalArgumentException(conditionLabel
                        + " must include non-empty deviceId, targetType, and key.");
            }

            String normalizedTargetType = targetType.toLowerCase(Locale.ROOT);
            if (!TARGET_TYPES.contains(normalizedTargetType)) {
                throw new IllegalArgumentException(conditionLabel
                        + " has unsupported targetType '" + targetType
                        + "'. Allowed: state, mode, variable, api, trust, privacy.");
            }
            String propertyScope = nullableTextField(cn, "propertyScope", conditionPath);
            if ("trust".equals(normalizedTargetType) || "privacy".equals(normalizedTargetType)) {
                propertyScope = propertyScope == null ? null : propertyScope.toLowerCase(Locale.ROOT);
                if (!Set.of("state", "variable").contains(propertyScope)) {
                    throw new IllegalArgumentException(conditionLabel
                            + " requires propertyScope='state' or 'variable' for trust/privacy.");
                }
            } else if (propertyScope != null) {
                throw new IllegalArgumentException(conditionLabel
                        + " may use propertyScope only with trust/privacy.");
            }

            // Whether the condition asks about the home or about this device's report. Refused here rather
            // than defaulted, for the same reason the DTO carries no default: the two answers differ once a
            // device is compromised, so choosing one silently would put words in the author's mouth.
            // Refusing it here gives the model a message it can act on, rather than the field-path error it
            // would get from `addSpec`, which this tool delegates to. Whether `environment` is legal for
            // this particular declaration needs the manifest, and that check lives in the storage-side
            // semantic validation.
            String variableSource = nullableTextField(cn, "variableSource", conditionPath);
            if ("variable".equals(normalizedTargetType)) {
                variableSource = variableSource == null ? null : variableSource.toLowerCase(Locale.ROOT);
                // Null-checked before the set: Set.of(...).contains(null) throws NPE, which would surface an
                // omitted field as a 500 instead of the message that tells the model what to send.
                if (variableSource == null || !Set.of("environment", "reported").contains(variableSource)) {
                    throw new IllegalArgumentException(conditionLabel
                            + " requires variableSource='environment' (the real value in the home) or "
                            + "'reported' (what this device says) for a variable condition.");
                }
            } else if (variableSource != null) {
                throw new IllegalArgumentException(conditionLabel
                        + " may use variableSource only with variable.");
            }

            String relationInput = nullableTextField(cn, "relation", conditionPath);
            String relation = normalizeRelation(relationInput);
            String value = nullableTextField(cn, "value", conditionPath);

            if (relationInput != null && relation == null) {
                throw new IllegalArgumentException(conditionLabel
                        + " has unsupported relation '" + relationInput
                        + "'. Allowed: =, !=, >, <, >=, <=, in, not in.");
            }

            if ("api".equals(normalizedTargetType)) {
                if (relation == null) {
                    relation = "=";
                }
                if (value == null) {
                    value = "TRUE";
                }
            }

            if (relation == null || value == null) {
                throw new IllegalArgumentException(conditionLabel
                        + " requires relation/value (api allows default '= TRUE').");
            }

            if (("in".equals(relation) || "not in".equals(relation)) && isEmptyValueList(value)) {
                throw new IllegalArgumentException(conditionLabel
                        + " has empty value list for relation '" + relation + "'.");
            }

            if (("state".equals(normalizedTargetType)
                    || "mode".equals(normalizedTargetType)
                    || "api".equals(normalizedTargetType)
                    || "trust".equals(normalizedTargetType)
                    || "privacy".equals(normalizedTargetType))
                    && !API_STATE_RELATIONS.contains(relation)) {
                throw new IllegalArgumentException(conditionLabel
                        + " targetType='" + normalizedTargetType + "' only supports =, !=, in, not in.");
            }

            SpecConditionDto dto = new SpecConditionDto();
            dto.setId(UUID.randomUUID().toString());
            dto.setSide(side);
            dto.setDeviceId(deviceId);
            dto.setDeviceLabel(deviceLookup.labelsById().getOrDefault(deviceId, deviceId));
            dto.setTargetType(normalizedTargetType);
            dto.setKey(key);
            dto.setPropertyScope(propertyScope);
            dto.setVariableSource(variableSource);
            dto.setRelation(relation);
            dto.setValue(value);
            String semanticError = BoardSemanticValidator.validateSpecCondition(
                    deviceLookup.semanticContext(),
                    dto,
                    side,
                    index
            );
            if (semanticError != null) {
                throw new IllegalArgumentException(semanticError);
            }
            conditions.add(dto);
            index++;
        }
        return conditions;
    }

    private DeviceLookup buildDeviceLookup(Long userId) {
        List<DeviceNodeDto> nodes = safeList(boardStorageService.getNodes(userId));
        List<DeviceTemplateDto> templates = safeList(boardStorageService.getDeviceTemplates(userId));
        Map<String, String> idsByKey = new HashMap<>();
        Map<String, String> labelsById = new HashMap<>();
        for (DeviceNodeDto node : nodes) {
            if (node == null) {
                continue;
            }
            String id = node.getId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            idsByKey.put(normalizeLookupKey(id), id);
            String label = trimToNull(node.getLabel());
            if (label != null) {
                labelsById.put(id, label);
            }
        }
        return new DeviceLookup(
                idsByKey,
                labelsById,
                BoardSemanticValidator.context(
                        nodes, templates, safeList(boardStorageService.getEnvironmentVariables(userId))),
                SpecificationToolPresenter.context(nodes, templates)
        );
    }

    private SpecificationFormulaPreview.Context currentPresentationContext(Long userId) {
        return SpecificationToolPresenter.context(
                safeList(boardStorageService.getNodes(userId)),
                safeList(boardStorageService.getDeviceTemplates(userId)));
    }

    private String resolveDeviceIdById(String value, DeviceLookup lookup) {
        if (value == null || lookup == null || lookup.idsByKey().isEmpty()) {
            return null;
        }
        return lookup.idsByKey().get(normalizeLookupKey(value));
    }

    private String normalizeLookupKey(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private record DeviceLookup(Map<String, String> idsByKey,
                                Map<String, String> labelsById,
                                BoardSemanticValidator.BoardContext semanticContext,
                                SpecificationFormulaPreview.Context presentationContext) {
    }

    private String validateTemplateShape(String templateId,
                                         List<SpecConditionDto> aConditions,
                                         List<SpecConditionDto> ifConditions,
                                         List<SpecConditionDto> thenConditions) {
        // single-side templates use aConditions only
        if ("1".equals(templateId) || "2".equals(templateId) || "3".equals(templateId) || "7".equals(templateId)) {
            if (aConditions.isEmpty()) {
                return "Template " + templateId + " requires non-empty aConditions.";
            }
            if (!ifConditions.isEmpty() || !thenConditions.isEmpty()) {
                return "Template " + templateId + " uses aConditions only; ifConditions and thenConditions must be empty.";
            }
            if ("7".equals(templateId)) {
                for (int index = 0; index < aConditions.size(); index++) {
                    SpecConditionDto condition = aConditions.get(index);
                    String targetType = condition.getTargetType();
                    String relation = normalizeRelation(condition.getRelation());
                    if ("trust".equals(targetType) || "privacy".equals(targetType)) {
                        return "Template 7 derives the MEDIC control-source label automatically; condition " + (index + 1)
                                + " must describe a protected state, mode, variable, or signal API.";
                    }
                    if (("state".equals(targetType) || "mode".equals(targetType)) && !"=".equals(relation)) {
                        return "Template 7 state and mode condition " + (index + 1) + " must use '='.";
                    }
                    if ("api".equals(targetType)
                            && (!"=".equals(relation) || !"TRUE".equalsIgnoreCase(condition.getValue()))) {
                        return "Template 7 API condition " + (index + 1) + " must use '= TRUE'.";
                    }
                }
            }
            return null;
        }

        // implication templates require both if and then
        if ("4".equals(templateId) || "5".equals(templateId) || "6".equals(templateId)) {
            if (ifConditions.isEmpty() || thenConditions.isEmpty()) {
                return "Template " + templateId + " requires non-empty ifConditions and thenConditions.";
            }
            if (!aConditions.isEmpty()) {
                return "Template " + templateId + " uses ifConditions/thenConditions only; aConditions must be empty.";
            }
            return null;
        }
        return null;
    }

    private String normalizeRelation(String relation) {
        String normalized = SmvRelationUtils.normalizeRelation(relation);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return SmvRelationUtils.isSupportedRelation(normalized) ? normalized : null;
    }

    private boolean isEmptyValueList(String value) {
        if (value == null) {
            return true;
        }
        for (String item : value.split("[,;|]")) {
            if (item != null && !item.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private record ConditionGroup(String side, List<SpecConditionDto> conditions) {
    }
}
