package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResultContract;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single owner of the on-disk chat-message wire format and the {@link ChatMessagePo} ⇄
 * {@link LlmMessage} conversion. Concentrating this here keeps both the persistence format
 * and history reconstruction in one cohesive place and free of any vendor SDK type.
 *
 * <p>The persisted encoding is a structured JSON object:
 * {@code {"type":"tool_calls"|"tool_result", ...}}. Development data should be migrated
 * or cleared rather than carrying multiple historical chat wire formats.
 */
@Component
@RequiredArgsConstructor
public class LlmMessageCodec {

    // structured serialization format
    public static final String TOOL_CALLS_JSON_TYPE = "tool_calls";
    public static final String TOOL_RESULT_JSON_TYPE = "tool_result";

    private final ObjectMapper objectMapper;

    // ── PO → domain ──────────────────────────────────────────────────────

    public List<LlmMessage> toMessages(List<ChatMessagePo> poList) {
        List<LlmMessage> messages = new ArrayList<>(poList.size());
        Map<String, String> toolNames = new HashMap<>();
        for (ChatMessagePo po : poList) {
            LlmMessage message = toMessage(po);
            for (LlmToolCall call : message.toolCalls()) toolNames.put(call.id(), call.name());
            if ("tool".equalsIgnoreCase(po.getRole())) {
                message = toMessage(po, toolNames.get(message.toolCallId()));
            }
            messages.add(message);
        }
        return messages;
    }

    public LlmMessage toMessage(ChatMessagePo po) {
        return toMessage(po, null);
    }

    public LlmMessage toMessage(ChatMessagePo po, String sourceTool) {
        if (po == null) {
            throw new PersistedDataIntegrityException("chat message", null, "message", "record is null");
        }
        if (po.getRole() == null || po.getRole().isBlank()) {
            throw new PersistedDataIntegrityException("chat message", po.getId(), "role", "role is missing");
        }
        String roleStr = po.getRole().toLowerCase(Locale.ROOT);
        String content = po.getContent() == null ? "" : po.getContent();

        if ("tool".equals(roleStr)) {
            ToolResultParts parts = parseToolResult(content, po.getId());
            return LlmMessage.tool(parts.toolCallId(), projectToolResult(parts.result(), po.getId(), sourceTool));
        }

        if ("assistant".equals(roleStr)) {
            List<LlmToolCall> toolCalls = parseAssistantToolCalls(content, po.getId());
            if (toolCalls != null) {
                return LlmMessage.assistantToolCalls(toolCalls);
            }
            return LlmMessage.assistant(content);
        }

        return switch (roleStr) {
            case "system" -> LlmMessage.system(content);
            case "user" -> LlmMessage.user(content);
            default -> throw new PersistedDataIntegrityException(
                    "chat message", po.getId(), "role", "unsupported role '" + roleStr + "'");
        };
    }

    /** The canonical result is retained for server-side proposal selection, never copied by the model. */
    public String toolResultContent(ChatMessagePo po) {
        if (po == null || !"tool".equalsIgnoreCase(po.getRole())) {
            throw new PersistedDataIntegrityException("chat message", po == null ? null : po.getId(),
                    "role", "a stored tool result is required");
        }
        return parseToolResult(po.getContent(), po.getId()).result();
    }

