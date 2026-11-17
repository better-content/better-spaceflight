package com.bettercontent.spaceflight.qualification;

import com.bettercontent.spaceflight.travel.ServerTravelPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure suite-contract checks; no Minecraft, native body, world or synthetic readiness provider. */
final class QualificationScenariosTest {
    @Test void allMandatoryNativeFaultAndClientCasesRemainBlockedWithoutProductionRoute() {
        var scenarios = QualificationScenarios.all();
        var pending = QualificationScenarios.pending();
        assertEquals(scenarios.size(), pending.size());
        assertEquals(37, scenarios.size());
        assertEquals(scenarios.size(), new HashSet<>(scenarios.stream()
            .map(QualificationScenarios.Scenario::id).toList()).size());
        assertEquals(Set.of(QualificationScenarios.Tier.values()), Set.copyOf(scenarios.stream()
            .map(QualificationScenarios.Scenario::tier).toList()));
        for (var row : pending) {
            assertEquals(QualificationScenarios.Status.blocked, row.status());
            assertTrue(row.reason().contains("no production recovery/dispatch readiness provider"));
            assertFalse(row.scenario().oracle().isBlank());
            assertFalse(row.scenario().evidence().isBlank());
        }
        assertThrows(IllegalArgumentException.class, () -> new QualificationScenarios.Pending(
            scenarios.get(0), QualificationScenarios.Status.passed, "historical probe is not production"));
        assertThrows(IllegalArgumentException.class, () -> new QualificationScenarios.Pending(
            scenarios.get(0), QualificationScenarios.Status.not_run, "not executed"));
    }

