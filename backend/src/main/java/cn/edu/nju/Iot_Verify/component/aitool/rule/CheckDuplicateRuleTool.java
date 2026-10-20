package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.recommendation.RuleCheckService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 规则重复检查工具
 * 当用户添加一条规则后，使用确定性规则签名检查该规则是否与现有规则重复。
 */
@Slf4j
@Component
public class CheckDuplicateRuleTool extends AbstractAiTool {

    private final RuleCheckService ruleCheckService;

    public CheckDuplicateRuleTool(RuleCheckService ruleCheckService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.ruleCheckService = ruleCheckService;
    }

    @Override
    public String getName() {
        return "check_duplicate_rule";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();

        props.put("newRule", RuleCandidateArgument.schema());

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("newRule")
        );

        return LlmToolSpec.of(getName(),
                "Check if a new rule is duplicate with existing rules on the board. " +
                        "Uses deterministic typed trigger/action signatures so rule creation is not blocked by LLM latency.",
                schema);
    }

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

            return objectMapper.writeValueAsString(ruleCheckService.checkDuplicate(userId, newRule));
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (BadRequestException e) {
            return errorJson(e.getMessage(), "VALIDATION_ERROR", e.getCode());
        } catch (ServiceUnavailableException e) {
            log.warn("check_duplicate_rule busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("check_duplicate_rule business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("check_duplicate_rule failed", e);
            return errorJson("Failed to check duplicate rule.", "INTERNAL_ERROR", 500);
        }
    }

}
