package cn.edu.nju.Iot_Verify.component.nusmv.fixer;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Once a strategy has a verified repair, looking for alternatives costs about as long again as that repair
 * took and never more than an equal share of the time left, so it neither keeps the user waiting long
 * after an answer exists nor starves the strategies after it.
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
        FixContext ctx = contextWithDeadlineIn(Duration.ofSeconds(8));
        ctx.beginStrategy("parameter", 2);

        ctx.startAlternativesSearch();
        assertEquals(4_000, ctx.remainingMillis());
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
