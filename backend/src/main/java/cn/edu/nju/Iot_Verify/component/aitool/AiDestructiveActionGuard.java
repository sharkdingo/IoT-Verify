package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.component.ai.state.AiSessionStateStore;
import cn.edu.nju.Iot_Verify.component.ai.state.InMemoryAiSessionStateStore;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Session-scoped, single-use authorization for destructive AI tool calls. */
@Component
public class AiDestructiveActionGuard {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final int TOKEN_BYTES = 32;

    private final ObjectMapper objectMapper;
    private final AiSessionStateStore stateStore;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiDestructiveActionGuard(ObjectMapper objectMapper) {
        this(objectMapper, new InMemoryAiSessionStateStore(), Clock.systemUTC());
    }

    @Autowired
    public AiDestructiveActionGuard(ObjectMapper objectMapper, AiSessionStateStore stateStore) {
        this(objectMapper, stateStore, Clock.systemUTC());
    }

    AiDestructiveActionGuard(ObjectMapper objectMapper, AiSessionStateStore stateStore, Clock clock) {
        this.objectMapper = objectMapper;
        this.stateStore = stateStore;
        this.clock = clock;
    }

    public String issue(Long userId,
                        String toolName,
                        String targetKey,
                        String displayTarget,
                        Object previewSnapshot,
                        String domainImpactToken) {
        return issue(userId, toolName, targetKey, displayTarget, previewSnapshot, domainImpactToken, null);
    }

    /**
     * Stores an immutable action payload for tools whose confirmed call must not ask the model to
     * repeat a signed or otherwise security-sensitive proposal from chat history.
     */
    public String issueStoredAction(Long userId,
                                    String toolName,
                                    String targetKey,
                                    String displayTarget,
                                    Object actionPayload) {
        if (actionPayload == null) {
            throw new IllegalArgumentException("actionPayload must not be null");
        }
        return issue(userId, toolName, targetKey, displayTarget, actionPayload, null, actionPayload);
    }

    private String issue(Long userId,
                         String toolName,
                         String targetKey,
                         String displayTarget,
                         Object previewSnapshot,
                         String domainImpactToken,
                         Object actionPayload) {
        SessionScope scope = currentScope(userId);
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = clock.instant().plus(TOKEN_TTL);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("token", token);
        payload.put("toolName", requireText(toolName, "toolName"));
        payload.put("targetKey", requireText(targetKey, "targetKey"));
        // Display the previewed target after reload without making its label an authorization key.
        payload.put("displayTarget", requireText(displayTarget, "displayTarget"));
        payload.put("previewDigest", Base64.getEncoder().encodeToString(digest(previewSnapshot)));
        if (domainImpactToken != null) payload.put("domainImpactToken", domainImpactToken);
        if (actionPayload != null) payload.set("actionPayload", objectMapper.valueToTree(actionPayload));
        stateStore.put(scope.userId(), scope.sessionId(), AiSessionStateStore.Kind.DESTRUCTIVE_ACTION,
                payload, expiresAt);
        return token;
    }

    public ConsumeResult consume(Long userId,
                                 String toolName,
                                 String targetKey,
                                 String suppliedToken,
                                 Object currentPreviewSnapshot) {
        return consume(userId, toolName, targetKey, suppliedToken, currentPreviewSnapshot, false);
    }

    /**
     * Consumes the exact immutable action payload stored by {@link #issueStoredAction}. Current
     * domain state must still be checked by the called mutation service before it writes.
     */
    public ConsumeResult consumeStoredAction(Long userId,
                                             String toolName,
                                             String targetKey,
                                             String suppliedToken) {
        return consume(userId, toolName, targetKey, suppliedToken, null, true);
    }

