package com.bettercontent.spaceflight.vs.compat.mixin;

import com.bettercontent.spaceflight.vs.compat.internal.PinnedVs2411Bridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.valkyrienskies.core.impl.shadow.FG;
import org.valkyrienskies.core.impl.shadow.FH;
import org.valkyrienskies.core.impl.shadow.FQ;

/** FQ.postTickGame owns the producer FH and exact destination FN; tag BEFORE enqueue. */
@Mixin(value = FQ.class, remap = false)
public abstract class VsFqMixin {
    @Redirect(method = "postTickGame()V", at = @At(value = "INVOKE",
        target = "Lorg/valkyrienskies/core/impl/shadow/FH;b()Lorg/valkyrienskies/core/impl/shadow/FG;",
        remap = false), require = 1, remap = false)
    private FG spaceflight$tagBeforeEnqueue(FH producer) {
        FG frame = producer.b();
        PinnedVs2411Bridge.onGameFrame(((VsFqAccess) this).spaceflight$physics(), frame);
        return frame;
    }
}
