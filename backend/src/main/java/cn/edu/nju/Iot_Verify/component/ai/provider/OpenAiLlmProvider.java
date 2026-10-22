package cn.edu.nju.Iot_Verify.component.ai.provider;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.configure.LlmConfig;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.SseException;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.ReasoningEffort;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * OpenAI-compatible {@link LlmProvider} implementation and the sole anti-corruption layer
 * between the application and the {@code com.openai:openai-java} SDK. SDK imports stay in
 * the provider package; the rest of the codebase sees only {@code component.ai.model} types.
 *
 * <p>Supports Chat Completions and Responses through {@code llm.base-url}. Protocol selection
 * follows {@link LlmConfig}; reasoning-capable tool calls retain the original Responses items.
 *
 * <p>Registered only when {@code llm.provider=openai} (the default), leaving room for
 * additional providers without touching this class.
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "llm", name = "provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiLlmProvider implements LlmProvider {

    static final int MAX_REASONING_SUMMARY_CHARS = 2000;
    private static final List<String> SAFE_REASONING_SUMMARY_FIELDS = List.of(
            "reasoning_summary",
            "reasoningSummary",
            "reasoning_summary_content",
            "analysis_summary",
            "analysisSummary");

    private final LlmConfig config;
    private okhttp3.OkHttpClient transport;
    private OpenAIClient client;
    private OpenAIClient alternativeClient;

    public OpenAiLlmProvider(LlmConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        Duration requestTimeout = Duration.ofMinutes(config.getTimeoutMinutes());
        Timeout timeout = Timeout.builder().connect(Duration.ofSeconds(10))
                .read(requestTimeout).write(requestTimeout).request(requestTimeout).build();
        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
        // Match the SDK's dispatcher policy rather than introducing a per-host queue.
        dispatcher.setMaxRequestsPerHost(dispatcher.getMaxRequests());
        this.transport = new okhttp3.OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .retryOnConnectionFailure(false)
                .build();
        this.client = createClient(transport, timeout);
        this.alternativeClient = createClient(transport.newBuilder()
                .protocols(List.of(okhttp3.Protocol.HTTP_1_1)).build(), timeout);
        log.info("OpenAiLlmProvider initialized: model={}, api={}", config.getModel(),
                config.usesResponses(config.getModel()) ? "responses" : "chat-completions");
    }

    private OpenAIClient createClient(okhttp3.OkHttpClient transport, Timeout timeout) {
        return new OpenAIClientImpl(ClientOptions.builder()
                .httpClient(new com.openai.client.okhttp.OkHttpClient(transport))
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .putHeader("Accept-Charset", "utf-8")
                .timeout(timeout)
                // Chat owns one evidence-preserving continuation; do not multiply it by SDK retries.
                .maxRetries(0)
                .build());
    }

    /** Request views borrow the provider's pool and dispatcher; SDK phantom cleanup must not close them. */
    private static final class BorrowedHttpClient implements HttpClient {
        private final HttpClient delegate;

        private BorrowedHttpClient(okhttp3.OkHttpClient transport) {
            delegate = new com.openai.client.okhttp.OkHttpClient(transport);
        }

        @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
            return delegate.execute(request, options);
        }

        @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
            return delegate.executeAsync(request, options);
        }

        @Override public void close() { }
    }

    /** OkHttp's TCP timeout excludes TLS; stop a stalled setup without limiting model generation. */
    private static final class ConnectionSetupBudget extends okhttp3.EventListener {
        private final AtomicBoolean connected = new AtomicBoolean();
        private CompletableFuture<Void> budget;
        private long startedAt;
        private boolean connectStarted;

        @Override public void callStart(okhttp3.Call call) {
            startedAt = System.nanoTime();
            budget = CompletableFuture.runAsync(() -> {
                if (!connected.get()) call.cancel();
            }, CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS));
        }

        @Override public void connectionAcquired(okhttp3.Call call, okhttp3.Connection connection) {
            connected.set(true);
            clearBudget();
            log.info("LLM connection ready: protocol={}, reused={}, setupMs={}", connection.protocol(),
                    !connectStarted, (System.nanoTime() - startedAt) / 1_000_000);
        }

        @Override public void connectStart(okhttp3.Call call, java.net.InetSocketAddress address, java.net.Proxy proxy) {
            connectStarted = true;
        }

        @Override public void callEnd(okhttp3.Call call) { clearBudget(); }

        @Override public void callFailed(okhttp3.Call call, IOException failure) {
            clearBudget();
            log.warn("LLM transport failed: connected={}, elapsedMs={}, exception={}", connected.get(),
                    (System.nanoTime() - startedAt) / 1_000_000, failure.getClass().getName());
        }

        private void clearBudget() { if (budget != null) budget.cancel(false); }
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            try {
                client.close();
                log.info("OpenAiLlmProvider client closed");
            } catch (Exception e) {
                log.warn("Error closing OpenAI client: exception={}", e.getClass().getName());
            }
        }
        if (alternativeClient != null) {
            try {
                alternativeClient.close();
            } catch (Exception failure) {
                log.warn("Error closing alternative OpenAI client: exception={}", failure.getClass().getName());
            }
        }
    }

    // ── LlmProvider ──────────────────────────────────────────────────────

    @Override
    public LlmChatResponse chat(LlmChatRequest request) {
        return chat(request, new LlmRequestControl());
    }

    @Override
    public LlmChatResponse chat(LlmChatRequest request, LlmRequestControl control) {
        return chat(request, control, ignored -> { });
    }

    @Override
    public LlmChatResponse chat(LlmChatRequest request, LlmRequestControl control, Consumer<String> onDelta) {
        return completeStreaming(request, control, onDelta, () -> false, true);
    }

    private LlmChatResponse completeStreaming(LlmChatRequest request, LlmRequestControl control,
                                              Consumer<String> onDelta, BooleanSupplier shouldStop,
                                              boolean includeTools) {
        if (control.isCancellationRequested() || shouldStop.getAsBoolean()) return LlmChatResponse.empty();
        String model = request.model() == null || request.model().isBlank() ? config.getModel() : request.model();
        try {
            if (config.usesResponses(model)) {
                String effort = effectiveReasoningEffort(request, model);
                var params = OpenAiResponsesCodec.toParams(request, model, effort, includeTools);
                ResponseAccumulator accumulator = ResponseAccumulator.create();
                return consumeStream(request, control, onDelta, shouldStop, includeTools, "responses",
                        requestClient -> requestClient.async().responses().createStreaming(params),
                        event -> {
                            accumulator.accumulate(event);
                            if (event.failed().isPresent()) {
                                OpenAiResponsesCodec.parse(event.failed().get().response(), request, includeTools);
                            }
                            return event.outputTextDelta().map(delta -> delta.delta())
                                    .or(() -> event.refusalDelta().map(delta -> delta.delta())).orElse("");
                        }, () -> OpenAiResponsesCodec.parse(accumulator.response(), request, includeTools));
            }
            ChatCompletionCreateParams params = toParams(request, includeTools);
            ChatCompletionAccumulator accumulator = ChatCompletionAccumulator.create();
            return consumeStream(request, control, onDelta, shouldStop, includeTools, "chat-completions",
                    requestClient -> requestClient.async().chat().completions().createStreaming(params),
                    chunk -> {
                        accumulator.accumulate(chunk);
                        return chunk.choices().isEmpty() ? "" : chunk.choices().get(0).delta().content().orElse("");
                    }, () -> {
                        ChatCompletion completion = accumulator.chatCompletion();
                        if (completion.choices().stream().anyMatch(choice ->
                                !(includeTools ? List.of("stop", "tool_calls") : List.of("stop"))
                                        .contains(choice.finishReason().asString()))) {
                            throw new OpenAIInvalidDataException("Model response did not finish normally");
                        }
                        return parseResponse(completion);
                    });
        } catch (ServiceUnavailableException failure) {
            throw failure;
        } catch (Exception failure) {
            throw completionFailure(failure, control);
        }
    }

    private <T> LlmChatResponse consumeStream(LlmChatRequest request, LlmRequestControl control,
                                             Consumer<String> onDelta, BooleanSupplier shouldStop,
                                             boolean includeTools, String api,
                                             Function<OpenAIClient, AsyncStreamResponse<T>> openStream,
                                             Function<T, String> accumulateDelta,
                                             Supplier<LlmChatResponse> assembledResponse) {
        long startedAt = System.nanoTime();
        AtomicLong firstChunkAt = new AtomicLong(-1), firstTextAt = new AtomicLong(-1);
        AsyncStreamResponse<T> stream = null;
        AtomicReference<okhttp3.Call> activeCall = new AtomicReference<>();
        AutoCloseable cancellation = null;
        try {
            // Attach cancellation before response headers in both planning and final replies;
            // unfinished tool arguments never leave this boundary.
            boolean useAlternative = control.isAlternativeTransportRequested();
            okhttp3.OkHttpClient requestTransport = transport.newBuilder()
                    .protocols(useAlternative ? List.of(okhttp3.Protocol.HTTP_1_1) : transport.protocols())
                    .eventListenerFactory(call -> {
                        activeCall.set(call);
                        if (control.isCancellationRequested()) call.cancel();
                        return new ConnectionSetupBudget();
                    }).build();
            OpenAIClient requestClient = (useAlternative ? alternativeClient : client).withOptions(options ->
                    options.httpClient(new BorrowedHttpClient(requestTransport)));
            AsyncStreamResponse<T> activeStream = openStream.apply(requestClient);
            stream = activeStream;
            cancellation = () -> {
                // Closing the SDK stream alone can wait for its blocked reader. Cancel the
                // owning network call first, without affecting other users of the shared pool.
                okhttp3.Call call = activeCall.get();
                if (call != null) call.cancel();
                activeStream.close();
            };
            control.attach(cancellation);
            if (control.isCancellationRequested() || shouldStop.getAsBoolean()) return LlmChatResponse.empty();
            activeStream.subscribe(chunk -> {
                firstChunkAt.compareAndSet(-1, System.nanoTime());
                if (control.isCancellationRequested() || shouldStop.getAsBoolean()) {
                    control.cancel();
                    activeStream.close();
                    return;
                }
                String delta = accumulateDelta.apply(chunk);
                if (!delta.isEmpty()) {
                    firstTextAt.compareAndSet(-1, System.nanoTime());
                    onDelta.accept(delta);
                }
            });
            stream.onCompleteFuture().get();
            if (control.isCancellationRequested() || shouldStop.getAsBoolean()) return LlmChatResponse.empty();
            return assembledResponse.get();
        } catch (CancellationException e) {
            if (control.isCancellationRequested() || shouldStop.getAsBoolean()) return LlmChatResponse.empty();
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmChatResponse.empty();
        } catch (ExecutionException e) {
            if (control.isCancellationRequested() || shouldStop.getAsBoolean()) return LlmChatResponse.empty();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Exception providerFailure = cause instanceof Exception exception
                    ? exception
                    : new RuntimeException(cause);
            throw completionFailure(providerFailure, control);
        } catch (Exception e) {
            if (control.isCancellationRequested() || shouldStop.getAsBoolean()) return LlmChatResponse.empty();
            if (e instanceof ServiceUnavailableException serviceFailure) throw serviceFailure;
            throw completionFailure(e, control);
        } finally {
            control.detach(cancellation);
            if (stream != null) stream.close();
            log.info("LLM stream finished: phase={}, messages={}, tools={}, inputChars={}, firstChunkMs={}, firstTextMs={}, elapsedMs={}, api={}, model={}",
                    includeTools ? "planning" : "reply", request.messages().size(), includeTools ? request.tools().size() : 0,
                    request.messages().stream().mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum(),
                    firstChunkAt.get() < 0 ? -1 : (firstChunkAt.get() - startedAt) / 1_000_000,
                    firstTextAt.get() < 0 ? -1 : (firstTextAt.get() - startedAt) / 1_000_000,
                    (System.nanoTime() - startedAt) / 1_000_000, api,
                    request.model() == null || request.model().isBlank() ? config.getModel() : request.model());
        }
    }

    @Override
    public void streamChat(LlmChatRequest request, Consumer<String> onDelta, BooleanSupplier shouldStop) {
        streamChat(request, onDelta, shouldStop, new LlmRequestControl());
    }

    @Override
    public void streamChat(LlmChatRequest request,
                           Consumer<String> onDelta,
                           BooleanSupplier shouldStop,
                           LlmRequestControl control) {
        completeStreaming(request, control, onDelta, shouldStop, false);
    }

    // ── domain → SDK ─────────────────────────────────────────────────────

    private ChatCompletionCreateParams toParams(LlmChatRequest request, boolean includeTools) {
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model() : config.getModel();

        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model);
        String effort = effectiveReasoningEffort(request, model);
        if (includeTools && request.hasTools() && LlmConfig.requiresResponsesForToolReasoning(model)
                && !"none".equals(effort)) {
            throw new ServiceUnavailableException("This model requires the Responses API for reasoning with tools. "
                    + "Select llm.api=responses or use a service that supports it.");
        }
        if (effort != null) builder.reasoningEffort(ReasoningEffort.of(effort));

        for (LlmMessage msg : request.messages()) {
            builder.addMessage(toMessageParam(msg));
        }

        if (includeTools && request.hasTools()) {
            for (LlmToolSpec spec : request.tools()) {
                builder.addTool(toFunctionTool(spec));
            }
        }
        if (request.temperature() != null && (effort == null || "none".equals(effort))) {
            builder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            builder.maxCompletionTokens(request.maxTokens().longValue());
        }
        return builder.build();
    }

    private String effectiveReasoningEffort(LlmChatRequest request, String model) {
        String effort = request.reasoningEffort() == null ? config.getReasoningEffort() : request.reasoningEffort();
        if (effort != null && !effort.isBlank()) return effort.trim();
        return LlmConfig.requiresResponsesForToolReasoning(model) ? "medium" : null;
    }

    private ChatCompletionMessageParam toMessageParam(LlmMessage msg) {
        return switch (msg.role()) {
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder().content(msg.content()).build());
            case USER -> ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder().content(msg.content()).build());
            case TOOL -> ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                            .toolCallId(msg.toolCallId() == null ? "" : msg.toolCallId())
                            .content(msg.content())
                            .build());
            case ASSISTANT -> ChatCompletionMessageParam.ofAssistant(toAssistantParam(msg));
        };
    }

    private ChatCompletionAssistantMessageParam toAssistantParam(LlmMessage msg) {
        ChatCompletionAssistantMessageParam.Builder builder = ChatCompletionAssistantMessageParam.builder();
        if (msg.hasToolCalls()) {
            for (LlmToolCall call : msg.toolCalls()) {
                builder.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                        .id(call.id())
                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name(call.name())
                                .arguments(call.argumentsJson())
                                .build())
                        .build());
            }
            // OpenAI requires assistant tool-call messages to omit/empty content.
        } else {
            builder.content(msg.content());
        }
        return builder.build();
    }

    private ChatCompletionFunctionTool toFunctionTool(LlmToolSpec spec) {
        FunctionParameters.Builder parametersBuilder = FunctionParameters.builder();
        toJsonSchemaObject(spec.parameters()).forEach((key, value) ->
                parametersBuilder.putAdditionalProperty(key, JsonValue.from(value)));
        FunctionParameters parameters = parametersBuilder.build();

        FunctionDefinition definition = FunctionDefinition.builder()
                .name(spec.name())
                .description(spec.description() == null ? "" : spec.description())
                .parameters(parameters)
                .build();

        return ChatCompletionFunctionTool.builder().function(definition).build();
    }

    static Map<String, Object> toJsonSchemaObject(FunctionParameterSchema schema) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", schema.type);
        json.put("properties", normalizeJsonSchemaValue(schema.properties));
        json.put("required", normalizeJsonSchemaValue(schema.required));
        json.put("additionalProperties", schema.additionalProperties);
        return json;
    }

    private static Object normalizeJsonSchemaValue(Object value) {
        if (value instanceof FunctionParameterSchema nestedSchema) {
            return toJsonSchemaObject(nestedSchema);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> normalized.put(
                    String.valueOf(key), normalizeJsonSchemaValue(nestedValue)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(OpenAiLlmProvider::normalizeJsonSchemaValue).toList();
        }
        return value;
    }

    // ── SDK → domain ─────────────────────────────────────────────────────

    private LlmChatResponse parseResponse(ChatCompletion completion) {
        if (completion.choices().isEmpty()) {
            return LlmChatResponse.empty();
        }
        ChatCompletionMessage message = completion.choices().get(0).message();

        List<LlmToolCall> toolCalls = message.toolCalls()
                .map(this::toDomainToolCalls)
                .orElseGet(List::of);
        String reasoningSummary = safeReasoningSummary(message);
        if (!toolCalls.isEmpty()) {
            return new LlmChatResponse(
                    message.content().orElse(""), toolCalls, reasoningSummary);
        }
        return new LlmChatResponse(message.content().orElse(""), List.of(), reasoningSummary);
    }

    private String safeReasoningSummary(ChatCompletionMessage message) {
        Map<String, JsonValue> additional = message._additionalProperties();
        for (String field : SAFE_REASONING_SUMMARY_FIELDS) {
            JsonValue value = additional.get(field);
            if (value == null) continue;
            try {
                com.fasterxml.jackson.databind.JsonNode node = value.convert(
                        com.fasterxml.jackson.databind.JsonNode.class);
                String summary = summaryText(node);
                if (summary != null) return summary;
            } catch (RuntimeException e) {
                log.debug("Ignoring unreadable safe reasoning summary field {}", field);
            }
        }
        // Deliberately ignore reasoning_content and analysis: compatible endpoints commonly
        // use those names for private chain-of-thought rather than a user-safe summary.
        return "";
    }

    private String summaryText(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return null;
        String value = null;
        if (node.isTextual()) {
            value = node.asText();
        } else if (node.isObject()) {
            for (String key : List.of("summary", "text", "content")) {
                if (node.path(key).isTextual()) {
                    value = node.path(key).asText();
                    break;
                }
            }
        }
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= MAX_REASONING_SUMMARY_CHARS
                ? normalized
                : normalized.substring(0, MAX_REASONING_SUMMARY_CHARS);
    }

    private List<LlmToolCall> toDomainToolCalls(List<ChatCompletionMessageToolCall> sdkCalls) {
        List<LlmToolCall> result = new ArrayList<>();
        for (ChatCompletionMessageToolCall call : sdkCalls) {
            if (!call.isFunction()) {
                continue; // custom tools are not used by this application
            }
            ChatCompletionMessageFunctionToolCall fn = call.asFunction();
            result.add(new LlmToolCall(fn.id(), fn.function().name(), fn.function().arguments()));
        }
        return result;
    }

    private ServiceUnavailableException completionFailure(Exception failure, LlmRequestControl control) {
        if (failure instanceof ServiceUnavailableException serviceFailure) return serviceFailure;
        logProviderError("OpenAI chat completion failed", failure);
        if (failure instanceof OpenAIServiceException serviceFailure
                && List.of(502, 503, 504).contains(serviceFailure.statusCode())) {
            // The chat owns one evidence-preserving continuation; SDK retries stay disabled.
            return new LlmStreamInterruptedException(failure);
        }
        if (failure instanceof SseException sse) {
            // Relays can report an upstream interruption inside an HTTP-200 SSE response.
            boolean interrupted = sse.statusCode() == 200 && (sse.type().filter(type ->
                    List.of("server_error", "upstream_error").contains(type)).isPresent()
                    || sse.code().filter(code -> List.of("stream_error", "stream_interrupted").contains(code)).isPresent()
                    || "200: Upstream response stream was interrupted".equals(sse.getMessage()));
            return interrupted ? new LlmStreamInterruptedException(failure)
                    : ServiceUnavailableException.aiService(failure);
        }
        if (!(failure instanceof OpenAIServiceException) && !(failure instanceof OpenAIInvalidDataException)) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) break;
                if (cause instanceof java.io.IOException) {
                    // A reset is specific to HTTP/2; only this turn's existing continuation changes protocol.
                    for (Throwable transportFailure = cause; transportFailure != null; transportFailure = transportFailure.getCause()) {
                        if (transportFailure instanceof okhttp3.internal.http2.StreamResetException) {
                            control.requestAlternativeTransport();
                            break;
                        }
                    }
                    return new LlmStreamInterruptedException(failure);
                }
            }
        }
        return ServiceUnavailableException.aiService(failure);
    }

    private void logProviderError(String message, Exception e) {
        List<String> failureTypes = new ArrayList<>();
        for (Throwable cause = e; cause != null && failureTypes.size() < 8; cause = cause.getCause()) {
            failureTypes.add(cause.getClass().getName());
        }
        // SDK messages can contain request/response secrets; class names retain transport diagnostics.
        String causes = String.join(" -> ", failureTypes);
        if (e instanceof OpenAIServiceException serviceException) {
            // Typed SDK accessors require fields that compatible error responses can omit.
            Map<?, ?> errorFields = (Map<?, ?>) serviceException.body().asObject().orElse(Map.of());
            String reason = List.of("type", "code").stream().map(errorFields::get)
                    .anyMatch(value -> value instanceof JsonValue json
                            && json.asString().filter("model_not_found"::equals).isPresent())
                    ? "MODEL_NOT_FOUND" : "UNCLASSIFIED";
            log.error("{}: status={}, reason={}, causes={}",
                    message,
                    serviceException.statusCode(),
                    reason,
                    causes);
            return;
        }
        log.error("{}: causes={}", message, causes);
    }
}
