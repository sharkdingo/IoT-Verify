package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BoardDataConverterTest {

    @Test
    void freshModelFingerprintsIgnoreBoardDeviceOrderWhilePreservingRawNodeIds() {
        BoardStorageService storage = mock(BoardStorageService.class);
        var room2 = node("Room-2", "Light", "off");
        var room10 = node("Room-10", "Light", "on");
        var templates = List.of(template("Light", List.of("PowerMode"), List.of("off", "on")));
        when(storage.getSemanticSnapshot(1L)).thenReturn(
                snapshot(List.of(room2, room10), templates),
                snapshot(List.of(room10, room2), templates));

        BoardDataConverter converter = new BoardDataConverter(storage);
        var first = converter.getModelInputSnapshot(1L);
        var reversed = converter.getModelInputSnapshot(1L);
        ModelFingerprint fingerprint = new ModelFingerprint(new ObjectMapper());

        assertEquals(fingerprint.boardFingerprints(first), fingerprint.boardFingerprints(reversed));
        assertEquals(fingerprint.paperDomainFingerprint(first, 5),
                fingerprint.paperDomainFingerprint(reversed, 5));
        assertEquals(List.of("room_10", "room_2"),
                first.devices().stream().map(DeviceVerificationDto::getVarName).toList());
        assertEquals(List.of("Room-2", "Room-10"), first.nodes().stream().map(DeviceNodeDto::getId).toList());
        assertEquals(List.of("Room-10", "Room-2"), reversed.nodes().stream().map(DeviceNodeDto::getId).toList());
    }

    @Test
    void getDevicesForVerification_omitsStateForModeLessTemplates() {
        BoardStorageService storage = mock(BoardStorageService.class);
        when(storage.getSemanticSnapshot(1L)).thenReturn(snapshot(
                List.of(
                        node("kitchen_smoke", "Smoke Sensor", "Working"),
                        node("range_hood", "Range Hood", "off")),
                List.of(
                        template("Smoke Sensor", List.of(), List.of()),
                        template("Range Hood", List.of("MachineState"), List.of("off", "on")))));

        BoardDataConverter converter = new BoardDataConverter(storage);
        List<DeviceVerificationDto> devices = converter.getModelInputSnapshot(1L).devices();

        DeviceVerificationDto smoke = devices.stream()
                .filter(device -> "kitchen_smoke".equals(device.getVarName()))
                .findFirst()
                .orElseThrow();
        assertEquals("kitchen_smoke", smoke.getDeviceLabel());
        assertNull(smoke.getState());
        assertNull(smoke.getCurrentStateTrust());
        assertNull(smoke.getCurrentStatePrivacy());

        DeviceVerificationDto hood = devices.stream()
                .filter(device -> "range_hood".equals(device.getVarName()))
                .findFirst()
                .orElseThrow();
        assertEquals("off", hood.getState());
        assertEquals("trusted", hood.getCurrentStateTrust());
        assertEquals("private", hood.getCurrentStatePrivacy());
        verify(storage).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void getDevicesForVerification_omitsStateWhenTemplateHasModesButNoWorkingStates() {
        BoardStorageService storage = mock(BoardStorageService.class);
        when(storage.getSemanticSnapshot(1L)).thenReturn(snapshot(
                List.of(node("draft_device", "Draft Template", "on")),
                List.of(template("Draft Template", List.of("MachineState"), List.of()))));

        BoardDataConverter converter = new BoardDataConverter(storage);
        DeviceVerificationDto device = converter.getModelInputSnapshot(1L).devices().get(0);

        assertEquals("draft_device", device.getVarName());
        assertNull(device.getState());
        assertNull(device.getCurrentStateTrust());
        assertNull(device.getCurrentStatePrivacy());
    }

    @Test
    void getDevicesForVerification_matchesTemplateByDtoNameOrManifestName() {
        BoardStorageService storage = mock(BoardStorageService.class);
        DeviceTemplateDto template = template("Window Shade Display", List.of("OpenableState"), List.of("closed", "open"));
        template.getManifest().setName("Window Shade");
        when(storage.getSemanticSnapshot(1L)).thenReturn(snapshot(
                List.of(node("shade_1", "Window Shade Display", "closed")),
                List.of(template)));

        BoardDataConverter converter = new BoardDataConverter(storage);
        BoardDataConverter.ModelInputSnapshot modelSnapshot = converter.getModelInputSnapshot(1L);
        DeviceVerificationDto device = modelSnapshot.devices().get(0);

        assertEquals("closed", device.getState());
        assertEquals("trusted", device.getCurrentStateTrust());
        assertEquals("private", device.getCurrentStatePrivacy());
        assertEquals(template.getManifest(), modelSnapshot.templateManifests().get("Window Shade Display"));
    }

    @Test
    void getDevicesForVerification_capturesTemplateProvenanceWithoutNameGuessing() {
        BoardStorageService storage = mock(BoardStorageService.class);
        DeviceTemplateDto bundled = template("Bundled", List.of(), List.of());
        bundled.setDefaultTemplate(true);
        DeviceTemplateDto custom = template("Custom", List.of(), List.of());
        custom.setDefaultTemplate(false);
        DeviceTemplateDto legacy = template("Legacy", List.of(), List.of());
        legacy.setDefaultTemplate(null);
        when(storage.getSemanticSnapshot(1L)).thenReturn(snapshot(
                List.of(
                        node("bundled", "Bundled", null),
                        node("custom", "Custom", null),
                        node("legacy", "Legacy", null)),
                List.of(bundled, custom, legacy)));

        List<DeviceVerificationDto> devices = new BoardDataConverter(storage)
                .getModelInputSnapshot(1L).devices();

        assertEquals(ModelTokenSource.BUNDLED, devices.get(0).getModelTokenSource());
        assertEquals(ModelTokenSource.CUSTOM, devices.get(1).getModelTokenSource());
        assertEquals(ModelTokenSource.UNKNOWN, devices.get(2).getModelTokenSource());
    }

    @Test
    void getDevicesForVerification_capturesBundledProvenanceThroughManifestNameAlias() {
        BoardStorageService storage = mock(BoardStorageService.class);
        DeviceTemplateDto bundled = template(
                "Window Shade Display", List.of("OpenableState"), List.of("closed", "open"));
        bundled.getManifest().setName("Window Shade");
        bundled.setDefaultTemplate(true);
        when(storage.getSemanticSnapshot(1L)).thenReturn(snapshot(
                List.of(node("shade_1", "Window Shade", "closed")),
                List.of(bundled)));

        BoardDataConverter.ModelInputSnapshot modelSnapshot = new BoardDataConverter(storage)
                .getModelInputSnapshot(1L);
        DeviceVerificationDto device = modelSnapshot.devices().get(0);

        assertEquals(ModelTokenSource.BUNDLED, device.getModelTokenSource());
        assertEquals("closed", device.getState());
        assertEquals(bundled.getManifest(), modelSnapshot.templateManifests().get("Window Shade"));
    }

    private DeviceNodeDto node(String id, String templateName, String state) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId(id);
        node.setTemplateName(templateName);
        node.setLabel(id);
        node.setState(state);
        node.setCurrentStateTrust("trusted");
        node.setCurrentStatePrivacy("private");
        return node;
    }

    private DeviceTemplateDto template(String name, List<String> modes, List<String> workingStates) {
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName(name);
        template.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name(name)
                .modes(modes)
                .workingStates(workingStates.stream()
                        .map(state -> DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                                .name(state)
                                .build())
                        .toList())
                .build());
        return template;
    }

    private BoardSemanticSnapshotDto snapshot(
            List<DeviceNodeDto> nodes,
            List<DeviceTemplateDto> templates) {
        return new BoardSemanticSnapshotDto(
                nodes, List.of(), List.of(), List.of(), templates);
    }
}