    @Test void requiredGapIdsCoverExactDestructiveFailureAndRealClientBoundaries() {
        Set<String> ids = QualificationScenarios.all().stream().map(QualificationScenarios.Scenario::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertTrue(ids.containsAll(List.of(
            "native_default_off", "native_startup_checksum", "native_connected_component",
            "native_joint_identity", "native_physics_liveness", "native_pose_velocity", "native_frame_boundary",
            "native_loaded_coverage", "native_unloaded_claims", "native_block_entity_cargo",
            "native_capability_ticks", "native_actor_closure", "native_destination_empty",
            "native_motion_gravity", "native_equal_layout_refusal", "native_ascent_space",
            "native_overworld_route", "native_wastelands_route", "native_abyssal_route",
            "native_twilight_route", "native_addon_veto",
            "fault_before_mutation", "fault_preparing_ambiguous", "fault_cancel_no_mutation",
            "fault_destination_copy", "fault_source_clear", "fault_partial_component",
            "fault_joint_restore", "fault_release_fsync", "fault_restart_reconcile", "fault_restore_integrity",
            "client_four_markers", "client_pilot_confirmation", "client_mounted_tracking",
            "client_concurrent_users", "client_restart_persistence")));
        var twilight = QualificationScenarios.pending().stream()
            .filter(row -> row.scenario().id().equals("native_twilight_route")).findFirst().orElseThrow();
        assertEquals(QualificationScenarios.Status.blocked, twilight.status());
        assertTrue(twilight.scenario().oracle().contains("refusal alone is not route success"));
    }

    @Test void allFiveActualRoutesRequireExactIdsPilotConnectedStateAndLandingWithoutAliases() {
        assertEquals(5, QualificationScenarios.routes().size());
        assertEquals(List.of("better_spaceflight:space", "minecraft:overworld",
                "fallout_wastelands_:wastelands", "better_abyssal_city:abyssal",
                "twilightforest:twilight_forest"),
            QualificationScenarios.routes().stream().map(QualificationScenarios.Route::destination).toList());
        assertEquals(List.of("surface-allowlist", "better_spaceflight:space", "better_spaceflight:space",
                "better_spaceflight:space", "better_spaceflight:space"),
            QualificationScenarios.routes().stream().map(QualificationScenarios.Route::source).toList());
        assertEquals(List.of(false, true, true, true, true),
            QualificationScenarios.routes().stream().map(QualificationScenarios.Route::landingRequired).toList());
        for (var route : QualificationScenarios.routes()) {
            assertEquals(route, QualificationScenarios.route(route.scenarioId()).orElseThrow());
            assertEquals(route, QualificationScenarios.routeTo(route.destination()).orElseThrow());
            var descriptor = QualificationScenarios.all().stream()
                .filter(s -> s.id().equals(route.scenarioId())).findFirst().orElseThrow();
            assertTrue(descriptor.oracle().contains("Pilot-confirmed"));
            assertTrue(descriptor.oracle().contains(route.destination()));
            assertTrue(descriptor.oracle().contains("connected ships/joints/actors/cargo"));
            assertTrue(descriptor.oracle().contains("motion") || descriptor.oracle().contains("moving pose/velocity"));
            if (route.landingRequired()) assertTrue(descriptor.oracle().contains("return/landing"));
            assertEquals(QualificationScenarios.Status.blocked, QualificationScenarios.pending().stream()
                .filter(p -> p.scenario().id().equals(route.scenarioId())).findFirst().orElseThrow().status());
        }
        assertTrue(QualificationScenarios.route("native_moon_route").isEmpty());
        assertTrue(QualificationScenarios.route("NATIVE_OVERWORLD_ROUTE").isEmpty());
        assertTrue(QualificationScenarios.route("native_Overworld_route").isEmpty());
        assertTrue(QualificationScenarios.routeTo("minecraft:Overworld").isEmpty());
        assertTrue(QualificationScenarios.routeTo("better_abyssal_city:ABYSSAL").isEmpty());
        assertTrue(QualificationScenarios.routeTo("unknown:moon").isEmpty());
        assertTrue(QualificationScenarios.route(null).isEmpty());
        assertTrue(QualificationScenarios.routeTo(null).isEmpty());
    }

    @Test void purePolicyGateCoordinatesAreNotTreatedAsVisibleOrTravelReady() {
        var gates = ServerTravelPolicy.GATES;
        assertEquals(List.of("north-overworld", "east-wastelands", "south-twilight", "west-abyssal"),
            gates.stream().map(ServerTravelPolicy.GateRegion::id).toList());
        assertEquals(List.of("minecraft:overworld", "fallout_wastelands_:wastelands",
                "twilightforest:twilight_forest", "better_abyssal_city:abyssal"),
            gates.stream().map(ServerTravelPolicy.GateRegion::destination).toList());
        assertEquals(List.of(new ServerTravelPolicy.Position(0, 256, -1024),
                new ServerTravelPolicy.Position(1024, 256, 0),
                new ServerTravelPolicy.Position(0, 256, 1024),
                new ServerTravelPolicy.Position(-1024, 256, 0)),
            gates.stream().map(ServerTravelPolicy.GateRegion::center).toList());
        assertTrue(gates.stream().allMatch(g -> g.radius() == 64));
        assertEquals(512, ServerTravelPolicy.ASCENT_Y);
        assertEquals(QualificationScenarios.Status.blocked, QualificationScenarios.pending().stream()
            .filter(row -> row.scenario().id().equals("client_four_markers")).findFirst().orElseThrow().status());
    }

    @Test void exactOutcomeVocabularyDoesNotInferPassFromMissingOrUnrecognizedEvidence() {
        assertEquals(List.of("passed", "failed", "blocked", "not_run"),
            java.util.Arrays.stream(QualificationScenarios.Status.values()).map(Enum::name).toList());
        for (var status : QualificationScenarios.Status.values())
            assertEquals(status, QualificationScenarios.Status.parse(status.name()));
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarios.Status.parse(null));
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarios.Status.parse("PASS"));
        assertThrows(IllegalArgumentException.class, () -> QualificationScenarios.Status.parse("passed_with_warnings"));
    }

    @Test void legacyTestsAndHistoricalProbeRemainSeparateNotAProductionSuite() throws Exception {
        Path existing = Path.of("src/test/java/com/bettercontent/spaceflight");
        try (var files = Files.walk(existing)) {
            assertEquals(25, files.filter(p -> p.toString().endsWith("Test.java"))
                .filter(p -> !p.toString().contains("/qualification/")).count());
        }
        Path probe = Path.of("src/probe/java/com/bettercontent/spaceflight/probe");
        assertTrue(Files.isRegularFile(probe.resolve("TransferGameTests.java")));
        assertTrue(Files.isRegularFile(probe.resolve("DynamicGameTests.java")));
        String old = Files.readString(probe.resolve("TransferGameTests.java"));
        assertTrue(old.contains("Vs2411Adapter.teleportForProbe"));
        assertFalse(QualificationScenarios.all().stream().anyMatch(s -> s.id().contains("historical_probe_pass")));
    }
}
