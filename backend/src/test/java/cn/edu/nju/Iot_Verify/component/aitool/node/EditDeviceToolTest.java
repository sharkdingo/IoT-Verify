package cn.edu.nju.Iot_Verify.component.aitool.node;

import cn.edu.nju.Iot_Verify.dto.device.DeviceLayoutDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeUpdateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceUpdateResultDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.exception.DeviceLabelConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceLayoutConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceRuntimeConflictException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EditDeviceToolTest {

    @Mock
    private BoardStorageService boardStorageService;

    private ObjectMapper objectMapper;
    private EditDeviceTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new EditDeviceTool(boardStorageService, objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private DeviceNodeDto device(String id, String label) {
        DeviceNodeDto dto = new DeviceNodeDto();
        dto.setId(id);
        dto.setLabel(label);
        dto.setTemplateName("AC");
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(10.0);
        position.setY(20.0);
        dto.setPosition(position);
        dto.setState("on");
        dto.setWidth(176);
        dto.setHeight(128);
        return dto;
    }

    @Test
    void editLabel_renamesThroughCompareAndSet() throws Exception {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(device("d1", "Old")));
        DeviceNodeDto renamed = device("d1", "New");
        when(boardStorageService.renameNode(1L, "d1", "New", "Old"))
                .thenReturn(DeviceMutationResultDto.builder()
                        .operation("renamed")
                        .affectedDevices(List.of(renamed))
                        .previousLabel("Old")
                        .updatedSpecificationCount(2)
                        .build());

        JsonNode json = objectMapper.readTree(
                tool.execute("{\"id\":\"d1\",\"field\":\"label\",\"label\":\"New\"}"));

        assertEquals("renamed", json.path("operation").asText());
        assertEquals("Old", json.path("previousLabel").asText());
        assertEquals("New", json.path("device").path("label").asText());
        assertEquals(2, json.path("updatedSpecificationCount").asInt());
        verify(boardStorageService).renameNode(1L, "d1", "New", "Old");
    }

    @Test
    void editLabel_conflictReturnsSuggestion() throws Exception {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(device("d1", "Old")));
        when(boardStorageService.renameNode(1L, "d1", "Kitchen", "Old"))
                .thenThrow(new DeviceLabelConflictException("Kitchen", "Kitchen 2"));

        JsonNode json = objectMapper.readTree(
                tool.execute("{\"id\":\"d1\",\"field\":\"label\",\"label\":\"Kitchen\"}"));

        assertEquals("DEVICE_LABEL_CONFLICT", json.path("errorCode").asText());
        assertEquals(409, json.path("status").asInt());
        assertEquals("Kitchen 2", json.path("suggestedLabel").asText());
    }

    @Test
    void editLabel_rejectsRuntimeFieldForLabelEdit() throws Exception {
        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"label\",\"label\":\"New\",\"state\":\"off\"}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(boardStorageService, never()).renameNode(any(), any(), any(), any());
    }

    @Test
    void editRuntime_buildsExpectedFromCurrentAndDesiredFromArgs() throws Exception {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(device("d1", "AC")));
        DeviceNodeDto updated = device("d1", "AC");
        updated.setState("off");
        when(boardStorageService.updateNodeRuntime(eq(1L), eq("d1"), any()))
                .thenReturn(DeviceUpdateResultDto.builder()
                        .operation("updated")
                        .mutationType("runtime")
                        .changedFields(List.of("state"))
                        .currentDevice(updated)
                        .build());

        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"runtime\",\"state\":\"off\"}"));

        assertEquals("updated", json.path("operation").asText());
        assertEquals("off", json.path("device").path("state").asText());

        ArgumentCaptor<DeviceRuntimeUpdateDto> captor =
                ArgumentCaptor.forClass(DeviceRuntimeUpdateDto.class);
        verify(boardStorageService).updateNodeRuntime(eq(1L), eq("d1"), captor.capture());
        assertEquals("on", captor.getValue().getExpected().getState());
        assertEquals("off", captor.getValue().getDesired().getState());
    }

    @Test
    void editRuntime_preservesExistingVariableTrustWhenOnlyItsValueChanges() throws Exception {
        DeviceNodeDto current = device("d1", "Sensor");
        current.setVariables(List.of(new VariableStateDto("temperature", "20", "untrusted")));
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(current));
        when(boardStorageService.updateNodeRuntime(eq(1L), eq("d1"), any()))
                .thenReturn(DeviceUpdateResultDto.builder()
                        .operation("updated")
                        .mutationType("runtime")
                        .changedFields(List.of("variables"))
                        .currentDevice(current)
                        .build());

        objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"runtime\",\"variables\":[{\"name\":\"temperature\",\"value\":\"21\"}]}"));

        ArgumentCaptor<DeviceRuntimeUpdateDto> captor =
                ArgumentCaptor.forClass(DeviceRuntimeUpdateDto.class);
        verify(boardStorageService).updateNodeRuntime(eq(1L), eq("d1"), captor.capture());
        assertEquals("untrusted", captor.getValue().getDesired().getVariables().get(0).getTrust());
    }

    @Test
    void editRuntime_rejectsEmptyOrExplicitNullPatch() throws Exception {
        JsonNode empty = objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"runtime\"}"));
        JsonNode explicitNull = objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"runtime\",\"state\":null}"));

        assertEquals("VALIDATION_ERROR", empty.path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", explicitNull.path("errorCode").asText());
        verify(boardStorageService, never()).updateNodeRuntime(any(), any(), any());
    }

    @Test
    void editRuntime_conflictReturnsCurrentDevice() throws Exception {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(device("d1", "AC")));
        DeviceNodeDto current = device("d1", "AC");
        current.setState("cooling");
        when(boardStorageService.updateNodeRuntime(eq(1L), eq("d1"), any()))
                .thenThrow(new DeviceRuntimeConflictException(current));

        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"runtime\",\"state\":\"off\"}"));

        assertEquals("DEVICE_RUNTIME_CONFLICT", json.path("errorCode").asText());
        assertEquals(409, json.path("status").asInt());
        assertEquals("cooling", json.path("currentDevice").path("state").asText());
    }

    @Test
    void editLayout_defaultsUnspecifiedDimensionsFromCurrentDevice() throws Exception {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(device("d1", "AC")));
        DeviceNodeDto moved = device("d1", "AC");
        moved.getPosition().setX(300.0);
        when(boardStorageService.updateNodeLayoutIfUnchanged(eq(1L), eq("d1"), any(), any()))
                .thenReturn(DeviceUpdateResultDto.builder()
                        .operation("updated")
                        .mutationType("layout")
                        .changedFields(List.of("position.x"))
                        .currentDevice(moved)
                        .build());

        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"layout\",\"x\":300}"));

        assertEquals("updated", json.path("operation").asText());
        ArgumentCaptor<DeviceLayoutDto> expected = ArgumentCaptor.forClass(DeviceLayoutDto.class);
        ArgumentCaptor<DeviceLayoutDto> desired = ArgumentCaptor.forClass(DeviceLayoutDto.class);
        verify(boardStorageService).updateNodeLayoutIfUnchanged(
                eq(1L), eq("d1"), expected.capture(), desired.capture());
        assertEquals(10.0, expected.getValue().getPosition().getX());
        assertEquals(20.0, expected.getValue().getPosition().getY());
        assertEquals(176, expected.getValue().getWidth());
        assertEquals(128, expected.getValue().getHeight());
        assertEquals(300.0, desired.getValue().getPosition().getX());
        assertEquals(20.0, desired.getValue().getPosition().getY());
        assertEquals(176, desired.getValue().getWidth());
        assertEquals(128, desired.getValue().getHeight());
    }

    @Test
    void editLayout_rejectsEmptyNullAndOutOfRangePatches() throws Exception {
        for (String args : List.of(
                "{\"id\":\"d1\",\"field\":\"layout\"}",
                "{\"id\":\"d1\",\"field\":\"layout\",\"x\":null}",
                "{\"id\":\"d1\",\"field\":\"layout\",\"w\":79}",
                "{\"id\":\"d1\",\"field\":\"layout\",\"h\":2001}")) {
            JsonNode json = objectMapper.readTree(tool.execute(args));
            assertEquals("VALIDATION_ERROR", json.path("errorCode").asText(), args);
        }
        verify(boardStorageService, never()).updateNodeLayoutIfUnchanged(any(), any(), any(), any());
    }

    @Test
    void edit_unknownDeviceReturnsNotFound() throws Exception {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(device("d1", "AC")));

        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"id\":\"missing\",\"field\":\"label\",\"label\":\"X\"}"));

        assertEquals("NOT_FOUND", json.path("errorCode").asText());
        assertEquals(404, json.path("status").asInt());
        verify(boardStorageService, never()).renameNode(any(), any(), any(), any());
    }

    @Test
    void edit_unknownFieldRejected() throws Exception {
        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"color\"}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertFalse(json.path("error").asText().isEmpty());
    }

    @Test
    void editLayout_unchangedReportsNoChange() throws Exception {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(device("d1", "AC")));
        when(boardStorageService.updateNodeLayoutIfUnchanged(eq(1L), eq("d1"), any(), any()))
                .thenReturn(DeviceUpdateResultDto.builder()
                        .operation("unchanged")
                        .mutationType("layout")
                        .changedFields(List.of())
                        .currentDevice(device("d1", "AC"))
                        .build());

        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"layout\",\"x\":10,\"y\":20}"));

        assertEquals("unchanged", json.path("operation").asText());
        assertTrue(json.path("message").asText().toLowerCase().contains("no layout change"));
    }

    @Test
    void editLayout_reportsConcurrentBoardMovementWithoutOverwritingIt() throws Exception {
        DeviceNodeDto current = device("d1", "AC");
        DeviceNodeDto movedByUser = device("d1", "AC");
        movedByUser.getPosition().setX(500.0);
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(current));
        when(boardStorageService.updateNodeLayoutIfUnchanged(eq(1L), eq("d1"), any(), any()))
                .thenThrow(new DeviceLayoutConflictException(movedByUser));

        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"id\":\"d1\",\"field\":\"layout\",\"x\":300}"));

        assertEquals("DEVICE_LAYOUT_CONFLICT", json.path("errorCode").asText());
        assertEquals(409, json.path("status").asInt());
        assertEquals("notUpdated", json.path("operation").asText());
        assertEquals(500.0, json.path("currentDevice").path("position").path("x").asDouble());
    }
}
