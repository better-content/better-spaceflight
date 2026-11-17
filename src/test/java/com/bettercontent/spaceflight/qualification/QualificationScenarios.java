package com.bettercontent.spaceflight.qualification;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Test-source-only qualification inventory. No GameTest registration, Minecraft
 * server, native call or production travel provider is reachable from here.
 * A scenario descriptor is NOT a test result or a readiness grant.
 */
final class QualificationScenarios {
    enum Tier { NATIVE, FAULT, CLIENT }
    enum Status {
        passed, failed, blocked, not_run;
        static Status parse(String text) {
            if (text == null) throw new IllegalArgumentException("Missing qualification status");
            for (var status : values()) if (status.name().equals(text)) return status;
            throw new IllegalArgumentException("Unknown qualification status: " + text);
        }
    }
    record Scenario(String id, Tier tier, String requiredProof, String oracle, String evidence) {
        Scenario {
            if (id == null || !id.matches("[a-z][a-z0-9_]{4,63}") || tier == null
                || requiredProof == null || requiredProof.isBlank() || oracle == null || oracle.isBlank()
                || evidence == null || evidence.isBlank()) throw new IllegalArgumentException("Invalid scenario");
        }
    }
    record Pending(Scenario scenario, Status status, String reason) {
        Pending {
            Objects.requireNonNull(scenario);
            if (status != Status.blocked || reason == null || reason.isBlank())
                throw new IllegalArgumentException("No executable production readiness or validated outcome exists");
        }
    }
    private static final String RECOVERY = "verified full-world backup/restore + complete meaningful chunk/actor closure";
    private static final String DISPATCH = RECOVERY + " + real production connected-set dispatch/readiness";
    private static final String RUNTIME = DISPATCH + " + pinned transformed VS/native owner in disposable fixture";
    private static final String CLIENT = RUNTIME + " + two independently connected real clients and authoritative server grant";
    private static final List<Scenario> SCENARIOS = List.of(
        new Scenario("native_original_artifacts", Tier.NATIVE, RUNTIME,
            "Verify original VS 2.4.11, exact classloader bytes and transformed hook thread; refuse mismatch", "startup log + artifact digests"),
        new Scenario("native_default_off", Tier.NATIVE, RUNTIME,
            "Ordinary launch leaves compatibility hooks OFF and exposes no production travel route", "startup properties + transformed target scan"),
        new Scenario("native_startup_checksum", Tier.NATIVE, RUNTIME,
            "Corrupt/missing startup joint journals and altered mapped/original artifacts block owner admission", "cold-start exit + sha256 + journal scan"),
        new Scenario("native_connected_component", Tier.NATIVE, RUNTIME,
            "Two or more constraint-connected occupied ships transfer as one bounded component", "server snapshots + native graph IDs"),
        new Scenario("native_joint_identity", Tier.NATIVE, RUNTIME,
            "Every logical and backend joint keeps its original ID and endpoints", "before/after native and persisted joint snapshots"),
        new Scenario("native_physics_liveness", Tier.NATIVE, RUNTIME,
            "Original body identities, callback ownership and listener-free native liveness persist through handoff", "thread-bound body snapshots + callbacks"),
        new Scenario("native_pose_velocity", Tier.NATIVE, RUNTIME,
            "Each ship retains intended relative pose, inertia, linear/angular velocity and original static flag", "physics-boundary and game snapshots"),
        new Scenario("native_frame_boundary", Tier.NATIVE, RUNTIME,
            "All intended removals and same-ID restores bind the exact producer frame without partial release", "frame nonce and physics epoch records"),
        new Scenario("native_loaded_coverage", Tier.NATIVE, RUNTIME,
            "Actual FULL loaded and pending-unload shipyard holders exactly match selected source claims", "chunk-holder enumeration and digest"),
        new Scenario("native_unloaded_claims", Tier.NATIVE, RECOVERY,
            "Meaningful unloaded/on-disk claim chunks and pending ticks are included or rejected", "offline claim scan + original chunk payloads"),
        new Scenario("native_block_entity_cargo", Tier.NATIVE, RUNTIME,
            "Block states, named BE payloads and sentinel cargo/fuel are preserved without duplication", "before/after raw chunk and BE hashes"),
        new Scenario("native_capability_ticks", Tier.NATIVE, RUNTIME,
            "ForgeCaps provider data and exact tick identity/deadlines/order survive transfer", "provider registry + tick inventory + raw NBT"),
        new Scenario("native_actor_closure", Tier.NATIVE, RUNTIME,
            "Online/offline/on-deck contacts, seats, vehicles, passengers and riding roots have complete closure", "UUID/contact/riding graph + player data"),
        new Scenario("native_destination_empty", Tier.NATIVE, RUNTIME,
            "Loaded/unloaded destination claim area is empty and remains collision-free", "pre/post destination region inventory"),
        new Scenario("native_motion_gravity", Tier.NATIVE, RUNTIME,
            "Dynamic ship visibly coasts in space and gravity control advances at altitude", "sampled pose/velocity/clock/native body"),
        new Scenario("native_equal_layout_refusal", Tier.NATIVE, RUNTIME,
            "Incompatible vertical layout is rejected before any destructive call", "before/after complete world snapshot"),
        new Scenario("native_ascent_space", Tier.NATIVE, RUNTIME,
            "Pilot-confirmed ascent from an exact allowed surface ID to better_spaceflight:space; original connected ships/joints/actors/cargo and moving pose/velocity/inertia must match at space arrival before any return", "bound pilot nonce + space arrival ship/actor/cargo/native motion snapshots"),
        new Scenario("native_overworld_route", Tier.NATIVE, RUNTIME,
            "Pilot-confirmed exit from better_spaceflight:space to minecraft:overworld and return/landing; original connected ships/joints/actors/cargo and moving pose/velocity/inertia must match", "bound pilot + destination/landing world and native snapshots"),
        new Scenario("native_wastelands_route", Tier.NATIVE, RUNTIME,
            "Pilot-confirmed exit from better_spaceflight:space to fallout_wastelands_:wastelands and return/landing; original connected ships/joints/actors/cargo and moving pose/velocity/inertia must match", "bound pilot + destination/landing world and native snapshots"),
        new Scenario("native_abyssal_route", Tier.NATIVE, RUNTIME,
            "Pilot-confirmed exit from better_spaceflight:space to better_abyssal_city:abyssal and return/landing; original connected ships/joints/actors/cargo and moving pose/velocity/inertia must match", "bound pilot + destination/landing world and native snapshots"),
        new Scenario("native_twilight_route", Tier.NATIVE, RUNTIME,
            "Pilot-confirmed exit from better_spaceflight:space to twilightforest:twilight_forest and return/landing with original connected ships/joints/actors/cargo/motion; BLOCKED until unequal-layout transfer is safe; refusal alone is not route success", "full destination/landing world/actor/ship evidence"),
        new Scenario("native_addon_veto", Tier.NATIVE, RUNTIME,
            "Powered Eureka and unsupported Clockwork/Trackwork refuse admission without mutating worlds", "listener/attachment snapshots + veto log"),
        new Scenario("fault_before_mutation", Tier.FAULT, RUNTIME,
            "Abort before first destructive game call leaves source and destination unchanged", "exclusive gate ledger + raw before/after world hashes"),
        new Scenario("fault_preparing_ambiguous", Tier.FAULT, RUNTIME,
            "PREPARING fsync ambiguity retains claims; verified physics restore ends mutation seals only", "fault point + disk phase + physics ACK"),
        new Scenario("fault_cancel_no_mutation", Tier.FAULT, RUNTIME,
            "Only proven zero-dispatch PREPARING can durably CANCELLED; late shutdown/timeout cannot re-freeze", "exclusive gate ledger + terminal fsync"),
        new Scenario("fault_destination_copy", Tier.FAULT, RUNTIME,
            "Fail before/within target copy; pre-existing destination bytes and entire source recover", "full-world backup + both region digests"),
        new Scenario("fault_source_clear", Tier.FAULT, RUNTIME,
            "Fail mid source-clear second pass; mixed source/target state never commits", "fault ordering + backup restore + hashes"),
        new Scenario("fault_partial_component", Tier.FAULT, RUNTIME,
            "Fail between members or physics frames; all connected bodies/joints stay quarantined", "per-member phase and native graph"),
        new Scenario("fault_joint_restore", Tier.FAULT, RUNTIME,
            "Backend same-ID restoration throws or collides; never release or commit partial constraints", "joint IDs + native exception + quarantine evidence"),
        new Scenario("fault_release_fsync", Tier.FAULT, RUNTIME,
            "Error before/after RELEASED atomic replacement keeps irrevocable RELEASE_ACK semantics", "durable phase + owner/body state"),
        new Scenario("fault_restart_reconcile", Tier.FAULT, RUNTIME,
            "Process kill/restart resolves only verified terminal journals; ambiguous records block admission", "retained failed world + cold restart scan"),
        new Scenario("fault_restore_integrity", Tier.FAULT, RECOVERY,
            "Restoration recovers source AND destination regions, actors and cargo without duplication", "full-world backup ID + all hashes"),
        new Scenario("client_four_markers", Tier.CLIENT, CLIENT,
            "Only authenticated server grants display exactly four policy destinations; none without grant", "two-client visual capture + packet grant proof"),
        new Scenario("client_pilot_confirmation", Tier.CLIENT, CLIENT,
            "Real seat controller alone receives nonce-bound ascent offer; confirmation rechecks live authority", "server offer/confirmation trace + clients"),
        new Scenario("client_mounted_tracking", Tier.CLIENT, CLIENT,
            "Both clients observe one moving assembly and correct seat/camera/interaction after transfer", "two-client timestamps + video + server IDs"),
        new Scenario("client_concurrent_users", Tier.CLIENT, CLIENT,
            "Disconnect/reconnect and pilot/root swaps never duplicate an offer or craft", "two-client logs + UUID registry"),
        new Scenario("client_restart_persistence", Tier.CLIENT, CLIENT,
            "Cold world restart preserves one craft, all joints, cargo, players and gate status", "pre/post save hashes + two-client observations")
    );
    /** Exact authored route keys, never client-supplied aliases or inferred marker labels. */
    record Route(String scenarioId, String source, String destination, boolean landingRequired) {}
    private static final List<Route> ROUTES = List.of(
        new Route("native_ascent_space", "surface-allowlist", "better_spaceflight:space", false),
        new Route("native_overworld_route", "better_spaceflight:space", "minecraft:overworld", true),
        new Route("native_wastelands_route", "better_spaceflight:space", "fallout_wastelands_:wastelands", true),
        new Route("native_abyssal_route", "better_spaceflight:space", "better_abyssal_city:abyssal", true),
        new Route("native_twilight_route", "better_spaceflight:space", "twilightforest:twilight_forest", true)
    );
    static {
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : SCENARIOS) if (!ids.add(scenario.id()))
            throw new IllegalStateException("Duplicate qualification scenario ID: " + scenario.id());
        Set<String> destinations = new HashSet<>();
        for (Route route : ROUTES) {
            if (!ids.contains(route.scenarioId()) || !destinations.add(route.destination()))
                throw new IllegalStateException("Missing/ambiguous mandatory route: " + route.scenarioId());
        }
    }
    private QualificationScenarios() {}
    static List<Scenario> all() { return SCENARIOS; }
    static List<Route> routes() { return ROUTES; }
    static java.util.Optional<Route> route(String id) {
        return ROUTES.stream().filter(r -> r.scenarioId().equals(id)).findFirst();
    }
    static java.util.Optional<Route> routeTo(String destination) {
        return ROUTES.stream().filter(r -> r.destination().equals(destination)).findFirst();
    }

    /** No production readiness/transport exists. Fake unit observations cannot promote a scenario. */
    static List<Pending> pending() {
        return SCENARIOS.stream().map(s -> new Pending(s, Status.blocked,
            "BLOCKED: " + s.requiredProof() + "; no production recovery/dispatch readiness provider")).toList();
    }
}
