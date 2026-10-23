package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ListRulesTool extends AbstractAiTool {

    private final BoardStorageService boardStorageService;

    public ListRulesTool(BoardStorageService boardStorageService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
    }

    @Override
    public String getName() {
        return "list_rules";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();
        props.put("keyword", Map.of(
                "type", "string",
                "maxLength", 100,
                "description", "Optional literal keyword to filter rules by device name, attribute, relation, value, or action. "
                        + "Matching ignores case but does not translate. Leave empty to return all rules."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema("object", props, Collections.emptyList());

        return LlmToolSpec.of(getName(), "List automation rules on the current board. "
                + "Copy ruleId and readRef exactly into manage_rule update arguments.", schema);
    }

    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of("keyword"));
            String keyword = optionalTextArg(args, "keyword", "", 100);

            BoardSemanticSnapshotDto snapshot = boardStorageService.getSemanticSnapshot(userId);
            List<RuleDto> rules = snapshot.rules();
            List<DeviceNodeDto> nodes = snapshot.nodes();
            if (!keyword.isEmpty()) {
                rules = rules.stream()
                        .filter(rule -> RuleToolPresenter.containsKeyword(userId, rule, nodes, keyword))
                        .toList();
            }

            if (rules.isEmpty()) {
                String message = keyword.isEmpty() ? "No rules found on the board."
                        : "No rules match this literal keyword. Use an empty keyword to list all rules.";
                return readOnlySuccessJson(Map.of(
                        "message", message,
                        "count", 0
                ), message);
            }

            List<Map<String, Object>> presentedRules = rules.stream()
                    .map(rule -> RuleToolPresenter.present(userId, rule, nodes))
                    .toList();
            return readOnlySuccessJson(Map.of(
                    "count", rules.size(),
                    "rules", presentedRules
            ), "Rules loaded.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("list_rules busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("list_rules business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("list_rules failed", e);
            return errorJson("Failed to list rules.", "INTERNAL_ERROR", 500);
        }
    }

}
