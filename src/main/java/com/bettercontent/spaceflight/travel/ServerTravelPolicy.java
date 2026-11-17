package com.bettercontent.spaceflight.travel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SOURCE-ONLY server-game-tick policy. It issues/consumes confirmation nonces; it does
 * not teleport anything, invoke VS, register UI, or trust client-supplied positions.
 * An actual server owner must supply authoritative live providers, reconciliation,
 * cargo/world backup and safe transfer lifecycle before even exposing offers to users.
 * ServerGameTick MUST NOT be confused with VS physics-boundary epochs.
 */
public final class ServerTravelPolicy {
    public static final String SPACE = "better_spaceflight:space";
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String WASTELANDS = "fallout_wastelands_:wastelands";
    public static final String TWILIGHT = "twilightforest:twilight_forest";
    public static final String ABYSSAL = "better_abyssal_city:abyssal";
    public static final Set<String> SURFACE_DESTINATIONS = Set.of(OVERWORLD, WASTELANDS, TWILIGHT, ABYSSAL);
    public static final long NONCE_TTL_GAME_TICKS = 600, COOLDOWN_GAME_TICKS = 200;
    public static final double ASCENT_Y = 512, GATE_CARDINAL_RADIUS = 1024,
        GATE_CENTER_Y = 256, GATE_ACTIVATION_RADIUS = 64;
    private static final int MAX_PENDING = 128, MAX_TRACKED_SHIPS = 256,
        MAX_SHIPS = 64, MAX_BLOCKS = 16_384;
    private static final long IDLE_GAME_TICKS = 1_200;
    private static final String ASCENT = "surface-ascent";

