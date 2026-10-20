package cn.edu.nju.Iot_Verify.component.aitool.scenario;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationLimits;
import cn.edu.nju.Iot_Verify.dto.recommendation.ScenarioRecommendationRequestDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationFailure;
import cn.edu.nju.Iot_Verify.service.recommendation.ScenarioRecommendationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSON tool boundary for scenario recommendation and chat-draft generation. */
@Slf4j
@Component
public class RecommendScenarioTool extends AbstractAiTool {
    private final ScenarioRecommendationService recommendationService;

    public RecommendScenarioTool(ScenarioRecommendationService recommendationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.recommendationService = recommendationService;
    }

    @Override
    public String getName() {
        return "recommend_scenario";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("minDevices", Map.of(
                "type", "integer",
                "description", "Required minimum number of devices in the final canonical scene (1-10)."
        ));
        props.put("minRules", Map.of(
                "type", "integer",
                "description", "Required minimum number of automation rules in the final canonical scene (1-10)."
        ));
        props.put("minSpecs", Map.of(
                "type", "integer",
                "description", "Required minimum number of formal specifications in the final canonical scene (1-10)."
        ));
        props.put("maxDevices", Map.of(
                "type", "integer",
                "description", "Maximum number of devices in the importable scene draft (1-10)."
        ));
        props.put("maxRules", Map.of(
                "type", "integer",
                "description", "Maximum number of automation rules in the scenario (1-10)."
        ));
        props.put("maxSpecs", Map.of(
                "type", "integer",
                "description", "Maximum number of formal specifications in the scenario (1-10)."
        ));
        props.put("language", Map.of(
                "type", "string",
                "enum", List.of("en", "zh-CN"),
                "description", "Natural-language output locale: en or zh-CN. Default en."
        ));
        props.put("userRequirement", Map.of(
                "type", "string",
                "maxLength", RecommendationLimits.MAX_USER_REQUIREMENT_LENGTH,
                "description", "Scenario goal or user pain point. Example: nighttime elder safety with conflicting privacy constraints."
        ));
        props.put("templateNames", Map.of(
                "type", "array", "minItems", 1, "maxItems", 100, "uniqueItems", true,
                "items", Map.of("type", "string", "minLength", 1, "maxLength", 100),
                "description", "Optional exact template names selected for this full-replacement draft. Include every template needed; omitted means the full catalog. Copy known names rather than inferring capabilities from names."
        ));
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object",
                props,
                List.of("minDevices", "minRules", "minSpecs", "maxDevices", "maxRules", "maxSpecs"));
        return LlmToolSpec.of(getName(),
                "Generate one coupled, importable IoT-Verify scene draft with devices, environment variables, automation rules, and formal specifications. The draft is capability-validated but not formally verified and does not mutate the board.",
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
            requireOnlyFields(args, "$", Set.of(
                    "minDevices", "minRules", "minSpecs", "maxDevices", "maxRules", "maxSpecs",
                    "language", "userRequirement", "templateNames"));
            ScenarioRecommendationRequestDto request = new ScenarioRecommendationRequestDto();
            request.setMinDevices(requiredCountArg(args, "minDevices"));
            request.setMinRules(requiredCountArg(args, "minRules"));
            request.setMinSpecs(requiredCountArg(args, "minSpecs"));
            request.setMaxDevices(requiredCountArg(args, "maxDevices"));
            request.setMaxRules(requiredCountArg(args, "maxRules"));
            request.setMaxSpecs(requiredCountArg(args, "maxSpecs"));
            requireOrderedRange("devices", request.getMinDevices(), request.getMaxDevices());
            requireOrderedRange("rules", request.getMinRules(), request.getMaxRules());
            requireOrderedRange("specs", request.getMinSpecs(), request.getMaxSpecs());
            request.setLanguage(languageArg(args, "language"));
            request.setUserRequirement(optionalTextArg(
                    args, "userRequirement", "", RecommendationLimits.MAX_USER_REQUIREMENT_LENGTH));
            if (args.has("templateNames")) {
                JsonNode names = args.get("templateNames");
                if (!names.isArray() || names.isEmpty() || names.size() > 100) {
                    throw new ArgValidationException(errorJson(
                            "templateNames must be an array of 1 to 100 template names.", "VALIDATION_ERROR", 400));
                }
                List<String> selected = new ArrayList<>();
                for (JsonNode name : names) {
                    if (!name.isTextual() || name.textValue().isBlank() || name.textValue().length() > 100
                            || selected.contains(name.textValue())) {
                        throw new ArgValidationException(errorJson(
                                "templateNames must contain distinct, non-empty strings of at most 100 characters.", "VALIDATION_ERROR", 400));
                    }
                    selected.add(name.textValue());
                }
                request.setTemplateNames(selected);
            }
            return objectMapper.writeValueAsString(recommendationService.recommend(
                    userId, request, UserContextHolder.getChatSessionId()));
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (RecommendationFailure e) {
            return errorJson(e.getMessage(), e.getErrorCode(), e.getCode(), e.getDetails());
        } catch (ScenarioRecommendationService.ResultTooLargeException e) {
            return errorJson(e.getMessage(), "TOOL_RESULT_TOO_LARGE", e.getCode(), e.getDetails());
        } catch (ServiceUnavailableException e) {
            log.warn("recommend_scenario busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("recommend_scenario business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("recommend_scenario failed", e);
            return errorJson("Failed to generate scenario recommendations.", "INTERNAL_ERROR", 500);
        }
    }

    private int requiredCountArg(JsonNode args, String field) throws ArgValidationException {
        if (args == null || !args.has(field) || args.get(field).isNull()) {
            throw new ArgValidationException(errorJson(
                    field + " is required.", "VALIDATION_ERROR", 400));
        }
        return intArgInRange(
                args,
                field,
                ScenarioObjectiveTargets.MIN_COUNT,
                ScenarioObjectiveTargets.MIN_COUNT,
                ScenarioObjectiveTargets.MAX_COUNT);
    }

    private void requireOrderedRange(String itemType, int minimum, int maximum)
            throws ArgValidationException {
        if (minimum <= maximum) {
            return;
        }
        throw new ArgValidationException(errorJson(
                "Minimum " + itemType + " must not exceed maximum " + itemType + ".",
                "VALIDATION_ERROR",
                400));
    }

}
