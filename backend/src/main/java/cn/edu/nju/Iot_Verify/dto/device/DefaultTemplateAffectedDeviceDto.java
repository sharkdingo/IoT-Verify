package cn.edu.nju.Iot_Verify.dto.device;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultTemplateAffectedDeviceDto {
    private String deviceId;
    private String deviceLabel;
    private String templateName;
    @Builder.Default
    private List<DefaultTemplateRuntimeChangeDto> runtimeChanges = List.of();
}
