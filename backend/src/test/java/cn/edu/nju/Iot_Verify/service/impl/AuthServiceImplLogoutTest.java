package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.auth.AuthResponseDto;
import cn.edu.nju.Iot_Verify.dto.auth.DeleteAccountRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.LoginRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.RegisterRequestDto;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.repository.BoardEditJournalRepository;
import cn.edu.nju.Iot_Verify.repository.BoardLayoutRepository;
import cn.edu.nju.Iot_Verify.repository.BoardEnvironmentVariableRepository;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceNodeRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceTemplateRepository;
import cn.edu.nju.Iot_Verify.repository.FuzzFindingRepository;
import cn.edu.nju.Iot_Verify.repository.FuzzTaskRepository;
import cn.edu.nju.Iot_Verify.repository.RuleRepository;
import cn.edu.nju.Iot_Verify.repository.SimulationTaskRepository;
import cn.edu.nju.Iot_Verify.repository.SimulationTraceRepository;
import cn.edu.nju.Iot_Verify.repository.SpecificationRepository;
import cn.edu.nju.Iot_Verify.repository.TraceRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.repository.AiSessionStateRepository;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import cn.edu.nju.Iot_Verify.service.AsyncTaskExecutionControl;
import cn.edu.nju.Iot_Verify.service.ChatExecutionControl;
import cn.edu.nju.Iot_Verify.service.ChatService;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.service.InteractiveAiExecutionService;
import cn.edu.nju.Iot_Verify.service.InteractiveFixExecutionService;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.service.TokenBlacklistService;
import cn.edu.nju.Iot_Verify.service.UserService;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.JwtUtil;
import cn.edu.nju.Iot_Verify.util.mapper.UserMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplLogoutTest {

    @Mock private UserService userService;
    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private UserMapper userMapper;
    @Mock private DeviceTemplateService deviceTemplateService;
    @Mock private VerificationService verificationService;
    @Mock private SimulationService simulationService;
    @Mock private FuzzService fuzzService;
    @Mock private ChatService chatService;
    @Mock private InteractiveAiExecutionService interactiveAiExecutionService;
    @Mock private InteractiveFixExecutionService interactiveFixExecutionService;
    @Mock private UserRepository userRepository;
    @Mock private BoardEnvironmentVariableRepository boardEnvironmentVariableRepository;
    @Mock private BoardLayoutRepository boardLayoutRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private ChatSessionRepository chatSessionRepository;
    @Mock private DeviceNodeRepository deviceNodeRepository;
    @Mock private DeviceTemplateRepository deviceTemplateRepository;
    @Mock private FuzzFindingRepository fuzzFindingRepository;
    @Mock private FuzzTaskRepository fuzzTaskRepository;
    @Mock private RuleRepository ruleRepository;
    @Mock private SimulationTaskRepository simulationTaskRepository;
    @Mock private SimulationTraceRepository simulationTraceRepository;
    @Mock private SpecificationRepository specificationRepository;
    @Mock private TraceRepository traceRepository;
    @Mock private VerificationTaskRepository verificationTaskRepository;
    @Mock private AiSessionStateRepository aiSessionStateRepository;
    private final BoardEditJournalRepository boardEditJournalRepository =
            mock(BoardEditJournalRepository.class);

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        verificationService = mock(
                VerificationService.class, withSettings().extraInterfaces(AsyncTaskExecutionControl.class));
        simulationService = mock(
                SimulationService.class, withSettings().extraInterfaces(AsyncTaskExecutionControl.class));
        fuzzService = mock(
                FuzzService.class, withSettings().extraInterfaces(AsyncTaskExecutionControl.class));
        chatService = mock(
                ChatService.class, withSettings().extraInterfaces(ChatExecutionControl.class));
        authService = new AuthServiceImpl(
                userService, jwtUtil, passwordEncoder,
                tokenBlacklistService, userMapper, deviceTemplateService,
                verificationService, simulationService, fuzzService, chatService,
                interactiveAiExecutionService, interactiveFixExecutionService, userRepository,
                boardEnvironmentVariableRepository,
                boardLayoutRepository, chatMessageRepository, chatSessionRepository,
                deviceNodeRepository, deviceTemplateRepository, fuzzFindingRepository,
                fuzzTaskRepository, ruleRepository,
                simulationTaskRepository, simulationTraceRepository, specificationRepository,
                traceRepository, verificationTaskRepository, aiSessionStateRepository,
                boardEditJournalRepository
        );
    }

    @Test
    void login_withUsername_usesOnlyUsernameNamespace() {
        LoginRequestDto request = new LoginRequestDto();
        request.setIdentifier("alice");
        request.setPassword("Pass1234");
        UserPo user = UserPo.builder()
                .id(7L)
                .phone("13900000000")
                .username("alice")
                .password("encoded")
                .build();
        AuthResponseDto response = AuthResponseDto.builder()
                .userId(7L)
                .phone("13900000000")
                .username("alice")
                .token("jwt")
                .build();

        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass1234", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken(7L, "13900000000", 0L)).thenReturn("jwt");
        when(userMapper.toAuthResponseDto(user, "jwt")).thenReturn(response);

        AuthResponseDto result = authService.login(request);

        assertEquals("alice", result.getUsername());
        assertEquals("jwt", result.getToken());
        verify(userService).findByUsername("alice");
        verify(userService, never()).findByPhone(anyString());
    }

    @Test
    void login_withPhone_usesOnlyPhoneNamespace() {
        LoginRequestDto request = new LoginRequestDto();
        request.setIdentifier("13800138000");
        request.setPassword("PhoneOwnerPass");
        UserPo phoneOwner = UserPo.builder()
                .id(7L).phone("13800138000").username("bob").password("phone-password").build();
        AuthResponseDto response = AuthResponseDto.builder()
                .userId(7L).phone("13800138000").username("bob").token("jwt").build();
        when(userService.findByPhone("13800138000")).thenReturn(Optional.of(phoneOwner));
        when(passwordEncoder.matches("PhoneOwnerPass", "phone-password")).thenReturn(true);
        when(jwtUtil.generateToken(7L, "13800138000", 0L)).thenReturn("jwt");
        when(userMapper.toAuthResponseDto(phoneOwner, "jwt")).thenReturn(response);

        AuthResponseDto result = authService.login(request);

        assertEquals(7L, result.getUserId());
        verify(userService).findByPhone("13800138000");
        verify(userService, never()).findByUsername(anyString());
    }

    @Test
    void login_invalidCredentials_usesAccountNeutralMessage() {
        LoginRequestDto request = new LoginRequestDto();
        request.setIdentifier("alice");
        request.setPassword("WrongPass123");
        when(userService.findByUsername("alice")).thenReturn(Optional.empty());

        UnauthorizedException error = assertThrows(
                UnauthorizedException.class, () -> authService.login(request));

        assertEquals("Account or password is incorrect", error.getMessage());
        verify(userService, never()).findByPhone(anyString());
    }

    @Test
    void register_whenDefaultCatalogCannotBeInitialized_doesNotReturnPartialSuccess() {
        RegisterRequestDto request = new RegisterRequestDto();
        request.setPhone("13900000000");
        request.setUsername("alice");
        request.setPassword("Pass1234");
        UserPo user = UserPo.builder().id(7L).phone(request.getPhone()).username(request.getUsername()).build();
        when(userService.register(request.getPhone(), request.getUsername(), request.getPassword()))
                .thenReturn(user);
        when(deviceTemplateService.initDefaultTemplates(7L)).thenReturn(0);

        assertThrows(InternalServerException.class, () -> authService.register(request));

        verify(userMapper, never()).toAuthResponseDto(any(), anyString());
    }

    @Test
    void register_whenDefaultCatalogLoadFails_propagatesFailure() {
        RegisterRequestDto request = new RegisterRequestDto();
        request.setPhone("13900000000");
        request.setUsername("alice");
        request.setPassword("Pass1234");
        UserPo user = UserPo.builder().id(7L).phone(request.getPhone()).username(request.getUsername()).build();
        when(userService.register(request.getPhone(), request.getUsername(), request.getPassword()))
                .thenReturn(user);
        when(deviceTemplateService.initDefaultTemplates(7L))
                .thenThrow(new InternalServerException("defaults invalid"));

        InternalServerException error = assertThrows(
                InternalServerException.class, () -> authService.register(request));

        assertEquals("defaults invalid", error.getMessage());
        verify(userMapper, never()).toAuthResponseDto(any(), anyString());
    }

    // --- 正常登出 ---

    @Test
    void logout_validToken_blacklists() {
        when(jwtUtil.getExpirationSeconds("valid-token")).thenReturn(3600L);

        authService.logout(1L, "Bearer valid-token");

        verify((ChatExecutionControl) chatService).requestUserExecutionStop(1L);
        verify(tokenBlacklistService).blacklist("valid-token", 3600L);
    }

    @Test
    void logout_chatStopFailureDoesNotRevokeTheTokenBeforeTheOutcomeIsKnown() {
        doThrow(new IllegalStateException("database unavailable"))
                .when((ChatExecutionControl) chatService).requestUserExecutionStop(1L);

        assertThrows(IllegalStateException.class,
                () -> authService.logout(1L, "Bearer valid-token"));

        verifyNoInteractions(tokenBlacklistService);
    }

    // --- userId 为 null ---

    @Test
    void logout_nullUserId_throwsUnauthorized() {
        assertThrows(UnauthorizedException.class, () -> authService.logout(null, "Bearer token"));
        verifyNoInteractions(tokenBlacklistService);
    }

    // --- 无 Authorization header ---

    @Test
    void logout_noAuthHeader_succeeds() {
        assertDoesNotThrow(() -> authService.logout(1L, null));
        verifyNoInteractions(tokenBlacklistService);
    }

    @Test
    void logout_nonBearerHeader_succeeds() {
        assertDoesNotThrow(() -> authService.logout(1L, "Basic abc123"));
        verifyNoInteractions(tokenBlacklistService);
    }

    // --- JwtException 降级 ---

    @Test
    void logout_expiredToken_doesNotThrow() {
        when(jwtUtil.getExpirationSeconds(anyString()))
                .thenThrow(new ExpiredJwtException(null, null, "expired"));

        assertDoesNotThrow(() -> authService.logout(1L, "Bearer expired-token"));
        verifyNoInteractions(tokenBlacklistService);
    }

    @Test
    void logout_malformedToken_doesNotThrow() {
        when(jwtUtil.getExpirationSeconds(anyString()))
                .thenThrow(new MalformedJwtException("bad token"));

        assertDoesNotThrow(() -> authService.logout(1L, "Bearer bad-token"));
        verifyNoInteractions(tokenBlacklistService);
    }

    // --- 非 JwtException 不被吞掉 ---

    @Test
    void logout_runtimeException_propagates() {
        when(jwtUtil.getExpirationSeconds(anyString()))
                .thenThrow(new RuntimeException("unexpected bug"));

        assertThrows(RuntimeException.class, () -> authService.logout(1L, "Bearer some-token"));
        verifyNoInteractions(tokenBlacklistService);
    }

    @Test
    void deleteAccount_cancelsAndDeletesFuzzDataWithUserOwnedData() {
        UserPo user = UserPo.builder()
                .id(7L)
                .phone("13900000000")
                .username("alice")
                .password("encoded")
                .build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass1234", "encoded")).thenReturn(true);
        when(fuzzTaskRepository.findIdsByUserIdAndStatus(7L, FuzzTaskPo.TaskStatus.PENDING))
                .thenReturn(java.util.List.of(21L));
        when(fuzzTaskRepository.findIdsByUserIdAndStatus(7L, FuzzTaskPo.TaskStatus.RUNNING))
                .thenReturn(java.util.List.of(22L));

        DeleteAccountRequestDto request = new DeleteAccountRequestDto();
        request.setPassword("Pass1234");
        request.setConfirmation("alice");

        authService.deleteAccount(7L, null, request);

        InOrder chatCleanupOrder = inOrder(chatSessionRepository, aiSessionStateRepository);
        chatCleanupOrder.verify(chatSessionRepository).findByUserIdForUpdate(7L);
        chatCleanupOrder.verify(aiSessionStateRepository).deleteUser(7L);
        InOrder fuzzCleanupOrder = inOrder(fuzzFindingRepository, fuzzTaskRepository);
        fuzzCleanupOrder.verify(fuzzFindingRepository).deleteByUserId(7L);
        fuzzCleanupOrder.verify(fuzzTaskRepository).deleteByUserId(7L);
        AsyncTaskExecutionControl fuzzControl = (AsyncTaskExecutionControl) fuzzService;
        verify(fuzzControl).requestLocalExecutionStop(21L);
        verify(fuzzControl).requestLocalExecutionStop(22L);
        verify((ChatExecutionControl) chatService).requestLocalUserExecutionStop(7L);
        verify(interactiveAiExecutionService).requestUserExecutionStop(7L);
        verify(interactiveFixExecutionService).requestUserExecutionStop(7L);
        verify(fuzzService, never()).cancelTask(anyLong(), anyLong());
        verify(userRepository).delete(user);
    }

    @Test
    void deleteAccount_defersIrreversibleSideEffectsUntilCommit() {
        UserPo user = UserPo.builder()
                .id(7L).phone("13900000000").username("alice").password("encoded").build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass1234", "encoded")).thenReturn(true);
        when(jwtUtil.getExpirationSeconds("token")).thenReturn(60L);
        when(fuzzTaskRepository.findIdsByUserIdAndStatus(7L, FuzzTaskPo.TaskStatus.PENDING))
                .thenReturn(java.util.List.of());
        when(fuzzTaskRepository.findIdsByUserIdAndStatus(7L, FuzzTaskPo.TaskStatus.RUNNING))
                .thenReturn(java.util.List.of(22L));
        DeleteAccountRequestDto request = new DeleteAccountRequestDto();
        request.setPassword("Pass1234");
        request.setConfirmation("alice");

        TransactionSynchronizationManager.initSynchronization();
        try {
            authService.deleteAccount(7L, "Bearer token", request);

            verifyNoInteractions(tokenBlacklistService);
            verify((AsyncTaskExecutionControl) fuzzService, never()).requestLocalExecutionStop(anyLong());
            verify((ChatExecutionControl) chatService, never()).requestLocalUserExecutionStop(anyLong());
            verify(interactiveAiExecutionService, never()).requestUserExecutionStop(anyLong());
            verify(interactiveFixExecutionService, never()).requestUserExecutionStop(anyLong());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);

            verify(tokenBlacklistService).blacklist("token", 60L);
            verify((AsyncTaskExecutionControl) fuzzService).requestLocalExecutionStop(22L);
            verify((ChatExecutionControl) chatService).requestLocalUserExecutionStop(7L);
            verify(interactiveAiExecutionService).requestUserExecutionStop(7L);
            verify(interactiveFixExecutionService).requestUserExecutionStop(7L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteAccount_databaseFailureDoesNotBlacklistOrStopTasks() {
        UserPo user = UserPo.builder()
                .id(7L).phone("13900000000").username("alice").password("encoded").build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass1234", "encoded")).thenReturn(true);
        when(fuzzTaskRepository.findIdsByUserIdAndStatus(7L, FuzzTaskPo.TaskStatus.PENDING))
                .thenReturn(java.util.List.of());
        when(fuzzTaskRepository.findIdsByUserIdAndStatus(7L, FuzzTaskPo.TaskStatus.RUNNING))
                .thenReturn(java.util.List.of(22L));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(fuzzFindingRepository).deleteByUserId(7L);
        DeleteAccountRequestDto request = new DeleteAccountRequestDto();
        request.setPassword("Pass1234");
        request.setConfirmation("alice");

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> authService.deleteAccount(7L, "Bearer token", request));

        verifyNoInteractions(tokenBlacklistService);
        verify((AsyncTaskExecutionControl) fuzzService, never()).requestLocalExecutionStop(anyLong());
        verify((ChatExecutionControl) chatService, never()).requestLocalUserExecutionStop(anyLong());
        verify(interactiveAiExecutionService, never()).requestUserExecutionStop(anyLong());
        verify(interactiveFixExecutionService, never()).requestUserExecutionStop(anyLong());
        verify(userRepository, never()).delete(any());
    }
}
