package com.bettercontent.spaceflight.probe;

import com.bettercontent.spaceflight.vs.Vs2411Adapter;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

/** Additive, separately selected native gate. Synthetic observer is NOT real-client proof. */
@PrefixGameTestTemplate(false)
public final class DynamicGameTests {
    private DynamicGameTests() {}

    @GameTest(templateNamespace = ProbeMod.ID, template = "empty", timeoutTicks = 360)
    public static void loadedNativeGravityAndSpaceCoastRoundTrip(GameTestHelper helper) {
        ServerLevel home = helper.getLevel();
        helper.assertTrue(Vs2411Adapter.nativePhysicsForProbe(), "Native physics is unavailable");
        ServerLevel space = home.getServer().getLevel(ProbeRig.SPACE);
        helper.assertTrue(space != null, "Probe space dimension is missing");
        helper.assertTrue(home.getServer().getPlayerList().getPlayers().isEmpty(),
            "Disposable dynamic fixture refuses existing players");
        // The vanilla helper's unbound Connection fails Forge's login filter before registration.
        // This still uses normal PlayerList admission; its bound packet sink is NOT a client.
        ServerPlayer observer = ProbeObserver.open(home);
        try {
            ProbeClock.begin(observer);
            BlockPos origin = helper.absolutePos(new BlockPos(2, 3, 2));
            observer.teleportTo(home, origin.getX() + 4, origin.getY() + 5, origin.getZ() + 4, 0, 0);
            ProbeRig rig = ProbeRig.create(home, origin);
            MotionChecks.Frame[] gravity = new MotionChecks.Frame[1];
            MotionChecks.Frame[] coast = new MotionChecks.Frame[1];
            int[] coastSamples = {0};
            at(helper, observer, 10, () -> {
                Vs2411Adapter.setProbeState(home, home, rig.ship(home.getServer()),
                    new Vector3d(0, 384, 0), new Vector3d(), new Vector3d());
                observer.teleportTo(home, 4, 389, 4, 0, 0);
            });
            at(helper, observer, 50, () -> {
                requireLoaded(helper, rig, home, observer);
                var ship = rig.ship(home.getServer());
                ship.setStatic(false);
                Vs2411Adapter.setProbeState(home, home, ship, new Vector3d(0, 384, 0), new Vector3d(), new Vector3d());
                gravity[0] = frame(rig, home, 50);
            });
            at(helper, observer, 90, () -> {
                requireLoaded(helper, rig, home, observer);
                helper.assertTrue(!rig.ship(home.getServer()).isStatic(), "Gravity body was anchored");
                MotionChecks.requireGravity(gravity[0], frame(rig, home, 90));
                rig.ship(home.getServer()).setStatic(true);
                rig.hop(space, new Vector3d(0, 384, 0));
                observer.teleportTo(space, 4, 389, 4, 0, 0);
            });
            at(helper, observer, 140, () -> {
                requireLoaded(helper, rig, space, observer);
                var ship = rig.ship(home.getServer());
                ship.setStatic(false);
                // Initial conditions ONLY. No subsequent velocity/position clamp during coast.
                Vs2411Adapter.setProbeState(space, space, ship, new Vector3d(0, 384, 0),
                    new Vector3d(10, 0, 0), new Vector3d());
                coast[0] = frame(rig, space, 140);
            });
            for (int tick = 160; tick <= 240; tick += 10) {
                int sampleTick = tick;
                at(helper, observer, tick, () -> {
                    requireLoaded(helper, rig, space, observer);
                    helper.assertTrue(!rig.ship(home.getServer()).isStatic(), "Coast body was anchored");
                    MotionChecks.requireCoast(coast[0], frame(rig, space, sampleTick));
                    coastSamples[0]++;
                });
            }
            at(helper, observer, 250, () -> {
                rig.assertState(home.getServer());
                rig.ship(home.getServer()).setStatic(true);
                rig.hop(home, new Vector3d(0, 384, 0));
                observer.teleportTo(home, 4, 389, 4, 0, 0);
            });
            at(helper, observer, 300, () -> {
                requireLoaded(helper, rig, home, observer);
                helper.assertTrue(rig.hops == 2, "Dynamic round trip changed hop history");
                helper.assertTrue(coastSamples[0] == 9, "Missing independent native coast samples");
                helper.assertTrue(Math.abs(rig.ship(home.getServer()).getTransform().getPositionInWorld().y() - 384) < 0.01,
                    "Returned anchored ship lost Y384");
                close(observer);
                helper.assertTrue(home.getServer().getPlayerList().getPlayers().isEmpty(), "Synthetic observer leaked");
                LogUtils.getLogger().info("SPACEFLIGHT_DYNAMIC_SUBGATE ship={} hops={} blocks={} blockEntities={} coastSamples=9 observer=BOUND_SYNTHETIC_SERVER_PLAYER; packet-delivery/real-client/passenger/restart NOT_EVALUATED",
                    rig.shipId, rig.hops, rig.states.size(), rig.entities.size());
                helper.succeed();
            });
        } catch (RuntimeException | Error failure) {
            close(observer);
            throw failure;
        }
    }

    private static void requireLoaded(GameTestHelper helper, ProbeRig rig, ServerLevel level, ServerPlayer observer) {
        ProbeObserver.requireActive(observer);
        helper.assertTrue(level.getServer().getPlayerList().getPlayers().contains(observer) && !observer.isRemoved(),
            "Synthetic server observer left the player registry");
        helper.assertTrue(observer.serverLevel() == level && rig.dimension().equals(level.dimension()),
            "Observer/ship dimension diverged");
        helper.assertTrue(Vs2411Adapter.loadedForProbe(level, rig.shipId), "Ship did not enter native loaded registry in " + level.dimension().location());
        rig.assertState(level.getServer());
    }

    private static MotionChecks.Frame frame(ProbeRig rig, ServerLevel level, int tick) {
        var ship = rig.ship(level.getServer());
        var p = ship.getTransform().getPositionInWorld();
        var v = ship.getVelocity();
        var sample = new MotionChecks.Frame(tick, p.x(), p.y(), p.z(), v.x(), v.y(), v.z());
        LogUtils.getLogger().info("SPACEFLIGHT_DYNAMIC_SAMPLE ship={} dimension={} loaded={} static={} {} clock={} loadedView={}",
            rig.shipId, level.dimension().location(), Vs2411Adapter.loadedForProbe(level, rig.shipId), ship.isStatic(), sample,
            ProbeClock.frame(), Vs2411Adapter.loadedStateForProbe(level, rig.shipId));
        return sample;
    }

    private static void at(GameTestHelper helper, ServerPlayer observer, int tick, Runnable action) {
        helper.runAtTickTime(tick, () -> {
            try { action.run(); }
            catch (RuntimeException | Error failure) {
                LogUtils.getLogger().error("SPACEFLIGHT_DYNAMIC_FAILURE tick=" + tick, failure);
                try { close(observer); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        });
    }

    private static void close(ServerPlayer observer) {
        ProbeObserver.close(observer);
    }
}
