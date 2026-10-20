package cn.edu.nju.Iot_Verify.security;

import cn.edu.nju.Iot_Verify.exception.AuthRateLimitException;
import cn.edu.nju.Iot_Verify.util.UsernameNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounds valid authentication attempts by account and by source without coupling users behind one NAT. */
@Component
public class AuthRateLimitGuard {

    static final int DEFAULT_MAX_WINDOWS = 65_536;
    private static final long PERIODIC_CLEANUP_MASK = 1023L;
    private static final long CLEANUP_COOLDOWN_MS = 1_000L;

    private final int loginAccountLimit;
    private final int registerPhoneLimit;
    private final int loginSourceLimit;
    private final int registerSourceLimit;
    private final int maxWindows;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger windowCount = new AtomicInteger();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong nextCleanupAt = new AtomicLong();
    private final AtomicBoolean cleanupInProgress = new AtomicBoolean();
    private long cachedCapacityExpiry;

    public AuthRateLimitGuard(
            @Value("${security.auth-rate-limit.login-per-minute:10}") int loginAccountLimit,
            @Value("${security.auth-rate-limit.register-per-hour:5}") int registerPhoneLimit,
            @Value("${security.auth-rate-limit.source-login-per-minute:120}") int loginSourceLimit,
            @Value("${security.auth-rate-limit.source-register-per-hour:60}") int registerSourceLimit) {
        this(loginAccountLimit, registerPhoneLimit, loginSourceLimit, registerSourceLimit,
                DEFAULT_MAX_WINDOWS);
    }

    @Autowired
    public AuthRateLimitGuard(
            @Value("${security.auth-rate-limit.login-per-minute:10}") int loginAccountLimit,
            @Value("${security.auth-rate-limit.register-per-hour:5}") int registerPhoneLimit,
            @Value("${security.auth-rate-limit.source-login-per-minute:120}") int loginSourceLimit,
            @Value("${security.auth-rate-limit.source-register-per-hour:60}") int registerSourceLimit,
            @Value("${security.auth-rate-limit.max-windows:65536}") int maxWindows) {
        this.loginAccountLimit = Math.max(1, loginAccountLimit);
        this.registerPhoneLimit = Math.max(1, registerPhoneLimit);
        this.loginSourceLimit = Math.max(this.loginAccountLimit, loginSourceLimit);
        this.registerSourceLimit = Math.max(this.registerPhoneLimit, registerSourceLimit);
        this.maxWindows = Math.max(2, maxWindows);
    }

    public void checkLogin(String identifier, HttpServletRequest request) {
        check("login", normalizedIdentity(identifier), sourceAddress(request),
                loginAccountLimit, loginSourceLimit, Duration.ofMinutes(1));
    }

    public void checkRegistration(String phone, HttpServletRequest request) {
        check("register", normalizedIdentity(phone), sourceAddress(request),
                registerPhoneLimit, registerSourceLimit, Duration.ofHours(1));
    }

    public void checkAccountChange(Long userId, HttpServletRequest request) {
        check("account_change", String.valueOf(userId), sourceAddress(request),
                loginAccountLimit, loginSourceLimit, Duration.ofMinutes(1));
    }

    private void check(String operation,
                       String identity,
                       String source,
                       int identityLimit,
                       int sourceLimit,
                       Duration duration) {
        long now = System.currentTimeMillis();
        long windowMs = duration.toMillis();
        if ((requests.incrementAndGet() & PERIODIC_CLEANUP_MASK) == 0L) {
            cleanupExpiredIfDue(now);
        }
        Decision sourceDecision = consume(
                operation + ":source:" + source, sourceLimit, now, windowMs);
        if (!sourceDecision.allowed()) {
            throw new AuthRateLimitException(
                    operation, sourceDecision.capacityBlocked() ? "CAPACITY" : "SOURCE",
                    sourceDecision.retryAfterSeconds());
        }
        Decision identityDecision = consume(
                operation + ":identity:" + digest(identity), identityLimit, now, windowMs);
        if (!identityDecision.allowed()) {
            throw new AuthRateLimitException(
                    operation, identityDecision.capacityBlocked() ? "CAPACITY" : "ACCOUNT",
                    identityDecision.retryAfterSeconds());
        }
    }

    private Decision consume(String key, int limit, long now, long windowMs) {
        Window window = updateWindow(key, now, windowMs);
        if (window == null) {
            cleanupExpiredIfDue(now);
            window = updateWindow(key, now, windowMs);
        }
        if (window == null) {
            return new Decision(false, capacityRetryAfterSeconds(now, windowMs), true);
        }
        long retryAfter = Math.max(1, (window.expiresAt() - now + 999) / 1000);
        return new Decision(window.count() <= limit, retryAfter, false);
    }

    private synchronized long capacityRetryAfterSeconds(long now, long fallbackWindowMs) {
        if (cachedCapacityExpiry <= now) {
            cachedCapacityExpiry = windows.values().stream()
                    .mapToLong(Window::expiresAt)
                    .filter(expiresAt -> expiresAt > now)
                    .min()
                    .orElse(now + Math.min(fallbackWindowMs, CLEANUP_COOLDOWN_MS));
        }
        return Math.max(1, (cachedCapacityExpiry - now + 999) / 1000);
    }

    private Window updateWindow(String key, long now, long windowMs) {
        return windows.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt() <= now) {
                if (current == null && !reserveWindowSlot()) {
                    return null;
                }
                return new Window(now + windowMs, 1);
            }
            int nextCount = current.count() == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : current.count() + 1;
            return new Window(current.expiresAt(), nextCount);
        });
    }

    private boolean reserveWindowSlot() {
        int current = windowCount.get();
        while (current < maxWindows) {
            if (windowCount.compareAndSet(current, current + 1)) {
                return true;
            }
            current = windowCount.get();
        }
        return false;
    }

    private void cleanupExpiredIfDue(long now) {
        long nextCleanup = nextCleanupAt.get();
        if (now < nextCleanup
                || !nextCleanupAt.compareAndSet(
                        nextCleanup, now + CLEANUP_COOLDOWN_MS)) {
            return;
        }
        cleanupExpired(now);
    }

    private void cleanupExpired(long now) {
        if (!cleanupInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            windows.forEach((key, window) -> {
                if (window.expiresAt() <= now && windows.remove(key, window)) {
                    windowCount.decrementAndGet();
                }
            });
        } finally {
            cleanupInProgress.set(false);
        }
    }

    int trackedWindowCount() {
        return windows.size();
    }

    private String normalizedIdentity(String value) {
        return UsernameNormalizer.normalize(value);
    }

    private String sourceAddress(HttpServletRequest request) {
        String remoteAddress = request == null ? "unknown" : request.getRemoteAddr();
        if (request == null || !("127.0.0.1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress) || "::1".equals(remoteAddress))) {
            return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
        }
        String proxyAddress = request.getHeader("X-Real-IP");
        if (proxyAddress == null) return remoteAddress;
        String normalized = proxyAddress.trim();
        return normalized.isEmpty() || normalized.length() > 128 ? remoteAddress : normalized;
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record Window(long expiresAt, int count) {
    }

    private record Decision(boolean allowed, long retryAfterSeconds, boolean capacityBlocked) {
    }
}