    private String projectToolResult(String result, Long messageId, String sourceTool) {
        JsonNode root;
        try {
            root = objectMapper.readTree(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return result;
        }
        if (root == null || !root.isObject()) return result;
        boolean changed = false;
        if (root.path("suggestions").isArray()) {
            int index = 0;
            for (JsonNode suggestion : root.path("suggestions")) {
                index++;
                if (!suggestion.has("suggestionToken")) continue;
                if (!(suggestion instanceof ObjectNode object) || messageId == null || messageId <= 0) {
                    throw new PersistedDataIntegrityException("chat message", messageId, "content",
                            "signed repair suggestions require a persisted message identity");
                }
                object.remove("suggestionToken");
                object.put("suggestionId", messageId + ":" + index);
                changed = true;
            }
        }
        changed |= projectBoardReads(root.path("rules"), messageId, "rule",
                AiToolResultContract.isCompletedRead(root)
                        && ("list_rules".equals(sourceTool) || "board_overview".equals(sourceTool)));
        changed |= projectBoardReads(root.path("specs"), messageId, "spec",
                AiToolResultContract.isCompletedRead(root)
                        && ("list_specs".equals(sourceTool) || "board_overview".equals(sourceTool)));
        for (String field : List.of("rule", "specification", "deletedRule", "deletedSpecification")) {
            if (root.path(field) instanceof ObjectNode item && item.has("expectedVersion")) {
                item.remove("expectedVersion");
                changed = true;
            }
        }
        return changed ? root.toString() : result;
    }

    private boolean projectBoardReads(JsonNode rows, Long messageId, String kind, boolean selectable) {
        if (!rows.isArray()) return false;
        boolean changed = false;
        for (int index = 0; index < rows.size(); index++) {
            if (!(rows.get(index) instanceof ObjectNode item) || !item.has("expectedVersion")) continue;
            item.remove("expectedVersion");
            if (selectable) {
                if (messageId == null || messageId <= 0) {
                    throw new PersistedDataIntegrityException("chat message", messageId, "content",
                            "Board reads require a persisted message identity");
                }
                item.put("readRef", messageId + ":" + kind + ":" + (index + 1));
            }
            changed = true;
        }
        return changed;
    }

    // ── domain → persisted content ───────────────────────────────────────

    /** Serialize an assistant tool-call request for persistence (structured form). */
    public String serializeToolCalls(List<LlmToolCall> toolCalls) {
        try {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (LlmToolCall c : toolCalls) {
                calls.add(Map.of(
                        "id", c.id(),
                        "type", "function",
                        "function", Map.of("name", c.name(), "arguments", c.argumentsJson())));
            }
            return objectMapper.writeValueAsString(Map.of(
                    "type", TOOL_CALLS_JSON_TYPE,
                    "toolCalls", calls));
        } catch (Exception e) {
            throw InternalServerException.jsonSerializationFailed(e);
        }
    }

    /** Serialize a tool-execution result for persistence (structured form). */
    public String serializeToolResult(String toolCallId, String result) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", TOOL_RESULT_JSON_TYPE,
                    "toolCallId", toolCallId == null ? "" : toolCallId,
                    "result", result == null ? "" : result));
        } catch (Exception e) {
            throw InternalServerException.jsonSerializationFailed(e);
        }
    }

    // ── classification helpers (used by history windowing / frontend filtering) ──

    public boolean isToolResultContent(ChatMessagePo po) {
        return po != null && "tool".equalsIgnoreCase(po.getRole());
    }

    public boolean isAssistantToolCallContent(ChatMessagePo po) {
        if (po == null || !"assistant".equalsIgnoreCase(po.getRole())) {
            return false;
        }
        String content = po.getContent() == null ? "" : po.getContent();
        try {
            JsonNode root = objectMapper.readTree(content);
            return root.isObject()
                    && TOOL_CALLS_JSON_TYPE.equals(root.path("type").asText(""))
                    && root.has("toolCalls");
        } catch (Exception ignore) {
            return false;
        }
    }

    // ── internal parsing ─────────────────────────────────────────────────

    private ToolResultParts parseToolResult(String content, Long messageId) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (!root.isObject() || !TOOL_RESULT_JSON_TYPE.equals(root.path("type").asText(""))) {
                throw new IllegalArgumentException("tool message is not a structured tool_result");
            }
            String toolCallId = root.path("toolCallId").asText("");
            if (toolCallId.isBlank() || !root.has("result") || !root.get("result").isTextual()) {
                throw new IllegalArgumentException("tool_result requires a non-blank toolCallId and textual result");
            }
            return new ToolResultParts(toolCallId, root.get("result").asText());
        } catch (Exception e) {
            throw new PersistedDataIntegrityException("chat message", messageId, "content", e);
        }
    }

    private List<LlmToolCall> parseAssistantToolCalls(String content, Long messageId) {
        if (content != null && !content.isEmpty() && content.stripLeading().startsWith("{")) {
            try {
                JsonNode root = objectMapper.readTree(content);
                if (root.isObject() && TOOL_CALLS_JSON_TYPE.equals(root.path("type").asText(""))
                        && root.has("toolCalls")) {
                    return extractCalls(root.path("toolCalls"), messageId);
                }
            } catch (Exception e) {
                if (e instanceof PersistedDataIntegrityException integrityException) {
                    throw integrityException;
                }
                throw new PersistedDataIntegrityException("chat message", messageId, "content", e);
            }
        }
        return null;
    }

    private List<LlmToolCall> extractCalls(JsonNode toolCallsNode, Long messageId) {
        List<LlmToolCall> calls = new ArrayList<>();
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            throw new PersistedDataIntegrityException(
                    "chat message", messageId, "content", "toolCalls must be a non-empty array");
        }
        for (JsonNode node : toolCallsNode) {
            JsonNode fn = node.path("function");
            String id = node.path("id").asText("");
            String name = fn.path("name").asText("");
            JsonNode arguments = fn.get("arguments");
            if (id.isBlank() || name.isBlank() || arguments == null || !arguments.isTextual()) {
                throw new PersistedDataIntegrityException(
                        "chat message", messageId, "content", "tool call id, name, or arguments is invalid");
            }
            calls.add(new LlmToolCall(id, name, arguments.asText()));
        }
        return calls;
    }

    private record ToolResultParts(String toolCallId, String result) {
    }
}
