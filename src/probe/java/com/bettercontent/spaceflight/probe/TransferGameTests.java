package com.bettercontent.spaceflight.probe;

import com.bettercontent.spaceflight.vs.Vs2411Adapter;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

/** Native server-side sub-gate only. Does not claim player, rendering, physics or process-restart proof. */
@PrefixGameTestTemplate(false)
public final class TransferGameTests {
    private TransferGameTests() {}

    @GameTest(templateNamespace = ProbeMod.ID, template = "empty", timeoutTicks = 300)
    public static void completeBlockStateAndInventoryRoundTrip(GameTestHelper helper) {
        ServerLevel home = helper.getLevel();
        helper.assertTrue(Vs2411Adapter.nativePhysicsForProbe(), "VS is using dummy physics; native sub-gate cannot pass");
        ServerLevel space = home.getServer().getLevel(ProbeRig.SPACE);
        helper.assertTrue(space != null, "Probe space dimension was not registered");
        ProbeRig rig = ProbeRig.create(home, helper.absolutePos(new BlockPos(2, 3, 2)));
        helper.runAtTickTime(20, () -> {
            // Reject a known unequal vertical chunk layout before any destructive native call.
            ServerLevel nether = home.getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "Negative-control Nether is missing");
            boolean rejected = false;
            try { Vs2411Adapter.teleportForProbe(home, nether, rig.ship(home.getServer()), new Vector3d(0, 80, 0)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Unequal vertical chunk layout must fail before copying");
            rig.assertState(home.getServer());
            rig.hop(space, new Vector3d(0, 160, 0));
        });
        helper.runAtTickTime(60, () -> {
            rig.assertState(home.getServer());
            rig.hop(home, new Vector3d(rig.homePosition));
        });
        helper.runAtTickTime(100, () -> {
            rig.assertState(home.getServer());
            Vs2411Adapter.teleportForProbe(home, home, rig.ship(home.getServer()), new Vector3d(0, 384, 0));
        });
        helper.runAtTickTime(140, () -> {
            var ship = rig.ship(home.getServer());
            helper.assertTrue(Math.abs(ship.getTransform().getPositionInWorld().y() - 384) < 0.01,
                "Static native ship transform did not retain Y384");
            rig.assertState(home.getServer());
            ProbeRig recovered = ProbeRig.load(rig.save(), home);
            helper.assertTrue(recovered.shipId == rig.shipId && recovered.hops == 2, "Diagnostic record roundtrip changed identity/history");
            recovered.assertState(home.getServer());
            com.mojang.logging.LogUtils.getLogger().info(
                "SPACEFLIGHT_NATIVE_SUBGATE ship={} hops={} blocks={} blockEntities={}; static Y384 and NBT serialization only; player/client/physics/process restart NOT_EVALUATED",
                rig.shipId, rig.hops, rig.states.size(), rig.entities.size());
            helper.succeed();
        });
    }
}
