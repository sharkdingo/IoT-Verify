package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.security.UserContextHolder.BoardWritePermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class AiToolManagerTest {

    @Mock
    private AiTool knownTool;
    @Mock private ChatConfirmationDetector confirmationDetector;

    private ObjectMapper objectMapper;
    private AiToolManager manager;
    private ChatExecutionConfig chatExecutionConfig;

    @BeforeEach
    void setUp() {
        UserContextHolder.clear();
        objectMapper = new ObjectMapper();
        chatExecutionConfig = new ChatExecutionConfig();
        when(knownTool.getName()).thenReturn("known_tool");
        manager = new AiToolManager(List.of(knownTool), objectMapper, chatExecutionConfig, confirmationDetector);
        manager.init();
    }

    @AfterEach
    void clearUserContext() { UserContextHolder.clear(); }

    @Test
    void explicitSearchOnlyTurnCannotEditSavedRulesEvenTemporarily() throws Exception {
        when(knownTool.getName()).thenReturn("manage_rule");
        manager.init();
        String request = "请分析反例，只尝试调整数值参数寻找方案，不要应用方案。";
        UserContextHolder.setChatExecutionId("current-turn");
        UserContextHolder.setChatUserMessage(request);
        when(confirmationDetector.detectBoardWritePermission(request)).thenReturn(BoardWritePermission.DENIED);

        JsonNode first = objectMapper.readTree(manager.execute("manage_rule", "{\"action\":\"update\"}"));
        JsonNode second = objectMapper.readTree(manager.execute("manage_rule", "{\"action\":\"add\"}"));

        assertEquals("BOARD_WRITE_NOT_AUTHORIZED", first.path("errorCode").asText());
        assertEquals(403, first.path("status").asInt());
        assertEquals("BOARD_WRITE_NOT_AUTHORIZED", second.path("errorCode").asText());
        assertFalse(first.has("mutationMayHaveCommitted"));
        verify(knownTool, never()).execute(anyString());
        verify(confirmationDetector).detectBoardWritePermission(request);
    }

    @Test
    void requestedEditsAreAuthorizedOncePerTurnAndCannotLeakToTheNextTurn() throws Exception {
        when(knownTool.getName()).thenReturn("edit_device");
        when(knownTool.execute("{}")).thenReturn("{\"operation\":\"updated\"}");
        manager.init();
        UserContextHolder.setChatExecutionId("first-turn");
        UserContextHolder.setChatUserMessage("请修改设备名称");
        when(confirmationDetector.detectBoardWritePermission("请修改设备名称")).thenReturn(BoardWritePermission.ALLOWED);
        assertEquals("updated", objectMapper.readTree(manager.execute("edit_device", "{}")).path("operation").asText());
        manager.execute("edit_device", "{}");
        verify(confirmationDetector).detectBoardWritePermission("请修改设备名称");

        UserContextHolder.clear();
        UserContextHolder.setChatExecutionId("next-turn");
        UserContextHolder.setChatUserMessage("只检查，不修改画布");
        when(confirmationDetector.detectBoardWritePermission("只检查，不修改画布")).thenReturn(BoardWritePermission.DENIED);
        assertEquals("BOARD_WRITE_NOT_AUTHORIZED", objectMapper.readTree(manager.execute("edit_device", "{}")).path("errorCode").asText());
        verify(knownTool, times(2)).execute("{}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"board_overview", "fix_violation", "verify_model", "simulate_model_async"})
    void readSearchAndSavedRunToolsDoNotNeedBoardWritePermission(String toolName) {
        when(knownTool.getName()).thenReturn(toolName);
        when(knownTool.execute("{}")).thenReturn("{\"count\":0}");
        manager.init();
        UserContextHolder.setChatExecutionId("read-only-turn");
        UserContextHolder.setChatUserMessage("分析并验证，不修改画布");
        manager.execute(toolName, "{}");
        verify(knownTool).execute("{}");
        verify(confirmationDetector, never()).detectBoardWritePermission(anyString());
    }

    @Test
    void historyAvailabilityIsReadOnlyButUndoRequiresCurrentUserPermission() throws Exception {
        when(knownTool.getName()).thenReturn("manage_board_history");
        when(knownTool.execute("{\"action\":\"availability\"}")).thenReturn("{\"operation\":\"availability\"}");
        manager.init();
        UserContextHolder.setChatExecutionId("read-only-turn");
        UserContextHolder.setChatUserMessage("读取操作历史，不要应用修改");
        when(confirmationDetector.detectBoardWritePermission("读取操作历史，不要应用修改")).thenReturn(BoardWritePermission.DENIED);
        manager.execute("manage_board_history", "{\"action\":\"availability\"}");
        assertEquals("BOARD_WRITE_NOT_AUTHORIZED", objectMapper.readTree(manager.execute("manage_board_history", "{\"action\":\"undo\"}")).path("errorCode").asText());
        verify(knownTool).execute("{\"action\":\"availability\"}");
        verify(knownTool, never()).execute("{\"action\":\"undo\"}");
    }

    @Test
    void missingOrUnavailablePermissionDoesNotDispatchAWrite() throws Exception {
        when(knownTool.getName()).thenReturn("manage_rule");
        manager.init();
        UserContextHolder.setChatExecutionId("current-turn");
        when(confirmationDetector.detectBoardWritePermission(null)).thenReturn(BoardWritePermission.UNAVAILABLE);
        JsonNode result = objectMapper.readTree(manager.execute("manage_rule", "{\"action\":\"update\"}"));
        assertEquals("BOARD_WRITE_PERMISSION_UNAVAILABLE", result.path("errorCode").asText());
        verify(knownTool, never()).execute(anyString());
    }

    @Test
    void signedFixPreviewAndExplicitConfirmationKeepTheirOwnTargetGuard() {
        when(knownTool.getName()).thenReturn("apply_fix");
        when(knownTool.execute(anyString())).thenReturn("{\"operation\":\"preview\"}");
        manager.init();
        UserContextHolder.setChatExecutionId("preview-turn");
        UserContextHolder.setChatUserMessage("只展示方案，暂不应用");
        manager.execute("apply_fix", "{\"confirmed\":false}");
        UserContextHolder.setChatExecutionId("confirm-turn");
        UserContextHolder.setChatUserMessage("确认应用刚才那条方案");
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", "exact-confirmation");
        manager.execute("apply_fix", "{\"confirmed\":true}");
        verify(knownTool, times(2)).execute(anyString());
        verify(confirmationDetector, never()).detectBoardWritePermission(anyString());
    }

    @Test
    void execute_unknownTool_shouldReturnValidJsonError() throws Exception {
        String result = manager.execute("unknown\"tool", "{}");

        JsonNode json = objectMapper.readTree(result);
        assertEquals("Unknown function: unknown\"tool", json.path("error").asText());
        assertEquals("TOOL_NOT_FOUND", json.path("errorCode").asText());
        assertEquals(404, json.path("status").asInt());
    }

    @Test
    void execute_knownTool_shouldDelegateToTool() {
        when(knownTool.execute("{\"x\":1}")).thenReturn("{\"ok\":true}");

        String result = manager.execute("known_tool", "{\"x\":1}");

        assertTrue(result.contains("\"ok\":true"));
        verify(knownTool).execute("{\"x\":1}");
    }

    @Test
    void execute_successAndPreview_shouldAttachExplicitExecutionEvidence() throws Exception {
        when(knownTool.execute("{}"))
                .thenReturn("{\"count\":0}")
                .thenReturn("{\"operation\":\"preview\",\"requiresUserConfirmation\":true}");

        JsonNode success = objectMapper.readTree(manager.execute("known_tool", "{}"));
        JsonNode preview = objectMapper.readTree(manager.execute("known_tool", "{}"));

        assertEquals("SUCCESS", success.path("resultStatus").asText());
        assertTrue(success.path("resultAvailable").asBoolean());
        assertEquals("PREVIEW", preview.path("resultStatus").asText());
        assertTrue(preview.path("resultAvailable").asBoolean());
    }

    @Test
    void execute_oversizedReadOnlyResult_shouldReturnBoundedUnavailableResult() throws Exception {
        chatExecutionConfig.setMaxToolResultBytes(4096);
        when(knownTool.execute("{}"))
                .thenReturn("{\"payload\":\"" + "x".repeat(5000) + "\"}");

        JsonNode result = objectMapper.readTree(manager.execute("known_tool", "{}"));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertEquals("TOOL_RESULT_TOO_LARGE", result.path("errorCode").asText());
        assertEquals(false, result.path("mutationMayHaveCommitted").asBoolean());
        assertEquals(4096, result.path("maxResultBytes").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"get_trace", "get_simulation_trace", "get_fuzz_finding"})
    void statePagesFitTheByteBudgetWithoutLosingStatesOrContext(String toolName) throws Exception {
        chatExecutionConfig.setMaxToolResultBytes(6000);
        when(knownTool.getName()).thenReturn(toolName);
        manager.init();
        ObjectNode full = statePage();
        if (toolName.equals("get_fuzz_finding")) {
            full.put("inputEventCount", 5);
            full.put("returnedInputEventCount", 3);
            for (int step = 2; step < 5; step++) {
                full.withArray("inputEvents").addObject().put("step", step).put("value", "active");
            }
        }
        String original = objectMapper.writeValueAsString(full);
        assertTrue(original.length() < 6000, "character counts would incorrectly admit this page");
        assertTrue(original.getBytes(StandardCharsets.UTF_8).length > 6000);
        when(knownTool.execute("{}"))
                .thenReturn(original)
                .thenReturn(objectMapper.writeValueAsString(remainingPage(full, 3)))
                .thenReturn(objectMapper.writeValueAsString(remainingPage(full, 4)));

        ArrayNode restored = objectMapper.createArrayNode();
        for (int offset = 2; offset < 5; offset++) {
            String serialized = manager.execute(toolName, "{}");
            JsonNode page = objectMapper.readTree(serialized);
            assertTrue(serialized.getBytes(StandardCharsets.UTF_8).length <= 6000);
            assertEquals("SUCCESS", page.path("resultStatus").asText());
            assertTrue(page.path("resultAvailable").asBoolean());
            assertEquals(5, page.path("stateCount").asInt());
            assertEquals(offset, page.path("stateOffset").asInt());
            assertEquals(10, page.path("stateLimit").asInt());
            assertEquals(1, page.path("returnedStateCount").asInt());
            assertEquals(full.path("modelSnapshot"), page.path("modelSnapshot"));
            assertEquals(full.path("modelSemantics"), page.path("modelSemantics"));
            assertEquals(offset < 4, page.path("hasMoreStates").asBoolean());
            if (offset < 4) assertEquals(offset + 1, page.path("nextStateOffset").asInt());
            else assertFalse(page.has("nextStateOffset"));
            if (toolName.equals("get_fuzz_finding")) {
                assertEquals(5, page.path("inputEventCount").asInt());
                assertEquals(1, page.path("returnedInputEventCount").asInt());
                assertEquals(offset, page.path("inputEvents").get(0).path("step").asInt());
            }
            restored.add(page.path("states").get(0));
        }

        assertEquals(full.path("states"), restored, "every complete state and loop marker remains reachable");
        verify(knownTool, times(3)).execute("{}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"singleState", "oversizedContext", "invalidCount", "overflowOffset"})
    void statePagingDoesNotManufactureSuccessWhenNoValidCompletePageFits(String kind) throws Exception {
        chatExecutionConfig.setMaxToolResultBytes(4096);
        when(knownTool.getName()).thenReturn("get_simulation_trace");
        manager.init();
        ObjectNode page = statePage();
        switch (kind) {
            case "singleState" -> {
                page.withArray("states").remove(2);
                page.withArray("states").remove(1);
                ((ObjectNode) page.path("states").get(0)).put("largeValue", "x".repeat(5000));
                page.put("returnedStateCount", 1);
            }
            case "oversizedContext" -> page.withObject("modelSnapshot").put("description", "x".repeat(5000));
            case "invalidCount" -> page.put("returnedStateCount", 1);
            case "overflowOffset" -> page.put("stateOffset", Integer.MAX_VALUE);
            default -> throw new AssertionError(kind);
        }
        when(knownTool.execute("{}")).thenReturn(objectMapper.writeValueAsString(page));

        JsonNode result = objectMapper.readTree(manager.execute("get_simulation_trace", "{}"));

        assertEquals("TOOL_RESULT_TOO_LARGE", result.path("errorCode").asText());
        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertFalse(result.path("resultAvailable").asBoolean(true));
        assertFalse(result.path("mutationMayHaveCommitted").asBoolean(true));
        verify(knownTool).execute("{}");
    }

    private ObjectNode statePage() {
        ObjectNode page = objectMapper.createObjectNode();
        page.put("stateCount", 5);
        page.put("stateOffset", 2);
        page.put("stateLimit", 10);
        page.put("returnedStateCount", 3);
        page.put("hasMoreStates", false);
        page.putObject("modelSnapshot").put("deviceCount", 14).put("fingerprint", "frozen model");
        page.putObject("modelSemantics").put("sharedValues", "one value per name");
        for (int index = 3; index <= 5; index++) {
            ObjectNode state = page.withArray("states").addObject().put("stateIndex", index);
            if (index == 3) state.put("loopStart", true);
            if (index == 5) state.put("loopBack", true);
            for (int device = 0; device < 14; device++) {
                state.withArray("devices").addObject()
                        .put("deviceLabel", "照护".repeat(40) + device).put("state", "on");
            }
        }
        return page;
    }

    private ObjectNode remainingPage(ObjectNode full, int offset) {
        ObjectNode page = full.deepCopy();
        for (int index = 2; index < offset; index++) page.withArray("states").remove(0);
        page.put("stateOffset", offset);
        page.put("returnedStateCount", 5 - offset);
        if (page.has("inputEvents")) {
            for (int index = 2; index < offset; index++) page.withArray("inputEvents").remove(0);
            page.put("returnedInputEventCount", 5 - offset);
        }
        return page;
    }

    @Test
    void execute_oversizedAnalysisAndNonPersistentSimulationResultsRemainReadOnly() throws Exception {
        chatExecutionConfig.setMaxToolResultBytes(4096);
        for (String toolName : List.of("fix_violation", "simulate_model")) {
            org.mockito.Mockito.reset(knownTool);
            when(knownTool.getName()).thenReturn(toolName);
            when(knownTool.execute("{}"))
                    .thenReturn("{\"payload\":\"" + "x".repeat(5000) + "\"}");
            manager = new AiToolManager(List.of(knownTool), objectMapper, chatExecutionConfig, confirmationDetector);
            manager.init();

            JsonNode result = objectMapper.readTree(manager.execute(toolName, "{}"));

            assertEquals(false, result.path("mutationMayHaveCommitted").asBoolean(), toolName);
        }
    }

    @Test
    void execute_oversizedApplyFixResult_shouldReportThatMutationMayHaveCommitted() throws Exception {
        chatExecutionConfig.setMaxToolResultBytes(4096);
        when(knownTool.getName()).thenReturn("apply_fix");
        manager = new AiToolManager(List.of(knownTool), objectMapper, chatExecutionConfig, confirmationDetector);
        manager.init();
        when(knownTool.execute("{}"))
                .thenReturn("{\"payload\":\"" + "x".repeat(5000) + "\"}");

        JsonNode result = objectMapper.readTree(manager.execute("apply_fix", "{}"));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertEquals("TOOL_RESULT_TOO_LARGE", result.path("errorCode").asText());
        assertTrue(result.path("mutationMayHaveCommitted").asBoolean());
    }

    @Test
    void execute_wrappedInterrupt_isAlsoPreserved() throws Exception {
        // `AiTool.execute` declares no checked exceptions, so an interruption can only reach this
        // catch wrapped in a RuntimeException — which is precisely the shape that a plain
        // `instanceof InterruptedException` check would miss. A tool delegating to synchronous
        // verification or simulation runs interruptible work; swallowing the flag here would leave a
        // later interruptible call on this same chat thread unable to see the cancellation.
        when(knownTool.execute("{}"))
                .thenThrow(new RuntimeException("wrapped", new InterruptedException("cancelled")));

        JsonNode result = objectMapper.readTree(manager.execute("known_tool", "{}"));

        assertTrue(Thread.currentThread().isInterrupted(),
                "the interrupt must survive the broad catch");
        // The failure is still reported as a failure, never as an empty success.
        assertEquals("TOOL_EXECUTION_ERROR", result.path("errorCode").asText());
        Thread.interrupted();
    }

    @Test
    void execute_ordinaryFailure_doesNotFabricateAnInterrupt() throws Exception {
        // A transient tool error must stay an ordinary failure; marking it as cancellation would
        // abort work the user is still waiting for.
        when(knownTool.execute("{}")).thenThrow(new IllegalStateException("boom"));

        manager.execute("known_tool", "{}");

        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void execute_mutationToolFailure_shouldReportUnknownOutcome() throws Exception {
        when(knownTool.getName()).thenReturn("manage_rule");
        manager = new AiToolManager(List.of(knownTool), objectMapper, chatExecutionConfig, confirmationDetector);
        manager.init();
        when(knownTool.execute("{}"))
                .thenThrow(new IllegalStateException("failed after an unknown execution point"));

        JsonNode result = objectMapper.readTree(manager.execute("manage_rule", "{}"));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertEquals(false, result.path("resultAvailable").asBoolean());
        assertTrue(result.path("mutationMayHaveCommitted").asBoolean());
        assertEquals("TOOL_EXECUTION_OUTCOME_UNKNOWN", result.path("errorCode").asText());
        assertTrue(result.path("message").asText().contains("refresh current state"));
    }

    @Test
    void execute_unusableMutationToolResult_shouldReportUnknownOutcome() throws Exception {
        for (String unusableResult : java.util.Arrays.asList(null, "", "not json", "[]", "{}")) {
            org.mockito.Mockito.reset(knownTool);
            when(knownTool.getName()).thenReturn("manage_rule");
            when(knownTool.execute("{}")).thenReturn(unusableResult);
            manager = new AiToolManager(List.of(knownTool), objectMapper, chatExecutionConfig, confirmationDetector);
            manager.init();

            JsonNode result = objectMapper.readTree(manager.execute("manage_rule", "{}"));

            assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText(),
                    String.valueOf(unusableResult));
            assertTrue(result.path("mutationMayHaveCommitted").asBoolean(),
                    String.valueOf(unusableResult));
            assertEquals("TOOL_RESULT_MALFORMED", result.path("errorCode").asText(),
                    String.valueOf(unusableResult));
        }
    }

    @Test
    void execute_messageOnlyMutationResult_shouldReportUnknownOutcome() throws Exception {
        when(knownTool.getName()).thenReturn("manage_rule");
        manager = new AiToolManager(List.of(knownTool), objectMapper, chatExecutionConfig, confirmationDetector);
        manager.init();
        when(knownTool.execute("{}")).thenReturn("{\"message\":\"Rule updated successfully.\"}");

        JsonNode result = objectMapper.readTree(manager.execute("manage_rule", "{}"));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertFalse(result.path("resultAvailable").asBoolean(true));
        assertTrue(result.path("mutationMayHaveCommitted").asBoolean());
        assertEquals("TOOL_RESULT_MALFORMED", result.path("errorCode").asText());
        assertTrue(result.path("message").asText().contains("authoritative completion marker"));
    }

    @Test
    void execute_blankOrMalformedErrorCodeCannotBypassMutationResultContract() throws Exception {
        when(knownTool.getName()).thenReturn("manage_rule");
        for (String malformedResult : List.of(
                "{\"message\":\"done\",\"errorCode\":\"\"}",
                "{\"message\":\"done\",\"errorCode\":123}",
                "{\"operation\":\"created\",\"errorCode\":123}")) {
            org.mockito.Mockito.reset(knownTool);
            when(knownTool.getName()).thenReturn("manage_rule");
            when(knownTool.execute("{}")).thenReturn(malformedResult);
            manager = new AiToolManager(List.of(knownTool), objectMapper, chatExecutionConfig, confirmationDetector);
            manager.init();

            JsonNode result = objectMapper.readTree(manager.execute("manage_rule", "{}"));

            assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText(), malformedResult);
            assertEquals("TOOL_RESULT_MALFORMED", result.path("errorCode").asText(), malformedResult);
            assertTrue(result.path("mutationMayHaveCommitted").asBoolean(), malformedResult);
        }
    }
}
