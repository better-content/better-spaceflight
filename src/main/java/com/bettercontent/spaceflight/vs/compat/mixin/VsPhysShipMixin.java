package com.bettercontent.spaceflight.vs.compat.mixin;

import com.bettercontent.spaceflight.vs.compat.internal.PinnedVs2411Bridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.impl.game.ships.PhysShipImpl;

/** Prevent third-party physics callbacks from making a reserved body dynamic again. */
@Mixin(value = PhysShipImpl.class, remap = false)
public abstract class VsPhysShipMixin {
    @Inject(method = "setStatic(Z)V", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void spaceflight$keepReservedStatic(boolean requested, CallbackInfo ci) {
        if (!PinnedVs2411Bridge.allowStaticChange((PhysShipImpl) (Object) this, requested)) ci.cancel();
    }
}
