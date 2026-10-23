package cn.edu.nju.Iot_Verify.testutil;

import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateNuSmvValidator;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** A persistable enum domain shared by recommendation boundary regressions. */
public final class CaseSensitiveEnumFixture {
    private CaseSensitiveEnumFixture() {
    }

    public static DeviceTemplateDto template() {
        var dto = new DeviceTemplateDto();
        dto.setName("CaseSensor");
        dto.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name("CaseSensor").modes(List.of("Power")).initState("idle")
                .workingStates(List.of(state("idle", "on"), state("active", "On")))
                .internalVariables(List.of(
                        DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                                .name("reading").isInside(false).reads(true).falsifiableWhenCompromised(true)
                                .values(List.of("on", "On")).trust("trusted").privacy("public").build(),
                        DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                                .name("level").isInside(true).falsifiableWhenCompromised(false)
                                .values(List.of("on", "On")).trust("trusted").privacy("public").build()))
                .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                        .name("activate").signal(false).startState("").endState("active").build()))
                .build());
        new DeviceTemplateSchemaValidator(new ObjectMapper()).validateManifest(dto.getName(), dto.getManifest());
        new DeviceTemplateNuSmvValidator(null).validateTemplateManifestForNuSmv(dto.getName(), dto.getManifest());
        return dto;
    }

    private static DeviceTemplateDto.DeviceManifest.WorkingState state(String name, String level) {
        return DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                .name(name).trust("trusted").privacy("public")
                .dynamics(List.of(DeviceTemplateDto.DeviceManifest.Dynamic.builder()
                        .variableName("level").value(level).build())).build();
    }
}
