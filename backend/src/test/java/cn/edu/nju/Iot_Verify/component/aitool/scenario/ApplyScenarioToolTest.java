package cn.edu.nju.Iot_Verify.component.aitool.scenario;

import cn.edu.nju.Iot_Verify.component.board.PortableSceneBatchMapper;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardReplacementPreviewDto;
import cn.edu.nju.Iot_Verify.exception.BoardReplacementStaleException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.exception.BoardContentConflictException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplyScenarioToolTest {

    @Mock
    private PortableSceneBatchMapper batchMapper;
    @Mock
    private BoardStorageService boardStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiScenarioDraftStore draftStore;
    private ApplyScenarioTool tool;

    @BeforeEach
    void setUp() throws Exception {
        draftStore = new AiScenarioDraftStore();
        tool = new ApplyScenarioTool(draftStore, batchMapper, boardStorageService, objectMapper);
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("session-1");
        draftStore.saveDraft(1L, "session-1", "Night safety", scene());
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void execute_previewsThenAtomicallyAppliesOnlyAfterLaterExplicitConfirmation() throws Exception {
        BoardReplacementPreviewDto preview = preview("board-v1", 4, 2, 3, 5);
        when(boardStorageService.previewBoardReplacement(1L)).thenReturn(preview);

        JsonNode first = objectMapper.readTree(tool.execute("{\"confirmed\":false}"));

        assertTrue(first.path("requiresUserConfirmation").asBoolean());
        assertEquals("preview", first.path("operation").asText());
        assertEquals(4, first.path("currentDeviceCount").asInt());
        assertEquals(7, first.path("editHistoryEntryCount").asInt());
        assertEquals(1, first.path("replacementDeviceCount").asInt());
        assertEquals(scene(), first.path("replacementScene"));
        assertFalse(first.path("verificationReady").asBoolean());
        assertEquals("NO_SPECIFICATIONS", first.path("readinessIssues").get(0).path("code").asText());
        verify(boardStorageService, never()).saveBoardBatch(any(), any(), any());

        BoardBatchDto request = new BoardBatchDto();
        BoardBatchDto saved = savedBatch();
        when(batchMapper.toBatch(any(), eq("board-v1"), eq("chat_scene_spec_"))).thenReturn(request);
        doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return saved;
        }).when(boardStorageService).saveBoardBatch(eq(1L), eq(request), any());
        confirmCurrentScenario();

        JsonNode applied = objectMapper.readTree(tool.execute("{\"confirmed\":false}"));

        assertEquals("replaced", applied.path("operation").asText());
        assertEquals("NOT_VERIFIED", applied.path("verificationStatus").asText());
        assertFalse(applied.path("verificationReady").asBoolean());
        assertEquals(1, applied.path("deviceCount").asInt());
        assertFalse(draftStore.latestDraft(1L, "session-1").isPresent());
        verify(boardStorageService).saveBoardBatch(eq(1L), eq(request), any());
    }

    @Test
    void execute_whenBoardDrifts_returnsFreshPreviewAndRequiresAnotherConfirmation() throws Exception {
        BoardReplacementPreviewDto firstPreview = preview("board-v1", 1, 0, 0, 0);
        BoardReplacementPreviewDto freshPreview = preview("board-v2", 2, 0, 1, 0);
        when(boardStorageService.previewBoardReplacement(1L)).thenReturn(firstPreview);
        tool.execute("{\"confirmed\":false}");

        BoardBatchDto firstRequest = new BoardBatchDto();
        firstRequest.setImpactToken("board-v1");
        when(batchMapper.toBatch(any(), eq("board-v1"), eq("chat_scene_spec_"))).thenReturn(firstRequest);
        when(boardStorageService.saveBoardBatch(eq(1L), eq(firstRequest), any()))
                .thenThrow(new BoardReplacementStaleException(freshPreview));
        confirmCurrentScenario();

        JsonNode stale = objectMapper.readTree(tool.execute("{\"confirmed\":true}"));

        assertEquals("BOARD_REPLACEMENT_STALE", stale.path("errorCode").asText());
        assertTrue(stale.path("requiresUserConfirmation").asBoolean());
        assertEquals(2, stale.path("currentDeviceCount").asInt());

        BoardBatchDto secondRequest = new BoardBatchDto();
        secondRequest.setImpactToken("board-v2");
        when(batchMapper.toBatch(any(), eq("board-v2"), eq("chat_scene_spec_"))).thenReturn(secondRequest);
        doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return savedBatch();
        }).when(boardStorageService).saveBoardBatch(eq(1L), eq(secondRequest), any());
        JsonNode unapproved = objectMapper.readTree(tool.execute("{\"confirmed\":true}"));
        assertEquals("CONFIRMATION_MISMATCH", unapproved.path("errorCode").asText());
        confirmCurrentScenario();

        JsonNode applied = objectMapper.readTree(tool.execute("{\"confirmed\":true}"));

        assertEquals("replaced", applied.path("operation").asText());
        verify(boardStorageService).saveBoardBatch(eq(1L), eq(secondRequest), any());
    }

    @Test
    void execute_doesNotTreatDeletionConfirmationAsSceneReplacementAuthorization() throws Exception {
        BoardReplacementPreviewDto preview = preview("board-v1", 1, 0, 0, 0);
        when(boardStorageService.previewBoardReplacement(1L)).thenReturn(preview);
        tool.execute("{\"confirmed\":false}");
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", "another-confirmation");

        JsonNode result = objectMapper.readTree(tool.execute("{\"confirmed\":true}"));

        assertEquals("preview", result.path("operation").asText());
        assertTrue(result.path("requiresUserConfirmation").asBoolean());
        verify(boardStorageService, never()).saveBoardBatch(any(), any(), any());
    }

    @Test
    void execute_rejectsAnApprovedIdentityAfterAnotherDraftIsPreviewed() throws Exception {
        when(boardStorageService.previewBoardReplacement(1L)).thenReturn(preview("board-v1", 1, 0, 0, 0));
        tool.execute("{\"confirmed\":false}");
        confirmCurrentScenario();
        draftStore.saveDraft(1L, "session-1", "Another draft", scene());
        draftStore.beginApplication(1L, "session-1", preview("board-v1", 1, 0, 0, 0));

        JsonNode result = objectMapper.readTree(tool.execute("{\"confirmed\":true}"));
        assertEquals("CONFIRMATION_MISMATCH", result.path("errorCode").asText());
        assertEquals("Another draft", draftStore.latestDraft(1L, "session-1").orElseThrow().scenarioName());
        verify(boardStorageService, never()).saveBoardBatch(any(), any(), any());
    }

    @Test
    void execute_reportsAnUnconfirmedSettlementWithoutClaimingTheBoardStayedUnchanged() throws Exception {
        when(boardStorageService.previewBoardReplacement(1L)).thenReturn(preview("board-v1", 1, 0, 0, 0));
        tool.execute("{\"confirmed\":false}");
        confirmCurrentScenario();
        BoardBatchDto request = new BoardBatchDto();
        when(batchMapper.toBatch(any(), eq("board-v1"), eq("chat_scene_spec_"))).thenReturn(request);
        when(boardStorageService.saveBoardBatch(eq(1L), eq(request), any()))
                .thenThrow(new IllegalStateException("commit acknowledgement lost"));

        JsonNode result = objectMapper.readTree(tool.execute("{\"confirmed\":true}"));
        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertTrue(result.path("mutationMayHaveCommitted").asBoolean());
        assertFalse(result.path("resultAvailable").asBoolean());
        assertFalse(result.has("operation"));
    }

    private void confirmCurrentScenario() {
        UserContextHolder.setConfirmedProtectedAction("SCENE_REPLACEMENT",
                draftStore.pendingApplication(1L, "session-1").orElseThrow().confirmationId());
    }

    @Test
    void execute_reportsPortableSemanticFieldsAndKeepsTheRejectedDraft() throws Exception {
        when(boardStorageService.previewBoardReplacement(1L)).thenReturn(preview("board-v1", 0, 0, 0, 0));
        tool.execute("{\"confirmed\":false}");
        confirmCurrentScenario();
        BoardBatchDto request = new BoardBatchDto();
        when(batchMapper.toBatch(any(), eq("board-v1"), eq("chat_scene_spec_"))).thenReturn(request);
        when(boardStorageService.saveBoardBatch(eq(1L), eq(request), any())).thenThrow(new ValidationException(
                Map.of("rules[0].conditions[1].attribute", "State rule conditions must use attribute 'state'")));

        JsonNode result = objectMapper.readTree(tool.execute("{\"confirmed\":true}"));

        assertEquals(422, result.path("status").asInt());
        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertTrue(result.path("errors").has("rules[0].sources[1].fromApi"));
        assertTrue(draftStore.latestDraft(1L, "session-1").isPresent());
    }

    @Test
    void execute_distinguishesTemplateMismatchFromOtherConflicts() throws Exception {
        when(boardStorageService.previewBoardReplacement(1L)).thenReturn(preview("board-v1", 0, 0, 0, 0));
        tool.execute("{\"confirmed\":false}");
        confirmCurrentScenario();
        BoardBatchDto request = new BoardBatchDto();
        when(batchMapper.toBatch(any(), eq("board-v1"), eq("chat_scene_spec_"))).thenReturn(request);
        when(boardStorageService.saveBoardBatch(eq(1L), eq(request), any())).thenThrow(new BoardContentConflictException(
                "SCENE_TEMPLATE_MISMATCH", "Match the existing manifest", Map.of("templateSnapshots[1].manifest", "Match the existing manifest"), "Light"));

        JsonNode result = objectMapper.readTree(tool.execute("{\"confirmed\":true}"));

        assertEquals(409, result.path("status").asInt());
        assertEquals("SCENE_TEMPLATE_MISMATCH", result.path("reasonCode").asText());
        assertEquals("Light", result.path("templateName").asText());
        assertTrue(result.path("errors").has("templates[1].manifest"));
        assertTrue(draftStore.latestDraft(1L, "session-1").isPresent());
    }

    private JsonNode scene() throws Exception {
        return objectMapper.readTree("""
                {
                  "templates":[],
                  "devices":[{"id":"device_1"}],
                  "environmentVariables":[],
                  "rules":[],
                  "specs":[]
                }
                """);
    }

    private BoardReplacementPreviewDto preview(String token,
                                                int devices,
                                                int variables,
                                                int rules,
                                                int specs) {
        return BoardReplacementPreviewDto.builder()
                .impactToken(token)
                .deviceCount(devices)
                .environmentVariableCount(variables)
                .ruleCount(rules)
                .specificationCount(specs)
                .editHistoryEntryCount(7)
                .build();
    }

    private BoardBatchDto savedBatch() {
        BoardBatchDto saved = new BoardBatchDto();
        saved.setNodes(List.of(new cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto()));
        saved.setEnvironmentVariables(List.of());
        saved.setRules(List.of());
        saved.setSpecs(List.of());
        saved.setCreatedTemplates(List.of());
        return saved;
    }
}
