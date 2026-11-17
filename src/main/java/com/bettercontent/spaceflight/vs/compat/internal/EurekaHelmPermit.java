package com.bettercontent.spaceflight.vs.compat.internal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/** Game-thread exact-owner guard for Eureka helm menu/tick mutation entrypoints. */
public final class EurekaHelmPermit {
    private EurekaHelmPermit() {}
    public static boolean deny(BlockEntity helm) {
        if (!(helm.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
        // This source-only policy never issues a reservation. If a future caller
        // does, the actual loaded owner and precise dimension must match; no
        // bare ID across servers/dimensions or client positions are authoritative.
        var ship = VSGameUtilsKt.getLoadedShipManagingPos(level, helm.getBlockPos());
        return ship != null && ship.getChunkClaimDimension().equals(VSGameUtilsKt.getDimensionId(level))
            && PinnedVs2411Bridge.denyHeldHelmMutation(level.getServer(), ship.getChunkClaimDimension(), ship.getId());
    }
}
