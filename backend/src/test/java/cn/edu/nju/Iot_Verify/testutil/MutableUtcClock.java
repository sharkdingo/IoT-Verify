package cn.edu.nju.Iot_Verify.testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A test clock with an explicitly advanced UTC instant. */
public final class MutableUtcClock extends Clock {
    private Instant instant;

    public MutableUtcClock(Instant instant) {
        this.instant = instant;
    }

    public void advance(Duration duration) {
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
