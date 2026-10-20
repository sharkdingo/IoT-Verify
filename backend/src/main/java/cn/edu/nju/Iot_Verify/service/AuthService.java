package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.auth.AuthResponseDto;
import cn.edu.nju.Iot_Verify.dto.auth.AccountProfileDto;
import cn.edu.nju.Iot_Verify.dto.auth.ChangePasswordRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.DeleteAccountRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.LoginRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.RegisterRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.UpdateUsernameRequestDto;

public interface AuthService {
    AuthResponseDto register(RegisterRequestDto request);
    AuthResponseDto login(LoginRequestDto request);
    AccountProfileDto getAccount(Long userId);
    AccountProfileDto updateUsername(Long userId, UpdateUsernameRequestDto request);
    void changePassword(Long userId, ChangePasswordRequestDto request);
    void logout(Long userId, String authHeader);
    void deleteAccount(Long userId, String authHeader, DeleteAccountRequestDto request);
}
