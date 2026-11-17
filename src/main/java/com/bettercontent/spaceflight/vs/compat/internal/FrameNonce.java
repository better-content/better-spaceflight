package com.bettercontent.spaceflight.vs.compat.internal;

import java.util.UUID;

/** Private Mixin attachment on the EXACT queued FG object. Not a Spaceflight public API. */
public interface FrameNonce {
    UUID spaceflight$nonce();
    void spaceflight$nonce(UUID value);
}
