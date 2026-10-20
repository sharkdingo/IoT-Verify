package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.auth.ChangePasswordRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.UpdateUsernameRequestDto;
import cn.edu.nju.Iot_Verify.exception.AccountChangeException;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.service.TokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplAccountTest {
    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwords;
    @Mock private TokenBlacklistService blacklist;
    @InjectMocks private AuthServiceImpl service;

    private UserPo user;

    @BeforeEach
    void setUp() {
        user = UserPo.builder().id(7L).phone("13800138000").username("Alice")
                .password("encoded").createdAt(LocalDateTime.of(2026, 10, 9, 10, 0)).build();
    }

    @Test
    void getAccountReturnsOnlyTheCurrentUsersPublicProfile() {
        when(users.findById(7L)).thenReturn(Optional.of(user));

        var profile = service.getAccount(7L);

        assertEquals(7L, profile.getUserId());
        assertEquals(user.getCreatedAt(), profile.getCreatedAt());
        assertEquals("Alice", profile.getUsername());
        verify(users).findById(7L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void getAccountRejectsMissingOrDeletedUser() {
        assertThrows(UnauthorizedException.class, () -> service.getAccount(null));
        assertThrows(UnauthorizedException.class, () -> service.getAccount(7L));
    }

    @Test
    void usernameChangeUsesCanonicalNameAndKeepsIdentityAndCredentialVersion() {
        allowCurrentPassword();

        var profile = service.updateUsername(7L, username("\u00a0Cafe\u0301\u2003", "Alice"));

        assertEquals("Caf\u00e9", profile.getUsername());
        assertEquals(7L, profile.getUserId());
        assertEquals("13800138000", profile.getPhone());
        assertEquals(0L, user.getAuthVersion());
        assertEquals("encoded", user.getPassword());
        verify(users).saveAndFlush(user);
        verifyNoInteractions(blacklist);
    }

    @Test
    void staleUsernameDoesNotOverwriteTheCurrentNameAndReturnsCurrentProfile() {
        allowCurrentPassword();

        var error = assertThrows(AccountChangeException.class,
                () -> service.updateUsername(7L, username("Carol", "Previous")));

        assertEquals(409, error.getCode());
        assertEquals("ACCOUNT_PROFILE_CHANGED", error.getReasonCode());
        assertEquals("Alice", error.getCurrentProfile().getUsername());
        assertEquals("Alice", user.getUsername());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void unchangedCanonicalUsernameIsAnAuthenticatedNoOp() {
        allowCurrentPassword();

        assertEquals("Alice", service.updateUsername(7L, username("  Alice  ", "Alice")).getUsername());

        verify(users, never()).existsByUsername(anyString());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void duplicateUsernameIsRejectedBeforeAWrite() {
        allowCurrentPassword();
        when(users.existsByUsername("Taken")).thenReturn(true);

        var error = assertThrows(AccountChangeException.class,
                () -> service.updateUsername(7L, username("Taken", "Alice")));

        assertEquals("USERNAME_ALREADY_EXISTS", error.getReasonCode());
        assertEquals("Alice", user.getUsername());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void uniqueIndexRaceIsStillATypedUsernameConflict() {
        allowCurrentPassword();
        when(users.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("unique index",
                new org.hibernate.exception.ConstraintViolationException("unique", new SQLException(),
                        org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE, "username")));

        var error = assertThrows(AccountChangeException.class,
                () -> service.updateUsername(7L, username("Taken", "Alice")));

        assertEquals(409, error.getCode());
        assertEquals("USERNAME_ALREADY_EXISTS", error.getReasonCode());
    }

    @Test
    void unrelatedIntegrityFailureIsNotReportedAsATakenUsername() {
        allowCurrentPassword();
        var failure = new DataIntegrityViolationException("unrelated integrity failure");
        when(users.saveAndFlush(user)).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.updateUsername(7L, username("Carol", "Alice"))));
    }

    @Test
    void bothAccountMutationsRejectWrongCurrentPasswordWithoutWriting() {
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        var usernameError = assertThrows(AccountChangeException.class,
                () -> service.updateUsername(7L, username("Carol", "Alice")));
        var passwordError = assertThrows(AccountChangeException.class,
                () -> service.changePassword(7L, password("NewPassword2026")));

        assertEquals(400, usernameError.getCode());
        assertEquals("CURRENT_PASSWORD_INCORRECT", passwordError.getReasonCode());
        assertEquals(0L, user.getAuthVersion());
        assertEquals("encoded", user.getPassword());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void passwordChangeCommitsHashAndVersionThroughTheSameUserRow() {
        allowCurrentPassword();
        when(passwords.encode("NewPassword2026")).thenReturn("new-encoded");

        service.changePassword(7L, password("NewPassword2026"));

        assertEquals("new-encoded", user.getPassword());
        assertEquals(1L, user.getAuthVersion());
        assertEquals("Alice", user.getUsername());
        verify(users).findByIdForUpdate(7L);
        verify(users).saveAndFlush(user);
        verifyNoInteractions(blacklist);
    }

    @Test
    void unchangedPasswordDoesNotAdvanceVersionOrEncode() {
        allowCurrentPassword();
        when(passwords.matches("SamePassword2026", "encoded")).thenReturn(true);

        var error = assertThrows(AccountChangeException.class,
                () -> service.changePassword(7L, password("SamePassword2026")));

        assertEquals("NEW_PASSWORD_UNCHANGED", error.getReasonCode());
        assertEquals(0L, user.getAuthVersion());
        verify(passwords, never()).encode(anyString());
        verify(users, never()).saveAndFlush(any());
    }

    private void allowCurrentPassword() {
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(passwords.matches("CurrentPassword2026", "encoded")).thenReturn(true);
    }

    private UpdateUsernameRequestDto username(String name, String expected) {
        var request = new UpdateUsernameRequestDto();
        request.setUsername(name);
        request.setExpectedUsername(expected);
        request.setCurrentPassword("CurrentPassword2026");
        return request;
    }

    private ChangePasswordRequestDto password(String newPassword) {
        var request = new ChangePasswordRequestDto();
        request.setCurrentPassword("CurrentPassword2026");
        request.setNewPassword(newPassword);
        return request;
    }
}
