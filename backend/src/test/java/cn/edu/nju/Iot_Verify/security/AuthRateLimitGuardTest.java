package cn.edu.nju.Iot_Verify.security;

import cn.edu.nju.Iot_Verify.exception.AuthRateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthRateLimitGuardTest {

    @Test
    void accountChangesShareAUserBucketAcrossOperationsAndNamesButDoNotConsumeLoginAttempts() {
        AuthRateLimitGuard guard = new AuthRateLimitGuard(1, 1, 10, 10);
        MockHttpServletRequest request = request("203.0.113.10");

        guard.checkAccountChange(7L, request);
        AuthRateLimitException error = assertThrows(AuthRateLimitException.class,
                () -> guard.checkAccountChange(7L, request));

        assertEquals("AUTH_ACCOUNT_CHANGE_RATE_LIMIT_REACHED", error.getReasonCode());
        assertEquals("ACCOUNT", error.getScope());
        assertDoesNotThrow(() -> guard.checkAccountChange(8L, request));
        assertDoesNotThrow(() -> guard.checkLogin("Alice", request));
    }

    @Test
    void usersBehindOneSourceDoNotShareTheLowAccountLimit() {
        AuthRateLimitGuard guard = new AuthRateLimitGuard(1, 1, 10, 10);
        MockHttpServletRequest request = request("203.0.113.10");

        assertDoesNotThrow(() -> guard.checkLogin("alice", request));
        assertDoesNotThrow(() -> guard.checkLogin("bob", request));
        AuthRateLimitException error = assertThrows(
                AuthRateLimitException.class, () -> guard.checkLogin("alice", request));

        assertEquals("ACCOUNT", error.getScope());
        assertEquals("AUTH_LOGIN_RATE_LIMIT_REACHED", error.getReasonCode());
    }

    @Test
    void caseSensitiveUsernamesDoNotShareAnAccountBucket() {
        AuthRateLimitGuard guard = new AuthRateLimitGuard(1, 1, 10, 10);
        MockHttpServletRequest request = request("203.0.113.10");

        assertDoesNotThrow(() -> guard.checkLogin("Alice", request));
        assertDoesNotThrow(() -> guard.checkLogin("alice", request));
        assertThrows(AuthRateLimitException.class, () -> guard.checkLogin("Alice", request));
    }

    @Test
    void highSourceLimitStillBoundsRotatingIdentifiers() {
        AuthRateLimitGuard guard = new AuthRateLimitGuard(1, 1, 2, 2);
        MockHttpServletRequest request = request("203.0.113.10");

        guard.checkLogin("alice", request);
        guard.checkLogin("bob", request);
        AuthRateLimitException error = assertThrows(
                AuthRateLimitException.class, () -> guard.checkLogin("carol", request));

        assertEquals("SOURCE", error.getScope());
        org.junit.jupiter.api.Assertions.assertTrue(error.getRetryAfterSeconds() >= 1);
    }

    @Test
    void sourceRejectionDoesNotConsumeTheRotatedIdentityLimit() {
        AuthRateLimitGuard guard = new AuthRateLimitGuard(1, 1, 1, 1);

        guard.checkLogin("alice", request("203.0.113.10"));
        AuthRateLimitException error = assertThrows(
                AuthRateLimitException.class,
                () -> guard.checkLogin("bob", request("203.0.113.10")));

        assertEquals("SOURCE", error.getScope());
        assertDoesNotThrow(() -> guard.checkLogin("bob", request("203.0.113.11")));
    }

    @Test
    void loopbackProxyUsesOverwrittenRealIp() {
        AuthRateLimitGuard guard = new AuthRateLimitGuard(10, 10, 1, 10);
        MockHttpServletRequest first = request("127.0.0.1");
        first.addHeader("X-Real-IP", "198.51.100.10");
        MockHttpServletRequest second = request("127.0.0.1");
        second.addHeader("X-Real-IP", "198.51.100.11");

        assertDoesNotThrow(() -> guard.checkLogin("alice", first));
        assertDoesNotThrow(() -> guard.checkLogin("alice", second));
    }

    @Test
    void capacityPressureFailsClosedWithoutEvictingActiveBuckets() {
        AuthRateLimitGuard guard = new AuthRateLimitGuard(1, 1, 100, 100, 6);

        guard.checkLogin("alice", request("203.0.113.10"));
        guard.checkLogin("bob", request("203.0.113.11"));
        guard.checkLogin("carol", request("203.0.113.12"));

        AuthRateLimitException capacityError = assertThrows(
                AuthRateLimitException.class,
                () -> guard.checkLogin("dave", request("203.0.113.13")));
        assertEquals("CAPACITY", capacityError.getScope());
        org.junit.jupiter.api.Assertions.assertTrue(capacityError.getRetryAfterSeconds() <= 60);
        assertEquals(6, guard.trackedWindowCount());

        AuthRateLimitException existingAccountError = assertThrows(
                AuthRateLimitException.class,
                () -> guard.checkLogin("alice", request("203.0.113.10")));
        assertEquals("ACCOUNT", existingAccountError.getScope());
        assertEquals(6, guard.trackedWindowCount());
    }

    @Test
    void concurrentHighCardinalityTrafficNeverExceedsConfiguredCapacity() throws Exception {
        int capacity = 32;
        AuthRateLimitGuard guard = new AuthRateLimitGuard(100, 100, 100, 100, capacity);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 128; i++) {
                int requestId = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        guard.checkLogin("user-" + requestId, request("source-" + requestId));
                    } catch (AuthRateLimitException expectedAtCapacity) {
                        // Capacity exhaustion is intentionally fail-closed.
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(capacity, guard.trackedWindowCount());
    }

    private MockHttpServletRequest request(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }
}
