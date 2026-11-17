package com.bettercontent.spaceflight.vs.compat.mixin;

import com.bettercontent.spaceflight.vs.compat.internal.PinnedVs2411Bridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.impl.shadow.FG;
import org.valkyrienskies.core.impl.shadow.FL;
import org.valkyrienskies.core.impl.shadow.FN;
import org.valkyrienskies.core.impl.shadow.FU;

/** FN.a drains frames before physics events/stepping; FN.b creates before removes. */
@Mixin(value = FN.class, remap = false)
public abstract class VsFnMixin {
    @Inject(method = "a(DZ)Lorg/valkyrienskies/core/impl/shadow/FL;",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;values()Ljava/util/Collection;", ordinal = 0,
                 remap = false), require = 1, remap = false)
    private void spaceflight$boundary(double dt, boolean flag, CallbackInfoReturnable<FL> cir) {
        PinnedVs2411Bridge.onPhysicsBoundary((FN) (Object) this);
    }

    @Inject(method = "b(Lorg/valkyrienskies/core/impl/shadow/FG;)V", at = @At("HEAD"),
        require = 1, remap = false)
    private void spaceflight$beforeFrame(FG frame, CallbackInfo ci) {
        PinnedVs2411Bridge.beforeFrame((FN) (Object) this, frame);
    }

    @Redirect(method = "b(Lorg/valkyrienskies/core/impl/shadow/FG;)V",
        at = @At(value = "INVOKE", target = "Lorg/valkyrienskies/core/impl/shadow/FU;removeJoint(I)Z",
                 remap = false), require = 1, remap = false)
    private boolean spaceflight$scopedNativeRemoval(FU level, int id, FG frame) {
        return PinnedVs2411Bridge.removeFromFrame((FN) (Object) this, frame, level, id);
    }

    @Inject(method = "b(Lorg/valkyrienskies/core/impl/shadow/FG;)V", at = @At("RETURN"),
        require = 1, remap = false)
    private void spaceflight$afterFrame(FG frame, CallbackInfo ci) {
        PinnedVs2411Bridge.afterFrame((FN) (Object) this);
    }
}
