package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import org.mockito.stubbing.OngoingStubbing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

final class ModelRunBoardFixture {

    private ModelRunBoardFixture() {
    }

    static void stubBoardSnapshot(BoardDataConverter converter,
                                  List<DeviceVerificationDto> devices,
                                  List<RuleDto> rules, List<SpecificationDto> specs) {
        // Capture new immutable lists per read, before a submitted worker can see later fixture edits.
        lenient().when(converter.getModelInputSnapshot(anyLong())).thenAnswer(invocation ->
                new BoardDataConverter.ModelInputSnapshot(
                        playbackNodes(devices), devices, List.of(), rules, specs, Map.of()));
    }

    static OngoingStubbing<SmvGenerator.GenerateResult> stubResolvedGeneration(SmvGenerator generator)
            throws IOException {
        return when(generator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(), any(), any(), anyMap()));
    }

    static List<DeviceNodeDto> playbackNodes(List<DeviceVerificationDto> devices) {
        if (devices == null) return null;
        List<DeviceNodeDto> nodes = new ArrayList<>();
        for (int index = 0; index < devices.size(); index++) {
            DeviceVerificationDto device = devices.get(index);
            DeviceNodeDto node = new DeviceNodeDto();
            String id = device != null && device.getVarName() != null && !device.getVarName().isBlank()
                    ? device.getVarName() : "invalid_" + index;
            node.setId(id);
            node.setTemplateName(device != null && device.getTemplateName() != null
                    && !device.getTemplateName().isBlank() ? device.getTemplateName() : "Invalid");
            node.setLabel(device != null && device.getDeviceLabel() != null
                    && !device.getDeviceLabel().isBlank() ? device.getDeviceLabel() : id);
            DeviceNodeDto.Position position = new DeviceNodeDto.Position();
            position.setX((double) index * 240);
            position.setY(0.0);
            node.setPosition(position);
            node.setState("Working");
            node.setWidth(160);
            node.setHeight(120);
            nodes.add(node);
        }
        return nodes;
    }
}
