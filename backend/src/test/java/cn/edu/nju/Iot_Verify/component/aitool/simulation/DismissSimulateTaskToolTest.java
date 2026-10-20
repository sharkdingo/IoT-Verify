package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskDto;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DismissSimulateTaskToolTest {

    @Mock
    private SimulationService simulationService;

    private ObjectMapper objectMapper;
    private DismissSimulateTaskTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        tool = new DismissSimulateTaskTool(
                simulationService, objectMapper, new AiDestructiveActionGuard(objectMapper));
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("simulation-cleanup-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void dismiss_previewThenConfirmRemovesCancelledTask() throws Exception {
        SimulationTaskDto task = SimulationTaskDto.builder()
                .id(4L)
                .status("CANCELLED")
                .requestedSteps(20)
                .progress(35)
                .errorMessage("Cancelled by user")
                .build();
        when(simulationService.getTask(1L, 4L)).thenReturn(task);

        JsonNode preview = objectMapper.readTree(
                tool.execute("{\"taskId\":4,\"confirmed\":false}"));

        assertTrue(preview.path("requiresUserConfirmation").asBoolean());
        assertEquals("CANCELLED", preview.path("status").asText());
        assertEquals(20, preview.path("requestedSteps").asInt());
        verify(simulationService, never()).deleteTask(1L, 4L);

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", preview.path("impactToken").asText());
        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"taskId\":4,\"confirmed\":true,\"impactToken\":\""
                        + preview.path("impactToken").asText() + "\"}"));

        assertEquals(true, json.path("dismissed").asBoolean());
        verify(simulationService).deleteTask(1L, 4L);
    }

    @Test
    void dismiss_activeTaskIsRejectedBeforeIssuingPreview() throws Exception {
        when(simulationService.getTask(1L, 4L)).thenReturn(
                SimulationTaskDto.builder().id(4L).status("RUNNING").build());

        JsonNode json = objectMapper.readTree(
                tool.execute("{\"taskId\":4,\"confirmed\":false}"));

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        verify(simulationService, never()).deleteTask(1L, 4L);
    }

    @Test
    void dismiss_confirmWithoutTokenIsRejected() throws Exception {
        when(simulationService.getTask(1L, 4L)).thenReturn(
                SimulationTaskDto.builder().id(4L).status("FAILED").build());
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", "missing-token");

        JsonNode json = objectMapper.readTree(
                tool.execute("{\"taskId\":4,\"confirmed\":true}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(simulationService, never()).deleteTask(1L, 4L);
    }

    @Test
    void dismiss_missingTaskIdRejected() throws Exception {
        JsonNode json = objectMapper.readTree(tool.execute("{\"confirmed\":false}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(simulationService, never()).deleteTask(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
