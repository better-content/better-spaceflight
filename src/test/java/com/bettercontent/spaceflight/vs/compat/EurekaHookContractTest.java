package com.bettercontent.spaceflight.vs.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure source/descriptor checks, not transformed Mixin or native behavior. */
final class EurekaHookContractTest {
    private static List<String> handlers(String resource) throws Exception {
        var names = new ArrayList<String>();
        try (var stream = EurekaHookContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (name.startsWith("spaceflight$")) names.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_DEBUG);
        }
        return names;
    }
    @Test void onlyExactOptionalEurekaTargetsHaveScopedHandlers() throws Exception {
        assertEquals(List.of("spaceflight$holdPhysicsCallback(Lorg/valkyrienskies/core/api/ships/PhysShip;"
            + "Lorg/valkyrienskies/core/api/world/PhysLevel;"
            + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"), handlers(
                "com/bettercontent/spaceflight/vs/compat/mixin/EurekaShipControlMixin.class"));
        assertEquals(2, handlers("com/bettercontent/spaceflight/vs/compat/mixin/EurekaShipHelmMixin.class").size());
    }
    @Test void exactOwnerAndBodyIdentityGuardDoesNotWhitelistListenerAdmission() throws Exception {
        var bridge = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/bettercontent/spaceflight/vs/compat/internal/PinnedVs2411Bridge.java"));
        assertTrue(bridge.contains("worldBodies.get(actual.getId()) == actual"));
        assertTrue(bridge.contains("owner.serverIdentity == server"));
        assertTrue(bridge.contains("owner.registry.shouldSealGameMutation(shipId, dimension)"));
        assertTrue(bridge.contains("Thread.currentThread() != owner.physicsThread"));
        String gameGuard = bridge.substring(bridge.indexOf("public static boolean denyHeldHelmMutation"),
            bridge.indexOf("public static boolean allowStaticChange"));
        assertFalse(gameGuard.contains("owner.physics.b()"));
        assertTrue(bridge.contains("unknown physics attachment/listener")); // still veto until handoff
        var helm = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/bettercontent/spaceflight/vs/compat/internal/EurekaHelmPermit.java"));
        assertTrue(helm.contains("getLoadedShipManagingPos(level, helm.getBlockPos())"));
        assertTrue(helm.contains("denyHeldHelmMutation(level.getServer()"));
    }
    @Test void independentVsAndEurekaPluginOrderUsesSameIdempotentArtifactGate() throws Exception {
        var vs = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/bettercontent/spaceflight/vs/compat/internal/Vs2411MixinGate.java"));
        var eureka = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/bettercontent/spaceflight/vs/compat/internal/Eureka163MixinGate.java"));
        assertTrue(vs.contains("public static synchronized boolean ensureVerifiedFromForgeArtifacts()"));
        assertTrue(vs.contains("if (!ENABLED) return false;"));
        assertTrue(vs.contains("if (verified) return true;"));
        assertTrue(vs.contains("pinned = ensureVerifiedFromForgeArtifacts()"));
        assertTrue(eureka.contains("Vs2411MixinGate.ensureVerifiedFromForgeArtifacts()"));
        assertFalse(eureka.contains("if (!Vs2411MixinGate.verifiedForThisProcess())"));
        assertFalse(com.bettercontent.spaceflight.vs.compat.internal.Vs2411MixinGate
            .ensureVerifiedFromForgeArtifacts()); // off means no Forge loader access
    }
    @Test void optionalConfigurationIsSeparateExactGuardedAndNotDefaultOn() throws Exception {
        try (var stream = getClass().getClassLoader()
            .getResourceAsStream("better_spaceflight.eureka163.mixins.json")) {
            assertNotNull(stream);
            var text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("\"required\": false"));
            assertTrue(text.contains("\"plugin\": \"com.bettercontent.spaceflight.vs.compat.internal.Eureka163MixinGate\""));
            assertTrue(text.contains("\"defaultRequire\": 1"));
            assertTrue(text.contains("EurekaShipControlMixin")); assertTrue(text.contains("EurekaShipHelmMixin"));
        }
        var gate = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/bettercontent/spaceflight/vs/compat/internal/Eureka163MixinGate.java"));
        assertTrue(gate.contains("getModFileById(\"eureka\")"));
        assertTrue(gate.contains("Vs2411MixinGate.ensureVerifiedFromForgeArtifacts()"));
        assertTrue(gate.contains("Eureka163ArtifactGuard.requireExactOriginal"));
    }
}
