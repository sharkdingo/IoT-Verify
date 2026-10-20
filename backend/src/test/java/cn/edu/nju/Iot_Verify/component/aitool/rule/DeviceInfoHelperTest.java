package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.service.recommendation.DeviceInfoHelper;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeviceInfoHelperTest {

    @Test
    void getDevicesWithTemplateInfo_matchesTemplateAliasesAndDoesNotReserveVariablePrefix() {
        List<DeviceNodeDto> nodes = List.of(
                node("shade_1", "Bedroom Shade", "Window Shade", "closed"),
                node("shade_1_position", "position", "variable_position", "0")
        );
        List<DeviceTemplateDto> templates = List.of(
                template("Window Shade Display", "Window Shade"),
                template("variable_position", "variable_position")
        );

        BoardSemanticSnapshotDto snapshot = new BoardSemanticSnapshotDto(
                nodes, List.of(), List.of(), List.of(), templates);
        DeviceInfoHelper helper = new DeviceInfoHelper();
        List<DeviceInfoHelper.DeviceInfo> devices = helper.getDevicesWithTemplateInfo(snapshot);

        assertEquals(2, devices.size());
        DeviceInfoHelper.DeviceInfo shade = devices.get(0);
        assertEquals("shade_1", shade.nodeId());
        assertEquals("Window Shade", shade.templateName());
        assertEquals(List.of("OpenableState"), shade.modes().stream().map(DeviceInfoHelper.ModeInfo::name).toList());
        assertEquals(List.of("closed", "open"), shade.workingStates());
        assertEquals("open", shade.apis().get(0).name());
        assertEquals("shade_1_position", devices.get(1).nodeId());
        assertEquals("variable_position", devices.get(1).templateName());
    }

    private DeviceNodeDto node(String id, String label, String templateName, String state) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId(id);
        node.setLabel(label);
        node.setTemplateName(templateName);
        node.setState(state);
        return node;
    }

    private DeviceTemplateDto template(String recordName, String manifestName) {
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName(recordName);
        template.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name(manifestName)
                .modes(List.of("OpenableState"))
                .initState("closed")
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("closed").build(),
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("open").build()
                ))
                .apis(List.of(
                        DeviceTemplateDto.DeviceManifest.API.builder()
                                .name("open")
                                .signal(false)
                                .startState("closed")
                                .endState("open")
                                .build()
                ))
                .build());
        return template;
    }
}
