package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControlHolder;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.fix.FixResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.service.FixService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class FixViolationTool extends AbstractAiTool {

    private final FixService fixService;

    public FixViolationTool(FixService fixService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.fixService = fixService;
    }

    @Override
    public String getName() {
        return "fix_violation";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();

        props.put("traceId", Map.of(
                "type", "integer",
                "description", "ID of the violation trace to fix. Obtained from verify_model result."
        ));
        props.put("strategies", Map.of(
                "type", "array",
                "description", "Fix strategies to attempt, in order. Supported: 'parameter' (§5.1 threshold adjustment), "
                        + "'condition' (§5.2 triggering condition adjustment), 'remove' (remove rules). "
                        + "Omit this field for the default ['parameter', 'condition', 'remove']; an explicit array must be non-empty and is attempted exactly as supplied.",
                "items", Map.of(
                        "type", "string",
                        "enum", List.of("parameter", "condition", "remove"))
        ));
        props.put("preferredRangeSelections", Map.of(
                "type", "array",
                "description", "Optional preferred value ranges chosen from concrete parameter-adjustment targets. "
                        + "Each item identifies the target by the opaque targetId copied from parameterTargets "
                        + "returned for the same trace/fix context "
                        + "plus 32-bit integer lower/upper bounds.",
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "targetId", Map.of("type", "string", "description", "targetId copied from the selected ParameterTarget"),
                                "lower", Map.of("type", "integer", "description", "Lower bound (inclusive)"),
                                "upper", Map.of("type", "integer", "description", "Upper bound (inclusive)")
                        ),
                        "required", List.of("targetId", "lower", "upper"),
                        "additionalProperties", false
                )
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("traceId")
        );

        return LlmToolSpec.of(getName(), "Analyze a violation trace to localize fault rules and suggest fixes. " +
                "Supports 'parameter' (adjust numeric thresholds), 'condition' (add or remove triggering conditions), " +
                "and 'remove' (remove entire rules) strategies. This tool only analyzes and never applies a change. " +
                "To preview or apply any returned option, including remove, use apply_fix with its suggestionId; " +
                "do not recreate the edit with manage_rule or other manual mutation tools. " +
                "Requires a traceId from a previous verification.", schema);
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
            requireOnlyFields(args, "arguments", Set.of(
                    "traceId", "strategies", "preferredRangeSelections", "preferredRanges"));

            long traceId = positiveLongArg(args, "traceId");

            List<String> strategies = null;
            if (args.has("strategies")) {
                JsonNode strategiesNode = args.get("strategies");
                if (!strategiesNode.isArray() || strategiesNode.isEmpty()) {
                    return errorJson("strategies must be a non-empty array when provided. Omit it to use the default order.",
                            "VALIDATION_ERROR", 400);
                }
                strategies = new ArrayList<>();
                Set<String> seenStrategies = new LinkedHashSet<>();
                for (int i = 0; i < strategiesNode.size(); i++) {
                    JsonNode strategyNode = strategiesNode.get(i);
                    if (!strategyNode.isTextual() || strategyNode.asText().isBlank()) {
                        return errorJson("strategies[" + i + "] must be a non-empty string.",
                                "VALIDATION_ERROR", 400);
                    }
                    String strategy = strategyNode.asText().trim().toLowerCase(Locale.ROOT);
                    if (!Set.of("parameter", "condition", "remove").contains(strategy)) {
                        return errorJson("Unsupported strategy '" + strategy
                                        + "'. Allowed: parameter, condition, remove.",
                                "VALIDATION_ERROR", 400);
                    }
                    if (!seenStrategies.add(strategy)) {
                        return errorJson("Duplicate strategy '" + strategy + "'.",
                                "VALIDATION_ERROR", 400);
                    }
                    strategies.add(strategy);
                }
            }

            // Parse preferredRangeSelections (strict validation, fail-fast on invalid input).
            Map<String, PreferredRange> preferredRanges = null;
            if (args.has("preferredRanges")) {
                return errorJson("preferredRanges is an internal locator map. "
                        + "Use preferredRangeSelections with targetId, lower, and upper.",
                        "VALIDATION_ERROR", 400);
            }
            if (args.has("preferredRangeSelections")) {
                JsonNode prNode = args.get("preferredRangeSelections");
                if (!prNode.isArray()) {
                    return errorJson("preferredRangeSelections must be an array.", "VALIDATION_ERROR", 400);
                }
                preferredRanges = new LinkedHashMap<>();
                for (int i = 0; i < prNode.size(); i++) {
                    JsonNode val = prNode.get(i);
                    if (val == null || !val.isObject()) {
                        return errorJson("preferredRangeSelections[" + i + "] must be an object.",
                                "VALIDATION_ERROR", 400);
                    }
                    requireOnlyFields(val, "arguments.preferredRangeSelections[" + i + "]",
                            Set.of("targetId", "lower", "upper"));
                    String missingField = missingSelectionField(val);
                    if (missingField != null) {
                        return errorJson("preferredRangeSelections[" + i + "] missing '" + missingField + "'.",
                                "VALIDATION_ERROR", 400);
                    }
                    String intFieldError = firstNonIntSelectionField(val);
                    if (intFieldError != null) {
                        return errorJson("preferredRangeSelections[" + i + "] field '" + intFieldError
                                + "' must be a 32-bit integer.", "VALIDATION_ERROR", 400);
                    }
                    if (!val.get("targetId").isTextual()) {
                        return errorJson("preferredRangeSelections[" + i
                                        + "] field 'targetId' must be a non-empty string.",
                                "VALIDATION_ERROR", 400);
                    }
                    String targetId = val.get("targetId").textValue();
                    int lower = val.get("lower").intValue();
                    int upper = val.get("upper").intValue();
                    if (targetId == null || targetId.isBlank()) {
                        return errorJson("preferredRangeSelections[" + i
                                + "]: targetId must not be blank.",
                                "VALIDATION_ERROR", 400);
                    }
                    if (lower > upper) {
                        return errorJson("preferredRangeSelections[" + i
                                + "]: lower(" + lower + ") > upper(" + upper + ").", "VALIDATION_ERROR", 400);
                    }
                    PreferredRangeSelection selection = PreferredRangeSelection.builder()
                            .targetId(targetId)
                            .lower(lower)
                            .upper(upper)
                            .build();
                    if (!PreferredRangeSelection.isValidTargetId(targetId)) {
                        return errorJson("preferredRangeSelections[" + i
                                + "]: targetId is not a valid parameter-adjustment selector.",
                                "VALIDATION_ERROR", 400);
                    }
                    if (preferredRanges.containsKey(targetId)) {
                        return errorJson("Duplicate target in preferredRangeSelections[" + i + "].",
                                "VALIDATION_ERROR", 400);
                    }
                    preferredRanges.put(targetId, selection.toPreferredRange());
                }
                if (preferredRanges.isEmpty()) preferredRanges = null;
            }

            FixResultDto result;
            // Analysis is read-only. Propagate Stop to the existing interrupted-search and native
            // process cleanup path; confirmed mutations retain their separate commit boundary.
            LlmRequestControl control = LlmRequestControlHolder.currentOrNew();
            try (AutoCloseable cancellation = control.interruptCurrentThreadOnCancellation()) {
                if (control.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
                    return cancelledAnalysis();
                }
                result = fixService.fix(userId, traceId, strategies, preferredRanges);
            }
            if (control.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
                return cancelledAnalysis();
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("traceId", result.getTraceId());
            summary.put("fixable", result.isFixable());
            summary.put("sourceModelComplete", result.isSourceModelComplete());
            summary.put("sourceDisabledRuleCount", result.getSourceDisabledRuleCount());
            summary.put("sourceSkippedSpecCount", result.getSourceSkippedSpecCount());
            summary.put("sourceGenerationIssues", result.getSourceGenerationIssues() != null
                    ? result.getSourceGenerationIssues() : List.of());
            summary.put("templateSnapshotComparison", result.getTemplateSnapshotComparison());
            summary.put("faultRuleCount", result.getFaultRules() != null ? result.getFaultRules().size() : 0);
            summary.put("suggestionCount", result.getSuggestions() != null ? result.getSuggestions().size() : 0);
            summary.put("summary", result.getSummary());
            summary.put("strategyAttempts", result.getStrategyAttempts() != null
                    ? result.getStrategyAttempts() : List.of());
            summary.put("warnings", result.getWarnings() != null ? result.getWarnings() : List.of());
            summary.put("preferredRangeSelections", preferredRanges == null ? List.of()
                    : preferredRanges.entrySet().stream()
                            .map(entry -> new PreferredRangeSelection(entry.getKey(),
                                    entry.getValue().getLower(), entry.getValue().getUpper()))
                            .toList());

            if (result.getFaultRules() != null && !result.getFaultRules().isEmpty()) {
                summary.put("faultRules", result.getFaultRules());
            }
            if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
                summary.put("suggestions", result.getSuggestions());
            }
            if (result.getParameterTargets() != null && !result.getParameterTargets().isEmpty()) {
                summary.put("parameterTargets", result.getParameterTargets());
            }
            if (result.getUnusedPreferredRangeSelections() != null
                    && !result.getUnusedPreferredRangeSelections().isEmpty()) {
                summary.put("unusedPreferredRangeSelections", result.getUnusedPreferredRangeSelections());
            }

            String message = result.isFixable()
                    ? "Fix analysis found one or more forward-verified suggestions; no Board change was applied."
                    : "Fix analysis found no forward-verified suggestion; no Board change was applied.";
            summary.put("message", message);
            return readOnlySuccessJson(summary, message);

        } catch (ResourceNotFoundException e) {
            return errorJson("Trace not found: " + e.getMessage(), "NOT_FOUND", 404);
        } catch (BadRequestException e) {
            return errorJson(e.getMessage(), "BAD_REQUEST", 400);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("fix_violation busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (SmvGenerationException e) {
            log.warn("fix_violation generation failed [{}]: {}", e.getErrorCategory(), e.getMessage());
            return errorJson(e.getMessage(),
                    "SMV_GENERATION_ERROR",
                    500,
                    Map.of("errorCategory", e.getErrorCategory()));
        } catch (BaseException e) {
            log.warn("fix_violation business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("FixViolationTool execution failed", e);
            return errorJson("Fix analysis failed.", "INTERNAL_ERROR", 500);
        }
    }

    private String cancelledAnalysis() {
        return errorJson("Repair analysis was stopped before completion; no changes were applied.",
                "FIX_ANALYSIS_CANCELLED", 499);
    }

    private static String missingSelectionField(JsonNode node) {
        for (String field : List.of("targetId", "lower", "upper")) {
            if (!node.has(field)) {
                return field;
            }
        }
        return null;
    }

    private static String firstNonIntSelectionField(JsonNode node) {
        for (String field : List.of("lower", "upper")) {
            JsonNode value = node.get(field);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
                return field;
            }
        }
        return null;
    }
}
