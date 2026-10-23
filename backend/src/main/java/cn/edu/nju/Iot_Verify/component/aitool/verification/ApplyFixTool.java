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
import cn.edu.nju.Iot_Verify.service.AiToolResultReferenceService;
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

    private final FixService fixService;
    private final FixSuggestionTokenService suggestionTokenService;
    private final AiDestructiveActionGuard destructiveActionGuard;
    private final ChatExecutionConfig chatExecutionConfig;
    private final AiToolResultReferenceService suggestionService;

    public ApplyFixTool(FixService fixService,
                        FixSuggestionTokenService suggestionTokenService,
                        ObjectMapper objectMapper,
                        AiDestructiveActionGuard destructiveActionGuard,
                        ChatExecutionConfig chatExecutionConfig,
                        AiToolResultReferenceService suggestionService) {
        super(objectMapper);
        this.fixService = fixService;
        this.suggestionTokenService = suggestionTokenService;
        this.destructiveActionGuard = destructiveActionGuard;
        this.chatExecutionConfig = chatExecutionConfig;
        this.suggestionService = suggestionService;
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
                "description", "Use false with suggestionId to preview. Use true only in a later turn after explicit user confirmation."));
        properties.put("suggestionId", Map.of(
                "type", "string",
                "description", "Required with confirmed=false. Select the suggestionId returned by fix_violation in this conversation. The server retains the exact signed proposal and its preferred ranges. On confirmation this selector may be resent only if it matches the preview."));
        properties.put("impactToken", Map.of(
                "type", "string",
                "description", "Required only with confirmed=true. Use the impactToken from the current server-authoritative confirmation context; the server applies its stored proposal."));

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
                Set.of("traceId", "confirmed", "suggestionId"));
        String suggestionId = requiredTextField(args, "suggestionId", "arguments");
        String[] reference = suggestionId.split(":", -1);
        if (reference.length != 2 || !suggestionId.matches("[1-9][0-9]{0,18}:[1-9][0-9]{0,9}")) {
            throw validation("arguments.suggestionId must be a repair option returned in this conversation.");
        }
        long messageId;
        int suggestionIndex;
        try {
            messageId = Long.parseLong(reference[0]);
            suggestionIndex = Integer.parseInt(reference[1]) - 1;
        } catch (NumberFormatException e) {
            throw validation("arguments.suggestionId is outside the supported range.");
        }
        AiToolResultReferenceService.ResolvedSuggestion selected = suggestionService.resolveRepair(
                userId, UserContextHolder.getChatSessionId(), traceId, messageId, suggestionIndex);
        JsonNode suggestionNode = selected.suggestion();
        FixSuggestionDto suggestion = objectMapper.treeToValue(suggestionNode, FixSuggestionDto.class);
        String strategy = suggestion.getStrategy();
        String suggestionToken = requiredTextField(suggestionNode, "suggestionToken", "stored suggestion");
        ParsedPreferredRanges preferredRanges = parsePreferredRanges(selected.preferredRangeSelections());

        // The signed token is the trust boundary: only the fixer issues tokens, and it lists only
        // suggestions that forward verification accepted.
        suggestionTokenService.verify(
                userId, traceId, strategy, suggestion, suggestionToken, preferredRanges.byTargetId());

        ApplyCommand command = new ApplyCommand(
                traceId, suggestionId, strategy, suggestion, suggestionToken, preferredRanges.selections());
        String impactToken = destructiveActionGuard.issueStoredAction(
                userId, getName(), Long.toString(traceId),
                "remove".equals(strategy) ? String.join(", ", suggestion.getRemovedRuleDescriptions())
                        : suggestion.getDescription(), command);

        ObjectNode visibleSuggestion = suggestionNode.deepCopy();
        visibleSuggestion.remove("suggestionToken");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("operation", "preview");
        response.put("requiresUserConfirmation", true);
        response.put("traceId", traceId);
        response.put("suggestionId", suggestionId);
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
        requireOnlyFields(args, "arguments",
                Set.of("traceId", "confirmed", "impactToken", "suggestionId"));
        String impactToken = requiredTextField(args, "impactToken", "arguments");
        AiDestructiveActionGuard.ConsumeResult confirmation = destructiveActionGuard.consumeStoredAction(
                userId, getName(), Long.toString(traceId), impactToken);
        if (!confirmation.approved()) {
            return errorJson(confirmation.message(), confirmation.errorCode(), 409, Map.of(
                    "requiresUserConfirmation", true));
        }

        ApplyCommand command = objectMapper.treeToValue(confirmation.actionPayload(), ApplyCommand.class);
        if (command.traceId() != traceId || command.suggestionId() == null || !STRATEGIES.contains(command.strategy())
                || command.suggestion() == null || command.suggestionToken() == null) {
            return errorJson(
                    "The stored formal-fix proposal is unavailable. No changes were made; run fix_violation and request a fresh apply preview.",
                    "CONFIRMATION_MISSING", 409, Map.of("requiresUserConfirmation", true));
        }

        if (args.has("suggestionId") && !command.suggestionId().equals(
                requiredTextField(args, "suggestionId", "arguments"))) {
            return errorJson("The selected repair option changed after its preview. Request a new preview before confirming it.",
                    "CONFIRMATION_MISMATCH", 409, Map.of("requiresUserConfirmation", true));
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

    private int requiredInt(JsonNode object, String field, String path) throws ArgValidationException {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw validation(path + "." + field + " is required and must be a 32-bit integer.");
        }
        return value.intValue();
    }

    private ArgValidationException validation(String message) {
        return new ArgValidationException(errorJson(message, "VALIDATION_ERROR", 400));
    }

    private record ParsedPreferredRanges(List<PreferredRangeSelection> selections,
                                         Map<String, PreferredRange> byTargetId) {
    }

    private record ApplyCommand(long traceId,
                                String suggestionId,
                                String strategy,
                                FixSuggestionDto suggestion,
                                String suggestionToken,
                                List<PreferredRangeSelection> preferredRangeSelections) {
    }
}
