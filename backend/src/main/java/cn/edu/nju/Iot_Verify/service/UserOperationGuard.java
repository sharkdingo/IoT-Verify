package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.configure.OperationAdmissionConfig;
import cn.edu.nju.Iot_Verify.exception.UserOperationBusyException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Per-user admission guard, distributed through Redis with a process-local fail-open fallback. */
@Slf4j
@Component
public class UserOperationGuard {

    private static final Duration MIN_RENEWABLE_TTL = Duration.ofMinutes(2);
    private static final int HEARTBEATS_PER_TTL = 4;

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final OperationAdmissionConfig operationAdmissionConfig;
    private final ConcurrentMap<String, String> localSlots = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Lease> renewableLeases = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingRelease> pendingReleases = new ConcurrentHashMap<>();

    @Autowired
    public UserOperationGuard(
            StringRedisTemplate redisTemplate,
            OperationAdmissionConfig operationAdmissionConfig) {
        this.redisTemplate = redisTemplate;
        this.operationAdmissionConfig = operationAdmissionConfig;
    }

    UserOperationGuard(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new OperationAdmissionConfig());
    }

    Duration renewableLeaseTtl() {
        long heartbeatWindow = operationAdmissionConfig.getLeaseHeartbeatMs() * HEARTBEATS_PER_TTL;
        return Duration.ofMillis(Math.max(MIN_RENEWABLE_TTL.toMillis(), heartbeatWindow));
    }

    public Lease acquire(Long userId, Kind kind, int limit, Duration ttl) {
        if (userId == null) throw new IllegalArgumentException("Operation admission requires a user");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Operation admission TTL must be positive");
        }
        Duration maxRenewableTtl = renewableLeaseTtl();
        Duration effectiveTtl = ttl.compareTo(maxRenewableTtl) > 0 ? maxRenewableTtl : ttl;
        int slots = Math.max(1, limit);
        String token = UUID.randomUUID().toString();
        boolean redisAvailable = true;
        for (int slot = 0; slot < slots; slot++) {
            String key = "iot-verify:operation:" + kind.name().toLowerCase(Locale.ROOT) + ":" + userId + ":" + slot;
            if (localSlots.putIfAbsent(key, token) != null) continue;
            long confirmationStartedNanos = System.nanoTime();
            Boolean acquired;
            try {
                acquired = redisTemplate.opsForValue().setIfAbsent(key, token, effectiveTtl);
            } catch (RuntimeException e) {
                redisAvailable = false;
                log.warn("Redis operation admission is unavailable; using local guard for {}: {}", kind, e.toString());
                return new Lease(key, token, false, effectiveTtl, confirmationStartedNanos);
            }
            if (Boolean.TRUE.equals(acquired)) {
                Lease lease = new Lease(key, token, true, effectiveTtl, confirmationStartedNanos);
                renewableLeases.put(key, lease);
                if (!lease.isActive()) {
                    lease.close();
                    throw new ServiceUnavailableException(
                            "The operation admission response arrived after its lease TTL expired.");
                }
                return lease;
            }
            localSlots.remove(key, token);
        }
        String scope = redisAvailable ? "all backend instances" : "this backend instance";
        throw new UserOperationBusyException(
                kind.name(), scope, slots,
                "This user already has the maximum number of " + kind.label
                        + " operations running across " + scope + ".");
    }

    @Scheduled(fixedDelayString = "#{@operationAdmissionConfig.leaseHeartbeatMs}")
    public void renewActiveLeases() {
        renewableLeases.values().forEach(Lease::renew);
        retryPendingReleases();
    }

    private void retryPendingReleases() {
        long now = System.nanoTime();
        pendingReleases.forEach((key, pending) -> {
            if (now >= pending.retryUntilNanos()) {
                pendingReleases.remove(key, pending);
                return;
            }
            try {
                Long released = redisTemplate.execute(
                        RELEASE_SCRIPT, java.util.List.of(key), pending.token());
                if (released != null) {
                    pendingReleases.remove(key, pending);
                }
            } catch (RuntimeException e) {
                log.warn("Could not retry release of distributed operation lease {}: {}", key, e.toString());
            }
        });
    }

    private record PendingRelease(String token, long retryUntilNanos) { }

    public enum Kind {
        FORMAL("formal verification, simulation, or fix"),
        CHAT("assistant");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final String token;
        private final boolean redisBacked;
        private final long ttlMillis;
        private volatile boolean closed;
        private volatile boolean lost;
        private volatile long lastConfirmedNanos;
        private volatile Thread ownerThread = Thread.currentThread();

        private Lease(String key,
                      String token,
                      boolean redisBacked,
                      Duration ttl,
                      long lastConfirmedNanos) {
            this.key = key;
            this.token = token;
            this.redisBacked = redisBacked;
            this.ttlMillis = Math.max(1, ttl.toMillis());
            this.lastConfirmedNanos = lastConfirmedNanos;
        }

        private synchronized void renew() {
            if (closed || lost || !redisBacked) return;
            long confirmationStartedNanos = System.nanoTime();
            try {
                Long renewed = redisTemplate.execute(
                        RENEW_SCRIPT, java.util.List.of(key), token, Long.toString(ttlMillis));
                if (!Long.valueOf(1L).equals(renewed)) {
                    markLost("the distributed lease is no longer owned by this worker");
                } else {
                    lastConfirmedNanos = confirmationStartedNanos;
                    if (System.nanoTime() - lastConfirmedNanos
                            >= Duration.ofMillis(ttlMillis).toNanos()) {
                        markLost("the distributed lease renewal response arrived after its TTL expired");
                    }
                }
            } catch (RuntimeException e) {
                long elapsed = System.nanoTime() - lastConfirmedNanos;
                if (elapsed >= Duration.ofMillis(ttlMillis).toNanos()) {
                    markLost("the distributed lease could not be renewed before its TTL expired");
                } else {
                    // Redis is deliberately fail-open; a short outage must not stop an in-flight request.
                    log.warn("Could not renew distributed operation lease {}: {}", key, e.toString());
                }
            }
        }

        /** Rebind the lease to the worker thread that performs the actual operation (for SSE tasks). */
        public synchronized void attachCurrentThread() {
            requireActive();
            ownerThread = Thread.currentThread();
        }

        public boolean isActive() {
            if (closed || lost) return false;
            if (redisBacked
                    && System.nanoTime() - lastConfirmedNanos >= Duration.ofMillis(ttlMillis).toNanos()) {
                markLost("the distributed lease has not been confirmed within its TTL");
                return false;
            }
            return true;
        }

        public void requireActive() {
            if (!isActive()) {
                throw new ServiceUnavailableException(
                        "The operation admission lease was lost; the operation was stopped to prevent duplicate execution.");
            }
        }

        private synchronized void markLost(String reason) {
            if (closed || lost) return;
            lost = true;
            renewableLeases.remove(key, this);
            Thread thread = ownerThread;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
            log.warn("Stopping operation after losing distributed lease {}: {}", key, reason);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            renewableLeases.remove(key, this);
            localSlots.remove(key, token);
            if (!redisBacked) return;
            // Lettuce rejects commands on an interrupted worker; terminal cleanup must release
            // this owner's slot before restoring the cancellation signal.
            boolean interrupted = Thread.interrupted();
            try {
                Long released = redisTemplate.execute(RELEASE_SCRIPT, java.util.List.of(key), token);
                if (released == null) {
                    pendingReleases.put(key, new PendingRelease(
                            token, System.nanoTime() + Duration.ofMillis(ttlMillis).toNanos()));
                    log.warn("Distributed operation lease release returned no result for {}; scheduling a retry", key);
                }
            } catch (RuntimeException e) {
                pendingReleases.put(key, new PendingRelease(
                        token, System.nanoTime() + Duration.ofMillis(ttlMillis).toNanos()));
                log.warn("Could not release distributed operation lease {}: {}", key, e.toString());
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
