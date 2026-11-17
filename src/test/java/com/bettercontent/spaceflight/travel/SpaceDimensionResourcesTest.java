package com.bettercontent.spaceflight.travel;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Source-only dimension schema/pack boundary checks; no world is booted. */
final class SpaceDimensionResourcesTest {
    private static final String DATA = "data/better_spaceflight/";
    private static String resource(String name) throws Exception {
        try (var stream = SpaceDimensionResourcesTest.class.getClassLoader().getResourceAsStream(DATA + name)) {
            assertNotNull(stream, "shipping dimension resource missing: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", "");
        }
    }
    @Test void mainSpaceIsEmptyFlatVoidWithNoGenerationLayersFeaturesOrStructures() throws Exception {
        var dimension = resource("dimension/space.json");
        assertEquals("{\"type\":\"better_spaceflight:space\",\"generator\":{\"type\":\"minecraft:flat\","
            + "\"settings\":{\"biome\":\"minecraft:the_void\",\"lakes\":false,\"features\":false,"
            + "\"layers\":[],\"structure_overrides\":[]}}}", dimension);
    }
    @Test void verticalLayoutMatchesOverworldAndUsesVanillaEndSkyWithoutNewRenderer() throws Exception {
        var type = resource("dimension_type/space.json");
        assertTrue(type.contains("\"min_y\":-64,\"height\":384"));
        assertTrue(type.contains("\"logical_height\":384"));
        assertTrue(type.contains("\"effects\":\"minecraft:the_end\""));
        assertTrue(type.contains("\"natural\":false"));
        assertTrue(type.contains("\"fixed_time\":18000"));
        assertTrue(type.contains("\"monster_spawn_block_light_limit\":0"));
        assertTrue(type.contains("\"monster_spawn_light_level\":0"));
    }
    @Test void productionDoesNotInheritHistoricalProbeZeroGravityOrAirDensityOverride() throws Exception {
        assertFalse(Files.exists(Path.of("src/main/resources/" + DATA + "vs_dimension_parameters/space.json")));
        var probe = Files.readString(Path.of("src/probe/resources/" + DATA + "vs_dimension_parameters/space.json"));
        assertTrue(probe.contains("\"gravity\": [0.0, 0.0, 0.0]")); // probe ONLY, deliberately unchanged
    }
}
