package cn.edu.nju.Iot_Verify.component.ai.provider;

import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatRequest;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.configure.LlmConfig;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.openai.client.OpenAIClient;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class OpenAiLlmProviderPlanningStreamTest {

    @Test
    void toolAwareReply_deliversPublicTextWhileTheProviderStreamIsStillOpen() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch textReceived = new CountDownLatch(1);
        List<String> visible = new CopyOnWriteArrayList<>();
        String frame = chunk("{\"content\":\"Current evidence.\",\"reasoning_content\":\"private\"}", null);
        try (Gateway gateway = new Gateway(frame, release)) {
            LlmRequestControl control = new LlmRequestControl();
            CompletableFuture<LlmChatResponse> pending = CompletableFuture.supplyAsync(() ->
                    gateway.provider.chat(request(), control, delta -> {
                        visible.add(delta);
                        textReceived.countDown();
                    }));
            try {
                assertTrue(textReceived.await(10, TimeUnit.SECONDS));
                assertEquals(List.of("Current evidence."), visible);
                assertTrue(!pending.isDone(), "Text must arrive before the completed response");
                control.cancel();
                assertTrue(pending.get(10, TimeUnit.SECONDS).toolCalls().isEmpty());
            } finally {
                control.cancel();
                release.countDown();
            }
        }
    }

    @Test
    void planning_classifiesAnUpstreamSseInterruptionWithoutReturningUnfinishedTools() throws Exception {
        String frames = chunk("{\"content\":\"Partial answer.\",\"tool_calls\":[{\"index\":0,\"id\":\"unfinished\","
                + "\"type\":\"function\",\"function\":{\"name\":\"board_overview\",\"arguments\":\"{\"}}]}", null)
                + "data: {\"error\":{\"message\":\"Upstream response stream was interrupted\","
                + "\"type\":\"server_error\",\"code\":\"stream_error\"}}\n\n";
        try (Gateway gateway = new Gateway(frames)) {
            List<String> visible = new ArrayList<>();
            assertThrows(LlmStreamInterruptedException.class,
                    () -> gateway.provider.chat(request(), new LlmRequestControl(), visible::add));
            assertEquals(List.of("Partial answer."), visible);
            assertEquals(1, gateway.requests.size());
        }
    }

    @Test
    void planning_doesNotClassifyQuotaErrorsAsRecoverableStreamInterruptions() throws Exception {
        try (Gateway gateway = new Gateway("data: {\"error\":{\"message\":\"Quota exhausted\","
                + "\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}\n\n")) {
            ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.chat(request(), new LlmRequestControl()));
            assertTrue(!(failure instanceof LlmStreamInterruptedException));
        }
    }

    @Test
    void planning_usesSseAndAssemblesInterleavedToolArgumentsBeforeReturning() throws Exception {
        String frames = chunk("""
                {"content":"读取","reasoning_summary":"检查当前画布。","reasoning_content":"private reasoning",
                 "tool_calls":[{"index":1,"id":"second","type":"function","function":{"name":"list_rules","arguments":"{"}}]}
                """, null)
                + chunk("""
                {"content":"画布","tool_calls":[{"index":0,"id":"first","type":"function","function":{"name":"board_overview","arguments":"{"}}]}
                """, null)
                + chunk("""
                {"tool_calls":[{"index":0,"function":{"arguments":"}"}},{"index":1,"function":{"arguments":"}"}}]}
                """, null)
                + chunk("{}", "tool_calls") + "data: [DONE]\n\n";
        try (Gateway gateway = new Gateway(frames)) {
            var result = gateway.provider.chat(request(), new LlmRequestControl());

            assertEquals("读取画布", result.text());
            assertEquals("检查当前画布。", result.reasoningSummary());
            assertEquals(List.of("first", "second"), result.toolCalls().stream().map(call -> call.id()).toList());
            assertEquals(List.of("board_overview", "list_rules"), result.toolCalls().stream().map(call -> call.name()).toList());
            assertTrue(result.toolCalls().stream().allMatch(call -> "{}".equals(call.argumentsJson())));
            assertEquals(1, gateway.requests.size());
            assertTrue(gateway.requests.get(0).path("stream").asBoolean());
            assertEquals("board_overview", gateway.requests.get(0).path("tools").get(0).path("function").path("name").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter"})
    void planning_rejectsAnAbnormallyFinishedResponse(String reason) throws Exception {
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"partial\"}", reason) + "data: [DONE]\n\n")) {
            ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.chat(request(), new LlmRequestControl()));
            assertTrue(!(failure instanceof LlmStreamInterruptedException));
        }
    }

    @Test
    void planning_rejectsEofWithoutAFinishedChoice() throws Exception {
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"partial\"}", null) + "data: [DONE]\n\n")) {
            ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.chat(request(), new LlmRequestControl()));
            assertTrue(!(failure instanceof LlmStreamInterruptedException));
        }
    }

    @Test
    void planning_stopClosesAnOpenStreamWithoutReturningPartialTools() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"still planning\"}", null), release)) {
            LlmRequestControl control = new LlmRequestControl();
            CompletableFuture<LlmChatResponse> pending =
                    CompletableFuture.supplyAsync(() -> gateway.provider.chat(request(), control));
            try {
                assertTrue(gateway.responseStarted.await(10, TimeUnit.SECONDS));
                control.cancel();
                var result = pending.get(10, TimeUnit.SECONDS);
                assertTrue(result.text().isEmpty());
                assertTrue(result.toolCalls().isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void reply_streamsTextAndRequiresANormalFinish() throws Exception {
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"Verified answer.\"}", null)
                + chunk("{}", "stop") + "data: [DONE]\n\n")) {
            StringBuilder visible = new StringBuilder();
            gateway.provider.streamChat(request(), visible::append, () -> false);

            assertEquals("Verified answer.", visible.toString());
            assertTrue(gateway.requests.get(0).path("stream").asBoolean());
            assertTrue(gateway.requests.get(0).path("tools").isMissingNode());
        }
    }

    @Test
    void inferenceRounds_reuseAHealthyConnectionWithoutRepeatingRequests() throws Exception {
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"Done.\"}", "stop")
                + "data: [DONE]\n\n")) {
            gateway.provider.chat(request());
            gateway.provider.chat(request());
            gateway.provider.streamChat(request(), ignored -> { }, () -> false);

            assertEquals(3, gateway.clientPorts.size());
            assertEquals(3, gateway.requests.size());
            assertEquals(1, gateway.clientPorts.stream().distinct().count());
        }
    }

    @Test
    void inferenceAfterCancellationUsesAFreshConnectionWithoutConsumingTheUnfinishedResponse() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch textReceived = new CountDownLatch(1);
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"Fresh reply.\"}", null), release)) {
            LlmRequestControl control = new LlmRequestControl();
            CompletableFuture<LlmChatResponse> pending = CompletableFuture.supplyAsync(() ->
                    gateway.provider.chat(request(), control, ignored -> textReceived.countDown()));
            try {
                assertTrue(gateway.responseStarted.await(10, TimeUnit.SECONDS));
                assertTrue(textReceived.await(10, TimeUnit.SECONDS));
                assertTimeout(Duration.ofSeconds(5), control::cancel);
                assertEquals("", assertDoesNotThrow(() -> pending.get(10, TimeUnit.SECONDS)).text());
                release.countDown();
                assertEquals("Fresh reply.", gateway.provider.chat(request(), new LlmRequestControl()).text());
                assertEquals(2, gateway.requests.size());
                assertEquals(2, gateway.clientPorts.stream().distinct().count());
            } finally {
                control.cancel();
                release.countDown();
            }
        }
    }

    @Test
    void requestClientCleanupKeepsTheSharedTransportAliveUntilProviderShutdown() throws Exception {
        Gateway gateway = new Gateway(chunk("{\"content\":\"Done.\"}", "stop") + "data: [DONE]\n\n");
        okhttp3.OkHttpClient transport = (okhttp3.OkHttpClient) ReflectionTestUtils.getField(gateway.provider, "transport");
        try (gateway) {
            List<HttpClient> requestClients = new ArrayList<>();
            OpenAIClient original = (OpenAIClient) ReflectionTestUtils.getField(gateway.provider, "client");
            OpenAIClient observed = spy(original);
            doAnswer(invocation -> {
                Consumer<ClientOptions.Builder> modifier = invocation.getArgument(0);
                return original.withOptions(options -> {
                    modifier.accept(options);
                    requestClients.add(options.build().httpClient());
                });
            }).when(observed).withOptions(any());
            ReflectionTestUtils.setField(gateway.provider, "client", observed);

            gateway.provider.chat(request());
            assertEquals(1, requestClients.size());
            // The SDK's phantom cleanup closes precisely this derived HTTP client.
            requestClients.get(0).close();
            assertFalse(transport.dispatcher().executorService().isShutdown());
            assertDoesNotThrow(() -> gateway.provider.chat(request()));
            assertEquals(2, gateway.requests.size());
            assertEquals(1, gateway.clientPorts.stream().distinct().count());
        }
        assertTrue(transport.dispatcher().executorService().isShutdown());
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "tool_calls"})
    void reply_rejectsAnAbnormalFinishWithoutErasingReceivedText(String reason) throws Exception {
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"Partial answer.\"}", reason)
                + "data: [DONE]\n\n")) {
            StringBuilder visible = new StringBuilder();
            assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.streamChat(request(), visible::append, () -> false));
            assertEquals("Partial answer.", visible.toString());
        }
    }

    @Test
    void reply_rejectsEofWithoutAFinishReason() throws Exception {
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"Partial answer.\"}", null)
                + "data: [DONE]\n\n")) {
            assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.streamChat(request(), ignored -> { }, () -> false));
        }
    }

    @Test
    void reply_cancellationClosesTheRequestBeforeResponseHeadersArrive() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"Done.\"}", "stop")
                + "data: [DONE]\n\n", release, true, 200)) {
            LlmRequestControl control = new LlmRequestControl();
            CompletableFuture<Void> pending = CompletableFuture.runAsync(() ->
                    gateway.provider.streamChat(request(), ignored -> { }, () -> false, control));
            try {
                assertTrue(gateway.requestReceived.await(10, TimeUnit.SECONDS));
                control.cancel();
                assertDoesNotThrow(() -> pending.get(5, TimeUnit.SECONDS),
                        "Stopping must not wait for response headers");
                assertEquals(1, gateway.requests.size());
            } finally {
                control.cancel();
                release.countDown();
                pending.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void transientStatusDoesNotMultiplyTheChatOwnedContinuationWithSdkRetries(int status) throws Exception {
        try (Gateway gateway = new Gateway("{\"error\":{\"message\":\"Temporary failure\"}}",
                null, false, status)) {
            LlmRequestControl control = new LlmRequestControl();
            assertThrows(LlmStreamInterruptedException.class,
                    () -> gateway.provider.chat(request(), control));
            assertFalse(control.isAlternativeTransportRequested(), "A gateway status does not imply an HTTP/2 reset");
            assertEquals(1, gateway.requests.size(), "Only the chat workflow may decide to continue");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 501})
    void rejectedOrUnclassifiedStatusDoesNotRequestContinuation(int status) throws Exception {
        try (Gateway gateway = new Gateway("{\"error\":{\"message\":\"Request rejected\"}}",
                null, false, status)) {
            ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class,
                    () -> gateway.provider.chat(request()));
            assertFalse(failure instanceof LlmStreamInterruptedException);
            assertEquals(1, gateway.requests.size());
        }
    }

    @Test
    void connectionSetupBudgetDoesNotLimitAnEstablishedModelStream() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstText = new CountDownLatch(1);
        List<String> visible = new CopyOnWriteArrayList<>();
        try (Gateway gateway = new Gateway(chunk("{\"content\":\"Still analysing.\"}", null), release)) {
            LlmRequestControl control = new LlmRequestControl();
            CompletableFuture<LlmChatResponse> pending = CompletableFuture.supplyAsync(() ->
                    gateway.provider.chat(request(), control, delta -> {
                        visible.add(delta);
                        firstText.countDown();
                    }));
            try {
                assertTrue(firstText.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.get(11, TimeUnit.SECONDS),
                        "A connected model retains the configured generation deadline");
                release.countDown();
                assertEquals("Still analysing.", pending.get(10, TimeUnit.SECONDS).text());
                assertEquals(List.of("Still analysing."), visible);
            } finally {
                control.cancel();
                release.countDown();
            }
        }
    }

    @Test
    void stalledTlsHandshakeEndsWithinTheConnectionSetupBudget() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Void> stalledTls = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    accepted.countDown();
                    release.await(30, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            });
            LlmConfig config = new LlmConfig();
            config.setApiKey("test-key");
            config.setModel("test");
            config.setBaseUrl("https://127.0.0.1:" + server.getLocalPort() + "/v1");
            OpenAiLlmProvider provider = new OpenAiLlmProvider(config);
            provider.init();
            LlmRequestControl control = new LlmRequestControl();
            CompletableFuture<LlmChatResponse> pending = CompletableFuture.supplyAsync(() ->
                    provider.chat(request(), control));
            try {
                assertTrue(accepted.await(10, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> pending.get(15, TimeUnit.SECONDS), "TLS setup must not inherit the model's long read timeout");
                assertTrue(failure.getCause() instanceof LlmStreamInterruptedException);
            } finally {
                control.cancel();
                release.countDown();
                pending.handle((result, failure) -> null).get(10, TimeUnit.SECONDS);
                stalledTls.get(10, TimeUnit.SECONDS);
                provider.destroy();
            }
        }
    }

    private static LlmChatRequest request() {
        return LlmChatRequest.builder().messages(List.of(LlmMessage.user("inspect the board")))
                .tools(List.of(LlmToolSpec.of("board_overview", "Read the board",
                        new FunctionParameterSchema("object", Map.of(), List.of())))).build();
    }

    private static String chunk(String delta, String finishReason) {
        return "data: {\"id\":\"plan\",\"created\":0,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":"
                + delta.replace("\n", "") + ",\"finish_reason\":"
                + (finishReason == null ? "null" : "\"" + finishReason + "\"") + "}]}\n\n";
    }

    static final class Gateway implements AutoCloseable {
        private final HttpServer server;
        final OpenAiLlmProvider provider;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<Integer> clientPorts = new CopyOnWriteArrayList<>();
        final CountDownLatch responseStarted = new CountDownLatch(1);
        final CountDownLatch requestReceived = new CountDownLatch(1);

        Gateway(String frames) throws Exception {
            this(frames, null);
        }

        Gateway(String frames, CountDownLatch release) throws Exception {
            this(frames, release, false, 200);
        }

        Gateway(String frames, CountDownLatch release, boolean waitBeforeHeaders, int status) throws Exception {
            this(request -> frames, release, waitBeforeHeaders, status);
        }

        Gateway(Function<JsonNode, String> frames, CountDownLatch release, boolean waitBeforeHeaders, int status) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/", exchange -> {
                paths.add(exchange.getRequestURI().getPath());
                JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody());
                requests.add(request);
                clientPorts.add(exchange.getRemoteAddress().getPort());
                requestReceived.countDown();
                if (waitBeforeHeaders) await(release);
                byte[] bytes = frames.apply(request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(request.path("stream").asBoolean() ? status : 400,
                        release == null || waitBeforeHeaders ? bytes.length : 0);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                    output.flush();
                    responseStarted.countDown();
                    if (release != null && !waitBeforeHeaders) {
                        await(release);
                        output.write((chunk("{}", "stop") + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
            });
            LlmConfig config = new LlmConfig();
            config.setApiKey("test-key");
            config.setModel("test");
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            provider = new OpenAiLlmProvider(config);
            server.start();
            provider.init();
        }

        private static void await(CountDownLatch release) throws java.io.IOException {
            try {
                if (!release.await(30, TimeUnit.SECONDS)) throw new java.io.IOException("Test gateway was not released");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(failure);
            }
        }

        @Override
        public void close() {
            provider.destroy();
            server.stop(0);
        }
    }
}
