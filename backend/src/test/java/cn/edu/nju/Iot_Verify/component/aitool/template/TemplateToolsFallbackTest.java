package cn.edu.nju.Iot_Verify.component.aitool.template;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDeletionResultDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateToolsFallbackTest {

    @Mock
    private BoardStorageService boardStorageService;
    @Mock
    private DeviceTemplateSchemaValidator deviceTemplateSchemaValidator;

    private ObjectMapper objectMapper;
    private AiDestructiveActionGuard destructiveActionGuard;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        destructiveActionGuard = new AiDestructiveActionGuard(objectMapper);
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("template-test-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void listTemplates_rejectsUnknownOptionBeforeReadingCatalog() throws Exception {
        ListTemplatesTool tool = new ListTemplatesTool(boardStorageService, objectMapper);

        JsonNode json = objectMapper.readTree(tool.execute("{\"details\":true}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(boardStorageService, never()).getDeviceTemplates(1L);
    }

    @Test
    void listTemplates_detail_shouldOmitUiOnlyIcon() throws Exception {
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Camera")
                .description("Entry camera")
                .icon("data:image/png;base64," + "A".repeat(70_000))
                .modes(java.util.List.of("Power"))
                .build();
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setId(7L);
        template.setName("Camera");
        template.setManifest(manifest);
        template.setDefaultTemplate(false);
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(java.util.List.of(template));
        ListTemplatesTool tool = new ListTemplatesTool(boardStorageService, objectMapper);

        JsonNode json = objectMapper.readTree(tool.execute("{\"detail\":true}"));

        JsonNode returnedTemplate = json.path("templates").path(0);
        assertEquals("Camera", returnedTemplate.path("name").asText());
        assertEquals("Entry camera", returnedTemplate.path("manifest").path("Description").asText());
        assertFalse(returnedTemplate.path("manifest").has("Icon"));
        String semantics = json.path("message").asText();
        assertTrue(semantics.contains("false is one shared environment value per name"));
        assertTrue(semantics.contains("Reads=false grants no reading"));
        assertTrue(semantics.contains("effects on that shared value, not a private copy"));
        assertTrue(semantics.contains("all active device effects with NaturalChangeRate"));
        assertTrue(semantics.contains("mirrors the shared value outside compromise modeling"));
        assertTrue(semantics.contains("current shared labels come from the Board Environment Pool"));
        assertTrue(json.toString().length() < 10_000);
    }

    @Test
    void listTemplates_exactNamesReturnOnlyRelevantFullManifestsWithoutSilentlyDroppingUnknowns() throws Exception {
        DeviceTemplateDto sensor = new DeviceTemplateDto();
        sensor.setName("TemperatureSensor");
        sensor.setManifest(DeviceTemplateDto.DeviceManifest.builder().description("Reports temperature").build());
        DeviceTemplateDto ac = new DeviceTemplateDto();
        ac.setName("AC");
        ac.setManifest(DeviceTemplateDto.DeviceManifest.builder().description("Changes temperature").build());
        DeviceTemplateDto irrelevant = new DeviceTemplateDto();
        irrelevant.setName("Camera");
        when(boardStorageService.getDeviceTemplates(1L)).thenReturn(java.util.List.of(sensor, irrelevant, ac));
        ListTemplatesTool tool = new ListTemplatesTool(boardStorageService, objectMapper);

        JsonNode json = objectMapper.readTree(tool.execute("{\"names\":[\"ac\",\"TemperatureSensor\"],\"detail\":true}"));
        assertEquals(2, json.path("count").asInt());
        assertEquals("TemperatureSensor", json.path("templates").get(0).path("name").asText());
        assertEquals("Reports temperature", json.path("templates").get(0).path("manifest").path("Description").asText());
        assertEquals("AC", json.path("templates").get(1).path("name").asText());
        assertFalse(json.toString().contains("Camera"));
        JsonNode missing = objectMapper.readTree(tool.execute("{\"names\":[\"AC\",\"Missing\"]}"));
        assertEquals(422, missing.path("status").asInt());
        assertFalse(missing.has("templates"));
        assertEquals(objectMapper.valueToTree(java.util.List.of("Missing")), missing.path("unknownTemplateNames"));
        assertEquals(objectMapper.valueToTree(java.util.List.of("TemperatureSensor", "Camera", "AC")),
                missing.path("availableTemplateNames"));
        assertTrue(missing.path("error").asText().contains("not deviceLabel"));
    }

    @Test
    void listTemplates_invalidExactNamesFailBeforeReadingAnyCatalog() throws Exception {
        ListTemplatesTool tool = new ListTemplatesTool(boardStorageService, objectMapper);
        for (String args : java.util.List.of("{\"names\":null}", "{\"names\":[]}", "{\"names\":[true]}",
                "{\"names\":[\"AC\",\"AC\"]}", "{\"names\":[\" \"]}", "{\"names\":[\"AC\"],\"keyword\":\"AC\"}")) {
            assertEquals("VALIDATION_ERROR", objectMapper.readTree(tool.execute(args)).path("errorCode").asText());
        }
        verifyNoInteractions(boardStorageService);
    }

    @Test
    void listTemplates_deviceIdsReadActualTemplatesFromOneSnapshotWithoutGuessingLabels() throws Exception {
        DeviceTemplateDto alarm = new DeviceTemplateDto();
        alarm.setName("Alarm");
        alarm.setManifest(DeviceTemplateDto.DeviceManifest.builder().description("Actual alarm behavior").build());
        DeviceTemplateDto camera = new DeviceTemplateDto();
        camera.setName("Camera");
        DeviceNodeDto first = new DeviceNodeDto();
        first.setId("alarm-1");
        first.setTemplateName("Alarm");
        first.setLabel("Camera");
        DeviceNodeDto second = new DeviceNodeDto();
        second.setId("alarm-2");
        second.setTemplateName("Alarm");
        second.setLabel("Emergency Alarm");
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(new BoardSemanticSnapshotDto(
                java.util.List.of(first, second), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(camera, alarm)));
        ListTemplatesTool tool = new ListTemplatesTool(boardStorageService, objectMapper);

        JsonNode result = objectMapper.readTree(tool.execute(
                "{\"deviceIds\":[\"alarm-1\",\"alarm-2\"],\"detail\":true}"));

        assertEquals(1, result.path("count").asInt());
        assertEquals("Alarm", result.path("templates").get(0).path("name").asText());
        assertEquals("Actual alarm behavior", result.path("templates").get(0).path("manifest").path("Description").asText());
        assertFalse(result.toString().contains("Camera"));
        verify(boardStorageService).getSemanticSnapshot(1L);
        verify(boardStorageService, never()).getDeviceTemplates(1L);
    }

    @Test
    void listTemplates_missingDeviceDoesNotReturnAPartialOrGuessedTemplateRead() throws Exception {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("alarm-1");
        node.setTemplateName("Alarm");
        node.setLabel("Emergency Alarm");
        when(boardStorageService.getSemanticSnapshot(1L)).thenReturn(new BoardSemanticSnapshotDto(
                java.util.List.of(node), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()));
        ListTemplatesTool tool = new ListTemplatesTool(boardStorageService, objectMapper);

        JsonNode result = objectMapper.readTree(tool.execute(
                "{\"deviceIds\":[\"alarm-1\",\"Emergency Alarm\"],\"detail\":true}"));

        assertEquals(422, result.path("status").asInt());
        assertEquals(objectMapper.valueToTree(java.util.List.of("Emergency Alarm")), result.path("unknownDeviceIds"));
        assertFalse(result.has("templates"));
        verify(boardStorageService, never()).getDeviceTemplates(1L);
    }

    @Test
    void listTemplates_invalidDeviceScopeFailsBeforeReadingTheBoard() throws Exception {
        ListTemplatesTool tool = new ListTemplatesTool(boardStorageService, objectMapper);
        for (String args : java.util.List.of("{\"deviceIds\":null}", "{\"deviceIds\":[]}",
                "{\"deviceIds\":[true]}", "{\"deviceIds\":[\"alarm-1\",\"alarm-1\"]}",
                "{\"deviceIds\":[\" \"]}", "{\"deviceIds\":[\"alarm-1\"],\"names\":[\"Alarm\"]}",
                "{\"deviceIds\":[\"alarm-1\"],\"keyword\":\"Alarm\"}")) {
            assertEquals(400, objectMapper.readTree(tool.execute(args)).path("status").asInt(), args);
        }
        verifyNoInteractions(boardStorageService);
    }

    @Test
    void addTemplate_whenResponseSerializationFails_shouldReturnFallbackSuccess() throws Exception {
        ObjectMapper failingMapper = spy(new ObjectMapper());
        doThrow(new RuntimeException("boom")).when(failingMapper).writeValueAsString(any());

        DeviceTemplateDto saved = new DeviceTemplateDto();
        saved.setId(1L);
        saved.setName("Lamp");
        when(boardStorageService.addDeviceTemplate(anyLong(), any(DeviceTemplateDto.class))).thenReturn(saved);

        AddTemplateTool tool = new AddTemplateTool(boardStorageService, failingMapper, deviceTemplateSchemaValidator);
        tool.initTolerantMapper();
        String result = tool.execute("""
                {
                  "name":"Lamp",
                  "manifest":{
                    "Modes":["LampState"],
                    "InitState":"Off",
                    "WorkingStates":[{"Name":"Off"},{"Name":"On"}]
                  }
                }
                """);
        JsonNode json = objectMapper.readTree(result);

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
        assertTrue(json.path("warning").asText().contains("serialization failed"));
        verify(boardStorageService).addDeviceTemplate(anyLong(), any(DeviceTemplateDto.class));
    }

    @Test
    void deleteTemplate_whenResponseSerializationFails_shouldReturnFallbackSuccess() throws Exception {
        ObjectMapper failingMapper = spy(new ObjectMapper());
        doThrow(new RuntimeException("boom")).when(failingMapper).writeValueAsString(any());
        DeviceTemplateDto deleted = new DeviceTemplateDto();
        deleted.setId(42L);
        deleted.setName("Lamp");
        DeviceTemplateDeletionResultDto deletion = DeviceTemplateDeletionResultDto.builder()
                .operation("deleted").impactToken("token-42").canDelete(true)
                .template(deleted).deletedTemplate(deleted).currentTemplates(java.util.List.of()).build();
        DeviceTemplateDeletionResultDto preview = DeviceTemplateDeletionResultDto.builder()
                .operation("preview").impactToken("token-42").canDelete(true)
                .template(deleted).currentTemplates(java.util.List.of(deleted)).build();
        when(boardStorageService.previewDeviceTemplateDeletion(1L, 42L)).thenReturn(preview);
        when(boardStorageService.deleteDeviceTemplate(1L, 42L, "token-42")).thenReturn(deletion);

        DeleteTemplateTool previewTool = new DeleteTemplateTool(
                boardStorageService, objectMapper, destructiveActionGuard);
        JsonNode previewJson = objectMapper.readTree(
                previewTool.execute("{\"templateId\":42,\"confirmed\":false}"));
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", previewJson.path("preview").path("impactToken").asText());

        DeleteTemplateTool tool = new DeleteTemplateTool(boardStorageService, failingMapper, destructiveActionGuard);
        String result = tool.execute("{\"templateId\":42,\"confirmed\":true,\"impactToken\":\""
                + previewJson.path("preview").path("impactToken").asText() + "\"}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
        assertTrue(json.path("warning").asText().contains("serialization failed"));
        verify(boardStorageService).deleteDeviceTemplate(1L, 42L, "token-42");
    }

    @Test
    void deleteTemplate_withoutExplicitConfirmation_shouldReturnPreviewWithoutMutation() throws Exception {
        DeviceTemplateDto target = new DeviceTemplateDto();
        target.setId(42L);
        target.setName("Lamp");
        when(boardStorageService.previewDeviceTemplateDeletion(1L, 42L)).thenReturn(
                DeviceTemplateDeletionResultDto.builder()
                        .operation("preview").impactToken("token-42").canDelete(true)
                        .template(target).currentTemplates(java.util.List.of(target)).build());

        DeleteTemplateTool tool = new DeleteTemplateTool(boardStorageService, objectMapper, destructiveActionGuard);
        String result = tool.execute("{\"templateId\":42,\"confirmed\":true}");
        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.path("requiresUserConfirmation").asBoolean());
        assertEquals("preview", json.path("preview").path("operation").asText());
        assertEquals("Lamp", destructiveActionGuard.pendingContext(1L, "template-test-session")
                .orElseThrow().displayTarget());
        assertEquals(false, json.path("mutationMayHaveCommitted").asBoolean(false));
        verify(boardStorageService, never()).deleteDeviceTemplate(anyLong(), anyLong(), any());
    }

    /**
     * A read-only preview must not clear another tool's pending confirmation.
     *
     * <p>The undeletable-template branch called {@code clearSession} while returning
     * {@code readOnlySuccessJson} and {@code requiresUserConfirmation: false} — declaring itself read-only
     * and mutating session state anyway. It issues no confirmation of its own on that path, so the only
     * thing it could clear belonged to something else: the store is keyed
     * {@code (userId, sessionId, Kind.DESTRUCTIVE_ACTION)} with no tool name and holds one row per session.
     * So "can this template be deleted?" silently discarded a pending {@code delete_device} or
     * {@code clear_board}, and the model's next confirmed call got a {@code CONFIRMATION_MISSING} it could
     * not explain to the user.
     *
     * <p>{@code ApplyFixTool} clears in a comparable place and is correct, because it clears a confirmation
     * *it just issued* when the response carrying the preview could not be delivered. Clearing what you
     * issued is hygiene; clearing what another tool issued is a side effect.
     */
    @Test
    void deleteTemplate_undeletablePreview_shouldNotClearAnotherToolsConfirmation() throws Exception {
        DeviceTemplateDto target = new DeviceTemplateDto();
        target.setId(42L);
        target.setName("Lamp");

        DeviceTemplateDeletionResultDto preview = new DeviceTemplateDeletionResultDto();
        preview.setTemplate(target);
        preview.setCanDelete(false);
        preview.setImpactToken("token-abc");
        when(boardStorageService.previewDeviceTemplateDeletion(anyLong(), anyLong())).thenReturn(preview);

        // A pending confirmation belonging to a DIFFERENT tool — the thing the cleared session destroyed.
        Map<String, Object> pendingPreview = Map.of("device", Map.of("id", "node-7", "label", "Porch Light"));
        String otherToolsToken = destructiveActionGuard.issue(
                1L, "delete_device", "node-7", "Preview target", pendingPreview, "device-domain-token");

        DeleteTemplateTool tool = new DeleteTemplateTool(boardStorageService, objectMapper, destructiveActionGuard);
        JsonNode json = objectMapper.readTree(tool.execute("{\"templateId\":42}"));

        // The branch still reports honestly that nothing can be deleted.
        assertEquals(false, json.path("requiresUserConfirmation").asBoolean(true));

        /*
         * Asserted on behaviour rather than on a `verify(...)` interaction: the fixture builds a REAL
         * `AiDestructiveActionGuard`, so verifying a call on it throws `NotAMockException`. Consuming the
         * other tool's confirmation afterwards is the stronger check anyway — it proves the confirmation is
         * still usable, which is what the user's next click depends on.
         */
        // The guard also requires the *turn* to be a confirmation turn, independently of the stored token
        // (`consume_shouldRequireAnExplicitConfirmationTurn`). Without this the consume is refused for that
        // reason instead, and the test would pass or fail for nothing to do with session clearing.
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", otherToolsToken);
        AiDestructiveActionGuard.ConsumeResult stillPending = destructiveActionGuard.consume(
                1L, "delete_device", "node-7", otherToolsToken, pendingPreview);
        assertTrue(stillPending.approved(),
                "previewing an undeletable template must not invalidate another tool's pending confirmation");
    }

    @Test
    void deleteTemplate_stalePreview_returnsCurrentStructuredPreview() throws Exception {
        DeviceTemplateDto target = new DeviceTemplateDto();
        target.setId(42L);
        target.setName("Lamp");
        DeviceTemplateDeletionResultDto originalPreview = DeviceTemplateDeletionResultDto.builder()
                .operation("preview").impactToken("old-token").canDelete(true)
                .template(target).currentTemplates(java.util.List.of(target)).build();
        DeviceTemplateDeletionResultDto currentPreview = DeviceTemplateDeletionResultDto.builder()
                .operation("preview").impactToken("new-token").canDelete(true)
                .template(target).currentTemplates(java.util.List.of()).build();
        when(boardStorageService.previewDeviceTemplateDeletion(1L, 42L))
                .thenReturn(originalPreview, currentPreview);

        DeleteTemplateTool tool = new DeleteTemplateTool(boardStorageService, objectMapper, destructiveActionGuard);
        JsonNode original = objectMapper.readTree(
                tool.execute("{\"templateId\":42,\"confirmed\":false}"));
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", original.path("preview").path("impactToken").asText());

        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"templateId\":42,\"confirmed\":true,\"impactToken\":\""
                        + original.path("preview").path("impactToken").asText() + "\"}"));

        assertEquals(409, json.path("status").asInt());
        assertEquals("CONFIRMATION_STALE", json.path("errorCode").asText());
        assertEquals("preview", json.path("currentPreview").path("operation").asText());
        assertTrue(json.path("currentPreview").path("impactToken").asText().length() > 20);
        verify(boardStorageService, never()).deleteDeviceTemplate(anyLong(), anyLong(), any());
    }

    @Test
    void addTemplate_invalidJsonArgs_shouldReturnValidationError() throws Exception {
        AddTemplateTool tool = new AddTemplateTool(boardStorageService, objectMapper, deviceTemplateSchemaValidator);
        tool.initTolerantMapper();
        String result = tool.execute("{");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("Invalid JSON arguments.", json.path("error").asText());
        verify(boardStorageService, never()).addDeviceTemplate(anyLong(), any(DeviceTemplateDto.class));
    }

    @Test
    void templateMutationsRejectWrongTypesAndUnknownFieldsBeforeStorage() throws Exception {
        AddTemplateTool addTool = new AddTemplateTool(
                boardStorageService, objectMapper, deviceTemplateSchemaValidator);
        addTool.initTolerantMapper();
        DeleteTemplateTool deleteTool = new DeleteTemplateTool(boardStorageService, objectMapper, destructiveActionGuard);

        JsonNode numericName = objectMapper.readTree(
                addTool.execute("{\"name\":7,\"manifest\":{}}"));
        JsonNode unknownDeleteField = objectMapper.readTree(
                deleteTool.execute("{\"templateId\":7,\"confirmed\":false,\"name\":\"Lamp\"}"));

        assertEquals("VALIDATION_ERROR", numericName.path("errorCode").asText());
        assertTrue(numericName.path("error").asText().contains("non-empty string"));
        assertEquals("VALIDATION_ERROR", unknownDeleteField.path("errorCode").asText());
        assertTrue(unknownDeleteField.path("error").asText().contains("name"));
        verifyNoInteractions(boardStorageService);
    }

    @Test
    void deleteTemplate_outOfRangeLong_shouldReturn400() throws Exception {
        DeleteTemplateTool tool = new DeleteTemplateTool(boardStorageService, objectMapper, destructiveActionGuard);
        String result = tool.execute("{\"templateId\":99999999999999999999}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertTrue(json.path("error").asText().contains("out of range"));
        verify(boardStorageService, never()).deleteDeviceTemplate(anyLong(), anyLong(), any());
    }

    @Test
    void listTemplates_invalidJsonArgs_shouldReturnValidationError() throws Exception {
        ListTemplatesTool tool = new ListTemplatesTool(boardStorageService, objectMapper);

        String result = tool.execute("{");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("Invalid JSON arguments.", json.path("error").asText());
        verify(boardStorageService, never()).getDeviceTemplates(anyLong());
    }
}
