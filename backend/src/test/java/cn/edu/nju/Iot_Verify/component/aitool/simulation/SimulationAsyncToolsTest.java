package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControlHolder;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskDto;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.SimulationService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationAsyncToolsTest {

    @Mock
    private SimulationService simulationService;

    private ObjectMapper objectMapper;
    private SimulateModelAsyncTool simulateModelAsyncTool;
    private SimulateTaskStatusTool simulateTaskStatusTool;
    private CancelSimulateTaskTool cancelSimulateTaskTool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        simulateModelAsyncTool = new SimulateModelAsyncTool(simulationService, objectMapper);
        simulateTaskStatusTool = new SimulateTaskStatusTool(simulationService, objectMapper);
        cancelSimulateTaskTool = new CancelSimulateTaskTool(simulationService, objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        LlmRequestControlHolder.clear();
    }

    @Test
    void simulateModelAsync_boardValidationUsesServiceFailure() throws Exception {
        when(simulationService.submitSimulation(anyLong(), any()))
                .thenThrow(new ValidationException("devices", "Add at least one device to the board"));

        String result = simulateModelAsyncTool.execute("{}");
        JsonNode json = objectMapper.readTree(result);

        assertTrue(result.contains("Add at least one device"));
        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(422, json.path("status").asInt());
        verify(simulationService).submitSimulation(anyLong(), any());
    }

    @Test
    void simulateModelAsync_invalidSteps_shouldReturnValidationErrorBeforeLoadingBoard() throws Exception {
        String result = simulateModelAsyncTool.execute("{\"steps\":0}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("steps must be between 1 and 100.", json.path("error").asText());
        verify(simulationService, never()).submitSimulation(anyLong(), any());
    }

    @Test
    void simulateModelAsync_exactModeWithoutPoints_shouldReturnValidationErrorBeforeLoadingBoard() throws Exception {
        String result = simulateModelAsyncTool.execute(
                "{\"attackMode\":\"exact\",\"attackPoints\":[]}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("attackPoints must contain between 1 and 50 points when attackMode is exact.",
                json.path("error").asText());
        verify(simulationService, never()).submitSimulation(anyLong(), any());
    }

    @Test
    void simulateModelAsync_exhaustiveMode_shouldReturnValidationErrorBeforeLoadingBoard() throws Exception {
        String result = simulateModelAsyncTool.execute("{\"attackMode\":\"exhaustive\"}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertTrue(json.path("error").asText().contains("attackMode must be one of"));
        verify(simulationService, never()).submitSimulation(anyLong(), any());
    }

    @Test
    void simulateModelAsync_withValidInput_shouldStartTask() throws Exception {
        when(simulationService.submitSimulation(eq(1L), any()))
                .thenReturn(200L);
        when(simulationService.getTask(1L, 200L)).thenReturn(SimulationTaskDto.builder()
                .id(200L)
                .status("PENDING")
                .progress(0)
                .requestedSteps(10)
                .isAttack(false)
                .attackBudget(0)
                .enablePrivacy(false)
                .modelSnapshot(ModelRunSnapshotDto.captured(
                        LocalDateTime.of(2026, 7, 12, 9, 30), 1, 0, 0, 0, 1))
                .build());

        String result = simulateModelAsyncTool.execute("{}");
        JsonNode json = objectMapper.readTree(result);

        assertTrue(result.contains("taskId"));
        assertEquals("PENDING", json.path("taskStatus").asText());
        assertEquals(10, json.path("requestedSteps").asInt());
        assertEquals(false, json.path("isAttack").asBoolean());
        assertEquals(0, json.path("attackBudget").asInt());
        assertEquals(false, json.path("enablePrivacy").asBoolean());
        assertEquals(1, json.path("modelSnapshot").path("deviceCount").asInt());
        assertTrue(json.path("modelSnapshot").path("templatesFrozen").asBoolean());
        assertTrue(json.path("taskAccepted").asBoolean());
        assertTrue(json.path("message").asText().contains("completion is not implied"));
        verify(simulationService).submitSimulation(eq(1L), argThat(request ->
                request.getDevices() == null && request.getPlaybackNodes() == null
                        && request.getRules().isEmpty() && request.getEnvironmentVariables().isEmpty()
                        && request.getAttackScenario() != null));
        verify(simulationService).getTask(1L, 200L);
    }

    @Test
    void simulateModelAsync_whenQueueRejected_shouldReturnStructuredServiceUnavailable() throws Exception {
        doThrow(new ServiceUnavailableException("Server busy, please try again later")).when(simulationService)
                .submitSimulation(eq(1L), any());

        String result = simulateModelAsyncTool.execute("{}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("SERVICE_UNAVAILABLE", json.path("errorCode").asText());
        assertEquals(503, json.path("status").asInt());
        // The captured-snapshot submit path owns task creation and failure compensation internally,
        // so the AI tool only invokes the high-level submit entry point.
        verify(simulationService).submitSimulation(eq(1L), any());
        verifyNoMoreInteractions(simulationService);
    }

    @Test
    void simulateModelAsync_whenDispatchCleanupIsUnknown_shouldPreserveTaskId() throws Exception {
        doThrow(new AsyncTaskDispatchOutcomeUnknownException(
                "simulation", 202L, new IllegalStateException("cleanup failed")))
                .when(simulationService)
                .submitSimulation(eq(1L), any());

        JsonNode json = objectMapper.readTree(simulateModelAsyncTool.execute("{}"));

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals("TASK_DISPATCH_OUTCOME_UNKNOWN", json.path("errorCode").asText());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
        assertEquals(202L, json.path("taskId").asLong());
        assertEquals("simulate_task_status", json.path("statusTool").asText());
        assertTrue(json.path("message").asText().contains("before retrying"));
        verify(simulationService).submitSimulation(eq(1L), any());
        verifyNoMoreInteractions(simulationService);
    }

    @Test
    void simulateTaskStatus_missingTaskId_shouldReturnError() throws Exception {
        String result = simulateTaskStatusTool.execute("{}");
        JsonNode json = objectMapper.readTree(result);
        assertTrue(result.contains("taskId"));
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
    }

    @Test
    void simulateTaskStatus_completedTask_shouldExposeTraceHandoffWithoutExecutionLogs()
            throws Exception {
        when(simulationService.getTask(1L, 12L)).thenReturn(SimulationTaskDto.builder()
                .id(12L)
                .status("COMPLETED")
                .progress(100)
                .simulationTraceId(31L)
                .checkLogs(List.of("internal execution log"))
                .build());

        JsonNode json = objectMapper.readTree(
                simulateTaskStatusTool.execute("{\"taskId\":12}"));

        assertEquals(100, json.path("progress").asInt());
        assertEquals(100, json.path("task").path("progress").asInt());
        assertEquals(31L, json.path("simulationId").asLong());
        assertEquals("get_simulation_trace", json.path("nextTool").asText());
        assertEquals(31L, json.path("task").path("simulationTraceId").asLong());
        assertEquals(1, json.path("task").path("checkLogCount").asInt());
        assertFalse(json.path("task").has("checkLogs"));
        verify(simulationService).getTask(1L, 12L);
        verifyNoMoreInteractions(simulationService);
    }

    @Test
    void simulateTaskStatus_usesOneOwnedStatusAndProgressSnapshot() throws Exception {
        when(simulationService.getTask(1L, 13L)).thenReturn(SimulationTaskDto.builder()
                .id(13L).status("RUNNING").progress(50).build());

        JsonNode json = objectMapper.readTree(simulateTaskStatusTool.execute("{\"taskId\":13,\"waitMs\":0}"));

        assertEquals("RUNNING", json.path("task").path("status").asText());
        assertEquals(50, json.path("progress").asInt());
        assertEquals(50, json.path("task").path("progress").asInt());
        verify(simulationService).getTask(1L, 13L);
        verifyNoMoreInteractions(simulationService);
    }

    @Test
    void simulateTaskStatusSupportsTheSameBriefWaitAsVerification() throws Exception {
        when(simulationService.getTask(1L, 13L)).thenReturn(
                SimulationTaskDto.builder().id(13L).status("RUNNING").progress(50).build(),
                SimulationTaskDto.builder().id(13L).status("COMPLETED").progress(100)
                        .simulationTraceId(31L).build());

        JsonNode result = objectMapper.readTree(simulateTaskStatusTool.execute(
                "{\"taskId\":13,\"waitMs\":10000}"));

        assertEquals("COMPLETED", result.path("task").path("status").asText());
        assertEquals(100, result.path("progress").asInt());
        assertEquals(31L, result.path("simulationId").asLong());
        verify(simulationService, org.mockito.Mockito.times(2)).getTask(1L, 13L);
    }

    @Test
    void simulateTaskStatusRejectsInvalidWaitsBeforeReadingATask() throws Exception {
        for (String wait : List.of("-1", "10001", "1.5", "\"soon\"", "null")) {
            JsonNode result = objectMapper.readTree(simulateTaskStatusTool.execute(
                    "{\"taskId\":13,\"waitMs\":" + wait + "}"));
            assertEquals("VALIDATION_ERROR", result.path("errorCode").asText(), wait);
        }
        verifyNoMoreInteractions(simulationService);
    }

    @Test
    void simulateTaskStatusCanStopWaitingWithoutCancellingTheSimulation() throws Exception {
        when(simulationService.getTask(1L, 13L)).thenReturn(
                SimulationTaskDto.builder().id(13L).status("RUNNING").progress(50).build());
        LlmRequestControlHolder.set(new LlmRequestControl(() -> true));

        JsonNode result = objectMapper.readTree(simulateTaskStatusTool.execute("{\"taskId\":13}"));

        assertEquals("TASK_STATUS_WAIT_CANCELLED", result.path("errorCode").asText());
        assertEquals(499, result.path("status").asInt());
        assertFalse(Thread.currentThread().isInterrupted());
        verify(simulationService).getTask(1L, 13L);
        verifyNoMoreInteractions(simulationService);
    }

    @Test
    void simulateTaskStatusWaitExpiryPreservesTheActiveStateWithoutATraceHandoff() throws Exception {
        when(simulationService.getTask(1L, 13L)).thenReturn(
                SimulationTaskDto.builder().id(13L).status("RUNNING").progress(50).build());

        JsonNode result = objectMapper.readTree(simulateTaskStatusTool.execute(
                "{\"taskId\":13,\"waitMs\":1}"));

        assertEquals("RUNNING", result.path("task").path("status").asText());
        assertFalse(result.has("simulationId"));
        assertFalse(result.has("nextTool"));
        assertFalse(result.has("errorCode"));
    }

    @Test
    void simulateModelAsync_whenAcceptedTaskStatusCannotBeRead_shouldPreserveTaskId() throws Exception {
        when(simulationService.submitSimulation(eq(1L), any()))
                .thenReturn(201L);
        when(simulationService.getTask(1L, 201L))
                .thenThrow(new ServiceUnavailableException("status store unavailable"));

        JsonNode json = objectMapper.readTree(simulateModelAsyncTool.execute("{}"));

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals("ACCEPTED_TASK_STATUS_UNAVAILABLE", json.path("errorCode").asText());
        assertTrue(json.path("taskAccepted").asBoolean());
        assertEquals(201L, json.path("taskId").asLong());
        assertEquals("simulate_task_status", json.path("statusTool").asText());
        assertTrue(json.path("message").asText().contains("do not submit a duplicate"));
        verify(simulationService).submitSimulation(eq(1L), any());
        verify(simulationService).getTask(1L, 201L);
    }

    @Test
    void taskTools_rejectUnknownFieldsBeforeCallingService() throws Exception {
        JsonNode status = objectMapper.readTree(simulateTaskStatusTool.execute(
                "{\"taskId\":12,\"taksId\":12}"));
        JsonNode cancel = objectMapper.readTree(cancelSimulateTaskTool.execute(
                "{\"taskId\":12,\"force\":true}"));

        assertEquals("VALIDATION_ERROR", status.path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", cancel.path("errorCode").asText());
        verify(simulationService, never()).getTask(anyLong(), anyLong());
        verify(simulationService, never()).cancelTask(anyLong(), anyLong());
    }

    @Test
    void simulateTaskStatus_whenServiceThrowsBaseException_shouldReturnStructuredBusinessError() throws Exception {
        when(simulationService.getTask(1L, 9L)).thenThrow(new BadRequestException("invalid simulation task"));

        String result = simulateTaskStatusTool.execute("{\"taskId\":9}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("invalid simulation task", json.path("error").asText());
    }

    @Test
    void cancelSimulateTask_invalidTaskId_shouldReturnError() throws Exception {
        String result = cancelSimulateTaskTool.execute("{\"taskId\":-1}");
        JsonNode json = objectMapper.readTree(result);
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertTrue(result.contains("must be positive"));
    }

    @Test
    void cancelSimulateTask_whenResponseSerializationFails_shouldReturnAmbiguousMutationResult() throws Exception {
        ObjectMapper failingMapper = spy(new ObjectMapper());
        doThrow(new RuntimeException("boom")).when(failingMapper).writeValueAsString(any());
        when(simulationService.cancelTask(1L, 7L)).thenReturn(
                cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto.builder()
                        .taskId(7L)
                        .cancellationAccepted(true)
                        .cancellationOutcome(cn.edu.nju.Iot_Verify.dto.model.TaskCancellationOutcome.ACCEPTED)
                        .taskStatus("CANCELLED")
                        .executionMayStillBeStopping(true)
                        .build());

        CancelSimulateTaskTool fallbackTool = new CancelSimulateTaskTool(simulationService, failingMapper);
        String result = fallbackTool.execute("{\"taskId\":7}");
        JsonNode json = objectMapper.readTree(result);

        verify(simulationService).cancelTask(1L, 7L);
        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals(false, json.path("resultAvailable").asBoolean());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
        assertTrue(json.path("message").asText().contains("refresh current state"));
        assertTrue(json.path("warning").asText().contains("serialization failed"));
    }

}
