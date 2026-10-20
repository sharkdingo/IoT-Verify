package cn.edu.nju.Iot_Verify.component.aitool.chat;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.AiConversationHistoryService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ReadConversationActivityTool extends AbstractAiTool {

    private final AiConversationHistoryService historyService;

    public ReadConversationActivityTool(AiConversationHistoryService historyService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.historyService = historyService;
    }

    @Override
    public String getName() {
        return "read_conversation_activity";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(),
                "Read saved operation evidence in this conversation, including operations outside the recent model context. Pages are newest first, with entries in chronological order within each page. Follow nextBeforeMessageId while hasMore=true before claiming a complete audit. appliedAction proves only that historical operation; a recommendation or requiresUserConfirmation preview is not an applied change. Unavailable evidence is unknown, never proof that nothing happened. This read does not restore confirmations or edit current data.",
                new FunctionParameterSchema("object", Map.of(
                        "beforeMessageId", Map.of("type", "integer", "minimum", 1,
                                "description", "Omit for the newest page; otherwise copy nextBeforeMessageId exactly."),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", AiConversationHistoryService.MAX_PAGE_SIZE,
                                "description", "Maximum operation records per page, default 20.")), List.of()));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("beforeMessageId", "limit"));
            Long beforeId = args.has("beforeMessageId") ? positiveLongArg(args, "beforeMessageId") : null;
            int limit = intArgInRange(args, "limit", 20, 1, AiConversationHistoryService.MAX_PAGE_SIZE);
            var page = historyService.readPage(userId, UserContextHolder.getChatSessionId(), beforeId, limit);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("activities", page.activities());
            response.put("count", page.activities().size());
            response.put("hasMore", page.hasMore());
            if (page.nextBeforeMessageId() != null) response.put("nextBeforeMessageId", page.nextBeforeMessageId());
            response.put("historicalEvidenceOnly", true);
            return readOnlySuccessJson(response, "Saved conversation activity loaded; current data was not changed.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (BaseException e) {
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("read_conversation_activity failed", e);
            return errorJson("Saved conversation activity could not be read. Retry before drawing conclusions about earlier operations.", "INTERNAL_ERROR", 500);
        }
    }
}
