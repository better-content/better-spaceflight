package com.bettercontent.spaceflight.vs.compat;

import com.bettercontent.spaceflight.vs.compat.internal.Vs2411MixinGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

final class Vs2411ArtifactGuardTest {
    @TempDir Path scratch;
    private static Path original() {
        String configured = System.getProperty("spaceflight.vs.originalJar");
        if (configured != null) return Path.of(configured);
        try {
            var resource = Vs2411ArtifactGuardTest.class.getClassLoader()
                .getResource("org/valkyrienskies/core/impl/shadow/FU.class");
            assertNotNull(resource, "exact pinned VS JAR required for read-only class byte test");
            return Path.of(((java.net.JarURLConnection) resource.openConnection()).getJarFileURL().toURI());
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @Test void originalPinnedJarHasExactVersionAndAllTargetByteHashes() throws Exception {
        Vs2411ArtifactGuard.requireExactJar(original());
    }
    @Test void originalSecureJarClassResourceBytesMustAlsoMatch() throws Exception {
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{original().toUri().toURL()}, null)) {
            Vs2411ArtifactGuard.requireExactClassloaderBytes(loader);
        }
        assertThrows(java.io.IOException.class,
            () -> Vs2411ArtifactGuard.requireExactClassloaderBytes(new ClassLoader(null) {}));
    }
    @Test void alteredOrIncompleteJarIsRejectedWithoutRunningVs() throws Exception {
        var destination = scratch.resolve("altered-vs.jar");
        try (var jar = new java.util.jar.JarFile(original().toFile());
             var out = new ZipOutputStream(java.nio.file.Files.newOutputStream(destination))) {
            for (String entry : new String[]{"META-INF/mods.toml",
                "org/valkyrienskies/core/impl/shadow/Et.class",
                "org/valkyrienskies/core/impl/shadow/Ek.class",
                "org/valkyrienskies/core/impl/shadow/El.class",
                "org/valkyrienskies/core/impl/shadow/FN.class",
                "org/valkyrienskies/core/impl/shadow/FU.class",
                "org/valkyrienskies/core/impl/shadow/FH.class",
                "org/valkyrienskies/core/impl/shadow/FG.class",
                "org/valkyrienskies/core/impl/shadow/FQ.class",
                "org/valkyrienskies/core/impl/game/ships/PhysShipImpl.class",
                "org/valkyrienskies/physics_api_krunch/KrunchNativePhysicsWorldReference.class"}) {
                out.putNextEntry(new ZipEntry(entry));
                byte[] bytes;
                try (var in = jar.getInputStream(jar.getJarEntry(entry))) { bytes = in.readAllBytes(); }
                if (entry.endsWith("FU.class")) bytes[bytes.length - 1] ^= 1;
                out.write(bytes); out.closeEntry();
            }
        }
        assertThrows(java.io.IOException.class, () -> Vs2411ArtifactGuard.requireExactJar(destination));
    }
    @Test void shippingMixinGateDefaultsOffEvenWhenConfigIsPresent() {
        assertNull(System.getProperty(Vs2411MixinGate.PROPERTY));
        var gate = new Vs2411MixinGate();
        gate.onLoad("com.bettercontent.spaceflight.vs.compat.mixin");
        assertFalse(gate.shouldApplyMixin("org.valkyrienskies.core.impl.shadow.FU", "VsFuMixin"));
    }
}
