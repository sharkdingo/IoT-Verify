package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.component.aitool.board.ClearBoardTool;
import cn.edu.nju.Iot_Verify.component.aitool.board.ManageBoardHistoryTool;
import cn.edu.nju.Iot_Verify.component.aitool.task.ListAsyncTasksTool;
import cn.edu.nju.Iot_Verify.component.aitool.verification.GetVerificationRunTool;
import cn.edu.nju.Iot_Verify.component.aitool.verification.ListVerificationRunsTool;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEditHistoryClearPreviewDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardReplacementPreviewDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardUndoResultDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.SpecResultDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskSummaryDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCapabilityClosureToolsTest {

    @Mock VerificationService verificationService;
    @Mock SimulationService simulationService;
    @Mock FuzzService fuzzService;
    @Mock BoardStorageService boardStorageService;
    @Mock AiDestructiveActionGuard destructiveActionGuard;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        UserContextHolder.setUserId(7L);
        UserContextHolder.setChatSessionId("closure-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void listAsyncTasks_discoversAndFiltersTasksAcrossAllThreeRunners() throws Exception {
        when(verificationService.getTasks(7L, List.of())).thenReturn(List.of(
                VerificationTaskSummaryDto.builder().id(11L).initiator(RunInitiator.AI_ASSISTANT)
                        .status("RUNNING").progress(40).createdAt(LocalDateTime.of(2026, 7, 30, 9, 0)).build()));
        when(simulationService.getTasks(7L, List.of())).thenReturn(List.of(
                SimulationTaskSummaryDto.builder().id(12L).initiator(RunInitiator.USER)
                        .status("FAILED").progress(100).createdAt(LocalDateTime.of(2026, 7, 30, 10, 0)).build()));
        when(fuzzService.getTasks(7L, List.of(), 0, 100)).thenReturn(List.of(
                FuzzTaskSummaryDto.builder().id(13L).initiator(RunInitiator.AI_ASSISTANT)
                        .status("RUNNING").progress(20).createdAt(LocalDateTime.of(2026, 7, 30, 11, 0)).build()));

        JsonNode result = objectMapper.readTree(new ListAsyncTasksTool(
                verificationService, simulationService, fuzzService, objectMapper)
                .execute("{\"status\":\"RUNNING\",\"initiator\":\"AI_ASSISTANT\"}"));

        assertEquals(2, result.path("count").asInt());
        assertEquals(13L, result.path("tasks").get(0).path("taskId").asLong());
        assertEquals("counterexample_search", result.path("tasks").get(0).path("kind").asText());
        assertEquals(11L, result.path("tasks").get(1).path("taskId").asLong());
    }

    @Test
    void listAsyncTasks_keepsCompletedResultsInRunHistory() throws Exception {
        JsonNode result = objectMapper.readTree(new ListAsyncTasksTool(
                verificationService, simulationService, fuzzService, objectMapper)
                .execute("{\"status\":\"COMPLETED\"}"));

        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
    }

    @Test
    void verificationRunTools_includeSatisfiedRunsAndPerSpecDetailsWithoutRawOutput() throws Exception {
        VerificationRunSummaryDto summary = VerificationRunSummaryDto.builder()
                .id(21L).initiator(RunInitiator.USER).outcome(
                        cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome.SATISFIED)
                .modelComplete(true).violatedSpecCount(0).counterexampleCount(0)
                .dataAvailable(true).build();
        when(verificationService.getRuns(7L)).thenReturn(List.of(summary));
        VerificationRunDto detail = VerificationRunDto.builder()
                .id(21L).initiator(RunInitiator.USER).outcome(
                        cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome.SATISFIED)
                .modelComplete(true).specResults(List.of(SpecResultDto.builder()
                        .specId("internal-spec-id")
                        .templateId("internal-template-id")
                        .specificationLabel("Door remains closed")
                        .build())).checkLogs(List.of("checked"))
                .nusmvOutput("large internal output").build();
        when(verificationService.getRun(7L, 21L)).thenReturn(detail);

        JsonNode listed = objectMapper.readTree(
                new ListVerificationRunsTool(verificationService, objectMapper).execute("{}"));
        JsonNode loaded = objectMapper.readTree(
                new GetVerificationRunTool(verificationService, objectMapper)
                        .execute("{\"runId\":21}"));

        assertEquals("SATISFIED", listed.path("runs").get(0).path("outcome").asText());
        assertEquals(0, listed.path("runs").get(0).path("counterexampleCount").asInt());
        String interpretation = listed.path("interpretation").asText();
        assertTrue(interpretation.contains("formula outcomes only"));
        assertTrue(interpretation.contains("omit response-trigger reachability"));
        assertTrue(interpretation.contains("SATISFIED does not establish preserved functionality"));
        assertTrue(interpretation.contains("get_verification_run"));
        assertTrue(new ListVerificationRunsTool(verificationService, objectMapper)
                .getDefinition().description().contains("omit response-trigger reachability"));
        assertEquals(1, loaded.path("checkLogCount").asInt());
        assertTrue(loaded.path("specResults").isArray());
        assertFalse(loaded.path("specResults").get(0).has("specId"));
        assertFalse(loaded.path("specResults").get(0).has("templateId"));
        assertFalse(loaded.has("nusmvOutput"));
    }

    @Test
    void verificationDetailCarriesExactAvailableAndUnavailableTraceReferences() throws Exception {
        var available = cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto.builder()
                .id(91L).verificationTaskId(21L).stateCount(4).dataAvailable(true).build();
        var unavailable = cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto.builder()
                .id(92L).verificationTaskId(21L).dataAvailable(false).build();
        when(verificationService.getRun(7L, 21L)).thenReturn(VerificationRunDto.builder()
                .id(21L).counterexampleCount(1).counterexamples(List.of(available, unavailable)).build());

        JsonNode loaded = objectMapper.readTree(new GetVerificationRunTool(verificationService, objectMapper)
                .execute("{\"runId\":21}"));

        assertEquals(21L, loaded.path("runId").asLong());
        assertEquals(91L, loaded.path("counterexamples").get(0).path("traceId").asLong());
        assertEquals(21L, loaded.path("counterexamples").get(0).path("runId").asLong());
        assertEquals(4, loaded.path("counterexamples").get(0).path("stateCount").asInt());
        assertEquals(92L, loaded.path("counterexamples").get(1).path("traceId").asLong());
        assertFalse(loaded.path("counterexamples").get(1).path("dataAvailable").asBoolean());
        assertFalse(loaded.path("counterexamples").get(1).has("stateCount"));
        assertFalse(loaded.path("counterexamples").get(1).has("violatedSpecification"));
    }

    @Test
    void manageBoardHistory_returnsExactAppliedEditAndAvailability() throws Exception {
        when(boardStorageService.undoLastEdit(7L)).thenReturn(new BoardUndoResultDto(
                true, "RULE", "CREATE", "UNDO_CREATE", List.of(), List.of(),
                List.of(), List.of(), false, true));

        JsonNode result = objectMapper.readTree(
                new ManageBoardHistoryTool(boardStorageService, destructiveActionGuard, objectMapper)
                        .execute("{\"action\":\"undo\"}"));

        assertEquals("undone", result.path("operation").asText());
        assertTrue(result.path("applied").asBoolean());
        assertEquals("RULE", result.path("entityType").asText());
        assertFalse(result.path("canUndo").asBoolean());
        assertTrue(result.path("canRedo").asBoolean());
    }

    @Test
    void manageBoardHistory_clearsOnlyTheConfirmedExactJournalPreview() throws Exception {
        BoardEditHistoryClearPreviewDto preview = BoardEditHistoryClearPreviewDto.builder()
                .impactToken("domain-history-token")
                .entryCount(4)
                .canUndo(true)
                .canRedo(false)
                .build();
        when(boardStorageService.previewBoardEditHistoryClear(7L)).thenReturn(preview);
        when(destructiveActionGuard.issue(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("manage_board_history"),
                org.mockito.ArgumentMatchers.eq("board-edit-history"),
                org.mockito.ArgumentMatchers.eq("Undo/redo history"),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq("domain-history-token")))
                .thenReturn("history-confirmation-token");
        ManageBoardHistoryTool tool = new ManageBoardHistoryTool(
                boardStorageService, destructiveActionGuard, objectMapper);

        JsonNode previewResult = objectMapper.readTree(
                tool.execute("{\"action\":\"clear\",\"confirmed\":false}"));

        assertEquals("clear_preview", previewResult.path("operation").asText());
        assertEquals(4, previewResult.path("entryCount").asInt());
        assertTrue(previewResult.path("requiresUserConfirmation").asBoolean());
        verify(boardStorageService, never()).clearBoardEditHistory(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", "history-confirmation-token");
        when(destructiveActionGuard.consume(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("manage_board_history"),
                org.mockito.ArgumentMatchers.eq("board-edit-history"),
                org.mockito.ArgumentMatchers.eq("history-confirmation-token"),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(new AiDestructiveActionGuard.ConsumeResult(
                        true, null, null, "domain-history-token", null));
        when(boardStorageService.clearBoardEditHistory(7L, "domain-history-token"))
                .thenReturn(new BoardUndoResultDto(
                        false, null, null, "HISTORY_CLEARED", List.of(), List.of(),
                        List.of(), List.of(), false, false));

        JsonNode cleared = objectMapper.readTree(tool.execute(
                "{\"action\":\"clear\",\"confirmed\":true,"
                        + "\"impactToken\":\"history-confirmation-token\"}"));

        assertEquals("history_cleared", cleared.path("operation").asText());
        assertEquals(4, cleared.path("clearedEntryCount").asInt());
        assertFalse(cleared.path("canUndo").asBoolean());
        assertFalse(cleared.path("canRedo").asBoolean());
        verify(boardStorageService).clearBoardEditHistory(7L, "domain-history-token");
    }

    @Test
    void clearBoard_requiresPreviewThenUsesTheConfirmedDomainTokenForAtomicReplacement() throws Exception {
        BoardReplacementPreviewDto preview = BoardReplacementPreviewDto.builder()
                .impactToken("domain-token").deviceCount(2).environmentVariableCount(1)
                .ruleCount(3).specificationCount(1).editHistoryEntryCount(4).build();
        when(boardStorageService.previewBoardReplacement(7L)).thenReturn(preview);
        when(destructiveActionGuard.issue(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("clear_board"),
                org.mockito.ArgumentMatchers.eq("current-board"),
                org.mockito.ArgumentMatchers.eq("Whole Board"),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq("domain-token")))
                .thenReturn("confirmation-token");
        ClearBoardTool tool = new ClearBoardTool(
                boardStorageService, destructiveActionGuard, objectMapper);

        JsonNode previewResult = objectMapper.readTree(tool.execute("{\"confirmed\":false}"));

        assertEquals("preview", previewResult.path("operation").asText());
        assertTrue(previewResult.path("requiresUserConfirmation").asBoolean());
        assertEquals(2, previewResult.path("wouldRemoveDeviceCount").asInt());
        verify(boardStorageService, never()).saveBoardBatch(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", "confirmation-token");
        when(destructiveActionGuard.consume(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("clear_board"),
                org.mockito.ArgumentMatchers.eq("current-board"),
                org.mockito.ArgumentMatchers.eq("confirmation-token"),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(new AiDestructiveActionGuard.ConsumeResult(
                        true, null, null, "domain-token", null));

        JsonNode cleared = objectMapper.readTree(tool.execute(
                "{\"confirmed\":true,\"impactToken\":\"confirmation-token\"}"));

        assertEquals("cleared", cleared.path("operation").asText());
        ArgumentCaptor<BoardBatchDto> batch = ArgumentCaptor.forClass(BoardBatchDto.class);
        verify(boardStorageService).saveBoardBatch(org.mockito.ArgumentMatchers.eq(7L), batch.capture());
        assertTrue(batch.getValue().getNodes().isEmpty());
        assertTrue(batch.getValue().getEnvironmentVariables().isEmpty());
        assertTrue(batch.getValue().getRules().isEmpty());
        assertTrue(batch.getValue().getSpecs().isEmpty());
        assertEquals("domain-token", batch.getValue().getImpactToken());
    }
}
