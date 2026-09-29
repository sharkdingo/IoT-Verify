package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.fix.FixApplyResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.FixApplyPreflightUnavailableException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.FixService;
import cn.edu.nju.Iot_Verify.service.FixSuggestionTokenService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies one exact, signed formal-fix suggestion after a separate confirmation turn. */
@Slf4j
@Component
public class ApplyFixTool extends AbstractAiTool {

    private static final Set<String> STRATEGIES = Set.of("parameter", "condition", "remove");
    private static final Set<String> MODEL_TOKEN_SOURCES = Set.of("BUNDLED", "CUSTOM", "UNKNOWN");

    private final FixService fixService;
    private final FixSuggestionTokenService suggestionTokenService;
    private final AiDestructiveActionGuard destructiveActionGuard;
    private final ChatExecutionConfig chatExecutionConfig;

    public ApplyFixTool(FixService fixService,
                        FixSuggestionTokenService suggestionTokenService,
                        ObjectMapper objectMapper,
                        AiDestructiveActionGuard destructiveActionGuard,
                        ChatExecutionConfig chatExecutionConfig) {
        super(objectMapper);
        this.fixService = fixService;
        this.suggestionTokenService = suggestionTokenService;
        this.destructiveActionGuard = destructiveActionGuard;
        this.chatExecutionConfig = chatExecutionConfig;
    }

    @Override
    public String getName() {
        return "apply_fix";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("traceId", Map.of(
                "type", "integer",
                "description", "Formal verification trace ID returned with the signed fix suggestion."));
        properties.put("confirmed", Map.of(
                "type", "boolean",
                "description", "Use false with suggestion to preview. Use true only in a later turn after explicit user confirmation."));
        properties.put("suggestion", suggestionSchema());
        properties.put("preferredRangeSelections", preferredRangeSelectionsSchema());
        properties.put("impactToken", Map.of(
                "type", "string",
                "description", "Required only with confirmed=true. Copy the opaque impactToken from the latest apply_fix preview. On that call suggestion and preferredRangeSelections may be resent unchanged; they are ignored because the server uses its own stored proposal."));

        return LlmToolSpec.of(
                getName(),
                "Preview or, after explicit two-turn confirmation, apply one exact signed formal-fix suggestion returned by fix_violation. The confirmed call uses the server-stored proposal and rechecks signature expiry plus all current Board, template, specification, device, environment, and write-fence guards before changing rules. Fuzz findings are not accepted.",
                new FunctionParameterSchema("object", properties, List.of("traceId", "confirmed")));
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

            long traceId = positiveLongArg(args, "traceId");
            // Omission degrades to a preview, matching every other confirmation-gated tool. Rejecting
            // it instead spent a round on a VALIDATION_ERROR to reach the same no-write outcome, and
            // the two behaviours diverged behind an identical schema.
            boolean confirmed = booleanArg(args, "confirmed", false);
            if (confirmed) {
                return applyConfirmed(userId, traceId, args);
            }
            return preview(userId, traceId, args);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ResourceNotFoundException e) {
            return errorJson(e.getMessage(), "NOT_FOUND", 404);
        } catch (BadRequestException e) {
            return errorJson(e.getMessage(), "BAD_REQUEST", 400);
        } catch (ServiceUnavailableException e) {
            log.warn("apply_fix unavailable: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("apply_fix business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("apply_fix failed", e);
            return errorJson("Failed to apply the formal fix suggestion.", "INTERNAL_ERROR", 500);
        }
    }

