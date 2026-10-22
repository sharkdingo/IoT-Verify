// src/main/java/cn/edu/nju/Iot_Verify/dto/spec/SpecificationDto.java
package cn.edu.nju.Iot_Verify.dto.spec;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE,
                isGetterVisibility = JsonAutoDetect.Visibility.NONE,
                fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SpecificationDto {

    /** Frozen evidence uses normalized model identities and generated display caches. */
    public interface FrozenModel {}

    @NotBlank(message = "Specification ID is required", groups = {Default.class, FrozenModel.class})
    @Size(max = 100, message = "Specification ID must be at most 100 characters", groups = {Default.class, FrozenModel.class})
    private String id;

    @NotBlank(message = "Template ID is required", groups = {Default.class, FrozenModel.class})
    @Pattern(regexp = "^[1-7]$", message = "Template ID must be one of 1, 2, 3, 4, 5, 6, 7", groups = {Default.class, FrozenModel.class})
    @Size(max = 10, message = "Template ID must be at most 10 characters", groups = {Default.class, FrozenModel.class})
    private String templateId;

    /** Response/display cache rebuilt from templateId; ignored as write authority. */
    @Size(max = 255, message = "Template label must be at most 255 characters")
    private String templateLabel;

    @Valid
    @NotNull(message = "A-conditions cannot be null", groups = {Default.class, FrozenModel.class})
    @JsonProperty("aConditions")
    @Size(max = RequestLimits.MAX_SPEC_CONDITIONS, message = "At most 50 A-conditions are allowed", groups = {Default.class, FrozenModel.class})
    private List<@Valid @NotNull(message = "A-condition item cannot be null", groups = {Default.class, FrozenModel.class}) SpecConditionDto> aConditions = new ArrayList<>();

    @Valid
    @NotNull(message = "If-conditions cannot be null", groups = {Default.class, FrozenModel.class})
    @Size(max = RequestLimits.MAX_SPEC_CONDITIONS, message = "At most 50 If-conditions are allowed", groups = {Default.class, FrozenModel.class})
    private List<@Valid @NotNull(message = "If-condition item cannot be null", groups = {Default.class, FrozenModel.class}) SpecConditionDto> ifConditions = new ArrayList<>();

    @Valid
    @NotNull(message = "Then-conditions cannot be null", groups = {Default.class, FrozenModel.class})
    @Size(max = RequestLimits.MAX_SPEC_CONDITIONS, message = "At most 50 Then-conditions are allowed", groups = {Default.class, FrozenModel.class})
    private List<@Valid @NotNull(message = "Then-condition item cannot be null", groups = {Default.class, FrozenModel.class}) SpecConditionDto> thenConditions = new ArrayList<>();

    /**
     * User-readable preview rebuilt from structured conditions; never parsed for verification.
     *
     * <p>This is why three "implementations" of each spec formula legitimately coexist and an audit reading them
     * as a duplication defect is misreading them. {@code SmvSpecificationBuilder} builds the model from the
     * structured conditions and never calls {@code getFormula()}; {@code SpecificationFormulaPreview} renders
     * for the LLM (used only by {@code BoardOverviewTool}/{@code ListSpecsTool}); and the frontend's
     * {@code buildSpecFormula} renders for the user, deliberately in a different notation
     * ({@code CTL AG NOT (…)} rather than {@code CTLSPEC AG !(…)}) because its audience is human. Only the first
     * decides what NuSMV checks, so the other two cannot make the model disagree with itself.
     */
    @Size(max = 4000, message = "Formula preview must be at most 4000 characters")
    private String formula;

    @Size(max = 50, message = "At most 50 devices can be bound to a specification")
    @Size(max = RequestLimits.MAX_DEVICES, message = "Frozen specification references exceed the model device limit", groups = FrozenModel.class)
    @Valid
    private List<@Valid @NotNull(message = "Specification device item cannot be null", groups = {Default.class, FrozenModel.class}) DeviceRefDto> devices = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceRefDto {
        @NotBlank(message = "Specification device ID is required", groups = {Default.class, FrozenModel.class})
        @Size(max = 100, message = "Device ID must be at most 100 characters")
        @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Model device ID must be at most 200 characters", groups = FrozenModel.class)
        private String deviceId;

        @Size(max = 100, message = "Device label must be at most 100 characters")
        private String deviceLabel;

        @NotNull(message = "Selected APIs cannot be null")
        @Size(max = 100, message = "At most 100 APIs can be selected for a specification device")
        private List<@NotNull(message = "Selected API name cannot be null")
                @Size(max = 100, message = "Selected API name must be at most 100 characters") String> selectedApis = new ArrayList<>();
    }

    @JsonIgnore
    @AssertTrue(message = "Specification condition side must match its containing collection", groups = {Default.class, FrozenModel.class})
    public boolean isConditionSidesConsistent() {
        return conditionSidesMatch(aConditions, "a")
                && conditionSidesMatch(ifConditions, "if")
                && conditionSidesMatch(thenConditions, "then");
    }

    private boolean conditionSidesMatch(List<SpecConditionDto> conditions, String expectedSide) {
        if (conditions == null) {
            return true;
        }
        for (SpecConditionDto condition : conditions) {
            if (condition == null) {
                continue;
            }
            String side = condition.getSide();
            if (side != null && !side.isBlank() && !expectedSide.equals(side)) {
                return false;
            }
        }
        return true;
    }
}
