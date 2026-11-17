package com.bettercontent.spaceflight.vs.compat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Structural source contract only; no Forge/Minecraft lifecycle is executed. */
final class ForgeOwnerContractTest {
    @Test void defaultOffStartupScansBeforeAnyNativeBindingAndHasNoReadyState() throws Exception {
        var owner = Files.readString(Path.of("src/main/java/com/bettercontent/spaceflight/vs/compat/internal/ForgeVsServerOwner.java"));
        assertTrue(owner.indexOf("ServerOwnerPreflight.scan(server.getWorldPath(LevelResource.ROOT))")
            < owner.indexOf("PinnedVs2411Bridge.bindReviewedWorld(physics, registry, owner.server, thread)"));
        assertTrue(owner.contains("if (owner == null || owner.stopping || owner.state != State.WAITING_FOR_PHYSICS_HOOK) return"));
        assertTrue(owner.contains("hasPhysicsHeartbeat(physics, thread)"));
        assertTrue(owner.contains("thread.isAlive()"));
        assertTrue(owner.contains("owner.registry.shutdown()"));
        assertTrue(owner.contains("PhysicsThreadStopProof.awaitFromWatcher"));
        assertFalse(owner.contains("READY_TO_TRANSFER"));
        assertFalse(owner.contains("teleportShip("));
    }
    @Test void exactVsPhysicsThreadAccessorAndWeakThreadHeartbeatExist() throws Exception {
        var accessor = Files.readString(Path.of("src/main/java/com/bettercontent/spaceflight/vs/compat/mixin/VsFqAccess.java"));
        assertTrue(accessor.contains("@Accessor(value = \"h\", remap = false)"));
        assertTrue(accessor.contains("Thread spaceflight$physicsThread()"));
        var bridge = Files.readString(Path.of("src/main/java/com/bettercontent/spaceflight/vs/compat/internal/PinnedVs2411Bridge.java"));
        assertTrue(bridge.contains("Map<FN, WeakReference<Thread>> HEARTBEATS = new WeakHashMap<>()"));
        assertTrue(bridge.contains("new WeakReference<>(Thread.currentThread())"));
        assertTrue(bridge.contains("observed.get() == expected"));
    }
}
