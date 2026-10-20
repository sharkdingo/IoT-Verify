package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.EnvironmentProvenanceCollector;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class ModelRunPreparationTest {

    private final SmvGenerator generator = mock(SmvGenerator.class);
    private final EnvironmentProvenanceCollector provenance = spy(new EnvironmentProvenanceCollector());
    private final DeviceVerificationDto device = device();
    private final List<DeviceVerificationDto> devices = List.of(device);
    private final Map<String, DeviceManifest> templates = Map.of(
            "Sensor", DeviceManifest.builder().name("Sensor").build());

    @Test
    void structuralErrorsPreventTemplateDerivationAndPreserveOriginalContext() {
        stubCapturedSensor();
        Map<String, String> errors = NusmvRequestValidator.newErrors();
        errors.put("rules[0].command", "Command is required");

        var prepared = ModelRunPreparation.prepare(generator, provenance, devices,
                List.of(), List.of(), List.of(), false, templates, errors);

        assertSame(devices, prepared.devices());
        assertNull(prepared.modelSnapshot());
        assertTrue(prepared.deviceSmvMap().isEmpty());
        assertTrue(prepared.templateManifests().isEmpty());
        assertEquals(Map.of("rules[0].command", "Command is required"), errors);
        verifyNoInteractions(generator, provenance);
    }

    @Test
    void frozenTemplateFailureRemainsADeviceValidationErrorWithoutAModelSnapshot() {
        when(generator.captureDeviceModelFromTemplateSnapshots(devices, templates))
                .thenThrow(SmvGenerationException.smvGenerationError("Frozen template is missing"));
        Map<String, String> errors = NusmvRequestValidator.newErrors();

        var prepared = ModelRunPreparation.prepare(generator, provenance, devices,
                List.of(), List.of(), List.of(), false, templates, errors);

        assertTrue(errors.get("devices").contains("Frozen template is missing"));
        assertSame(devices, prepared.devices());
        assertNull(prepared.modelSnapshot());
        verify(generator).captureDeviceModelFromTemplateSnapshots(devices, templates);
        verifyNoMoreInteractions(generator);
        verifyNoInteractions(provenance);
    }

    @Test
    void unexpectedCaptureFailureIsNotConvertedToValidationOrSuccess() {
        IllegalStateException failure = new IllegalStateException("Capture failed");
        when(generator.captureDeviceModelFromTemplateSnapshots(devices, templates)).thenThrow(failure);
        Map<String, String> errors = NusmvRequestValidator.newErrors();

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ModelRunPreparation.prepare(generator, provenance, devices,
                        List.of(), List.of(), List.of(), false, templates, errors)));
        assertTrue(errors.isEmpty());
        verifyNoInteractions(provenance);
    }

    @Test
    void invalidSpecificationStopsExpansionWhileRetainingCapturedTemplatesAndDefaults() {
        SmvGenerator.CapturedDeviceModel captured = stubCapturedSensor();
        Map<String, String> errors = NusmvRequestValidator.newErrors();

        var prepared = ModelRunPreparation.prepare(generator, provenance, devices,
                List.of(), List.of(specification("missing")), List.of(), false, templates, errors);

        assertTrue(errors.containsKey("specs[0].aConditions[0].key"));
        assertEquals("15", prepared.environmentVariables().get(0).getValue());
        assertSame(devices, prepared.devices());
        assertSame(captured.templateManifests(), prepared.templateManifests());
        assertNull(prepared.modelSnapshot());
        assertTrue(prepared.deviceSmvMap().isEmpty());
        verify(generator).captureDeviceModelFromTemplateSnapshots(devices, templates);
        verifyNoMoreInteractions(generator);
        verifyNoInteractions(provenance);
    }

    @Test
    void invalidRulePreventsModelExpansionAndProvenanceCapture() {
        stubCapturedSensor();
        RuleDto rule = new RuleDto();
        rule.setCommand(RuleDto.Command.builder().deviceName("sensor_1").action("missing").build());
        Map<String, String> errors = NusmvRequestValidator.newErrors();

        var prepared = ModelRunPreparation.prepare(generator, provenance, devices,
                List.of(rule), List.of(), List.of(), false, templates, errors);

        assertTrue(errors.containsKey("rules[0].command.action"));
        assertNull(prepared.modelSnapshot());
        verify(generator).captureDeviceModelFromTemplateSnapshots(devices, templates);
        verifyNoMoreInteractions(generator);
        verifyNoInteractions(provenance);
    }

    @Test
    void verificationUsesExpandedDefaultsAndExactSpecCountWithoutChangingFrozenRequestDevices() {
        SmvGenerator.CapturedDeviceModel captured = stubCapturedSensor();
        Map<String, String> errors = NusmvRequestValidator.newErrors();

        var prepared = ModelRunPreparation.prepare(generator, provenance, devices,
                List.of(), List.of(specification("temperature")), List.of(), false, templates, errors);

        assertTrue(errors.isEmpty(), errors.toString());
        assertNotSame(device, prepared.devices().get(0));
        assertNull(device.getVariables());
        assertEquals("temperature", prepared.devices().get(0).getVariables().get(0).getName());
        assertEquals("15", prepared.devices().get(0).getVariables().get(0).getValue());
        assertEquals("trusted", prepared.devices().get(0).getVariables().get(0).getTrust());
        assertEquals("private", prepared.devices().get(0).getPrivacies().get(0).getPrivacy());
        assertSame(captured.templateManifests(), prepared.templateManifests());
        assertSame(captured.deviceSmvMap(), prepared.deviceSmvMap());
        assertTrue(prepared.modelSnapshot().isTemplatesFrozen());
        assertEquals(1, prepared.modelSnapshot().getDeviceCount());
        assertEquals(1, prepared.modelSnapshot().getSpecificationCount());
        assertEquals(1, prepared.modelSnapshot().getEnvironmentVariableCount());
        assertEquals(1, prepared.modelSnapshot().getDeviceTemplateCount());
        assertEquals("temperature", prepared.modelSnapshot().getEnvironmentProvenance().get(0).getName());
        verify(provenance).collectEnvironmentProvenance(same(prepared.environmentVariables()),
                same(prepared.devices()), same(prepared.deviceSmvMap()));
        verify(generator).captureDeviceModelFromTemplateSnapshots(devices, templates);
        verify(generator).buildDeviceSmvMapFromTemplateSnapshots(
                same(prepared.devices()), same(captured.templateManifests()));
        verifyNoMoreInteractions(generator);
    }

    @Test
    void simulationPreparesTheSameEnvironmentWithNoSpecificationCount() {
        stubCapturedSensor();
        Map<String, String> errors = NusmvRequestValidator.newErrors();

        var prepared = ModelRunPreparation.prepare(generator, provenance, devices,
                List.of(), List.of(), List.of(), false, templates, errors);

        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(0, prepared.modelSnapshot().getSpecificationCount());
        assertEquals("15", prepared.environmentVariables().get(0).getValue());
        assertEquals("15", prepared.devices().get(0).getVariables().get(0).getValue());
    }

    private SmvGenerator.CapturedDeviceModel stubCapturedSensor() {
        DeviceManifest.InternalVariable temperature = DeviceManifest.InternalVariable.builder()
                .name("temperature").isInside(false).reads(true).lowerBound(15).upperBound(35)
                .trust("trusted").privacy("private").build();
        DeviceManifest manifest = DeviceManifest.builder().name("Sensor")
                .internalVariables(List.of(temperature)).build();
        DeviceSmvData data = new DeviceSmvData();
        data.setVarName("sensor_1");
        data.setTemplateName("Sensor");
        data.setModelTokenSource(ModelTokenSource.BUNDLED);
        data.setManifest(manifest);
        data.setSensor(true);
        data.setEnvVariables(Map.of("temperature", temperature));
        data.setSharedDeclarations(Map.of("temperature", temperature));
        SmvGenerator.CapturedDeviceModel captured = new SmvGenerator.CapturedDeviceModel(
                Map.of("Sensor", manifest), Map.of("sensor_1", data));
        when(generator.captureDeviceModelFromTemplateSnapshots(devices, templates)).thenReturn(captured);
        when(generator.buildDeviceSmvMapFromTemplateSnapshots(anyList(), same(captured.templateManifests())))
                .thenReturn(captured.deviceSmvMap());
        return captured;
    }

    private DeviceVerificationDto device() {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("sensor_1");
        device.setTemplateName("Sensor");
        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        return device;
    }

    private SpecificationDto specification(String key) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_1");
        spec.setTemplateId("1");
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("sensor_1");
        condition.setTargetType("variable");
        condition.setKey(key);
        condition.setVariableSource("environment");
        condition.setRelation(">");
        condition.setValue("20");
        spec.setAConditions(List.of(condition));
        return spec;
    }
}
