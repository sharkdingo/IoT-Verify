package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.ai.provider.LlmProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Facade the chat orchestration layer ({@code ChatServiceImpl}) talks to. Combines the
 * {@link LlmProvider} strategy so the orchestrator deals only in
 * {@link LlmMessage}/{@link LlmChatResponse} and never touches a provider SDK.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmChatService {

    private final LlmProvider llmProvider;
    /**
     * One tool-aware round. Text is delivered while receiving the response; tools are returned
     * only after the provider has assembled and validated their complete arguments.
     */
    public LlmChatResponse chatWithTools(List<LlmMessage> messages, List<LlmToolSpec> tools,
                                         Consumer<String> onDelta) {
        LlmChatRequest request = LlmChatRequest.builder()
                .messages(messages)
                .tools(tools)
                .build();
        return llmProvider.chat(request, LlmRequestControlHolder.currentOrNew(), onDelta);
    }

    /**
     * Stream the visible assistant reply (no tools), forwarding text deltas to {@code onDelta}
     * until {@code shouldStop} signals cancellation.
     */
    public void streamReply(List<LlmMessage> messages, Consumer<String> onDelta, BooleanSupplier shouldStop) {
        LlmChatRequest request = LlmChatRequest.builder()
                .messages(messages)
                .build();
        llmProvider.streamChat(
                request, onDelta, shouldStop, LlmRequestControlHolder.currentOrNew());
    }
}
