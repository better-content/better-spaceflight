package com.bettercontent.spaceflight.probe;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Probe-only wall-clock pacing; never reads or changes a craft's state. */
public final class TickPacer {
    public static final long PERIOD_NANOS = 50_000_000L;
    private final LongSupplier clock;
    private final LongConsumer park;
    private long ticks, first, last, interval, minimum = Long.MAX_VALUE, maximum;

    public TickPacer() { this(System::nanoTime, LockSupport::parkNanos); }
    TickPacer(LongSupplier clock, LongConsumer park) {
        this.clock = Objects.requireNonNull(clock);
        this.park = Objects.requireNonNull(park);
    }

    public record Sample(long ticks, long elapsedNanos, long intervalNanos,
                         long minimumIntervalNanos, long maximumIntervalNanos) {}

    public Sample awaitTick() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Interrupted probe clock");
        long now = clock.getAsLong();
        if (ticks == 0) first = now;
        else {
            long delta = now - last; // System.nanoTime subtraction tolerates signed wrap.
            if (delta < 0) throw new IllegalStateException("Probe monotonic clock went backwards");
            while (delta < PERIOD_NANOS) {
                park.accept(PERIOD_NANOS - delta);
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Interrupted probe clock");
                now = clock.getAsLong();
                delta = now - last;
                if (delta < 0) throw new IllegalStateException("Probe monotonic clock went backwards");
            }
            interval = delta;
            minimum = Math.min(minimum, delta);
            maximum = Math.max(maximum, delta);
        }
        // A late tick starts a new period: never catch up by accelerating later ticks.
        last = now;
        ticks++;
        return sample();
    }

    public Sample sample() {
        return new Sample(ticks, ticks == 0 ? 0 : last - first, interval,
            ticks < 2 ? 0 : minimum, maximum);
    }
}
