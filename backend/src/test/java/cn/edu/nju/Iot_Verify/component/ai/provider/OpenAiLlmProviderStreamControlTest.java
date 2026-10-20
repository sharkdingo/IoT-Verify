package cn.edu.nju.Iot_Verify.component.ai.provider;

import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.configure.LlmConfig;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.testutil.LogCapture;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link OpenAiLlmProvider#streamChat} honors the cooperative stop signal and
 * stops consuming further chunks — the behavior previously covered by the ArkAiClient stream
 * test, now against the OpenAI SDK adapter.
 */
class OpenAiLlmProviderStreamControlTest {

    /**
     * Bound for latches that wait for an async task to REACH a point, not for a behavioural
     * deadline. The old 1-2s bounds failed under full-suite CPU contention (the common
     * ForkJoinPool is shared), reporting a timing artifact as a cancellation defect.
     */
    private static final long COORDINATION_TIMEOUT_SECONDS = 10;

    @Test
    void chat_failedPlanningRequest_logsCauseTypesWithoutUpstreamMessages() {
        String sensitive = "private-upstream-response-never-log";
        RuntimeException failure = new RuntimeException(sensitive, new IOException(sensitive));
        OpenAIClient client = mock(OpenAIClient.class);
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        ChatServiceAsync chatService = mock(ChatServiceAsync.class);
        ChatCompletionServiceAsync completions = mock(ChatCompletionServiceAsync.class);
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completions);
        AsyncStreamResponse<ChatCompletionChunk> response = mock(AsyncStreamResponse.class);
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(response);
        when(response.onCompleteFuture()).thenReturn(CompletableFuture.failedFuture(failure));

