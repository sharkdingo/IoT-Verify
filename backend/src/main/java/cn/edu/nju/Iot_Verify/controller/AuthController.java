package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.dto.Result;
import cn.edu.nju.Iot_Verify.dto.auth.AuthResponseDto;
import cn.edu.nju.Iot_Verify.dto.auth.AccountProfileDto;
import cn.edu.nju.Iot_Verify.dto.auth.ChangePasswordRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.DeleteAccountRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.LoginRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.RegisterRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.UpdateUsernameRequestDto;
import cn.edu.nju.Iot_Verify.security.CurrentUser;
import cn.edu.nju.Iot_Verify.security.AuthRateLimitGuard;
import cn.edu.nju.Iot_Verify.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthRateLimitGuard authRateLimitGuard;

    @PostMapping("/register")
    public Result<AuthResponseDto> register(
            @Valid @RequestBody RegisterRequestDto request,
            HttpServletRequest httpRequest) {
        authRateLimitGuard.checkRegistration(request.getPhone(), httpRequest);
        return Result.success(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthResponseDto> login(
            @Valid @RequestBody LoginRequestDto request,
            HttpServletRequest httpRequest) {
        authRateLimitGuard.checkLogin(request.getIdentifier(), httpRequest);
        return Result.success(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@CurrentUser Long userId, @RequestHeader("Authorization") String authHeader) {
        authService.logout(userId, authHeader);
        return Result.success();
    }

    @GetMapping("/account")
    public Result<AccountProfileDto> getAccount(@CurrentUser Long userId) {
        return Result.success(authService.getAccount(userId));
    }

    @PatchMapping("/account/username")
    public Result<AccountProfileDto> updateUsername(
            @CurrentUser Long userId,
            @Valid @RequestBody UpdateUsernameRequestDto request,
            HttpServletRequest httpRequest) {
        authRateLimitGuard.checkAccountChange(userId, httpRequest);
        return Result.success(authService.updateUsername(userId, request));
    }

    @PostMapping("/account/password")
    public Result<Void> changePassword(
            @CurrentUser Long userId,
            @Valid @RequestBody ChangePasswordRequestDto request,
            HttpServletRequest httpRequest) {
        authRateLimitGuard.checkAccountChange(userId, httpRequest);
        authService.changePassword(userId, request);
        return Result.success();
    }

    @DeleteMapping("/account")
    public Result<Void> deleteAccount(
            @CurrentUser Long userId,
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody DeleteAccountRequestDto request) {
        authService.deleteAccount(userId, authHeader, request);
        return Result.success();
    }
}