    public record ServerGameTick(long value) {
        public ServerGameTick { if (value < 0) throw new IllegalArgumentException("negative server game tick"); }
    }
    public record Position(double x, double y, double z) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("nonfinite position");
        }
        public double distanceSquared(Position other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
    public record GateRegion(String id, String label, String destination, Position center, double radius) {
        public GateRegion {
            Objects.requireNonNull(id); Objects.requireNonNull(label); Objects.requireNonNull(destination);
            Objects.requireNonNull(center);
            if (id.isBlank() || label.isBlank() || !SURFACE_DESTINATIONS.contains(destination)
                || !Double.isFinite(radius) || radius <= 0) throw new IllegalArgumentException("invalid gate region");
        }
        public boolean contains(Position point) { return point.distanceSquared(center) <= radius * radius; }
        /** Closest point on the swept finite segment, including a fast outside-to-outside crossing. */
        public boolean intersects(Position start, Position end) {
            double dx = end.x - start.x, dy = end.y - start.y, dz = end.z - start.z;
            double length2 = dx * dx + dy * dy + dz * dz;
            if (!Double.isFinite(length2)) return false;
            if (length2 == 0) return contains(start);
            double t = Math.max(0, Math.min(1,
                ((center.x - start.x) * dx + (center.y - start.y) * dy + (center.z - start.z) * dz) / length2));
            return contains(new Position(start.x + t * dx, start.y + t * dy, start.z + t * dz));
        }
    }
    /** Definitions only: not visible blocks, portals, or spawned gate objects. */
    public static final List<GateRegion> GATES = List.of(
        new GateRegion("north-overworld", "Overworld", OVERWORLD,
            new Position(0, GATE_CENTER_Y, -GATE_CARDINAL_RADIUS), GATE_ACTIVATION_RADIUS),
        new GateRegion("east-wastelands", "Wastelands", WASTELANDS,
            new Position(GATE_CARDINAL_RADIUS, GATE_CENTER_Y, 0), GATE_ACTIVATION_RADIUS),
        new GateRegion("south-twilight", "Twilight Forest", TWILIGHT,
            new Position(0, GATE_CENTER_Y, GATE_CARDINAL_RADIUS), GATE_ACTIVATION_RADIUS),
        new GateRegion("west-abyssal", "Abyssal", ABYSSAL,
            new Position(-GATE_CARDINAL_RADIUS, GATE_CENTER_Y, 0), GATE_ACTIVATION_RADIUS));

    /** Snapshot must come from the actual server ship graph, never a client DTO. */
    public record Formation(long rootShipId, Set<Long> members, int blockCount, Position center) {
        public Formation {
            Objects.requireNonNull(members); Objects.requireNonNull(center);
            members = Set.copyOf(members);
            if (rootShipId <= 0 || members.isEmpty() || members.size() > MAX_SHIPS
                || !members.contains(rootShipId) || members.stream().anyMatch(id -> id == null || id <= 0)
                || blockCount <= 0 || blockCount > MAX_BLOCKS)
                throw new IllegalArgumentException("empty, oversized or invalid assembly");
        }
    }
    /** No default implementation or permissive stub. Absent/throwing providers deny offers. */
    public interface AssemblySnapshot { Optional<Formation> current(String source, long rootShipId); }
    public interface PilotAuthority { boolean isCurrentPilot(UUID actor, String source, Formation formation); }
    public interface TransferReadiness {
        boolean destinationEnabled(String dimension);
        /** Must reject concurrent/incomplete transfer, blocked native provider, or unavailable recovery. */
        boolean ready(String source, String destination, Formation formation);
    }
    /** Only authoritative sampled positions from the server owner may be passed here. */
    public record Motion(UUID actor, long rootShipId, String source, Position previous,
                         Position current, ServerGameTick tick) {
        public Motion { Objects.requireNonNull(actor); Objects.requireNonNull(source);
            Objects.requireNonNull(previous); Objects.requireNonNull(current); Objects.requireNonNull(tick); }
    }
    public record Offer(UUID nonce, UUID actor, long rootShipId, Set<Long> members,
                        String source, String destination, String region,
                        ServerGameTick issuedAt, ServerGameTick expiresAt) {
        public Offer { members = Set.copyOf(members); }
    }
    private static final class MemberState {
        long lastTick, cooldownUntil;
        final Set<String> mustLeave = new HashSet<>();
        MemberState(long tick) { lastTick = tick; }
    }
    private final PilotAuthority pilots;
    private final AssemblySnapshot assemblies;
    private final TransferReadiness readiness;
    private final Map<UUID, Offer> pending = new HashMap<>();
    // All connected members share cooldown and latches, even after pilot/root changes.
    private final Map<Long, MemberState> members = new HashMap<>();
    private boolean stopped;

    public ServerTravelPolicy(PilotAuthority pilots, AssemblySnapshot assemblies, TransferReadiness readiness) {
        this.pilots = Objects.requireNonNull(pilots, "pilot authority required");
        this.assemblies = Objects.requireNonNull(assemblies, "assembly snapshot required");
        this.readiness = Objects.requireNonNull(readiness, "transfer readiness required");
    }
    /** Pure offer, never a teleport. First observation establishes an unarmed baseline. */
    public synchronized Optional<Offer> observe(Motion motion) {
        Objects.requireNonNull(motion);
        if (stopped) return Optional.empty();
        long now = motion.tick.value;
        if (now > Long.MAX_VALUE - NONCE_TTL_GAME_TICKS) return Optional.empty();
        prune(now);
        var found = snapshot(motion.actor, motion.rootShipId, motion.source, motion.current, null);
        if (found.isEmpty()) return Optional.empty();
        var formation = found.get();
        boolean firstObservation = formation.members.stream().anyMatch(id -> !members.containsKey(id));
        if (members.size() + formation.members.stream().filter(id -> !members.containsKey(id)).count()
            > MAX_TRACKED_SHIPS) return Optional.empty();
        for (long id : formation.members) {
            var state = members.computeIfAbsent(id, ignored -> new MemberState(now));
            if (now < state.lastTick) return Optional.empty();
        }
        for (long id : formation.members) {
            var state = members.get(id);
            state.lastTick = now;
            if (motion.current.y <= ASCENT_Y) state.mustLeave.remove(ASCENT);
            if (SPACE.equals(motion.source)) for (var gate : GATES) {
                if (!gate.contains(motion.current)
                    && (gate.contains(motion.previous) || !gate.intersects(motion.previous, motion.current)))
                    state.mustLeave.remove(gate.id); // fully outside, or left from inside
            }
        }
        if (firstObservation) {
            // After startup/idle eviction no previous segment is trusted. A newly seen
            // assembly must be observed outside/below before its next actual crossing.
            for (long id : formation.members) {
                var latch = members.get(id).mustLeave;
                if (motion.current.y > ASCENT_Y) latch.add(ASCENT);
                if (SPACE.equals(motion.source)) for (var gate : GATES)
                    if (gate.intersects(motion.previous, motion.current)) latch.add(gate.id);
            }
            return Optional.empty();
        }
        if (pending.size() >= MAX_PENDING || formation.members.stream().anyMatch(id -> now < members.get(id).cooldownUntil)
            || pending.values().stream().anyMatch(offer -> !java.util.Collections.disjoint(offer.members, formation.members)))
            return Optional.empty();
        String target = null, region = null;
        if (SURFACE_DESTINATIONS.contains(motion.source)
            && motion.previous.y <= ASCENT_Y && motion.current.y > ASCENT_Y
            && formation.members.stream().noneMatch(id -> members.get(id).mustLeave.contains(ASCENT))) {
            target = SPACE; region = ASCENT;
        } else if (SPACE.equals(motion.source)) {
            for (var gate : GATES) {
                if (formation.members.stream().noneMatch(id -> members.get(id).mustLeave.contains(gate.id))
                    && !gate.contains(motion.previous) && gate.intersects(motion.previous, motion.current)) {
                    target = gate.destination; region = gate.id; break;
                }
            }
        }
        if (target == null) return Optional.empty();
        try {
            if (!readiness.destinationEnabled(target) || !readiness.ready(motion.source, target, formation))
                return Optional.empty();
        } catch (RuntimeException ex) { return Optional.empty(); }
        var offer = new Offer(UUID.randomUUID(), motion.actor, motion.rootShipId, formation.members,
            motion.source, target, region, motion.tick, new ServerGameTick(now + NONCE_TTL_GAME_TICKS));
        for (long id : formation.members) members.get(id).mustLeave.add(region);
        pending.put(offer.nonce, offer);
        return Optional.of(offer);
    }
    /**
     * Reject other actors/roots/routes WITHOUT consuming their token. After matching
     * identity, consume before the authoritative revalidation. true is policy-only.
     */
    public synchronized boolean confirm(UUID nonce, UUID actor, long rootShipId, String source,
                                        String destination, ServerGameTick now) {
        if (stopped || nonce == null || actor == null || source == null || destination == null || now == null)
            return false;
        Offer offer = pending.get(nonce);
        if (offer == null || !offer.actor.equals(actor) || offer.rootShipId != rootShipId
            || !offer.source.equals(source) || !offer.destination.equals(destination)) return false;
        pending.remove(nonce); // only the bound actor can consume, even on a stale/rejected attempt
        if (now.value < offer.issuedAt.value || now.value >= offer.expiresAt.value
            || now.value > Long.MAX_VALUE - COOLDOWN_GAME_TICKS) return false;
        for (long id : offer.members) {
            var state = members.get(id);
            if (state == null || now.value < state.lastTick || now.value < state.cooldownUntil) return false;
        }
        var found = checked(actor, rootShipId, source, destination, offer.members);
        if (found.isEmpty()) return false;
        var current = found.get();
        if (ASCENT.equals(offer.region)) {
            if (!SURFACE_DESTINATIONS.contains(source) || !SPACE.equals(destination)
                || current.center.y <= ASCENT_Y) return false;
        } else {
            if (!SPACE.equals(source)) return false;
            GateRegion gate = GATES.stream().filter(g -> g.id.equals(offer.region)
                && g.destination.equals(destination)).findFirst().orElse(null);
            if (gate == null || !gate.contains(current.center)) return false;
        }
        for (long id : current.members) {
            var state = members.get(id);
            state.lastTick = now.value;
            state.cooldownUntil = now.value + COOLDOWN_GAME_TICKS;
        }
        return true;
    }
    /** Called when a pilot leaves/logs out; does NOT clear craft-wide cooldown or latches. */
    public synchronized void invalidateActor(UUID actor) {
        if (actor != null) pending.values().removeIf(offer -> offer.actor.equals(actor));
    }
    /** Irreversible owner-stop boundary; a new world owner must rebuild its own policy. */
    public synchronized void stopOwner() {
        stopped = true; pending.clear(); members.clear();
    }
    private void prune(long now) {
        pending.values().removeIf(offer -> now >= offer.expiresAt.value);
        var held = new HashSet<Long>();
        for (var offer : pending.values()) held.addAll(offer.members);
        members.entrySet().removeIf(entry -> now >= entry.getValue().lastTick
            && now - entry.getValue().lastTick > IDLE_GAME_TICKS
            && now >= entry.getValue().cooldownUntil && !held.contains(entry.getKey()));
    }
    private Optional<Formation> snapshot(UUID actor, long root, String source,
                                         Position expectedCenter, Set<Long> expectedMembers) {
        if (!SPACE.equals(source) && !SURFACE_DESTINATIONS.contains(source)) return Optional.empty();
        try {
            var found = assemblies.current(source, root);
            if (found == null || found.isEmpty()) return Optional.empty();
            var assembly = found.get();
            if (assembly == null || assembly.rootShipId != root
                || expectedCenter != null && !expectedCenter.equals(assembly.center)
                || expectedMembers != null && !expectedMembers.equals(assembly.members)
                || !pilots.isCurrentPilot(actor, source, assembly)
                || !readiness.destinationEnabled(source)) return Optional.empty();
            return Optional.of(assembly);
        } catch (RuntimeException ex) { return Optional.empty(); }
    }
    private Optional<Formation> checked(UUID actor, long root, String source, String destination,
                                        Set<Long> expectedMembers) {
        if (!(SPACE.equals(source) && SURFACE_DESTINATIONS.contains(destination)
            || SURFACE_DESTINATIONS.contains(source) && SPACE.equals(destination))) return Optional.empty();
        var found = snapshot(actor, root, source, null, expectedMembers);
        if (found.isEmpty()) return Optional.empty();
        try {
            if (!readiness.destinationEnabled(destination)
                || !readiness.ready(source, destination, found.get())) return Optional.empty();
            return found;
        } catch (RuntimeException ex) { return Optional.empty(); }
    }
}
