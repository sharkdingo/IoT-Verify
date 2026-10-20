package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.dto.auth.AccountProfileDto;
import cn.edu.nju.Iot_Verify.exception.AccountChangeException;
import cn.edu.nju.Iot_Verify.exception.GlobalExceptionHandler;
import cn.edu.nju.Iot_Verify.security.AuthRateLimitGuard;
import cn.edu.nju.Iot_Verify.security.CurrentUserArgumentResolver;
import cn.edu.nju.Iot_Verify.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerAccountTest {
    private final AuthService service = mock(AuthService.class);
    private final AuthRateLimitGuard rateLimit = mock(AuthRateLimitGuard.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null));
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(service, rateLimit))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accountReadReturnsOnlyTheProfileForTheAuthenticatedOwner() throws Exception {
        when(service.getAccount(7L)).thenReturn(profile("Alice"));

        mvc.perform(get("/api/auth/account"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.username").value("Alice"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.authVersion").doesNotExist());
        verify(service).getAccount(7L);
    }

    @Test
    void usernameChangeReturnsTheAuthoritativeProfileAndConsumesTheAccountBucket() throws Exception {
        when(service.updateUsername(eq(7L), any())).thenReturn(profile("Carol"));

        mvc.perform(patch("/api/auth/account/username").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Carol\",\"expectedUsername\":\"Alice\",\"currentPassword\":\"CurrentPassword2026\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("Carol"));
        verify(rateLimit).checkAccountChange(eq(7L), any());
    }

    @Test
    void passwordChangeReturnsVoidAndConsumesTheSameAccountBucket() throws Exception {
        mvc.perform(post("/api/auth/account/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"NewPassword2026\",\"currentPassword\":\"CurrentPassword2026\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        verify(service).changePassword(eq(7L), any());
        verify(rateLimit).checkAccountChange(eq(7L), any());
    }

    @Test
    void wrongCurrentPasswordIsAFieldErrorAndDoesNotReturn401() throws Exception {
        doThrow(AccountChangeException.incorrectPassword()).when(service).changePassword(eq(7L), any());

        mvc.perform(post("/api/auth/account/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"NewPassword2026\",\"currentPassword\":\"WrongPassword2026\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reasonCode").value("CURRENT_PASSWORD_INCORRECT"))
                .andExpect(jsonPath("$.data.errors.currentPassword").exists());
    }

    @Test
    void staleUsernameConflictCarriesTheCurrentSafeProfile() throws Exception {
        doThrow(AccountChangeException.staleProfile(profile("Carol"))).when(service).updateUsername(eq(7L), any());

        mvc.perform(patch("/api/auth/account/username").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Dana\",\"expectedUsername\":\"Alice\",\"currentPassword\":\"CurrentPassword2026\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reasonCode").value("ACCOUNT_PROFILE_CHANGED"))
                .andExpect(jsonPath("$.data.currentProfile.username").value("Carol"))
                .andExpect(jsonPath("$.data.currentProfile.authVersion").doesNotExist());
    }

    @Test
    void missingUsernameBaselineAndAnOverlongBcryptPasswordAreRejectedBeforeTheService() throws Exception {
        mvc.perform(patch("/api/auth/account/username").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Carol\",\"currentPassword\":\"CurrentPassword2026\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errors.expectedUsername").exists());
        mvc.perform(post("/api/auth/account/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"" + "\u4e2d".repeat(25) + "\",\"currentPassword\":\"CurrentPassword2026\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, rateLimit);
    }

    private AccountProfileDto profile(String name) {
        return AccountProfileDto.builder().userId(7L).username(name).phone("13800138000").build();
    }
}
