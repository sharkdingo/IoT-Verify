package cn.edu.nju.Iot_Verify.dto.device;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 设备验证 DTO
 *
 * 仅包含 NuSMV 验证所需的字段，与 DeviceNodeDto（画布 CRUD）分离。
 *
 * 字段语义：
 * - varName / templateName / state: 设备身份和当前状态
 * - currentStateTrust/currentStatePrivacy: current-state label overrides
 * - variables: device-local variable values and trust (IsInside=true only)
 *   → smv.variableValues + smv.instanceVariableTrust
 * - privacies: device-local variable sensitivity overrides
 *
 * <p>Both collections are device-local only. A shared variable (IsInside=false) is rejected here by
 * {@code NusmvRequestValidator} and supplied through the top-level
 * environmentVariables pool instead, whose labels are scenario-wide; reading this list as "any
 * variable" suggests a per-device label for a shared value, which no writer accepts.
 */
@Data
public class DeviceVerificationDto {

    @NotBlank(message = "Device varName is required")
    @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Device varName must be at most 200 characters")
    private String varName;

    /** Display-only instance label captured with the model request; never used for resolution. */
    @Size(max = 200, message = "Device label must be at most 200 characters")
    private String deviceLabel;

    @NotBlank(message = "Template name is required")
    @Size(max = 100, message = "Template name must be at most 100 characters")
    private String templateName;

    /** Server-only provenance captured with the resolved template; never accepted from REST input. */
    @JsonIgnore
    private ModelTokenSource modelTokenSource = ModelTokenSource.UNKNOWN;

    @Size(max = RequestLimits.MAX_VALUE_LENGTH, message = "Device state must be at most 1000 characters")
    private String state;

    // 运行时验证状态
    private String currentStateTrust;
    private String currentStatePrivacy;
    @Size(max = RequestLimits.MAX_DEVICE_VARIABLES, message = "At most 100 device variables can be verified")
    private List<@Valid @NotNull(message = "Variable item cannot be null") VariableStateDto> variables;
    @Size(max = RequestLimits.MAX_DEVICE_PRIVACIES, message = "At most 100 device privacy states can be verified")
    private List<@Valid @NotNull(message = "Privacy item cannot be null") PrivacyStateDto> privacies;
}
