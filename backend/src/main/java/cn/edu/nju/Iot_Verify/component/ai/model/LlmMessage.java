package cn.edu.nju.Iot_Verify.component.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Provider-agnostic chat message — the internal domain model that replaces a vendor
 * SDK's {@code ChatMessage}. Used throughout the chat tool-loop and history handling.
 *
 * <p>Semantics by role:
 * <ul>
 *   <li>{@code SYSTEM}/{@code USER}: {@code content} holds the text; {@code toolCallId} and
 *       {@code toolCalls} are null.</li>
 *   <li>{@code ASSISTANT} plain reply: {@code content} holds the text.</li>
 *   <li>{@code ASSISTANT} tool-call request: {@code toolCalls} is non-empty; {@code content}
 *       is typically empty.</li>
 *   <li>{@code TOOL} result: {@code content} holds the tool's JSON result and
 *       {@code toolCallId} references the originating {@link LlmToolCall#id()}.</li>
 * </ul>
 *
 * <p>Instances are immutable; use the static factory methods.
 */
public record LlmMessage(LlmRole role,
                         String content,
                         String toolCallId,
                         List<LlmToolCall> toolCalls,
                         @JsonIgnore List<?> continuationItems) {

    public LlmMessage {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        continuationItems = continuationItems == null ? List.of() : List.copyOf(continuationItems);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(LlmRole.SYSTEM, content, null, List.of(), List.of());
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(LlmRole.USER, content, null, List.of(), List.of());
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(LlmRole.ASSISTANT, content, null, List.of(), List.of());
    }

    public static LlmMessage assistantToolCalls(List<LlmToolCall> toolCalls) {
        return assistantToolCalls(toolCalls, List.of());
    }

    /** Completed provider items stay together for in-turn reasoning; they are not chat history. */
    public static LlmMessage assistantToolCalls(List<LlmToolCall> toolCalls, List<?> continuationItems) {
        return new LlmMessage(LlmRole.ASSISTANT, "", null, toolCalls, continuationItems);
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage(LlmRole.TOOL, content, toolCallId, List.of(), List.of());
    }

    @Override
    public String toString() {
        return "LlmMessage[role=" + role + ", content=" + content + ", toolCallId=" + toolCallId
                + ", toolCalls=" + toolCalls + "]";
    }

    /** True when this is an assistant message carrying one or more tool-call requests. */
    public boolean hasToolCalls() {
        return role == LlmRole.ASSISTANT && toolCalls != null && !toolCalls.isEmpty();
    }
}
