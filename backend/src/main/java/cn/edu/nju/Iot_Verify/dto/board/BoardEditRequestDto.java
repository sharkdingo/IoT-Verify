package cn.edu.nju.Iot_Verify.dto.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BoardEditRequestDto(
        @NotBlank @Pattern(regexp = "[0-9]{1,19}:[0-9]{1,19}:[0-9]{1,19}") String expectedEditHistoryRevision) {
}
