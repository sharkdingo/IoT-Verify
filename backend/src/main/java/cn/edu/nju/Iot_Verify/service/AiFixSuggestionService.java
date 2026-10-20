package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.component.ai.LlmMessageCodec;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves only the selected repair option from its owner's canonical conversation record. */
@Service
@RequiredArgsConstructor
public class AiFixSuggestionService {

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final LlmMessageCodec messageCodec;

    public ResolvedSuggestion resolve(Long userId, String sessionId, long traceId,
                                      long messageId, int optionIndex) {
        if (userId == null || sessionId == null || sessionId.isBlank()
                || sessionRepository.findByIdAndUserId(sessionId, userId).isEmpty()) {
            throw unavailable();
        }
        var stored = messageRepository.findByIdAndSessionIdAndRole(messageId, sessionId, "tool")
                .orElseThrow(this::unavailable);
        JsonNode result;
        try {
            result = objectMapper.readTree(messageCodec.toolResultContent(stored));
        } catch (JsonProcessingException e) {
            throw unavailable();
        }
        if (result == null || !result.isObject()) throw unavailable();
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

    private BadRequestException unavailable() {
        return new BadRequestException("The repair option is unavailable in this conversation. Analyze the issue again before applying it.");
    }

    public record ResolvedSuggestion(JsonNode suggestion, JsonNode preferredRangeSelections) {
    }
}
