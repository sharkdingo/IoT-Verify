package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.component.ai.LlmMessageCodec;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResultContract;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import cn.edu.nju.Iot_Verify.util.BoardItemVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves short selectors against their owner's canonical conversation records. */
@Service
@RequiredArgsConstructor
public class AiToolResultReferenceService {

    public static final String BOARD_READ_PATTERN = "[1-9][0-9]{0,18}:(rule|spec):[1-9][0-9]{0,9}";

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final LlmMessageCodec messageCodec;

    public ResolvedSuggestion resolveRepair(Long userId, String sessionId, long traceId,
                                            long messageId, int optionIndex) {
        JsonNode result = readResult(ownedMessage(userId, sessionId, messageId));
        JsonNode suggestions = result.path("suggestions");
        if (!result.path("traceId").isIntegralNumber() || !result.path("traceId").canConvertToLong()
                || result.path("traceId").longValue() != traceId || !suggestions.isArray()
                || optionIndex < 0 || optionIndex >= suggestions.size()
                || !result.path("preferredRangeSelections").isArray()) {
            throw new BadRequestException("The selected repair option does not match this issue. Analyze the issue again before applying it.");
        }
        JsonNode suggestion = suggestions.get(optionIndex);
        if (!suggestion.isObject()) throw unavailable();
        return new ResolvedSuggestion(suggestion, result.get("preferredRangeSelections"));
    }

    public String resolveBoardVersion(Long userId, String sessionId, String readRef, String kind, String targetId) {
        if (readRef == null || !readRef.matches(BOARD_READ_PATTERN)) throw unavailableRead();
        String[] reference = readRef.split(":");
        if (!kind.equals(reference[1])) throw unavailableRead();
        long messageId;
        int index;
        try {
            messageId = Long.parseLong(reference[0]);
            index = Integer.parseInt(reference[2]) - 1;
        } catch (NumberFormatException error) {
            throw unavailableRead();
        }
        ChatMessagePo stored = ownedMessage(userId, sessionId, messageId);
        var request = messageRepository.findFirstBySessionIdAndIdLessThanAndRoleOrderByIdDesc(
                sessionId, messageId, "assistant").orElseThrow(this::unavailableRead);
        String callId = messageCodec.toMessage(stored).toolCallId();
        var matchingCalls = messageCodec.toMessage(request).toolCalls().stream()
                .filter(call -> call.id().equals(callId)).toList();
        if (matchingCalls.size() != 1) throw unavailableRead();
        String source = matchingCalls.get(0).name();
        if (!("board_overview".equals(source) || ("rule".equals(kind) ? "list_rules" : "list_specs").equals(source))) {
            throw unavailableRead();
        }
        JsonNode result = readResult(stored);
        if (!AiToolResultContract.isCompletedRead(result)) throw unavailableRead();
        JsonNode rows = result.path("rule".equals(kind) ? "rules" : "specs");
        if (!rows.isArray() || index < 0 || index >= rows.size()) throw unavailableRead();
        JsonNode selected = rows.get(index);
        JsonNode id = selected.path("rule".equals(kind) ? "ruleId" : "specId");
        if (("rule".equals(kind) ? !id.isIntegralNumber() || !id.canConvertToLong() : !id.isTextual())
                || !targetId.equals(id.asText())) throw unavailableRead();
        JsonNode version = selected.path("expectedVersion");
        if (!version.isTextual() || !version.textValue().matches(BoardItemVersion.PATTERN)) throw unavailableRead();
        return version.textValue();
    }

    private ChatMessagePo ownedMessage(Long userId, String sessionId, long messageId) {
        if (userId == null || sessionId == null || sessionId.isBlank()
                || sessionRepository.findByIdAndUserId(sessionId, userId).isEmpty()) {
            throw unavailable();
        }
        return messageRepository.findByIdAndSessionIdAndRole(messageId, sessionId, "tool")
                .orElseThrow(this::unavailable);
    }

    private JsonNode readResult(ChatMessagePo stored) {
        JsonNode result;
        try {
            result = objectMapper.readTree(messageCodec.toolResultContent(stored));
        } catch (JsonProcessingException e) {
            throw unavailable();
        }
        if (result == null || !result.isObject()) throw unavailable();
        return result;
    }

    private BadRequestException unavailable() {
        return new BadRequestException("The selected tool result is unavailable in this conversation. Read the target or analyze the issue again.");
    }

    private BadRequestException unavailableRead() {
        return new BadRequestException("readRef must select this target from a completed list_rules, list_specs or board_overview result in this conversation. Read the target again.");
    }

    public record ResolvedSuggestion(JsonNode suggestion, JsonNode preferredRangeSelections) {
    }
}