        try (LogCapture logs = LogCapture.forClass(OpenAiLlmProvider.class)) {
            ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                    () -> provider(client).chat(request(), new LlmRequestControl()));
            org.junit.jupiter.api.Assertions.assertSame(failure, error.getCause());
            org.junit.jupiter.api.Assertions.assertTrue(logs.messages().stream().anyMatch(message ->
                    message.contains("java.lang.RuntimeException -> java.io.IOException")));
            assertFalse(logs.messages().stream().anyMatch(message -> message.contains(sensitive)));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamChat_withStopSignal_shouldStopConsumingFurtherChunks() {
        AsyncStreamResponse<ChatCompletionChunk> response = mock(AsyncStreamResponse.class);
        when(response.onCompleteFuture()).thenReturn(CompletableFuture.completedFuture(null));
        doAnswer(invocation -> {
            AsyncStreamResponse.Handler<ChatCompletionChunk> handler = invocation.getArgument(0);
            List.of(chunk("A"), chunk("B"), chunk("C")).forEach(handler::onNext);
            return response;
        }).when(response).subscribe(any(AsyncStreamResponse.Handler.class));
        List<String> outputs = new ArrayList<>();
        provider(asyncClient(response)).streamChat(request(), outputs::add, () -> !outputs.isEmpty());
        assertEquals(List.of("A"), outputs);
        verify(response, atLeastOnce()).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamChat_whenSdkParsingFails_shouldPropagateServiceUnavailable() {
        AsyncStreamResponse<ChatCompletionChunk> response = mock(AsyncStreamResponse.class);
        when(response.onCompleteFuture()).thenReturn(CompletableFuture.failedFuture(
                new OpenAIInvalidDataException("Invalid UTF-8 middle byte 0xe3")));
        assertThrows(ServiceUnavailableException.class,
                () -> provider(asyncClient(response)).streamChat(request(), ignored -> { }, () -> false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamChat_cancelledFailure_doesNotLogUpstreamExceptionMessage() {
        String sensitive = "private-upstream-response-never-log";
        AsyncStreamResponse<ChatCompletionChunk> response = mock(AsyncStreamResponse.class);
        LlmRequestControl control = new LlmRequestControl();
        CompletableFuture<Void> failure = new CompletableFuture<>();
        when(response.onCompleteFuture()).thenReturn(failure);
        doAnswer(ignored -> {
            control.cancel();
            failure.completeExceptionally(new OpenAIInvalidDataException(sensitive));
            return response;
        }).when(response).subscribe(any(AsyncStreamResponse.Handler.class));
        try (LogCapture logs = LogCapture.forClass(OpenAiLlmProvider.class)) {
            provider(asyncClient(response)).streamChat(request(), ignored -> { }, () -> false, control);
            assertFalse(logs.messages().stream().anyMatch(message -> message.contains(sensitive)));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamChat_explicitCancellation_shouldCloseBlockedUpstreamStream() throws Exception {
        AsyncStreamResponse<ChatCompletionChunk> response = mock(AsyncStreamResponse.class);
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Void> pending = new CompletableFuture<>();
        when(response.onCompleteFuture()).thenAnswer(ignored -> {
            waiting.countDown();
            return pending;
        });
        doAnswer(ignored -> {
            pending.complete(null);
            return null;
        }).when(response).close();

        OpenAiLlmProvider provider = provider(asyncClient(response));
        LlmRequestControl control = new LlmRequestControl();
        LlmChatRequest request = request();
        CompletableFuture<Void> call = CompletableFuture.runAsync(() ->
                provider.streamChat(request, ignored -> { }, () -> false, control));

        try {
            org.junit.jupiter.api.Assertions.assertTrue(waiting.await(COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            control.cancel();
            call.get(COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            verify(response, atLeastOnce()).close();
        } finally {
            control.cancel();
            pending.complete(null);
        }
    }

    @Test
    void chat_explicitCancellation_shouldCancelPendingPlanningRequest() throws Exception {
        OpenAIClient client = mock(OpenAIClient.class);
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        ChatServiceAsync chatService = mock(ChatServiceAsync.class);
        ChatCompletionServiceAsync completions = mock(ChatCompletionServiceAsync.class);
        CompletableFuture<Void> pending = new CompletableFuture<>();
        AsyncStreamResponse<ChatCompletionChunk> response = mock(AsyncStreamResponse.class);
        CountDownLatch requestStarted = new CountDownLatch(1);

        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completions);
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenAnswer(ignored -> {
            requestStarted.countDown();
            return response;
        });
        when(response.onCompleteFuture()).thenReturn(pending);
        doAnswer(ignored -> {
            pending.complete(null);
            return null;
        }).when(response).close();

        OpenAiLlmProvider provider = provider(client);
        LlmRequestControl control = new LlmRequestControl();
        CompletableFuture<LlmChatResponse> call = CompletableFuture.supplyAsync(() ->
                provider.chat(request(), control));

        org.junit.jupiter.api.Assertions.assertTrue(requestStarted.await(COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        control.cancel();
        LlmChatResponse result = call.get(COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        verify(response, atLeastOnce()).close();
        org.junit.jupiter.api.Assertions.assertFalse(result.hasToolCalls());
        assertEquals("", result.text());
    }

    @Test
    @SuppressWarnings("unchecked")
    void http2ResetUsesTheAlternativeOnlyForTheSameTurnContinuation() {
        AsyncStreamResponse<ChatCompletionChunk> failed = mock(AsyncStreamResponse.class);
        when(failed.onCompleteFuture()).thenReturn(CompletableFuture.failedFuture(new RuntimeException(
                new IOException(new okhttp3.internal.http2.StreamResetException(okhttp3.internal.http2.ErrorCode.CANCEL)))));
        OpenAIClient primary = asyncClient(failed);
        AsyncStreamResponse<ChatCompletionChunk> completed = mock(AsyncStreamResponse.class);
        doAnswer(invocation -> {
            AsyncStreamResponse.Handler<ChatCompletionChunk> handler = invocation.getArgument(0);
            handler.onNext(ChatCompletionChunk.builder().id("finished").created(0L).model("mock-model")
                    .addChoice(ChatCompletionChunk.Choice.builder().index(0L)
                            .delta(ChatCompletionChunk.Choice.Delta.builder().content("Complete answer.").build())
                            .finishReason(ChatCompletionChunk.Choice.FinishReason.STOP).build()).build());
            return completed;
        }).when(completed).subscribe(any(AsyncStreamResponse.Handler.class));
        when(completed.onCompleteFuture()).thenReturn(CompletableFuture.completedFuture(null));
        OpenAIClient alternative = asyncClient(completed);
        OpenAiLlmProvider provider = provider(primary);
        ReflectionTestUtils.setField(provider, "alternativeClient", alternative);
        LlmRequestControl control = new LlmRequestControl();

        assertThrows(cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException.class,
                () -> provider.chat(request(), control));
        org.junit.jupiter.api.Assertions.assertTrue(control.isAlternativeTransportRequested());
        assertFalse(control.isCancellationRequested());
        assertEquals("Complete answer.", provider.chat(request(), control).text());
        LlmRequestControl nextTurn = new LlmRequestControl();
        assertFalse(nextTurn.isAlternativeTransportRequested());
        assertThrows(cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException.class,
                () -> provider.chat(request(), nextTurn));
        verify(primary, org.mockito.Mockito.times(2)).async();
        verify(alternative).async();
    }

    @Test
    void ordinaryIoFailuresDoNotSilentlyChangeProtocol() {
        AsyncStreamResponse<ChatCompletionChunk> failed = mock(AsyncStreamResponse.class);
        when(failed.onCompleteFuture()).thenReturn(CompletableFuture.failedFuture(new IOException("network failure")));
        LlmRequestControl control = new LlmRequestControl();
        assertThrows(cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException.class,
                () -> provider(asyncClient(failed)).chat(request(), control));
        assertFalse(control.isAlternativeTransportRequested());
    }

    private static OpenAIClient asyncClient(AsyncStreamResponse<ChatCompletionChunk> response) {
        OpenAIClient client = mock(OpenAIClient.class);
        when(client.withOptions(any())).thenReturn(client);
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        ChatServiceAsync chatService = mock(ChatServiceAsync.class);
        ChatCompletionServiceAsync completions = mock(ChatCompletionServiceAsync.class);
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completions);
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(response);
        return client;
    }

    private static OpenAiLlmProvider provider(OpenAIClient client) {
        LlmConfig config = new LlmConfig();
        config.setModel("mock-model");
        OpenAiLlmProvider provider = new OpenAiLlmProvider(config);
        when(client.withOptions(any())).thenReturn(client);
        ReflectionTestUtils.setField(provider, "transport", new okhttp3.OkHttpClient());
        ReflectionTestUtils.setField(provider, "client", client);
        return provider;
    }

    private static LlmChatRequest request() {
        return LlmChatRequest.builder()
                .messages(List.of(LlmMessage.user("hello")))
                .build();
    }

    private static ChatCompletionChunk chunk(String text) {
        ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
                .delta(ChatCompletionChunk.Choice.Delta.builder().content(text).build())
                .finishReason(java.util.Optional.empty())
                .index(0L)
                .build();
        return ChatCompletionChunk.builder()
                .id("chunk_" + text)
                .addChoice(choice)
                .created(0L)
                .model("mock-model")
                .build();
    }
}
