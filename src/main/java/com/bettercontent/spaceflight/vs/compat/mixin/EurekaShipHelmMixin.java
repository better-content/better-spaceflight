package com.bettercontent.spaceflight.vs.compat.mixin;

import com.bettercontent.spaceflight.vs.compat.internal.EurekaHelmPermit;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dormant, exact-artifact Eureka helm guards; menu and tick both call these methods. */
@Mixin(targets = "org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity", remap = false)
public abstract class EurekaShipHelmMixin {
    @Inject(method = "disassemble()V", at = @At("HEAD"), require = 1, remap = false, cancellable = true)
    private void spaceflight$denyDisassembly(CallbackInfo ci) {
        if (EurekaHelmPermit.deny((BlockEntity) (Object) this)) ci.cancel();
    }
    @Inject(method = "align()V", at = @At("HEAD"), require = 1, remap = false, cancellable = true)
    private void spaceflight$denyAlignment(CallbackInfo ci) {
        if (EurekaHelmPermit.deny((BlockEntity) (Object) this)) ci.cancel();
    }
}
