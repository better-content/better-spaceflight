package com.bettercontent.spaceflight.vs.compat.mixin;

import com.bettercontent.spaceflight.vs.compat.internal.FrameNonce;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.valkyrienskies.core.impl.shadow.FG;

import java.util.UUID;

@Mixin(value = FG.class, remap = false)
public abstract class VsFrameMixin implements FrameNonce {
    @Unique private UUID spaceflight$handoffNonce;
    @Override public UUID spaceflight$nonce() { return spaceflight$handoffNonce; }
    @Override public void spaceflight$nonce(UUID value) {
        if (value == null || spaceflight$handoffNonce != null) throw new IllegalStateException("Duplicate/absent frame nonce");
        spaceflight$handoffNonce = value;
    }
}