    private String preview(Long userId, long traceId, JsonNode args) throws Exception {
        requireOnlyFields(args, "arguments",
                Set.of("traceId", "confirmed", "suggestion", "preferredRangeSelections"));
        JsonNode suggestionNode = args.get("suggestion");
        validateSuggestion(suggestionNode);
        FixSuggestionDto suggestion = objectMapper.treeToValue(suggestionNode, FixSuggestionDto.class);
        String strategy = suggestion.getStrategy();
        String suggestionToken = requiredTextField(suggestionNode, "suggestionToken", "arguments.suggestion");
        ParsedPreferredRanges preferredRanges = parsePreferredRanges(args.get("preferredRangeSelections"));

        // The signed token is the trust boundary: only the fixer issues tokens, and it lists only
        // suggestions that forward verification accepted.
        suggestionTokenService.verify(
                userId, traceId, strategy, suggestion, suggestionToken, preferredRanges.byTargetId());

        ApplyCommand command = new ApplyCommand(
                traceId, strategy, suggestion, suggestionToken, preferredRanges.selections());
        String impactToken = destructiveActionGuard.issueStoredAction(
                userId, getName(), Long.toString(traceId), command);

        ObjectNode visibleSuggestion = suggestionNode.deepCopy();
        visibleSuggestion.remove("suggestionToken");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("operation", "preview");
        response.put("requiresUserConfirmation", true);
        response.put("traceId", traceId);
        response.put("strategy", strategy);
        response.put("suggestion", visibleSuggestion);
        response.put("preferredRangeSelections", preferredRanges.selections());
        response.put("impactToken", impactToken);
        response.put("message", "No rules were changed. The signed formal-fix proposal is ready for explicit confirmation; current model drift and the write fence will be checked again before persistence.");
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            destructiveActionGuard.clearSession(userId, UserContextHolder.getChatSessionId());
            log.error("apply_fix could not serialize its confirmation preview for trace {}", traceId, e);
            return previewResultUnavailable();
        }
        int resultBytes = serialized.getBytes(StandardCharsets.UTF_8).length;
        boolean resultTooLarge = resultBytes > chatExecutionConfig.getMaxToolResultBytes();
        if (resultTooLarge) {
            // A confirmation is valid only after the user can inspect the exact preview. Do not
            // leave a hidden pending action behind when the response itself could not be delivered.
            destructiveActionGuard.clearSession(userId, UserContextHolder.getChatSessionId());
        }
        if (resultTooLarge) {
            return oversizedPreview(resultBytes);
        }
        return serialized;
    }

    private String applyConfirmed(Long userId, long traceId, JsonNode args) throws Exception {
        // `suggestion` and `preferredRangeSelections` are accepted but ignored: the model reaches this
        // call by resending the object it previewed with, and rejecting them cost a guaranteed wasted
        // round on a VALIDATION_ERROR. They are not read — the proposal comes from the server-stored
        // action below, which is what makes a confirmation unforgeable.
        requireOnlyFields(args, "arguments",
                Set.of("traceId", "confirmed", "impactToken", "suggestion", "preferredRangeSelections"));
        String impactToken = requiredTextField(args, "impactToken", "arguments");
        AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consumeStoredAction(
                userId, getName(), Long.toString(traceId), impactToken);
        if (!confirmation.approved()) {
            return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                    "requiresUserConfirmation", true));
        }

        ApplyCommand command = objectMapper.treeToValue(confirmation.actionPayload(), ApplyCommand.class);
        if (command.traceId() != traceId || !STRATEGIES.contains(command.strategy())
                || command.suggestion() == null || command.suggestionToken() == null) {
            return errorJson(
                    "The stored formal-fix proposal is unavailable. No changes were made; run fix_violation and request a fresh apply preview.",
                    "CONFIRMATION_MISSING", 409, Map.of("requiresUserConfirmation", true));
        }

        Map<String, PreferredRange> preferredRanges = preferredRangesByTarget(command.preferredRangeSelections());
        try {
            FixApplyResultDto result = fixService.applyFix(
                    userId,
                    traceId,
                    command.strategy(),
                    command.suggestion(),
                    command.suggestionToken(),
                    preferredRanges);

            if (!isCompleteMutationResult(command, result)) {
                log.error("apply_fix returned an incomplete mutation result for trace {}", traceId);
                return mutationResultUnavailable();
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("operation", "applied");
            response.put("applied", result.isApplied());
            response.put("strategy", result.getStrategy());
            response.put("verificationEvidenceReused", result.isVerificationEvidenceReused());
            response.put("previousRuleCount", result.getPreviousRuleCount());
            response.put("currentRuleCount", result.getCurrentRuleCount());
            ObjectNode appliedSuggestion = objectMapper.valueToTree(result.getAppliedSuggestion());
            appliedSuggestion.remove("suggestionToken");
            response.put("appliedSuggestion", appliedSuggestion);
            response.put("message", result.getMessage());
            return successJson(response, "Formal-fix mutation completed, but its result details are unavailable.");
        } catch (FixApplyPreflightUnavailableException e) {
            // This subtype is explicitly guaranteed to occur before any board write.
            throw e;
        } catch (ServiceUnavailableException e) {
            // A general admission failure can be raised by the post-operation lease check after
            // the inner rule transaction committed. Its settlement is therefore not safely retryable.
            log.error("apply_fix lost operation admission while settling trace {}", traceId, e);
            return mutationResultUnavailable();
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("apply_fix could not confirm the mutation result for trace {}", traceId, e);
            return mutationResultUnavailable();
        }
    }

    private boolean isCompleteMutationResult(ApplyCommand command, FixApplyResultDto result) {
        return result != null
                && result.isApplied()
                && command.strategy().equals(result.getStrategy())
                && result.isVerificationEvidenceReused()
                && result.getAppliedSuggestion() != null
                && sameVisibleSuggestion(command.suggestion(), result.getAppliedSuggestion())
                && result.getPreviousRuleCount() >= 0
                && result.getCurrentRuleCount() >= 0
                && expectedRuleCountChange(command, result)
                && result.getRules() != null
                && result.getRules().size() == result.getCurrentRuleCount()
                && result.getMessage() != null
                && !result.getMessage().isBlank();
    }

    private boolean sameVisibleSuggestion(FixSuggestionDto expected, FixSuggestionDto actual) {
        if (expected == null || actual == null) return false;
        ObjectNode expectedNode = objectMapper.valueToTree(expected);
        ObjectNode actualNode = objectMapper.valueToTree(actual);
        expectedNode.remove("suggestionToken");
        actualNode.remove("suggestionToken");
        return expectedNode.equals(actualNode);
    }

    private boolean expectedRuleCountChange(ApplyCommand command, FixApplyResultDto result) {
        if ("remove".equals(command.strategy())) {
            int removed = command.suggestion().getRemovedRuleDescriptions().size();
            return removed > 0
                    && result.getPreviousRuleCount() - result.getCurrentRuleCount() == removed;
        }
        return result.getPreviousRuleCount() == result.getCurrentRuleCount();
    }

    private String mutationResultUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resultStatus", "RESULT_UNAVAILABLE");
        body.put("resultAvailable", false);
        body.put("mutationMayHaveCommitted", true);
        body.put("errorCode", "MUTATION_RESULT_INVALID");
        body.put("message", "The formal-fix result could not be confirmed. Refresh the current rule list before retrying.");
        return successJson(body, "Formal-fix result unavailable.");
    }

    private String previewResultUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resultStatus", "RESULT_UNAVAILABLE");
        body.put("resultAvailable", false);
        body.put("mutationMayHaveCommitted", false);
        body.put("errorCode", "PREVIEW_RESULT_INVALID");
        body.put("message", "The formal-fix preview could not be delivered. No rules were changed and no confirmation remains active.");
        return readOnlySuccessJson(body, "Formal-fix preview unavailable; no rules were changed.");
    }

    private String oversizedPreview(int resultBytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resultStatus", "RESULT_UNAVAILABLE");
        body.put("resultAvailable", false);
        body.put("mutationMayHaveCommitted", false);
        body.put("errorCode", "TOOL_RESULT_TOO_LARGE");
        body.put("message", "The formal-fix preview is too large to review safely in chat. No rules were changed; use the formal-fix interface to review this suggestion.");
        body.put("resultBytes", resultBytes);
        body.put("maxResultBytes", chatExecutionConfig.getMaxToolResultBytes());
        return readOnlySuccessJson(body, "Formal-fix preview exceeded the safe result size limit.");
    }

    private void validateSuggestion(JsonNode suggestion) throws ArgValidationException {
        requireOnlyFields(suggestion, "arguments.suggestion", Set.of(
                "suggestionToken", "strategy", "description", "parameterAdjustments",
                "conditionAdjustments", "removedRuleDescriptions", "preexistingViolations"));
        requiredTextField(suggestion, "suggestionToken", "arguments.suggestion");
        String strategy = requiredTextField(suggestion, "strategy", "arguments.suggestion");
        if (!STRATEGIES.contains(strategy)) {
            throw validation("arguments.suggestion.strategy must be parameter, condition, or remove.");
        }
        requiredTextField(suggestion, "description", "arguments.suggestion");

        JsonNode parameters = requiredArray(suggestion, "parameterAdjustments", "arguments.suggestion");
        JsonNode conditions = requiredArray(suggestion, "conditionAdjustments", "arguments.suggestion");
        JsonNode removals = requiredArray(suggestion, "removedRuleDescriptions", "arguments.suggestion");
        for (int index = 0; index < parameters.size(); index++) {
            validateParameterAdjustment(parameters.get(index), index);
        }
        for (int index = 0; index < conditions.size(); index++) {
            validateConditionAdjustment(conditions.get(index), index);
        }
        for (int index = 0; index < removals.size(); index++) {
            if (!removals.get(index).isTextual() || removals.get(index).textValue().isBlank()) {
                throw validation("arguments.suggestion.removedRuleDescriptions[" + index
                        + "] must be a non-empty string.");
            }
        }
        JsonNode preexisting = requiredArray(suggestion, "preexistingViolations", "arguments.suggestion");
        for (int index = 0; index < preexisting.size(); index++) {
            String path = "arguments.suggestion.preexistingViolations[" + index + "]";
            requireOnlyFields(preexisting.get(index), path, Set.of("specId", "templateId", "formulaPreview"));
            for (String field : List.of("specId", "templateId", "formulaPreview")) {
                requiredTextField(preexisting.get(index), field, path);
            }
        }

        boolean shapeMatches = switch (strategy) {
            case "parameter" -> !parameters.isEmpty() && conditions.isEmpty() && removals.isEmpty();
            case "condition" -> parameters.isEmpty() && !conditions.isEmpty() && removals.isEmpty();
            case "remove" -> parameters.isEmpty() && conditions.isEmpty() && !removals.isEmpty();
            default -> false;
        };
        if (!shapeMatches) {
            throw validation("arguments.suggestion change lists do not match its strategy.");
        }
    }

    private void validateParameterAdjustment(JsonNode adjustment, int index) throws ArgValidationException {
        String path = "arguments.suggestion.parameterAdjustments[" + index + "]";
        requireOnlyFields(adjustment, path, Set.of(
                "targetId", "attribute", "relation", "originalValue", "newValue",
                "lowerBound", "upperBound", "description", "modelTokenSource"));
        String targetId = requiredTextField(adjustment, "targetId", path);
        if (!PreferredRangeSelection.isValidTargetId(targetId)) {
            throw validation(path + ".targetId is not a valid parameter-adjustment selector.");
        }
        for (String field : List.of("attribute", "relation", "originalValue", "newValue", "description")) {
            requiredTextField(adjustment, field, path);
        }
        requiredInt(adjustment, "lowerBound", path);
        requiredInt(adjustment, "upperBound", path);
        requireModelTokenSource(adjustment, path);
    }

    private void validateConditionAdjustment(JsonNode adjustment, int index) throws ArgValidationException {
        String path = "arguments.suggestion.conditionAdjustments[" + index + "]";
        requireOnlyFields(adjustment, path, Set.of(
                "action", "attribute", "targetType", "description", "ruleDescription",
                "deviceLabel", "relation", "value", "modelTokenSource"));
        String action = requiredTextField(adjustment, "action", path);
        if (!Set.of("add", "remove", "keep").contains(action)) {
            throw validation(path + ".action must be add, remove, or keep.");
        }
        String targetType = requiredTextField(adjustment, "targetType", path);
        if (!Set.of("api", "variable", "mode", "state").contains(targetType)) {
            throw validation(path + ".targetType must be api, variable, mode, or state.");
        }
        for (String field : List.of("attribute", "description", "ruleDescription", "deviceLabel")) {
            requiredTextField(adjustment, field, path);
        }
        nullableText(adjustment, "relation", path);
        nullableText(adjustment, "value", path);
        requireModelTokenSource(adjustment, path);
    }

    private ParsedPreferredRanges parsePreferredRanges(JsonNode node) throws ArgValidationException {
        if (node == null) return new ParsedPreferredRanges(List.of(), null);
        if (!node.isArray()) {
            throw validation("arguments.preferredRangeSelections must be an array when provided.");
        }
        List<PreferredRangeSelection> selections = new ArrayList<>();
        Map<String, PreferredRange> ranges = new LinkedHashMap<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode selectionNode = node.get(index);
            String path = "arguments.preferredRangeSelections[" + index + "]";
            requireOnlyFields(selectionNode, path, Set.of("targetId", "lower", "upper"));
            String targetId = requiredTextField(selectionNode, "targetId", path);
            if (!PreferredRangeSelection.isValidTargetId(targetId)) {
                throw validation(path + ".targetId is not a valid parameter-adjustment selector.");
            }
            int lower = requiredInt(selectionNode, "lower", path);
            int upper = requiredInt(selectionNode, "upper", path);
            if (lower > upper) {
                throw validation(path + ".lower must be less than or equal to upper.");
            }
            if (ranges.putIfAbsent(targetId, new PreferredRange(lower, upper)) != null) {
                throw validation("arguments.preferredRangeSelections contains duplicate targetId " + targetId + ".");
            }
            selections.add(new PreferredRangeSelection(targetId, lower, upper));
        }
        return new ParsedPreferredRanges(List.copyOf(selections), ranges.isEmpty() ? null : ranges);
    }

    private Map<String, PreferredRange> preferredRangesByTarget(List<PreferredRangeSelection> selections) {
        if (selections == null || selections.isEmpty()) return null;
        Map<String, PreferredRange> ranges = new LinkedHashMap<>();
        for (PreferredRangeSelection selection : selections) {
            ranges.put(selection.getTargetId(), selection.toPreferredRange());
        }
        return ranges;
    }

    private JsonNode requiredArray(JsonNode object, String field, String path) throws ArgValidationException {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isArray()) {
            throw validation(path + "." + field + " is required and must be an array.");
        }
        return value;
    }

    private int requiredInt(JsonNode object, String field, String path) throws ArgValidationException {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw validation(path + "." + field + " is required and must be a 32-bit integer.");
        }
        return value.intValue();
    }

    private void nullableText(JsonNode object, String field, String path) throws ArgValidationException {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || (!value.isNull() && !value.isTextual())) {
            throw validation(path + "." + field + " is required and must be a string or null.");
        }
    }

    private void requireModelTokenSource(JsonNode object, String path) throws ArgValidationException {
        String source = requiredTextField(object, "modelTokenSource", path);
        if (!MODEL_TOKEN_SOURCES.contains(source)) {
            throw validation(path + ".modelTokenSource must be BUNDLED, CUSTOM, or UNKNOWN.");
        }
    }

    private ArgValidationException validation(String message) {
        return new ArgValidationException(errorJson(message, "VALIDATION_ERROR", 400));
    }

    private Map<String, Object> suggestionSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("suggestionToken", Map.of("type", "string"));
        properties.put("strategy", Map.of("type", "string", "enum", List.of("parameter", "condition", "remove")));
        properties.put("description", Map.of("type", "string"));
        properties.put("parameterAdjustments", Map.of("type", "array", "items", parameterAdjustmentSchema()));
        properties.put("conditionAdjustments", Map.of("type", "array", "items", conditionAdjustmentSchema()));
        properties.put("removedRuleDescriptions", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("preexistingViolations", Map.of(
                "type", "array",
                "description", "Specifications the original rules already violated and this suggestion leaves "
                        + "violated; copy unchanged.",
                "items", preexistingViolationSchema()));
        return objectSchema(properties, List.copyOf(properties.keySet()),
                "Required with confirmed=false. Copy one complete suggestion object exactly from "
                        + "fix_violation. On the confirmed=true call it may be resent unchanged and is "
                        + "ignored: the server applies its own stored proposal.");
    }

    private Map<String, Object> preexistingViolationSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("specId", Map.of("type", "string"));
        properties.put("templateId", Map.of("type", "string"));
        properties.put("formulaPreview", Map.of("type", "string"));
        return objectSchema(properties, List.copyOf(properties.keySet()), null);
    }

    private Map<String, Object> parameterAdjustmentSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetId", Map.of("type", "string"));
        properties.put("attribute", Map.of("type", "string"));
        properties.put("relation", Map.of("type", "string"));
        properties.put("originalValue", Map.of("type", "string"));
        properties.put("newValue", Map.of("type", "string"));
        properties.put("lowerBound", Map.of("type", "integer"));
        properties.put("upperBound", Map.of("type", "integer"));
        properties.put("description", Map.of("type", "string"));
        properties.put("modelTokenSource", Map.of("type", "string", "enum", List.of("BUNDLED", "CUSTOM", "UNKNOWN")));
        return objectSchema(properties, List.copyOf(properties.keySet()), null);
    }

    private Map<String, Object> conditionAdjustmentSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of("add", "remove", "keep")));
        properties.put("attribute", Map.of("type", "string"));
        properties.put("targetType", Map.of("type", "string", "enum", List.of("api", "variable", "mode", "state")));
        properties.put("description", Map.of("type", "string"));
        properties.put("ruleDescription", Map.of("type", "string"));
        properties.put("deviceLabel", Map.of("type", "string"));
        properties.put("relation", Map.of("type", List.of("string", "null")));
        properties.put("value", Map.of("type", List.of("string", "null")));
        properties.put("modelTokenSource", Map.of("type", "string", "enum", List.of("BUNDLED", "CUSTOM", "UNKNOWN")));
        return objectSchema(properties, List.copyOf(properties.keySet()), null);
    }

    private Map<String, Object> preferredRangeSelectionsSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetId", Map.of("type", "string"));
        properties.put("lower", Map.of("type", "integer"));
        properties.put("upper", Map.of("type", "integer"));
        return Map.of(
                "type", "array",
                "description", "Copy the exact preferred range selections used when fix_violation generated this suggestion; omit when none were used.",
                "items", objectSchema(properties, List.copyOf(properties.keySet()), null));
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties,
                                             List<String> required,
                                             String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (description != null) schema.put("description", description);
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private record ParsedPreferredRanges(List<PreferredRangeSelection> selections,
                                         Map<String, PreferredRange> byTargetId) {
    }

    private record ApplyCommand(long traceId,
                                String strategy,
                                FixSuggestionDto suggestion,
                                String suggestionToken,
                                List<PreferredRangeSelection> preferredRangeSelections) {
    }
}
