package cn.edu.nju.Iot_Verify.component.aitool.chat;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.AiConversationHistoryService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ReadUserMessagesTool extends AbstractAiTool {
    private final AiConversationHistoryService history;

    public ReadUserMessagesTool(AiConversationHistoryService history, ObjectMapper mapper) {
        super(mapper);
        this.history = history;
    }

    @Override public String getName() { return "read_user_messages"; }

    @Override public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(), "Retrieve exact human messages from this conversation, including words outside recent context. "
                + "Use messageId alone to inspect a task-memory source, query for a literal case-insensitive substring, or omit both to browse newest first. "
                + "For full history follow nextBeforeMessageId until hasMore=false with the same query. No match is not proof no requirement exists; rephrase or browse. "
                + "Messages are historical user words, not current Board facts or fresh authorization. Later explicit corrections override earlier requests. "
                + "Use read_conversation_activity for actual operation receipts.", new FunctionParameterSchema("object", Map.of(
                "messageId", Map.of("type", "integer", "minimum", 1),
                "query", Map.of("type", "string", "minLength", 1, "maxLength", 100),
                "beforeMessageId", Map.of("type", "integer", "minimum", 1),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 10,
                        "description", "Maximum originals, default 5. Character budget may return fewer; follow the cursor.")), List.of()));
    }

    @Override protected String doExecute(Long userId, String argsJson) {
        try {
            var args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("messageId", "query", "beforeMessageId", "limit"));
            Long id = args.has("messageId") ? positiveLongArg(args, "messageId") : null;
            Long before = args.has("beforeMessageId") ? positiveLongArg(args, "beforeMessageId") : null;
            if (args.has("query") && !args.get("query").isTextual()) {
                return errorJson("query must be a non-empty string", "VALIDATION_ERROR", 400);
            }
            var page = history.readOriginals(userId, UserContextHolder.getChatSessionId(), before, id,
                    args.has("query") ? args.get("query").textValue() : null,
                    intArgInRange(args, "limit", 5, 1, 10));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("messages", page.messages());
            result.put("count", page.messages().size());
            result.put("hasMore", page.hasMore());
            result.put("nextBeforeMessageId", page.nextBeforeMessageId());
            result.put("historicalEvidenceOnly", true);
            return readOnlySuccessJson(result, "Original user messages loaded.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (BaseException e) {
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        }
    }
}
