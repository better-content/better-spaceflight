package com.bettercontent.spaceflight.probe;

import com.mojang.logging.LogUtils;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;

/** Only the explicitly active dynamic GameTest; no global server timing or physics patch. */
final class ProbeClock {
    private static ServerPlayer owner;
    private static TickPacer pacer;
    private ProbeClock() {}

    static void begin(ServerPlayer observer) {
        if (!(observer.getServer() instanceof GameTestServer) || owner != null) {
            throw new IllegalStateException("Probe clock requires one fresh GameTest observer");
        }
        owner = observer;
        pacer = new TickPacer();
        LogUtils.getLogger().info("SPACEFLIGHT_CLOCK_BEGIN periodNanos={} scope=DYNAMIC_GAMETEST_ONLY", TickPacer.PERIOD_NANOS);
    }

    static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START && owner != null && event.getServer() == owner.getServer()) {
            pacer.awaitTick();
        }
    }

    record Frame(long monotonicNanos, TickPacer.Sample cadence) {}
    static Frame frame() {
        if (owner == null) throw new IllegalStateException("Dynamic fixture clock is not active");
        return new Frame(System.nanoTime(), pacer.sample());
    }

    static void end(ServerPlayer observer) {
        if (owner == observer) {
            LogUtils.getLogger().info("SPACEFLIGHT_CLOCK_END {}", frame());
            owner = null;
            pacer = null;
        }
    }
}
