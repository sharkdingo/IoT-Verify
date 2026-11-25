package cn.edu.nju.Iot_Verify.dto.device;

import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableChangeDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceDeletionResultDto {
    private String operation;
    @ToString.Exclude
    private String impactToken;
    private DeviceNodeDto deletedDevice;
    @Builder.Default
    private List<RuleDto> removedRules = List.of();
    @Builder.Default
    private List<SpecificationDto> removedSpecifications = List.of();
    @Builder.Default
    private List<DeviceNodeDto> currentNodes = List.of();
    @Builder.Default
    private List<BoardEnvironmentVariableDto> environmentVariables = List.of();
    @Builder.Default
    private List<EnvironmentVariableChangeDto> environmentChanges = List.of();
    @Builder.Default
    private List<RuleDto> currentRules = List.of();
    @Builder.Default
    private List<SpecificationDto> currentSpecifications = List.of();
    @Builder.Default
    private List<DeviceTemplateDto> deviceTemplates = List.of();
    /**
     * Whether the account now has a reversible edit / a redoable one.
     *
     * Captured with the current Board collections in both previews and committed receipts.
     */
    private Boolean canUndo;
    private Boolean canRedo;
    private String editHistoryRevision;
}
