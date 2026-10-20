package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageRuleToolTest {

    @Mock
    private BoardStorageService boardStorageService;

    private ManageRuleTool tool;
    private AiDestructiveActionGuard destructiveActionGuard;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        destructiveActionGuard = new AiDestructiveActionGuard(objectMapper);
        tool = new ManageRuleTool(boardStorageService, objectMapper, destructiveActionGuard);
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("rule-test-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void add_withUnsupportedRelation_shouldReject() {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        String argsJson = """
                {
                  "action":"add",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"state",
                      "targetType":"state",
                      "relation":"approx",
                      "value":"On"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"turnOn"
                  }
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("Unsupported relation"));
        verify(boardStorageService, never()).addRule(anyLong(), any());
    }

    @Test
    void add_withUnsupportedTargetType_shouldReject() {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        String argsJson = """
                {
                  "action":"add",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"LightMode",
                      "targetType":"unknown",
                      "relation":"=",
                      "value":"on"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"turnOn"
                  }
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("Unsupported targetType"));
        verify(boardStorageService, never()).addRule(anyLong(), any());
    }

    @Test
    void add_withModeTargetType_shouldPersistSemanticHint() throws Exception {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        stubSuccessfulRuleAdd();
        String argsJson = """
                {
                  "action":"add",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"LightMode",
                      "targetType":"mode",
                      "relation":"=",
                      "value":"on"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"turnOn"
                  }
                }
                """;

        String result = tool.execute(argsJson);

        JsonNode response = new ObjectMapper().readTree(result);
        assertEquals("NOT_VERIFIED", response.path("verificationStatus").asText());
        assertEquals(1, response.path("executionOrder").asInt());
        assertEquals("Light_1", response.path("rule").path("conditions").get(0).path("deviceId").asText());
        assertEquals("Light", response.path("rule").path("conditions").get(0).path("deviceLabel").asText());
        assertTrue(response.path("rule").path("conditions").get(0).path("deviceName").isMissingNode());
        assertTrue(response.path("message").asText().contains("not been formally verified"));
        verify(boardStorageService).addRule(anyLong(), argThat(rule ->
                rule.getConditions() != null
                        && !rule.getConditions().isEmpty()
                        && "mode".equals(rule.getConditions().get(0).getTargetType())
                        && rule.getRuleString() != null
                        && rule.getRuleString().contains("IF Light.LightMode = on THEN Light.turnOn")));
    }

    @Test
    void add_withLabel_shouldPersistProvidedUserText() {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        stubSuccessfulRuleAdd();
        String argsJson = """
                {
                  "action":"add",
                  "label":"When motion is detected, turn the light on.",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"motionDetected",
                      "targetType":"api"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"turnOn"
                  }
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("not been formally verified"));
        verify(boardStorageService).addRule(anyLong(), argThat(rule ->
                "When motion is detected, turn the light on.".equals(rule.getRuleString())));
    }

    @Test
    void add_withOrderedModeRelation_shouldRejectBeforeStorage() {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        String argsJson = """
                {
                  "action":"add",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"LightMode",
                      "targetType":"mode",
                      "relation":">",
                      "value":"on"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"turnOn"
                  }
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("targetType 'mode' only supports =, !=, in, not in"));
        verify(boardStorageService, never()).addRule(anyLong(), any());
    }

    @Test
    void add_withApiTargetTypeWithoutRelationValue_shouldPersistEventSignalShape() {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        stubSuccessfulRuleAdd();
        String argsJson = """
                {
                  "action":"add",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"on",
                      "targetType":"api"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"off"
                  }
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("not been formally verified"));
        verify(boardStorageService).addRule(anyLong(), argThat(rule ->
                rule.getConditions() != null
                        && !rule.getConditions().isEmpty()
                        && "api".equals(rule.getConditions().get(0).getTargetType())
                        && rule.getConditions().get(0).getRelation() == null
                        && rule.getConditions().get(0).getValue() == null));
    }

    @Test
    void add_withApiTargetTypeAndRelationValue_shouldReject() {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        String argsJson = """
                {
                  "action":"add",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"on",
                      "targetType":"api",
                      "relation":"=",
                      "value":"TRUE"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"off"
                  }
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("must not include relation or value"));
        verify(boardStorageService, never()).addRule(anyLong(), any());
    }

    @Test
    void add_withUnknownCommandApi_shouldRejectBeforeStorage() {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(lightTemplate()));
        String argsJson = """
                {
                  "action":"add",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"state",
                      "targetType":"state",
                      "relation":"=",
                      "value":"On"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"launch"
                  }
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("unknown API"));
        verify(boardStorageService, never()).addRule(anyLong(), any());
    }

    @Test
    void add_withIllegalVariableValue_shouldRejectBeforeStorage() {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(lightTemplate()));
        String argsJson = """
                {
                  "action":"add",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"motion",
                      "targetType":"variable",
                      "relation":"=",
                      "value":"maybe"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"turnOn"
                  }
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("Condition 1"));
        assertFalse(result.contains("Condition index"));
        assertTrue(result.contains("illegal enum value"));
        verify(boardStorageService, never()).addRule(anyLong(), any());
    }

    @Test
    void execute_whenJsonSerializationFails_shouldReturnStableErrorJson() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });

        ManageRuleTool fallbackTool = new ManageRuleTool(boardStorageService, failingMapper, destructiveActionGuard);
        UserContextHolder.clear();

        String result = fallbackTool.execute("{}");

        JsonNode json = new ObjectMapper().readTree(result);
        assertEquals("User not logged in", json.path("error").asText());
        assertEquals("UNAUTHORIZED", json.path("errorCode").asText());
        assertEquals(401, json.path("status").asInt());
    }

    @Test
    void add_whenResponseSerializationFails_shouldReturnResultUnavailable() throws Exception {
        ObjectMapper failingMapper = spy(new ObjectMapper());
        doThrow(new RuntimeException("boom")).when(failingMapper).writeValueAsString(any());

        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        stubSuccessfulRuleAdd();

        ManageRuleTool fallbackTool = new ManageRuleTool(boardStorageService, failingMapper, destructiveActionGuard);

        String argsJson = """
                {
                  "action":"add",
                  "conditions":[
                    {
                      "deviceId":"Light_1",
                      "attribute":"state",
                      "targetType":"state",
                      "relation":"=",
                      "value":"On"
                    }
                  ],
                  "command":{
                    "deviceId":"Light_1",
                    "action":"turnOn"
                  }
                }
                """;

        String result = fallbackTool.execute(argsJson);
        JsonNode json = new ObjectMapper().readTree(result);

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals(false, json.path("resultAvailable").asBoolean());
        assertTrue(json.path("warning").asText().contains("serialization failed"));
    }

    @Test
    void delete_requiresPreviewAndExplicitLaterConfirmation() throws Exception {
        RuleDto target = RuleDto.builder().id(9L).ruleString("When smoke is detected, sound alarm").build();
        when(boardStorageService.getRules(1L)).thenReturn(List.of(target));
        when(boardStorageService.removeRuleIfUnchanged(1L, 9L, target))
                .thenReturn(CollectionMutationResultDto.of("deleted", target, List.of()));

        String preview = tool.execute("{\"action\":\"delete\",\"ruleId\":9,\"confirmed\":true}");
        JsonNode previewJson = new ObjectMapper().readTree(preview);
        assertTrue(previewJson.path("requiresUserConfirmation").asBoolean());
        assertEquals("When smoke is detected, sound alarm", destructiveActionGuard
                .pendingContext(1L, "rule-test-session").orElseThrow().displayTarget());
        verify(boardStorageService, never()).removeRuleIfUnchanged(anyLong(), anyLong(), any());

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", previewJson.path("impactToken").asText());
        String result = tool.execute("{\"action\":\"delete\",\"ruleId\":9,\"confirmed\":true,\"impactToken\":\""
                + previewJson.path("impactToken").asText() + "\"}");
        JsonNode resultJson = new ObjectMapper().readTree(result);
        assertEquals("deleted", resultJson.path("operation").asText());
        verify(boardStorageService).removeRuleIfUnchanged(1L, 9L, target);
    }

    @Test
    void add_rejectsWrongScalarTypesAndActionIrrelevantFieldsBeforeStorage() throws Exception {
        JsonNode numericValue = new ObjectMapper().readTree(tool.execute("""
                {
                  "action":"add",
                  "conditions":[{
                    "deviceId":"Light_1",
                    "attribute":"state",
                    "targetType":"state",
                    "relation":"=",
                    "value":1
                  }],
                  "command":{"deviceId":"Light_1","action":"turnOn"}
                }
                """));
        JsonNode irrelevantDeleteField = new ObjectMapper().readTree(tool.execute("""
                {
                  "action":"add",
                  "ruleId":9,
                  "conditions":[],
                  "command":{}
                }
                """));

        assertEquals("VALIDATION_ERROR", numericValue.path("errorCode").asText());
        assertTrue(numericValue.path("error").asText().contains("conditions[0].value"));
        assertEquals("VALIDATION_ERROR", irrelevantDeleteField.path("errorCode").asText());
        assertTrue(irrelevantDeleteField.path("error").asText().contains("ruleId"));
        verify(boardStorageService, never()).addRule(anyLong(), any());
    }

    private void stubSuccessfulRuleAdd() {
        when(boardStorageService.addRule(anyLong(), any())).thenAnswer(invocation -> {
            RuleDto rule = invocation.getArgument(1);
            return CollectionMutationResultDto.of("created", rule, List.of(rule));
        });
    }

    @Test
    void reorder_withCompleteOrder_shouldReplaceExecutionOrder() throws Exception {
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node("Light_1", "Light")));
        RuleDto first = RuleDto.builder().id(2L).ruleString("Rule two").build();
        RuleDto second = RuleDto.builder().id(1L).ruleString("Rule one").build();
        when(boardStorageService.reorderRules(1L, List.of(1L, 2L), List.of(2L, 1L)))
                .thenReturn(CollectionMutationResultDto.of("reordered", null, List.of(first, second)));

        JsonNode json = objectMapper().readTree(tool.execute(
                "{\"action\":\"reorder\",\"expectedRuleIds\":[1,2],\"ruleIds\":[2,1]}"));

        assertEquals("reordered", json.path("operation").asText());
        assertEquals("NOT_VERIFIED", json.path("verificationStatus").asText());
        assertEquals(2, json.path("totalRules").asInt());
        assertEquals(2L, json.path("rules").get(0).path("ruleId").asLong());
        assertEquals(1L, json.path("rules").get(1).path("ruleId").asLong());
        verify(boardStorageService).reorderRules(1L, List.of(1L, 2L), List.of(2L, 1L));
    }

    @Test
    void reorder_withMissingRuleIds_shouldReject() throws Exception {
        JsonNode json = objectMapper().readTree(tool.execute("{\"action\":\"reorder\"}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(boardStorageService, never()).reorderRules(anyLong(), any(), any());
    }

    @Test
    void reorder_withNonPositiveId_shouldReject() throws Exception {
        JsonNode json = objectMapper().readTree(tool.execute(
                "{\"action\":\"reorder\",\"expectedRuleIds\":[1,2],\"ruleIds\":[1,0]}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(boardStorageService, never()).reorderRules(anyLong(), any(), any());
    }

    @Test
    void reorder_withIdOutsideLongRange_shouldRejectWithoutTruncating() throws Exception {
        JsonNode json = objectMapper().readTree(tool.execute(
                "{\"action\":\"reorder\",\"expectedRuleIds\":[1],\"ruleIds\":[18446744073709551617]}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(boardStorageService, never()).reorderRules(anyLong(), any(), any());
    }

    @Test
    void reorder_rejectsFieldFromOtherAction() throws Exception {
        JsonNode json = objectMapper().readTree(tool.execute(
                "{\"action\":\"reorder\",\"expectedRuleIds\":[1],\"ruleIds\":[1],\"ruleId\":5}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(boardStorageService, never()).reorderRules(anyLong(), any(), any());
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Test
    void updateChangesOnlyTheRequestedRuleInPlaceAndKeepsItsNameAndOrder() throws Exception {
        BoardSemanticSnapshotDto baseline = numericRuleSnapshot(28, 100);
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(baseline);
        when(boardStorageService.updateRulesAgainstSnapshot(anyLong(), any())).thenAnswer(invocation -> {
            java.util.function.Function<BoardSemanticSnapshotDto, List<RuleDto>> update = invocation.getArgument(1);
            List<RuleDto> next = update.apply(baseline);
            assertEquals(2, next.size());
            assertEquals(List.of(11L, 12L), next.stream().map(RuleDto::getId).toList());
            assertEquals("30", next.get(0).getConditions().get(0).getValue());
            assertEquals(">", next.get(0).getConditions().get(0).getRelation());
            assertEquals("Cooling threshold", next.get(0).getRuleString());
            assertEquals(baseline.rules().get(1), next.get(1));
            assertEquals("28", baseline.rules().get(0).getConditions().get(0).getValue());
            return CollectionMutationResultDto.of("updated", null, next);
        });

        JsonNode result = objectMapper().readTree(tool.execute(updateArgs(baseline, "30")));
        assertEquals("updated", result.path("operation").asText());
        assertEquals(11L, result.path("rule").path("ruleId").asLong());
        assertEquals(1, result.path("executionOrder").asInt());
        assertEquals(2, result.path("totalRules").asInt());
        assertEquals("NOT_VERIFIED", result.path("verificationStatus").asText());
        verify(boardStorageService, never()).addRule(anyLong(), any());
        verify(boardStorageService, never()).removeRuleIfUnchanged(anyLong(), anyLong(), any());
    }

    @Test
    void updateRejectsAChangedRuleAtTheAtomicCommitBoundary() throws Exception {
        BoardSemanticSnapshotDto baseline = numericRuleSnapshot(28, 100);
        BoardSemanticSnapshotDto changed = numericRuleSnapshot(31, 100);
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(baseline);
        when(boardStorageService.updateRulesAgainstSnapshot(anyLong(), any())).thenAnswer(invocation -> {
            java.util.function.Function<BoardSemanticSnapshotDto, List<RuleDto>> update = invocation.getArgument(1);
            return CollectionMutationResultDto.of("updated", null, update.apply(changed));
        });
        JsonNode result = objectMapper().readTree(tool.execute(updateArgs(baseline, "30")));
        assertEquals(409, result.path("status").asInt());
        assertFalse(result.has("operation"));
        assertEquals("31", changed.rules().get(0).getConditions().get(0).getValue());
    }

    @Test
    void updateRejectsAChangedTemplateDomainAtTheAtomicCommitBoundary() throws Exception {
        BoardSemanticSnapshotDto baseline = numericRuleSnapshot(28, 100);
        BoardSemanticSnapshotDto changed = numericRuleSnapshot(28, 20);
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(baseline);
        when(boardStorageService.updateRulesAgainstSnapshot(anyLong(), any())).thenAnswer(invocation -> {
            java.util.function.Function<BoardSemanticSnapshotDto, List<RuleDto>> update = invocation.getArgument(1);
            return CollectionMutationResultDto.of("updated", null, update.apply(changed));
        });
        JsonNode result = objectMapper().readTree(tool.execute(updateArgs(baseline, "30")));
        assertEquals(400, result.path("status").asInt());
        assertFalse(result.has("operation"));
        assertEquals("28", changed.rules().get(0).getConditions().get(0).getValue());
    }

    @Test
    void updateRejectsInvalidValuesBeforeEnteringTheWriteBoundary() throws Exception {
        BoardSemanticSnapshotDto baseline = numericRuleSnapshot(28, 100);
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(baseline);
        JsonNode result = objectMapper().readTree(tool.execute(updateArgs(baseline, "101")));
        assertEquals(400, result.path("status").asInt());
        verify(boardStorageService, never()).updateRulesAgainstSnapshot(anyLong(), any());
        verify(boardStorageService, never()).addRule(anyLong(), any());
    }

    private BoardSemanticSnapshotDto numericRuleSnapshot(int threshold, int upperBound) {
        DeviceTemplateDto template = lightTemplate();
        template.getManifest().setInternalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                .name("temperature").isInside(true).lowerBound(0).upperBound(upperBound).build()));
        RuleDto first = RuleDto.builder().id(11L).userId(1L).ruleString("Cooling threshold")
                .conditions(List.of(RuleDto.Condition.builder().deviceName("Light_1").attribute("temperature")
                        .targetType("variable").relation(">").value(String.valueOf(threshold)).build()))
                .command(RuleDto.Command.builder().deviceName("Light_1").action("turnOn").build()).build();
        RuleDto second = RuleDto.builder().id(12L).userId(1L).ruleString("Heating threshold")
                .conditions(List.of(RuleDto.Condition.builder().deviceName("Light_1").attribute("temperature")
                        .targetType("variable").relation("<").value("18").build()))
                .command(RuleDto.Command.builder().deviceName("Light_1").action("turnOff").build()).build();
        return new BoardSemanticSnapshotDto(List.of(node("Light_1", "Light")), List.of(),
                List.of(first, second), List.of(), List.of(template));
    }

    private String updateArgs(BoardSemanticSnapshotDto baseline, String value) throws Exception {
        var args = objectMapper().createObjectNode();
        args.put("action", "update");
        args.put("ruleId", 11);
        args.set("expectedRule", objectMapper().valueToTree(RuleToolPresenter.present(baseline.rules().get(0), baseline.nodes())));
        args.set("conditions", objectMapper().readTree("""
                [{"deviceId":"Light_1","attribute":"temperature","targetType":"variable","relation":">","value":"30"}]
                """));
        ((com.fasterxml.jackson.databind.node.ObjectNode) args.path("conditions").get(0)).put("value", value);
        args.set("command", objectMapper().readTree("{\"deviceId\":\"Light_1\",\"action\":\"turnOn\"}"));
        return args.toString();
    }

    private DeviceNodeDto node(String id, String label) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId(id);
        node.setLabel(label);
        node.setTemplateName("Light");
        return node;
    }

    private DeviceTemplateDto lightTemplate() {
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Light");
        DeviceTemplateDto.DeviceManifest manifest = new DeviceTemplateDto.DeviceManifest();
        manifest.setName("Light");
        manifest.setModes(List.of("Switch"));
        manifest.setInitState("Off");
        manifest.setWorkingStates(List.of(
                workingState("Off"),
                workingState("On")
        ));
        manifest.setInternalVariables(List.of(
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("motion")
                        .isInside(false)
                        .values(List.of("yes", "no"))
                        .build()
        ));
        manifest.setApis(List.of(
                api("turnOn", false),
                api("turnOff", false),
                api("motionDetected", true)
        ));
        template.setManifest(manifest);
        return template;
    }

    private DeviceTemplateDto.DeviceManifest.WorkingState workingState(String name) {
        DeviceTemplateDto.DeviceManifest.WorkingState state = new DeviceTemplateDto.DeviceManifest.WorkingState();
        state.setName(name);
        state.setTrust("trusted");
        state.setDescription("");
        state.setDynamics(List.of());
        return state;
    }

    private DeviceTemplateDto.DeviceManifest.API api(String name, boolean signal) {
        DeviceTemplateDto.DeviceManifest.API api = new DeviceTemplateDto.DeviceManifest.API();
        api.setName(name);
        api.setSignal(signal);
        return api;
    }
}
