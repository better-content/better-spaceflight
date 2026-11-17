package com.bettercontent.spaceflight.vs.compat;

import com.bettercontent.spaceflight.vs.compat.internal.Eureka163MixinGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class Eureka163ArtifactGuardTest {
    @TempDir Path root;
    private Path original() {
        String configured = System.getProperty("spaceflight.eureka.originalJar");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null,
            "Original pinned Eureka JAR path must be explicitly supplied for read-only byte test");
        return Path.of(configured);
    }
    @Test void originalOptionalArtifactAndLoaderTargetBytesAreExact() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{original().toUri().toURL()}, null)) {
            Eureka163ArtifactGuard.requireExactOriginal(original(), loader);
        }
    }
    @Test void alteredArchiveOrLoaderBytesAreRejectedWithoutMinecraft() throws Exception {
        var altered = root.resolve("eureka.jar");
        byte[] bytes = Files.readAllBytes(original());
        bytes[bytes.length - 9] ^= 1;
        Files.write(altered, bytes);
        try (var loader = new URLClassLoader(new java.net.URL[]{altered.toUri().toURL()}, null)) {
            assertThrows(java.io.IOException.class, () -> Eureka163ArtifactGuard.requireExactOriginal(altered, loader));
        }
        assertThrows(java.io.IOException.class,
            () -> Eureka163ArtifactGuard.requireExactOriginal(original(), new ClassLoader(null) {}));
    }
    @Test void mappedDevBytecodeContractsAreKnownButNotBlessedAsOriginal() throws Exception {
        String configured = System.getProperty("spaceflight.eureka.mappedJar");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null, "Mapped cached Eureka path required");
        try (var jar = new java.util.jar.JarFile(Path.of(configured).toFile())) {
            var hashes = java.util.Map.of(
                "org/valkyrienskies/eureka/ship/EurekaShipControl.class",
                    "ba3d27e2c2c5227a27c222cab494dc61e59deb502dc038b4f39026fe7517e6b1",
                "org/valkyrienskies/eureka/blockentity/ShipHelmBlockEntity.class",
                    "3dd89dbdf5011b9932c6c061e9d2b8ebbcb77bb14f3c14757d88803d240b11d0");
            for (var entry : hashes.entrySet()) {
                try (var in = jar.getInputStream(jar.getJarEntry(entry.getKey()))) {
                    assertEquals(entry.getValue(), java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
                }
            }
        }
    }
    @Test void optionalAddonMixinsDefaultOff() {
        assertNull(System.getProperty("better_spaceflight.experimental.vs2411_hooks"));
        assertFalse(Eureka163MixinGate.verifiedForThisProcess());
        var gate = new Eureka163MixinGate();
        gate.onLoad("com.bettercontent.spaceflight.vs.compat.mixin");
        assertFalse(gate.shouldApplyMixin("org.valkyrienskies.eureka.ship.EurekaShipControl",
            "EurekaShipControlMixin"));
    }
}
