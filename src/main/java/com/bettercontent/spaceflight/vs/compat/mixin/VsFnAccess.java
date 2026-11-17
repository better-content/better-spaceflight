package com.bettercontent.spaceflight.vs.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.valkyrienskies.core.impl.shadow.FN;
import org.valkyrienskies.core.impl.shadow.FU;

import java.util.Map;

/** Exact VS 2.4.11 shadow field accessor; no reflective field guessing. */
@Mixin(value = FN.class, remap = false)
public interface VsFnAccess {
    @Accessor(value = "u", remap = false)
    Map<String, FU> spaceflight$levels();
}
