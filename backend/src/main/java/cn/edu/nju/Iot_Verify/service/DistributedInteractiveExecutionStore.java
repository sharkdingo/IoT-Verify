package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStage;
import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStatusDto;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Redis-backed ownership, status, and stop propagation for interactive HTTP operations. */
@Slf4j
@Component
public class DistributedInteractiveExecutionStore {

    private static final Duration ACTIVE_TTL = Duration.ofSeconds(30);
    private static final Duration COMPLETED_TTL = Duration.ofSeconds(15);
    private static final Duration USER_CANCELLATION_TTL = Duration.ofMinutes(10);
    private static final String AVAILABILITY_PROBE_KEY = "iot-verify:interactive:availability";
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return -1 end; "
                    + "if ARGV[3] == '1' and redis.call('exists', KEYS[2]) == 1 then return -2 end; "
                    + "redis.call('psetex', KEYS[1], ARGV[2], ARGV[1]); "
                    + "if ARGV[3] == '1' then redis.call('psetex', KEYS[2], ARGV[2], ARGV[1]) end; "
                    + "redis.call('hset', KEYS[3], 'state', 'WAITING'); "
                    + "redis.call('hset', KEYS[3], 'stage', 'QUEUED'); "
                    + "redis.call('hset', KEYS[3], 'startedAt', ARGV[4]); "
                    + "redis.call('hset', KEYS[3], 'token', ARGV[1]); "
                    + "redis.call('pexpire', KEYS[3], ARGV[2]); return 1",
            Long.class);
    private static final DefaultRedisScript<Long> STATUS_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "local stage = ARGV[3]; "
                    + "if redis.call('get', KEYS[3]) == ARGV[1] then stage = 'CANCELLING' end; "
                    + "redis.call('hset', KEYS[2], 'state', ARGV[2]); "
                    + "redis.call('hset', KEYS[2], 'stage', stage); "
                    + "redis.call('hset', KEYS[2], 'startedAt', ARGV[4]); "
                    + "redis.call('hset', KEYS[2], 'token', ARGV[1]); "
                    + "redis.call('pexpire', KEYS[2], ARGV[5]); return 1",
            Long.class);
    private static final DefaultRedisScript<Long> CANCEL_SCRIPT = new DefaultRedisScript<>(
            "local token = redis.call('get', KEYS[1]); "
                    + "if not token then return 0 end; "
                    + "redis.call('psetex', KEYS[2], ARGV[1], token); "
                    + "redis.call('hset', KEYS[3], 'stage', ARGV[2]); "
                    + "redis.call('pexpire', KEYS[3], ARGV[1]); return 1",
            Long.class);
    private static final DefaultRedisScript<Long> POLL_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return -1 end; "
                    + "if ARGV[3] == '1' and redis.call('get', KEYS[2]) ~= ARGV[1] then return -1 end; "
                    + "redis.call('pexpire', KEYS[1], ARGV[2]); "
                    + "if ARGV[3] == '1' then redis.call('pexpire', KEYS[2], ARGV[2]) end; "
                    + "redis.call('pexpire', KEYS[3], ARGV[2]); "
                    + "if redis.call('get', KEYS[4]) == ARGV[1] or redis.call('exists', KEYS[5]) == 1 "
                    + "then return 1 else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> COMPLETE_SUCCESS_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return -1 end; "
                    + "if ARGV[5] == '1' and redis.call('get', KEYS[2]) ~= ARGV[1] then return -1 end; "
                    + "if redis.call('get', KEYS[4]) == ARGV[1] or redis.call('exists', KEYS[5]) == 1 "
                    + "then return 0 end; "
                    + "redis.call('hset', KEYS[3], 'state', 'FINISHED'); "
                    + "redis.call('hset', KEYS[3], 'stage', ARGV[2]); "
                    + "redis.call('hset', KEYS[3], 'startedAt', ARGV[3]); "
                    + "redis.call('hset', KEYS[3], 'token', ARGV[1]); "
                    + "redis.call('pexpire', KEYS[3], ARGV[4]); "
                    + "if redis.call('get', KEYS[4]) == ARGV[1] then redis.call('del', KEYS[4]) end; "
                    + "if ARGV[5] == '1' and redis.call('get', KEYS[2]) == ARGV[1] then redis.call('del', KEYS[2]) end; "
                    + "redis.call('del', KEYS[1]); return 1",
            Long.class);
    private static final DefaultRedisScript<Long> FINISH_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "redis.call('hset', KEYS[3], 'state', 'FINISHED'); "
                    + "redis.call('hset', KEYS[3], 'stage', ARGV[2]); "
                    + "redis.call('hset', KEYS[3], 'startedAt', ARGV[3]); "
                    + "redis.call('hset', KEYS[3], 'token', ARGV[1]); "
                    + "redis.call('pexpire', KEYS[3], ARGV[4]); "
                    + "if redis.call('get', KEYS[4]) == ARGV[1] then redis.call('del', KEYS[4]) end; "
                    + "if ARGV[5] == '1' and redis.call('get', KEYS[2]) == ARGV[1] then redis.call('del', KEYS[2]) end; "
                    + "redis.call('del', KEYS[1]); return 1",
            Long.class);
    private static final DefaultRedisScript<Long> ABANDON_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "redis.call('del', KEYS[3]); "
                    + "if redis.call('get', KEYS[4]) == ARGV[1] then redis.call('del', KEYS[4]) end; "
                    + "if ARGV[2] == '1' and redis.call('get', KEYS[2]) == ARGV[1] then redis.call('del', KEYS[2]) end; "
                    + "redis.call('del', KEYS[1]); return 1",
            Long.class);
    private static final DefaultRedisScript<String> STATUS_READ_SCRIPT = new DefaultRedisScript<>(
            "local state = redis.call('hget', KEYS[1], 'state'); "
                    + "if not state then return nil end; "
                    + "local stage = redis.call('hget', KEYS[1], 'stage'); "
                    + "local startedAt = redis.call('hget', KEYS[1], 'startedAt'); "
                    + "local statusToken = redis.call('hget', KEYS[1], 'token'); "
                    + "if not stage or not startedAt or not statusToken then return nil end; "
                    + "local ownerToken = redis.call('get', KEYS[2]); "
                    + "if state == 'FINISHED' and ownerToken and ownerToken ~= statusToken then return nil end; "
                    + "if state ~= 'FINISHED' and (not ownerToken or ownerToken ~= statusToken) then return nil end; "
                    + "return state .. '\\n' .. stage .. '\\n' .. startedAt",
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final LongSupplier monotonicNanos;

    @Autowired
    public DistributedInteractiveExecutionStore(StringRedisTemplate redisTemplate) {
        this(redisTemplate, System::nanoTime);
    }

    DistributedInteractiveExecutionStore(
            StringRedisTemplate redisTemplate,
            LongSupplier monotonicNanos) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    public Lease acquire(String kind, Long userId, String requestId, boolean exclusivePerUser) {
        Lease lease = new Lease(kind, userId, requestId, UUID.randomUUID().toString(),
                exclusivePerUser, System.currentTimeMillis(), monotonicNanos.getAsLong());
        if (!redisAvailableBeforeAcquire(kind)) {
            return lease;
        }

        long confirmationStartedNanos = monotonicNanos.getAsLong();
        Long acquired;
        try {
            acquired = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    List.of(lease.ownerKey(), lease.userKey(), lease.statusKey()),
                    lease.token,
                    Long.toString(ACTIVE_TTL.toMillis()),
                    lease.exclusivePerUser ? "1" : "0",
                    Long.toString(lease.startedAtMillis));
        } catch (RuntimeException e) {
            abandonUncertainAcquisition(lease);
            throw new ServiceUnavailableException(
                    "Distributed interactive execution ownership could not be confirmed. Try again.", e);
        }

        if (Long.valueOf(-1L).equals(acquired)) {
            throw new BusyException(BusyScope.REQUEST);
        }
        if (Long.valueOf(-2L).equals(acquired)) {
            throw new BusyException(BusyScope.USER);
        }
        if (!Long.valueOf(1L).equals(acquired)) {
            abandonUncertainAcquisition(lease);
            throw new ServiceUnavailableException(
                    "Distributed interactive execution ownership could not be confirmed. Try again.");
        }

        lease.lastConfirmedAtNanos = confirmationStartedNanos;
        if (confirmationExpired(confirmationStartedNanos)) {
            abandonUncertainAcquisition(lease);
            throw new ServiceUnavailableException(
                    "Distributed interactive execution ownership expired before acquisition was confirmed. Try again.");
        }
        lease.redisBacked = true;
        return lease;
    }

    private boolean redisAvailableBeforeAcquire(String kind) {
        try {
            Boolean probe = redisTemplate.hasKey(AVAILABILITY_PROBE_KEY);
            if (probe != null) return true;
            log.warn("Redis interactive execution registry returned no availability result; "
                    + "using local tracking for {}", kind);
        } catch (RuntimeException e) {
            log.warn("Redis interactive execution registry is unavailable before ownership acquisition; "
                    + "using local tracking for {}: {}", kind, e.toString());
        }
        return false;
    }

    private void abandonUncertainAcquisition(Lease lease) {
        try {
            redisTemplate.execute(
                    ABANDON_SCRIPT,
                    List.of(lease.ownerKey(), lease.userKey(), lease.statusKey(), lease.cancelKey()),
                    lease.token,
                    lease.exclusivePerUser ? "1" : "0");
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not clean up uncertain distributed interactive acquisition {}: {}",
                    lease.ownerKey(), cleanupFailure.toString());
        }
    }

    public void update(Lease lease, String state, InteractiveOperationStage stage) {
        if (lease == null || !lease.redisBacked) return;
        try {
            writeStatus(lease, state, stage, ACTIVE_TTL);
        } catch (RuntimeException e) {
            log.warn("Could not update distributed interactive status {}: {}", lease.statusKey(), e.toString());
        }
    }

    public Optional<InteractiveOperationStatusDto> getStatus(String kind, Long userId, String requestId) {
        String statusKey = statusKey(kind, userId, requestId);
        try {
            String encoded = redisTemplate.execute(
                    STATUS_READ_SCRIPT,
                    List.of(statusKey, ownerKey(kind, userId, requestId)));
            if (encoded == null || encoded.isBlank()) return Optional.empty();
            String[] fields = encoded.split("\\n", -1);
            if (fields.length != 3) return Optional.empty();
            String state = fields[0];
            String stage = fields[1];
            long startedAt = parseLong(fields[2], System.currentTimeMillis());
            return Optional.of(InteractiveOperationStatusDto.builder()
                    .requestId(requestId)
                    .state(state)
                    .stage(InteractiveOperationStage.valueOf(stage))
                    .elapsedMs(Math.max(0, System.currentTimeMillis() - startedAt))
                    .build());
        } catch (RuntimeException e) {
            log.warn("Could not read distributed interactive status {}: {}", statusKey, e.toString());
            return Optional.empty();
        }
    }

    public boolean requestCancellation(String kind, Long userId, String requestId) {
        String ownerKey = ownerKey(kind, userId, requestId);
        try {
            Long published = redisTemplate.execute(
                    CANCEL_SCRIPT,
                    List.of(ownerKey, cancelKey(kind, userId, requestId),
                            statusKey(kind, userId, requestId)),
                    Long.toString(ACTIVE_TTL.toMillis()),
                    InteractiveOperationStage.CANCELLING.name());
            return Long.valueOf(1L).equals(published);
        } catch (RuntimeException e) {
            log.warn("Could not publish distributed interactive cancellation for {}/{}: {}",
                    userId, requestId, e.toString());
            return false;
        }
    }

    /** Account deletion uses this fence to stop every interactive request on every instance. */
    public boolean requestUserCancellation(String kind, Long userId) {
        try {
            redisTemplate.opsForValue().set(
                    userCancellationKey(kind, userId),
                    UUID.randomUUID().toString(),
                    USER_CANCELLATION_TTL);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not publish distributed interactive user cancellation for {}/{}: {}",
                    kind, userId, e.toString());
            return false;
        }
    }

    /** Returns true when cancellation was requested or this worker no longer owns its lease. */
    public boolean shouldStop(Lease lease) {
        if (lease == null || !lease.redisBacked) return false;
        long confirmationStartedNanos = monotonicNanos.getAsLong();
        try {
            Long result = redisTemplate.execute(
                    POLL_SCRIPT,
                    List.of(lease.ownerKey(), lease.userKey(), lease.statusKey(), lease.cancelKey(),
                            userCancellationKey(lease.kind, lease.userId)),
                    lease.token,
                    Long.toString(ACTIVE_TTL.toMillis()),
                    lease.exclusivePerUser ? "1" : "0");
            if (result == null || result < 0) return true;
            lease.lastConfirmedAtNanos = confirmationStartedNanos;
            if (confirmationExpired(confirmationStartedNanos)) return true;
            return result == 1L;
        } catch (RuntimeException e) {
            if (confirmationExpired(lease.lastConfirmedAtNanos)) {
                return true;
            }
            log.warn("Could not poll distributed interactive execution {}: {}", lease.ownerKey(), e.toString());
            return false;
        }
    }

    private boolean confirmationExpired(long confirmedAtNanos) {
        return monotonicNanos.getAsLong() - confirmedAtNanos >= ACTIVE_TTL.toNanos();
    }

    /**
     * Atomically establishes the point at which a successful result may be delivered.
     * A stop ordered before this script wins; a later stop observes that ownership is gone.
     */
    public void completeSuccessfully(Lease lease, InteractiveOperationStage stage) {
        if (lease == null || !lease.redisBacked) return;
        Long completed;
        try {
            completed = redisTemplate.execute(
                    COMPLETE_SUCCESS_SCRIPT,
                    List.of(lease.ownerKey(), lease.userKey(), lease.statusKey(), lease.cancelKey(),
                            userCancellationKey(lease.kind, lease.userId)),
                    lease.token,
                    stage.name(),
                    Long.toString(lease.startedAtMillis),
                    Long.toString(COMPLETED_TTL.toMillis()),
                    lease.exclusivePerUser ? "1" : "0");
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException(
                    "Distributed interactive execution completion could not be confirmed. Try again.", e);
        }
        if (!Long.valueOf(1L).equals(completed)) {
            throw new ServiceUnavailableException(
                    "Distributed interactive execution ownership or stop state changed before completion. Try again.");
        }
    }

    public void finish(Lease lease, InteractiveOperationStage stage) {
        if (lease == null || !lease.redisBacked) return;
        // Keep cancellation set for the caller, but let Lettuce publish the owner's terminal state.
        boolean interrupted = Thread.interrupted();
        try {
            redisTemplate.execute(
                    FINISH_SCRIPT,
                    List.of(lease.ownerKey(), lease.userKey(), lease.statusKey(), lease.cancelKey()),
                    lease.token,
                    stage.name(),
                    Long.toString(lease.startedAtMillis),
                    Long.toString(COMPLETED_TTL.toMillis()),
                    lease.exclusivePerUser ? "1" : "0");
        } catch (RuntimeException e) {
            log.warn("Could not publish final interactive status {}: {}", lease.statusKey(), e.toString());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public void abandon(Lease lease) {
        if (lease == null || !lease.redisBacked) return;
        try {
            redisTemplate.execute(
                    ABANDON_SCRIPT,
                    List.of(lease.ownerKey(), lease.userKey(), lease.statusKey(), lease.cancelKey()),
                    lease.token,
                    lease.exclusivePerUser ? "1" : "0");
        } catch (RuntimeException e) {
            log.warn("Could not abandon interactive execution {}: {}", lease.ownerKey(), e.toString());
        }
    }

    private void writeStatus(Lease lease, String state, InteractiveOperationStage stage, Duration ttl) {
        Long updated = redisTemplate.execute(
                STATUS_SCRIPT,
                List.of(lease.ownerKey(), lease.statusKey(), lease.cancelKey()),
                lease.token,
                state,
                stage.name(),
                Long.toString(lease.startedAtMillis),
                Long.toString(ttl.toMillis()));
        if (!Long.valueOf(1L).equals(updated)) {
            throw new IllegalStateException("Interactive execution lease is no longer owned");
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String prefix(String kind, Long userId, String requestId) {
        return "iot-verify:interactive:" + kind + ":" + userId + ":" + requestId;
    }

    private static String ownerKey(String kind, Long userId, String requestId) {
        return prefix(kind, userId, requestId) + ":owner";
    }

    private static String statusKey(String kind, Long userId, String requestId) {
        return prefix(kind, userId, requestId) + ":status";
    }

    private static String cancelKey(String kind, Long userId, String requestId) {
        return prefix(kind, userId, requestId) + ":cancel";
    }

    private static String userKey(String kind, Long userId) {
        return "iot-verify:interactive:" + kind + ":" + userId + ":active";
    }

    private static String userCancellationKey(String kind, Long userId) {
        return "iot-verify:interactive:" + kind + ":" + userId + ":cancel-all";
    }

    public enum BusyScope {
        REQUEST,
        USER
    }

    public static final class BusyException extends RuntimeException {
        private final BusyScope scope;

        private BusyException(BusyScope scope) {
            this.scope = scope;
        }

        public BusyScope getScope() {
            return scope;
        }
    }

    public static final class Lease {
        private final String kind;
        private final Long userId;
        private final String requestId;
        private final String token;
        private final boolean exclusivePerUser;
        private final long startedAtMillis;
        private volatile boolean redisBacked;
        private volatile long lastConfirmedAtNanos;

        private Lease(String kind, Long userId, String requestId, String token,
                      boolean exclusivePerUser, long startedAtMillis,
                      long lastConfirmedAtNanos) {
            this.kind = kind;
            this.userId = userId;
            this.requestId = requestId;
            this.token = token;
            this.exclusivePerUser = exclusivePerUser;
            this.startedAtMillis = startedAtMillis;
            this.lastConfirmedAtNanos = lastConfirmedAtNanos;
        }

        private String ownerKey() { return DistributedInteractiveExecutionStore.ownerKey(kind, userId, requestId); }
        private String statusKey() { return DistributedInteractiveExecutionStore.statusKey(kind, userId, requestId); }
        private String cancelKey() { return DistributedInteractiveExecutionStore.cancelKey(kind, userId, requestId); }
        private String userKey() { return DistributedInteractiveExecutionStore.userKey(kind, userId); }

        boolean isRedisBacked() { return redisBacked; }
    }
}
