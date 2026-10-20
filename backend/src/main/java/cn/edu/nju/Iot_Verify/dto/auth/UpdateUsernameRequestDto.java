package cn.edu.nju.Iot_Verify.dto.auth;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class UpdateUsernameRequestDto {
    @NotBlank(message = "Username is required")
    @Size(max = RequestLimits.MAX_USERNAME_LENGTH,
            message = "Username must not exceed 100 characters before normalization")
    private String username;

    @NotBlank(message = "Current password is required")
    @Size(max = 128, message = "Current password must not exceed 128 characters")
    @ToString.Exclude
    private String currentPassword;

    @NotBlank(message = "Expected username is required")
    @Size(max = RequestLimits.MAX_USERNAME_LENGTH,
            message = "Expected username must not exceed 100 characters")
    private String expectedUsername;
}
