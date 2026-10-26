package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.chat.ChatToolProgressPresenter;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmRole;

@RequiredArgsConstructor
public class ChatContextCompressor {
    private final ObjectMapper mapper;
    private final ChatToolProgressPresenter presenter;

    public void compact(List<LlmMessage> messages, int charBudget) {
        long length = messages.stream().filter(m -> m.role() != LlmRole.SYSTEM)
                .mapToLong(m -> m.content().length()).sum();
        if (length <= charBudget) return;
        int newestCall = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).hasToolCalls()) { newestCall = i; break; }
        }
        Map<String, String> names = new HashMap<>();
        for (int i = 0; i < newestCall && length > charBudget; i++) {
            LlmMessage message = messages.get(i);
            message.toolCalls().forEach(call -> names.put(call.id(), call.name()));
            if (message.role() != LlmRole.TOOL || message.content().length() < 1000) continue;
            String name = names.get(message.toolCallId());
            if (name == null) continue;
            String summary = presenter.toolProgressDetail(name, message.content(), false);
            if (summary == null) summary = "Evidence unavailable; retrieve the saved operation before drawing conclusions.";
            if (summary.length() > 400) summary = summary.substring(0, 400) + " [summary shortened]";
            var compact = mapper.createObjectNode();
            compact.put("contextCompacted", true);
            compact.put("historicalEvidenceOnly", true);
            compact.put("toolName", name);
            compact.put("evidenceSummary", summary);
            compact.put("retrieval", "Use read_conversation_activity for saved receipts, read_user_messages for original requirements, and fresh Board reads for current data. This summary cannot restore readRefs or confirmations.");
            String content = compact.toString();
            if (content.length() < message.content().length()) {
                length -= message.content().length() - content.length();
                messages.set(i, LlmMessage.tool(message.toolCallId(), content));
            }
        }
    }
}
