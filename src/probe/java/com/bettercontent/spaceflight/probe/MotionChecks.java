package com.bettercontent.spaceflight.probe;

/** Pure nonshipping assertions. No actuators, clamping or physics implementation. */
public final class MotionChecks {
    private MotionChecks() {}
    public record Frame(int tick, double x, double y, double z, double vx, double vy, double vz) {
        public Frame {
            if (tick < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)) {
                throw new IllegalArgumentException("Nonfinite/invalid native motion sample");
            }
        }
    }

    public static void requireGravity(Frame start, Frame end) {
        elapsed(start, end);
        require(Math.abs(start.y() - 384) < 0.01, "Gravity control did not start at Y384");
        require(end.y() < start.y() - 0.25 && end.vy() < -0.5,
            "Unanchored Overworld native body did not fall: " + end);
        require(end.y() > 128, "Gravity control hit low terrain instead of sampling high altitude");
    }

    public static void requireCoast(Frame start, Frame end) {
        double seconds = elapsed(start, end) / 20.0;
        require(Math.abs(start.y() - 384) < 0.01, "Coast did not start at Y384");
        require(Math.abs(start.vx() - 10) < 0.001 && Math.abs(start.vy()) < 0.001 && Math.abs(start.vz()) < 0.001,
            "Coast initial condition differs from commanded 10,0,0");
        require(Math.abs(end.vx() - 10) <= 0.05 && Math.abs(end.vy()) <= 0.05 && Math.abs(end.vz()) <= 0.05,
            "Coast velocity changed (drag/gravity or missing integration): " + end);
        require(Math.abs(end.y() - start.y()) <= 0.25 && Math.abs(end.z() - start.z()) <= 0.25,
            "Coast drifted vertically/laterally: " + end);
        double expected = 10 * seconds;
        // Native frames arrive asynchronously: allow 0.5 block plus 15% of tick-derived travel.
        // Velocity and cross-axis oracles above remain tight; frozen transforms cannot pass.
        require(Math.abs((end.x() - start.x()) - expected) <= 0.5 + expected * 0.15,
            "Native position did not integrate expected coast travel: " + end + "; expected dx=" + expected);
    }

    private static int elapsed(Frame start, Frame end) {
        int ticks = end.tick() - start.tick();
        require(ticks >= 20, "Need at least 20 independent game ticks of native motion");
        return ticks;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
