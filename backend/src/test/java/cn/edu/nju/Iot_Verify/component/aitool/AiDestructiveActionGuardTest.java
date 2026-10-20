package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.component.ai.state.AiSessionStateStore;
import cn.edu.nju.Iot_Verify.component.ai.state.InMemoryAiSessionStateStore;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Stream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AiDestructiveActionGuardTest {

    private AiDestructiveActionGuard guard;

    @BeforeEach
    void setUp() {
        guard = new AiDestructiveActionGuard(new ObjectMapper());
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("session-1");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void consume_shouldBindExactActionAndAllowOnlyOneUse() {
        Map<String, Object> preview = Map.of("rule", Map.of("id", 7, "label", "Alarm"));
        String token = guard.issue(1L, "manage_rule", "7", "Preview target", preview, "domain-token");
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);

        AiDestructiveActionGuard.ConsumeResult first =
                guard.consume(1L, "manage_rule", "7", token, preview);
        AiDestructiveActionGuard.ConsumeResult replay =
                guard.consume(1L, "manage_rule", "7", token, preview);

        assertTrue(first.approved());
        assertEquals("domain-token", first.domainImpactToken());
        assertFalse(replay.approved());
        assertEquals("CONFIRMATION_MISSING", replay.errorCode());
    }

    @Test
    void consume_shouldRejectAnotherUserSessionToolOrTargetWithoutConsumingMatch() {
        Map<String, Object> preview = Map.of("traceId", 11, "stateCount", 3);
        String token = guard.issue(1L, "delete_trace", "11", "Preview target", preview, null);
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);

        assertEquals("CONFIRMATION_MISSING",
                guard.consume(2L, "delete_trace", "11", token, preview).errorCode());

        UserContextHolder.setChatSessionId("session-2");
        assertEquals("CONFIRMATION_MISSING",
                guard.consume(1L, "delete_trace", "11", token, preview).errorCode());

        UserContextHolder.setChatSessionId("session-1");
        assertEquals("CONFIRMATION_MISMATCH",
                guard.consume(1L, "delete_simulation_trace", "11", token, preview).errorCode());
        assertEquals("CONFIRMATION_MISMATCH",
                guard.consume(1L, "delete_trace", "12", token, preview).errorCode());
        assertEquals("CONFIRMATION_MISMATCH",
                guard.consume(1L, "delete_trace", "11", "wrong-token", preview).errorCode());

        assertTrue(guard.consume(1L, "delete_trace", "11", token, preview).approved());
    }

    @Test
    void consume_shouldRejectPreviewDriftAndInvalidateOldToken() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("specification", Map.of("id", "spec-1", "conditions", List.of("smoke")));
        String token = guard.issue(1L, "manage_spec", "spec-1", "Preview target", original, null);
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);

        Map<String, Object> changed = Map.of(
                "specification", Map.of("id", "spec-1", "conditions", List.of("smoke", "heat")));
        AiDestructiveActionGuard.ConsumeResult stale =
                guard.consume(1L, "manage_spec", "spec-1", token, changed);
        AiDestructiveActionGuard.ConsumeResult replay =
                guard.consume(1L, "manage_spec", "spec-1", token, original);

        assertFalse(stale.approved());
        assertEquals("CONFIRMATION_STALE", stale.errorCode());
        assertEquals("CONFIRMATION_MISSING", replay.errorCode());
    }

    @Test
    void issue_shouldReplaceEarlierPendingActionInSameSession() {
        String first = guard.issue(1L, "manage_rule", "1", "Preview target", Map.of("id", 1), null);
        String second = guard.issue(1L, "manage_rule", "2", "Preview target", Map.of("id", 2), null);
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", second);

        assertNotEquals(first, second);
        assertEquals("CONFIRMATION_MISMATCH",
                guard.consume(1L, "manage_rule", "1", first, Map.of("id", 1)).errorCode());
        assertTrue(guard.consume(1L, "manage_rule", "2", second, Map.of("id", 2)).approved());
    }

    @Test
    void consume_shouldNotLetAnApprovedTurnAuthorizeAnotherPreviewOfTheSameKind() {
        String approved = guard.issue(1L, "delete_device", "X", "Preview target", Map.of("id", "X"), null);
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", approved);
        String replacement = guard.issue(1L, "delete_device", "Y", "Preview target", Map.of("id", "Y"), null);

        assertEquals("CONFIRMATION_MISMATCH",
                guard.consume(1L, "delete_device", "Y", replacement, Map.of("id", "Y")).errorCode());
        assertFalse(guard.cancelPending(1L, "session-1", approved));
        assertEquals(replacement, guard.pendingContext(1L, "session-1").orElseThrow().impactToken());

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", replacement);
        assertTrue(guard.consume(1L, "delete_device", "Y", replacement, Map.of("id", "Y")).approved());
    }

    @Test
    void consume_shouldRequireAnExplicitConfirmationTurn() {
        Map<String, Object> preview = Map.of("simulationId", 5);
        String token = guard.issue(1L, "delete_simulation_trace", "5", "Preview target", preview, null);

        AiDestructiveActionGuard.ConsumeResult result =
                guard.consume(1L, "delete_simulation_trace", "5", token, preview);

        assertFalse(result.approved());
        assertEquals("CONFIRMATION_REQUIRED", result.errorCode());
    }

    @Test
    void consume_shouldNotTreatSceneReplacementConfirmationAsDeletionAuthorization() {
        Map<String, Object> preview = Map.of("rule", Map.of("id", 7, "label", "Alarm"));
        String token = guard.issue(1L, "manage_rule", "7", "Preview target", preview, "domain-token");
        UserContextHolder.setConfirmedProtectedAction("SCENE_REPLACEMENT", token);

        AiDestructiveActionGuard.ConsumeResult result =
                guard.consume(1L, "manage_rule", "7", token, preview);

        assertFalse(result.approved());
        assertEquals("CONFIRMATION_REQUIRED", result.errorCode());
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);
        assertTrue(guard.consume(1L, "manage_rule", "7", token, preview).approved());
    }

    @Test
    void consume_shouldKeepDefaultTemplateResetConfirmationScopedToResetTool() {
        Map<String, Object> preview = Map.of("rule", Map.of("id", 7, "label", "Alarm"));
        String token = guard.issue(1L, "manage_rule", "7", "Preview target", preview, "domain-token");
        UserContextHolder.setConfirmedProtectedAction("DEFAULT_TEMPLATE_RESET", token);

        AiDestructiveActionGuard.ConsumeResult result =
                guard.consume(1L, "manage_rule", "7", token, preview);

        assertFalse(result.approved());
        assertEquals("CONFIRMATION_REQUIRED", result.errorCode());
    }

    @Test
    void pendingContext_shouldExposeCompactServerSideConfirmationData() {
        String token = guard.issue(
                1L, "delete_device", "alarm_1", "Emergency Alarm", Map.of("id", "alarm_1"), "domain-token");

        AiDestructiveActionGuard.PendingActionContext context = guard
                .pendingContext(1L, "session-1")
                .orElseThrow();

        assertEquals("delete_device", context.toolName());
        assertEquals("alarm_1", context.targetKey());
        assertEquals(token, context.impactToken());
        assertEquals("Emergency Alarm", context.displayTarget());
    }

    @Test
    void displayTarget_preservesThePreviewLabelAcrossInstancesWithoutReplacingTheAuthorizationKey() {
        InMemoryAiSessionStateStore state = new InMemoryAiSessionStateStore();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
        AiDestructiveActionGuard issuer = new AiDestructiveActionGuard(new ObjectMapper(), state, clock);
        AiDestructiveActionGuard reloaded = new AiDestructiveActionGuard(new ObjectMapper(), state, clock);
        Map<String, Object> preview = new LinkedHashMap<>(Map.of("label", "Hallway Camera"));
        String token = issuer.issue(1L, "delete_device", "camera_1", "Hallway Camera", preview, null);

        preview.put("label", "Changed in another tab");
        AiDestructiveActionGuard.PendingActionContext pending = reloaded
                .pendingContext(1L, "session-1").orElseThrow();
        assertEquals("Hallway Camera", pending.displayTarget());
        assertEquals("camera_1", pending.targetKey());
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);
        assertEquals("CONFIRMATION_MISMATCH", reloaded.consume(
                1L, "delete_device", pending.displayTarget(), token, Map.of("label", "Hallway Camera"))
                .errorCode());
        assertEquals(token, reloaded.pendingContext(1L, "session-1").orElseThrow().impactToken());
        assertEquals("CONFIRMATION_STALE", reloaded.consume(
                1L, "delete_device", pending.targetKey(), token, preview).errorCode());
    }

    @Test
    void issue_requiresAReadableDisplayTargetWithoutOverwritingThePreviousPreview() {
        String original = guard.issue(1L, "delete_device", "camera_1", "Hallway Camera",
                Map.of("id", "camera_1"), null);
        assertThrows(IllegalArgumentException.class, () -> guard.issue(
                1L, "delete_device", "air_purifier", " ", Map.of("id", "air_purifier"), null));
        assertEquals(original, guard.pendingContext(1L, "session-1").orElseThrow().impactToken());
    }

    @ParameterizedTest
    @MethodSource("invalidPendingPayloads")
    void pendingContext_rejectsInvalidPayloadsWithoutWritingState(JsonNode payload) {
        AiSessionStateStore state = mock(AiSessionStateStore.class);
        when(state.get(eq(1L), eq("session-1"), eq(AiSessionStateStore.Kind.DESTRUCTIVE_ACTION), any(Instant.class)))
                .thenReturn(Optional.of(new AiSessionStateStore.Snapshot(payload, Instant.now().plusSeconds(600), 7L)));
        AiDestructiveActionGuard reader = new AiDestructiveActionGuard(new ObjectMapper(), state);

        assertTrue(reader.pendingContext(1L, "session-1").isEmpty());
        verify(state).get(eq(1L), eq("session-1"), eq(AiSessionStateStore.Kind.DESTRUCTIVE_ACTION), any(Instant.class));
        verifyNoMoreInteractions(state);
    }

    private static Stream<JsonNode> invalidPendingPayloads() {
        ObjectNode valid = JsonNodeFactory.instance.objectNode();
        valid.put("token", "opaque-confirmation");
        valid.put("toolName", "delete_device");
        valid.put("targetKey", "camera_1");
        valid.put("displayTarget", "Hallway Camera");
        valid.put("previewDigest", Base64.getEncoder().encodeToString(new byte[32]));
        ObjectNode previousShape = valid.deepCopy();
        previousShape.remove("displayTarget");
        ObjectNode missingDigest = valid.deepCopy();
        missingDigest.remove("previewDigest");
        return Stream.of(previousShape, missingDigest,
                valid.deepCopy().put("displayTarget", " "), valid.deepCopy().putNull("token"),
                valid.deepCopy().put("toolName", ""), valid.deepCopy().putNull("targetKey"),
                valid.deepCopy().put("previewDigest", "!invalid-base64!"),
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.nullNode());
    }

    @Test
    void anotherGuardInstanceCanConsumeThePersistedSingleUseConfirmation() {
        InMemoryAiSessionStateStore sharedState = new InMemoryAiSessionStateStore();
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC);
        AiDestructiveActionGuard issuer = new AiDestructiveActionGuard(
                new ObjectMapper(), sharedState, clock);
        AiDestructiveActionGuard consumer = new AiDestructiveActionGuard(
                new ObjectMapper(), sharedState, clock);
        Map<String, Object> preview = Map.of("id", 17);
        String token = issuer.issue(1L, "delete_trace", "17", "Preview target", preview, null);
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);

        assertTrue(consumer.consume(1L, "delete_trace", "17", token, preview).approved());
        assertEquals("CONFIRMATION_MISSING",
                issuer.consume(1L, "delete_trace", "17", token, preview).errorCode());
    }

    @Test
    void storedAction_canBeConsumedExactlyOnceAcrossInstances() {
        InMemoryAiSessionStateStore sharedState = new InMemoryAiSessionStateStore();
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC);
        AiDestructiveActionGuard issuer = new AiDestructiveActionGuard(
                new ObjectMapper(), sharedState, clock);
        AiDestructiveActionGuard consumer = new AiDestructiveActionGuard(
                new ObjectMapper(), sharedState, clock);
        Map<String, Object> action = Map.of("traceId", 19, "strategy", "remove");
        String token = issuer.issueStoredAction(1L, "apply_fix", "19", "Preview target", action);
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);

        AiDestructiveActionGuard.ConsumeResult result =
                consumer.consumeStoredAction(1L, "apply_fix", "19", token);

        assertTrue(result.approved());
        assertEquals(19, result.actionPayload().path("traceId").asInt());
        assertEquals("remove", result.actionPayload().path("strategy").asText());
        assertEquals("CONFIRMATION_MISSING",
                issuer.consumeStoredAction(1L, "apply_fix", "19", token).errorCode());
    }

    @Test
    void storedAction_expiresWithoutReturningItsPayload() {
        InMemoryAiSessionStateStore sharedState = new InMemoryAiSessionStateStore();
        Instant issuedAt = Instant.parse("2026-07-17T00:00:00Z");
        AiDestructiveActionGuard issuer = new AiDestructiveActionGuard(
                new ObjectMapper(), sharedState, Clock.fixed(issuedAt, ZoneOffset.UTC));
        String token = issuer.issueStoredAction(
                1L, "apply_fix", "23", "Preview target", Map.of("traceId", 23));
        AiDestructiveActionGuard expiredConsumer = new AiDestructiveActionGuard(
                new ObjectMapper(), sharedState,
                Clock.fixed(issuedAt.plusSeconds(16 * 60), ZoneOffset.UTC));
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", token);

        AiDestructiveActionGuard.ConsumeResult result =
                expiredConsumer.consumeStoredAction(1L, "apply_fix", "23", token);

        assertFalse(result.approved());
        assertEquals("CONFIRMATION_MISSING", result.errorCode());
        assertEquals(null, result.actionPayload());
    }

}
