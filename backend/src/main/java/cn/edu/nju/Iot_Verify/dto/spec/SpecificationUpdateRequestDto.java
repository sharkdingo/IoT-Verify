package cn.edu.nju.Iot_Verify.dto.spec;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Complete before/after snapshots for a targeted specification edit. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpecificationUpdateRequestDto {
    @Valid
    @NotNull(message = "Expected specification snapshot is required")
    private SpecificationDto expected;

    @Valid
    @NotNull(message = "Desired specification is required")
    private SpecificationDto desired;
}
