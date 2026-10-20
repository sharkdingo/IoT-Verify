package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.recommendation.RuleCheckService;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationFailure;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI-assisted rule similarity analysis.
 *
 * <p>This tool is intentionally separate from {@link CheckDuplicateRuleTool}. Rule creation uses
 * deterministic duplicate checks, while this tool preserves the slower LLM semantic analysis as an
 * explicit user action.
 */
@Slf4j
@Component
public class CheckRuleSimilarityTool extends AbstractAiTool {

    private final RuleCheckService ruleCheckService;

    public CheckRuleSimilarityTool(RuleCheckService ruleCheckService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.ruleCheckService = ruleCheckService;
    }

    @Override
    public String getName() {
        return "check_rule_similarity";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("newRule", RuleCandidateArgument.schema());

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("newRule")
        );

        return LlmToolSpec.of(getName(),
                "Use AI to analyze whether a new typed rule is semantically similar to existing board rules. " +
                        "This is an explicit user-triggered check and may call the configured LLM.",
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

            requireOnlyFields(args, "arguments", Set.of("newRule"));

            JsonNode newRuleNode = args.path("newRule");
            RuleDto newRule;
            try {
                newRule = RuleCandidateArgument.parse(newRuleNode);
            } catch (RuleCandidateArgument.ValidationException e) {
                return errorJson(e.getMessage(), "VALIDATION_ERROR", 400);
            }

            return objectMapper.writeValueAsString(ruleCheckService.checkSimilarity(userId, newRule));
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (BadRequestException e) {
            return errorJson(e.getMessage(), "VALIDATION_ERROR", e.getCode());
        } catch (RecommendationFailure e) {
            return errorJson(e.getMessage(), e.getErrorCode(), e.getCode(), e.getDetails());
        } catch (ServiceUnavailableException e) {
            log.warn("check_rule_similarity busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("check_rule_similarity business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("check_rule_similarity failed", e);
            return errorJson("Failed to check rule similarity.", "INTERNAL_ERROR", 500);
        }
    }

}