    private ConsumeResult consume(Long userId,
                                  String toolName,
                                  String targetKey,
                                  String suppliedToken,
                                  Object currentPreviewSnapshot,
                                  boolean useStoredAction) {
        boolean actionConfirmed = "reset_default_templates".equals(toolName)
                ? UserContextHolder.isDefaultTemplateResetConfirmed()
                : UserContextHolder.isDestructiveActionConfirmed();
        if (!actionConfirmed) {
            return ConsumeResult.rejected(
                    "CONFIRMATION_REQUIRED",
                    "No changes were made. Confirm the exact preview in a later message before retrying.");
        }

        SessionScope scope;
        try {
            scope = currentScope(userId);
        } catch (IllegalStateException e) {
            return ConsumeResult.rejected(
                    "CONFIRMATION_CONTEXT_REQUIRED",
                    "No changes were made because the protected-action confirmation is not associated with an active chat session.");
        }

        AiSessionStateStore.Snapshot snapshot = stateStore.get(
                scope.userId(), scope.sessionId(), AiSessionStateStore.Kind.DESTRUCTIVE_ACTION,
                clock.instant()).orElse(null);
        if (snapshot == null) {
            return ConsumeResult.rejected(
                    "CONFIRMATION_MISSING",
                    "No changes were made. The preview is missing, expired, or already used; request a fresh preview.");
        }

        PendingAction pending = parsePending(snapshot);
        if (pending == null) {
            return ConsumeResult.rejected(
                    "CONFIRMATION_MISSING",
                    "No changes were made. The preview is missing, expired, or unreadable; request a fresh preview.");
        }

        String expectedTool = requireText(toolName, "toolName");
        String expectedTarget = requireText(targetKey, "targetKey");
        if (!pending.toolName().equals(expectedTool)
                || !pending.targetKey().equals(expectedTarget)
                || !secureEquals(pending.token(), suppliedToken)
                || !secureEquals(pending.token(), UserContextHolder.getConfirmedProtectedActionId())) {
            return ConsumeResult.rejected(
                    "CONFIRMATION_MISMATCH",
                    "No changes were made. The confirmation token does not match this tool and target; review a fresh preview.");
        }

        if (useStoredAction) {
            if (pending.actionPayload() == null) {
                stateStore.remove(scope.userId(), scope.sessionId(),
                        AiSessionStateStore.Kind.DESTRUCTIVE_ACTION, snapshot.version());
                return ConsumeResult.rejected(
                        "CONFIRMATION_MISSING",
                        "No changes were made because the stored protected-action proposal is unavailable; request a fresh preview.");
            }
            if (!MessageDigest.isEqual(pending.previewDigest(), digest(pending.actionPayload()))) {
                stateStore.remove(scope.userId(), scope.sessionId(),
                        AiSessionStateStore.Kind.DESTRUCTIVE_ACTION, snapshot.version());
                return ConsumeResult.rejected(
                        "CONFIRMATION_MISSING",
                        "No changes were made because the stored protected-action proposal is unreadable; request a fresh preview.");
            }
        } else {
            byte[] currentDigest = digest(currentPreviewSnapshot);
            if (!MessageDigest.isEqual(pending.previewDigest(), currentDigest)) {
                stateStore.remove(scope.userId(), scope.sessionId(), AiSessionStateStore.Kind.DESTRUCTIVE_ACTION,
                        snapshot.version());
                return ConsumeResult.rejected(
                        "CONFIRMATION_STALE",
                        "No changes were made because the protected-action impact changed after the preview; review and confirm the current preview.");
            }
        }

        if (!stateStore.remove(scope.userId(), scope.sessionId(),
                AiSessionStateStore.Kind.DESTRUCTIVE_ACTION, snapshot.version())) {
            return ConsumeResult.rejected(
                    "CONFIRMATION_CONSUMED",
                    "No changes were made. This protected-action confirmation was already used; request a fresh preview.");
        }
        return ConsumeResult.approved(pending.domainImpactToken(), pending.actionPayload());
    }

