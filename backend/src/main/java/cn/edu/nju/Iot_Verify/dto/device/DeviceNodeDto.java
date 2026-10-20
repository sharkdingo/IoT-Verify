package cn.edu.nju.Iot_Verify.dto.device;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DeviceNodeDto {

    // Include the BOM that ECMAScript treats as whitespace when checking a portable identity.
    public static final String ID_PATTERN = "(?U)[^\\s\\uFEFF](?:[\\s\\S]*[^\\s\\uFEFF])?";

    @NotBlank(message = "Node ID is required")
    @Size(max = 100, message = "Node ID must be at most 100 characters")
    @Pattern(regexp = ID_PATTERN, message = "Node ID must not have leading or trailing whitespace")
    private String id;

    @NotBlank(message = "Template name is required")
    @Size(max = 100, message = "Template name must be at most 100 characters")
    private String templateName;

    @NotBlank(message = "Label is required")
    @Size(max = 255, message = "Label must be at most 255 characters")
    private String label;

    @Valid
    @NotNull(message = "Position is required")
    private Position position;

    @Size(max = 50, message = "State must be at most 50 characters")
    private String state;

    @NotNull(message = "Width is required")
    @Min(value = DeviceLayoutDto.MIN_WIDTH, message = "Width must be at least 80")
    @Max(value = DeviceLayoutDto.MAX_WIDTH, message = "Width must be at most 2000")
    private Integer width;

    @NotNull(message = "Height is required")
    @Min(value = DeviceLayoutDto.MIN_HEIGHT, message = "Height must be at least 60")
    @Max(value = DeviceLayoutDto.MAX_HEIGHT, message = "Height must be at most 2000")
    private Integer height;

    // 运行时状态（持久化需要，同时可用于从画布数据转换为验证数据）
    private String currentStateTrust;
    private String currentStatePrivacy;
    @Size(max = RequestLimits.MAX_DEVICE_VARIABLES, message = "At most 100 local variable values are allowed")
    private List<@Valid @NotNull(message = "Variable item cannot be null") VariableStateDto> variables;
    @Size(max = RequestLimits.MAX_DEVICE_PRIVACIES, message = "At most 100 local sensitivity values are allowed")
    private List<@Valid @NotNull(message = "Privacy item cannot be null") PrivacyStateDto> privacies;

    @Data
    public static class Position {
        @NotNull(message = "X coordinate is required")
        private Double x;

        @NotNull(message = "Y coordinate is required")
        private Double y;
    }
}
