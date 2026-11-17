package com.bettercontent.spaceflight.vs.compat.mixin;

import com.bettercontent.spaceflight.vs.compat.internal.PinnedVs2411Bridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.world.PhysLevel;

/** Dormant optional exact-Eureka physTick seal; never changes unrelated craft. */
@Mixin(targets = "org.valkyrienskies.eureka.ship.EurekaShipControl", remap = false)
public abstract class EurekaShipControlMixin {
    @Inject(method = "physTick(Lorg/valkyrienskies/core/api/ships/PhysShip;"
        + "Lorg/valkyrienskies/core/api/world/PhysLevel;)V", at = @At("HEAD"),
        require = 1, remap = false, cancellable = true)
    private void spaceflight$holdPhysicsCallback(PhysShip ship, PhysLevel level, CallbackInfo ci) {
        if (PinnedVs2411Bridge.suspendEurekaPhysTick(this, ship)) ci.cancel();
    }
}
