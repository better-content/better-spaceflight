package com.bettercontent.spaceflight.vs.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.valkyrienskies.core.impl.shadow.Ek;
import org.valkyrienskies.core.impl.shadow.FU;

/** Pinned persisted joint ID map; used for post-restore membership/allocator checks. */
@Mixin(value = FU.class, remap = false)
public interface VsFuAccess {
    @Accessor(value = "j", remap = false)
    Ek spaceflight$persisted();
}
