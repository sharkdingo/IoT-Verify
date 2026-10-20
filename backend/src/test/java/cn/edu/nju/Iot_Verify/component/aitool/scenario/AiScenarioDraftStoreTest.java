package cn.edu.nju.Iot_Verify.component.aitool.scenario;

import cn.edu.nju.Iot_Verify.component.ai.state.AiSessionStateStore;
import cn.edu.nju.Iot_Verify.component.ai.state.InMemoryAiSessionStateStore;
import cn.edu.nju.Iot_Verify.dto.board.BoardReplacementPreviewDto;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AiScenarioDraftStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void invalidReplacementDoesNotDeleteThePreviousValidDraft() throws Exception {
        AiScenarioDraftStore store = new AiScenarioDraftStore();
        store.saveDraft(1L, "session", "Existing", objectMapper.readTree(
                "{\"devices\":[{\"id\":\"device_1\"}],\"specs\":[]}"));

        AiScenarioDraftStore.DraftSaveResult result = store.saveDraft(
                1L, "session", "Invalid", objectMapper.readTree(
                "{\"devices\":[],\"specs\":[]}"));

        assertFalse(result.draftStored());
        assertTrue(result.previousDraftRetained());
        AiScenarioDraftStore.DraftSnapshot snapshot = store.latestDraft(1L, "session").orElseThrow();
        assertEquals("Existing", snapshot.scenarioName());
        assertTrue(snapshot.scene().path("devices").size() == 1);
    }

    @Test
    void anotherStoreInstanceCanReadThePersistedDraft() throws Exception {
        InMemoryAiSessionStateStore sharedState = new InMemoryAiSessionStateStore();
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC);
        AiScenarioDraftStore writer = new AiScenarioDraftStore(sharedState, objectMapper, clock);
        AiScenarioDraftStore reader = new AiScenarioDraftStore(sharedState, objectMapper, clock);

        writer.saveDraft(1L, "session", "Shared", objectMapper.readTree(
                "{\"devices\":[{\"id\":\"device_1\"}],\"specs\":[]}"));

        assertEquals("Shared", reader.latestDraft(1L, "session").orElseThrow().scenarioName());
    }

    @Test
    void anotherStoreInstanceCanRecoverThePendingReplacementPreview() throws Exception {
        InMemoryAiSessionStateStore sharedState = new InMemoryAiSessionStateStore();
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC);
        AiScenarioDraftStore writer = new AiScenarioDraftStore(sharedState, objectMapper, clock);
        AiScenarioDraftStore reader = new AiScenarioDraftStore(sharedState, objectMapper, clock);
        writer.saveDraft(1L, "session", "Shared", objectMapper.readTree("""
                {"devices":[{"id":"device_1"}],"specs":[]}
                """));
        writer.beginApplication(1L, "session", BoardReplacementPreviewDto.builder()
                .impactToken("board-v1")
                .deviceCount(3)
                .environmentVariableCount(2)
                .ruleCount(1)
                .specificationCount(4)
                .editHistoryEntryCount(7)
                .build());

        AiScenarioDraftStore.PendingApplication pending = reader
                .pendingApplication(1L, "session")
                .orElseThrow();

        assertEquals("board-v1", pending.preview().getImpactToken());
        assertEquals(3, pending.preview().getDeviceCount());
        assertEquals(7, pending.preview().getEditHistoryEntryCount());
        assertEquals(writer.pendingApplication(1L, "session").orElseThrow().confirmationId(),
                pending.confirmationId());
    }

    @Test
    void staleApprovalAndCancellationCannotConsumeAnotherDraftWithTheSameBoardImpact() throws Exception {
        AiScenarioDraftStore store = new AiScenarioDraftStore();
        BoardReplacementPreviewDto preview = BoardReplacementPreviewDto.builder().impactToken("unchanged-board").build();
        store.saveDraft(1L, "session", "X", objectMapper.readTree("{\"devices\":[{\"id\":\"X\"}]}"));
        String previous = store.beginApplication(1L, "session", preview).orElseThrow().confirmationId();
        store.saveDraft(1L, "session", "Y", objectMapper.readTree("{\"devices\":[{\"id\":\"Y\"}]}"));
        String current = store.beginApplication(1L, "session", preview).orElseThrow().confirmationId();

        assertNotEquals(previous, current);
        assertFalse(store.clearPendingApplication(1L, "session", previous));
        assertThrows(ConflictException.class, () -> store.completeApplication(1L, "session", previous));
        assertEquals(current, store.pendingApplication(1L, "session").orElseThrow().confirmationId());
        assertTrue(store.clearPendingApplication(1L, "session", current));
        assertTrue(store.pendingApplication(1L, "session").isEmpty());
        assertEquals("Y", store.latestDraft(1L, "session").orElseThrow().scenarioName());
    }

    @Test
    void completeApplicationConsumesOnlyTheConfirmedPendingDraft() throws Exception {
        AiScenarioDraftStore store = new AiScenarioDraftStore();
        store.saveDraft(1L, "session", "X", objectMapper.readTree("{\"devices\":[{\"id\":\"X\"}]}"));
        String identity = store.beginApplication(1L, "session",
                BoardReplacementPreviewDto.builder().impactToken("board").build()).orElseThrow().confirmationId();
        store.completeApplication(1L, "session", identity);
        assertTrue(store.latestDraft(1L, "session").isEmpty());
        assertThrows(ConflictException.class, () -> store.completeApplication(1L, "session", identity));
    }

    @Test
    void expiredPendingApplicationReadsLeaveTheDraftUnchangedForAFreshPreview() throws Exception {
        InMemoryAiSessionStateStore state = spy(new InMemoryAiSessionStateStore());
        Instant createdAt = Instant.parse("2026-10-08T00:00:00Z");
        AiScenarioDraftStore writer = new AiScenarioDraftStore(state, objectMapper,
                Clock.fixed(createdAt, ZoneOffset.UTC));
        writer.saveDraft(1L, "session", "Stored", objectMapper.readTree(
                "{\"devices\":[{\"id\":\"device_1\"}],\"specs\":[]}"));
        BoardReplacementPreviewDto preview = BoardReplacementPreviewDto.builder().impactToken("board-v1").build();
        String previous = writer.beginApplication(1L, "session", preview).orElseThrow().confirmationId();
        Instant readAt = createdAt.plusSeconds(16 * 60);
        AiScenarioDraftStore reader = new AiScenarioDraftStore(state, objectMapper,
                Clock.fixed(readAt, ZoneOffset.UTC));
        clearInvocations(state);

        assertTrue(reader.pendingApplication(1L, "session").isEmpty());
        assertEquals("Stored", reader.latestDraft(1L, "session").orElseThrow().scenarioName());
        verify(state, times(2)).get(1L, "session", AiSessionStateStore.Kind.SCENARIO_DRAFT, readAt);
        verifyNoMoreInteractions(state);

        AiScenarioDraftStore.PendingApplication refreshed = reader.beginApplication(1L, "session", preview).orElseThrow();
        assertNotEquals(previous, refreshed.confirmationId());
        assertEquals(readAt.plus(AiScenarioDraftStore.PENDING_TTL), refreshed.expiresAt());
        assertEquals(refreshed.confirmationId(), reader.pendingApplication(1L, "session").orElseThrow().confirmationId());
        reader.completeApplication(1L, "session", refreshed.confirmationId());
        assertTrue(reader.latestDraft(1L, "session").isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidDraftPayloads")
    void invalidDraftReadsRejectPayloadWithoutWritingState(JsonNode payload) {
        AiSessionStateStore state = mock(AiSessionStateStore.class);
        Instant now = Instant.parse("2026-10-08T00:00:00Z");
        when(state.get(eq(1L), eq("session"), eq(AiSessionStateStore.Kind.SCENARIO_DRAFT), any(Instant.class)))
                .thenReturn(Optional.of(new AiSessionStateStore.Snapshot(payload, now.plusSeconds(3600), 4L)));
        AiScenarioDraftStore reader = new AiScenarioDraftStore(state, objectMapper, Clock.fixed(now, ZoneOffset.UTC));

        assertTrue(reader.pendingApplication(1L, "session").isEmpty());
        assertTrue(reader.latestDraft(1L, "session").isEmpty());
        verify(state, times(2)).get(1L, "session", AiSessionStateStore.Kind.SCENARIO_DRAFT, now);
        verifyNoMoreInteractions(state);
    }

    private static Stream<JsonNode> invalidDraftPayloads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode valid = (ObjectNode) mapper.readTree("""
                {"scenarioName":"Stored","scene":{"devices":[{"id":"device_1"}]},
                 "pendingPreview":{"impactToken":"board"},"pendingExpiresAt":"2026-10-08T00:15:00Z",
                 "confirmationId":"preview-identity"}
                """);
        ObjectNode missingScene = valid.deepCopy();
        missingScene.remove("scene");
        ObjectNode nullScene = valid.deepCopy();
        nullScene.putNull("scene");
        ObjectNode scalarScene = valid.deepCopy();
        scalarScene.put("scene", "invalid");
        ObjectNode invalidPreview = valid.deepCopy();
        invalidPreview.put("pendingPreview", "invalid");
        ObjectNode invalidExpiry = valid.deepCopy();
        invalidExpiry.put("pendingExpiresAt", "invalid");
        ObjectNode missingIdentity = valid.deepCopy();
        missingIdentity.remove("confirmationId");
        return Stream.of(missingScene, nullScene, scalarScene, invalidPreview, invalidExpiry,
                missingIdentity, mapper.createArrayNode(), mapper.nullNode());
    }

}
