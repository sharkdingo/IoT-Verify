package cn.edu.nju.Iot_Verify.dto.rule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Complete before/after snapshots for a targeted rule edit. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleUpdateRequestDto {
    @Valid
    @NotNull(message = "Expected rule snapshot is required")
    private RuleDto expected;

    @Valid
    @NotNull(message = "Desired rule is required")
    private RuleDto desired;
}
