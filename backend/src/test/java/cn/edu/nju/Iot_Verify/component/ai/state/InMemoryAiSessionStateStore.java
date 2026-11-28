package cn.edu.nju.Iot_Verify.component.ai.state;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded fallback used by isolated unit tests that construct AI stores directly. */
public final class InMemoryAiSessionStateStore implements AiSessionStateStore {

    static final int MAX_ENTRIES = 1024;

    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public Optional<Snapshot> get(Long userId, String sessionId, Kind kind, Instant now) {
        Key key = key(userId, sessionId, kind);
        Entry entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (!entry.expiresAt().isAfter(now)) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(new Snapshot(entry.payload().deepCopy(), entry.expiresAt(), entry.version()));
    }

    @Override
    public void put(Long userId, String sessionId, Kind kind, JsonNode payload, Instant expiresAt) {
        Key key = key(userId, sessionId, kind);
        entries.compute(key, (ignored, current) -> new Entry(
                payload.deepCopy(),
                expiresAt,
                current == null ? 1L : current.version() + 1L,
                sequence.incrementAndGet()));
        while (entries.size() > MAX_ENTRIES) {
            entries.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().sequence()))
                    .ifPresent(entry -> entries.remove(entry.getKey(), entry.getValue()));
        }
    }

    @Override
    public boolean remove(Long userId, String sessionId, Kind kind, long expectedVersion) {
        Key key = key(userId, sessionId, kind);
        Entry entry = entries.get(key);
        return entry != null && entry.version() == expectedVersion && entries.remove(key, entry);
    }

    @Override
    public void remove(Long userId, String sessionId, Kind kind) {
        entries.remove(key(userId, sessionId, kind));
    }

    @Override
    public void removeSession(Long userId, String sessionId) {
        entries.keySet().removeIf(key -> key.userId().equals(userId) && key.sessionId().equals(sessionId));
    }

    @Override
    public void removeUser(Long userId) {
        entries.keySet().removeIf(key -> key.userId().equals(userId));
    }

    int size() {
        return entries.size();
    }

    private Key key(Long userId, String sessionId, Kind kind) {
        if (userId == null || sessionId == null || sessionId.isBlank() || kind == null) {
            throw new IllegalArgumentException("AI session state requires user, session, and kind.");
        }
        return new Key(userId, sessionId, kind);
    }

    private record Key(Long userId, String sessionId, Kind kind) {
    }

    private record Entry(JsonNode payload, Instant expiresAt, long version, long sequence) {
    }
}
