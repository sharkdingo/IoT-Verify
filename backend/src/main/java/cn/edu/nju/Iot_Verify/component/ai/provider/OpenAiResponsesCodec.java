package cn.edu.nju.Iot_Verify.component.ai.provider;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmRole;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stateless Responses encoding; completed opaque reasoning travels with its function calls. */
@Slf4j
final class OpenAiResponsesCodec {

    private OpenAiResponsesCodec() { }

    static ResponseCreateParams toParams(LlmChatRequest request, String model, String effort, boolean includeTools) {
        List<ResponseInputItem> input = new ArrayList<>();
        List<String> instructions = new ArrayList<>();
        boolean leadingSystem = true;
        for (LlmMessage message : request.messages()) {
            // Explicit instructions prevent compatible relays from substituting their default
            // agent prompt. Later system reminders retain their position beside tool evidence.
            if (leadingSystem && message.role() == LlmRole.SYSTEM) {
                instructions.add(message.content());
                continue;
            }
            leadingSystem = false;
            if (!message.continuationItems().isEmpty()) {
                for (Object item : message.continuationItems()) {
                    if (!(item instanceof ResponseInputItem responseItem)) {
                        throw new OpenAIInvalidDataException("Unexpected provider continuation item");
                    }
                    input.add(responseItem);
                }
            } else if (message.hasToolCalls()) {
                for (LlmToolCall call : message.toolCalls()) {
                    input.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                            .callId(call.id()).name(call.name()).arguments(call.argumentsJson()).build()));
                }
            } else if (message.toolCallId() != null) {
                input.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                        .callId(message.toolCallId()).output(message.content()).build()));
            } else {
                input.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.of(message.role().name().toLowerCase(java.util.Locale.ROOT)))
                        .content(message.content()).build()));
            }
        }
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder().model(model)
                .inputOfResponse(input).store(false).addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
        if (!instructions.isEmpty()) builder.instructions(String.join("\n\n", instructions));
        if (effort != null) {
            Reasoning.Builder reasoning = Reasoning.builder().effort(ReasoningEffort.of(effort));
            if (!"none".equals(effort)) reasoning.summary(Reasoning.Summary.AUTO);
            builder.reasoning(reasoning.build());
        }
        if (request.temperature() != null && (effort == null || "none".equals(effort))) {
            builder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) builder.maxOutputTokens(request.maxTokens());
        if (includeTools) {
            request.tools().forEach(spec -> {
                FunctionTool.Parameters.Builder parameters = FunctionTool.Parameters.builder();
                OpenAiLlmProvider.toJsonSchemaObject(spec.parameters()).forEach((name, value) ->
                        parameters.putAdditionalProperty(name, JsonValue.from(value)));
                builder.addTool(FunctionTool.builder().name(spec.name())
                        .description(spec.description() == null ? "" : spec.description())
                        .parameters(parameters.build()).strict(false).build());
            });
        }
        return builder.build();
    }

    static LlmChatResponse parse(Response response, LlmChatRequest request, boolean includeTools) {
        if (response.error().filter(error -> "server_error".equals(error.code().asString())).isPresent()) {
            throw new LlmStreamInterruptedException(new OpenAIInvalidDataException("Upstream model response failed"));
        }
        if (!"completed".equals(response.status().map(status -> status.asString()).orElse(null))
                || response.error().isPresent()) {
            throw new OpenAIInvalidDataException("Model response did not finish normally");
        }
        StringBuilder text = new StringBuilder(), summary = new StringBuilder();
        List<LlmToolCall> calls = new ArrayList<>();
        List<ResponseInputItem> continuation = new ArrayList<>();
        Set<String> callIds = new HashSet<>();
        request.messages().forEach(message -> message.toolCalls().forEach(call -> callIds.add(call.id())));
        for (ResponseOutputItem item : response.output()) {
            if (item.isMessage()) {
                item.asMessage().content().forEach(content -> {
                    if (content.isOutputText()) text.append(content.asOutputText().text());
                    else if (content.isRefusal()) text.append(content.asRefusal().refusal());
                    else throw new OpenAIInvalidDataException("Unsupported model output content");
                });
                continuation.add(ResponseInputItem.ofResponseOutputMessage(item.asMessage()));
            } else if (item.isFunctionCall()) {
                ResponseFunctionToolCall call = item.asFunctionCall();
                if (!includeTools || call.callId().isBlank() || !call.callId().equals(call.callId().trim())
                        || !callIds.add(call.callId())) {
                    throw new OpenAIInvalidDataException("Invalid or repeated model function-call identity");
                }
                calls.add(new LlmToolCall(call.callId(), call.name(), call.arguments()));
                continuation.add(ResponseInputItem.ofFunctionCall(call));
            } else if (item.isReasoning()) {
                item.asReasoning().summary().forEach(part -> {
                    if (!summary.isEmpty()) summary.append('\n');
                    summary.append(part.text());
                });
                continuation.add(ResponseInputItem.ofReasoning(item.asReasoning()));
            } else {
                throw new OpenAIInvalidDataException("Unsupported model output item");
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            throw new OpenAIInvalidDataException("Model returned no answer or function calls");
        }
        // Usage is diagnostic metadata; missing provider counters cannot invalidate completed output.
        long reasoningTokens = response._usage().asKnown()
                .flatMap(usage -> usage._outputTokensDetails().asKnown())
                .flatMap(details -> details._reasoningTokens().asKnown()).orElse(-1L);
        log.info("LLM Responses completed: functionCalls={}, reasoningTokens={}, continuationItems={}",
                calls.size(), reasoningTokens, continuation.size());
        return new LlmChatResponse(text.toString(), calls,
                summary.substring(0, Math.min(summary.length(), OpenAiLlmProvider.MAX_REASONING_SUMMARY_CHARS)), continuation);
    }
}
