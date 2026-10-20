package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.dto.auth.ChangePasswordRequestDto;
import cn.edu.nju.Iot_Verify.dto.auth.UpdateUsernameRequestDto;
import cn.edu.nju.Iot_Verify.exception.AccountChangeException;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountCredentialConcurrencyIntegrationTest {
    private static final String CURRENT_PASSWORD = "CurrentPassword2026";
    private static final long TIMEOUT_SECONDS = 10;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private final List<Long> createdUsers = new ArrayList<>();

    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void removeCommittedUsers() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                createdUsers.forEach(users::deleteById));
    }

    @Test
    void simultaneousPasswordChangesSerializeAndTheOldPasswordCannotWinTwice() throws Exception {
        Long userId = saveUser("password-lock-test", "13800000201");
        CountDownLatch winnerLocked = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        CountDownLatch loserAtLock = new CountDownLatch(1);
        UserRepository winnerUsers = delayedLock(userId, winnerLocked, releaseWinner);
        UserRepository loserUsers = mock(UserRepository.class, delegatesTo(users));
        doAnswer(invocation -> {
            loserAtLock.countDown();
            return users.findByIdForUpdate(userId);
        }).when(loserUsers).findByIdForUpdate(userId);
        AuthServiceImpl winner = service(winnerUsers);
        AuthServiceImpl loser = service(loserUsers);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> first = executor.submit(() -> winner.changePassword(userId, password("WinnerPassword2026")));
        try {
            await(winnerLocked);
            Future<?> second = executor.submit(() -> loser.changePassword(userId, password("LoserPassword2026")));
            await(loserAtLock);
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));

            releaseWinner.countDown();
            first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("CURRENT_PASSWORD_INCORRECT", ((AccountChangeException) rejected.getCause()).getReasonCode());

            UserPo saved = users.findById(userId).orElseThrow();
            assertEquals(1L, saved.getAuthVersion());
            assertTrue(passwords.matches("WinnerPassword2026", saved.getPassword()));
            assertFalse(passwords.matches(CURRENT_PASSWORD, saved.getPassword()));
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void simultaneousNamesOnOneAccountRejectTheSecondStaleForm() throws Exception {
        Long userId = saveUser("username-lock-test", "13800000202");
        CountDownLatch winnerLocked = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        AuthServiceImpl winner = service(delayedLock(userId, winnerLocked, releaseWinner));
        AuthServiceImpl loser = service(users);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> first = executor.submit(() -> winner.updateUsername(userId, username("winner-name", "username-lock-test")));
        try {
            await(winnerLocked);
            Future<?> second = executor.submit(() -> loser.updateUsername(userId, username("loser-name", "username-lock-test")));
            releaseWinner.countDown();
            first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            var conflict = (AccountChangeException) rejected.getCause();
            assertEquals("ACCOUNT_PROFILE_CHANGED", conflict.getReasonCode());
            assertEquals("winner-name", conflict.getCurrentProfile().getUsername());
            assertEquals("winner-name", users.findById(userId).orElseThrow().getUsername());
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentAccountsCannotBothClaimTheSameNameEvenWhenBothPrechecksPass() throws Exception {
        Long firstId = saveUser("first-name-test", "13800000203");
        Long secondId = saveUser("second-name-test", "13800000204");
        CountDownLatch bothChecked = new CountDownLatch(2);
        UserRepository racingUsers = mock(UserRepository.class, delegatesTo(users));
        doAnswer(invocation -> {
            boolean exists = users.existsByUsername("shared-name-test");
            bothChecked.countDown();
            await(bothChecked);
            return exists;
        }).when(racingUsers).existsByUsername("shared-name-test");
        AuthServiceImpl first = service(racingUsers);
        AuthServiceImpl second = service(racingUsers);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = executor.submit(() -> first.updateUsername(firstId, username("shared-name-test", "first-name-test")));
            Future<?> two = executor.submit(() -> second.updateUsername(secondId, username("shared-name-test", "second-name-test")));
            int successes = 0;
            int conflicts = 0;
            for (Future<?> update : List.of(one, two)) {
                try {
                    update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException rejected) {
                    var conflict = (AccountChangeException) rejected.getCause();
                    assertEquals("USERNAME_ALREADY_EXISTS", conflict.getReasonCode());
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
            List<String> names = List.of(users.findById(firstId).orElseThrow().getUsername(),
                    users.findById(secondId).orElseThrow().getUsername());
            assertEquals(1, names.stream().filter("shared-name-test"::equals).count());
            assertTrue(names.contains("first-name-test") || names.contains("second-name-test"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedPersistenceRollsBackTheHashAndVersionTogether() {
        Long userId = saveUser("password-rollback", "13800000205");
        UserRepository failingUsers = mock(UserRepository.class, delegatesTo(users));
        doAnswer(invocation -> {
            users.saveAndFlush(invocation.getArgument(0));
            throw new IllegalStateException("simulated failure after flush");
        }).when(failingUsers).saveAndFlush(any(UserPo.class));

        assertThrows(IllegalStateException.class,
                () -> service(failingUsers).changePassword(userId, password("NewPassword2026")));

        UserPo saved = users.findById(userId).orElseThrow();
        assertEquals(0L, saved.getAuthVersion());
        assertTrue(passwords.matches(CURRENT_PASSWORD, saved.getPassword()));
        assertFalse(passwords.matches("NewPassword2026", saved.getPassword()));
    }

    @Test
    void versionOverflowRejectsTheChangeAndPreservesTheCurrentCredentials() {
        Long userId = saveUser("password-overflow", "13800000206");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            UserPo user = users.findByIdForUpdate(userId).orElseThrow();
            user.setAuthVersion(Long.MAX_VALUE);
            users.saveAndFlush(user);
        });

        assertThrows(ArithmeticException.class,
                () -> service(users).changePassword(userId, password("NewPassword2026")));

        UserPo saved = users.findById(userId).orElseThrow();
        assertEquals(Long.MAX_VALUE, saved.getAuthVersion());
        assertTrue(passwords.matches(CURRENT_PASSWORD, saved.getPassword()));
    }

    private UserRepository delayedLock(Long userId, CountDownLatch locked, CountDownLatch release) {
        UserRepository repository = mock(UserRepository.class, delegatesTo(users));
        doAnswer(invocation -> {
            var user = users.findByIdForUpdate(userId);
            locked.countDown();
            await(release);
            return user;
        }).when(repository).findByIdForUpdate(userId);
        return repository;
    }

    private AuthServiceImpl service(UserRepository repository) {
        AuthServiceImpl target = new AuthServiceImpl(null, null, passwords, null, null, null,
                null, null, null, null, null, null, repository, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (AuthServiceImpl) proxy.getProxy();
    }

    private Long saveUser(String name, String phone) {
        Long userId = new TransactionTemplate(transactionManager).execute(status -> users.saveAndFlush(
                UserPo.builder().username(name).phone(phone).password(passwords.encode(CURRENT_PASSWORD)).build()).getId());
        createdUsers.add(userId);
        return userId;
    }

    private ChangePasswordRequestDto password(String newPassword) {
        var request = new ChangePasswordRequestDto();
        request.setCurrentPassword(CURRENT_PASSWORD);
        request.setNewPassword(newPassword);
        return request;
    }

    private UpdateUsernameRequestDto username(String username, String expected) {
        var request = new UpdateUsernameRequestDto();
        request.setUsername(username);
        request.setCurrentPassword(CURRENT_PASSWORD);
        request.setExpectedUsername(expected);
        return request;
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "test coordination timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test coordination interrupted", interrupted);
        }
    }
}
