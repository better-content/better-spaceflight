package com.bettercontent.spaceflight.probe;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TickPacerTest {
    private static final long P = TickPacer.PERIOD_NANOS;
    private static final class Clock {
        long now;
        int spurious;
        List<Long> waits = new ArrayList<>();
        TickPacer pacer() { return new TickPacer(() -> now, delay -> {
            waits.add(delay);
            if (spurious-- <= 0) now += delay;
        }); }
    }

    @Test void firstTickDoesNotWait() {
        Clock c = new Clock(); c.now = 123;
        var s = c.pacer().awaitTick();
        assertEquals(1, s.ticks()); assertEquals(0, s.elapsedNanos()); assertTrue(c.waits.isEmpty());
    }
    @Test void earlyTickWaitsOnlyRemainingPeriod() {
        Clock c = new Clock(); var p = c.pacer(); p.awaitTick(); c.now += 10_000_000;
        var s = p.awaitTick();
        assertEquals(List.of(40_000_000L), c.waits); assertEquals(P, s.intervalNanos());
    }
    @Test void exactDeadlineDoesNotPark() {
        Clock c = new Clock(); var p = c.pacer(); p.awaitTick(); c.now += P;
        assertEquals(P, p.awaitTick().intervalNanos()); assertTrue(c.waits.isEmpty());
    }
    @Test void lateTickDoesNotPermitFastCatchup() {
        Clock c = new Clock(); var p = c.pacer(); p.awaitTick(); c.now += 3 * P;
        assertEquals(3 * P, p.awaitTick().intervalNanos()); c.now += 5;
        assertEquals(P, p.awaitTick().intervalNanos()); assertEquals(List.of(P - 5), c.waits);
    }
    @Test void spuriousWakeupsCannotAccelerateClock() {
        Clock c = new Clock(); var p = c.pacer(); p.awaitTick(); c.spurious = 2;
        assertEquals(P, p.awaitTick().intervalNanos()); assertEquals(List.of(P, P, P), c.waits);
    }
    @Test void signedNanoTimeWrapIsValid() {
        Clock c = new Clock(); c.now = Long.MAX_VALUE - 10;
        var p = c.pacer(); p.awaitTick(); c.now += P;
        assertEquals(P, p.awaitTick().intervalNanos()); assertTrue(c.waits.isEmpty());
    }
    @Test void backwardsClockFailsClosed() {
        Clock c = new Clock(); c.now = 100;
        var p = c.pacer(); p.awaitTick(); c.now--;
        assertThrows(IllegalStateException.class, p::awaitTick);
    }
    @Test void interruptionIsNotSwallowed() {
        Clock c = new Clock(); var p = c.pacer();
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, p::awaitTick);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(0, p.sample().ticks());
    }
    @Test void reportsActualIntervalsAndElapsedNotIdealizedTime() {
        Clock c = new Clock(); var p = c.pacer(); p.awaitTick(); p.awaitTick(); c.now += 2 * P;
        var s = p.awaitTick();
        assertEquals(3, s.ticks()); assertEquals(3 * P, s.elapsedNanos());
        assertEquals(P, s.minimumIntervalNanos()); assertEquals(2 * P, s.maximumIntervalNanos());
    }
}
