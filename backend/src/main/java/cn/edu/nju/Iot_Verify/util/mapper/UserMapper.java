package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.auth.AuthResponseDto;
import cn.edu.nju.Iot_Verify.po.UserPo;
import org.springframework.stereotype.Component;

/**
 * User PO 与 Auth DTOs 之间的转换映射器
 */
@Component
public class UserMapper {

    public AuthResponseDto toAuthResponseDto(UserPo userPo, String token) {
        if (userPo == null) {
            return null;
        }
        AuthResponseDto dto = new AuthResponseDto();
        dto.setUserId(userPo.getId());
        dto.setPhone(userPo.getPhone());
        dto.setUsername(userPo.getUsername());
        dto.setToken(token);
        return dto;
    }
}
