package com.bettercontent.spaceflight.qualification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Pure source/resource inspection; does not configure Gradle or load Forge. */
final class QualificationHarnessContractTest {
    private static String read(String path) throws Exception { return Files.readString(Path.of(path)); }

    @Test void sourceSetCannotJoinShippingJarOldProbeOrAnyRunTask() throws Exception {
        String build = read("build.gradle.kts");
        int start = build.indexOf("val qualificationSupport by sourceSets.creating");
        int end = build.indexOf("val attemptId", start);
        assertTrue(start > 0 && end > start, "qualificationSupport must be an independent source set");
        String snippet = build.substring(start, end);
        assertTrue(snippet.contains("sourceSets.main.get().output"));
        assertFalse(snippet.contains("probe.output"));
        assertFalse(snippet.contains("sourceSets.test.get().runtimeClasspath"));
        assertFalse(snippet.contains("jar") || snippet.contains("reobfJar"));
        assertFalse(build.contains("qualificationNativeAttempt"), "Forge configuration cannot guarantee a blocked receipt");
        assertFalse(build.contains("runQualificationServer") || build.contains("qualificationGameTestServer"));
        assertTrue(build.contains("property(\"forge.enabledGameTestNamespaces\", \"better_spaceflight_probe\")"));
        assertTrue(build.contains("source(probe)")); // historical configuration remains separate
    }

    @Test void annotatedSkeletonHasNoRegisteredTestPassOrProbeTeleport() throws Exception {
        String mod = read("src/qualificationSupport/java/com/bettercontent/spaceflight/qualification/QualificationTestMod.java");
        String tests = read("src/qualificationSupport/java/com/bettercontent/spaceflight/qualification/QualificationNativeGameTests.java");
        assertTrue(mod.contains("@Mod(QualificationTestMod.ID)"));
        assertTrue(mod.contains("better_spaceflight_qualification"));
        assertFalse(mod.contains("import net.minecraftforge.event.RegisterGameTestsEvent;"));
        assertFalse(mod.contains("event.register("));
        assertFalse(tests.contains("helper.succeed("));
        assertTrue(tests.contains("throw new IllegalStateException(\"BLOCKED:"));
        assertFalse(tests.contains("teleportForProbe("));
        assertTrue(tests.contains("@GameTest(templateNamespace = QualificationTestMod.ID"));
        String script = read("scripts/qualification-native-preflight.py");
        assertTrue(script.contains("\"status\": \"blocked\""));
        assertTrue(script.contains("--historical-probe-sha256"));
        assertTrue(script.contains("\"src/test\"") && script.contains("\"tests\"")
            && script.contains("\"gradle\"") && script.contains("\"src/probe\""));
        assertTrue(script.contains("stat.S_ISREG(os.fstat(lock_fd).st_mode)"));
        assertFalse(script.contains("subprocess.") || script.contains("./gradlew") || script.contains("helper.succeed"));
    }

    @Test void isolatedTemplateCannotImportProbeZeroGravityOrShippingAssets() throws Exception {
        Path resources = Path.of("src/qualificationSupport/resources");
        String metadata = Files.readString(resources.resolve("META-INF/mods.toml"));
        assertTrue(metadata.contains("modId=\"better_spaceflight_qualification\""));
        assertFalse(metadata.contains("better_spaceflight_probe"));
        assertFalse(Files.exists(resources.resolve("data/better_spaceflight/vs_dimension_parameters/space.json")));
        try (var files = Files.walk(resources)) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().endsWith(".nbt")).count());
        }
        assertArrayEquals(Files.readAllBytes(Path.of("src/probe/resources/data/better_spaceflight_probe/structures/empty.nbt")),
            Files.readAllBytes(resources.resolve("data/better_spaceflight_qualification/structures/empty.nbt")));
        assertTrue(Files.readString(resources.resolve("pack.mcmeta")).contains("NONSHIPPING"));
    }
}
