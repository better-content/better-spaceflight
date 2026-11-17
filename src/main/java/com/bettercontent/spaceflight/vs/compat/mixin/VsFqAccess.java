package com.bettercontent.spaceflight.vs.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.valkyrienskies.core.impl.shadow.FN;
import org.valkyrienskies.core.impl.shadow.FQ;

/** Pinned FQ.b is the exact physics owner of this producer's frame. */
@Mixin(value = FQ.class, remap = false)
public interface VsFqAccess {
    @Accessor(value = "b", remap = false)
    FN spaceflight$physics();

    /** FQ.h is the actual VS-owned Thread; !isAlive after observed start proves its loop ended,
     * not that FN.j's asynchronous native resource-close coroutine has completed. */
    @Accessor(value = "h", remap = false)
    Thread spaceflight$physicsThread();
}