    public void clearSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) return;
        stateStore.remove(userId, sessionId, AiSessionStateStore.Kind.DESTRUCTIVE_ACTION);
    }

    public boolean cancelPending(Long userId, String sessionId, String confirmationId) {
        AiSessionStateStore.Snapshot snapshot = stateStore.get(userId, sessionId,
                AiSessionStateStore.Kind.DESTRUCTIVE_ACTION, clock.instant()).orElse(null);
        if (snapshot == null) return false;
        PendingAction pending = parsePending(snapshot);
        return pending != null && secureEquals(pending.token(), confirmationId)
                && stateStore.remove(userId, sessionId, AiSessionStateStore.Kind.DESTRUCTIVE_ACTION,
                        snapshot.version());
    }

    public void clearUser(Long userId) {
        if (userId == null) return;
        stateStore.removeUser(userId);
    }

    /** Returns compact confirmation context without depending on persisted tool-result history. */
    public Optional<PendingActionContext> pendingContext(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) return Optional.empty();
        AiSessionStateStore.Snapshot snapshot = stateStore.get(
                userId, sessionId, AiSessionStateStore.Kind.DESTRUCTIVE_ACTION,
                clock.instant()).orElse(null);
        if (snapshot == null) return Optional.empty();
        PendingAction pending = parsePending(snapshot);
        if (pending == null) return Optional.empty();
        return Optional.of(new PendingActionContext(
                pending.toolName(), pending.targetKey(), pending.token(), pending.displayTarget()));
    }

    private PendingAction parsePending(AiSessionStateStore.Snapshot snapshot) {
        try {
            JsonNode payload = snapshot.payload();
            return new PendingAction(
                    requireText(payload.path("token").asText(null), "token"),
                    requireText(payload.path("toolName").asText(null), "toolName"),
                    requireText(payload.path("targetKey").asText(null), "targetKey"),
                    requireText(payload.path("displayTarget").asText(null), "displayTarget"),
                    Base64.getDecoder().decode(requireText(
                            payload.path("previewDigest").asText(null), "previewDigest")),
                    payload.hasNonNull("domainImpactToken")
                            ? payload.path("domainImpactToken").asText() : null,
                    payload.hasNonNull("actionPayload")
                            ? payload.path("actionPayload").deepCopy() : null);
        } catch (RuntimeException e) {
            // Do not enlist deletes in a read-only caller; expiry cleanup or the next preview handles this row.
            return null;
        }
    }

    private SessionScope currentScope(Long userId) {
        if (userId == null) {
            throw new IllegalStateException("A destructive AI action requires an authenticated user.");
        }
        String sessionId = UserContextHolder.getChatSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("A destructive AI action requires an active chat session.");
        }
        return new SessionScope(userId, sessionId);
    }

    private byte[] digest(Object value) {
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            JsonNode canonical = canonicalize(tree);
            return MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not bind the protected-action preview", e);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) return JsonNodeFactory.instance.nullNode();
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.sort(Comparator.naturalOrder());
            for (String fieldName : fieldNames) {
                sorted.set(fieldName, canonicalize(node.get(fieldName)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        return node.deepCopy();
    }

    private boolean secureEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record SessionScope(Long userId, String sessionId) {
    }

    private record PendingAction(String token,
                                 String toolName,
                                 String targetKey,
                                 String displayTarget,
                                 byte[] previewDigest,
                                 String domainImpactToken,
                                 JsonNode actionPayload) {
    }

    public record PendingActionContext(String toolName, String targetKey, String impactToken,
                                       String displayTarget) {
    }

    public record ConsumeResult(boolean approved,
                                String errorCode,
                                String message,
                                String domainImpactToken,
                                JsonNode actionPayload) {

        private static ConsumeResult approved(String domainImpactToken, JsonNode actionPayload) {
            return new ConsumeResult(true, null, null, domainImpactToken,
                    actionPayload == null ? null : actionPayload.deepCopy());
        }

        private static ConsumeResult rejected(String errorCode, String message) {
            return new ConsumeResult(false, errorCode, message, null, null);
        }
    }
}
