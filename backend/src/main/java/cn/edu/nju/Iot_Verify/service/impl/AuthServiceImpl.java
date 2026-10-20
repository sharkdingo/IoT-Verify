package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.auth.AuthResponseDto;
import cn.edu.nju.Iot_Verify.dto.auth.AccountProfileDto;
import cn.edu.nju.Iot_Verify.dto.auth.ChangePasswordRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.DeleteAccountRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.LoginRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.RegisterRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.UpdateUsernameRequestDto;
import cn.edu.nju.Iot_Verify.exception.AccountChangeException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.po.UserPo;
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
import cn.edu.nju.Iot_Verify.service.AuthService;
import cn.edu.nju.Iot_Verify.service.AsyncTaskExecutionControl;
import cn.edu.nju.Iot_Verify.service.ChatExecutionControl;
import cn.edu.nju.Iot_Verify.service.ChatService;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.service.InteractiveAiExecutionService;
import cn.edu.nju.Iot_Verify.service.InteractiveFixExecutionService;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.service.TokenBlacklistService;
import cn.edu.nju.Iot_Verify.service.UserService;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.JwtUtil;
import cn.edu.nju.Iot_Verify.util.UsernameNormalizer;
import cn.edu.nju.Iot_Verify.util.mapper.UserMapper;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserMapper userMapper;
    private final DeviceTemplateService deviceTemplateService;
    private final VerificationService verificationService;
    private final SimulationService simulationService;
    private final FuzzService fuzzService;
    private final ChatService chatService;
    private final InteractiveAiExecutionService interactiveAiExecutionService;
    private final InteractiveFixExecutionService interactiveFixExecutionService;
    private final UserRepository userRepository;
    private final BoardEnvironmentVariableRepository boardEnvironmentVariableRepository;
    private final BoardLayoutRepository boardLayoutRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final DeviceNodeRepository deviceNodeRepository;
    private final DeviceTemplateRepository deviceTemplateRepository;
    private final FuzzFindingRepository fuzzFindingRepository;
    private final FuzzTaskRepository fuzzTaskRepository;
    private final RuleRepository ruleRepository;
    private final SimulationTaskRepository simulationTaskRepository;
    private final SimulationTraceRepository simulationTraceRepository;
    private final SpecificationRepository specificationRepository;
    private final TraceRepository traceRepository;
    private final VerificationTaskRepository verificationTaskRepository;
    private final AiSessionStateRepository aiSessionStateRepository;
    private final BoardEditJournalRepository boardEditJournalRepository;

    @Override
    @Transactional
    public AuthResponseDto register(RegisterRequestDto request) {
        UserPo user = userService.register(
                request.getPhone(),
                request.getUsername(),
                request.getPassword()
        );

        // Registration is usable only when the initial type catalog is complete. Any bundled
        // template failure rolls back the user and templates in this transaction.
        int count = deviceTemplateService.initDefaultTemplates(user.getId());
        if (count <= 0) {
            throw new InternalServerException("Registration could not initialize the default device type catalog.");
        }
        log.info("Loaded {} default templates for new user {}", count, user.getId());

        String token = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getAuthVersion());
        return userMapper.toAuthResponseDto(user, token);
    }

    @Override
    public AuthResponseDto login(LoginRequestDto request) {
        String identifier = UsernameNormalizer.normalize(request.getIdentifier());
        UserPo user = resolveLoginUser(identifier, request.getPassword());

        String token = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getAuthVersion());
        return userMapper.toAuthResponseDto(user, token);
    }

    private UserPo resolveLoginUser(String identifier, String password) {
        java.util.Optional<UserPo> candidate = UsernameNormalizer.isPhoneNumber(identifier)
                ? userService.findByPhone(identifier)
                : userService.findByUsername(identifier);
        if (candidate.isPresent()
                && passwordEncoder.matches(password, candidate.get().getPassword())) {
            return candidate.get();
        }
        throw UnauthorizedException.invalidCredentials();
    }

    @Override
    @Transactional(readOnly = true)
    public AccountProfileDto getAccount(Long userId) {
        if (userId == null) throw UnauthorizedException.missingToken();
        return accountProfile(userRepository.findById(userId)
                .orElseThrow(UnauthorizedException::invalidToken));
    }

    @Override
    @Transactional
    public AccountProfileDto updateUsername(Long userId, UpdateUsernameRequestDto request) {
        UserPo user = lockAccount(userId, request.getCurrentPassword());
        if (!user.getUsername().equals(request.getExpectedUsername())) {
            throw AccountChangeException.staleProfile(accountProfile(user));
        }
        String username = UsernameNormalizer.normalizeAndValidate(request.getUsername());
        if (username.equals(user.getUsername())) return accountProfile(user);
        if (userRepository.existsByUsername(username)) throw AccountChangeException.duplicateUsername();

        user.setUsername(username);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException conflict) {
            // Only username changed on this locked row; a typed unique violation settles a concurrent claim.
            Throwable cause = conflict;
            while (cause != null) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                        && constraint.getKind()
                                == org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE) {
                    throw AccountChangeException.duplicateUsername();
                }
                cause = cause.getCause();
            }
            throw conflict;
        }
        return accountProfile(user);
    }

    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequestDto request) {
        UserPo user = lockAccount(userId, request.getCurrentPassword());
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw AccountChangeException.unchangedPassword();
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        // Credential authority is in the same row/transaction as the hash, independent of Redis.
        user.setAuthVersion(Math.incrementExact(user.getAuthVersion()));
        userRepository.saveAndFlush(user);
    }

    private UserPo lockAccount(Long userId, String currentPassword) {
        if (userId == null) throw UnauthorizedException.missingToken();
        UserPo user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(UnauthorizedException::invalidToken);
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw AccountChangeException.incorrectPassword();
        }
        return user;
    }

    private AccountProfileDto accountProfile(UserPo user) {
        return AccountProfileDto.builder()
                .userId(user.getId())
                .phone(user.getPhone())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Override
    public void logout(Long userId, String authHeader) {
        if (userId == null) {
            throw UnauthorizedException.missingToken();
        }

        if (chatService instanceof ChatExecutionControl control) {
            control.requestUserExecutionStop(userId);
        }
        blacklistCurrentToken(authHeader);
    }

    @Override
    @Transactional
    public void deleteAccount(Long userId, String authHeader, DeleteAccountRequestDto request) {
        if (userId == null) {
            throw UnauthorizedException.missingToken();
        }

        UserPo user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(UnauthorizedException::invalidToken);

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadRequestException("Current password is incorrect.");
        }

        String confirmation = UsernameNormalizer.normalize(request.getConfirmation());
        if (!confirmation.equals(user.getUsername()) && !confirmation.equals(user.getPhone())) {
            throw new BadRequestException("Account confirmation must match the username or phone number.");
        }

        ActiveTaskIds activeTaskIds = activeTaskIds(userId);
        deleteUserOwnedData(userId);
        userRepository.delete(user);
        afterCommit(() -> finishAccountDeletion(userId, authHeader, activeTaskIds));
        log.info("Deleted account and user-owned data for user {}", userId);
    }

    private void blacklistCurrentToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                long expirationSeconds = jwtUtil.getExpirationSeconds(token);
                tokenBlacklistService.blacklist(token, expirationSeconds);
            } catch (JwtException e) {
                // token 已过期或无效，无需加黑名单，登出仍视为成功
                log.warn("Logout skipped blacklist: token expired or invalid ({})", e.getClass().getSimpleName());
                log.debug("Skipped token blacklist because the token cannot be parsed for expiration", e);
            }
        }
    }

    private void deleteUserOwnedData(Long userId) {
        List<String> sessionIds = chatSessionRepository.findByUserIdForUpdate(userId).stream()
                .map(session -> session.getId())
                .filter(id -> id != null && !id.isBlank())
                .toList();
        aiSessionStateRepository.deleteUser(userId);
        if (!sessionIds.isEmpty()) {
            chatMessageRepository.deleteBySessionIdIn(sessionIds);
        }
        chatSessionRepository.deleteByUserId(userId);

        traceRepository.deleteByUserId(userId);
        verificationTaskRepository.deleteByUserId(userId);
        simulationTraceRepository.deleteByUserId(userId);
        simulationTaskRepository.deleteByUserId(userId);
        fuzzFindingRepository.deleteByUserId(userId);
        fuzzTaskRepository.deleteByUserId(userId);

        deviceNodeRepository.deleteByUserId(userId);
        boardEnvironmentVariableRepository.deleteByUserId(userId);
        ruleRepository.deleteByUserId(userId);
        specificationRepository.deleteByUserId(userId);
        boardLayoutRepository.deleteByUserId(userId);
        deviceTemplateRepository.deleteByUserId(userId);
        // The undo journal stores before/after snapshots of the account's board content, so
        // leaving it behind would retain deleted users' data.
        boardEditJournalRepository.deleteByUserId(userId);
    }

    private ActiveTaskIds activeTaskIds(Long userId) {
        List<Long> verificationIds = new java.util.ArrayList<>();
        verificationTaskRepository.findByUserIdAndStatus(
                        userId, cn.edu.nju.Iot_Verify.po.VerificationTaskPo.TaskStatus.PENDING)
                .forEach(task -> verificationIds.add(task.getId()));
        verificationTaskRepository.findByUserIdAndStatus(
                        userId, cn.edu.nju.Iot_Verify.po.VerificationTaskPo.TaskStatus.RUNNING)
                .forEach(task -> verificationIds.add(task.getId()));

        List<Long> simulationIds = new java.util.ArrayList<>();
        simulationTaskRepository.findByUserIdAndStatus(
                        userId, cn.edu.nju.Iot_Verify.po.SimulationTaskPo.TaskStatus.PENDING)
                .forEach(task -> simulationIds.add(task.getId()));
        simulationTaskRepository.findByUserIdAndStatus(
                        userId, cn.edu.nju.Iot_Verify.po.SimulationTaskPo.TaskStatus.RUNNING)
                .forEach(task -> simulationIds.add(task.getId()));

        List<Long> fuzzIds = new java.util.ArrayList<>();
        fuzzIds.addAll(fuzzTaskRepository.findIdsByUserIdAndStatus(
                userId, cn.edu.nju.Iot_Verify.po.FuzzTaskPo.TaskStatus.PENDING));
        fuzzIds.addAll(fuzzTaskRepository.findIdsByUserIdAndStatus(
                userId, cn.edu.nju.Iot_Verify.po.FuzzTaskPo.TaskStatus.RUNNING));
        return new ActiveTaskIds(List.copyOf(verificationIds), List.copyOf(simulationIds), List.copyOf(fuzzIds));
    }

    private void finishAccountDeletion(Long userId, String authHeader, ActiveTaskIds activeTaskIds) {
        try {
            blacklistCurrentToken(authHeader);
        } catch (RuntimeException e) {
            log.warn("Account was deleted but its token could not be blacklisted", e);
        }
        stopLocalExecutions(verificationService, activeTaskIds.verificationIds(), "verification");
        stopLocalExecutions(simulationService, activeTaskIds.simulationIds(), "simulation");
        stopLocalExecutions(fuzzService, activeTaskIds.fuzzIds(), "fuzz");
        if (chatService instanceof ChatExecutionControl control) {
            try {
                control.requestLocalUserExecutionStop(userId);
            } catch (RuntimeException e) {
                log.warn("Account deletion could not stop local chat work for user {}", userId, e);
            }
        }
        stopInteractiveExecution(interactiveAiExecutionService::requestUserExecutionStop,
                userId, "AI recommendation");
        stopInteractiveExecution(interactiveFixExecutionService::requestUserExecutionStop,
                userId, "automatic-fix");
    }

    private void stopInteractiveExecution(java.util.function.Consumer<Long> stop,
                                          Long userId, String operationType) {
        try {
            stop.accept(userId);
        } catch (RuntimeException e) {
            log.warn("Account deletion could not stop {} work for user {}", operationType, userId, e);
        }
    }

    private void stopLocalExecutions(Object service, List<Long> taskIds, String taskType) {
        if (!(service instanceof AsyncTaskExecutionControl control)) return;
        for (Long taskId : taskIds) {
            try {
                control.requestLocalExecutionStop(taskId);
            } catch (RuntimeException e) {
                log.warn("Account deletion could not stop local {} task {}", taskType, taskId, e);
            }
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private record ActiveTaskIds(
            List<Long> verificationIds,
            List<Long> simulationIds,
            List<Long> fuzzIds) {
    }
}
