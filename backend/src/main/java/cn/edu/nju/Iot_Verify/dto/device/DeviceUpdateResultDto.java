package cn.edu.nju.Iot_Verify.dto.device;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Authoritative result of a targeted layout or runtime subresource update. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceUpdateResultDto {

    private String operation;
    private String mutationType;
    @Builder.Default
    private List<String> changedFields = List.of();
    private DeviceNodeDto previousDevice;
    private DeviceNodeDto currentDevice;
    @Builder.Default
    private List<DeviceNodeDto> currentNodes = List.of();
    private int currentCount;
    /** Present only when an actual update was committed and journalled. */
    private Boolean canUndo;
    /** Present only when an actual update was committed and journalled. */
    private Boolean canRedo;
    private String editHistoryRevision;
}
