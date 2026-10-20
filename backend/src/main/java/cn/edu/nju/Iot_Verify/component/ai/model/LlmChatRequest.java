package cn.edu.nju.Iot_Verify.component.ai.model;

import java.util.List;

/**
 * Provider-agnostic chat-completion request.
 *
 * <p>One request type serves both flows:
 * <ul>
 *   <li>the multi-round tool loop (tools non-empty, temperature/maxTokens null → provider defaults),</li>
 *   <li>one-shot prompt completions used by the recommend tools (tools empty, explicit
 *       temperature/maxTokens).</li>
 * </ul>
 *
 * <p>{@code model} is optional; when null the provider uses its configured default model.
 * Build via {@link #builder()}.
 */
public record LlmChatRequest(List<LlmMessage> messages,
                             List<LlmToolSpec> tools,
                             String model,
                             Double temperature,
                             Integer maxTokens,
                             String reasoningEffort) {

    public LlmChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<LlmMessage> messages = List.of();
        private List<LlmToolSpec> tools = List.of();
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private String reasoningEffort;

        public Builder messages(List<LlmMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder tools(List<LlmToolSpec> tools) {
            this.tools = tools;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        public LlmChatRequest build() {
            return new LlmChatRequest(messages, tools, model, temperature, maxTokens, reasoningEffort);
        }
    }
}
