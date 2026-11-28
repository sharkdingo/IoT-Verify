package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.state.InMemoryAiSessionStateStore;
import cn.edu.nju.Iot_Verify.testutil.MutableUtcClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTaskContinuationStoreTest {

    @Test
    void keepsOriginalObjectiveWithinTheSameUserAndSession() {
        MutableUtcClock clock = new MutableUtcClock(Instant.parse("2026-07-17T00:00:00Z"));
        AiTaskContinuationStore store = new AiTaskContinuationStore(new InMemoryAiSessionStateStore(), clock);

        store.save(1L, "s1", "  delete the old sensor, create a replacement, then verify  ");

        assertEquals(
                "delete the old sensor, create a replacement, then verify",
                store.pendingContext(1L, "s1").orElseThrow().originalObjective());
        assertTrue(store.pendingContext(1L, "s2").isEmpty());
        assertTrue(store.pendingContext(2L, "s1").isEmpty());
    }

    @Test
    void expiresContinuationAtTheConfirmationBoundaryTtl() {
        MutableUtcClock clock = new MutableUtcClock(Instant.parse("2026-07-17T00:00:00Z"));
        AiTaskContinuationStore store = new AiTaskContinuationStore(new InMemoryAiSessionStateStore(), clock);
        store.save(1L, "s1", "replace and verify");

        clock.advance(AiTaskContinuationStore.CONTINUATION_TTL.plus(Duration.ofSeconds(1)));

        assertTrue(store.pendingContext(1L, "s1").isEmpty());
    }

    @Test
    void preservesPendingChoiceAndRecentUserUpdatesWithoutReplacingTheOriginalGoal() {
        MutableUtcClock clock = new MutableUtcClock(Instant.parse("2026-07-17T00:00:00Z"));
        AiTaskContinuationStore store = new AiTaskContinuationStore(new InMemoryAiSessionStateStore(), clock);
        store.savePendingStep(
                1L,
                "s1",
                "delete the old sensor, create a replacement, then verify",
                "delete the old sensor, create a replacement, then verify",
                "delete_device",
                "{\"suggestedLabel\":\"Hall Sensor 2\"}");

        store.rememberUserMessage(1L, "s1", "Keep the existing rules");
        AiTaskContinuationStore.ContinuationContext context = store
                .pendingContext(1L, "s1")
                .orElseThrow();

        assertEquals("delete the old sensor, create a replacement, then verify",
                context.originalObjective());
        assertEquals("delete_device", context.pendingToolName());
        assertEquals("{\"suggestedLabel\":\"Hall Sensor 2\"}", context.pendingResult());
        assertEquals(java.util.List.of("Keep the existing rules"), context.recentUserMessages());
    }

    @Test
    void anotherStoreInstanceCanResumeThePersistedTask() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC);
        InMemoryAiSessionStateStore sharedState = new InMemoryAiSessionStateStore();
        AiTaskContinuationStore writer = new AiTaskContinuationStore(sharedState, clock);
        AiTaskContinuationStore reader = new AiTaskContinuationStore(sharedState, clock);

        writer.save(1L, "s1", "replace the sensor and verify");

        assertEquals("replace the sensor and verify",
                reader.pendingContext(1L, "s1").orElseThrow().originalObjective());
    }
}
