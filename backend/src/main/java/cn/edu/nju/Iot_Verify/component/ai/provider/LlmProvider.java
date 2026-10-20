package cn.edu.nju.Iot_Verify.component.ai.provider;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Strategy interface for a chat LLM backend. Implementations adapt a concrete vendor SDK
 * (OpenAI-compatible endpoints, including relays/proxies configured via {@code llm.base-url})
 * to the provider-agnostic domain model in {@code component.ai.model}.
 *
 * <p>This is the single seam that isolates the rest of the application from any SDK: no code
 * outside a {@code LlmProvider} implementation imports a vendor type. Adding another backend
 * means adding one implementation — nothing else changes.
 *
 * <p>Implementations must translate transport/SDK failures into
 * {@link cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException} so callers stay
 * SDK-agnostic.
 */
public interface LlmProvider {

    /**
     * Blocking chat completion. Used by the tool-calling loop (with tools) and by one-shot
     * prompt completions (without tools).
     *
     * @param request messages + optional tools + optional generation params
     * @return the assembled model reply as text or tool-call requests; cancellation may return
     *         {@link LlmChatResponse#empty()}. Incomplete provider responses must fail explicitly.
     */
    LlmChatResponse chat(LlmChatRequest request);

    default LlmChatResponse chat(LlmChatRequest request, LlmRequestControl control) {
        return chat(request);
    }

    /** Streams public assistant text while assembling a complete, validated tool-aware response. */
    default LlmChatResponse chat(LlmChatRequest request, LlmRequestControl control, Consumer<String> onDelta) {
        LlmChatResponse response = chat(request, control);
        if (!control.isCancellationRequested() && response != null && !response.text().isEmpty()) {
            onDelta.accept(response.text());
        }
        return response;
    }

    /**
     * Streaming chat completion. Text deltas are delivered to {@code onDelta} as they arrive.
     * Streaming stops early (and the underlying HTTP stream is closed) as soon as
     * {@code shouldStop} returns true — used to abort promptly on client disconnect.
     *
     * @param request    messages + optional generation params (tools are ignored for streaming)
     * @param onDelta    consumer invoked per non-empty text delta
     * @param shouldStop cooperative cancellation signal, polled between chunks
     */
    void streamChat(LlmChatRequest request, Consumer<String> onDelta, BooleanSupplier shouldStop);

    default void streamChat(LlmChatRequest request,
                            Consumer<String> onDelta,
                            BooleanSupplier shouldStop,
                            LlmRequestControl control) {
        streamChat(request, onDelta, shouldStop);
    }
}
