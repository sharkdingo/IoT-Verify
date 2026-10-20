package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.component.ai.LlmMessageCodec;
import cn.edu.nju.Iot_Verify.component.ai.chat.ChatToolProgressPresenter;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResultContract;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded access to canonical operation evidence beyond the model's recent context window. */
@Service
@RequiredArgsConstructor
public class AiConversationHistoryService {

    public static final int MAX_PAGE_SIZE = 30;
    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final LlmMessageCodec messageCodec;
    private final ChatToolProgressPresenter presenter;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ActivityPage readPage(Long userId, String sessionId, Long beforeId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BadRequestException("Conversation history requires an active chat session.");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE || (beforeId != null && beforeId <= 0)) {
            throw new BadRequestException("Conversation history page arguments are invalid.");
        }
        sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
        List<ChatMessagePo> rows = messageRepository.findToolActivityPage(
                sessionId, beforeId, PageRequest.of(0, limit + 1));
        boolean hasMore = rows.size() > limit;
        List<ChatMessagePo> selected = rows.subList(0, Math.min(limit, rows.size()));
        List<Map<String, Object>> activities = new ArrayList<>(selected.size());
        for (ChatMessagePo row : selected) activities.add(activity(sessionId, row));
        Collections.reverse(activities);
        Long nextBeforeId = hasMore ? selected.get(selected.size() - 1).getId() : null;
        return new ActivityPage(List.copyOf(activities), nextBeforeId, hasMore);
    }

    private Map<String, Object> activity(String sessionId, ChatMessagePo row) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("messageId", row.getId());
        entry.put("createdAt", row.getCreatedAt());
        // A result is evidence only for its adjacent request's exact tool-call id. Assistant
        // prose and current Board state cannot substitute for a missing operation receipt.
        ChatMessagePo request = messageRepository
                .findFirstBySessionIdAndIdLessThanAndRoleOrderByIdDesc(sessionId, row.getId(), "assistant")
                .orElse(null);
        try {
            if (request == null) throw new IllegalArgumentException("request missing");
            String callId = messageCodec.toMessage(row).toolCallId();
            var matching = messageCodec.toMessage(request).toolCalls().stream()
                    .filter(call -> call.id().equals(callId)).toList();
            if (matching.size() != 1) throw new IllegalArgumentException("tool call binding missing");
            String toolName = matching.get(0).name();
            entry.put("toolName", toolName);
            String result = messageCodec.toolResultContent(row);
            JsonNode root = objectMapper.readTree(result);
            if (!AiToolResultContract.hasValidControlFields(root)) {
                throw new IllegalArgumentException("result controls invalid");
            }
            if (AiToolResultContract.isUnavailable(root)) {
                entry.put("evidenceStatus", "UNAVAILABLE");
            } else if (AiToolResultContract.isStructuredError(root)) {
                entry.put("evidenceStatus", "FAILED");
            } else if (root.path("skipped").asBoolean(false)
                    || !presenter.hasValidKnownToolPayload(toolName, root)) {
                throw new IllegalArgumentException("result payload unavailable");
            } else {
                entry.put("evidenceStatus", "AVAILABLE");
                presenter.actionReceipt(toolName, result, false)
                        .ifPresent(receipt -> entry.put("appliedAction", receipt.assistantAction()));
            }
            String summary = presenter.toolProgressDetail(toolName, result, false);
            if (summary != null) entry.put("summary", summary);
            for (String key : List.of("operation", "resultStatus", "verificationStatus", "errorCode",
                    "requiresUserConfirmation", "mutationMayHaveCommitted", "applied", "changesApplied", "draftStored")) {
                JsonNode value = root.get(key);
                if (value != null && (value.isBoolean() || (value.isTextual() && value.textValue().length() <= 120))) {
                    entry.put(key, objectMapper.convertValue(value, Object.class));
                }
            }
        } catch (JsonProcessingException | PersistedDataIntegrityException | IllegalArgumentException e) {
            entry.put("evidenceStatus", "UNAVAILABLE");
            entry.put("unavailableReasonCode", "PERSISTED_OPERATION_EVIDENCE_INVALID");
        }
        return entry;
    }

    public record ActivityPage(List<Map<String, Object>> activities, Long nextBeforeMessageId, boolean hasMore) {
    }
}
