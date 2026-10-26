package cn.edu.nju.Iot_Verify.component.aitool.node;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceCreationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceDeletionResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.DeviceLabelConflictException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.NodeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeToolsErrorTest {

    @Mock
    private NodeService nodeService;
    @Mock
    private BoardStorageService boardStorageService;

    private ObjectMapper objectMapper;
    private AiDestructiveActionGuard destructiveActionGuard;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        destructiveActionGuard = new AiDestructiveActionGuard(objectMapper);
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("node-test-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void addNodeTool_whenServiceThrowsBaseException_shouldReturnStructuredError() throws Exception {
        AddNodeTool tool = new AddNodeTool(nodeService, objectMapper);
        when(nodeService.addNode(1L, "UnknownTemplate", null, null, null, null, null, null))
                .thenThrow(new BadRequestException("invalid template"));

        String result = tool.execute("{\"templateName\":\"UnknownTemplate\"}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("invalid template", json.path("error").asText());
        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
    }

    @Test
    void addNodeTool_whenExplicitLabelConflicts_shouldExplainThatNothingWasCreated() throws Exception {
        AddNodeTool tool = new AddNodeTool(nodeService, objectMapper);
        when(nodeService.addNode(1L, "Light", "Hall Light", null, null, null, null, null))
                .thenThrow(new DeviceLabelConflictException("Hall Light", "Hall Light_1"));

        JsonNode json = objectMapper.readTree(
                tool.execute("{\"templateName\":\"Light\",\"label\":\"Hall Light\"}"));

        assertEquals("DEVICE_LABEL_CONFLICT", json.path("errorCode").asText());
        assertEquals(409, json.path("status").asInt());
        assertEquals("notCreated", json.path("operation").asText());
        assertEquals("Hall Light", json.path("requestedLabel").asText());
        assertEquals("Hall Light_1", json.path("suggestedLabel").asText());
        assertTrue(json.path("requiresUserConfirmation").asBoolean());
        assertTrue(json.path("userActionRequired").asBoolean());
    }

    @Test
    void deleteNodeTool_whenDeviceMissing_shouldReturnStructuredError() throws Exception {
        DeleteNodeTool tool = new DeleteNodeTool(boardStorageService, objectMapper, destructiveActionGuard);
        when(boardStorageService.previewNodeDeletion(1L, "ghost"))
                .thenThrow(new ResourceNotFoundException("Device", "ghost"));

        String result = tool.execute("{\"id\":\"ghost\",\"confirmed\":false}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("Device not found: ghost", json.path("error").asText());
        assertEquals("NOT_FOUND", json.path("errorCode").asText());
        assertEquals(404, json.path("status").asInt());
    }

    @Test
    void deleteNodeTool_rejectsCoercedOrUnknownIdentityFieldsBeforePreview() throws Exception {
        DeleteNodeTool tool = new DeleteNodeTool(boardStorageService, objectMapper, destructiveActionGuard);

        JsonNode numericId = objectMapper.readTree(tool.execute("{\"id\":42,\"confirmed\":false}"));
        JsonNode unknownField = objectMapper.readTree(tool.execute(
                "{\"id\":\"device_1\",\"confirmed\":false,\"label\":\"Kitchen\"}"));

        assertEquals("VALIDATION_ERROR", numericId.path("errorCode").asText());
        assertTrue(numericId.path("error").asText().contains("non-empty string"));
        assertEquals("VALIDATION_ERROR", unknownField.path("errorCode").asText());
        assertTrue(unknownField.path("error").asText().contains("label"));
        verifyNoInteractions(boardStorageService);
    }

    @Test
    void addNodeTool_whenResponseSerializationFails_shouldReturnFallbackSuccess() throws Exception {
        ObjectMapper failingMapper = spy(new ObjectMapper());
        doThrow(new RuntimeException("boom")).when(failingMapper).writeValueAsString(any());
        AddNodeTool tool = new AddNodeTool(nodeService, failingMapper);
        when(nodeService.addNode(1L, "Air Conditioner", null, null, null, null, null, null))
                .thenReturn(DeviceCreationResultDto.builder()
                        .device(node("ac_1", "ac_1", "Air Conditioner"))
                        .initialStateSource("templateDefault")
                        .build());

        String result = tool.execute("{\"templateName\":\"Air Conditioner\"}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals(false, json.path("resultAvailable").asBoolean());
        assertTrue(json.path("warning").asText().contains("serialization failed"));
    }

    @Test
    void deleteNodeTool_whenResponseSerializationFails_shouldReturnFallbackSuccess() throws Exception {
        DeviceNodeDto node = node("ac_1", "ac_1", "Light");
        DeviceDeletionResultDto preview = DeviceDeletionResultDto.builder()
                .operation("preview")
                .impactToken("preview-token")
                .deletedDevice(node)
                .build();
        when(boardStorageService.previewNodeDeletion(1L, "ac_1")).thenReturn(preview);
        DeleteNodeTool previewTool = new DeleteNodeTool(boardStorageService, objectMapper, destructiveActionGuard);
        String impactToken = objectMapper.readTree(
                previewTool.execute("{\"id\":\"ac_1\",\"confirmed\":false}"))
                .path("impactToken").asText();

        ObjectMapper failingMapper = spy(new ObjectMapper());
        doThrow(new RuntimeException("boom")).when(failingMapper).writeValueAsString(any());
        DeleteNodeTool tool = new DeleteNodeTool(boardStorageService, failingMapper, destructiveActionGuard);
        when(boardStorageService.deleteNodeCascade(1L, "ac_1", "preview-token"))
                .thenReturn(DeviceDeletionResultDto.builder()
                        .deletedDevice(node)
                        .build());
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", impactToken);

        String result = tool.execute("{\"id\":\"ac_1\",\"confirmed\":true,\"impactToken\":\""
                + impactToken + "\"}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals(false, json.path("resultAvailable").asBoolean());
        assertTrue(json.path("warning").asText().contains("serialization failed"));
    }

    @Test
    void deleteNodeTool_deletesOnlyTargetNodeAndRelatedRulesSpecsAtomically() throws Exception {
        DeleteNodeTool tool = new DeleteNodeTool(boardStorageService, objectMapper, destructiveActionGuard);
        DeviceNodeDto target = node("light_1", "KitchenLight", "Light");
        DeviceNodeDto variable = node("light_1_power", "power", "variable_power");
        DeviceNodeDto other = node("door_1", "Door", "Door");

        RuleDto relatedRule = RuleDto.builder()
                .id(10L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("light_1")
                        .attribute("state")
                        .targetType("state")
                        .relation("=")
                        .value("on")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("door_1")
                        .action("lock")
                        .build())
                .build();
        RuleDto remainingRule = RuleDto.builder()
                .id(11L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("door_1")
                        .attribute("state")
                        .targetType("state")
                        .relation("=")
                        .value("open")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("door_1")
                        .action("close")
                        .build())
                .build();

        SpecificationDto relatedSpec = spec("spec-light", "light_1");
        SpecificationDto remainingSpec = spec("spec-door", "door_1");
        EnvironmentVariableChangeDto removedEnvironment = EnvironmentVariableChangeDto.builder()
                .changeType(EnvironmentVariableChangeDto.ChangeType.REMOVED)
                .name("illuminance")
                .previousValue(new BoardEnvironmentVariableDto(
                        "illuminance", "20", "untrusted", "public"))
                .build();

        DeviceDeletionResultDto preview = DeviceDeletionResultDto.builder()
                .operation("preview")
                .impactToken("preview-token")
                .deletedDevice(target)
                .removedRules(List.of(relatedRule))
                .removedSpecifications(List.of(relatedSpec))
                .environmentChanges(List.of(removedEnvironment))
                .currentNodes(List.of(target, variable, other))
                .currentRules(List.of(relatedRule, remainingRule))
                .currentSpecifications(List.of(relatedSpec, remainingSpec))
                .build();
        when(boardStorageService.previewNodeDeletion(1L, "light_1")).thenReturn(preview);
        when(boardStorageService.deleteNodeCascade(1L, "light_1", "preview-token"))
                .thenReturn(DeviceDeletionResultDto.builder()
                        .operation("deleted")
                        .deletedDevice(target)
                        .removedRules(List.of(relatedRule))
                        .removedSpecifications(List.of(relatedSpec))
                        .environmentChanges(List.of(removedEnvironment))
                        .currentNodes(List.of(variable, other))
                        .currentRules(List.of(remainingRule))
                        .currentSpecifications(List.of(remainingSpec))
                        .build());

        String previewResult = tool.execute("{\"id\":\"light_1\",\"confirmed\":false}");
        JsonNode previewJson = objectMapper.readTree(previewResult);
        assertTrue(previewJson.path("requiresUserConfirmation").asBoolean());
        assertEquals("KitchenLight", destructiveActionGuard.pendingContext(1L, "node-test-session")
                .orElseThrow().displayTarget());
        verify(boardStorageService, org.mockito.Mockito.never())
                .deleteNodeCascade(1L, "light_1", "preview-token");
        assertEquals("illuminance",
                previewJson.path("environmentChanges").path(0).path("name").asText());

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", previewJson.path("impactToken").asText());
        String result = tool.execute("{\"id\":\"light_1\",\"confirmed\":true,\"impactToken\":\""
                + previewJson.path("impactToken").asText() + "\"}");
        JsonNode json = objectMapper.readTree(result);

        verify(boardStorageService).deleteNodeCascade(1L, "light_1", "preview-token");
        assertEquals(1, json.path("removedRuleCount").asInt());
        assertEquals(10L, json.path("removedRules").path(0).path("id").asLong());
        assertEquals(1, json.path("removedSpecificationCount").asInt());
        assertEquals("spec-light", json.path("removedSpecifications").path(0).path("id").asText());
        assertEquals("illuminance", json.path("environmentChanges").path(0).path("name").asText());
    }

    private DeviceNodeDto node(String id, String label, String templateName) {
        DeviceNodeDto dto = new DeviceNodeDto();
        dto.setId(id);
        dto.setLabel(label);
        dto.setTemplateName(templateName);
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(0.0);
        position.setY(0.0);
        dto.setPosition(position);
        dto.setState("on");
        dto.setWidth(176);
        dto.setHeight(128);
        return dto;
    }

    private SpecificationDto spec(String id, String deviceId) {
        SpecificationDto dto = new SpecificationDto();
        dto.setId(id);
        dto.setName("Safety requirement");
        dto.setTemplateId("2");
        dto.setTemplateLabel("Spec");

        SpecConditionDto condition = new SpecConditionDto();
        condition.setId(id + "-condition");
        condition.setSide("a");
        condition.setDeviceId(deviceId);
        condition.setDeviceLabel(deviceId);
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("on");

        dto.setAConditions(new ArrayList<>(List.of(condition)));
        dto.setIfConditions(new ArrayList<>());
        dto.setThenConditions(new ArrayList<>());
        dto.setDevices(new ArrayList<>());
        return dto;
    }
}
