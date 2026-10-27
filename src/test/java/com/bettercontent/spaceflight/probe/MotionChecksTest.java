package com.bettercontent.spaceflight.probe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MotionChecksTest {
    private static MotionChecks.Frame start() { return new MotionChecks.Frame(140, 0, 384, 0, 10, 0, 0); }

    @Test void acceptsIntegratedCoastAtAllNinePlannedSamples() {
        for (int tick = 160; tick <= 240; tick += 10) {
            MotionChecks.requireCoast(start(), new MotionChecks.Frame(tick, (tick - 140) / 2.0, 384, 0, 10, 0, 0));
        }
    }

    @Test void rejectsFrozenTransformsEvenWhenVelocityClaimsMotion() {
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireCoast(start(), new MotionChecks.Frame(240, 0, 384, 0, 10, 0, 0)));
    }

    @Test void rejectsDragInsteadOfTreatingTravelAsEnough() {
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireCoast(start(), new MotionChecks.Frame(240, 50, 384, 0, 9.8, 0, 0)));
    }

    @Test void rejectsGravityAndCrossAxisDrift() {
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireCoast(start(), new MotionChecks.Frame(240, 50, 384, 0, 10, -0.2, 0)));
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireCoast(start(), new MotionChecks.Frame(240, 50, 383, 0, 10, 0, 0)));
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireCoast(start(), new MotionChecks.Frame(240, 50, 384, 1, 10, 0, 0)));
    }

    @Test void acceptsRealHighAltitudeGravityAndRejectsStaticControl() {
        var source = new MotionChecks.Frame(50, 0, 384, 0, 0, 0, 0);
        MotionChecks.requireGravity(source, new MotionChecks.Frame(90, 0, 364, 0, 0, -19.6, 0));
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireGravity(new MotionChecks.Frame(50, 0, 500, 0, 0, 0, 0),
                new MotionChecks.Frame(90, 0, 480, 0, 0, -19.6, 0)));
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireGravity(source, new MotionChecks.Frame(90, 0, 384, 0, 0, 0, 0)));
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireGravity(source, new MotionChecks.Frame(90, 0, 100, 0, 0, -19.6, 0)));
    }

    @Test void rejectsInsufficientTimeAndWrongInitialConditions() {
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireCoast(start(), new MotionChecks.Frame(141, 0.5, 384, 0, 10, 0, 0)));
        assertThrows(IllegalStateException.class,
            () -> MotionChecks.requireCoast(new MotionChecks.Frame(140, 0, 384, 0, 0, 0, 0),
                new MotionChecks.Frame(240, 50, 384, 0, 10, 0, 0)));
    }

    @Test void rejectsNonfiniteAndInvalidSamples() {
        assertThrows(IllegalArgumentException.class, () -> new MotionChecks.Frame(-1, 0, 384, 0, 10, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MotionChecks.Frame(0, Double.NaN, 384, 0, 10, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MotionChecks.Frame(0, 0, 384, 0, Double.POSITIVE_INFINITY, 0, 0));
    }

    @Test void selectorNeverSilentlyFallsBackOrRegistersBothGates() {
        assertEquals(ProbeGate.STATIC, ProbeGate.parse("static"));
        assertEquals(ProbeGate.DYNAMIC, ProbeGate.parse("dynamic"));
        for (String invalid : new String[] {null, "", "both", "STATIC", "unknown"}) {
            assertThrows(IllegalArgumentException.class, () -> ProbeGate.parse(invalid));
        }
    }
}
