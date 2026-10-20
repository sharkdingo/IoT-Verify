package cn.edu.nju.Iot_Verify.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountProfileDto {
    private Long userId;
    private String phone;
    private String username;
    private LocalDateTime createdAt;
}
