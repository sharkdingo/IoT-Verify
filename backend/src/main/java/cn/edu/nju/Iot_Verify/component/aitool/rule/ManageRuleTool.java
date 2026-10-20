package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.BoardSemanticValidator;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import cn.edu.nju.Iot_Verify.util.RulePreviewText;
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

@Slf4j
@Component
public class ManageRuleTool extends AbstractAiTool {

    private static final Set<String> TARGET_TYPES = Set.of("api", "variable", "mode", "state");
    private static final Set<String> ENUM_RELATIONS = Set.of("=", "!=", "in", "not in");

    private final BoardStorageService boardStorageService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public ManageRuleTool(BoardStorageService boardStorageService,
                          ObjectMapper objectMapper,
                          AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "manage_rule";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();

        props.put("action", Map.of(
                "type", "string",
                "enum", List.of("add", "update", "delete", "reorder"),
                "description", "Action to perform: 'add' to create a new rule, 'update' to edit one existing rule in place, 'delete' to remove an existing rule, 'reorder' to set the exact execution order of all existing rules. Rule execution order is verification-significant."
        ));

        props.put("conditions", Map.of(
                "type", "array",
                "description", "Required for 'add' and 'update'. Each condition: {deviceId, deviceLabel?, attribute, targetType, relation, value}. deviceId is the canonical board device id returned by board_overview or recommend_rules; deviceLabel is display-only. targetType=api is an event signal: attribute must be a Signal=true API and relation/value must be omitted. targetType=state/mode/variable requires relation and value; all value conditions support =, !=, in, not in; numeric variables additionally support >, <, >=, <=.",
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "deviceId", Map.of("type", "string"),
                                "deviceLabel", Map.of("type", "string", "description", "Optional display snapshot; ignored for identity resolution."),
                                "attribute", Map.of("type", "string"),
                                "targetType", Map.of("type", "string", "enum", List.of("api", "variable", "mode", "state")),
                                "relation", Map.of("type", "string"),
                                "value", Map.of("type", "string")
                        ),
                        "required", List.of("deviceId", "attribute", "targetType"),
                        "additionalProperties", false
                )
        ));

        props.put("command", Map.of(
                "type", "object",
                "description", "Required for 'add' and 'update'. The action to execute when conditions are met. deviceId/contentDevice must be canonical board device ids; labels are display-only.",
                "properties", Map.of(
                        "deviceId", Map.of("type", "string"),
                        "deviceLabel", Map.of("type", "string", "description", "Optional display snapshot; ignored for identity resolution."),
                        "action", Map.of("type", "string"),
                        "contentDevice", Map.of("type", "string"),
                        "contentDeviceLabel", Map.of("type", "string", "description", "Optional display snapshot; ignored for identity resolution."),
                        "content", Map.of("type", "string")
                ),
                "required", List.of("deviceId", "action"),
                "additionalProperties", false
        ));

        props.put("label", Map.of(
                "type", "string",
                "description", "Optional user-facing rule name. It does not define behavior. If omitted, add generates a readable label; update preserves the existing label."
        ));

        props.put("ruleId", Map.of(
                "type", "integer",
                "description", "Required for 'update' or 'delete'. Copy the existing ruleId from list_rules."
        ));
        props.put("expectedRule", Map.of(
                "type", "object",
                "description", "Required only for update. Copy the complete unchanged rule object from the latest list_rules result, including its display fields. The server rejects a changed baseline. Send the desired conditions/command separately; copy their schema fields without summary/description. Update keeps this rule's ID and execution position, preserves other rules, and supports undo."
        ));
        props.put("confirmed", Map.of(
                "type", "boolean",
                "description", "For delete: use false to preview the exact rule without changing it; use true only in a later turn after the user explicitly confirms that preview. Omit it entirely for add/update, which accept no confirmation fields."
        ));
        props.put("impactToken", Map.of(
                "type", "string",
                "description", "For delete with confirmed=true, copy the opaque impactToken from the latest preview."
        ));
        props.put("ruleIds", Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "Required for 'reorder'. The complete new execution order as rule IDs (from list_rules). Must contain every current rule id exactly once, must differ from expectedRuleIds, and the first id runs first. Reorder is a reversible full-list replacement and needs no confirmation."
        ));
        props.put("expectedRuleIds", Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "Required for 'reorder'. Copy the complete current rule ID order from the same list_rules result used to construct ruleIds. The reorder is rejected if this baseline changed."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("action")
        );

        return LlmToolSpec.of(getName(), "Add an automation rule, update an existing rule in place, preview/delete one through explicit two-turn confirmation, or reorder all rules. Use list_rules before updating, deleting or reordering. For a requested threshold or condition edit use update; never add a replacement and delete the original. Applying a formal fix_violation proposal, including rule removal, must use apply_fix instead so its verified-proposal guards are retained.", schema);
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
                    "action", "conditions", "command", "label", "ruleId", "ruleIds",
                    "expectedRuleIds", "expectedRule", "confirmed", "impactToken"));
            String action = requiredTextField(args, "action", "arguments").toLowerCase(Locale.ROOT);

            return switch (action) {
                case "add" -> executeAdd(userId, args);
                case "update" -> executeUpdate(userId, args);
                case "delete" -> executeDelete(userId, args);
                case "reorder" -> executeReorder(userId, args);
                default -> errorJson("Unknown action: " + action + ". Use 'add', 'update', 'delete', or 'reorder'.",
                        "VALIDATION_ERROR", 400);
            };
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("manage_rule busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("manage_rule business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("manage_rule failed", e);
            return errorJson("Rule operation failed. Please check parameters and retry.",
                    "INTERNAL_ERROR", 500);
        }
    }

    private String executeAdd(Long userId, JsonNode args) throws Exception {
        requireOnlyFields(args, "arguments", Set.of("action", "conditions", "command", "label"));
        List<DeviceNodeDto> nodes = safeList(boardStorageService.getNodes(userId));
        RuleDto newRule = parseRule(userId, args, nodes,
                safeList(boardStorageService.getDeviceTemplates(userId)),
                safeList(boardStorageService.getEnvironmentVariables(userId)));

        CollectionMutationResultDto<RuleDto> mutation = boardStorageService.addRule(userId, newRule);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Rule created at the end of the execution order. It has not been formally verified.");
        response.put("operation", "created");
        response.put("verificationStatus", "NOT_VERIFIED");
        response.put("executionOrder", mutation.getCurrentCount());
        response.put("rule", RuleToolPresenter.present(mutation.getAffectedItem(), nodes));
        response.put("totalRules", mutation.getCurrentCount());
        return successJson(response, "Rule created but not formally verified.");
    }

    /** Add and update share the same strict condition, command and conjunction admission. */
    private RuleDto parseRule(Long userId, JsonNode args, List<DeviceNodeDto> nodes,
                              List<DeviceTemplateDto> templates,
                              List<BoardEnvironmentVariableDto> environment)
            throws ArgValidationException {
        JsonNode conditionsNode = args.path("conditions");
        JsonNode commandNode = args.path("command");
        Set<String> deviceIds = getDeviceIds(nodes);
        Map<String, String> labelsById = labelsById(nodes);
        BoardSemanticValidator.BoardContext semanticContext = BoardSemanticValidator.context(nodes, templates, environment);

        if (conditionsNode.isMissingNode() || !conditionsNode.isArray() || conditionsNode.isEmpty()) {
            throw new ArgValidationException(errorJson("'conditions' array is required for add/update.",
                    "VALIDATION_ERROR", 400));
        }
        if (commandNode.isMissingNode() || !commandNode.isObject()) {
            throw new ArgValidationException(errorJson("'command' object is required for add/update.",
                    "VALIDATION_ERROR", 400));
        }

        List<RuleDto.Condition> conditions = new ArrayList<>();
        int index = 0;
        for (JsonNode cn : conditionsNode) {
            String conditionPath = "arguments.conditions[" + index + "]";
            requireOnlyFields(cn, conditionPath, Set.of(
                    "deviceId", "deviceLabel", "attribute", "targetType", "relation", "value"));
            String deviceName = nullableDeviceIdField(cn, "deviceId", conditionPath);
            String attribute = nullableTextField(cn, "attribute", conditionPath);
            if (deviceName == null || attribute == null) {
                throw new ArgValidationException(errorJson("Condition " + (index + 1) + " must include non-empty 'deviceId' and 'attribute'.",
                        "VALIDATION_ERROR", 400));
            }

            String relationInput = nullableTextField(cn, "relation", conditionPath);
            String relation = normalizeRelation(relationInput);
            String value = nullableTextField(cn, "value", conditionPath);
            String targetTypeInput = nullableTextField(cn, "targetType", conditionPath);
            String targetType = normalizeTargetType(targetTypeInput);

            if (targetTypeInput == null) {
                throw new ArgValidationException(errorJson("Condition " + (index + 1) + " must include non-empty 'targetType'.",
                        "VALIDATION_ERROR", 400));
            }
            if (targetType == null) {
                throw new ArgValidationException(errorJson("Unsupported targetType '" + targetTypeInput + "' in condition " + (index + 1)
                        + ". Allowed: api, variable, mode, state.",
                        "VALIDATION_ERROR", 400));
            }
            if (!deviceIds.contains(deviceName)) {
                throw new ArgValidationException(errorJson("Unknown deviceId '" + deviceName + "' in condition " + (index + 1)
                        + ". Use a canonical id returned by board_overview.",
                        "VALIDATION_ERROR", 400));
            }

            if (relationInput != null && relation == null) {
                throw new ArgValidationException(errorJson("Unsupported relation '" + relationInput + "' in condition " + (index + 1)
                        + ". Allowed: =, !=, >, <, >=, <=, in, not in.",
                        "VALIDATION_ERROR", 400));
            }

            if ("api".equals(targetType)) {
                if (relation != null || value != null) {
                    throw new ArgValidationException(errorJson("API signal condition " + (index + 1) + " must not include relation or value.",
                            "VALIDATION_ERROR", 400));
                }
            } else {
                if (relation == null || value == null) {
                    throw new ArgValidationException(errorJson("Condition relation and value are required for targetType '" + targetType
                            + "' in condition " + (index + 1) + ".",
                            "VALIDATION_ERROR", 400));
                }
            }

            if (("in".equals(relation) || "not in".equals(relation)) && isEmptyValueList(value)) {
                throw new ArgValidationException(errorJson("Condition value list cannot be empty for relation '" + relation
                        + "' in condition " + (index + 1) + ".",
                        "VALIDATION_ERROR", 400));
            }

            if (("state".equals(targetType) || "mode".equals(targetType))
                    && !ENUM_RELATIONS.contains(relation)) {
                throw new ArgValidationException(errorJson("Condition targetType '" + targetType
                                + "' only supports =, !=, in, not in. Invalid condition: " + (index + 1),
                        "VALIDATION_ERROR", 400));
            }

            RuleDto.Condition condition = RuleDto.Condition.builder()
                    .deviceName(deviceName)
                    .attribute(attribute)
                    .targetType(targetType)
                    .relation(relation)
                    .value(value)
                    .build();
            String semanticError = BoardSemanticValidator.validateRuleCondition(semanticContext, condition, index);
            if (semanticError != null) {
                throw new ArgValidationException(errorJson(semanticError, "VALIDATION_ERROR", 400));
            }
            conditions.add(condition);
            index++;
        }

        requireOnlyFields(commandNode, "arguments.command", Set.of(
                "deviceId", "deviceLabel", "action", "contentDevice", "contentDeviceLabel", "content"));
        String commandDeviceName = nullableDeviceIdField(commandNode, "deviceId", "arguments.command");
        String commandAction = nullableTextField(commandNode, "action", "arguments.command");
        if (commandDeviceName == null || commandAction == null) {
            throw new ArgValidationException(errorJson("Command must include non-empty 'deviceId' and 'action'.",
                    "VALIDATION_ERROR", 400));
        }
        if (!deviceIds.contains(commandDeviceName)) {
            throw new ArgValidationException(errorJson("Unknown command deviceId '" + commandDeviceName
                            + "'. Use a canonical id returned by board_overview.",
                    "VALIDATION_ERROR", 400));
        }

        String contentDevice = nullableDeviceIdField(commandNode, "contentDevice", "arguments.command");
        if (contentDevice != null && !deviceIds.contains(contentDevice)) {
            throw new ArgValidationException(errorJson("Unknown command contentDevice '" + contentDevice + "'. Use the board device node id.",
                    "VALIDATION_ERROR", 400));
        }

        RuleDto.Command command = RuleDto.Command.builder()
                .deviceName(commandDeviceName)
                .action(commandAction)
                .contentDevice(contentDevice)
                .content(nullableTextField(commandNode, "content", "arguments.command"))
                .build();
        String commandSemanticError = BoardSemanticValidator.validateRuleCommand(semanticContext, command);
        if (commandSemanticError != null) {
            throw new ArgValidationException(errorJson(commandSemanticError, "VALIDATION_ERROR", 400));
        }
        BoardSemanticValidator.GroupValidationIssue groupIssue =
                BoardSemanticValidator.validateRuleConditionGroup(semanticContext, conditions, command);
        if (groupIssue != null) {
            throw new ArgValidationException(errorJson(groupIssue.message(), groupIssue.reasonCode(), 400));
        }

        String ruleString = nullableTextField(args, "label", "arguments");
        if (ruleString == null) {
            // Bounded only on this branch: a composed preview is the product's own output, and long
            // device labels can push a legal rule past the cap, so truncating is right. A `label` the
            // caller supplied is its choice and stays subject to rejection.
            ruleString = RulePreviewText.bounded(buildRuleString(conditions, command, labelsById));
        }

        return RuleDto.builder()
                .userId(userId)
                .conditions(conditions)
                .command(command)
                .ruleString(ruleString)
                .build();

    }

    private String executeUpdate(Long userId, JsonNode args) throws Exception {
        requireOnlyFields(args, "arguments", Set.of(
                "action", "ruleId", "expectedRule", "conditions", "command", "label"));
        long ruleId = positiveLongArg(args, "ruleId");
        JsonNode expected = args.get("expectedRule");
        if (expected == null || !expected.isObject()) {
            return errorJson("expectedRule must copy the complete rule from list_rules.", "VALIDATION_ERROR", 400);
        }
        long expectedId = positiveLongArg(expected, "ruleId");
        if (expectedId != ruleId) {
            return errorJson("expectedRule.ruleId must match ruleId.", "VALIDATION_ERROR", 400);
        }
        // JSON integer node widths differ after a round trip; the typed id has one canonical width.
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected).put("ruleId", expectedId);
        BoardSemanticSnapshotDto baseline = boardStorageService.getSemanticSnapshot(userId);
        RuleDto proposed = parseRule(userId, args, baseline.nodes(), baseline.deviceTemplates(), baseline.environmentVariables());
        CollectionMutationResultDto<RuleDto> mutation = boardStorageService.updateRulesAgainstSnapshot(userId, current -> {
            List<RuleDto> next = new ArrayList<>(current.rules());
            int position = -1;
            for (int i = 0; i < next.size(); i++) {
                if (Long.valueOf(ruleId).equals(next.get(i).getId())) { position = i; break; }
            }
            if (position < 0) throw new ResourceNotFoundException("Rule", ruleId);
            RuleDto existing = next.get(position);
            if (!expected.equals(objectMapper.valueToTree(RuleToolPresenter.present(existing, current.nodes())))) {
                throw new ConflictException("This rule changed after it was read. Read list_rules again before updating it.");
            }
            // Template/state drift is checked against the committing snapshot, not the earlier read.
            BoardSemanticValidator.GroupValidationIssue issue = BoardSemanticValidator.validateRuleConditionGroup(
                    BoardSemanticValidator.context(current.nodes(), current.deviceTemplates(), current.environmentVariables()),
                    proposed.getConditions(), proposed.getCommand());
            if (issue != null) throw new BadRequestException(issue.message());
            proposed.setId(existing.getId());
            proposed.setCreatedAt(existing.getCreatedAt());
            if (!args.has("label")) proposed.setRuleString(existing.getRuleString());
            next.set(position, proposed);
            return next;
        });
        RuleDto saved = mutation.getCurrentItems().stream().filter(rule -> Long.valueOf(ruleId).equals(rule.getId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Updated rule missing from the committed snapshot"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Rule updated in place. It has not been formally re-verified.");
        response.put("operation", "updated");
        response.put("verificationStatus", "NOT_VERIFIED");
        response.put("executionOrder", mutation.getCurrentItems().indexOf(saved) + 1);
        response.put("rule", RuleToolPresenter.present(saved, baseline.nodes()));
        response.put("totalRules", mutation.getCurrentCount());
        return successJson(response, "Rule updated but not formally verified.");
    }

    private String executeDelete(Long userId, JsonNode args) throws Exception {
        requireOnlyFields(args, "arguments", Set.of("action", "ruleId", "confirmed", "impactToken"));
        long ruleId = positiveLongArg(args, "ruleId");
        RuleDto target = safeList(boardStorageService.getRules(userId)).stream()
                .filter(rule -> rule != null && java.util.Objects.equals(rule.getId(), ruleId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return errorJson("Rule not found: " + ruleId, "NOT_FOUND", 404);
        }

        Map<String, Object> presentedRule = RuleToolPresenter.present(
                target, safeList(boardStorageService.getNodes(userId)));
        Map<String, Object> bindingSnapshot = Map.of("rule", presentedRule);
        boolean confirmed = booleanArg(args, "confirmed", false);
        if (!confirmed || !UserContextHolder.isDestructiveActionConfirmed()) {
            String impactToken = destructiveActionGuard.issue(
                    userId, getName(), Long.toString(ruleId),
                    (String) presentedRule.getOrDefault("label", presentedRule.get("description")), bindingSnapshot, null);
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("message", "No changes were made. Explicit user confirmation is required before deleting this rule.");
            preview.put("operation", "preview");
            preview.put("requiresUserConfirmation", true);
            preview.put("rule", presentedRule);
            preview.put("impactToken", impactToken);
            return readOnlySuccessJson(preview, "Rule deletion preview unavailable.");
        }

        String impactToken = requiredTextField(args, "impactToken", "arguments");
        AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consume(
                userId, getName(), Long.toString(ruleId), impactToken, bindingSnapshot);
        if (!confirmation.approved()) {
            String freshToken = destructiveActionGuard.issue(
                    userId, getName(), Long.toString(ruleId),
                    (String) presentedRule.getOrDefault("label", presentedRule.get("description")), bindingSnapshot, null);
            return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                    "requiresUserConfirmation", true,
                    "currentPreview", Map.of(
                            "operation", "preview",
                            "rule", presentedRule,
                            "impactToken", freshToken)));
        }

        CollectionMutationResultDto<RuleDto> mutation;
        try {
            mutation = boardStorageService.removeRuleIfUnchanged(userId, ruleId, target);
        } catch (ConflictException conflict) {
            RuleDto current = safeList(boardStorageService.getRules(userId)).stream()
                    .filter(rule -> rule != null && java.util.Objects.equals(rule.getId(), ruleId))
                    .findFirst()
                    .orElse(null);
            if (current == null) {
                return errorJson("Rule not found: " + ruleId, "NOT_FOUND", 404);
            }
            Map<String, Object> currentPresentation = RuleToolPresenter.present(
                    current, safeList(boardStorageService.getNodes(userId)));
            Map<String, Object> currentBinding = Map.of("rule", currentPresentation);
            String freshToken = destructiveActionGuard.issue(
                    userId, getName(), Long.toString(ruleId),
                    (String) currentPresentation.getOrDefault("label", currentPresentation.get("description")), currentBinding, null);
            return errorJson(conflict.getMessage(), "CONFIRMATION_STALE", 409, Map.of(
                    "requiresUserConfirmation", true,
                    "currentPreview", Map.of(
                            "operation", "preview",
                            "rule", currentPresentation,
                            "impactToken", freshToken)));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Rule deleted successfully.");
        response.put("operation", "deleted");
        response.put("deletedRule", RuleToolPresenter.present(
                mutation.getAffectedItem(), safeList(boardStorageService.getNodes(userId))));
        response.put("totalRules", mutation.getCurrentCount());
        return successJson(response, "Rule deleted successfully.");
    }

    private String executeReorder(Long userId, JsonNode args) throws Exception {
        requireOnlyFields(args, "arguments", Set.of("action", "expectedRuleIds", "ruleIds"));
        List<Long> expectedRuleIds = positiveRuleIdsArg(args, "expectedRuleIds");
        List<Long> ruleIds = positiveRuleIdsArg(args, "ruleIds");

        List<RuleDto> reordered = boardStorageService
                .reorderRules(userId, expectedRuleIds, ruleIds)
                .getCurrentItems();
        List<DeviceNodeDto> nodes = safeList(boardStorageService.getNodes(userId));
        List<Object> presented = new ArrayList<>();
        for (RuleDto rule : safeList(reordered)) {
            presented.add(RuleToolPresenter.present(rule, nodes));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Rule execution order updated. The board has not been re-verified.");
        response.put("operation", "reordered");
        response.put("verificationStatus", "NOT_VERIFIED");
        response.put("totalRules", presented.size());
        response.put("rules", presented);
        return successJson(response, "Rule execution order updated.");
    }

    private List<Long> positiveRuleIdsArg(JsonNode args, String field) throws ArgValidationException {
        JsonNode ruleIdsNode = args.path(field);
        if (ruleIdsNode.isMissingNode() || !ruleIdsNode.isArray() || ruleIdsNode.isEmpty()) {
            throw validation("'" + field
                    + "' array is required for reorder and must list every current rule id once.");
        }

        List<Long> ruleIds = new ArrayList<>();
        int index = 0;
        for (JsonNode idNode : ruleIdsNode) {
            if (idNode == null || !idNode.isIntegralNumber()
                    || !idNode.canConvertToLong() || idNode.longValue() <= 0) {
                throw validation(field + "[" + index + "] must be a positive integer rule id.");
            }
            ruleIds.add(idNode.longValue());
            index++;
        }
        if (new java.util.LinkedHashSet<>(ruleIds).size() != ruleIds.size()) {
            throw validation(field + " must not contain duplicate rule ids.");
        }
        return List.copyOf(ruleIds);
    }

    private ArgValidationException validation(String message) {
        return new ArgValidationException(errorJson(message, "VALIDATION_ERROR", 400));
    }

    private String normalizeRelation(String relation) {
        String normalized = SmvRelationUtils.normalizeRelation(relation);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return SmvRelationUtils.isSupportedRelation(normalized) ? normalized : null;
    }

    private String normalizeTargetType(String targetType) {
        if (targetType == null) {
            return null;
        }
        String normalized = targetType.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        return TARGET_TYPES.contains(normalized) ? normalized : null;
    }

    private Set<String> getDeviceIds(List<DeviceNodeDto> nodes) {
        Set<String> ids = new java.util.HashSet<>();
        for (DeviceNodeDto node : nodes) {
            String id = node != null ? node.getId() : null;
            if (id != null && !id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Map<String, String> labelsById(List<DeviceNodeDto> nodes) {
        Map<String, String> labels = new HashMap<>();
        for (DeviceNodeDto node : nodes) {
            String id = node != null ? node.getId() : null;
            if (id == null || id.isEmpty()) {
                continue;
            }
            String label = trimToNull(node.getLabel());
            labels.put(id, label != null ? label : id);
        }
        return labels;
    }

    private String buildRuleString(List<RuleDto.Condition> conditions,
                                   RuleDto.Command command,
                                   Map<String, String> labelsById) {
        List<String> conditionTexts = new ArrayList<>();
        for (RuleDto.Condition condition : conditions) {
            conditionTexts.add(describeCondition(condition, labelsById));
        }
        return "IF " + String.join(" AND ", conditionTexts)
                + " THEN " + describeCommand(command, labelsById);
    }

    private String describeCondition(RuleDto.Condition condition, Map<String, String> labelsById) {
        String device = displayName(condition.getDeviceName(), labelsById);
        String targetType = trimToNull(condition.getTargetType());
        String attribute = trimToNull(condition.getAttribute());
        if ("api".equals(targetType)) {
            return device + " emits " + attribute;
        }
        return device + "." + attribute + " " + condition.getRelation() + " " + condition.getValue();
    }

    private String describeCommand(RuleDto.Command command, Map<String, String> labelsById) {
        String text = displayName(command.getDeviceName(), labelsById) + "." + command.getAction();
        String contentDevice = trimToNull(command.getContentDevice());
        String content = trimToNull(command.getContent());
        if (contentDevice != null || content != null) {
            text += " with";
            if (content != null) {
                text += " content " + content;
            }
            if (contentDevice != null) {
                text += " from " + displayName(contentDevice, labelsById);
            }
        }
        return text;
    }

    private String displayName(String deviceId, Map<String, String> labelsById) {
        String id = trimToNull(deviceId);
        if (id == null) {
            return "unknown-device";
        }
        String label = labelsById.get(id);
        return label != null ? label : id;
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
}
