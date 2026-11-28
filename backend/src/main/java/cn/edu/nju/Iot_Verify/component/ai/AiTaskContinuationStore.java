package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.state.AiSessionStateStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Keeps user-authored task context across a server-enforced confirmation pause. */
@Component
public class AiTaskContinuationStore {

    static final Duration CONTINUATION_TTL = Duration.ofMinutes(15);
    static final int MAX_OBJECTIVE_CHARS = 4000;
    static final int MAX_UPDATE_CHARS = 1000;
    static final int MAX_UPDATES = 4;
    static final int MAX_PENDING_RESULT_CHARS = 3000;

    private final AiSessionStateStore stateStore;
    private final Clock clock;

    @Autowired
    public AiTaskContinuationStore(AiSessionStateStore stateStore) {
        this(stateStore, Clock.systemUTC());
    }

    AiTaskContinuationStore(AiSessionStateStore stateStore, Clock clock) {
        this.stateStore = stateStore;
        this.clock = clock;
    }

    public void save(Long userId, String sessionId, String objective) {
        savePendingStep(userId, sessionId, objective, objective, null, null);
    }

    public void savePendingStep(Long userId,
                                String sessionId,
                                String fallbackObjective,
                                String latestUserMessage,
                                String pendingToolName,
                                String pendingResult) {
        SessionScope scope = scope(userId, sessionId);
        PendingTask current = active(scope);
        String objective = current == null
                ? boundedText(fallbackObjective, MAX_OBJECTIVE_CHARS)
                : current.originalObjective();
        if (objective == null) {
            stateStore.remove(scope.userId(), scope.sessionId(), AiSessionStateStore.Kind.TASK_CONTINUATION);
            return;
        }

        List<String> updates = current == null
                ? new ArrayList<>()
                : new ArrayList<>(current.recentUserMessages());
        addUpdate(updates, objective, latestUserMessage);
        save(scope, new PendingTask(
                objective,
                List.copyOf(updates),
                boundedText(pendingToolName, 120),
                boundedText(pendingResult, MAX_PENDING_RESULT_CHARS),
                clock.instant().plus(CONTINUATION_TTL)));
    }

    public void rememberUserMessage(Long userId, String sessionId, String userMessage) {
        SessionScope scope = scope(userId, sessionId);
        PendingTask current = active(scope);
        if (current == null) return;
        List<String> updates = new ArrayList<>(current.recentUserMessages());
        addUpdate(updates, current.originalObjective(), userMessage);
        save(scope, new PendingTask(
                current.originalObjective(),
                List.copyOf(updates),
                current.pendingToolName(),
                current.pendingResult(),
                clock.instant().plus(CONTINUATION_TTL)));
    }

    public Optional<ContinuationContext> pendingContext(Long userId, String sessionId) {
        PendingTask pending = active(scope(userId, sessionId));
        if (pending == null) return Optional.empty();
        return Optional.of(new ContinuationContext(
                pending.originalObjective(),
                List.copyOf(pending.recentUserMessages()),
                pending.pendingToolName(),
                pending.pendingResult(),
                pending.expiresAt()));
    }

    public void clear(Long userId, String sessionId) {
        SessionScope scope = scope(userId, sessionId);
        stateStore.remove(scope.userId(), scope.sessionId(), AiSessionStateStore.Kind.TASK_CONTINUATION);
    }

    public void clearSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) return;
        stateStore.remove(userId, sessionId, AiSessionStateStore.Kind.TASK_CONTINUATION);
    }

    public void clearUser(Long userId) {
        if (userId == null) return;
        stateStore.removeUser(userId);
    }

    private PendingTask active(SessionScope scope) {
        AiSessionStateStore.Snapshot snapshot = stateStore.get(
                scope.userId(), scope.sessionId(), AiSessionStateStore.Kind.TASK_CONTINUATION,
                clock.instant()).orElse(null);
        if (snapshot == null) return null;
        try {
            JsonNode payload = snapshot.payload();
            String objective = boundedText(payload.path("originalObjective").asText(null), MAX_OBJECTIVE_CHARS);
            if (objective == null) throw new IllegalArgumentException("missing objective");
            List<String> updates = new ArrayList<>();
            JsonNode recent = payload.path("recentUserMessages");
            if (recent.isArray()) {
                recent.forEach(value -> addUpdate(
                        updates, objective, boundedText(value.asText(null), MAX_UPDATE_CHARS)));
            }
            return new PendingTask(
                    objective,
                    List.copyOf(updates),
                    boundedText(payload.path("pendingToolName").asText(null), 120),
                    boundedText(payload.path("pendingResult").asText(null), MAX_PENDING_RESULT_CHARS),
                    snapshot.expiresAt());
        } catch (RuntimeException e) {
            stateStore.remove(scope.userId(), scope.sessionId(), AiSessionStateStore.Kind.TASK_CONTINUATION,
                    snapshot.version());
            return null;
        }
    }

    private void save(SessionScope scope, PendingTask pending) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("originalObjective", pending.originalObjective());
        ArrayNode updates = payload.putArray("recentUserMessages");
        pending.recentUserMessages().forEach(updates::add);
        if (pending.pendingToolName() != null) payload.put("pendingToolName", pending.pendingToolName());
        if (pending.pendingResult() != null) payload.put("pendingResult", pending.pendingResult());
        stateStore.put(scope.userId(), scope.sessionId(), AiSessionStateStore.Kind.TASK_CONTINUATION,
                payload, pending.expiresAt());
    }

    private void addUpdate(List<String> updates, String objective, String value) {
        String update = boundedText(value, MAX_UPDATE_CHARS);
        if (update == null || update.equals(objective)
                || (!updates.isEmpty() && update.equals(updates.get(updates.size() - 1)))) {
            return;
        }
        updates.add(update);
        while (updates.size() > MAX_UPDATES) {
            updates.remove(0);
        }
    }

    private String boundedText(String value, int maxChars) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private SessionScope scope(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("A chat user and session are required for task continuation.");
        }
        return new SessionScope(userId, sessionId);
    }

    public record ContinuationContext(String originalObjective,
                                      List<String> recentUserMessages,
                                      String pendingToolName,
                                      String pendingResult,
                                      Instant expiresAt) {
    }

    private record SessionScope(Long userId, String sessionId) {
    }

    private record PendingTask(String originalObjective,
                               List<String> recentUserMessages,
                               String pendingToolName,
                               String pendingResult,
                               Instant expiresAt) {
    }
}
