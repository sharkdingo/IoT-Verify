package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.component.aitool.node.DeleteNodeTool;
import cn.edu.nju.Iot_Verify.component.aitool.node.EditDeviceTool;
import cn.edu.nju.Iot_Verify.component.aitool.rule.ManageRuleTool;
import cn.edu.nju.Iot_Verify.component.aitool.simulation.SimulateModelAsyncTool;
import cn.edu.nju.Iot_Verify.component.aitool.simulation.SimulateModelTool;
import cn.edu.nju.Iot_Verify.component.aitool.spec.ManageSpecTool;
import cn.edu.nju.Iot_Verify.component.aitool.verification.VerifyModelAsyncTool;
import cn.edu.nju.Iot_Verify.component.aitool.verification.VerifyModelTool;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceDeletionResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationRequestDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiDeviceIdentityRegressionTest {

    private static final List<String> BOUNDARY_IDS = List.of(
            " lamp_a", "lamp_a ", "\tlamp_a", "lamp_a\n",
            "\u00a0lamp_a", "lamp_a\u00a0", "\ufefflamp_a", "lamp_a\ufeff",
            "\u0085lamp_a", "lamp_a\u0085", "");
    private static final List<String> LEGAL_IDS = List.of(
            "lamp_a", "room lamp", "room\u0085lamp", "room\ufefflamp", "设备_一");
    private static final List<String> RULE_FIELDS = List.of("condition", "command", "contentDevice");
    private static final List<String> SPEC_SIDES = List.of("a", "if", "then");
    private static final List<String> MODEL_TOOLS = List.of("simulate", "simulateAsync", "verify", "verifyAsync");

    private final ObjectMapper mapper = new ObjectMapper();
    private BoardStorageService board;
    private SimulationService simulation;
    private VerificationService verification;
    private AiDestructiveActionGuard guard;

    @BeforeEach
    void setUp() {
        board = mock(BoardStorageService.class);
        simulation = mock(SimulationService.class);
        verification = mock(VerificationService.class);
        guard = new AiDestructiveActionGuard(mapper);
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("identity-regression-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @ParameterizedTest
    @MethodSource("boundaryIds")
    void editRejectsMalformedIdentityBeforeLookingUpOrMutating(String id) throws Exception {
        assertValidation(new EditDeviceTool(board, mapper).execute(
                mapper.writeValueAsString(java.util.Map.of("id", id, "field", "label", "label", "Renamed"))),
                "arguments.id");
        verifyNoInteractions(board);
    }

    @ParameterizedTest
    @MethodSource("boundaryIds")
    void malformedDeleteDoesNotPreviewOrConsumeAnExistingConfirmation(String id) throws Exception {
        DeviceNodeDto node = node("lamp_a", "Light", "on");
        when(board.previewNodeDeletion(1L, "lamp_a")).thenReturn(deletion(node, "preview"));
        when(board.deleteNodeCascade(1L, "lamp_a", "domain-impact")).thenReturn(deletion(node, "deleted"));
        DeleteNodeTool tool = new DeleteNodeTool(board, mapper, guard);
        String token = mapper.readTree(tool.execute("{\"id\":\"lamp_a\",\"confirmed\":false}"))
                .path("impactToken").asText();
        assertTrue(!token.isBlank());
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);
        clearInvocations(board);

        assertValidation(tool.execute(mapper.writeValueAsString(
                java.util.Map.of("id", id, "confirmed", true, "impactToken", token))), "arguments.id");
        verifyNoInteractions(board);
        assertTrue(guard.pendingContext(1L, "identity-regression-session").isPresent());

        assertEquals("deleted", mapper.readTree(tool.execute(mapper.writeValueAsString(
                java.util.Map.of("id", "lamp_a", "confirmed", true, "impactToken", token))))
                .path("operation").asText());
        verify(board).deleteNodeCascade(1L, "lamp_a", "domain-impact");
    }

    @ParameterizedTest
    @MethodSource("legalIds")
    void editAndConfirmedDeleteForwardLegalIdentityUnchanged(String id) throws Exception {
        DeviceNodeDto node = node(id, "Light", "on");
        when(board.getNodes(1L)).thenReturn(List.of(node));
        when(board.renameNode(1L, id, "Renamed", node.getLabel())).thenReturn(
                DeviceMutationResultDto.builder().operation("renamed").affectedDevices(List.of(node)).build());
        assertEquals("renamed", mapper.readTree(new EditDeviceTool(board, mapper).execute(
                mapper.writeValueAsString(java.util.Map.of("id", id, "field", "label", "label", "Renamed"))))
                .path("operation").asText());
        verify(board).renameNode(1L, id, "Renamed", node.getLabel());

        when(board.previewNodeDeletion(1L, id)).thenReturn(deletion(node, "preview"));
        when(board.deleteNodeCascade(1L, id, "domain-impact")).thenReturn(deletion(node, "deleted"));
        DeleteNodeTool tool = new DeleteNodeTool(board, mapper, guard);
        JsonNode preview = mapper.readTree(tool.execute(mapper.writeValueAsString(
                java.util.Map.of("id", id, "confirmed", false))));
        assertEquals(id, preview.path("device").path("id").asText());
        String token = preview.path("impactToken").asText();
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);
        assertEquals("deleted", mapper.readTree(tool.execute(mapper.writeValueAsString(
                java.util.Map.of("id", id, "confirmed", true, "impactToken", token))))
                .path("operation").asText());
        verify(board).deleteNodeCascade(1L, id, "domain-impact");
    }

    @ParameterizedTest
    @MethodSource("ruleBoundaryCases")
    void ruleRejectsEachMalformedDeviceReferenceWithoutWriting(String field, String id) throws Exception {
        fixture("lamp_a");
        ObjectNode args = ruleArgs("lamp_a");
        String path;
        if ("condition".equals(field)) {
            ((ObjectNode) args.path("conditions").get(0)).put("deviceId", id);
            path = "arguments.conditions[0].deviceId";
        } else {
            String targetId = "command".equals(field) ? "lamp_a_email" : "lamp_a_phone";
            ((ObjectNode) args.path("command")).put("command".equals(field) ? "deviceId" : "contentDevice",
                    id.replace("lamp_a", targetId));
            path = "arguments.command." + ("command".equals(field) ? "deviceId" : "contentDevice");
        }
        assertValidation(new ManageRuleTool(board, mapper, guard, new cn.edu.nju.Iot_Verify.testutil.AiToolReadFixture().service).execute(args.toString()), path);
        verify(board, never()).addRule(any(), any());
    }

    @ParameterizedTest
    @MethodSource("legalIds")
    void rulePreservesAllLegalDeviceReferences(String id) throws Exception {
        fixture(id);
        when(board.addRule(eq(1L), any())).thenAnswer(invocation -> {
            RuleDto rule = invocation.getArgument(1);
            return CollectionMutationResultDto.of("created", rule, List.of(rule));
        });
        assertEquals("created", mapper.readTree(new ManageRuleTool(board, mapper, guard, new cn.edu.nju.Iot_Verify.testutil.AiToolReadFixture().service)
                .execute(ruleArgs(id).toString())).path("operation").asText());
        ArgumentCaptor<RuleDto> captured = ArgumentCaptor.forClass(RuleDto.class);
        verify(board).addRule(eq(1L), captured.capture());
        assertEquals(id, captured.getValue().getConditions().get(0).getDeviceName());
        assertEquals(id + "_email", captured.getValue().getCommand().getDeviceName());
        assertEquals(id + "_phone", captured.getValue().getCommand().getContentDevice());
    }

    @ParameterizedTest
    @MethodSource("specBoundaryCases")
    void specificationRejectsMalformedIdentityOnEveryConditionSide(String side, String id) throws Exception {
        fixture("lamp_a");
        ObjectNode args = specArgs("lamp_a", side);
        ((ObjectNode) args.path(side + "Conditions").get(0)).put("deviceId", id);
        assertValidation(new ManageSpecTool(board, mapper, guard, new cn.edu.nju.Iot_Verify.testutil.AiToolReadFixture().service).execute(args.toString()),
                "arguments." + side + "Conditions[0].deviceId");
        verify(board, never()).addSpec(any(), any());
    }

    @ParameterizedTest
    @MethodSource("specLegalCases")
    void specificationPreservesLegalIdentityOnEveryConditionSide(String side, String id) throws Exception {
        fixture(id);
        when(board.addSpec(eq(1L), any())).thenAnswer(invocation -> {
            SpecificationDto spec = invocation.getArgument(1);
            return CollectionMutationResultDto.of("created", spec, List.of(spec));
        });
        assertEquals("created", mapper.readTree(new ManageSpecTool(board, mapper, guard, new cn.edu.nju.Iot_Verify.testutil.AiToolReadFixture().service)
                .execute(specArgs(id, side).toString())).path("operation").asText());
        ArgumentCaptor<SpecificationDto> captured = ArgumentCaptor.forClass(SpecificationDto.class);
        verify(board).addSpec(eq(1L), captured.capture());
        SpecificationDto spec = captured.getValue();
        assertEquals(id, switch (side) {
            case "a" -> spec.getAConditions().get(0).getDeviceId();
            case "if" -> spec.getIfConditions().get(0).getDeviceId();
            default -> spec.getThenConditions().get(0).getDeviceId();
        });
    }

    @ParameterizedTest
    @MethodSource("attackBoundaryCases")
    void allModelToolsRejectMalformedAttackIdentityBeforeSolverOrDispatch(String name, String id) throws Exception {
        assertValidation(modelTool(name).execute(attackArgs(id).toString()), "attackPoints[0].deviceId");
        verifyNoInteractions(simulation, verification);
    }

    @ParameterizedTest
    @MethodSource("attackLegalCases")
    void modelToolsApplyExistingModelNormalizationOnlyAfterAcceptingLegalIdentity(String name, String id) throws Exception {
        // Stop at the service boundary: these controls check identity admission, not a solver verdict.
        BadRequestException serviceStop = new BadRequestException("identity-control-reached-service");
        if ("simulate".equals(name)) doThrow(serviceStop).when(simulation).simulate(eq(1L), any());
        if ("simulateAsync".equals(name)) doThrow(serviceStop).when(simulation).submitSimulation(eq(1L), any());
        if ("verify".equals(name)) doThrow(serviceStop).when(verification).verify(eq(1L), any());
        if ("verifyAsync".equals(name)) doThrow(serviceStop).when(verification).submitVerification(eq(1L), any());
        JsonNode response = mapper.readTree(modelTool(name).execute(attackArgs(id).toString()));
        assertEquals("BUSINESS_ERROR", response.path("errorCode").asText());
        assertEquals("identity-control-reached-service", response.path("error").asText());
        if (name.startsWith("simulate")) {
            ArgumentCaptor<SimulationRequestDto> captured = ArgumentCaptor.forClass(SimulationRequestDto.class);
            if ("simulate".equals(name)) verify(simulation).simulate(eq(1L), captured.capture());
            else verify(simulation).submitSimulation(eq(1L), captured.capture());
            assertEquals(DeviceNameNormalizer.normalize(id), captured.getValue().getAttackScenario().getPoints().get(0).getDeviceId());
        } else {
            ArgumentCaptor<VerificationRequestDto> captured = ArgumentCaptor.forClass(VerificationRequestDto.class);
            if ("verify".equals(name)) verify(verification).verify(eq(1L), captured.capture());
            else verify(verification).submitVerification(eq(1L), captured.capture());
            assertEquals(DeviceNameNormalizer.normalize(id), captured.getValue().getAttackScenario().getPoints().get(0).getDeviceId());
        }
    }

    private void fixture(String id) throws Exception {
        when(board.getNodes(1L)).thenReturn(List.of(node(id, "Light", "on"),
                node(id + "_email", "Email", "idle"), node(id + "_phone", "Mobile Phone", "on")));
        when(board.getDeviceTemplates(1L)).thenReturn(List.of(template("Light"), template("Email"), template("Mobile Phone")));
        when(board.getEnvironmentVariables(1L)).thenReturn(List.of());
    }

    private DeviceTemplateDto template(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/deviceTemplate/" + name + ".json")) {
            assertNotNull(input);
            DeviceTemplateDto template = new DeviceTemplateDto();
            template.setName(name);
            template.setManifest(mapper.readValue(input, DeviceTemplateDto.DeviceManifest.class));
            return template;
        }
    }

    private DeviceNodeDto node(String id, String template, String state) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId(id);
        node.setLabel("Device " + id);
        node.setTemplateName(template);
        node.setState(state);
        return node;
    }

    private DeviceDeletionResultDto deletion(DeviceNodeDto node, String operation) {
        return DeviceDeletionResultDto.builder().operation(operation).impactToken("domain-impact").deletedDevice(node).build();
    }

    private ObjectNode ruleArgs(String id) {
        ObjectNode args = mapper.createObjectNode().put("action", "add");
        args.putArray("conditions").addObject().put("deviceId", id).put("attribute", "SwitchState")
                .put("targetType", "mode").put("relation", "=").put("value", "on");
        args.putObject("command").put("deviceId", id + "_email").put("action", "send mail")
                .put("contentDevice", id + "_phone").put("content", "photo");
        return args;
    }

    private ObjectNode specArgs(String id, String side) {
        ObjectNode args = mapper.createObjectNode().put("action", "add").put("templateId", "a".equals(side) ? "1" : "4");
        for (String current : "a".equals(side) ? List.of("a") : List.of("if", "then")) {
            args.putArray(current + "Conditions").addObject().put("deviceId", id).put("targetType", "mode")
                    .put("key", "SwitchState").put("relation", "=").put("value", "on");
        }
        return args;
    }

    private ObjectNode attackArgs(String id) {
        ObjectNode args = mapper.createObjectNode().put("attackMode", "exact");
        args.putArray("attackPoints").addObject().put("kind", "device").put("deviceId", id);
        return args;
    }

    private AbstractAiTool modelTool(String name) {
        return switch (name) {
            case "simulate" -> new SimulateModelTool(simulation, mapper);
            case "simulateAsync" -> new SimulateModelAsyncTool(simulation, mapper);
            case "verify" -> new VerifyModelTool(verification, mapper);
            default -> new VerifyModelAsyncTool(verification, mapper);
        };
    }

    private void assertValidation(String response, String path) throws Exception {
        JsonNode json = mapper.readTree(response);
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText(), response);
        assertEquals(400, json.path("status").asInt(), response);
        assertTrue(json.path("error").asText().contains(path), response);
    }

    private static Stream<String> boundaryIds() { return BOUNDARY_IDS.stream(); }
    private static Stream<String> legalIds() { return LEGAL_IDS.stream(); }
    private static Stream<Arguments> ruleBoundaryCases() { return cases(RULE_FIELDS, BOUNDARY_IDS); }
    private static Stream<Arguments> specBoundaryCases() { return cases(SPEC_SIDES, BOUNDARY_IDS); }
    private static Stream<Arguments> specLegalCases() { return cases(SPEC_SIDES, LEGAL_IDS); }
    private static Stream<Arguments> attackBoundaryCases() { return cases(MODEL_TOOLS, BOUNDARY_IDS); }
    private static Stream<Arguments> attackLegalCases() { return cases(MODEL_TOOLS, LEGAL_IDS); }
    private static Stream<Arguments> cases(List<String> entries, List<String> ids) {
        return entries.stream().flatMap(entry -> ids.stream().map(id -> Arguments.of(entry, id)));
    }
}
