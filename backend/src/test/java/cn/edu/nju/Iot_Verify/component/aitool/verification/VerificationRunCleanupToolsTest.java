package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskDto;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationRunCleanupToolsTest {

    @Mock
    private VerificationService verificationService;

    private ObjectMapper objectMapper;
    private AiDestructiveActionGuard destructiveActionGuard;
    private DeleteVerificationRunTool deleteRunTool;
    private DismissVerifyTaskTool dismissTool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        destructiveActionGuard = new AiDestructiveActionGuard(objectMapper);
        deleteRunTool = new DeleteVerificationRunTool(
                verificationService, objectMapper, destructiveActionGuard);
        dismissTool = new DismissVerifyTaskTool(
                verificationService, objectMapper, destructiveActionGuard);
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("verify-cleanup-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void deleteRun_previewDistinguishesSameSizedRunsWithoutExposingTheAuthorizationKey() throws Exception {
        LocalDateTime firstCompletedAt = LocalDateTime.of(2026, 10, 8, 12, 0);
        LocalDateTime secondCompletedAt = firstCompletedAt.plusMinutes(1);
        when(verificationService.getRunDeletionImpact(1L, 5L)).thenReturn(
                RunDeletionImpactDto.builder().runId(5L).evidenceCount(3)
                        .completedAt(firstCompletedAt).build());
        when(verificationService.getRunDeletionImpact(1L, 6L)).thenReturn(
                RunDeletionImpactDto.builder().runId(6L).evidenceCount(3)
                        .completedAt(secondCompletedAt).build());

        deleteRunTool.execute("{\"runId\":5,\"confirmed\":false}");
        AiDestructiveActionGuard.PendingActionContext first = destructiveActionGuard
                .pendingContext(1L, "verify-cleanup-session").orElseThrow();
        deleteRunTool.execute("{\"runId\":6,\"confirmed\":false}");
        AiDestructiveActionGuard.PendingActionContext second = destructiveActionGuard
                .pendingContext(1L, "verify-cleanup-session").orElseThrow();

        assertEquals("Verification run: " + firstCompletedAt + " · 3 stored counterexamples",
                first.displayTarget());
        assertEquals("Verification run: " + secondCompletedAt + " · 3 stored counterexamples",
                second.displayTarget());
        assertNotEquals(first.displayTarget(), second.displayTarget());
        assertEquals("5", first.targetKey());
        assertEquals("6", second.targetKey());
        verify(verificationService, never()).deleteRun(1L, 5L, 3L);
        verify(verificationService, never()).deleteRun(1L, 6L, 3L);
    }

    @Test
    void deleteRun_previewThenConfirmCascades() throws Exception {
        when(verificationService.getRunDeletionImpact(1L, 5L)).thenReturn(
                RunDeletionImpactDto.builder().runId(5L).evidenceCount(3).build());
        when(verificationService.deleteRun(1L, 5L, 3L)).thenReturn(3L);

        JsonNode preview = objectMapper.readTree(
                deleteRunTool.execute("{\"runId\":5,\"confirmed\":false}"));
        assertTrue(preview.path("requiresUserConfirmation").asBoolean());
        assertEquals(3, preview.path("wouldRemoveStoredTraceCount").asInt());
        assertTrue(preview.path("countIncludesUnavailableEvidence").asBoolean());
        verify(verificationService, never()).deleteRun(1L, 5L, 3L);

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", preview.path("impactToken").asText());
        JsonNode result = objectMapper.readTree(deleteRunTool.execute(
                "{\"runId\":5,\"confirmed\":true,\"impactToken\":\""
                        + preview.path("impactToken").asText() + "\"}"));

        assertEquals(true, result.path("deleted").asBoolean());
        assertEquals(3, result.path("deletedTraceCount").asInt());
        verify(verificationService).deleteRun(1L, 5L, 3L);
    }

    @Test
    void deleteRun_staleTokenReturnsFreshPreview() throws Exception {
        when(verificationService.getRunDeletionImpact(1L, 5L)).thenReturn(
                RunDeletionImpactDto.builder().runId(5L).evidenceCount(0).build());
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", "stale-token");

        JsonNode json = objectMapper.readTree(deleteRunTool.execute(
                "{\"runId\":5,\"confirmed\":true,\"impactToken\":\"bogus\"}"));

        assertEquals(409, json.path("status").asInt());
        assertTrue(json.path("requiresUserConfirmation").asBoolean());
        verify(verificationService, never()).deleteRun(1L, 5L, 0L);
    }

    @Test
    void deleteRun_changedTraceCountInvalidatesConfirmation() throws Exception {
        when(verificationService.getRunDeletionImpact(1L, 5L))
                .thenReturn(RunDeletionImpactDto.builder().runId(5L).evidenceCount(2).build())
                .thenReturn(RunDeletionImpactDto.builder().runId(5L).evidenceCount(3).build());

        JsonNode preview = objectMapper.readTree(
                deleteRunTool.execute("{\"runId\":5,\"confirmed\":false}"));
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", preview.path("impactToken").asText());
        JsonNode result = objectMapper.readTree(deleteRunTool.execute(
                "{\"runId\":5,\"confirmed\":true,\"impactToken\":\""
                        + preview.path("impactToken").asText() + "\"}"));

        assertEquals(409, result.path("status").asInt());
        assertEquals(3, result.path("currentPreview")
                .path("wouldRemoveStoredTraceCount").asInt());
        verify(verificationService, never()).deleteRun(1L, 5L, 3L);
    }

    @Test
    void dismiss_previewThenConfirmRemovesFailedTaskAndDiagnostics() throws Exception {
        VerificationTaskDto task = VerificationTaskDto.builder()
                .id(9L)
                .status("FAILED")
                .progress(63)
                .errorMessage("NuSMV process failed")
                .checkLogs(List.of("model generated", "solver failed"))
                .build();
        when(verificationService.getTask(1L, 9L)).thenReturn(task);

        JsonNode preview = objectMapper.readTree(
                dismissTool.execute("{\"taskId\":9,\"confirmed\":false}"));

        assertTrue(preview.path("requiresUserConfirmation").asBoolean());
        assertEquals("FAILED", preview.path("status").asText());
        assertEquals(2, preview.path("checkLogCount").asInt());
        assertEquals("NuSMV process failed", preview.path("errorMessage").asText());
        verify(verificationService, never()).deleteTask(1L, 9L);

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", preview.path("impactToken").asText());
        JsonNode json = objectMapper.readTree(dismissTool.execute(
                "{\"taskId\":9,\"confirmed\":true,\"impactToken\":\""
                        + preview.path("impactToken").asText() + "\"}"));

        assertEquals(true, json.path("dismissed").asBoolean());
        verify(verificationService).deleteTask(1L, 9L);
    }

    @Test
    void dismiss_changedHiddenDiagnosticsRejectsStaleConfirmationAndReturnsFreshPreview() throws Exception {
        String originalError = "x".repeat(1_004) + "a";
        String changedError = "x".repeat(1_004) + "b";
        VerificationTaskDto original = VerificationTaskDto.builder()
                .id(9L).status("FAILED").errorMessage(originalError).build();
        VerificationTaskDto changed = VerificationTaskDto.builder()
                .id(9L).status("FAILED").errorMessage(changedError).build();
        when(verificationService.getTask(1L, 9L)).thenReturn(original, changed);

        JsonNode preview = objectMapper.readTree(
                dismissTool.execute("{\"taskId\":9,\"confirmed\":false}"));
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", preview.path("impactToken").asText());
        JsonNode json = objectMapper.readTree(dismissTool.execute(
                "{\"taskId\":9,\"confirmed\":true,\"impactToken\":\""
                        + preview.path("impactToken").asText() + "\"}"));

        assertEquals("CONFIRMATION_STALE", json.path("errorCode").asText());
        assertEquals(409, json.path("status").asInt());
        assertEquals(preview.path("errorMessage").asText(),
                json.path("currentPreview").path("errorMessage").asText());
        assertTrue(json.path("currentPreview").path("errorMessageTruncated").asBoolean());
        verify(verificationService, never()).deleteTask(1L, 9L);
    }

    @Test
    void dismiss_previewBoundsLargeErrorMessage() throws Exception {
        String errorMessage = "x".repeat(1_005);
        when(verificationService.getTask(1L, 9L)).thenReturn(
                VerificationTaskDto.builder()
                        .id(9L).status("FAILED").errorMessage(errorMessage).build());

        JsonNode preview = objectMapper.readTree(
                dismissTool.execute("{\"taskId\":9,\"confirmed\":false}"));

        assertEquals(1_000, preview.path("errorMessage").asText().length());
        assertEquals(1_005, preview.path("errorMessageLength").asInt());
        assertTrue(preview.path("errorMessageTruncated").asBoolean());
        verify(verificationService, never()).deleteTask(1L, 9L);
    }

    @Test
    void dismiss_completedTaskIsRejectedBeforeIssuingPreview() throws Exception {
        when(verificationService.getTask(1L, 9L)).thenReturn(
                VerificationTaskDto.builder().id(9L).status("COMPLETED").build());

        JsonNode json = objectMapper.readTree(
                dismissTool.execute("{\"taskId\":9,\"confirmed\":false}"));

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        verify(verificationService, never()).deleteTask(1L, 9L);
    }

    @Test
    void dismiss_requiresExplicitConfirmedArgumentBeforeReadingTask() throws Exception {
        JsonNode json = objectMapper.readTree(dismissTool.execute("{\"taskId\":9}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(verificationService, never()).getTask(1L, 9L);
        verify(verificationService, never()).deleteTask(1L, 9L);
    }

    @Test
    void dismiss_previewSerializationFailureReportsNoMutation() throws Exception {
        when(verificationService.getTask(1L, 9L)).thenReturn(
                VerificationTaskDto.builder().id(9L).status("FAILED").build());
        ObjectMapper failingMapper = spy(new ObjectMapper().findAndRegisterModules());
        doThrow(new RuntimeException("boom"))
                .when(failingMapper).writeValueAsString(any(Object.class));
        DismissVerifyTaskTool failingTool = new DismissVerifyTaskTool(
                verificationService, failingMapper, destructiveActionGuard);

        JsonNode json = objectMapper.readTree(
                failingTool.execute("{\"taskId\":9,\"confirmed\":false}"));

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertFalse(json.path("mutationMayHaveCommitted").asBoolean(true));
        verify(verificationService, never()).deleteTask(1L, 9L);
    }

    @Test
    void dismiss_confirmSerializationFailureReportsPossibleCommittedMutation() throws Exception {
        VerificationTaskDto task = VerificationTaskDto.builder()
                .id(9L).status("FAILED").build();
        when(verificationService.getTask(1L, 9L)).thenReturn(task);
        JsonNode preview = objectMapper.readTree(
                dismissTool.execute("{\"taskId\":9,\"confirmed\":false}"));
        ObjectMapper failingMapper = spy(new ObjectMapper().findAndRegisterModules());
        doThrow(new RuntimeException("boom"))
                .when(failingMapper).writeValueAsString(any(Object.class));
        DismissVerifyTaskTool failingTool = new DismissVerifyTaskTool(
                verificationService, failingMapper, destructiveActionGuard);
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", preview.path("impactToken").asText());

        JsonNode json = objectMapper.readTree(failingTool.execute(
                "{\"taskId\":9,\"confirmed\":true,\"impactToken\":\""
                        + preview.path("impactToken").asText() + "\"}"));

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
        verify(verificationService).deleteTask(1L, 9L);
    }

    @Test
    void deleteRun_unknownRunSurfacesBusinessError() throws Exception {
        when(verificationService.getRunDeletionImpact(1L, 5L))
                .thenThrow(new ResourceNotFoundException("VerificationRun", 5L));

        JsonNode json = objectMapper.readTree(
                deleteRunTool.execute("{\"runId\":5,\"confirmed\":false}"));

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(404, json.path("status").asInt());
    }
}
