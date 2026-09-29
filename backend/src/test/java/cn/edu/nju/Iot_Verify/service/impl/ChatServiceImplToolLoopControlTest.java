package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.ai.chat.ChatToolProgressPresenter;
import cn.edu.nju.Iot_Verify.component.ai.LlmChatService;
import cn.edu.nju.Iot_Verify.component.ai.LlmMessageCodec;
import cn.edu.nju.Iot_Verify.component.ai.AiTaskContinuationStore;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.ChatExecutionStatus;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolManager;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResultContract;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.scenario.AiScenarioDraftStore;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.chat.ChatConfirmationCommandDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatMessageResponseDto;
import cn.edu.nju.Iot_Verify.dto.chat.StreamResponseDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ChatSessionBusyException;
import cn.edu.nju.Iot_Verify.exception.ChatHistoryQuotaExceededException;
import cn.edu.nju.Iot_Verify.exception.ChatAdmissionOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.util.mapper.ChatMapper;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.lang.NonNull;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplToolLoopControlTest {

    @Mock
    private ChatSessionRepository sessionRepo;
    @Mock
    private ChatMessageRepository messageRepo;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LlmChatService llmChatService;
    @Mock
    private AiToolManager aiToolManager;
    @Mock
    private AiDestructiveActionGuard destructiveActionGuard;
    @Mock
    private AiScenarioDraftStore scenarioDraftStore;
    @Mock
    private AiTaskContinuationStore taskContinuationStore;
    @Mock
    private ChatMapper chatMapper;
    private LlmMessageCodec messageCodec;
    private TransactionTemplate transactionTemplate;
    private ChatExecutionConfig chatExecutionConfig;
    private final List<Integer> transactionPropagations = new ArrayList<>();

    private ChatServiceImpl service;
    private Method executeToolLoopMethod;
    private Method executeToolLoopWithTraceMethod;
    private Method jsonErrorMethod;
    private final ChatToolProgressPresenter toolProgressPresenter =
            new ChatToolProgressPresenter(new ObjectMapper());
    private Method classifyToolExecutionMethod;
    private Method attachPersistedExecutionTracesMethod;

    @BeforeEach
    void setUp() throws Exception {
        transactionPropagations.clear();
        transactionTemplate = transactionTemplateWithCommitHook(ignored -> { });

        messageCodec = new LlmMessageCodec(new ObjectMapper());
        chatExecutionConfig = new ChatExecutionConfig();
        lenient().when(sessionRepo.currentDatabaseTime()).thenAnswer(invocation -> LocalDateTime.now());
        lenient().when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(UserPo.builder().id(1L).build()));
        lenient().when(messageRepo.existsBySessionIdAndTurnId(anyString(), anyString()))
                .thenReturn(false);
        lenient().when(messageRepo.existsBySessionIdAndTurnIdAndExecutionIdAndRole(
                anyString(), anyString(), anyString(), eq("user"))).thenReturn(false);
        lenient().when(messageRepo.deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                anyString(), anyString(), anyString(), eq("user"))).thenReturn(1);
        ChatSessionPo defaultSession = new ChatSessionPo();
        defaultSession.setId("s1");
        defaultSession.setUserId(1L);
        lenient().when(sessionRepo.findByIdAndUserId("s1", 1L))
                .thenReturn(Optional.of(defaultSession));
        lenient().when(sessionRepo.findByIdAndUserIdForUpdate("s1", 1L))
                .thenReturn(Optional.of(defaultSession));
        lenient().when(sessionRepo.findByIdInForUpdate(any()))
                .thenAnswer(invocation -> {
                    Set<String> sessionIds = invocation.getArgument(0);
                    return sessionRepo.findByIdAndUserId("s1", 1L)
                            .filter(session -> sessionIds.contains(session.getId()))
                            .map(List::of)
                            .orElseGet(List::of);
                });
        lenient().when(sessionRepo.findByUserIdForUpdate(1L))
                .thenReturn(List.of(defaultSession));
        service = newService();
        executeToolLoopMethod = ChatServiceImpl.class.getDeclaredMethod(
                "executeToolLoop",
                Long.class,
                String.class,
                List.class,
                List.class,
                Set.class,
                SseEmitter.class,
                AtomicBoolean.class
        );
        executeToolLoopMethod.setAccessible(true);
        executeToolLoopWithTraceMethod = ChatServiceImpl.class.getDeclaredMethod(
                "executeToolLoop",
                Long.class,
                String.class,
                List.class,
                List.class,
                Set.class,
                SseEmitter.class,
                AtomicBoolean.class,
                boolean.class,
                List.class
        );
        executeToolLoopWithTraceMethod.setAccessible(true);

        jsonErrorMethod = ChatServiceImpl.class.getDeclaredMethod(
                "jsonError",
                String.class,
                String.class,
                int.class
        );
        jsonErrorMethod.setAccessible(true);
        classifyToolExecutionMethod = ChatServiceImpl.class.getDeclaredMethod(
                "classifyToolExecution", String.class, String.class);
        classifyToolExecutionMethod.setAccessible(true);
        attachPersistedExecutionTracesMethod = ChatServiceImpl.class.getDeclaredMethod(
                "attachPersistedExecutionTraces", List.class, List.class);
        attachPersistedExecutionTracesMethod.setAccessible(true);
    }

    private ChatServiceImpl newService() {
        return newService(new ObjectMapper());
    }

    private ChatServiceImpl newService(ObjectMapper objectMapper) {
        return new ChatServiceImpl(
                sessionRepo,
                messageRepo,
                userRepository,
                llmChatService,
                messageCodec,
                new ChatConfirmationDetector(
                        org.mockito.Mockito.mock(cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService.class),
                        new ObjectMapper()),
                aiToolManager,
                destructiveActionGuard,
                scenarioDraftStore,
                taskContinuationStore,
                objectMapper,
                new ChatToolProgressPresenter(objectMapper),
                chatMapper,
                transactionTemplate,
                chatExecutionConfig
        );
    }

    private TransactionTemplate transactionTemplateWithCommitHook(IntConsumer afterCommit) {
        AtomicInteger commitCount = new AtomicInteger();
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(@NonNull TransactionDefinition definition) {
                transactionPropagations.add(definition.getPropagationBehavior());
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(@NonNull TransactionStatus status) {
                afterCommit.accept(commitCount.incrementAndGet());
            }

            @Override
            public void rollback(@NonNull TransactionStatus status) {
            }
        });
    }

    private static void delayAtLeast(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test delay was interrupted", e);
        }
    }

    @Test
    void executeToolLoop_whenDisconnected_shouldSkipIntentCall() throws Exception {
        invokeToolLoop(new AtomicBoolean(true), new LinkedHashSet<>());

        verify(llmChatService, never()).chatWithTools(anyList(), anyList());
        verify(aiToolManager, never()).execute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void toolProgressDetail_shouldNotExposeRawInternalIdentifiersFromErrors() throws Exception {
        String detail = toolProgressPresenter.toolProgressDetail(
                "manage_rule",
                "{\"error\":\"Rule rule-17 for user id 42 was not found\",\"errorCode\":\"NOT_FOUND\"}",
                false);

        assertTrue(detail.contains("NOT_FOUND"));
        assertFalse(detail.contains("rule-17"));
        assertFalse(detail.contains("42"));
    }

    @Test
    void classifyToolExecution_rejectsEmptyAndMalformedControlFields() throws Exception {
        List<String> malformedResults = List.of(
                "{}",
                "{\"requiresUserConfirmation\":\"true\"}",
                "{\"resultAvailable\":\"false\",\"message\":\"missing\"}",
                "{\"mutationMayHaveCommitted\":1,\"message\":\"unknown\"}",
                "{\"status\":\"500\",\"message\":\"failed\"}",
                "{\"resultStatus\":\"SUCCESS\",\"message\":\"done\"}",
                "{\"objectiveStatus\":false,\"scene\":{}}",
                "{\"errorCode\":500,\"message\":\"failed\"}");

        for (String result : malformedResults) {
            Object outcome = classifyToolExecutionMethod.invoke(service, "list_rules", result);
            assertEquals("FAILED", outcome.toString(), result);
        }

        assertEquals("USABLE", classifyToolExecutionMethod.invoke(
                service, "list_rules", "{\"count\":0}").toString());
        assertEquals("RESULT_UNAVAILABLE", classifyToolExecutionMethod.invoke(
                service,
                "list_rules",
                "{\"resultAvailable\":false,\"resultStatus\":\"RESULT_UNAVAILABLE\","
                        + "\"errorCode\":\"TOOL_RESULT_TOO_LARGE\",\"message\":\"retry\"}").toString());
        assertEquals("FAILED", classifyToolExecutionMethod.invoke(
                service, "board_overview", "{\"devices\":[]}").toString());
        assertEquals("USABLE", classifyToolExecutionMethod.invoke(
                service, "board_overview",
                "{\"devices\":[],\"rules\":[],\"specs\":[],\"edges\":[],"
                        + "\"environmentVariables\":[]}").toString());
        assertEquals("FAILED", classifyToolExecutionMethod.invoke(
                service, "manage_rule", "{\"message\":\"Rule updated successfully.\"}").toString());
        assertEquals("USABLE", classifyToolExecutionMethod.invoke(
                service, "manage_rule", "{\"operation\":\"reordered\",\"ruleCount\":2}").toString());
        assertNull(toolProgressPresenter.toolProgressDetail(
                "board_overview", "{\"devices\":[]}", false));
    }

    @Test
    void toolProgressDetail_shouldSummarizeCommonMutationFromStructuredFields() throws Exception {
        String detail = toolProgressPresenter.toolProgressDetail(
                "add_device",
                "{\"operation\":\"created\",\"device\":{\"id\":\"device-17\",\"label\":\"Hall Light\",\"state\":\"off\"},\"environmentChanges\":[{}]}",
                false);

        assertTrue(detail.contains("Hall Light"));
        assertTrue(detail.contains("initial state off"));
        assertTrue(detail.contains("1 Environment Pool change"));
        assertFalse(detail.contains("device-17"));
    }

    @Test
    void executeToolLoop_shouldEmitSanitizedReactReasoningBeforeTheToolAction() throws Exception {
        LlmToolCall call = new LlmToolCall("tc_1", "board_overview", "{}");
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(LlmChatResponse.ofTextAndToolCalls(
                        "Inspect device_17 and impactToken=secret before choosing the next action.",
                        List.of(call)))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("board_overview", "{}"))
                .thenReturn("{\"devices\":[],\"rules\":[],\"specs\":[],\"edges\":[],\"environmentVariables\":[]}");
        List<StreamResponseDto.ProgressDto> trace = new ArrayList<>();

        executeToolLoopWithTraceMethod.invoke(
                service,
                1L,
                "s1",
                new ArrayList<LlmMessage>(),
                new ArrayList<LlmToolSpec>(),
                new LinkedHashSet<StreamResponseDto.CommandDto>(),
                mock(SseEmitter.class),
                new AtomicBoolean(false),
                false,
                trace);

        int reasoningIndex = java.util.stream.IntStream.range(0, trace.size())
                .filter(index -> "REASONING".equals(trace.get(index).getStage()))
                .findFirst()
                .orElseThrow();
        int executionIndex = java.util.stream.IntStream.range(0, trace.size())
                .filter(index -> "TOOL_EXECUTION".equals(trace.get(index).getStage()))
                .findFirst()
                .orElseThrow();
        assertTrue(reasoningIndex < executionIndex);
        assertTrue(trace.get(reasoningIndex).getDetail().contains("[internal reference]"));
        assertTrue(trace.get(reasoningIndex).getDetail().contains("impactToken=[hidden]"));
        assertFalse(trace.get(reasoningIndex).getDetail().contains("device_17"));
        assertFalse(trace.get(reasoningIndex).getDetail().contains("secret"));
    }

    @Test
    void reactReasoning_shouldKeepMoreContextThanCompactToolResults() throws Exception {
        // Long enough to exceed the 1600-char reasoning budget, so the cap and the boundary cut are
        // both exercised. The previous input was ~529 characters against a `<= 800` assertion, which
        // could not fail and did not notice the budget moving.
        String reasoning = "Goal and evidence. " + "remaining context ".repeat(120);
        LlmToolCall call = new LlmToolCall("tc_long", "board_overview", "{}");
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(LlmChatResponse.ofTextAndToolCalls(reasoning, List.of(call)))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("board_overview", "{}"))
                .thenReturn("{\"devices\":[],\"rules\":[],\"specs\":[],\"edges\":[],\"environmentVariables\":[]}");
        List<StreamResponseDto.ProgressDto> trace = new ArrayList<>();

        executeToolLoopWithTraceMethod.invoke(
                service,
                1L,
                "s1",
                new ArrayList<LlmMessage>(),
                new ArrayList<LlmToolSpec>(),
                new LinkedHashSet<StreamResponseDto.CommandDto>(),
                mock(SseEmitter.class),
                new AtomicBoolean(false),
                false,
                trace);

        String detail = trace.stream()
                .filter(progress -> "REASONING".equals(progress.getStage()))
                .findFirst()
                .orElseThrow()
                .getDetail();
        assertTrue(detail.length() > 240);
        // The reasoning budget is 1600, well above the tool-status budget it is contrasted with here.
        assertTrue(detail.length() <= 1600, "reasoning budget: " + detail.length());
        // Truncation cuts at a boundary and marks itself, rather than stopping mid-clause.
        assertTrue(detail.endsWith("…"), detail.substring(Math.max(0, detail.length() - 40)));
    }

    @Test
    void unreadableToolResult_isTreatedAsAMutationThatMayHaveCommitted() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "mutationMayHaveCommitted", String.class);
        method.setAccessible(true);

        // A tool that wrote to the database and then produced truncated or non-object output is an
        // UNKNOWN outcome. Reading it as "nothing was written" is the one answer that is unsafe: the
        // uncertain-mutation count would stay 0 and the notice injected into the next planning round
        // would tell the model those steps reported no write, inviting a retry against state nobody
        // inspected.
        assertEquals(true, method.invoke(service, "{\"applied\":true,\"rules\":[{\"id\":1"));
        assertEquals(true, method.invoke(service, "not json at all"));
        assertEquals(true, method.invoke(service, "[]"));
        assertEquals(true, method.invoke(service, "\"a bare string\""));

        // A well-formed result is still authoritative in both directions, and absence of the flag on
        // a readable object stays false — read-only tools must not be counted as uncertain writes.
        assertEquals(true, method.invoke(service, "{\"mutationMayHaveCommitted\":true}"));
        assertEquals(false, method.invoke(service, "{\"mutationMayHaveCommitted\":false}"));
        assertEquals(false, method.invoke(service, "{\"count\":0}"));
        // Non-boolean is not coerced: it is a malformed control field, not a claim of a write.
        assertEquals(false, method.invoke(service, "{\"mutationMayHaveCommitted\":1}"));

        // Nothing was ever sent, so there is nothing that could have committed.
        assertEquals(false, method.invoke(service, ""));
        assertEquals(false, method.invoke(service, (Object) null));
    }

    @Test
    void taskContinuation_shouldRemoveNestedConfirmationTokens() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "sanitizePendingResultForContinuation", String.class);
        method.setAccessible(true);

        String sanitized = (String) method.invoke(service, """
                {
                  "requiresUserConfirmation": true,
                  "preview": {
                    "impactToken": "nested-secret",
                    "items": [{"confirmationToken": "array-secret", "name": "Light"}]
                  },
                  "domainImpactToken": "root-secret"
                }
                """);

        JsonNode json = new ObjectMapper().readTree(sanitized);
        assertFalse(json.has("domainImpactToken"));
        assertFalse(json.path("preview").has("impactToken"));
        assertFalse(json.path("preview").path("items").get(0).has("confirmationToken"));
        assertEquals("Light", json.path("preview").path("items").get(0).path("name").asText());
    }

    /**
     * The planning prompt hand-lists every tool under "Available tools:", while the model is *also*
     * sent the real schema set from {@code AiToolManager.getAllToolDefinitions()}. Two independent
     * sources of the same fact: adding a tool without editing the prose leaves the catalogue claiming
     * a capability set that does not match what the model can actually call, and nothing fails.
     *
     * <p>Compared against the tool classes' own {@code getName()} literals rather than a Spring
     * context, so this stays a fast unit check.
     */
    /**
     * {@code hasCompletedToolEvidence} is the only guard standing between a turn and a persisted
     * COMPLETED status, at both the live terminal transition and history reload — and it had no test
     * of its own. The frontend carries a mirror implementation in {@code api/chat.ts} that is tested;
     * this pins the authoritative side.
     */
    @Test
    void completedEvidenceRequiresAPairedUsableToolResult() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "hasCompletedToolEvidence", List.class);
        method.setAccessible(true);

        // A round that ran a tool and got a usable result back is the only shape that proves work.
        assertEquals(true, method.invoke(service, List.of(
                progressFrame("TOOL_EXECUTION", "list_rules", 1, null),
                progressFrame("TOOL_RESULT", "list_rules", 1, "USABLE"))));

        // No trace at all, or no tool work in it, is not evidence of completion.
        assertEquals(false, method.invoke(service, List.of()));
        assertEquals(false, method.invoke(service, (Object) null));
        assertEquals(false, method.invoke(service, List.of(
                progressFrame("PLANNING", null, 1, null))));

        // An execution with no result never landed: the outcome is unknown, not complete.
        assertEquals(false, method.invoke(service, List.of(
                progressFrame("TOOL_EXECUTION", "manage_rule", 1, null))));

        // A result that does not pair with its execution means the trace is not trustworthy.
        assertEquals(false, method.invoke(service, List.of(
                progressFrame("TOOL_EXECUTION", "manage_rule", 1, null),
                progressFrame("TOOL_RESULT", "list_rules", 1, "USABLE"))));
        assertEquals(false, method.invoke(service, List.of(
                progressFrame("TOOL_EXECUTION", "manage_rule", 1, null),
                progressFrame("TOOL_RESULT", "manage_rule", 2, "USABLE"))));
        assertEquals(false, method.invoke(service, List.of(
                progressFrame("TOOL_RESULT", "list_rules", 1, "USABLE"))));

        // Two executions without an intervening result: the first one's outcome was never reported.
        assertEquals(false, method.invoke(service, List.of(
                progressFrame("TOOL_EXECUTION", "list_rules", 1, null),
                progressFrame("TOOL_EXECUTION", "list_specs", 1, null))));

        // A failed or unconfirmable outcome is not completion, however many usable ones precede it.
        for (String outcome : List.of("FAILED", "RESULT_UNAVAILABLE", "CONFIRMATION_REQUIRED")) {
            assertEquals(false, method.invoke(service, List.of(
                    progressFrame("TOOL_EXECUTION", "list_rules", 1, null),
                    progressFrame("TOOL_RESULT", "list_rules", 1, "USABLE"),
                    progressFrame("TOOL_EXECUTION", "manage_rule", 2, null),
                    progressFrame("TOOL_RESULT", "manage_rule", 2, outcome))), outcome);
        }

        // A guard stop means the loop was cut short, so the turn is PARTIAL regardless of prior work.
        assertEquals(false, method.invoke(service, List.of(
                progressFrame("TOOL_EXECUTION", "list_rules", 1, null),
                progressFrame("TOOL_RESULT", "list_rules", 1, "USABLE"),
                progressFrame("EXECUTION_GUARD", null, 2, "NO_PROGRESS"))));

        // A tool left PARTIAL blocks completion until a later round resolves that same tool.
        assertEquals(false, method.invoke(service, List.of(
                progressFrame("TOOL_EXECUTION", "list_rules", 1, null),
                progressFrame("TOOL_RESULT", "list_rules", 1, "USABLE"),
                progressFrame("TOOL_EXECUTION", "manage_rule", 2, null),
                progressFrame("TOOL_RESULT", "manage_rule", 2, "PARTIAL"))));
        assertEquals(true, method.invoke(service, List.of(
                progressFrame("TOOL_EXECUTION", "manage_rule", 1, null),
                progressFrame("TOOL_RESULT", "manage_rule", 1, "PARTIAL"),
                progressFrame("TOOL_EXECUTION", "manage_rule", 2, null),
                progressFrame("TOOL_RESULT", "manage_rule", 2, "USABLE"))));

        // A null frame makes the whole trace unreadable rather than partially trusted.
        assertEquals(false, method.invoke(service, java.util.Arrays.asList(
                progressFrame("TOOL_EXECUTION", "list_rules", 1, null),
                progressFrame("TOOL_RESULT", "list_rules", 1, "USABLE"),
                null)));
    }

    private StreamResponseDto.ProgressDto progressFrame(
            String stage, String toolName, Integer round, String outcome) {
        return new StreamResponseDto.ProgressDto(
                stage, toolName, round, outcome, null, null, null, null);
    }

    @Test
    void bothPromptsShareOneIdentityLine() throws Exception {
        // The planning and visible-reply prompts are deliberately different documents, but the model
        // sees both within one turn, so the platform's self-description must not drift between them.
        // It used to be duplicated verbatim in two text blocks — edit one, and the two rounds
        // describe the platform differently with nothing to catch it.
        Method planningMethod = ChatServiceImpl.class.getDeclaredMethod("buildToolPlanningSystemPrompt");
        planningMethod.setAccessible(true);
        Method visibleMethod = ChatServiceImpl.class.getDeclaredMethod(
                "buildVisibleReplySystemPrompt", boolean.class);
        visibleMethod.setAccessible(true);

        String planning = ((LlmMessage) planningMethod.invoke(service)).content();
        String visible = ((LlmMessage) visibleMethod.invoke(service, false)).content();

        java.lang.reflect.Field identity =
                ChatServiceImpl.class.getDeclaredField("ASSISTANT_IDENTITY_LINE");
        identity.setAccessible(true);
        String identityLine = (String) identity.get(null);

        assertFalse(identityLine.isBlank());
        assertTrue(planning.startsWith(identityLine),
                "the planning prompt must open with the shared identity line");
        assertTrue(visible.startsWith(identityLine),
                "the visible-reply prompt must open with the same shared line");
    }

    @Test
    void planningPromptCatalogListsExactlyTheRegisteredTools() throws Exception {
        Method planningMethod = ChatServiceImpl.class.getDeclaredMethod("buildToolPlanningSystemPrompt");
        planningMethod.setAccessible(true);
        String prompt = ((LlmMessage) planningMethod.invoke(service)).content();

        String catalog = prompt.substring(prompt.indexOf("Available tools:"));
        java.util.Set<String> listed = new java.util.TreeSet<>();
        java.util.regex.Matcher listedMatcher = java.util.regex.Pattern
                .compile("\\b([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\\b").matcher(catalog);
        while (listedMatcher.find()) listed.add(listedMatcher.group(1));

        java.util.Set<String> registered = new java.util.TreeSet<>();
        java.nio.file.Path toolRoot = java.nio.file.Path.of(
                "src/main/java/cn/edu/nju/Iot_Verify/component/aitool");
        assertTrue(java.nio.file.Files.isDirectory(toolRoot), "tool source root not found");
        try (var paths = java.nio.file.Files.walk(toolRoot)) {
            for (java.nio.file.Path file : paths.filter(f -> f.toString().endsWith("Tool.java")).toList()) {
                String body = java.nio.file.Files.readString(file);
                java.util.regex.Matcher name = java.util.regex.Pattern
                        .compile("public String getName\\(\\)\\s*\\{\\s*return \"([a-z0-9_]+)\"")
                        .matcher(body);
                if (name.find()) registered.add(name.group(1));
            }
        }
        assertFalse(registered.isEmpty(), "no tool getName() literals were found");

        java.util.Set<String> missingFromPrompt = new java.util.TreeSet<>(registered);
        missingFromPrompt.removeAll(listed);
        assertTrue(missingFromPrompt.isEmpty(),
                "registered tools absent from the prompt catalog: " + missingFromPrompt);

        // The catalog prose also mentions non-tool identifiers, so only flag snake_case names that
        // look like tools but match no registration.
        java.util.Set<String> unknownInPrompt = new java.util.TreeSet<>(listed);
        unknownInPrompt.removeAll(registered);
        assertTrue(unknownInPrompt.isEmpty(),
                "prompt catalog names that are not registered tools: " + unknownInPrompt);
    }

    @Test
    void assistantPrompts_requireRecommendationAccountingAndIncompleteModelDisclosure() throws Exception {
        Method planningMethod = ChatServiceImpl.class.getDeclaredMethod("buildToolPlanningSystemPrompt");
        planningMethod.setAccessible(true);
        Method visibleMethod = ChatServiceImpl.class.getDeclaredMethod(
                "buildVisibleReplySystemPrompt", boolean.class);
        visibleMethod.setAccessible(true);

        String planning = ((LlmMessage) planningMethod.invoke(service)).content();
        String visible = ((LlmMessage) visibleMethod.invoke(service, true)).content();

        assertTrue(planning.contains("truncated candidates were never inspected"));
        assertTrue(planning.contains("complete tool catalog"));
        assertTrue(planning.contains("inspect current Board state only when"));
        assertTrue(planning.contains("use list_templates only when"));
        assertTrue(planning.contains("use manage_rule directly"));
        // The reasoning channel must ask the model to *do* the analysis, not narrate the call list:
        // decomposition, evidence, rejected alternatives, and self-check are each load-bearing.
        assertTrue(planning.contains("do the thinking here, do not merely narrate the steps"));
        assertTrue(planning.contains("Decompose first"));
        assertTrue(planning.contains("Reason from the observed board"));
        assertTrue(planning.contains("name the alternative you did not"));
        // Not "before concluding": the planning round returns tool calls, so their results do not exist
        // yet in it. The self-check is only performable against an earlier round's results, and asking for
        // it "before concluding" invited the model to predict one — which the next bullet forbids.
        assertTrue(planning.contains("Check yourself."));
        assertTrue(planning.contains("Do not predict a result you have not received yet."));
        assertTrue(planning.contains("audit-friendly explanation, not private hidden chain-of-thought"));
        assertTrue(planning.contains("Use recommend_scenario only"));
        assertTrue(planning.contains("call apply_scenario with confirmed=false"));
        assertTrue(planning.contains("call apply_fix with confirmed=false"));
        // One owner now: the catalog's trailing paragraph stated this a second time, and its unique clause
        // (budget exhaustion is not proof) was merged here rather than deleted with it.
        assertTrue(planning.contains("never route a fuzz finding into either tool"));
        assertTrue(planning.contains("describe budget exhaustion as proof"));
        assertTrue(planning.contains("do not delete or recreate devices individually"));
        assertTrue(planning.contains("Do not call"));
        assertTrue(planning.contains("add_device, manage_rule, or manage_spec"));
        assertTrue(visible.contains("Match the level of detail to the user's request"));
        assertTrue(visible.contains("SATISFIED with modelComplete=false"));
        assertTrue(visible.contains("one possible formal-model trajectory"));
        assertTrue(visible.contains("A verified suggestion still is not applied"));
        assertTrue(visible.contains("Present every one and let the user choose"));
        assertTrue(visible.contains("alternativesComplete=false"));
        assertTrue(visible.contains("When a suggestion lists preexistingViolations"));
        assertTrue(visible.contains("NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE or ALL_CANDIDATES_REJECTED proves"));
        assertTrue(visible.contains("apply_fix"));
        assertTrue(visible.contains("Never expose impactToken"));
        assertTrue(visible.contains("suggestionToken"));
    }

    @Test
    void pendingConfirmationPrompt_restoresDestructiveTokenOutsideHistoryWindow() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "buildPendingTaskSystemPrompt",
                AiDestructiveActionGuard.PendingActionContext.class,
                AiScenarioDraftStore.PendingApplication.class,
                AiTaskContinuationStore.ContinuationContext.class,
                ChatConfirmationDetector.ConfirmationDecision.class,
                String.class);
        method.setAccessible(true);

        LlmMessage context = (LlmMessage) method.invoke(
                service,
                new AiDestructiveActionGuard.PendingActionContext(
                        "delete_device", "alarm_1", "server-token"),
                null,
                new AiTaskContinuationStore.ContinuationContext(
                        "Replace the hall alarm and verify the repaired scene",
                        List.of("Keep the existing rules"),
                        "delete_device",
                        "{\"operation\":\"preview\"}",
                        Instant.parse("2026-07-17T12:00:00Z")),
                ChatConfirmationDetector.ConfirmationDecision.confirmed(
                        ChatConfirmationDetector.ConfirmationKind.DESTRUCTIVE),
                "Confirm deletion, but do not create another alarm");

        assertTrue(context.content().contains("delete_device"));
        assertTrue(context.content().contains("alarm_1"));
        assertTrue(context.content().contains("server-token"));
        assertTrue(context.content().contains("Do not request another preview"));
        assertTrue(context.content().contains("Replace the hall alarm"));
        assertTrue(context.content().contains("latest user message is authoritative"));
        assertTrue(context.content().contains("do not create another alarm"));
    }

    @Test
    void protectedConfirmation_requiresStructuredCommandInsteadOfNaturalLanguage() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "resolveConfirmation",
                String.class,
                ChatConfirmationCommandDto.class,
                EnumSet.class);
        method.setAccessible(true);

        ChatConfirmationDetector.ConfirmationDecision textOnly =
                (ChatConfirmationDetector.ConfirmationDecision) method.invoke(
                        service,
                        "yes, delete it",
                        null,
                        EnumSet.of(ChatConfirmationDetector.ConfirmationKind.DESTRUCTIVE));
        assertEquals(ChatConfirmationDetector.DecisionType.NONE, textOnly.type());

        ChatConfirmationCommandDto command = new ChatConfirmationCommandDto();
        command.setAction(ChatConfirmationCommandDto.Action.CONFIRM);
        command.setKind(ChatConfirmationCommandDto.Kind.DESTRUCTIVE);
        ChatConfirmationDetector.ConfirmationDecision explicit =
                (ChatConfirmationDetector.ConfirmationDecision) method.invoke(
                        service,
                        "button confirmation",
                        command,
                        EnumSet.of(ChatConfirmationDetector.ConfirmationKind.DESTRUCTIVE));
        assertEquals(ChatConfirmationDetector.DecisionType.CONFIRMED, explicit.type());
        assertEquals(ChatConfirmationDetector.ConfirmationKind.DESTRUCTIVE, explicit.kind());
    }

    @Test
    void protectedConfirmation_rejectsACommandForANonPendingKind() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "resolveConfirmation",
                String.class,
                ChatConfirmationCommandDto.class,
                EnumSet.class);
        method.setAccessible(true);
        ChatConfirmationCommandDto command = new ChatConfirmationCommandDto();
        command.setAction(ChatConfirmationCommandDto.Action.CONFIRM);
        command.setKind(ChatConfirmationCommandDto.Kind.SCENE_REPLACEMENT);

        InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> method.invoke(
                service,
                "button confirmation",
                command,
                EnumSet.of(ChatConfirmationDetector.ConfirmationKind.DESTRUCTIVE)));

        assertTrue(error.getCause() instanceof BadRequestException);
    }

    @Test
    void pendingConfirmationPrompt_usesResetSchemaWithoutInventingTargetArgument() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "buildPendingTaskSystemPrompt",
                AiDestructiveActionGuard.PendingActionContext.class,
                AiScenarioDraftStore.PendingApplication.class,
                AiTaskContinuationStore.ContinuationContext.class,
                ChatConfirmationDetector.ConfirmationDecision.class,
                String.class);
        method.setAccessible(true);

        LlmMessage context = (LlmMessage) method.invoke(
                service,
                new AiDestructiveActionGuard.PendingActionContext(
                        "reset_default_templates", "bundled-default-template-catalog", "reset-token"),
                null,
                null,
                ChatConfirmationDetector.ConfirmationDecision.confirmed(
                        ChatConfirmationDetector.ConfirmationKind.DEFAULT_TEMPLATE_RESET),
                "Confirm refreshing the bundled default templates");

        assertTrue(context.content().contains("reset_default_templates exactly once"));
        assertTrue(context.content().contains("confirmed=true"));
        assertTrue(context.content().contains("reset-token"));
        assertTrue(context.content().contains("Do not add a target field"));
        assertFalse(context.content().contains("bundled-default-template-catalog"));
    }

    @Test
    void pendingConfirmationPrompt_usesClearBoardSchemaWithoutInventingTargetArgument() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "buildPendingTaskSystemPrompt",
                AiDestructiveActionGuard.PendingActionContext.class,
                AiScenarioDraftStore.PendingApplication.class,
                AiTaskContinuationStore.ContinuationContext.class,
                ChatConfirmationDetector.ConfirmationDecision.class,
                String.class);
        method.setAccessible(true);

        LlmMessage context = (LlmMessage) method.invoke(
                service,
                new AiDestructiveActionGuard.PendingActionContext(
                        "clear_board", "current-board", "clear-token"),
                null,
                null,
                ChatConfirmationDetector.ConfirmationDecision.confirmed(
                        ChatConfirmationDetector.ConfirmationKind.DESTRUCTIVE),
                "Confirm clearing the Board");

        assertTrue(context.content().contains("Call clear_board exactly once"));
        assertTrue(context.content().contains("confirmed=true"));
        assertTrue(context.content().contains("clear-token"));
        assertTrue(context.content().contains("Do not add a target field"));
        assertFalse(context.content().contains("current-board"));
        assertFalse(context.content().contains("targetKey"));
    }

    @Test
    void pendingConfirmationPrompt_usesHistoryClearSchemaWithoutInventingTargetArgument() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "buildPendingTaskSystemPrompt",
                AiDestructiveActionGuard.PendingActionContext.class,
                AiScenarioDraftStore.PendingApplication.class,
                AiTaskContinuationStore.ContinuationContext.class,
                ChatConfirmationDetector.ConfirmationDecision.class,
                String.class);
        method.setAccessible(true);

        LlmMessage context = (LlmMessage) method.invoke(
                service,
                new AiDestructiveActionGuard.PendingActionContext(
                        "manage_board_history", "board-edit-history", "history-token"),
                null,
                null,
                ChatConfirmationDetector.ConfirmationDecision.confirmed(
                        ChatConfirmationDetector.ConfirmationKind.DESTRUCTIVE),
                "Confirm clearing unusable undo history");

        assertTrue(context.content().contains("Call manage_board_history exactly once"));
        assertTrue(context.content().contains("action=clear"));
        assertTrue(context.content().contains("confirmed=true"));
        assertTrue(context.content().contains("history-token"));
        assertTrue(context.content().contains("Do not add a target field"));
        assertFalse(context.content().contains("board-edit-history"));
        assertFalse(context.content().contains("targetKey"));
    }

    @Test
    void pendingConfirmationPrompt_replaysStoredApplyFixWithoutRepeatingSignedSuggestion() throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "buildPendingTaskSystemPrompt",
                AiDestructiveActionGuard.PendingActionContext.class,
                AiScenarioDraftStore.PendingApplication.class,
                AiTaskContinuationStore.ContinuationContext.class,
                ChatConfirmationDetector.ConfirmationDecision.class,
                String.class);
        method.setAccessible(true);

        LlmMessage context = (LlmMessage) method.invoke(
                service,
                new AiDestructiveActionGuard.PendingActionContext(
                        "apply_fix", "31", "apply-token"),
                null,
                null,
                ChatConfirmationDetector.ConfirmationDecision.confirmed(
                        ChatConfirmationDetector.ConfirmationKind.DESTRUCTIVE),
                "Apply the reviewed formal fix");

        assertTrue(context.content().contains("Call apply_fix exactly once"));
        assertTrue(context.content().contains("JSON integer"));
        assertTrue(context.content().contains("Do not repeat suggestion"));
        assertTrue(context.content().contains("apply-token"));
        assertTrue(context.content().contains("\"targetKey\":\"31\""));
    }

    @Test
    void executeToolLoop_whenToolReturnsError_shouldNotCollectRefreshCommand() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"error\":\"failed\"}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertTrue(commandSet.isEmpty());
    }

    @Test
    void executeToolLoop_whenToolSucceeds_shouldCollectRefreshCommand() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"operation\":\"created\",\"message\":\"ok\"}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertEquals(1, commandSet.size());
        StreamResponseDto.CommandDto cmd = commandSet.iterator().next();
        assertEquals("REFRESH_DATA", cmd.getType());
        assertEquals("rule_list", cmd.getPayload().get("target"));
        assertEquals("RULES_UPDATED", cmd.getPayload().get("assistantAction"));
    }

    @Test
    void executeToolLoop_whenScenarioApplySucceeds_shouldRefreshWholeBoard() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("apply_scenario", "{\"confirmed\":true}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("apply_scenario", "{\"confirmed\":true}"))
                .thenReturn("{\"operation\":\"replaced\",\"message\":\"ok\"}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertEquals(1, commandSet.size());
        StreamResponseDto.CommandDto command = commandSet.iterator().next();
        assertEquals("board_state", command.getPayload().get("target"));
        assertEquals("SCENE_APPLIED", command.getPayload().get("assistantAction"));
    }

    @Test
    void executeToolLoop_whenFormalFixApplySucceeds_shouldRefreshRules() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("apply_fix", "{\"traceId\":31,\"confirmed\":true}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("apply_fix", "{\"traceId\":31,\"confirmed\":true}"))
                .thenReturn("{\"operation\":\"applied\",\"applied\":true,\"message\":\"ok\"}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertEquals(1, commandSet.size());
        StreamResponseDto.CommandDto command = commandSet.iterator().next();
        assertEquals("rule_list", command.getPayload().get("target"));
        assertEquals("REPAIR_APPLIED", command.getPayload().get("assistantAction"));
    }

    @Test
    void executeToolLoop_whenEditHistoryIsCleared_shouldRefreshBeforeShowingExactSummary()
            throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_board_history", "{\"action\":\"clear\"}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("manage_board_history", "{\"action\":\"clear\"}"))
                .thenReturn("{\"operation\":\"history_cleared\",\"clearedEntryCount\":4,"
                        + "\"canUndo\":false,\"canRedo\":false}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertEquals(1, commandSet.size());
        StreamResponseDto.CommandDto command = commandSet.iterator().next();
        assertEquals("board_state", command.getPayload().get("target"));
        assertFalse(command.getPayload().containsKey("assistantAction"));
        assertTrue(String.valueOf(command.getPayload().get("assistantSummary"))
                .contains("Cleared 4 undo/redo"));
    }

    @Test
    void executeToolLoop_whenMutationResultIsUnavailable_shouldRefreshStopAndNotCountSuccess() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"resultStatus\":\"RESULT_UNAVAILABLE\",\"resultAvailable\":false,"
                        + "\"mutationMayHaveCommitted\":true,\"message\":\"Refresh before retrying.\"}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        Object loopResult = invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertEquals(1, commandSet.size());
        StreamResponseDto.CommandDto command = commandSet.iterator().next();
        assertEquals("rule_list", command.getPayload().get("target"));
        assertEquals("OUTCOME_RECONCILED", command.getPayload().get("assistantAction"));
        assertEquals(0, recordInt(loopResult, "successfulToolCalls"));
        assertEquals(1, recordInt(loopResult, "resultUnavailableToolCalls"));
        assertEquals(1, recordInt(loopResult, "uncertainMutationCalls"));
        verify(llmChatService).chatWithTools(anyList(), anyList());
    }

    @Test
    void executeToolLoop_whenAsyncDispatchOutcomeIsUnknown_shouldRefreshRunHistoryAndStop() throws Exception {
        for (String toolName : List.of(
                "verify_model_async", "simulate_model_async", "fuzz_model_async")) {
            org.mockito.Mockito.reset(llmChatService, aiToolManager, messageRepo);
            String statusTool = switch (toolName) {
                case "verify_model_async" -> "verify_task_status";
                case "simulate_model_async" -> "simulate_task_status";
                case "fuzz_model_async" -> "fuzz_task_status";
                default -> throw new IllegalStateException("Unexpected async start tool " + toolName);
            };
            when(llmChatService.chatWithTools(anyList(), anyList()))
                    .thenReturn(toolCallResult(toolName, "{}"));
            when(aiToolManager.execute(toolName, "{}"))
                    .thenReturn("{\"resultStatus\":\"RESULT_UNAVAILABLE\",\"resultAvailable\":false,"
                            + "\"mutationMayHaveCommitted\":true,"
                            + "\"errorCode\":\"TASK_DISPATCH_OUTCOME_UNKNOWN\","
                            + "\"taskId\":17,\"statusTool\":\"" + statusTool + "\"}");

            Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
            Object loopResult = invokeToolLoop(new AtomicBoolean(false), commandSet);

            assertEquals(1, commandSet.size(), toolName);
            StreamResponseDto.CommandDto command = commandSet.iterator().next();
            assertEquals("run_history", command.getPayload().get("target"), toolName);
            assertEquals("OUTCOME_RECONCILED", command.getPayload().get("assistantAction"), toolName);
            assertEquals(0, recordInt(loopResult, "successfulToolCalls"), toolName);
            assertEquals(1, recordInt(loopResult, "resultUnavailableToolCalls"), toolName);
            assertEquals(1, recordInt(loopResult, "uncertainMutationCalls"), toolName);
            verify(llmChatService).chatWithTools(anyList(), anyList());
        }
    }

    @Test
    void executeToolLoop_whenReadOnlyResultIsUnavailable_shouldContinuePlanningWithoutRefresh()
            throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("list_rules", "{}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("list_rules", "{}"))
                .thenReturn("{\"resultStatus\":\"RESULT_UNAVAILABLE\",\"resultAvailable\":false,"
                        + "\"mutationMayHaveCommitted\":false}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        Object loopResult = invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertTrue(commandSet.isEmpty());
        assertEquals(0, recordInt(loopResult, "successfulToolCalls"));
        assertEquals(1, recordInt(loopResult, "resultUnavailableToolCalls"));
        assertEquals(0, recordInt(loopResult, "uncertainMutationCalls"));
        verify(llmChatService, org.mockito.Mockito.times(2)).chatWithTools(anyList(), anyList());
    }

    @Test
    void executeToolLoop_whenOneOfSeveralReadResultsIsUnavailable_shouldRunIndependentCalls()
            throws Exception {
        List<LlmToolCall> plannedCalls = List.of(
                new LlmToolCall("tc_unavailable", "get_trace", "{\"traceId\":1}"),
                new LlmToolCall("tc_later", "list_rules", "{}"));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(LlmChatResponse.ofToolCalls(plannedCalls))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("get_trace", "{\"traceId\":1}"))
                .thenReturn("{\"resultStatus\":\"RESULT_UNAVAILABLE\",\"resultAvailable\":false,"
                        + "\"mutationMayHaveCommitted\":false}");
        when(aiToolManager.execute("list_rules", "{}"))
                .thenReturn("{\"rules\":[]}");

        List<LlmMessage> messages = new ArrayList<>();
        Object loopResult = invokeToolLoop(
                new AtomicBoolean(false), new LinkedHashSet<>(), mock(SseEmitter.class), messages);

        verify(aiToolManager).execute("list_rules", "{}");
        assertEquals(1, recordInt(loopResult, "successfulToolCalls"));
        assertEquals(1, recordInt(loopResult, "resultUnavailableToolCalls"));
        assertEquals(3, messages.size());
        assertEquals("tc_later", messages.get(2).toolCallId());
        assertFalse(new ObjectMapper().readTree(messages.get(2).content())
                .path("skipped").asBoolean());
    }

    @Test
    void executeToolLoop_whenDeletionNeedsConfirmation_shouldStopWithoutRefreshOrSecondPlanningRound() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("delete_device", "{\"id\":\"light_1\",\"confirmed\":false}"));
        when(aiToolManager.execute("delete_device", "{\"id\":\"light_1\",\"confirmed\":false}"))
                .thenReturn("{\"operation\":\"preview\",\"requiresUserConfirmation\":true,\"message\":\"No changes were made.\"}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertTrue(commandSet.isEmpty());
        verify(llmChatService).chatWithTools(anyList(), anyList());
        verify(aiToolManager).execute("delete_device", "{\"id\":\"light_1\",\"confirmed\":false}");
    }

    @Test
    void executeToolLoop_whenOneOfSeveralCallsNeedsConfirmation_shouldRecordRemainingCallsAsSkipped() throws Exception {
        List<LlmToolCall> plannedCalls = List.of(
                new LlmToolCall("tc_preview", "delete_device",
                        "{\"id\":\"light_1\",\"confirmed\":false}"),
                new LlmToolCall("tc_later", "manage_spec", "{\"action\":\"add\"}"));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(LlmChatResponse.ofToolCalls(plannedCalls));
        when(aiToolManager.execute("delete_device", "{\"id\":\"light_1\",\"confirmed\":false}"))
                .thenReturn("{\"operation\":\"preview\",\"requiresUserConfirmation\":true}");

        List<LlmMessage> messages = new ArrayList<>();
        Object loopResult = invokeToolLoop(
                new AtomicBoolean(false), new LinkedHashSet<>(), mock(SseEmitter.class), messages);

        verify(aiToolManager, never()).execute("manage_spec", "{\"action\":\"add\"}");
        assertTrue(recordBoolean(loopResult, "confirmationPending"));
        assertEquals(3, messages.size());
        assertEquals("tc_preview", messages.get(1).toolCallId());
        assertEquals("tc_later", messages.get(2).toolCallId());
        JsonNode skipped = new ObjectMapper().readTree(messages.get(2).content());
        assertTrue(skipped.path("skipped").asBoolean());
        assertFalse(skipped.path("executed").asBoolean(true));
        assertEquals("PRIOR_CONFIRMATION_REQUIRED", skipped.path("reasonCode").asText());
    }

    @Test
    void executeToolLoop_whenOneOfSeveralResultsIsUnavailable_shouldRecordRemainingCallsAsSkipped() throws Exception {
        List<LlmToolCall> plannedCalls = List.of(
                new LlmToolCall("tc_unavailable", "manage_rule", "{}"),
                new LlmToolCall("tc_later", "manage_spec", "{\"action\":\"add\"}"));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(LlmChatResponse.ofToolCalls(plannedCalls));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"resultStatus\":\"RESULT_UNAVAILABLE\",\"resultAvailable\":false,"
                        + "\"mutationMayHaveCommitted\":true}");

        List<LlmMessage> messages = new ArrayList<>();
        invokeToolLoop(new AtomicBoolean(false), new LinkedHashSet<>(), mock(SseEmitter.class), messages);

        verify(aiToolManager, never()).execute("manage_spec", "{\"action\":\"add\"}");
        assertEquals(3, messages.size());
        JsonNode skipped = new ObjectMapper().readTree(messages.get(2).content());
        assertEquals("PRIOR_RESULT_UNAVAILABLE", skipped.path("reasonCode").asText());
    }

    @Test
    void executeToolLoop_repairsBlankDuplicateAndReusedProviderToolCallIds() throws Exception {
        List<LlmToolCall> plannedCalls = List.of(
                new LlmToolCall("", "list_rules", "{}"),
                new LlmToolCall("already_used", "list_specs", "{}"),
                new LlmToolCall("already_used", "board_overview", "{}"));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(LlmChatResponse.ofToolCalls(plannedCalls))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute(anyString(), anyString())).thenReturn("{\"count\":0}");

        List<LlmMessage> messages = new ArrayList<>(List.of(
                LlmMessage.assistantToolCalls(List.of(
                        new LlmToolCall("already_used", "list_templates", "{}"))),
                LlmMessage.tool("already_used", "{\"count\":45}")));
        invokeToolLoop(new AtomicBoolean(false), new LinkedHashSet<>(), mock(SseEmitter.class), messages);

        List<String> assistantIds = messages.get(2).toolCalls().stream().map(LlmToolCall::id).toList();
        List<String> resultIds = messages.subList(3, 6).stream().map(LlmMessage::toolCallId).toList();
        assertEquals(3, new LinkedHashSet<>(assistantIds).size());
        assertTrue(assistantIds.stream().noneMatch(String::isBlank));
        assertFalse(assistantIds.contains("already_used"));
        assertEquals(assistantIds, resultIds);
        verify(aiToolManager).execute("list_rules", "{}");
        verify(aiToolManager).execute("list_specs", "{}");
        verify(aiToolManager).execute("board_overview", "{}");
    }

    @Test
    void executeToolLoop_whenAddDeviceOffersAlternativeName_shouldNotAcceptItForTheUser() throws Exception {
        String args = "{\"templateName\":\"Light\",\"label\":\"Hall Light\"}";
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("add_device", args));
        when(aiToolManager.execute("add_device", args))
                .thenReturn("{\"error\":\"Display name is already in use. No device was created.\","
                        + "\"errorCode\":\"DEVICE_LABEL_CONFLICT\",\"operation\":\"notCreated\","
                        + "\"suggestedLabel\":\"Hall Light_1\",\"requiresUserConfirmation\":true}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        Object loopResult = invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertTrue(commandSet.isEmpty());
        assertTrue(recordBoolean(loopResult, "confirmationPending"));
        assertEquals(0, recordInt(loopResult, "successfulToolCalls"));
        verify(llmChatService).chatWithTools(anyList(), anyList());
        verify(aiToolManager).execute("add_device", args);
    }

    @Test
    void executeToolLoop_whenDeleteDeviceSucceeds_shouldRefreshDependentBoardLists() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("delete_device", "{\"identifier\":\"light_1\"}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("delete_device", "{\"identifier\":\"light_1\"}"))
                .thenReturn("{\"operation\":\"deleted\",\"deleted\":true,\"message\":\"ok\"}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        List<String> targets = commandSet.stream()
                .map(cmd -> String.valueOf(cmd.getPayload().get("target")))
                .toList();
        assertEquals(List.of("device_list", "environment_list", "rule_list", "spec_list"), targets);
    }

    @Test
    void executeToolLoop_whenAddDeviceSucceeds_shouldRefreshDeviceAndEnvironmentLists() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("add_device", "{\"templateName\":\"Sensor\"}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("add_device", "{\"templateName\":\"Sensor\"}"))
                .thenReturn("{\"operation\":\"created\",\"message\":\"ok\"}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        List<String> targets = commandSet.stream()
                .map(cmd -> String.valueOf(cmd.getPayload().get("target")))
                .toList();
        assertEquals(List.of("device_list", "environment_list"), targets);
    }

    @Test
    void executeToolLoop_whenEditDeviceSucceeds_shouldRefreshDeviceAndSpecificationLists() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("edit_device", "{\"id\":\"device-1\",\"field\":\"label\",\"label\":\"Hall\"}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("edit_device",
                "{\"id\":\"device-1\",\"field\":\"label\",\"label\":\"Hall\"}"))
                .thenReturn("{\"operation\":\"renamed\",\"message\":\"ok\"}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        List<String> targets = commandSet.stream()
                .map(cmd -> String.valueOf(cmd.getPayload().get("target")))
                .toList();
        assertEquals(List.of("device_list", "spec_list"), targets);
    }

    @Test
    void executeToolLoop_whenEnvironmentToolSucceeds_shouldRefreshEnvironmentList() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_environment", "{\"action\":\"set\",\"name\":\"temperature\",\"value\":\"21\"}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("manage_environment", "{\"action\":\"set\",\"name\":\"temperature\",\"value\":\"21\"}"))
                .thenReturn("{\"message\":\"ok\",\"operation\":\"updated\",\"changesApplied\":true}");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        StreamResponseDto.CommandDto command = commandSet.iterator().next();
        assertEquals("environment_list", command.getPayload().get("target"));
        assertEquals("ENVIRONMENT_UPDATED", command.getPayload().get("assistantAction"));
    }

    @Test
    void executeToolLoop_whenSuccessfulToolReportsNoChange_shouldNotClaimAnAssistantAction()
            throws Exception {
        Map<String, String> noChangeResults = Map.ofEntries(
                Map.entry("manage_environment", "{\"message\":\"listed\",\"operation\":\"listed\",\"changesApplied\":false}"),
                Map.entry("edit_device", "{\"message\":\"unchanged\",\"operation\":\"unchanged\"}"),
                Map.entry("delete_device", "{\"message\":\"preview\",\"operation\":\"preview\",\"requiresUserConfirmation\":true}"),
                Map.entry("manage_rule", "{\"message\":\"preview\",\"operation\":\"preview\",\"requiresUserConfirmation\":true}"),
                Map.entry("apply_fix", "{\"message\":\"preview\",\"operation\":\"preview\",\"requiresUserConfirmation\":true}"),
                Map.entry("manage_spec", "{\"message\":\"preview\",\"operation\":\"preview\",\"requiresUserConfirmation\":true}"),
                Map.entry("apply_scenario", "{\"message\":\"preview\",\"operation\":\"preview\",\"requiresUserConfirmation\":true}"),
                Map.entry("delete_template", "{\"message\":\"in use\",\"requiresUserConfirmation\":false,\"preview\":{}}"),
                Map.entry("reset_default_templates", "{\"message\":\"blocked\",\"requiresUserConfirmation\":false,\"preview\":{}}"),
                Map.entry("cancel_verify_task", "{\"message\":\"already completed\",\"cancellationAccepted\":false}"),
                Map.entry("delete_trace", "{\"message\":\"preview\",\"operation\":\"preview\",\"requiresUserConfirmation\":true}"),
                Map.entry("dismiss_verify_task", "{\"message\":\"preview\",\"operation\":\"preview\",\"requiresUserConfirmation\":true}")
        );
        for (Map.Entry<String, String> entry : noChangeResults.entrySet()) {
            org.mockito.Mockito.reset(llmChatService, aiToolManager, messageRepo);
            when(llmChatService.chatWithTools(anyList(), anyList()))
                    .thenReturn(toolCallResult(entry.getKey(), "{}"))
                    .thenReturn(textResult("done"));
            when(aiToolManager.execute(entry.getKey(), "{}")).thenReturn(entry.getValue());

            Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
            invokeToolLoop(new AtomicBoolean(false), commandSet);

            assertTrue(commandSet.isEmpty(), entry.getKey());
        }
    }

    @Test
    void executeToolLoop_whenRunHistoryChanges_shouldRefreshRunHistory() throws Exception {
        for (String toolName : List.of(
                "verify_model", "verify_model_async", "simulate_model_async",
                "fuzz_model_async", "cancel_verify_task", "cancel_simulate_task",
                "cancel_fuzz_task", "delete_trace", "delete_simulation_trace",
                "delete_verification_run", "delete_fuzz_run", "dismiss_verify_task",
                "dismiss_simulate_task", "dismiss_fuzz_task")) {
            org.mockito.Mockito.reset(llmChatService, aiToolManager, messageRepo);
            when(llmChatService.chatWithTools(anyList(), anyList()))
                    .thenReturn(toolCallResult(toolName, "{}"))
                    .thenReturn(textResult("done"));
            when(aiToolManager.execute(toolName, "{}"))
                    .thenReturn(confirmedRunHistoryMutationResult(toolName));

            Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
            invokeToolLoop(new AtomicBoolean(false), commandSet);

            assertEquals(1, commandSet.size(), toolName);
            StreamResponseDto.CommandDto command = commandSet.iterator().next();
            assertEquals("run_history", command.getPayload().get("target"), toolName);
            String expectedAction = switch (toolName) {
                case "verify_model" -> "FORMAL_VERIFICATION_RUN";
                case "verify_model_async" -> "VERIFICATION_TASK_STARTED";
                case "simulate_model_async" -> "SIMULATION_TASK_STARTED";
                case "fuzz_model_async" -> "EXPLORATION_TASK_STARTED";
                default -> "RUN_HISTORY_UPDATED";
            };
            assertEquals(expectedAction, command.getPayload().get("assistantAction"), toolName);
        }
    }

    @Test
    void executeToolLoop_whenToolReturnsNonJson_shouldNotCollectRefreshCommand() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("Rule added successfully.");

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        assertTrue(commandSet.isEmpty());
    }

    @Test
    void executeToolLoop_shouldNotEmitInternalProgressChunk() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"))
                .thenReturn(textResult("done"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"operation\":\"created\",\"message\":\"ok\"}");

        SseEmitter emitter = mock(SseEmitter.class);

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet, emitter);

        verify(aiToolManager).execute("manage_rule", "{}");
        assertEquals(1, commandSet.size());
    }

    @Test
    void sendFrontendCommands_shouldSendActionReceiptAfterAllPlainRefreshes() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);
        Set<StreamResponseDto.CommandDto> commands = new LinkedHashSet<>();
        commands.add(new StreamResponseDto.CommandDto("REFRESH_DATA", Map.of(
                "target", "device_list", "assistantAction", "DEVICE_ADDED")));
        commands.add(new StreamResponseDto.CommandDto("REFRESH_DATA", Map.of(
                "target", "environment_list")));

        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "sendFrontendCommands", SseEmitter.class, Set.class);
        method.setAccessible(true);

        assertEquals(true, method.invoke(service, emitter, commands));
        assertEquals(List.of("environment_list", "device_list"), frames.stream()
                .map(StreamResponseDto::getCommand)
                .map(StreamResponseDto.CommandDto::getPayload)
                .map(payload -> String.valueOf(payload.get("target")))
                .toList());
    }

    @Test
    void everyMutationCapableTool_shouldHaveAnAuthoritativeRefreshTarget() {
        for (String toolName : AiToolResultContract.mutationCapableTools()) {
            assertFalse(toolProgressPresenter.potentialRefreshTargets(toolName).isEmpty(),
                    toolName + " has no authoritative reconciliation target");
        }
    }

    @Test
    void executeToolLoop_whenFunctionNameMissing_shouldPersistStructuredErrorAndSkipToolExecution() throws Exception {
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("   ", "{}"))
                .thenReturn(textResult("done"));

        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        invokeToolLoop(new AtomicBoolean(false), commandSet);

        verify(aiToolManager, never()).execute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertTrue(commandSet.isEmpty());

        List<ChatMessagePo> persistedMessages = org.mockito.Mockito.mockingDetails(messageRepo).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("saveAndFlush"))
                .map(invocation -> invocation.getArgument(0, ChatMessagePo.class))
                .filter(Objects::nonNull)
                .toList();
        assertEquals(2, persistedMessages.size());
        ChatMessagePo toolMsg = Objects.requireNonNull(persistedMessages.stream()
                .filter(m -> "tool".equalsIgnoreCase(m.getRole()))
                .findFirst()
                .orElseThrow());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode persisted = mapper.readTree(toolMsg.getContent());
        JsonNode result = mapper.readTree(persisted.path("result").asText());
        assertEquals("Invalid tool call: missing function name.", result.path("error").asText());
        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertEquals(400, result.path("status").asInt());
    }

    @Test
    void jsonError_shouldReturnStructuredErrorJson() throws Exception {
        String result = (String) jsonErrorMethod.invoke(
                service,
                "Invalid tool call: missing function name.",
                "VALIDATION_ERROR",
                400
        );

        JsonNode json = new ObjectMapper().readTree(result);
        assertEquals("Invalid tool call: missing function name.", json.path("error").asText());
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
    }

    @Test
    void requestLocalUserExecutionStop_whenRequestIsQueued_preventsChatWorkFromStarting() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        transactionPropagations.clear();
        service.requestLocalUserExecutionStop(1L);
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(emitter).complete();
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                message -> message != null && "user".equals(message.getRole())));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                message -> message != null
                        && message.getExecutionStatus() == ChatExecutionStatus.DISCONNECTED));
        verifyNoInteractions(llmChatService, aiToolManager);
        verify(destructiveActionGuard).clearUser(1L);
        assertTrue(transactionPropagations.contains(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void requestUserExecutionStop_whenRequestIsQueued_persistsAnExplicitStop() {
        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        transactionPropagations.clear();
        service.requestUserExecutionStop(1L);
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                message -> message != null
                        && message.getExecutionStatus() == ChatExecutionStatus.STOPPED));
        verifyNoInteractions(llmChatService, aiToolManager);
        verify(destructiveActionGuard, never()).clearUser(1L);
        verify(scenarioDraftStore, never()).clearUser(1L);
        verify(taskContinuationStore, never()).clearUser(1L);
        assertTrue(transactionPropagations.contains(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenAccountDisappearsBeforeAssistantWrite_doesNotPersistLateMessage() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        UserPo user = UserPo.builder().id(1L).build();
        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user), Optional.of(user), Optional.empty());
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("late answer");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null && "user".equals(msg.getRole())));
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null && "assistant".equals(msg.getRole())));
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenAccountStopArrivesDuringToolPlanning_doesNotExecutePlannedTool() {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenAnswer(invocation -> {
            service.requestLocalUserExecutionStop(1L);
            return toolCallResult("list_rules", "{}");
        });
        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "please list rules", emitter);

        verify(aiToolManager, never()).execute(any(), any());
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.DISCONNECTED));
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null && "tool".equals(msg.getRole())));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenUserStopsDuringPlanning_persistsStoppedInsteadOfDisconnected() {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenAnswer(invocation -> {
            service.requestStreamStop(1L, "s1", "turn-1");
            return toolCallResult("list_rules", "{}");
        });
        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "please list rules", emitter);

        verify(aiToolManager, never()).execute(any(), any());
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.STOPPED
                        && msg.getContent().contains("user stopped")));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void requestStreamStop_whenDatabaseLeaseJustExpired_stillCancelsTheMatchingLocalRequest()
            throws Exception {
        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules");
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        session.setActiveExecutionExpiresAt(LocalDateTime.now().minusSeconds(1));

        service.requestStreamStop(1L, "s1", "turn-1");

        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        Object activeRequest = ((Map<?, ?>) activeRequests.get(service)).get("s1");
        assertNotNull(activeRequest);
        Method stopRequested = activeRequest.getClass().getDeclaredMethod("stopRequested");
        Method userStopRequested = activeRequest.getClass().getDeclaredMethod("userStopRequested");
        stopRequested.setAccessible(true);
        userStopRequested.setAccessible(true);
        assertTrue(((AtomicBoolean) stopRequested.invoke(activeRequest)).get());
        assertTrue(((AtomicBoolean) userStopRequested.invoke(activeRequest)).get());
        assertNull(session.getActiveExecutionId());
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void requestStreamStop_beforeAdmission_fencesMultipleTurnsAndBeginConsumesOnlyItsFence() {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();

        service.requestStreamStop(1L, "s1", "turn-early-stop-a");
        service.requestStreamStop(1L, "s1", "turn-early-stop-b");

        assertEquals(Set.of("turn-early-stop-a", "turn-early-stop-b"),
                session.getPreAdmissionStopTurns().keySet());
        ConflictException failure = assertThrows(ConflictException.class, () ->
                service.beginStreamRequest(1L, "s1", "turn-early-stop-a", "do not run"));
        assertTrue(failure.getMessage().contains("stopped before execution began"));
        assertEquals(Set.of("turn-early-stop-b"), session.getPreAdmissionStopTurns().keySet());
        assertThrows(ConflictException.class, () ->
                service.beginStreamRequest(1L, "s1", "turn-early-stop-b", "also do not run"));
        assertTrue(session.getPreAdmissionStopTurns().isEmpty());
        assertNull(session.getActiveExecutionId());
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                message -> message != null && "user".equals(message.getRole())));
    }

    @Test
    void requestStreamStop_beforeAdmission_rejectsOverflowWithoutDroppingExistingFences() {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        for (int index = 0; index < 64; index++) {
            service.requestStreamStop(1L, "s1", "turn-pending-" + index);
        }

        ConflictException failure = assertThrows(ConflictException.class, () ->
                service.requestStreamStop(1L, "s1", "turn-overflow"));

        assertTrue(failure.getMessage().contains("Too many chat turns"));
        assertEquals(64, session.getPreAdmissionStopTurns().size());
        assertFalse(session.getPreAdmissionStopTurns().containsKey("turn-overflow"));
    }

    @Test
    void requestStreamStop_beforeAdmission_discardsExpiredFencesBeforeApplyingCapacity() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 24, 12, 0);
        when(sessionRepo.currentDatabaseTime()).thenReturn(startedAt);
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        for (int index = 0; index < 64; index++) {
            service.requestStreamStop(1L, "s1", "turn-expired-" + index);
        }

        when(sessionRepo.currentDatabaseTime()).thenReturn(startedAt.plusMinutes(3));
        service.requestStreamStop(1L, "s1", "turn-current");

        assertEquals(Set.of("turn-current"), session.getPreAdmissionStopTurns().keySet());
        assertEquals(startedAt.plusMinutes(3), session.getPreAdmissionStopTurns().get("turn-current"));
    }

    @Test
    void beginStreamRequest_doesNotRejectARequestForAnExpiredStopFence() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 24, 12, 0);
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        session.getPreAdmissionStopTurns().put("turn-reused", startedAt);
        when(sessionRepo.currentDatabaseTime()).thenReturn(startedAt.plusMinutes(3));

        String executionId = service.beginStreamRequest(1L, "s1", "turn-reused", "run now");

        assertNotNull(executionId);
        assertTrue(session.getPreAdmissionStopTurns().isEmpty());
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void beginStreamRequest_rejectsContentContainingOnlyUnicodeSpaces() {
        assertThrows(BadRequestException.class,
                () -> service.beginStreamRequest(1L, "s1", "turn-unicode-blank", "\u00A0\u202F\u3000"));

        verify(userRepository, never()).findByIdForUpdate(any());
        verify(messageRepo, never()).saveAndFlush(any());
    }

    @Test
    void beginStreamRequest_rejectsOversizedContentBeforeAdmission() {
        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> service.beginStreamRequest(1L, "s1", "turn-oversized", "x".repeat(10_001)));

        assertTrue(failure.getMessage().contains("10000"));
        verify(userRepository, never()).findByIdForUpdate(any());
        verify(messageRepo, never()).saveAndFlush(any());
    }

    @Test
    void requestStreamStop_forDifferentUnadmittedTurn_doesNotStopCurrentExecution()
            throws Exception {
        String executionId = service.beginStreamRequest(1L, "s1", "turn-current", "keep running");
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();

        service.requestStreamStop(1L, "s1", "turn-stale");

        assertEquals(executionId, session.getActiveExecutionId());
        assertEquals("turn-current", session.getActiveExecutionTurnId());
        assertFalse(Boolean.TRUE.equals(session.getExecutionStopRequested()));
        assertEquals(Set.of("turn-stale"), session.getPreAdmissionStopTurns().keySet());

        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        Object activeRequest = ((Map<?, ?>) activeRequests.get(service)).get("s1");
        Method stopRequested = activeRequest.getClass().getDeclaredMethod("stopRequested");
        stopRequested.setAccessible(true);
        assertFalse(((AtomicBoolean) stopRequested.invoke(activeRequest)).get());
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void requestStreamStop_withoutKnownTurn_stillCancelsAnExpiredReattachedLocalRequest()
            throws Exception {
        String executionId = service.beginStreamRequest(1L, "s1", "turn-remote", "keep running");
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        session.setActiveExecutionExpiresAt(LocalDateTime.now().minusSeconds(1));

        service.requestStreamStop(1L, "s1", null);

        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        Object activeRequest = ((Map<?, ?>) activeRequests.get(service)).get("s1");
        Method stopRequested = activeRequest.getClass().getDeclaredMethod("stopRequested");
        Method userStopRequested = activeRequest.getClass().getDeclaredMethod("userStopRequested");
        stopRequested.setAccessible(true);
        userStopRequested.setAccessible(true);
        assertTrue(((AtomicBoolean) stopRequested.invoke(activeRequest)).get());
        assertTrue(((AtomicBoolean) userStopRequested.invoke(activeRequest)).get());
        assertNull(session.getActiveExecutionId());
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void executionLease_isVisibleAndExclusiveAcrossServiceInstances() {
        ChatServiceImpl otherInstance = newService();

        String executionId = service.beginStreamRequest(
                1L, "s1", "turn-1", "please list rules");

        assertTrue(otherInstance.getSessionActivity(1L, "s1").isActive());
        assertThrows(ChatSessionBusyException.class,
                () -> otherInstance.beginStreamRequest(1L, "s1", "turn-admission", "hello"));
        service.endStreamRequest(1L, "s1", executionId);
        assertFalse(otherInstance.getSessionActivity(1L, "s1").isActive());
    }

    @Test
    void beginAndAbortUndispatched_persistsThenExactlyRollsBackTheAdmittedTurn() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        LocalDateTime previousUpdatedAt = LocalDateTime.of(2026, 7, 23, 12, 30);
        session.setUpdatedAt(previousUpdatedAt);
        useSession(session);

        String executionId = service.beginStreamRequest(
                1L, "s1", " turn-exact ", "inspect the current board");

        assertEquals("New Chat", session.getTitle());
        assertEquals(previousUpdatedAt, session.getUpdatedAt());
        assertEquals(executionId, session.getActiveExecutionId());
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(message ->
                message != null
                        && "user".equals(message.getRole())
                        && "turn-exact".equals(message.getTurnId())
                        && executionId.equals(message.getExecutionId())
                        && "inspect the current board".equals(message.getContent())));

        service.abortUndispatched(1L, "s1", executionId, "turn-exact");

        verify(messageRepo).deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                "s1", "turn-exact", executionId, "user");
        assertEquals("New Chat", session.getTitle());
        assertEquals(previousUpdatedAt, session.getUpdatedAt());
        assertNull(session.getActiveExecutionId());
        assertFalse(service.getSessionActivity(1L, "s1").isActive());
    }

    @Test
    void abortUndispatched_dropsLocalRegistrationEvenWhenDatabaseRollbackFails() throws Exception {
        String executionId = service.beginStreamRequest(
                1L, "s1", "turn-rollback-failure", "hello");
        doThrow(new IllegalStateException("database unavailable"))
                .when(messageRepo).deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                        "s1", "turn-rollback-failure", executionId, "user");

        assertThrows(IllegalStateException.class, () -> service.abortUndispatched(
                1L, "s1", executionId, "turn-rollback-failure"));

        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        assertFalse(((Map<?, ?>) activeRequests.get(service)).containsKey("s1"));
    }

    @Test
    void abortUndispatched_doesNotClaimSuccessForAnOrphanedAdmittedTurn() throws Exception {
        String executionId = service.beginStreamRequest(
                1L, "s1", "turn-orphaned", "hello");
        when(sessionRepo.findByIdAndUserIdForUpdate("s1", 1L)).thenReturn(Optional.empty());
        when(messageRepo.existsBySessionIdAndTurnIdAndExecutionIdAndRole(
                "s1", "turn-orphaned", executionId, "user")).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.abortUndispatched(
                1L, "s1", executionId, "turn-orphaned"));

        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        assertFalse(((Map<?, ?>) activeRequests.get(service)).containsKey("s1"));
    }

    @Test
    void beginStreamRequest_rejectsAReusedTurnIdBeforePersistingOrClaiming() {
        when(messageRepo.existsBySessionIdAndTurnId("s1", "turn-duplicate"))
                .thenReturn(true);

        assertThrows(ConflictException.class, () -> service.beginStreamRequest(
                1L, "s1", "turn-duplicate", "hello"));

        verify(messageRepo, never()).saveAndFlush(any(ChatMessagePo.class));
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        assertNull(session.getActiveExecutionId());
    }

    @Test
    void beginStreamRequestFailsClosedWhenCommitReturnsAfterLeaseTtl() throws Exception {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        chatExecutionConfig.setLeaseTtlMs(20);
        chatExecutionConfig.setLeaseHeartbeatMs(5);
        transactionTemplate = transactionTemplateWithCommitHook(commit -> {
            if (commit == 1) delayAtLeast(Duration.ofMillis(80));
        });
        service = newService();

        assertThrows(ServiceUnavailableException.class,
                () -> service.beginStreamRequest(1L, "s1", "turn-admission", "hello"));

        assertEquals(null, session.getActiveExecutionId());
        assertEquals(null, session.getActiveExecutionExpiresAt());
        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        assertTrue(((Map<?, ?>) activeRequests.get(service)).isEmpty());
    }

    @Test
    void beginStreamRequestFailsClosedWhenCommitConsumesTheNextHeartbeatWindow() throws Exception {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        chatExecutionConfig.setLeaseTtlMs(1_000);
        chatExecutionConfig.setLeaseHeartbeatMs(400);
        transactionTemplate = transactionTemplateWithCommitHook(commit -> {
            if (commit == 1) delayAtLeast(Duration.ofMillis(700));
        });
        service = newService();

        assertThrows(ServiceUnavailableException.class,
                () -> service.beginStreamRequest(1L, "s1", "turn-admission", "hello"));

        assertNull(session.getActiveExecutionId());
        assertNull(session.getActiveExecutionExpiresAt());
        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        assertTrue(((Map<?, ?>) activeRequests.get(service)).isEmpty());
    }

    @Test
    void beginStreamRequest_reportsUnknownOutcomeWhenPostCommitRollbackFails() {
        chatExecutionConfig.setLeaseTtlMs(20);
        chatExecutionConfig.setLeaseHeartbeatMs(5);
        transactionTemplate = transactionTemplateWithCommitHook(commit -> {
            if (commit == 1) delayAtLeast(Duration.ofMillis(80));
        });
        service = newService();
        doThrow(new IllegalStateException("database unavailable"))
                .when(messageRepo).deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                        eq("s1"), eq("turn-admission"), anyString(), eq("user"));

        assertThrows(ChatAdmissionOutcomeUnknownException.class, () ->
                service.beginStreamRequest(1L, "s1", "turn-admission", "hello"));
    }

    @Test
    void beginStreamRequest_compensatesAnAmbiguousCommitBeforeReportingRetryableFailure() {
        transactionTemplate = transactionTemplateWithCommitHook(commit -> {
            if (commit == 1) throw new IllegalStateException("commit acknowledgement lost");
        });
        service = newService();

        assertThrows(ServiceUnavailableException.class, () ->
                service.beginStreamRequest(1L, "s1", "turn-ambiguous", "hello"));

        verify(messageRepo).deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                eq("s1"), eq("turn-ambiguous"), anyString(), eq("user"));
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        assertNull(session.getActiveExecutionId());
    }

    @Test
    void beginStreamRequest_reportsUnknownOutcomeWhenAmbiguousCommitCompensationFails() {
        transactionTemplate = transactionTemplateWithCommitHook(commit -> {
            if (commit == 1) throw new IllegalStateException("commit acknowledgement lost");
        });
        service = newService();
        doThrow(new IllegalStateException("database unavailable"))
                .when(messageRepo).deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                        eq("s1"), eq("turn-ambiguous"), anyString(), eq("user"));

        assertThrows(ChatAdmissionOutcomeUnknownException.class, () ->
                service.beginStreamRequest(1L, "s1", "turn-ambiguous", "hello"));
    }

    @Test
    void executionLeaseMaintenanceRenewsLongRunningRequestsAndSweepsExpiredRows() {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        chatExecutionConfig.setLeaseTtlMs(60_000);
        LocalDateTime initialDatabaseTime = LocalDateTime.of(2026, 7, 20, 12, 0);
        when(sessionRepo.currentDatabaseTime())
                .thenReturn(initialDatabaseTime, initialDatabaseTime.plusSeconds(1));
        String executionId = service.beginStreamRequest(1L, "s1", "turn-admission", "hello");
        assertEquals(executionId, session.getActiveExecutionId());
        LocalDateTime previousExpiry = session.getActiveExecutionExpiresAt();
        when(sessionRepo.clearExpiredExecutionLeases(any(LocalDateTime.class))).thenReturn(2);

        service.maintainExecutionLeases();

        assertTrue(session.getActiveExecutionExpiresAt().isAfter(previousExpiry));
        org.mockito.InOrder maintenanceOrder = org.mockito.Mockito.inOrder(sessionRepo);
        maintenanceOrder.verify(sessionRepo).clearExpiredExecutionLeases(any(LocalDateTime.class));
        maintenanceOrder.verify(sessionRepo).findByIdInForUpdate(any());
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void executionLeaseMaintenancePropagatesDurableUserStopFromTheLockedBatch() throws Exception {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        String executionId = service.beginStreamRequest(
                1L, "s1", "turn-stop-batch", "hello");
        session.setExecutionStopRequested(true);
        session.setExecutionUserStopRequested(true);

        service.maintainExecutionLeases();

        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        Object request = ((Map<?, ?>) activeRequests.get(service)).get("s1");
        assertNotNull(request);
        Method stopRequested = request.getClass().getDeclaredMethod("stopRequested");
        Method userStopRequested = request.getClass().getDeclaredMethod("userStopRequested");
        stopRequested.setAccessible(true);
        userStopRequested.setAccessible(true);
        assertTrue(((AtomicBoolean) stopRequested.invoke(request)).get());
        assertTrue(((AtomicBoolean) userStopRequested.invoke(request)).get());
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void executionLeaseMaintenanceRejectsRenewalWhoseCommitReturnsAfterTtl() throws Exception {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        chatExecutionConfig.setLeaseTtlMs(50);
        chatExecutionConfig.setLeaseHeartbeatMs(10);
        transactionTemplate = transactionTemplateWithCommitHook(commit -> {
            if (commit == 2) delayAtLeast(Duration.ofMillis(150));
        });
        service = newService();
        String executionId = service.beginStreamRequest(1L, "s1", "turn-admission", "hello");

        service.maintainExecutionLeases();

        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> requests = (Map<String, ?>) activeRequests.get(service);
        assertFalse(requests.containsKey("s1"));
        assertTrue(session.getActiveExecutionExpiresAt().isBefore(LocalDateTime.now()));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void executionLeaseMaintenanceRejectsWholeBatchThatCannotReachTheNextHeartbeat() throws Exception {
        chatExecutionConfig.setLeaseTtlMs(1_000);
        chatExecutionConfig.setLeaseHeartbeatMs(400);
        transactionTemplate = transactionTemplateWithCommitHook(commit -> {
            if (commit == 3) delayAtLeast(Duration.ofMillis(700));
        });
        service = newService();
        ChatSessionPo firstSession = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        ChatSessionPo secondSession = new ChatSessionPo();
        secondSession.setId("s2");
        secondSession.setUserId(1L);
        when(sessionRepo.findByIdAndUserIdForUpdate("s2", 1L)).thenReturn(Optional.of(secondSession));
        doAnswer(invocation -> {
            Set<String> sessionIds = invocation.getArgument(0);
            return List.of(firstSession, secondSession).stream()
                    .filter(session -> sessionIds.contains(session.getId()))
                    .toList();
        }).when(sessionRepo).findByIdInForUpdate(any());
        String firstExecutionId = service.beginStreamRequest(
                1L, "s1", "turn-admission", "hello");
        String secondExecutionId = service.beginStreamRequest(
                1L, "s2", "turn-admission-2", "hello again");

        service.maintainExecutionLeases();

        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        Map<?, ?> requests = (Map<?, ?>) activeRequests.get(service);
        assertFalse(requests.containsKey("s1"));
        assertFalse(requests.containsKey("s2"));
        verify(sessionRepo).findByIdInForUpdate(
                org.mockito.ArgumentMatchers.argThat(sessionIds ->
                        sessionIds.size() == 2
                                && sessionIds.contains("s1")
                                && sessionIds.contains("s2")));
        service.endStreamRequest(1L, "s1", firstExecutionId);
        service.endStreamRequest(1L, "s2", secondExecutionId);
    }

    @Test
    void executionLeaseMaintenanceDoesNotResurrectLeaseAfterLockWaitExceedsTtl() throws Exception {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        chatExecutionConfig.setLeaseTtlMs(20);
        chatExecutionConfig.setLeaseHeartbeatMs(5);
        LocalDateTime frozenDatabaseTime = LocalDateTime.of(2026, 7, 20, 12, 0);
        when(sessionRepo.currentDatabaseTime()).thenReturn(frozenDatabaseTime);
        String executionId = service.beginStreamRequest(1L, "s1", "turn-admission", "hello");
        LocalDateTime originalExpiry = session.getActiveExecutionExpiresAt();
        doAnswer(invocation -> {
            delayAtLeast(Duration.ofMillis(80));
            return List.of(session);
        }).when(sessionRepo).findByIdInForUpdate(any());

        service.maintainExecutionLeases();

        assertEquals(originalExpiry, session.getActiveExecutionExpiresAt());
        verify(sessionRepo, org.mockito.Mockito.times(1)).saveAndFlush(session);
        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> requests = (Map<String, ?>) activeRequests.get(service);
        assertFalse(requests.containsKey("s1"));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void executionLeaseMaintenanceDropsAStaleLocalRegistrationAfterLeaseLoss() {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        String executionId = service.beginStreamRequest(1L, "s1", "turn-admission", "hello");
        String expiredExecutionId = session.getActiveExecutionId();
        session.setActiveExecutionExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(sessionRepo.clearExpiredExecutionLeases(any(LocalDateTime.class))).thenAnswer(invocation -> {
            session.setActiveExecutionId(null);
            session.setActiveExecutionExpiresAt(null);
            session.setExecutionStopRequested(false);
            session.setExecutionUserStopRequested(false);
            return 1;
        });

        service.maintainExecutionLeases();
        String replacementExecutionId = service.beginStreamRequest(
                1L, "s1", "turn-replacement", "hello again");

        assertTrue(service.getSessionActivity(1L, "s1").isActive());
        assertFalse(expiredExecutionId.equals(session.getActiveExecutionId()));
        service.endStreamRequest(1L, "s1", executionId);
        assertTrue(service.getSessionActivity(1L, "s1").isActive());
        assertEquals(replacementExecutionId, session.getActiveExecutionId());
        service.endStreamRequest(1L, "s1", replacementExecutionId);
    }

    @Test
    void queuedWorkerCannotStartAfterItsExecutionLeaseWasLost() {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        String executionId = service.beginStreamRequest(1L, "s1", "turn-admission", "hello");
        session.setActiveExecutionExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(sessionRepo.clearExpiredExecutionLeases(any(LocalDateTime.class))).thenAnswer(invocation -> {
            session.setActiveExecutionId(null);
            session.setActiveExecutionExpiresAt(null);
            return 1;
        });
        SseEmitter emitter = mock(SseEmitter.class);

        service.maintainExecutionLeases();
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(emitter).complete();
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                message -> message != null && !"user".equals(message.getRole())));
        verifyNoInteractions(llmChatService, aiToolManager);
    }

    @Test
    void queuedWorkerStopsAfterDatabaseCannotConfirmItsLeaseForFullTtl() throws Exception {
        chatExecutionConfig.setLeaseTtlMs(60_000);
        String executionId = service.beginStreamRequest(1L, "s1", "turn-admission", "hello");
        LeaseConfirmationTestSupport.ageExecutionLease(
                service, "activeStreamRequests", "s1", Duration.ofSeconds(61));
        when(sessionRepo.currentDatabaseTime()).thenThrow(new RuntimeException("database unavailable"));
        SseEmitter emitter = mock(SseEmitter.class);

        service.maintainExecutionLeases();
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(emitter).complete();
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                message -> message != null && !"user".equals(message.getRole())));
        verifyNoInteractions(llmChatService, aiToolManager);
    }

    @Test
    void executionControlReadDoesNotRefreshLocalLeaseConfirmation() throws Exception {
        chatExecutionConfig.setLeaseTtlMs(60_000);
        String executionId = service.beginStreamRequest(1L, "s1", "turn-admission", "hello");
        LeaseConfirmationTestSupport.ageExecutionLease(
                service, "activeStreamRequests", "s1", Duration.ofSeconds(61));

        Field activeRequests = ChatServiceImpl.class.getDeclaredField("activeStreamRequests");
        activeRequests.setAccessible(true);
        Object activeRequest = ((Map<?, ?>) activeRequests.get(service)).get("s1");
        Method synchronize = ChatServiceImpl.class.getDeclaredMethod(
                "synchronizeExecutionStop", Long.class, String.class,
                activeRequest.getClass(), AtomicBoolean.class, AtomicBoolean.class, boolean.class);
        synchronize.setAccessible(true);
        assertFalse((Boolean) synchronize.invoke(
                service, 1L, "s1", activeRequest, new AtomicBoolean(), new AtomicBoolean(), true));

        when(sessionRepo.currentDatabaseTime()).thenThrow(new RuntimeException("database unavailable"));
        service.maintainExecutionLeases();
        assertFalse(((Map<?, ?>) activeRequests.get(service)).containsKey("s1"));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenAnotherInstanceStopsDuringPlanning_persistsAndStreamsStoppedTerminal()
            throws Exception {
        ChatServiceImpl otherInstance = newService();
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenAnswer(invocation -> {
            otherInstance.requestStreamStop(1L, "s1", "turn-1");
            return toolCallResult("list_rules", "{}");
        });

        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);
        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "please list rules", emitter);

        verify(aiToolManager, never()).execute(any(), any());
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.STOPPED));
        assertSingleFinalTerminal(frames, "turn-1", ChatExecutionStatus.STOPPED);
        verify(emitter).complete();
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenDurableStopPrecedesEmitterAbort_persistsStoppedAudit() {
        ChatServiceImpl otherInstance = newService();
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        AtomicReference<Consumer<Throwable>> errorCallback = new AtomicReference<>();
        SseEmitter emitter = mock(SseEmitter.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            errorCallback.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onError(any());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenAnswer(invocation -> {
            otherInstance.requestStreamStop(1L, "s1", "turn-1");
            errorCallback.get().accept(new IOException("client aborted after stop acknowledgement"));
            return textResult("stopped");
        });

        String executionId = service.beginStreamRequest(
                1L, "s1", "turn-1", "please list rules");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "please list rules", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.STOPPED
                        && msg.getContent().contains("The user stopped")));
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.DISCONNECTED));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenBrowserDetachesDuringPlanning_continuesAndPersistsTerminal() {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        AtomicReference<Runnable> completionCallback = new AtomicReference<>();
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
            completionCallback.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onCompletion(any());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenAnswer(invocation -> {
            completionCallback.get().run();
            return textResult("planning done");
        });
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("Finished in the background.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        String executionId = service.beginStreamRequest(
                1L, "s1", "turn-1", "continue after I switch conversations");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "continue after I switch conversations", emitter);

        verify(llmChatService).streamReply(anyList(), any(), any());
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL
                        && msg.getContent().contains("Finished in the background.")));
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.DISCONNECTED));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenSseWritesFail_continuesAndPersistsTerminal() throws Exception {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenReturn(textResult("planning done"));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("The detached request still completed.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("browser transport detached"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        String executionId = service.beginStreamRequest(
                1L, "s1", "turn-1", "continue after transport failure");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "continue after transport failure", emitter);

        verify(llmChatService).streamReply(anyList(), any(), any());
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL
                        && msg.getContent().contains("The detached request still completed.")));
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.DISCONNECTED));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenStopCommitsAfterFinalPoll_terminalTransactionPersistsStopped()
            throws Exception {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        AtomicBoolean armStopAtTerminalLock = new AtomicBoolean();
        AtomicBoolean stopAtTerminalLock = new AtomicBoolean();
        when(sessionRepo.findByIdAndUserId("s1", 1L)).thenAnswer(invocation -> {
            if (armStopAtTerminalLock.getAndSet(false)) {
                stopAtTerminalLock.set(true);
            }
            return Optional.of(session);
        });
        when(sessionRepo.findByIdAndUserIdForUpdate("s1", 1L)).thenAnswer(invocation -> {
            if (stopAtTerminalLock.getAndSet(false)) {
                session.setExecutionStopRequested(true);
                session.setExecutionUserStopRequested(true);
            }
            return Optional.of(session);
        });
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions())
                .thenReturn(List.of(toolSpec("board_overview")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("board_overview", "{}"), textResult("done"));
        when(aiToolManager.execute("board_overview", "{}"))
                .thenReturn("{\"devices\":[],\"rules\":[],\"specs\":[],\"edges\":[],"
                        + "\"environmentVariables\":[]}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("The board is empty.");
            armStopAtTerminalLock.set(true);
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(
                1L, "s1", "turn-1", "please inspect the board");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "please inspect the board", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.STOPPED
                        && msg.getContent().contains("The user stopped")));
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.COMPLETED));
        assertSingleFinalTerminal(frames, "turn-1", ChatExecutionStatus.STOPPED);
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenStoppedToolReturns_persistsItsAuthoritativeResultBeforeStopping() {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("list_rules", "{}"));
        when(aiToolManager.execute("list_rules", "{}")).thenAnswer(invocation -> {
            service.requestStreamStop(1L, "s1", "turn-1");
            return "{\"rules\":[]}";
        });

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "please list rules", mock(SseEmitter.class));

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "tool".equals(msg.getRole())
                        && msg.getContent().contains("\\\"rules\\\":[]")));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.STOPPED
                        && msg.getContent().contains("1 step(s) returned usable results")));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenLeaseIsReplacedDuringToolCall_rejectsOldExecutionWrites() {
        ChatSessionPo session = sessionRepo.findByIdAndUserId("s1", 1L).orElseThrow();
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("list_rules", "{}"));
        when(aiToolManager.execute("list_rules", "{}")).thenAnswer(invocation -> {
            session.setActiveExecutionId("replacement-execution");
            session.setActiveExecutionExpiresAt(LocalDateTime.now().plusMinutes(1));
            return "{\"rules\":[]}";
        });

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "please list rules", mock(SseEmitter.class));

        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null && "tool".equals(msg.getRole())));
        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() != null));
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenQueuedRequestWasAlreadyStopped_persistsAuditWithoutCallingModel() {
        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(
                1L, "s1", "turn-1", "please list rules");
        service.requestStreamStop(1L, "s1", "turn-1");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "please list rules", emitter);

        verifyNoInteractions(llmChatService, aiToolManager);
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.STOPPED));
        verify(emitter).complete();
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenUserStopsDuringFinalReply_persistsStoppedInsteadOfCompleted()
            throws Exception {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("list_rules", "{}"))
                .thenReturn(textResult("planning done"));
        when(aiToolManager.execute("list_rules", "{}")).thenReturn("{\"rules\":[]}");
        org.mockito.Mockito.doAnswer(invocation -> {
            service.requestStreamStop(1L, "s1", "turn-1");
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("partial reply");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "please list rules", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.STOPPED));
        assertSingleFinalTerminal(frames, "turn-1", ChatExecutionStatus.STOPPED);
        service.endStreamRequest(1L, "s1", executionId);
    }

    @Test
    void processStreamChat_whenStreamingProviderFails_shouldSendSseErrorFrame() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");

        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        doThrow(ServiceUnavailableException.aiService(new RuntimeException("Invalid UTF-8 middle byte 0xe3")))
                .when(llmChatService).streamReply(anyList(), any(), any());

        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(emitter, org.mockito.Mockito.atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.FAILED
                        && msg.getContent().contains("temporarily unavailable")));
        assertTrue(frames.stream().anyMatch(frame ->
                frame.getError() != null && frame.getError().contains("temporarily unavailable")));
        StreamResponseDto terminal = frames.get(frames.size() - 1);
        assertNotNull(terminal.getTerminal());
        assertEquals("turn-1", terminal.getTerminal().getTurnId());
        assertEquals(ChatExecutionStatus.FAILED, terminal.getTerminal().getExecutionStatus());
    }

    @Test
    void processStreamChat_whenPendingStateReadFails_persistsFailedTerminal() throws Exception {
        when(destructiveActionGuard.pendingContext(1L, "s1"))
                .thenThrow(new BadRequestException("pending state unavailable"));
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && "turn-1".equals(msg.getTurnId())
                        && msg.getExecutionStatus() == ChatExecutionStatus.FAILED));
        verify(destructiveActionGuard).pendingContext(1L, "s1");
        verifyNoInteractions(llmChatService);
        verify(aiToolManager, never()).execute(any(), any());
        assertSingleFinalTerminal(frames, "turn-1", ChatExecutionStatus.FAILED);
    }

    @Test
    void processStreamChat_whenFinalReplyFailsAfterToolResult_persistsPartialAudit() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("list_rules", "{}"))
                .thenReturn(textResult("planning done"));
        when(aiToolManager.execute("list_rules", "{}")).thenReturn("""
                {"rules":[]}
                """);
        doThrow(ServiceUnavailableException.aiService(new RuntimeException("provider unavailable")))
                .when(llmChatService).streamReply(anyList(), any(), any());

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "list rules");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "list rules", mock(SseEmitter.class));

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL
                        && msg.getContent().contains("temporarily unavailable")));
    }

    @Test
    void processStreamChat_whenFinalReplyFailsAfterMutation_doesNotRepeatDeliveredActionReceipt()
            throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("manage_rule")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"))
                .thenReturn(textResult("planning done"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"operation\":\"created\",\"message\":\"ok\"}");
        doThrow(ServiceUnavailableException.aiService(new RuntimeException("provider unavailable")))
                .when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "create a rule");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "create a rule", emitter);

        List<StreamResponseDto.CommandDto> commands = frames.stream()
                .map(StreamResponseDto::getCommand)
                .filter(Objects::nonNull)
                .toList();
        assertEquals(1, commands.size());
        assertEquals("RULES_UPDATED", commands.get(0).getPayload().get("assistantAction"));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL
                        && msg.getContent().contains("affected data was refreshed")
                        && msg.getContent().contains("final AI explanation could not be generated")));
    }

    @Test
    void processStreamChat_whenPendingRefreshCannotBeDelivered_persistsTruthfulReconciliationNotice()
            throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("manage_rule")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"))
                .thenReturn(textResult("planning done"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"operation\":\"created\",\"message\":\"ok\"}");
        doThrow(new IllegalStateException("continuation state unavailable"))
                .when(taskContinuationStore).clear(1L, "s1");
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
            SseEmitter.SseEventBuilder event = invocation.getArgument(0, SseEmitter.SseEventBuilder.class);
            for (ResponseBodyEmitter.DataWithMediaType item : event.build()) {
                if (item.getData() instanceof StreamResponseDto frame && frame.getCommand() != null) {
                    throw new IOException("connection failed while sending refresh");
                }
            }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "create a rule");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "create a rule", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL
                        && msg.getContent().contains("refresh instructions could not be fully delivered")
                        && msg.getContent().contains("Reload and review current state before retrying")
                        && !msg.getContent().contains("client was asked to refresh")));
    }

    @Test
    void processStreamChat_whenMutationResultIsUnavailable_persistsPartialTraceRefreshesAndEndsOnce()
            throws Exception {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("manage_rule")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"resultStatus\":\"RESULT_UNAVAILABLE\",\"resultAvailable\":false,"
                        + "\"mutationMayHaveCommitted\":true,\"message\":\"Refresh before retrying.\"}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("The rule update could not be confirmed.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "update the rule");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "update the rule", emitter);

        ArgumentCaptor<ChatMessagePo> messages = ArgumentCaptor.forClass(ChatMessagePo.class);
        verify(messageRepo, org.mockito.Mockito.atLeastOnce()).saveAndFlush(messages.capture());
        ChatMessagePo terminalMessage = messages.getAllValues().stream()
                .filter(message -> message != null
                        && "assistant".equals(message.getRole())
                        && "turn-1".equals(message.getTurnId()))
                .findFirst()
                .orElseThrow();
        assertEquals(ChatExecutionStatus.PARTIAL, terminalMessage.getExecutionStatus());
        JsonNode persistedTrace = new ObjectMapper().readTree(terminalMessage.getExecutionTraceJson());
        assertTrue(persistedTrace.isArray());
        assertTrue(persistedTrace.findValuesAsText("outcome").contains("RESULT_UNAVAILABLE"));
        assertTrue(frames.stream().anyMatch(frame -> frame.getProgress() != null
                && "TOOL_RESULT".equals(frame.getProgress().getStage())
                && "RESULT_UNAVAILABLE".equals(frame.getProgress().getOutcome())));
        assertTrue(frames.stream().anyMatch(frame -> frame.getCommand() != null
                && "REFRESH_DATA".equals(frame.getCommand().getType())
                && "rule_list".equals(frame.getCommand().getPayload().get("target"))));
        assertSingleFinalTerminal(frames, "turn-1", ChatExecutionStatus.PARTIAL);
    }

    @Test
    void processStreamChat_whenReadResultIsUnavailable_persistsPartialWithoutRequestingARefresh()
            throws Exception {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("board_overview")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("board_overview", "{}"));
        when(aiToolManager.execute("board_overview", "{}"))
                .thenReturn("{\"resultStatus\":\"RESULT_UNAVAILABLE\",\"resultAvailable\":false,"
                        + "\"mutationMayHaveCommitted\":false,\"message\":\"Read timed out.\"}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("The requested board read could not be confirmed.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "inspect the board");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "inspect the board", emitter);

        ArgumentCaptor<ChatMessagePo> messages = ArgumentCaptor.forClass(ChatMessagePo.class);
        verify(messageRepo, org.mockito.Mockito.atLeastOnce()).saveAndFlush(messages.capture());
        ChatMessagePo terminalMessage = messages.getAllValues().stream()
                .filter(message -> message != null
                        && "assistant".equals(message.getRole())
                        && "turn-1".equals(message.getTurnId()))
                .findFirst()
                .orElseThrow();
        assertEquals(ChatExecutionStatus.PARTIAL, terminalMessage.getExecutionStatus());
        JsonNode persistedTrace = new ObjectMapper().readTree(terminalMessage.getExecutionTraceJson());
        assertTrue(persistedTrace.findValuesAsText("outcome").contains("RESULT_UNAVAILABLE"));
        assertTrue(frames.stream()
                .map(StreamResponseDto::getContent)
                .filter(Objects::nonNull)
                .anyMatch(content -> content.contains("not counted as successful")));
        assertFalse(frames.stream().anyMatch(frame -> frame.getCommand() != null));
        assertSingleFinalTerminal(frames, "turn-1", ChatExecutionStatus.PARTIAL);
    }

    @Test
    void processStreamChat_whenToolResultFails_butReplyStreamClosesNormally_persistsFailedAudit() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("list_rules", "{}"))
                .thenReturn(textResult("I could not read the rules."));
        when(aiToolManager.execute("list_rules", "{}")).thenReturn(
                "{\"error\":\"rules unavailable\",\"errorCode\":\"SERVICE_UNAVAILABLE\"}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("The rules could not be loaded.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "请列出规则");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "请列出规则", mock(SseEmitter.class));

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equals(msg.getRole())
                        && msg.getExecutionStatus() == ChatExecutionStatus.FAILED
                        && msg.getContent().contains("1 个步骤失败")));
    }

    @Test
    void processStreamChat_persistsTheSameTurnIdOnUserAndTerminalMessages() {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenReturn(textResult("done"));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("done");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        String executionId = service.beginStreamRequest(1L, "s1", "  turn-1  ", "hello");
        service.processStreamChat(
                1L, "s1", executionId, "  turn-1  ", "hello", mock(SseEmitter.class));

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null && "user".equals(msg.getRole()) && "turn-1".equals(msg.getTurnId())));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null && "assistant".equals(msg.getRole())
                        && "turn-1".equals(msg.getTurnId())
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL));
    }

    @Test
    void processStreamChat_rejectsMissingTurnIdInsteadOfGeneratingOne() {
        String executionId = service.beginStreamRequest(1L, "s1", "turn-admission", "hello");

        assertThrows(BadRequestException.class, () -> service.processStreamChat(
                1L, "s1", executionId, "   ", "hello", mock(SseEmitter.class)));

        verify(messageRepo, never()).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                message -> message != null && !"user".equals(message.getRole())));
        verifyNoInteractions(llmChatService, aiToolManager);
    }

    @Test
    void processStreamChat_whenNormalTerminalPersistenceFails_reportsExplicitErrorWithoutRetry() throws Exception {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenReturn(textResult("done"));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("done");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            ChatMessagePo message = invocation.getArgument(0, ChatMessagePo.class);
            if ("assistant".equals(message.getRole())) {
                throw new IllegalStateException("database unavailable");
            }
            return message;
        }).when(messageRepo).saveAndFlush(any(ChatMessagePo.class));
        AtomicReference<Runnable> completionCallback = new AtomicReference<>();
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);
        org.mockito.Mockito.doAnswer(invocation -> {
            completionCallback.set(invocation.getArgument(0, Runnable.class));
            return null;
        }).when(emitter).onCompletion(any(Runnable.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            completionCallback.get().run();
            return null;
        }).when(emitter).complete();

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(messageRepo, org.mockito.Mockito.times(1)).saveAndFlush(
                org.mockito.ArgumentMatchers.argThat(message ->
                        message != null && "assistant".equals(message.getRole())));
        assertTrue(frames.stream()
                .map(StreamResponseDto::getError)
                .filter(Objects::nonNull)
                .anyMatch(content -> content.contains("could not be saved to conversation history")));
        assertEquals(0L, frames.stream().filter(frame -> frame.getTerminal() != null).count());
        assertNotNull(frames.get(frames.size() - 1).getError());
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void processStreamChat_sendsTerminalOnlyAfterItsTransactionCommitAcknowledgement() throws Exception {
        AtomicBoolean terminalSaveAwaitingCommit = new AtomicBoolean();
        List<String> ordering = new ArrayList<>();
        transactionTemplate = transactionTemplateWithCommitHook(commit -> {
            if (terminalSaveAwaitingCommit.getAndSet(false)) ordering.add("terminal-commit");
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            ChatMessagePo message = invocation.getArgument(0, ChatMessagePo.class);
            if ("assistant".equals(message.getRole()) && "turn-1".equals(message.getTurnId())) {
                terminalSaveAwaitingCommit.set(true);
            }
            return message;
        }).when(messageRepo).saveAndFlush(any(ChatMessagePo.class));
        service = newService();
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenReturn(textResult("done"));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("done");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            SseEmitter.SseEventBuilder event = invocation.getArgument(0, SseEmitter.SseEventBuilder.class);
            for (ResponseBodyEmitter.DataWithMediaType item : event.build()) {
                if (item.getData() instanceof StreamResponseDto frame && frame.getTerminal() != null) {
                    ordering.add("terminal-frame");
                }
            }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        assertEquals(List.of("terminal-commit", "terminal-frame"), ordering);
    }

    @Test
    void processStreamChat_whenTerminalCommitOutcomeIsUnknown_doesNotEmitTerminalProof()
            throws Exception {
        AtomicBoolean terminalSaveAwaitingCommit = new AtomicBoolean();
        transactionTemplate = transactionTemplateWithCommitHook(commit -> {
            if (terminalSaveAwaitingCommit.getAndSet(false)) {
                throw new IllegalStateException("terminal commit acknowledgement lost");
            }
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            ChatMessagePo message = invocation.getArgument(0, ChatMessagePo.class);
            if ("assistant".equals(message.getRole()) && "turn-1".equals(message.getTurnId())) {
                terminalSaveAwaitingCommit.set(true);
            }
            return message;
        }).when(messageRepo).saveAndFlush(any(ChatMessagePo.class));
        service = newService();
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenReturn(textResult("done"));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("done");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        assertEquals(0L, frames.stream().filter(frame -> frame.getTerminal() != null).count());
        assertNotNull(frames.get(frames.size() - 1).getError());
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenExecutionTraceSerializationFails_reportsErrorWithoutSavingTerminal()
            throws Exception {
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList())).thenReturn(textResult("done"));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("done");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        org.mockito.Mockito.doThrow(new JsonProcessingException("serialization failed") { })
                .when(failingObjectMapper).writeValueAsString(any());
        service = newService(failingObjectMapper);

        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(messageRepo, never()).saveAndFlush(
                org.mockito.ArgumentMatchers.argThat(message ->
                        message != null && "assistant".equals(message.getRole())));
        assertTrue(frames.stream()
                .map(StreamResponseDto::getError)
                .filter(Objects::nonNull)
                .anyMatch(content -> content.contains("could not be saved to conversation history")));
        assertEquals(0L, frames.stream().filter(frame -> frame.getTerminal() != null).count());
        assertNotNull(frames.get(frames.size() - 1).getError());
        verify(emitter).complete();
    }

    @Test
    void attachPersistedExecutionTraces_onlyRestoresValidExplicitEvidenceAndClearsFalseCompletion()
            throws Exception {
        StreamResponseDto.ProgressDto execution = new StreamResponseDto.ProgressDto(
                "TOOL_EXECUTION", "list_rules", 1, null, 0, 0, 0, null);
        StreamResponseDto.ProgressDto usableResult = new StreamResponseDto.ProgressDto(
                "TOOL_RESULT", "list_rules", 1, "USABLE", 1, 0, 0, null);
        String validTraceJson = new ObjectMapper().writeValueAsString(List.of(execution, usableResult));
        String unpairedTraceJson = new ObjectMapper().writeValueAsString(List.of(
                new StreamResponseDto.ProgressDto(
                        "TOOL_EXECUTION", "board_overview", 1, null, 0, 0, 0, null),
                usableResult));
        StreamResponseDto.ProgressDto partialScenarioResult = new StreamResponseDto.ProgressDto(
                "TOOL_RESULT", "recommend_scenario", 1, "PARTIAL", 1, 0, 0, null);
        StreamResponseDto.ProgressDto usableScenarioResult = new StreamResponseDto.ProgressDto(
                "TOOL_RESULT", "recommend_scenario", 2, "USABLE", 2, 0, 0, null);
        List<StreamResponseDto.ProgressDto> scenarioPartialPrefix = List.of(
                new StreamResponseDto.ProgressDto(
                        "TOOL_EXECUTION", "recommend_scenario", 1, null, 0, 0, 0, null),
                partialScenarioResult);
        List<StreamResponseDto.ProgressDto> recoveredScenarioTrace = new ArrayList<>(scenarioPartialPrefix);
        recoveredScenarioTrace.add(
                new StreamResponseDto.ProgressDto(
                        "TOOL_EXECUTION", "recommend_scenario", 2, null, 1, 0, 0, null));
        recoveredScenarioTrace.add(usableScenarioResult);
        List<StreamResponseDto.ProgressDto> unresolvedScenarioTrace = new ArrayList<>(scenarioPartialPrefix);
        unresolvedScenarioTrace.add(new StreamResponseDto.ProgressDto(
                "TOOL_EXECUTION", "list_rules", 2, null, 1, 0, 0, null));
        unresolvedScenarioTrace.add(new StreamResponseDto.ProgressDto(
                "TOOL_RESULT", "list_rules", 2, "USABLE", 2, 0, 0, null));
        List<StreamResponseDto.ProgressDto> failedThenUsableTrace = List.of(
                new StreamResponseDto.ProgressDto(
                        "TOOL_EXECUTION", "board_overview", 1, null, 0, 0, 0, null),
                new StreamResponseDto.ProgressDto(
                        "TOOL_RESULT", "board_overview", 1, "FAILED", 0, 1, 0, null),
                new StreamResponseDto.ProgressDto(
                        "TOOL_EXECUTION", "board_overview", 2, null, 0, 1, 0, null),
                new StreamResponseDto.ProgressDto(
                        "TOOL_RESULT", "board_overview", 2, "USABLE", 1, 1, 0, null));

        ChatMessagePo valid = assistantHistoryMessage(1L, ChatExecutionStatus.COMPLETED, validTraceJson);
        ChatMessagePo missing = assistantHistoryMessage(2L, ChatExecutionStatus.COMPLETED, null);
        ChatMessagePo corrupt = assistantHistoryMessage(
                3L, ChatExecutionStatus.COMPLETED, "[{\"stage\":\"TOOL_RESULT\"}]");
        ChatMessagePo unpaired = assistantHistoryMessage(
                4L, ChatExecutionStatus.COMPLETED, unpairedTraceJson);
        ChatMessagePo partial = assistantHistoryMessage(5L, ChatExecutionStatus.PARTIAL, null);
        ChatMessagePo recovered = assistantHistoryMessage(
                6L, ChatExecutionStatus.COMPLETED,
                new ObjectMapper().writeValueAsString(recoveredScenarioTrace));
        ChatMessagePo unresolved = assistantHistoryMessage(
                7L, ChatExecutionStatus.COMPLETED,
                new ObjectMapper().writeValueAsString(unresolvedScenarioTrace));
        ChatMessagePo failedThenUsable = assistantHistoryMessage(
                8L, ChatExecutionStatus.COMPLETED,
                new ObjectMapper().writeValueAsString(failedThenUsableTrace));
        ChatMessageResponseDto validDto = historyDto(ChatExecutionStatus.COMPLETED);
        ChatMessageResponseDto missingDto = historyDto(ChatExecutionStatus.COMPLETED);
        ChatMessageResponseDto corruptDto = historyDto(ChatExecutionStatus.COMPLETED);
        ChatMessageResponseDto unpairedDto = historyDto(ChatExecutionStatus.COMPLETED);
        ChatMessageResponseDto partialDto = historyDto(ChatExecutionStatus.PARTIAL);
        ChatMessageResponseDto recoveredDto = historyDto(ChatExecutionStatus.COMPLETED);
        ChatMessageResponseDto unresolvedDto = historyDto(ChatExecutionStatus.COMPLETED);
        ChatMessageResponseDto failedThenUsableDto = historyDto(ChatExecutionStatus.COMPLETED);

        attachPersistedExecutionTracesMethod.invoke(
                service,
                List.of(valid, missing, corrupt, unpaired, partial, recovered, unresolved, failedThenUsable),
                List.of(validDto, missingDto, corruptDto, unpairedDto, partialDto,
                        recoveredDto, unresolvedDto, failedThenUsableDto));

        assertNotNull(validDto.getExecutionTrace());
        assertEquals("USABLE", validDto.getExecutionTrace().get(1).getOutcome());
        assertEquals(ChatExecutionStatus.COMPLETED, validDto.getExecutionStatus());
        assertNull(missingDto.getExecutionTrace());
        assertNull(missingDto.getExecutionStatus());
        assertNull(corruptDto.getExecutionTrace());
        assertNull(corruptDto.getExecutionStatus());
        assertNotNull(unpairedDto.getExecutionTrace());
        assertNull(unpairedDto.getExecutionStatus());
        assertNull(partialDto.getExecutionTrace());
        assertEquals(ChatExecutionStatus.PARTIAL, partialDto.getExecutionStatus());
        assertEquals(ChatExecutionStatus.COMPLETED, recoveredDto.getExecutionStatus());
        assertEquals("USABLE", recoveredDto.getExecutionTrace().get(3).getOutcome());
        assertNull(unresolvedDto.getExecutionStatus());
        assertNull(failedThenUsableDto.getExecutionStatus());
    }

    @Test
    void beginStreamRequest_whenSessionMissing_shouldRejectBeforeDispatch() {
        when(sessionRepo.findByIdAndUserIdForUpdate("missing", 1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.beginStreamRequest(1L, "missing", "turn-missing", "hello"));
        verifyNoInteractions(aiToolManager);
    }

    @Test
    void processStreamChat_whenToolIntentDetected_shouldStreamVisibleFinalAnswerAfterPlanning() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");

        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("list_rules", "{}"))
                .thenReturn(textResult("planning done"));
        when(aiToolManager.execute("list_rules", "{}")).thenReturn("{\"rules\":[]}");
        AtomicReference<List<LlmMessage>> streamedMessages = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<LlmMessage> messages = invocation.getArgument(0, List.class);
            streamedMessages.set(messages);
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("stream ");
            onDelta.accept("final");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "please list rules", emitter);

        verify(llmChatService).streamReply(anyList(), any(), any());
        verify(emitter, org.mockito.Mockito.atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && "stream final".equals(msg.getContent())
                        && msg.getExecutionStatus() == ChatExecutionStatus.COMPLETED));
        String systemPrompt = streamedMessages.get().get(0).content();
        assertTrue(systemPrompt.contains("Tool executions may already be present"));
        assertTrue(systemPrompt.contains("Do not emit tool-call JSON"));
        assertTrue(systemPrompt.contains("resultAvailable=false"));
        assertTrue(systemPrompt.contains("Do not expose device node ids"));
        assertTrue(systemPrompt.contains("verificationStatus=NOT_VERIFIED"));
        assertTrue(systemPrompt.contains("Reply in the language used by the user's latest message"));
        assertFalse(systemPrompt.contains("Available tools:"));
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenChineseDeletionPreview_shouldLocalizeSafetyNotice() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");

        String arguments = "{\"id\":\"air_conditioner\",\"confirmed\":false}";
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("delete_device", arguments));
        when(aiToolManager.execute("delete_device", arguments))
                .thenReturn("{\"operation\":\"preview\",\"requiresUserConfirmation\":true}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("尚未删除，请确认是否继续。");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "删除设备 Air Conditioner");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "删除设备 Air Conditioner", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent() != null
                        && msg.getContent().startsWith("提示：当前只是未写入的影响预览或备选方案")
                        && msg.getContent().contains("尚未删除，请确认是否继续。")
                        && !msg.getContent().contains("A no-write preview")
                        && msg.getExecutionStatus() == ChatExecutionStatus.AWAITING_CONFIRMATION));
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenNoToolModelClaimsCompletion_persistsPartialOutcome() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");

        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        List<LlmToolSpec> completeCatalog = List.of(
                toolSpec("board_overview"),
                toolSpec("recommend_scenario"),
                toolSpec("add_device"),
                toolSpec("manage_rule"),
                toolSpec("manage_spec"));
        when(aiToolManager.getAllToolDefinitions()).thenReturn(completeCatalog);
        AtomicReference<List<LlmToolSpec>> plannedTools = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<LlmToolSpec> tools = invocation.getArgument(1, List.class);
            plannedTools.set(tools);
            return textResult("No tool is needed.");
        }).when(llmChatService).chatWithTools(anyList(), anyList());
        AtomicReference<List<LlmMessage>> streamedMessages = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<LlmMessage> messages = invocation.getArgument(0, List.class);
            streamedMessages.set(messages);
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("I deleted:\n");
            onDelta.accept("- all current devices.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "hello");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "hello", emitter);

        verify(destructiveActionGuard, never()).clearSession(1L, "s1");
        assertEquals(completeCatalog, plannedTools.get());
        verify(aiToolManager).getAllToolDefinitions();
        verify(llmChatService).chatWithTools(anyList(), eq(completeCatalog));
        verify(llmChatService).streamReply(anyList(), any(), any());
        String systemPrompt = streamedMessages.get().get(0).content();
        assertTrue(systemPrompt.contains("planning stage did not execute a tool"));
        assertTrue(systemPrompt.contains("do not claim to have read current platform data"));
        assertTrue(systemPrompt.contains("Do not emit tool-call JSON"));
        assertFalse(systemPrompt.contains("Available tools:"));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent() != null
                        // The "no platform tool ran" prose was removed: the client's execution-trace badge is the
                        // single owner of that fact. This test's subject is the guard below, which is unaffected.
                        && !msg.getContent().contains("no platform tool ran in this turn")
                        && msg.getContent().contains("The system hid an unverified reply")
                        && !msg.getContent().contains("I deleted:")
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL));
        String streamedContent = frames.stream()
                .map(StreamResponseDto::getContent)
                .filter(Objects::nonNull)
                .reduce("", String::concat);
        assertTrue(streamedContent.contains("The system hid an unverified reply"));
        assertFalse(streamedContent.contains("I deleted:"));
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenNoToolModelClaimsCurrentBoardRead_hidesTheClaim() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(textResult("No tool is needed."));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("Your current Board contains three devices.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "what is on my board?");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "what is on my board?", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent() != null
                        && msg.getContent().contains("The system hid an unverified reply")
                        && !msg.getContent().contains("contains three devices")
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL));
        String streamedContent = frames.stream()
                .map(StreamResponseDto::getContent)
                .filter(Objects::nonNull)
                .reduce("", String::concat);
        assertTrue(streamedContent.contains("The system hid an unverified reply"));
        assertFalse(streamedContent.contains("contains three devices"));
        verify(emitter).complete();
    }

    @Test
    void unsupportedPlatformClaimDetection_isConservativeAndBilingual() {
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The tool result shows that the verification succeeded."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The tool execution result confirms the device was updated."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "According to the tool output, verification succeeded."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The API returned two current devices."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "I deleted every device."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "I deleted the devices."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The devices have been deleted."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "Deleted all devices."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "All devices deleted."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "I've created a sample rule and deleted all devices."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The tool result shows whether verification succeeded, but I deleted all devices."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The devices were deleted during the previous migration, but I deleted every device."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "During the previous migration, the devices were deleted, but I deleted every device."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The tool result shows whether verification succeeded and I deleted all devices."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "工具调用结果显示验证已经完成。"));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "接口已返回当前设备列表。"));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "我已经删除了画布中的设备。"));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "已删除所有设备。"));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "所有设备已删除。"));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "I deleted:\n- all current devices."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "Your current Board contains three devices."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "I checked your Board and found three devices."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "当前画布有三个设备。"));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "在此前迁移中，所有设备已被删除，但是我已经删除了当前设备。"));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "Here is a conceptual explanation of CTL verification."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "Based on the API documentation, this endpoint returns a Result envelope."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "根据接口文档，该端点返回统一响应。"));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The tool result shows whether verification succeeded."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "Does your current Board contain three devices?"));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The devices were deleted during the previous migration."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "During the previous migration, the devices were deleted."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "在此前迁移中，所有设备已被删除。"));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "I've created a sample rule below for discussion."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "I've created a sample rule for your current model."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "我创建了一个示例规则供参考。"));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "我已经创建了适用于当前模型的示例规则。"));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "已删除的设备名称可以在历史记录中作为普通文本讨论。"));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The phrase \"I deleted every device.\" is an example of past tense."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "Translate `I deleted every device.` into Chinese."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "请把“我已经删除了当前设备。”翻译成英文。"));
    }

    @Test
    void unsupportedPlatformClaimDetection_handlesMarkdownLiteralsWithoutCreatingBypasses() {
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The phrase 'I deleted every device.' is an example."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "The phrase 'I\'ve deleted every device.' is an example."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "I've deleted every device."));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "请翻译「我已经删除了当前设备。」和『当前画布有三个设备。』。"));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "~~~text\nI deleted every device.\n~~~~"));
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "```text\nI deleted every device.\n````"));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "```text\nThis fence is not closed.\nI deleted every device."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "I recommend the name \"Lamp. I deleted every device."));
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(
                "```text\nI deleted every device.\n````\nI deleted every device."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I can see three devices on your board.",
            "I see three devices on your current Board.",
            "I've seen three devices on your board.",
            "I found three devices on your board.",
            "I've observed two rules in the current scene.",
            "We listed one specification on the current board.",
            "The current board lists three devices."
    })
    void unsupportedPlatformClaimDetection_catchesDirectCurrentBoardReadVariants(String reply) {
        assertTrue(ChatServiceImpl.containsUnsupportedPlatformClaim(reply));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Can I see three devices on your board?",
            "Did I find three devices on your current board?",
            "I found three devices on your board if the current snapshot is accurate.",
            "Previously, I found three devices on your board.",
            "During the previous migration, I observed two rules in the current scene."
    })
    void unsupportedPlatformClaimDetection_preservesQuestionsAndHistoricalReadContext(String reply) {
        assertFalse(ChatServiceImpl.containsUnsupportedPlatformClaim(reply));
    }

    @Test
    void processStreamChat_whenReadToolSucceedsButModelClaimsMutation_keepsAuthoritativeCompletionAndContinuesReply()
            throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("board_overview")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("board_overview", "{}"), textResult("done"));
        when(aiToolManager.execute("board_overview", "{}"))
                .thenReturn("{\"devices\":[],\"rules\":[],\"specs\":[],\"edges\":[],\"environmentVariables\":[]}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("I deleted every device.");
            onDelta.accept(" The requested review is summarized above.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "inspect the board");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "inspect the board", emitter);

        String authoritativeDetail = frames.stream()
                .map(StreamResponseDto::getProgress)
                .filter(Objects::nonNull)
                .filter(progress -> "TOOL_RESULT".equals(progress.getStage())
                        && "USABLE".equals(progress.getOutcome()))
                .map(StreamResponseDto.ProgressDto::getDetail)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent() != null
                        && msg.getContent().contains(authoritativeDetail)
                        && !msg.getContent().contains("I deleted every device.")
                        && msg.getContent().contains("The requested review is summarized above.")
                        && msg.getExecutionStatus() == ChatExecutionStatus.COMPLETED));
        String streamedContent = frames.stream()
                .map(StreamResponseDto::getContent)
                .filter(Objects::nonNull)
                .reduce("", String::concat);
        assertTrue(streamedContent.contains(authoritativeDetail));
        assertTrue(streamedContent.contains("The requested review is summarized above."));
        assertFalse(streamedContent.contains("I deleted every device."));
        assertSingleFinalTerminal(frames, "turn-1", ChatExecutionStatus.COMPLETED);
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenMutationCapableToolOnlyReads_doesNotTreatItAsMutationEvidence()
            throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("manage_environment")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_environment", "{\"action\":\"list\"}"), textResult("done"));
        when(aiToolManager.execute("manage_environment", "{\"action\":\"list\"}"))
                .thenReturn("{\"operation\":\"listed\",\"environmentVariables\":[]}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("I deleted every device.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "list environment values");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "list environment values", emitter);

        String streamedContent = frames.stream()
                .map(StreamResponseDto::getContent)
                .filter(Objects::nonNull)
                .reduce("", String::concat);
        assertTrue(streamedContent.contains("authoritative tool record"));
        assertFalse(streamedContent.contains("I deleted every device."));
        assertTrue(frames.stream().noneMatch(frame -> frame.getCommand() != null));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent().contains("authoritative tool record")
                        && !msg.getContent().contains("I deleted every device.")
                        && msg.getExecutionStatus() == ChatExecutionStatus.COMPLETED));
    }

    @Test
    void processStreamChat_whenFailedToolModelClaimsMutation_hidesUnsupportedClaim() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("manage_rule")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"), textResult("done"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"error\":\"failed\"}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("I deleted every device.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "delete the rules");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "delete the rules", emitter);

        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent() != null
                        && msg.getContent().contains("The system hid an unverified reply")
                        && !msg.getContent().contains("I deleted every device.")
                        && msg.getExecutionStatus() == ChatExecutionStatus.FAILED));
        String streamedContent = frames.stream()
                .map(StreamResponseDto::getContent)
                .filter(Objects::nonNull)
                .reduce("", String::concat);
        assertTrue(streamedContent.contains("The system hid an unverified reply"));
        assertFalse(streamedContent.contains("I deleted every device."));
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenMutationHasAuthoritativeEvidence_preservesModelSummary() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("manage_rule")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("manage_rule", "{}"), textResult("done"));
        when(aiToolManager.execute("manage_rule", "{}"))
                .thenReturn("{\"operation\":\"created\",\"rule\":{\"description\":\"Turn on the light\"}}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("I created the requested rule.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "create the rule");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "create the rule", emitter);

        String streamedContent = frames.stream()
                .map(StreamResponseDto::getContent)
                .filter(Objects::nonNull)
                .reduce("", String::concat);
        assertTrue(streamedContent.contains("I created the requested rule."));
        assertFalse(streamedContent.contains("system replaced"));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent().contains("I created the requested rule.")
                        && msg.getExecutionStatus() == ChatExecutionStatus.COMPLETED));
    }

    @Test
    void processStreamChat_whenNoToolReplyIsSafe_streamsCompleteSegments() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(textResult("No tool is needed."));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("First safe sentence.");
            onDelta.accept(" Second safe sentence.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "explain CTL");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "explain CTL", emitter);

        List<String> contentFrames = frames.stream()
                .map(StreamResponseDto::getContent)
                .filter(Objects::nonNull)
                .toList();
        assertTrue(contentFrames.contains("First safe sentence."));
        assertTrue(contentFrames.contains(" Second safe sentence."));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent() != null
                        && msg.getContent().contains("First safe sentence. Second safe sentence.")
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL));
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenNoToolReplyQuotesMutationText_preservesTheLiteralReply() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");
        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(textResult("No tool is needed."));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("The phrase \"I deleted every ");
            onDelta.accept("device.\" is an example of past tense.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "explain this phrase");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "explain this phrase", emitter);

        String streamedContent = frames.stream()
                .map(StreamResponseDto::getContent)
                .filter(Objects::nonNull)
                .reduce("", String::concat);
        assertTrue(streamedContent.contains("\"I deleted every device.\""));
        assertFalse(streamedContent.contains("The system hid an unverified reply"));
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent().contains("\"I deleted every device.\"")
                        && !msg.getContent().contains("The system hid an unverified reply")
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL));
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenScenarioDraftMissesCoreParts_persistsPartialOutcome() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");

        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("recommend_scenario")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("recommend_scenario", "{}"), textResult("draft ready"));
        when(aiToolManager.execute("recommend_scenario", "{}"))
                .thenReturn("{\"objectiveStatus\":\"PARTIAL\",\"objectiveIssues\":["
                        + "{\"code\":\"NO_AUTOMATION_RULES\"},{\"code\":\"NO_SPECIFICATIONS\"}],"
                        + "\"scene\":{\"devices\":[{\"id\":\"device_1\"}],\"rules\":[],\"specs\":[]}}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("Review the generated draft.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);
        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "create a scene");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "create a scene", emitter);

        ArgumentCaptor<ChatMessagePo> messages = ArgumentCaptor.forClass(ChatMessagePo.class);
        verify(messageRepo, org.mockito.Mockito.atLeastOnce()).saveAndFlush(messages.capture());
        ChatMessagePo terminalMessage = messages.getAllValues().stream()
                .filter(message -> message != null
                        && "assistant".equalsIgnoreCase(message.getRole())
                        && "turn-1".equals(message.getTurnId()))
                .findFirst()
                .orElseThrow();
        assertEquals(ChatExecutionStatus.PARTIAL, terminalMessage.getExecutionStatus());
        assertTrue(terminalMessage.getContent().contains("Review the generated draft."));
        JsonNode persistedTrace = new ObjectMapper().readTree(terminalMessage.getExecutionTraceJson());
        assertEquals(List.of("PARTIAL"), persistedTrace.findValues("outcome").stream()
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .toList());
        assertSingleFinalTerminal(frames, "turn-1", ChatExecutionStatus.PARTIAL);
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenScenarioPartialIsCorrectedByLaterUsableResult_persistsCompletedEvidence()
            throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");

        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of(toolSpec("recommend_scenario")));
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(
                        toolCallResult("recommend_scenario", "{}"),
                        toolCallResult("recommend_scenario", "{}"),
                        textResult("draft ready"));
        when(aiToolManager.execute("recommend_scenario", "{}"))
                .thenReturn(
                        "{\"objectiveStatus\":\"PARTIAL\",\"scene\":{"
                                + "\"devices\":[{\"id\":\"device_1\"}],\"rules\":[],\"specs\":[]}}",
                        "{\"objectiveStatus\":\"COMPLETE\",\"verificationReady\":true,\"scene\":{"
                                + "\"devices\":[{\"id\":\"device_1\"}],"
                                + "\"rules\":[{\"id\":\"rule_1\"}],"
                                + "\"specs\":[{\"id\":\"spec_1\"}]}}"
                );
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("The revised draft meets the requested targets.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        SseEmitter emitter = mock(SseEmitter.class);
        List<StreamResponseDto> frames = captureSseFrames(emitter);
        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "create a complete scene");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "create a complete scene", emitter);

        assertEquals(List.of("PARTIAL", "USABLE"), frames.stream()
                .map(StreamResponseDto::getProgress)
                .filter(Objects::nonNull)
                .filter(progress -> "TOOL_RESULT".equals(progress.getStage()))
                .map(StreamResponseDto.ProgressDto::getOutcome)
                .toList());
        assertSingleFinalTerminal(frames, "turn-1", ChatExecutionStatus.COMPLETED);
        ArgumentCaptor<ChatMessagePo> messages = ArgumentCaptor.forClass(ChatMessagePo.class);
        verify(messageRepo, org.mockito.Mockito.atLeastOnce()).saveAndFlush(messages.capture());
        ChatMessagePo terminalMessage = messages.getAllValues().stream()
                .filter(message -> message != null
                        && "assistant".equalsIgnoreCase(message.getRole())
                        && "turn-1".equals(message.getTurnId()))
                .findFirst()
                .orElseThrow();
        assertEquals(ChatExecutionStatus.COMPLETED, terminalMessage.getExecutionStatus());
        JsonNode persistedTrace = new ObjectMapper().readTree(terminalMessage.getExecutionTraceJson());
        assertEquals(List.of("PARTIAL", "USABLE"), persistedTrace.findValues("outcome").stream()
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .toList());
        verify(emitter).complete();
    }

    @Test
    void processStreamChat_whenMoreThanFiveRoundsMakeProgress_continuesAndStreamsFinalReply() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");

        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(
                        toolCallResult("list_rules", "{\"page\":1}"),
                        toolCallResult("list_rules", "{\"page\":2}"),
                        toolCallResult("list_rules", "{\"page\":3}"),
                        toolCallResult("list_rules", "{\"page\":4}"),
                        toolCallResult("list_rules", "{\"page\":5}"),
                        toolCallResult("list_rules", "{\"page\":6}"),
                        textResult("planning complete")
                );
        when(aiToolManager.execute(eq("list_rules"), anyString())).thenReturn("{\"rules\":[]}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("All requested rule pages were checked.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules across all pages");
        service.processStreamChat(
                1L, "s1", executionId, "turn-1", "please list rules across all pages", emitter);

        verify(aiToolManager, org.mockito.Mockito.times(6)).execute(eq("list_rules"), anyString());
        verify(llmChatService).streamReply(anyList(), any(), any());
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent() != null
                        && msg.getContent().contains("All requested rule pages were checked.")
                        && !msg.getContent().contains("planning limit")));
        verify(emitter).complete();
    }

    @Test
    void beginStreamRequest_whenHistoryCannotReserveAFullTurn_shouldRejectBeforeClaimingLease() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        when(sessionRepo.findByIdAndUserIdForUpdate("s1", 1L)).thenReturn(Optional.of(session));
        when(messageRepo.countBySessionId("s1")).thenReturn(4_000L);

        ChatHistoryQuotaExceededException error = assertThrows(
                ChatHistoryQuotaExceededException.class,
                () -> service.beginStreamRequest(1L, "s1", "turn-admission", "hello"));

        assertEquals(5_000, error.getMaxMessagesPerSession());
        assertEquals(1_090L, error.getRequiredTurnCapacity());
        assertEquals(null, session.getActiveExecutionId());
    }

    @Test
    void executeToolLoop_whenOnePlanningResponseExceedsToolCallLimit_shouldExecuteNone() throws Exception {
        List<LlmToolCall> calls = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> new LlmToolCall("tc_" + index, "list_rules", "{}"))
                .toList();
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(LlmChatResponse.ofToolCalls(calls));

        Object result = invokeToolLoop(new AtomicBoolean(false), new LinkedHashSet<>());

        Method stopReason = result.getClass().getDeclaredMethod("stopReason");
        stopReason.setAccessible(true);
        assertEquals("EMERGENCY_LIMIT", stopReason.invoke(result).toString());
        verify(aiToolManager, never()).execute(anyString(), anyString());
        verify(messageRepo, never()).saveAndFlush(any());
    }

    @Test
    void processStreamChat_whenConsecutiveRoundsRepeatExactly_stopsDuplicatesAndStillExplains() throws Exception {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("s1");
        session.setUserId(1L);
        session.setTitle("New Chat");

        useSession(session);
        when(messageRepo.findTop80BySessionIdOrderByIdDesc("s1")).thenReturn(List.of());
        when(aiToolManager.getAllToolDefinitions()).thenReturn(List.of());
        when(llmChatService.chatWithTools(anyList(), anyList()))
                .thenReturn(toolCallResult("list_rules", "{}"));
        when(aiToolManager.execute("list_rules", "{}")).thenReturn("{\"rules\":[]}");
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1, Consumer.class);
            onDelta.accept("No rules were found; repeated reads were stopped.");
            return null;
        }).when(llmChatService).streamReply(anyList(), any(), any());

        SseEmitter emitter = mock(SseEmitter.class);

        String executionId = service.beginStreamRequest(1L, "s1", "turn-1", "please list rules");
        service.processStreamChat(1L, "s1", executionId, "turn-1", "please list rules", emitter);

        verify(aiToolManager, org.mockito.Mockito.times(3)).execute("list_rules", "{}");
        verify(llmChatService).streamReply(anyList(), any(), any());
        verify(messageRepo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                msg -> msg != null
                        && "assistant".equalsIgnoreCase(msg.getRole())
                        && msg.getContent() != null
                        && msg.getContent().contains("repeated the exact same tool calls and results")
                        && msg.getContent().contains("No rules were found; repeated reads were stopped.")
                        && !msg.getContent().contains("5-round planning limit")
                        && msg.getExecutionStatus() == ChatExecutionStatus.PARTIAL));
        verify(emitter).complete();
    }

    private void useSession(ChatSessionPo session) {
        when(sessionRepo.findByIdAndUserId("s1", 1L)).thenReturn(Optional.of(session));
        when(sessionRepo.findByIdAndUserIdForUpdate("s1", 1L)).thenReturn(Optional.of(session));
    }

    private List<StreamResponseDto> captureSseFrames(SseEmitter emitter) throws IOException {
        List<StreamResponseDto> frames = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            SseEmitter.SseEventBuilder event = invocation.getArgument(0, SseEmitter.SseEventBuilder.class);
            for (ResponseBodyEmitter.DataWithMediaType item : event.build()) {
                if (item.getData() instanceof StreamResponseDto frame) {
                    frames.add(frame);
                }
            }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        return frames;
    }

    private void assertSingleFinalTerminal(
            List<StreamResponseDto> frames,
            String turnId,
            ChatExecutionStatus executionStatus) {
        assertFalse(frames.isEmpty());
        assertEquals(1L, frames.stream().filter(frame -> frame.getTerminal() != null).count());
        StreamResponseDto terminal = frames.get(frames.size() - 1);
        assertNotNull(terminal.getTerminal());
        assertEquals(turnId, terminal.getTerminal().getTurnId());
        assertEquals(executionStatus, terminal.getTerminal().getExecutionStatus());
    }

    private ChatMessagePo assistantHistoryMessage(
            Long id, ChatExecutionStatus status, String traceJson) {
        ChatMessagePo message = new ChatMessagePo();
        message.setId(id);
        message.setSessionId("s1");
        message.setRole("assistant");
        message.setContent("done");
        message.setExecutionStatus(status);
        message.setExecutionTraceJson(traceJson);
        return message;
    }

    private ChatMessageResponseDto historyDto(ChatExecutionStatus status) {
        ChatMessageResponseDto dto = new ChatMessageResponseDto();
        dto.setSessionId("s1");
        dto.setRole("assistant");
        dto.setContent("done");
        dto.setExecutionStatus(status);
        return dto;
    }

    private Object invokeToolLoop(AtomicBoolean disconnected, Set<StreamResponseDto.CommandDto> commandSet) throws Exception {
        return invokeToolLoop(disconnected, commandSet, mock(SseEmitter.class));
    }

    private Object invokeToolLoop(AtomicBoolean disconnected,
                                  Set<StreamResponseDto.CommandDto> commandSet,
                                  SseEmitter emitter) throws Exception {
        return invokeToolLoop(disconnected, commandSet, emitter, new ArrayList<>());
    }

    private Object invokeToolLoop(AtomicBoolean disconnected,
                                  Set<StreamResponseDto.CommandDto> commandSet,
                                  SseEmitter emitter,
                                  List<LlmMessage> messages) throws Exception {
        return executeToolLoopMethod.invoke(
                service,
                1L,
                "s1",
                messages,
                new ArrayList<LlmToolSpec>(),
                commandSet,
                emitter,
                disconnected
        );
    }

    private LlmChatResponse toolCallResult(String functionName, String argsJson) {
        return LlmChatResponse.ofToolCalls(List.of(new LlmToolCall("tc_1", functionName, argsJson)));
    }

    private LlmChatResponse textResult(String text) {
        return LlmChatResponse.ofText(text);
    }

    private LlmToolSpec toolSpec(String name) {
        return LlmToolSpec.of(name, name,
                new FunctionParameterSchema("object", Map.of(), List.of()));
    }

    private String confirmedRunHistoryMutationResult(String toolName) {
        return switch (toolName) {
            case "verify_model" -> "{\"outcome\":\"SATISFIED\",\"historyPersistence\":{"
                    + "\"status\":\"SAVED\",\"runId\":1},\"message\":\"ok\"}";
            case "verify_model_async", "simulate_model_async", "fuzz_model_async" ->
                    "{\"taskAccepted\":true,\"taskId\":1,\"message\":\"ok\"}";
            case "cancel_verify_task", "cancel_simulate_task", "cancel_fuzz_task" ->
                    "{\"taskId\":1,\"cancellationAccepted\":true,\"message\":\"ok\"}";
            case "delete_trace", "delete_simulation_trace",
                    "delete_verification_run", "delete_fuzz_run" ->
                    "{\"deleted\":true,\"message\":\"ok\"}";
            case "dismiss_verify_task", "dismiss_simulate_task", "dismiss_fuzz_task" ->
                    "{\"dismissed\":true,\"message\":\"ok\"}";
            default -> throw new IllegalArgumentException("Unexpected run-history tool " + toolName);
        };
    }

    private int recordInt(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (Integer) method.invoke(record);
    }

    private boolean recordBoolean(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (Boolean) method.invoke(record);
    }

}
