package cn.edu.nju.Iot_Verify.component.nusmv.fixer;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Affordable finite searches get a measured opportunity to finish. Larger searches retain their
 * proportional comparison window; neither can starve later strategies or exceed a running child budget.
 */
class FixContextTimeShareTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-29T00:00:00Z"));

    private FixContext contextWithDeadlineIn(Duration budget) {
        return FixContext.builder().clock(clock).deadline(clock.instant().plus(budget)).build();
    }

    @Test
    void aLoneStrategyDoesNotSpendTheWholeRequestOnAlternatives() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(300));
        ctx.beginStrategy("condition", 1);
        clock.advance(Duration.ofSeconds(2));
        assertEquals(298_000, ctx.remainingMillis(), "the strategy's own search is not limited");

        ctx.startAlternativesSearch();
        assertEquals(FixContext.MIN_ALTERNATIVES_SEARCH.toMillis(), ctx.remainingMillis(),
                "a quick first repair leaves the minimum search for alternatives, not the rest of the request");
    }

    @Test
    void alternativesGetAsLongAgainAsTheFirstRepairTook() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(300));
        ctx.beginStrategy("condition", 1);
        clock.advance(Duration.ofSeconds(40));

        ctx.startAlternativesSearch();
        assertEquals(40_000, ctx.remainingMillis());
    }

    @Test
    void alternativesNeverTakeMoreThanAnEqualShareOfTheRemainingTime() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(90));
        ctx.beginStrategy("parameter", 3);
        clock.advance(Duration.ofSeconds(30));

        ctx.startAlternativesSearch();
        assertEquals(20_000, ctx.remainingMillis(), "a third of the 60 s left, although the repair took 30 s");
    }

    @Test
    void theMinimumSearchDoesNotOverrideTheShare() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(4));
        ctx.beginStrategy("parameter", 2);

        ctx.startAlternativesSearch();
        assertEquals(2_000, ctx.remainingMillis());
    }

    @Test
    void aQuickVerifiedRepairReportsComparisonOnceAndDoesNotAddTenSeconds() {
        java.util.concurrent.atomic.AtomicInteger reports = new java.util.concurrent.atomic.AtomicInteger();
        FixContext ctx = FixContext.builder().clock(clock).deadline(clock.instant().plusSeconds(60))
                .onVerifiedRepair(reports::incrementAndGet).build();
        ctx.beginStrategy("condition", 1);
        assertEquals(0, reports.get());
        clock.advance(Duration.ofSeconds(1));
        ctx.startAlternativesSearch();
        assertEquals(1, reports.get());
        assertEquals(3_000, ctx.remainingMillis());
        clock.advance(Duration.ofSeconds(1));
        ctx.startAlternativesSearch();
        assertEquals(1, reports.get());
        assertEquals(2_000, ctx.remainingMillis());
    }

    @Test
    void aLaterRepairDoesNotRestartTheWindow() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(300));
        ctx.beginStrategy("parameter", 1);
        ctx.startAlternativesSearch();
        clock.advance(FixContext.MIN_ALTERNATIVES_SEARCH.plusMillis(1));
        assertTrue(ctx.isExpired(), "the window is over");

        ctx.startAlternativesSearch();
        assertTrue(ctx.isExpired(), "a later repair of the same strategy must not open a new window");
    }

    @Test
    void theNextStrategyStartsWithTheWholeRemainingTime() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(60));
        ctx.beginStrategy("parameter", 2);
        ctx.startAlternativesSearch();
        clock.advance(Duration.ofSeconds(11));
        assertTrue(ctx.isExpired(), "the window is over");

        ctx.beginStrategy("condition", 1);
        assertFalse(ctx.isExpired(), "the request deadline has not passed");
        assertEquals(49_000, ctx.remainingMillis());
    }

    @Test
    void withoutARequestDeadlineThereIsNothingToShare() {
        FixContext ctx = FixContext.builder().clock(clock).build();
        ctx.beginStrategy("parameter", 3);
        ctx.startAlternativesSearch();

        assertFalse(ctx.isExpired());
        assertEquals(Long.MAX_VALUE, ctx.remainingMillis());
    }

    @Test
    void aSmallFiniteSearchGetsTimeToFinishInsteadOfTheShortComparisonWindow() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(300));
        ctx.beginStrategy("condition", 1);
        ctx.initializeStrategySearch("condition", 20, List.of(2L, 2L));
        clock.advance(Duration.ofSeconds(2));
        ctx.startAlternativesSearch();

        assertTrue(ctx.prefersCompleteSearch());
        assertEquals(28_000, ctx.remainingMillis());
        clock.advance(Duration.ofSeconds(28).plusMillis(1));
        assertTrue(ctx.isExpired());
    }

    @Test
    void cheapFiniteEnumerationMayFinishBeyondTheNominalAttemptCount() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(300));
        ctx.beginStrategy("remove", 1);
        ctx.initializeStrategySearch("remove", 3, List.of(2L, 2L, 2L));
        ctx.addStrategyAttempts("remove", 3);
        assertTrue(ctx.hasStrategyAttemptsLeft("remove"));

        clock.advance(FixContext.SMALL_SEARCH_TIME);
        assertFalse(ctx.hasStrategyAttemptsLeft("remove"));
        assertEquals(3, ctx.strategySearchProgress("remove").attemptLimit());
    }

    @Test
    void freeValueDomainsAndOverflowCannotMakeALargeSearchLookSmall() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(300));
        ctx.beginStrategy("condition", 1);
        ctx.initializeStrategySearch("condition", 20, List.of(2L, 2L, 101L));
        assertFalse(ctx.prefersCompleteSearch());
        assertEquals(20, ctx.strategySearchProgress("condition").attemptLimit());

        ctx.initializeStrategySearch("parameter", 20, List.of(4_294_967_296L, 4_294_967_296L));
        assertFalse(ctx.prefersCompleteSearch());
        assertThrows(IllegalArgumentException.class,
                () -> ctx.initializeStrategySearch("condition", 20, List.of(0L)));
    }

    @Test
    void anExtraFiniteSearchAttemptAlsoBoundsTheRunningSolverAndKeepsComparisonFeedback() {
        java.util.concurrent.atomic.AtomicInteger reports = new java.util.concurrent.atomic.AtomicInteger();
        FixContext ctx = FixContext.builder().clock(clock).deadline(clock.instant().plusSeconds(300))
                .onVerifiedRepair(reports::incrementAndGet).build();
        ctx.beginStrategy("condition", 1);
        ctx.initializeStrategySearch("condition", 2, List.of(2L, 2L));
        ctx.addStrategyAttempts("condition", 2);
        assertEquals(300_000, ctx.remainingMillis(), "nominal discovery keeps its original budget");
        clock.advance(Duration.ofSeconds(29));
        assertTrue(ctx.hasStrategyAttemptsLeft("condition"));
        ctx.addStrategyAttempts("condition", 1);
        assertEquals(1_000, ctx.remainingMillis(), "the child solver cannot spend more than the small-search remainder");

        ctx.startAlternativesSearch();
        assertEquals(1, reports.get(), "an extra attempt's deadline must not hide the first verified-repair feedback");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(ctx.isExpired());
    }

    @Test
    void completeEnumerationStillSharesTimeAndResetsForTheNextStrategy() {
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(12));
        ctx.beginStrategy("parameter", 3);
        ctx.initializeStrategySearch("parameter", 20, List.of(11L));
        ctx.startAlternativesSearch();
        assertEquals(4_000, ctx.remainingMillis());

        ctx.beginStrategy("condition", 2);
        assertFalse(ctx.prefersCompleteSearch());
        assertEquals(12_000, ctx.remainingMillis());
    }

    @Test
    void aFiniteSearchWithoutADeadlineKeepsItsAttemptLimit() {
        FixContext ctx = FixContext.builder().clock(clock).build();
        ctx.initializeStrategySearch("remove", 3, List.of(2L, 2L));
        assertFalse(ctx.prefersCompleteSearch());
        assertEquals(3, ctx.strategySearchProgress("remove").attemptLimit());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
