package cn.edu.nju.Iot_Verify.component.aitool.board;

import cn.edu.nju.Iot_Verify.component.aitool.rule.ListRulesTool;
import cn.edu.nju.Iot_Verify.component.aitool.spec.ListSpecsTool;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardOverviewToolTest {

    @Mock
    private BoardStorageService boardStorageService;

    private ObjectMapper objectMapper;
    private BoardOverviewTool tool;

    @ParameterizedTest
    @ValueSource(strings = {"{\"detail\":false} {\"detail\":true}",
            "{\"detail\":false} trailing text", "{\"detail\":true,\"detail\":false}"})
    void ambiguousJsonIsRejectedBeforeReadingTheBoard(String arguments) throws Exception {
        UserContextHolder.setUserId(1L);
        JsonNode result = objectMapper.readTree(tool.execute(arguments));
        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertEquals(400, result.path("status").asInt());
        verifyNoMoreInteractions(boardStorageService);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new BoardOverviewTool(boardStorageService, objectMapper);
        UserContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void execute_includesEachActiveFullManifestOnceWithoutUiIconsOrUnusedTemplates() throws Exception {
        UserContextHolder.setUserId(1L);
        DeviceTemplateDto camera = new DeviceTemplateDto();
        camera.setName("Saved Camera");
        camera.setManifest(DeviceTemplateDto.DeviceManifest.builder().name("Camera")
                .icon("data:image/png;base64," + "x".repeat(70000)).modes(List.of("Power"))
                .initState("on").description("Preserve every behavior field").build());
        DeviceTemplateDto phone = new DeviceTemplateDto();
        phone.setName("Phone");
        phone.setManifest(DeviceTemplateDto.DeviceManifest.builder().name("Phone").modes(List.of("Power")).build());
        DeviceTemplateDto unused = new DeviceTemplateDto();
        unused.setName("Unused");
        unused.setManifest(DeviceTemplateDto.DeviceManifest.builder().name("Unused").build());
        List<DeviceNodeDto> nodes = java.util.stream.IntStream.range(0, 3).mapToObj(index -> {
            DeviceNodeDto node = new DeviceNodeDto();
            node.setId("device-" + index);
            node.setTemplateName(index == 2 ? "Phone" : "Saved Camera");
            return node;
        }).toList();
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(new BoardSemanticSnapshotDto(
                nodes, List.of(), List.of(), List.of(), List.of(camera, phone, unused)));

        JsonNode result = objectMapper.readTree(tool.execute("{}"));

        assertEquals(3, result.path("deviceCount").asInt());
        assertEquals(2, result.path("templates").size());
        assertEquals("Saved Camera", result.path("templates").get(0).path("name").asText());
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.valueToTree(camera.getManifest());
        expected.remove("Icon");
        assertEquals(expected, result.path("templates").get(0).path("manifest"));
        assertEquals("Phone", result.path("templates").get(1).path("name").asText());
        assertTrue(result.path("unavailableBehaviorDeviceIds").isEmpty());
        verify(boardStorageService).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(boardStorageService);
    }

    @Test
    void execute_detailFalseKeepsCountsAvailableWithoutManifestPayloads() throws Exception {
        UserContextHolder.setUserId(1L);
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(new BoardSemanticSnapshotDto(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        JsonNode result = objectMapper.readTree(tool.execute("{\"detail\":false}"));
        assertEquals(0, result.path("deviceCount").asInt());
        assertFalse(result.has("templates"));
        assertTrue(result.path("message").asText().contains("detail=false"));
    }

    @Test
    void execute_nonBooleanDetailIsRejectedBeforeReadingTheSnapshot() throws Exception {
        UserContextHolder.setUserId(1L);
        assertEquals("VALIDATION_ERROR", objectMapper.readTree(tool.execute("{\"detail\":\"true\"}"))
                .path("errorCode").asText());
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
    }

    @Test
    void execute_withoutLogin_shouldReturnErrorJson() throws Exception {
        String result = tool.execute("{}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("UNAUTHORIZED", json.path("errorCode").asText());
        assertEquals(401, json.path("status").asInt());
    }

    @Test
    void execute_withUnknownField_shouldRejectBeforeReadingBoard() throws Exception {
        UserContextHolder.setUserId(1L);
        JsonNode json = objectMapper.readTree(tool.execute("{\"includeRaw\":true}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
    }

    @Test
    void execute_snapshotUnavailable_shouldPreserveFailure() throws Exception {
        UserContextHolder.setUserId(1L);
        when(boardStorageService.getSemanticSnapshot(1L))
                .thenThrow(new ServiceUnavailableException("Board snapshot is unavailable"));

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals("SERVICE_UNAVAILABLE", json.path("errorCode").asText());
        assertEquals(503, json.path("status").asInt());
        assertTrue(json.path("deviceCount").isMissingNode());
        verify(boardStorageService).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(boardStorageService);
    }

    @Test
    void execute_indexesDirectedDependenciesWithoutConnectingPeersAndIncludesContentSources() throws Exception {
        UserContextHolder.setUserId(1L);
        List<DeviceNodeDto> nodes = List.of("bed", "clock", "air", "lamp", "window", "camera", "hub", "phone")
                .stream().map(id -> {
                    DeviceNodeDto node = new DeviceNodeDto();
                    node.setId(id);
                    node.setLabel(id);
                    return node;
                }).toList();
        RuleDto lampRule = RuleDto.builder().id(1L).conditions(List.of(
                RuleDto.Condition.builder().deviceName("bed").targetType("variable")
                        .attribute("bedOccupancy").relation("=").value("empty").build(),
                RuleDto.Condition.builder().deviceName("clock").targetType("variable")
                        .attribute("time").relation("=").value("23").build()))
                .command(RuleDto.Command.builder().deviceName("lamp").action("on").build()).build();
        RuleDto windowRule = RuleDto.builder().id(2L).conditions(List.of(
                RuleDto.Condition.builder().deviceName("air").targetType("variable")
                        .attribute("carbonDioxide").relation(">").value("60").build(),
                RuleDto.Condition.builder().deviceName("clock").targetType("variable")
                        .attribute("time").relation("=").value("23").build()))
                .command(RuleDto.Command.builder().deviceName("window").action("open").build()).build();
        RuleDto cameraRule = RuleDto.builder().id(3L).conditions(List.of(
                RuleDto.Condition.builder().deviceName("lamp").targetType("api").attribute("on").build()))
                .command(RuleDto.Command.builder().deviceName("camera").action("take photo").build()).build();
        RuleDto photoRule = RuleDto.builder().id(4L).ruleString("Send the camera photo").conditions(List.of(
                RuleDto.Condition.builder().deviceName("camera").targetType("api").attribute("take photo").build()))
                .command(RuleDto.Command.builder().deviceName("hub").action("send photo")
                        .contentDevice("phone").content("photo").build()).build();
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(new BoardSemanticSnapshotDto(
                nodes, List.of(), List.of(lampRule, windowRule, cameraRule, photoRule), List.of(), List.of()));

        JsonNode result = objectMapper.readTree(tool.execute("{}"));

        assertEquals(objectMapper.valueToTree(java.util.Map.of("bed", List.of("lamp"),
                "clock", List.of("lamp", "window"), "air", List.of("window"),
                "lamp", List.of("camera"), "camera", List.of("hub"))), result.path("directRuleTargets"));
        assertEquals(java.util.Set.of("bed", "clock", "air", "lamp", "window", "camera", "hub", "phone"),
                objectMapper.convertValue(result.path("behaviorDeviceIds"), java.util.Set.class));
        assertFalse(result.path("directRuleTargets").has("phone"));
        assertFalse(result.path("directRuleTargets").has("window"));
        assertEquals(4, result.path("ruleCount").asInt());
        assertEquals(6, result.path("edgeCount").asInt());
        assertEquals(8, result.path("unavailableBehaviorDeviceIds").size(), "Unavailable behavior must stay explicit");
        JsonNode listed = objectMapper.readTree(new ListRulesTool(boardStorageService, objectMapper).execute("{}"));
        assertEquals("Send the camera photo", result.path("rules").get(3).path("label").asText());
        assertEquals("phone", result.path("rules").get(3).path("command").path("contentDevice").asText());
        assertEquals(listed.path("rules"), result.path("rules"));
        verify(boardStorageService, times(2)).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(boardStorageService);
    }

    @Test
    void execute_shouldDeriveEdgeSummaryFromRules() throws Exception {
        UserContextHolder.setUserId(1L);

        DeviceNodeDto light = new DeviceNodeDto();
        light.setId("n1");
        light.setLabel("Light");
        light.setTemplateName("LightTemplate");
        light.setState("off");
        light.setCurrentStateTrust("untrusted");
        light.setCurrentStatePrivacy("private");
        light.setVariables(List.of(new VariableStateDto("brightness", "20", "trusted")));
        light.setPrivacies(List.of(new PrivacyStateDto("brightness", "private")));

        DeviceNodeDto sensor = new DeviceNodeDto();
        sensor.setId("n2");
        sensor.setLabel("Sensor");
        sensor.setTemplateName("SensorTemplate");
        sensor.setState("idle");

        RuleDto rule = RuleDto.builder()
                .id(1L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("n1")
                        .attribute("state")
                        .targetType("state")
                        .relation("=")
                        .value("on")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("n2")
                        .action("turnOn")
                        .build())
                .build();

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setTemplateLabel("Always");
        SpecConditionDto specCondition = new SpecConditionDto();
        specCondition.setDeviceId("n1");
        specCondition.setDeviceLabel("stale label");
        specCondition.setTargetType("state");
        specCondition.setKey("state");
        specCondition.setRelation("=");
        specCondition.setValue("on");
        spec.setAConditions(List.of(specCondition));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());
        spec.setFormula("CTLSPEC AG(n1.state = on)");

        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(new BoardSemanticSnapshotDto(
                List.of(light, sensor),
                List.of(new BoardEnvironmentVariableDto("temperature", "28", "untrusted", "private")),
                List.of(rule), List.of(spec), List.of()));

        String result = tool.execute("{}");
        JsonNode json = objectMapper.readTree(result);

        JsonNode listedSpecs = objectMapper.readTree(new ListSpecsTool(boardStorageService, objectMapper).execute("{}"));
        assertEquals(listedSpecs.path("specs"), json.path("specs"));
        assertTrue(json.path("rules").get(0).path("expectedVersion").asText().matches("v1:[0-9a-f]{64}"));
        assertTrue(json.path("specs").get(0).path("expectedVersion").asText().matches("v1:[0-9a-f]{64}"));
        verify(boardStorageService, times(2)).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(boardStorageService);

        assertEquals(2, json.path("deviceCount").asInt());
        assertTrue(json.path("message").asText().contains("not complete device behavior"));
        assertTrue(json.path("message").asText().contains("deviceIds copied from devices[].id"));
        assertEquals(List.of("n1", "n2"), objectMapper.convertValue(json.path("unavailableBehaviorDeviceIds"), List.class));
        assertTrue(json.path("message").asText().contains("Counts and configured links do not need that extra read"));
        assertEquals(1, json.path("environmentVariableCount").asInt());
        assertEquals("untrusted", json.path("devices").get(0).path("currentStateTrust").asText());
        assertEquals("private", json.path("devices").get(0).path("currentStatePrivacy").asText());
        assertEquals("brightness", json.path("devices").get(0).path("variables").get(0).path("name").asText());
        assertEquals("temperature", json.path("environmentVariables").get(0).path("name").asText());
        assertEquals(1, json.path("edgeCount").asInt());
        assertFalse(json.path("edges").get(0).has("id"));
        assertEquals(1L, json.path("edges").get(0).path("ruleId").asLong());
        assertFalse(json.path("edges").get(0).has("conditionIndex"));
        assertFalse(json.path("rules").get(0).has("id"));
        assertEquals(1L, json.path("rules").get(0).path("ruleId").asLong());
        assertFalse(json.path("specs").get(0).has("id"));
        assertEquals("spec_1", json.path("specs").get(0).path("specId").asText());
        assertFalse(json.path("specs").get(0).has("formula"));
        assertEquals("n1", json.path("edges").get(0).path("from").asText());
        assertEquals("n2", json.path("edges").get(0).path("to").asText());
        assertEquals("Light", json.path("edges").get(0).path("fromLabel").asText());
        assertEquals("Sensor", json.path("edges").get(0).path("toLabel").asText());
        assertEquals("state", json.path("edges").get(0).path("sourceAttribute").asText());
        assertEquals("turnOn", json.path("edges").get(0).path("targetAction").asText());
        assertEquals("n1", json.path("rules").get(0).path("conditions").get(0).path("deviceId").asText());
        assertEquals("Light", json.path("rules").get(0).path("conditions").get(0).path("deviceLabel").asText());
        assertEquals("Light.state = on", json.path("rules").get(0).path("conditions").get(0).path("summary").asText());
        assertEquals("Sensor", json.path("rules").get(0).path("command").path("deviceLabel").asText());
        assertEquals("IF Light.state = on THEN Sensor.turnOn",
                json.path("rules").get(0).path("description").asText());
        assertFalse(json.path("rules").get(0).path("description").asText().contains("n1"));
        assertEquals("Light", json.path("specs").get(0).path("aConditions").get(0).path("deviceLabel").asText());
        assertEquals("Light state = on", json.path("specs").get(0).path("aConditions").get(0).path("summary").asText());
        assertEquals("CTL AG(\"Light\".state = \"on\")",
                json.path("specs").get(0).path("formulaPreview").asText());
        assertFalse(json.path("specs").get(0).path("formulaPreview").asText().contains("n1"));
    }
}
