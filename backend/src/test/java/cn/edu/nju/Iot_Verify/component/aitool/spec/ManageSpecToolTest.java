package cn.edu.nju.Iot_Verify.component.aitool.spec;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResultContract;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageSpecToolTest {

    @Mock
    private BoardStorageService boardStorageService;

    private ManageSpecTool tool;
    private AiDestructiveActionGuard destructiveActionGuard;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        destructiveActionGuard = new AiDestructiveActionGuard(objectMapper);
        tool = new ManageSpecTool(boardStorageService, objectMapper, destructiveActionGuard);
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("spec-test-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void updateKeepsTheSpecificationIdentityAndUsesTheExpectedSnapshot() throws Exception {
        SpecificationDto before = prepareSpecificationUpdate();
        when(boardStorageService.updateSpecIfUnchanged(eq(1L), eq("spec-1"), eq(before), any()))
                .thenAnswer(invocation -> {
                    SpecificationDto desired = invocation.getArgument(3);
                    assertEquals("spec-1", desired.getId());
                    assertEquals("3", desired.getTemplateId());
                    return CollectionMutationResultDto.of("updated", desired, List.of(desired));
                });

        JsonNode result = new ObjectMapper().readTree(tool.execute(specificationUpdateArgs(before)));

        assertEquals("updated", result.path("operation").asText());
        assertEquals("spec-1", result.path("specification").path("specId").asText());
        assertEquals("NOT_VERIFIED", result.path("verificationStatus").asText());
        assertTrue(AiToolResultContract.hasValidKnownToolPayload("manage_spec", result));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
        verify(boardStorageService, never()).removeSpecIfUnchanged(anyLong(), anyString(), any());
    }

    @Test
    void updateRefusesAnExpectedSnapshotThatNoLongerMatchesTheRead() throws Exception {
        SpecificationDto before = prepareSpecificationUpdate();
        String args = specificationUpdateArgs(before);
        before.setTemplateId("2");

        JsonNode result = new ObjectMapper().readTree(tool.execute(args));

        assertEquals(409, result.path("status").asInt());
        verify(boardStorageService, never()).updateSpecIfUnchanged(anyLong(), anyString(), any(), any());
    }

    @Test
    void updatePreservesAConflictDetectedAtTheCommitBoundary() throws Exception {
        SpecificationDto before = prepareSpecificationUpdate();
        when(boardStorageService.updateSpecIfUnchanged(eq(1L), eq("spec-1"), eq(before), any()))
                .thenThrow(new ConflictException("The specification changed before saving."));

        JsonNode result = new ObjectMapper().readTree(tool.execute(specificationUpdateArgs(before)));

        assertEquals(409, result.path("status").asInt());
        assertFalse(result.has("operation"));
    }

    @Test
    void updateNoOpRetainsTheAuthoritativeUnchangedOperation() throws Exception {
        SpecificationDto before = prepareSpecificationUpdate();
        when(boardStorageService.updateSpecIfUnchanged(eq(1L), eq("spec-1"), eq(before), any()))
                .thenReturn(CollectionMutationResultDto.of("unchanged", before, List.of(before)));

        JsonNode result = new ObjectMapper().readTree(tool.execute(specificationUpdateArgs(before)));

        assertEquals("unchanged", result.path("operation").asText());
        assertTrue(AiToolResultContract.hasValidKnownToolPayload("manage_spec", result));
    }

    @Test
    void updateRequiresACompleteExpectedSpecification() throws Exception {
        JsonNode result = new ObjectMapper().readTree(tool.execute(
                "{\"action\":\"update\",\"specId\":\"spec-1\",\"templateId\":\"3\"}"));
        assertEquals(400, result.path("status").asInt());
        verify(boardStorageService, never()).updateSpecIfUnchanged(anyLong(), anyString(), any(), any());
    }

    private SpecificationDto prepareSpecificationUpdate() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        node.setTemplateName("Sensor");
        node.setState("on");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("sensor_1");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("on");
        condition.setSide("a");
        SpecificationDto before = new SpecificationDto();
        before.setId("spec-1");
        before.setTemplateId("1");
        before.setAConditions(List.of(condition));
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(new BoardSemanticSnapshotDto(
                List.of(node), List.of(), List.of(), List.of(before), List.of(sensorTemplate())));
        return before;
    }

    private String specificationUpdateArgs(SpecificationDto before) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var args = mapper.createObjectNode();
        args.put("action", "update");
        args.put("specId", before.getId());
        args.put("templateId", "3");
        args.set("expectedSpec", mapper.valueToTree(SpecificationToolPresenter.present(before,
                SpecificationToolPresenter.context(boardStorageService.getNodes(1L), List.of(sensorTemplate())))));
        args.set("aConditions", mapper.readTree("""
                [{"deviceId":"sensor_1","targetType":"state","key":"state","relation":"=","value":"on"}]
                """));
        return args.toString();
    }

    @Test
    void add_withUnknownTemplateId_shouldReject() {
        String argsJson = """
                {
                  "action":"add",
                  "templateId":"99",
                  "aConditions":[
                    {
                      "deviceId":"dev_1",
                      "targetType":"state",
                      "key":"state",
                      "relation":"=",
                      "value":"On"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("Unsupported templateId"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_withoutTemplateId_shouldRejectInsteadOfDefaultingToAlways() {
        String argsJson = """
                {
                  "action":"add",
                  "aConditions":[
                    {
                      "deviceId":"dev_1",
                      "targetType":"state",
                      "key":"state",
                      "relation":"=",
                      "value":"On"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("arguments.templateId is required"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_withDeviceLabelOnly_shouldReject() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("ac_1");
        node.setLabel("LivingRoomAC");
        node.setTemplateName("Air Conditioner");
        node.setState("Working");
        node.setWidth(100);
        node.setHeight(80);
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(10.0);
        position.setY(20.0);
        node.setPosition(position);

        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceLabel":"LivingRoomAC",
                      "targetType":"state",
                      "key":"state",
                      "relation":"=",
                      "value":"On"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);
        assertTrue(result.contains("must include an existing deviceId"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_withDuplicateLabelsAndDeviceId_shouldUseDeviceId() throws Exception {
        DeviceNodeDto node1 = new DeviceNodeDto();
        node1.setId("ac_1");
        node1.setLabel("LivingRoomAC");

        DeviceNodeDto node2 = new DeviceNodeDto();
        node2.setId("ac_2");
        node2.setLabel("LivingRoomAC");

        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node1, node2));
        stubSuccessfulSpecAdd();

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"ac_1",
                      "deviceLabel":"LivingRoomAC",
                      "targetType":"state",
                      "key":"state",
                      "relation":"=",
                      "value":"On"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("not been formally verified"));
        JsonNode response = new ObjectMapper().readTree(result);
        assertEquals("NOT_VERIFIED", response.path("verificationStatus").asText());
        assertTrue(response.path("specification").path("formulaPreview").asText().startsWith("CTL AG"));
        assertFalse(response.path("specification").path("formulaPreview").asText().contains("ac_1"));
        assertTrue(response.path("specification").path("formula").isMissingNode());
        assertEquals("LivingRoomAC",
                response.path("specification").path("aConditions").get(0).path("deviceLabel").asText());
        ArgumentCaptor<SpecificationDto> captor = ArgumentCaptor.forClass(SpecificationDto.class);
        verify(boardStorageService).addSpec(eq(1L), captor.capture());
        assertEquals("ac_1", captor.getValue().getAConditions().get(0).getDeviceId());
        assertNull(captor.getValue().getTemplateLabel());
        assertNull(captor.getValue().getFormula());
        assertTrue(captor.getValue().getDevices().isEmpty());
    }

    @Test
    void add_withInconsistentDeviceLabel_shouldIgnoreLabelForIdentity() {
        DeviceNodeDto node1 = new DeviceNodeDto();
        node1.setId("ac_1");
        node1.setLabel("LivingRoomAC");

        DeviceNodeDto node2 = new DeviceNodeDto();
        node2.setId("ac_2");
        node2.setLabel("KitchenAC");

        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node1, node2));
        stubSuccessfulSpecAdd();

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"ac_1",
                      "deviceLabel":"KitchenAC",
                      "targetType":"state",
                      "key":"state",
                      "relation":"=",
                      "value":"On"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("not been formally verified"));
        ArgumentCaptor<SpecificationDto> captor = ArgumentCaptor.forClass(SpecificationDto.class);
        verify(boardStorageService).addSpec(eq(1L), captor.capture());
        SpecificationDto saved = captor.getValue();
        assertEquals("ac_1", saved.getAConditions().get(0).getDeviceId());
        assertEquals("LivingRoomAC", saved.getAConditions().get(0).getDeviceLabel());
        assertTrue(saved.getDevices().isEmpty());
        assertEquals("LivingRoomAC",
                specification(result).path("aConditions").get(0).path("deviceLabel").asText());
    }

    /**
     * The assistant must choose which question a variable condition asks, and be refused if it does not.
     *
     * <p>Bean Validation is inert on this path — {@code service/} carries no {@code @Validated}, so the
     * DTO's {@code @Pattern} never runs and this parse is the binding gate. Without it the model can write
     * a condition that the storage boundary then rejects, or worse, one the generator reports as a skipped
     * specification: a run that silently answers less than it was asked.
     */
    @Test
    void add_variableConditionWithoutVariableSource_shouldBeRejected() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        node.setTemplateName("Sensor");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(sensorTemplate()));

        String result = tool.execute("""
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"sensor_1",
                      "targetType":"variable",
                      "key":"temperature",
                      "relation":">",
                      "value":"28"
                    }
                  ]
                }
                """);

        assertTrue(result.contains("requires variableSource"),
                () -> "Expected a variableSource requirement, got " + result);
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_variableSourceOnANonVariableTarget_shouldBeRejected() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        node.setTemplateName("Sensor");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(sensorTemplate()));

        String result = tool.execute("""
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"sensor_1",
                      "targetType":"state",
                      "variableSource":"environment",
                      "key":"state",
                      "relation":"=",
                      "value":"On"
                    }
                  ]
                }
                """);

        assertTrue(result.contains("may use variableSource only with variable"),
                () -> "Expected a target-type rejection, got " + result);
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_withLegacyGeneratedEnvironmentAlias_shouldRejectWhenNoLiteralVariableExists() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        node.setTemplateName("Sensor");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(sensorTemplate()));

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"sensor_1",
                      "targetType":"variable",
                      "variableSource":"environment",
                      "key":"a_temperature",
                      "relation":">",
                      "value":"28"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("Condition 1 on 'A'"));
        assertFalse(result.contains("Condition index"));
        assertTrue(result.contains("unknown template variable"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_withActualAPrefixedEnvironmentVariable_shouldTreatKeyLiterally() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        node.setTemplateName("A-prefixed Sensor");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(aPrefixedSensorTemplate()));
        stubSuccessfulSpecAdd();

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"sensor_1",
                      "targetType":"variable",
                      "variableSource":"environment",
                      "key":"a_temperature",
                      "relation":">",
                      "value":"28"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("not been formally verified"));
        ArgumentCaptor<SpecificationDto> captor = ArgumentCaptor.forClass(SpecificationDto.class);
        verify(boardStorageService).addSpec(eq(1L), captor.capture());
        SpecificationDto saved = captor.getValue();
        assertEquals("a_temperature", saved.getAConditions().get(0).getKey());
        assertNull(saved.getFormula());
        assertEquals("CTL AG(Environment.\"a_temperature\" > 28)",
                specification(result).path("formulaPreview").asText());
        assertFalse(specification(result).path("formulaPreview").asText().contains("a_a_temperature"));
    }

    @Test
    void add_withEnvironmentVariable_shouldPersistDeviceRefsAndSharedVariableFormula() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        node.setTemplateName("Sensor");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(sensorTemplate()));
        stubSuccessfulSpecAdd();

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"sensor_1",
                      "targetType":"variable",
                      "variableSource":"environment",
                      "key":"temperature",
                      "relation":">",
                      "value":"28"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("not been formally verified"));
        ArgumentCaptor<SpecificationDto> captor = ArgumentCaptor.forClass(SpecificationDto.class);
        verify(boardStorageService).addSpec(eq(1L), captor.capture());
        SpecificationDto saved = captor.getValue();
        assertNull(saved.getFormula());
        assertTrue(saved.getDevices().isEmpty());
        assertEquals("CTL AG(Environment.\"temperature\" > 28)",
                specification(result).path("formulaPreview").asText());
    }

    @Test
    void add_withOrderedTrustRelation_shouldRejectBeforeStorage() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"sensor_1",
                      "targetType":"trust",
                      "propertyScope":"variable",
                      "key":"temperature",
                      "relation":">",
                      "value":"trusted"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("targetType='trust' only supports =, !=, in, not in"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_withGeneratedTrustAlias_shouldRejectWhenNoLiteralVariableExists() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        node.setTemplateName("Sensor");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(sensorTemplate()));

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"sensor_1",
                      "targetType":"trust",
                      "propertyScope":"variable",
                      "key":"trust_temperature",
                      "relation":"=",
                      "value":"untrusted"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("unknown property variable"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_withActualTrustPrefixedVariable_shouldTreatTrustKeyLiterally() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        node.setTemplateName("Trust-prefixed Sensor");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(trustPrefixedSensorTemplate()));
        stubSuccessfulSpecAdd();

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"sensor_1",
                      "targetType":"trust",
                      "propertyScope":"variable",
                      "key":"trust_temperature",
                      "relation":"=",
                      "value":"untrusted"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("not been formally verified"));
        ArgumentCaptor<SpecificationDto> captor = ArgumentCaptor.forClass(SpecificationDto.class);
        verify(boardStorageService).addSpec(eq(1L), captor.capture());
        SpecificationDto saved = captor.getValue();
        assertEquals("trust_temperature", saved.getAConditions().get(0).getKey());
        assertNull(saved.getFormula());
        assertEquals("CTL AG(controlSource(\"Sensor\".\"trust_temperature\") = untrusted)",
                specification(result).path("formulaPreview").asText());
    }

    @Test
    void add_withStateTrustScope_buildsCurrentStateGuardedPreview() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Sensor");
        node.setTemplateName("Sensor");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(sensorTemplate()));
        stubSuccessfulSpecAdd();

        String result = tool.execute("""
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[{
                    "deviceId":"sensor_1",
                    "targetType":"trust",
                    "propertyScope":"state",
                    "key":"Mode",
                    "relation":"=",
                    "value":"untrusted"
                  }]
                }
                """);

        assertTrue(result.contains("not been formally verified"));
        ArgumentCaptor<SpecificationDto> captor = ArgumentCaptor.forClass(SpecificationDto.class);
        verify(boardStorageService).addSpec(eq(1L), captor.capture());
        SpecificationDto saved = captor.getValue();
        assertEquals("state", saved.getAConditions().get(0).getPropertyScope());
        assertEquals("Mode", saved.getAConditions().get(0).getKey());
        assertNull(saved.getFormula());
        assertEquals("CTL AG(controlSource(\"Sensor\".current \"Mode\" state) = untrusted)",
                specification(result).path("formulaPreview").asText());
    }

    @Test
    void add_safetyWithMultiModeStatePreviewsAllReliabilityLabels() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("ac_1");
        node.setLabel("Living Room AC");
        node.setTemplateName("Two-mode AC");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(twoModeAirConditionerTemplate()));
        stubSuccessfulSpecAdd();

        String result = tool.execute("""
                {
                  "action":"add",
                  "templateId":"7",
                  "aConditions":[{
                    "deviceId":"ac_1",
                    "targetType":"state",
                    "key":"state",
                    "relation":"=",
                    "value":"on;cool"
                  }]
                }
                """);

        assertTrue(result.contains("not been formally verified"));
        ArgumentCaptor<SpecificationDto> captor = ArgumentCaptor.forClass(SpecificationDto.class);
        verify(boardStorageService).addSpec(eq(1L), captor.capture());
        assertNull(captor.getValue().getFormula());
        assertEquals("CTL AG NOT (\"Living Room AC\".state = \"on;cool\" AND "
                        + "controlSource(\"Living Room AC\".state) = untrusted)",
                specification(result).path("formulaPreview").asText());
    }

    @Test
    void add_withIllegalStateValue_shouldRejectBeforeStorage() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("ac_1");
        node.setLabel("LivingRoomAC");
        node.setTemplateName("Air Conditioner");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(List.of(airConditionerTemplate()));

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"ac_1",
                      "targetType":"state",
                      "key":"state",
                      "relation":"=",
                      "value":"sleeping"
                    }
                  ]
                }
                """;

        String result = tool.execute(argsJson);

        assertTrue(result.contains("illegal state value"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void execute_whenJsonSerializationFails_shouldReturnStableErrorJson() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });

        ManageSpecTool fallbackTool = new ManageSpecTool(boardStorageService, failingMapper, destructiveActionGuard);
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

        ManageSpecTool fallbackTool = new ManageSpecTool(boardStorageService, failingMapper, destructiveActionGuard);

        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("ac_1");
        node.setLabel("LivingRoomAC");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));
        stubSuccessfulSpecAdd();

        String argsJson = """
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[
                    {
                      "deviceId":"ac_1",
                      "targetType":"state",
                      "key":"state",
                      "relation":"=",
                      "value":"On"
                    }
                  ]
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
        SpecificationDto target = new SpecificationDto();
        target.setId("spec-9");
        target.setTemplateLabel("Smoke alarm must eventually sound");
        when(boardStorageService.getSpecs(1L)).thenReturn(List.of(target));
        when(boardStorageService.removeSpecIfUnchanged(1L, "spec-9", target))
                .thenReturn(CollectionMutationResultDto.of("deleted", target, List.of()));

        String preview = tool.execute("{\"action\":\"delete\",\"specId\":\"spec-9\",\"confirmed\":true}");
        JsonNode previewJson = new ObjectMapper().readTree(preview);
        assertTrue(previewJson.path("requiresUserConfirmation").asBoolean());
        assertEquals(previewJson.path("specification").path("description").asText(),
                destructiveActionGuard.pendingContext(1L, "spec-test-session")
                        .orElseThrow().displayTarget());
        verify(boardStorageService, never()).removeSpecIfUnchanged(anyLong(), any(), any());

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", previewJson.path("impactToken").asText());
        String result = tool.execute("{\"action\":\"delete\",\"specId\":\"spec-9\",\"confirmed\":true,\"impactToken\":\""
                + previewJson.path("impactToken").asText() + "\"}");
        JsonNode resultJson = new ObjectMapper().readTree(result);
        assertEquals("deleted", resultJson.path("operation").asText());
        verify(boardStorageService).removeSpecIfUnchanged(1L, "spec-9", target);
    }

    @Test
    void add_rejectsMalformedConditionCollectionsInsteadOfSilentlyTreatingThemAsEmpty() throws Exception {
        JsonNode result = new ObjectMapper().readTree(tool.execute("""
                {
                  "action":"add",
                  "templateId":"4",
                  "aConditions":"not-an-array",
                  "ifConditions":[{
                    "deviceId":"ac_1","targetType":"state","key":"state","relation":"=","value":"On"
                  }],
                  "thenConditions":[{
                    "deviceId":"ac_1","targetType":"state","key":"state","relation":"=","value":"Off"
                  }]
                }
                """));

        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertTrue(result.path("error").asText().contains("aConditions must be a JSON array"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_requiresExplicitRelationForNonApiConditions() throws Exception {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("ac_1");
        node.setLabel("LivingRoomAC");
        when(boardStorageService.getNodes(1L)).thenReturn(List.of(node));

        JsonNode result = new ObjectMapper().readTree(tool.execute("""
                {
                  "action":"add",
                  "templateId":"1",
                  "aConditions":[{
                    "deviceId":"ac_1","targetType":"state","key":"state","value":"On"
                  }]
                }
                """));

        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertTrue(result.path("error").asText().contains("requires relation/value"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    @Test
    void add_rejectsNumericTemplateIdInsteadOfCoercingItToString() throws Exception {
        JsonNode result = new ObjectMapper().readTree(tool.execute("""
                {
                  "action":"add",
                  "templateId":1,
                  "aConditions":[]
                }
                """));

        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertTrue(result.path("error").asText().contains("templateId"));
        verify(boardStorageService, never()).addSpec(anyLong(), any());
    }

    private void stubSuccessfulSpecAdd() {
        when(boardStorageService.addSpec(eq(1L), any(SpecificationDto.class))).thenAnswer(invocation -> {
            SpecificationDto spec = invocation.getArgument(1);
            return CollectionMutationResultDto.of("created", spec, List.of(spec));
        });
    }

    private JsonNode specification(String result) {
        try {
            return new ObjectMapper().readTree(result).path("specification");
        } catch (JsonProcessingException e) {
            throw new AssertionError("Tool result was not valid JSON", e);
        }
    }

    private DeviceTemplateDto airConditionerTemplate() {
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Air Conditioner");
        DeviceTemplateDto.DeviceManifest manifest = new DeviceTemplateDto.DeviceManifest();
        manifest.setName("Air Conditioner");
        manifest.setModes(List.of("Power"));
        manifest.setInitState("off");
        manifest.setWorkingStates(List.of(
                workingState("off"),
                workingState("on")
        ));
        manifest.setInternalVariables(List.of());
        manifest.setApis(List.of());
        template.setManifest(manifest);
        return template;
    }

    private DeviceTemplateDto aPrefixedSensorTemplate() {
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("A-prefixed Sensor");
        DeviceTemplateDto.DeviceManifest manifest = new DeviceTemplateDto.DeviceManifest();
        manifest.setName("A-prefixed Sensor");
        manifest.setModes(List.of("Mode"));
        manifest.setInitState("on");
        manifest.setWorkingStates(List.of(workingState("on")));
        manifest.setInternalVariables(List.of(
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("a_temperature")
                        .isInside(false)
                        .lowerBound(0)
                        .upperBound(100)
                        .build()
        ));
        manifest.setApis(List.of());
        template.setManifest(manifest);
        return template;
    }

    private DeviceTemplateDto sensorTemplate() {
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Sensor");
        DeviceTemplateDto.DeviceManifest manifest = new DeviceTemplateDto.DeviceManifest();
        manifest.setName("Sensor");
        manifest.setModes(List.of("Mode"));
        manifest.setInitState("on");
        manifest.setWorkingStates(List.of(workingState("on")));
        manifest.setInternalVariables(List.of(
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .lowerBound(0)
                        .upperBound(100)
                        .build()
        ));
        manifest.setApis(List.of());
        template.setManifest(manifest);
        return template;
    }

    private DeviceTemplateDto twoModeAirConditionerTemplate() {
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Two-mode AC");
        DeviceTemplateDto.DeviceManifest manifest = new DeviceTemplateDto.DeviceManifest();
        manifest.setName("Two-mode AC");
        manifest.setModes(List.of("Power", "Mode"));
        manifest.setInitState("off;idle");
        manifest.setWorkingStates(List.of(
                workingState("off;idle"),
                workingState("on;cool")));
        manifest.setInternalVariables(List.of());
        manifest.setApis(List.of());
        template.setManifest(manifest);
        return template;
    }

    private DeviceTemplateDto trustPrefixedSensorTemplate() {
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Trust-prefixed Sensor");
        DeviceTemplateDto.DeviceManifest manifest = new DeviceTemplateDto.DeviceManifest();
        manifest.setName("Trust-prefixed Sensor");
        manifest.setModes(List.of("Mode"));
        manifest.setInitState("on");
        manifest.setWorkingStates(List.of(workingState("on")));
        manifest.setInternalVariables(List.of(
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("trust_temperature")
                        .isInside(true)
                        .lowerBound(0)
                        .upperBound(100)
                        .build()
        ));
        manifest.setApis(List.of());
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
}
