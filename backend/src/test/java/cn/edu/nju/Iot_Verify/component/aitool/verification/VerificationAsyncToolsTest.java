package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControlHolder;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.verification.SpecResultDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskDto;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
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

import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationAsyncToolsTest {

    @Mock
    private VerificationService verificationService;

    private ObjectMapper objectMapper;
    private VerifyModelAsyncTool verifyModelAsyncTool;
    private VerifyTaskStatusTool verifyTaskStatusTool;
    private CancelVerifyTaskTool cancelVerifyTaskTool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        verifyModelAsyncTool = new VerifyModelAsyncTool(verificationService, objectMapper);
        verifyTaskStatusTool = new VerifyTaskStatusTool(verificationService, objectMapper);
        cancelVerifyTaskTool = new CancelVerifyTaskTool(verificationService, objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        LlmRequestControlHolder.clear();
    }

    @Test
    void verifyModelAsync_boardValidationUsesServiceFailure() throws Exception {
        when(verificationService.submitVerification(anyLong(), any()))
                .thenThrow(new ValidationException("devices", "Add at least one device to the board"));

        String result = verifyModelAsyncTool.execute("{}");
        JsonNode json = objectMapper.readTree(result);

        assertTrue(result.contains("Add at least one device"));
        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(422, json.path("status").asInt());
        verify(verificationService).submitVerification(anyLong(), any());
    }

    @Test
    void verifyModelAsync_invalidIntensity_shouldReturnValidationErrorBeforeLoadingBoard() throws Exception {
        String result = verifyModelAsyncTool.execute("{\"attackBudget\":-1}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("attackBudget must be between 0 and 50.", json.path("error").asText());
        verify(verificationService, never()).submitVerification(anyLong(), any());
    }

    @Test
    void verifyModelAsync_attackEnabledWithZeroBudget_shouldReturnValidationErrorBeforeLoadingBoard() throws Exception {
        String result = verifyModelAsyncTool.execute(
                "{\"attackMode\":\"exhaustive\",\"attackBudget\":0}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("attackBudget must be between 1 and 50.", json.path("error").asText());
        verify(verificationService, never()).submitVerification(anyLong(), any());
    }

    @Test
    void verifyModelAsync_withValidInput_shouldStartTask() throws Exception {
        when(verificationService.submitVerification(eq(1L), any()))
                .thenReturn(100L);
        when(verificationService.getTask(1L, 100L)).thenReturn(VerificationTaskDto.builder()
                .id(100L)
                .status("PENDING")
                .progress(0)
                .isAttack(false)
                .attackBudget(0)
                .enablePrivacy(false)
                .modelSnapshot(ModelRunSnapshotDto.captured(
                        LocalDateTime.of(2026, 7, 12, 9, 30), 1, 0, 1, 0, 1))
                .build());

        String result = verifyModelAsyncTool.execute("{}");
        JsonNode json = objectMapper.readTree(result);

        assertTrue(result.contains("taskId"));
        assertEquals("PENDING", json.path("taskStatus").asText());
        assertEquals(0, json.path("progress").asInt());
        assertEquals(false, json.path("isAttack").asBoolean());
        assertEquals(0, json.path("attackBudget").asInt());
        assertEquals(false, json.path("enablePrivacy").asBoolean());
        assertEquals(1, json.path("modelSnapshot").path("deviceCount").asInt());
        assertTrue(json.path("modelSnapshot").path("templatesFrozen").asBoolean());
        assertTrue(json.path("taskAccepted").asBoolean());
        assertTrue(json.path("message").asText().contains("completion is not implied"));
        verify(verificationService).submitVerification(eq(1L), argThat(request ->
                request.getDevices() == null && request.getPlaybackNodes() == null
                        && request.getRules().isEmpty() && request.getEnvironmentVariables().isEmpty()
                        && request.getAttackScenario() != null));
        verify(verificationService).getTask(1L, 100L);
    }

    @Test
    void verifyModelAsync_whenQueueRejected_shouldReturnStructuredServiceUnavailable() throws Exception {
        doThrow(new ServiceUnavailableException("Server busy, please try again later")).when(verificationService)
                .submitVerification(eq(1L), any());

        String result = verifyModelAsyncTool.execute("{}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("SERVICE_UNAVAILABLE", json.path("errorCode").asText());
        assertEquals(503, json.path("status").asInt());
        // The captured-snapshot submit path owns task creation and failure compensation internally,
        // so the AI tool only invokes the high-level submit entry point.
        verify(verificationService).submitVerification(eq(1L), any());
        verifyNoMoreInteractions(verificationService);
    }

    @Test
    void verifyModelAsync_whenDispatchCleanupIsUnknown_shouldPreserveTaskId() throws Exception {
        doThrow(new AsyncTaskDispatchOutcomeUnknownException(
                "verification", 103L, new IllegalStateException("cleanup failed")))
                .when(verificationService)
                .submitVerification(eq(1L), any());

        JsonNode json = objectMapper.readTree(verifyModelAsyncTool.execute("{}"));

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals("TASK_DISPATCH_OUTCOME_UNKNOWN", json.path("errorCode").asText());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
        assertEquals(103L, json.path("taskId").asLong());
        assertEquals("verify_task_status", json.path("statusTool").asText());
        assertTrue(json.path("message").asText().contains("before retrying"));
        verify(verificationService).submitVerification(eq(1L), any());
        verifyNoMoreInteractions(verificationService);
    }

    @Test
    void verifyTaskStatus_missingTaskId_shouldReturnError() throws Exception {
        String result = verifyTaskStatusTool.execute("{}");
        JsonNode json = objectMapper.readTree(result);
        assertTrue(result.contains("taskId"));
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
    }

    @Test
    void verifyTaskStatus_completedTask_shouldExposeRunHandoffWithoutRawTechnicalOutput()
            throws Exception {
        when(verificationService.getTask(1L, 12L)).thenReturn(VerificationTaskDto.builder()
                .id(12L)
                .status("COMPLETED")
                .progress(100)
                .outcome(cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome.SATISFIED)
                .specResults(List.of(SpecResultDto.builder()
                        .specId("internal-spec-id")
                        .templateId("internal-template-id")
                        .specificationLabel("Door remains closed")
                        .outcome(cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome.SATISFIED)
                        .build()))
                .checkLogs(List.of("internal execution log"))
                .nusmvOutput("raw NuSMV stdout")
                .build());

        JsonNode json = objectMapper.readTree(
                verifyTaskStatusTool.execute("{\"taskId\":12}"));

        assertEquals(100, json.path("progress").asInt());
        assertEquals(100, json.path("task").path("progress").asInt());
        assertEquals(12L, json.path("runId").asLong());
        assertEquals("get_verification_run", json.path("nextTool").asText());
        assertEquals("SATISFIED", json.path("task").path("outcome").asText());
        assertFalse(json.path("task").path("specResults").get(0).has("specId"));
        assertFalse(json.path("task").path("specResults").get(0).has("templateId"));
        assertEquals(1, json.path("task").path("checkLogCount").asInt());
        assertFalse(json.path("task").has("checkLogs"));
        assertFalse(json.path("task").has("nusmvOutput"));
        verify(verificationService).getTask(1L, 12L);
        verifyNoMoreInteractions(verificationService);
    }

    @Test
    void verifyTaskStatus_usesOneOwnedStatusAndProgressSnapshot() throws Exception {
        when(verificationService.getTask(1L, 13L)).thenReturn(VerificationTaskDto.builder()
                .id(13L).status("RUNNING").progress(50).build());

        JsonNode json = objectMapper.readTree(verifyTaskStatusTool.execute("{\"taskId\":13,\"waitMs\":0}"));

        assertEquals("RUNNING", json.path("task").path("status").asText());
        assertEquals(50, json.path("progress").asInt());
        assertEquals(50, json.path("task").path("progress").asInt());
        verify(verificationService).getTask(1L, 13L);
        verifyNoMoreInteractions(verificationService);
    }

    @Test
    void verifyTaskStatus_waitsForTerminalSnapshotByDefault() throws Exception {
        when(verificationService.getTask(1L, 13L)).thenReturn(
                VerificationTaskDto.builder().id(13L).status("RUNNING").progress(50).build(),
                VerificationTaskDto.builder().id(13L).status("COMPLETED").progress(100).build());

        JsonNode json = objectMapper.readTree(verifyTaskStatusTool.execute("{\"taskId\":13}"));

        assertEquals("COMPLETED", json.path("task").path("status").asText());
        assertEquals(100, json.path("progress").asInt());
        assertEquals(13, json.path("runId").asInt());
        verify(verificationService, org.mockito.Mockito.times(2)).getTask(1L, 13L);
    }

    @Test
    void verifyTaskStatus_readFailureDuringWaitRemainsAnError() throws Exception {
        when(verificationService.getTask(1L, 13L)).thenReturn(
                VerificationTaskDto.builder().id(13L).status("RUNNING").progress(50).build())
                .thenThrow(new BadRequestException("task is no longer available"));

        JsonNode json = objectMapper.readTree(verifyTaskStatusTool.execute("{\"taskId\":13}"));

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertFalse(json.has("task"));
        assertFalse(json.has("runId"));
        verify(verificationService, org.mockito.Mockito.times(2)).getTask(1L, 13L);
    }

    @Test
    void verifyTaskStatus_waitTimeoutPreservesRunningWithoutRunHandoff() throws Exception {
        when(verificationService.getTask(1L, 13L)).thenReturn(
                VerificationTaskDto.builder().id(13L).status("RUNNING").progress(50).build());

        JsonNode json = objectMapper.readTree(verifyTaskStatusTool.execute("{\"taskId\":13,\"waitMs\":1}"));

        assertEquals("RUNNING", json.path("task").path("status").asText());
        assertEquals(50, json.path("progress").asInt());
        assertFalse(json.has("runId"));
        assertFalse(json.has("nextTool"));
    }

    @Test
    void verifyTaskStatus_waitCancellationDoesNotCancelTask() throws Exception {
        when(verificationService.getTask(1L, 13L)).thenReturn(
                VerificationTaskDto.builder().id(13L).status("RUNNING").progress(50).build());
        LlmRequestControlHolder.set(new LlmRequestControl(() -> true));

        JsonNode json = objectMapper.readTree(verifyTaskStatusTool.execute("{\"taskId\":13}"));

        assertEquals("TASK_STATUS_WAIT_CANCELLED", json.path("errorCode").asText());
        verify(verificationService).getTask(1L, 13L);
        verifyNoMoreInteractions(verificationService);
    }

    @Test
    void verifyTaskStatus_waitInterruptionPreservesInterrupt() throws Exception {
        when(verificationService.getTask(1L, 13L)).thenReturn(
                VerificationTaskDto.builder().id(13L).status("RUNNING").progress(50).build());
        LlmRequestControlHolder.set(new LlmRequestControl(() -> {
            Thread.currentThread().interrupt();
            return false;
        }));
        try {
            JsonNode json = objectMapper.readTree(verifyTaskStatusTool.execute("{\"taskId\":13}"));
            assertEquals("TASK_STATUS_WAIT_CANCELLED", json.path("errorCode").asText());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(verificationService).getTask(1L, 13L);
            verifyNoMoreInteractions(verificationService);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void verifyTaskStatus_rejectsUnboundedOrMalformedWaitBeforeReadingTask() throws Exception {
        for (String wait : List.of("-1", "10001", "1.5", "\"1000\"")) {
            JsonNode json = objectMapper.readTree(verifyTaskStatusTool.execute(
                    "{\"taskId\":13,\"waitMs\":" + wait + "}"));
            assertEquals("VALIDATION_ERROR", json.path("errorCode").asText(), wait);
        }
        verifyNoMoreInteractions(verificationService);
    }

    @Test
    void verifyModelAsync_whenAcceptedTaskStatusCannotBeRead_shouldPreserveTaskId() throws Exception {
        when(verificationService.submitVerification(eq(1L), any()))
                .thenReturn(101L);
        when(verificationService.getTask(1L, 101L))
                .thenThrow(new ServiceUnavailableException("status store unavailable"));

        JsonNode json = objectMapper.readTree(verifyModelAsyncTool.execute("{}"));

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals("ACCEPTED_TASK_STATUS_UNAVAILABLE", json.path("errorCode").asText());
        assertTrue(json.path("taskAccepted").asBoolean());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
        assertEquals(101L, json.path("taskId").asLong());
        assertEquals("verify_task_status", json.path("statusTool").asText());
        assertTrue(json.path("message").asText().contains("do not submit a duplicate"));
        verify(verificationService).submitVerification(eq(1L), any());
        verify(verificationService).getTask(1L, 101L);
    }

    @Test
    void verifyModelAsync_whenAcceptedResponseSerializationFails_shouldPreserveTaskId() throws Exception {
        when(verificationService.submitVerification(eq(1L), any()))
                .thenReturn(102L);
        when(verificationService.getTask(1L, 102L)).thenReturn(VerificationTaskDto.builder()
                .id(102L).status("PENDING").progress(0).build());
        ObjectMapper failingMapper = spy(new ObjectMapper().findAndRegisterModules());
        doThrow(new RuntimeException("boom")).when(failingMapper).writeValueAsString(any());
        VerifyModelAsyncTool tool = new VerifyModelAsyncTool(
                verificationService, failingMapper);

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertTrue(json.path("taskAccepted").asBoolean());
        assertEquals(102L, json.path("taskId").asLong());
        assertEquals("verify_task_status", json.path("statusTool").asText());
    }

    @Test
    void taskTools_rejectUnknownFieldsBeforeCallingService() throws Exception {
        JsonNode status = objectMapper.readTree(verifyTaskStatusTool.execute(
                "{\"taskId\":12,\"taksId\":12}"));
        JsonNode cancel = objectMapper.readTree(cancelVerifyTaskTool.execute(
                "{\"taskId\":12,\"force\":true}"));

        assertEquals("VALIDATION_ERROR", status.path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", cancel.path("errorCode").asText());
        verify(verificationService, never()).getTask(anyLong(), anyLong());
        verify(verificationService, never()).cancelTask(anyLong(), anyLong());
    }

    @Test
    void verifyTaskStatus_whenServiceThrowsBaseException_shouldReturnStructuredBusinessError() throws Exception {
        when(verificationService.getTask(1L, 12L)).thenThrow(new BadRequestException("invalid task"));

        String result = verifyTaskStatusTool.execute("{\"taskId\":12}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("invalid task", json.path("error").asText());
    }

    @Test
    void cancelVerifyTask_invalidTaskId_shouldReturnError() throws Exception {
        String result = cancelVerifyTaskTool.execute("{\"taskId\":0}");
        JsonNode json = objectMapper.readTree(result);
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertTrue(result.contains("must be positive"));
    }

    @Test
    void cancelVerifyTask_whenResponseSerializationFails_shouldReturnAmbiguousMutationResult() throws Exception {
        ObjectMapper failingMapper = spy(new ObjectMapper());
        doThrow(new RuntimeException("boom")).when(failingMapper).writeValueAsString(any());
        when(verificationService.cancelTask(1L, 7L)).thenReturn(
                cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto.builder()
                        .taskId(7L)
                        .cancellationAccepted(true)
                        .cancellationOutcome(cn.edu.nju.Iot_Verify.dto.model.TaskCancellationOutcome.ACCEPTED)
                        .taskStatus("CANCELLED")
                        .executionMayStillBeStopping(true)
                        .build());

        CancelVerifyTaskTool fallbackTool = new CancelVerifyTaskTool(verificationService, failingMapper);
        String result = fallbackTool.execute("{\"taskId\":7}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals(false, json.path("resultAvailable").asBoolean());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
        assertTrue(json.path("message").asText().contains("refresh current state"));
        assertTrue(json.path("warning").asText().contains("serialization failed"));
    }

}
