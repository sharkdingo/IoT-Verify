package cn.edu.nju.Iot_Verify.dto.auth;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class ChangePasswordRequestDto {
    @NotBlank(message = "Current password is required")
    @Size(max = 128, message = "Current password must not exceed 128 characters")
    @ToString.Exclude
    private String currentPassword;

    @NotBlank(message = "New password is required")
    @Size(min = RequestLimits.MIN_PASSWORD_LENGTH, max = RequestLimits.MAX_PASSWORD_LENGTH,
            message = "New password must be 10-64 characters")
    @ToString.Exclude
    private String newPassword;

    @AssertTrue(message = "New password must not exceed 72 UTF-8 bytes")
    public boolean isPasswordWithinBcryptLimit() {
        return newPassword == null
                || newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        <= RequestLimits.MAX_PASSWORD_BCRYPT_BYTES;
    }
}
