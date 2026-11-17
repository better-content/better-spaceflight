package com.bettercontent.spaceflight.travel;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.bettercontent.spaceflight.travel.ServerTravelPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

final class ServerTravelPolicyTest {
    private static final class Fixture {
        final UUID actor = UUID.randomUUID();
        Formation formation = new Formation(7, Set.of(7L), 32, pos(0, 513, 0));
        boolean pilot = true, ready = true, enabled = true, missing = false, throwOnRead = false;
        String disabledDestination;
        UUID additionalPilot;
        final ServerTravelPolicy policy = new ServerTravelPolicy(
            (actor, source, assembly) -> pilot && (this.actor.equals(actor)
                || additionalPilot != null && additionalPilot.equals(actor)),
            (source, root) -> {
                if (throwOnRead) throw new IllegalStateException("world unloading");
                return missing ? Optional.empty() : Optional.of(formation);
            },
            new TransferReadiness() {
                @Override public boolean destinationEnabled(String id) {
                    return enabled && !id.equals(disabledDestination)
                        && (id.equals(SPACE) || SURFACE_DESTINATIONS.contains(id));
                }
                @Override public boolean ready(String source, String destination, Formation assembly) {
                    return ready;
                }
            });
        Fixture() { policy.observe(motion(OVERWORLD, pos(0, 510, 0), pos(0, 511, 0), 0)); }
        Motion motion(String source, Position from, Position to, long tick) {
            formation = new Formation(7, formation.members(), formation.blockCount(), to);
            return new Motion(actor, 7, source, from, to, new ServerGameTick(tick));
        }
        boolean confirm(Offer offer, long tick) {
            return policy.confirm(offer.nonce(), actor, 7, offer.source(), offer.destination(), new ServerGameTick(tick));
        }
    }
    private static Position pos(double x, double y, double z) { return new Position(x, y, z); }
    private static Motion ascent(Fixture f, String source, long tick) {
        return f.motion(source, pos(0, 511, 0), pos(0, 513, 0), tick);
    }
    @Test void surfaceAllowlistIsExactAndAscentMustCrossUpwardWithExplicitConfirmation() {
        assertEquals(Set.of(OVERWORLD, WASTELANDS, TWILIGHT, ABYSSAL), SURFACE_DESTINATIONS);
        for (String source : SURFACE_DESTINATIONS) {
            var f = new Fixture();
            assertTrue(f.policy.observe(f.motion(source, pos(0, 512, 0), pos(0, 511, 0), 0)).isEmpty());
            var offer = f.policy.observe(ascent(f, source, 1)).orElseThrow();
            assertEquals(source, offer.source()); assertEquals(SPACE, offer.destination());
            assertEquals("surface-ascent", offer.region());
            assertEquals(601, offer.expiresAt().value());
            assertTrue(f.confirm(offer, 2));
            assertFalse(f.confirm(offer, 2)); // nonce never reusable
        }
        var f = new Fixture();
        assertTrue(f.policy.observe(ascent(f, "minecraft:the_nether", 0)).isEmpty());
        assertTrue(f.policy.observe(f.motion(OVERWORLD, pos(0, 511, 0), pos(0, 512, 0), 1)).isEmpty());
    }
    @Test void fourCardinalRegionsAreDistinctLabeledAndCannotOverlap() {
        assertEquals(4, GATES.size());
        assertEquals(Set.of(OVERWORLD, WASTELANDS, TWILIGHT, ABYSSAL),
            GATES.stream().map(GateRegion::destination).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("Overworld", "Wastelands", "Twilight Forest", "Abyssal"),
            GATES.stream().map(GateRegion::label).collect(java.util.stream.Collectors.toSet()));
        assertEquals(pos(0, 256, -1024), GATES.get(0).center());
        assertEquals(pos(1024, 256, 0), GATES.get(1).center());
        assertEquals(pos(0, 256, 1024), GATES.get(2).center());
        assertEquals(pos(-1024, 256, 0), GATES.get(3).center());
        for (var gate : GATES) {
            assertEquals(GATE_ACTIVATION_RADIUS, gate.radius());
            assertEquals(GATE_CENTER_Y, gate.center().y());
            for (var other : GATES) if (gate != other)
                assertTrue(gate.center().distanceSquared(other.center())
                    > Math.pow(gate.radius() + other.radius(), 2));
        }
    }
    @Test void sweptGateDetectsFastOutsideToOutsidePassButConfirmationRequiresInside() {
        var f = new Fixture(); var gate = GATES.get(0);
        Position before = pos(-100, 256, -1024), after = pos(100, 256, -1024);
        assertFalse(gate.contains(before)); assertFalse(gate.contains(after));
        assertTrue(gate.intersects(before, after));
        var offer = f.policy.observe(f.motion(SPACE, before, after, 1)).orElseThrow();
        assertEquals(OVERWORLD, offer.destination());
        assertFalse(f.confirm(offer, 2)); // no teleport for a fly-through outside the sphere
        // Leave fully, then enter again and confirm while physically inside.
        f.policy.observe(f.motion(SPACE, after, pos(101, 256, -1024), 3));
        var second = f.policy.observe(f.motion(SPACE, pos(101, 256, -1024), gate.center(), 4)).orElseThrow();
        assertTrue(f.confirm(second, 4));
    }
    @Test void successfulGateRequiresCooldownAndExitBeforeRearm() {
        var f = new Fixture(); var gate = GATES.get(1);
        Position outside = pos(1124, 256, 0);
        var first = f.policy.observe(f.motion(SPACE, outside, gate.center(), 1)).orElseThrow();
        assertTrue(f.confirm(first, 1));
        assertTrue(f.policy.observe(f.motion(SPACE, gate.center(), gate.center(), 201)).isEmpty());
        f.policy.observe(f.motion(SPACE, gate.center(), outside, 202));
        assertTrue(f.policy.observe(f.motion(SPACE, outside, gate.center(), 202)).isPresent());
    }
    @Test void ascentCooldownAndDescendToRearmBothRequired() {
        var f = new Fixture(); var first = f.policy.observe(ascent(f, OVERWORLD, 0)).orElseThrow();
        assertTrue(f.confirm(first, 1));
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 199)).isEmpty());
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 201)).isEmpty());
        f.policy.observe(f.motion(OVERWORLD, pos(0, 513, 0), pos(0, 500, 0), 202));
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 203)).isPresent());
    }
    @Test void nonceExpiresAtExactlyThirtySecondsAndUnauthorizedCannotConsumeIt() {
        var f = new Fixture(); var first = f.policy.observe(ascent(f, OVERWORLD, 0)).orElseThrow();
        assertFalse(f.policy.confirm(first.nonce(), UUID.randomUUID(), 7, OVERWORLD, SPACE, new ServerGameTick(1)));
        assertTrue(f.confirm(first, 1)); // unauthorized caller cannot consume another pilot's token
        var fresh = new Fixture(); var second = fresh.policy.observe(ascent(fresh, OVERWORLD, 0)).orElseThrow();
        assertFalse(fresh.policy.confirm(second.nonce(), fresh.actor, 7, OVERWORLD,
            WASTELANDS, new ServerGameTick(1)));
        assertTrue(fresh.confirm(second, 1)); // mismatched route does not consume
        var stale = new Fixture(); var third = stale.policy.observe(ascent(stale, OVERWORLD, 0)).orElseThrow();
        assertFalse(stale.confirm(third, 600));
        assertFalse(stale.confirm(third, 599));
    }
    @Test void confirmationRevalidatesPilotMembershipPositionAndConcurrentTransfer() {
        for (int fail = 0; fail < 5; fail++) {
            var f = new Fixture(); var offer = f.policy.observe(ascent(f, OVERWORLD, 0)).orElseThrow();
            if (fail == 0) f.pilot = false;
            if (fail == 1) f.formation = new Formation(7, Set.of(7L, 8L), 32, pos(0, 513, 0));
            if (fail == 2) f.formation = new Formation(7, Set.of(7L), 32, pos(0, 500, 0));
            if (fail == 3) f.ready = false;
            if (fail == 4) f.disabledDestination = SPACE;
            assertFalse(f.confirm(offer, 1));
            assertFalse(f.confirm(offer, 1));
        }
    }
    @Test void disabledOrMissingProviderDestinationsAndAssembliesDenyEvenOffers() {
        var f = new Fixture(); f.enabled = false;
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 1)).isEmpty());
        f.enabled = true; f.disabledDestination = SPACE;
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 2)).isEmpty());
        f.disabledDestination = null; f.missing = true;
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 2)).isEmpty());
        f.missing = false; f.throwOnRead = true;
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 3)).isEmpty());
        f.throwOnRead = false; f.ready = false;
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 4)).isEmpty());
        assertThrows(NullPointerException.class, () -> new ServerTravelPolicy(null,
            (source, root) -> Optional.empty(), new TransferReadiness() {
                public boolean destinationEnabled(String id) { return false; }
                public boolean ready(String source, String dest, Formation formation) { return false; }
            }));
    }
    @Test void pilotSwapAndAlternateConnectedRootCannotBypassCooldownOrLeaveLatch() {
        var f = new Fixture(); f.additionalPilot = UUID.randomUUID();
        f.formation = new Formation(7, Set.of(7L, 8L), 64, pos(0, 511, 0));
        f.policy.observe(f.motion(OVERWORLD, pos(0, 510, 0), pos(0, 511, 0), 1)); // prime new member
        var first = f.policy.observe(ascent(f, OVERWORLD, 2)).orElseThrow();
        assertTrue(f.confirm(first, 2));
        f.policy.invalidateActor(f.actor); // departure cancels offers but not shared cooldown
        f.formation = new Formation(8, Set.of(7L, 8L), 64, pos(0, 513, 0));
        assertTrue(f.policy.observe(new Motion(f.additionalPilot, 8, OVERWORLD,
            pos(0, 511, 0), pos(0, 513, 0), new ServerGameTick(100))).isEmpty());
        f.formation = new Formation(8, Set.of(7L, 8L), 64, pos(0, 500, 0));
        f.policy.observe(new Motion(f.additionalPilot, 8, OVERWORLD,
            pos(0, 513, 0), pos(0, 500, 0), new ServerGameTick(203)));
        f.formation = new Formation(8, Set.of(7L, 8L), 64, pos(0, 513, 0));
        var second = f.policy.observe(new Motion(f.additionalPilot, 8, OVERWORLD,
            pos(0, 511, 0), pos(0, 513, 0), new ServerGameTick(204))).orElseThrow();
        assertTrue(f.policy.confirm(second.nonce(), f.additionalPilot, 8, OVERWORLD, SPACE,
            new ServerGameTick(204)));
    }
    @Test void actorDepartureCancelsPendingWithoutClearingCraftCooldown() {
        var f = new Fixture(); var offer = f.policy.observe(ascent(f, OVERWORLD, 1)).orElseThrow();
        f.policy.invalidateActor(f.actor);
        assertFalse(f.confirm(offer, 1));
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 2)).isEmpty()); // must leave first
    }
    @Test void idleEvictionDoesNotExhaustNewPlayAndRequiresFreshBaseline() {
        var f = new Fixture();
        var first = f.policy.observe(ascent(f, OVERWORLD, 1)).orElseThrow();
        assertTrue(f.confirm(first, 1));
        // 1200 ticks of idleness: state can be evicted after cooldown/pending clear.
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 1_203)).isEmpty());
        f.policy.observe(f.motion(OVERWORLD, pos(0, 513, 0), pos(0, 500, 0), 1_204));
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 1_205)).isPresent());
        f.policy.stopOwner();
        assertFalse(f.policy.confirm(first.nonce(), f.actor, 7, OVERWORLD, SPACE, new ServerGameTick(1_205)));
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 1_206)).isEmpty());
    }
    @Test void trackedMemberCapReclaimsIdleEntriesInsteadOfPermanentlyDenyingNewPlay() {
        var actor = UUID.randomUUID();
        var current = new java.util.concurrent.atomic.AtomicReference<>(pos(0, 511, 0));
        var policy = new ServerTravelPolicy((a, source, assembly) -> a.equals(actor),
            (source, root) -> Optional.of(new Formation(root, Set.of(root), 1, current.get())),
            new TransferReadiness() {
                public boolean destinationEnabled(String dimension) { return true; }
                public boolean ready(String source, String destination, Formation formation) { return true; }
            });
        for (long root = 1; root <= 256; root++)
            assertTrue(policy.observe(new Motion(actor, root, OVERWORLD,
                pos(0, 510, 0), current.get(), new ServerGameTick(0))).isEmpty());
        assertTrue(policy.observe(new Motion(actor, 257, OVERWORLD,
            pos(0, 510, 0), current.get(), new ServerGameTick(0))).isEmpty());
        assertTrue(policy.observe(new Motion(actor, 257, OVERWORLD,
            pos(0, 510, 0), current.get(), new ServerGameTick(1_201))).isEmpty()); // baseline only
        current.set(pos(0, 513, 0));
        assertTrue(policy.observe(new Motion(actor, 257, OVERWORLD,
            pos(0, 511, 0), current.get(), new ServerGameTick(1_202))).isPresent());
    }
    @Test void oversizedEmptyOrNonfiniteAssemblyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Formation(7, Set.of(), 1, pos(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Formation(7, Set.of(7L), 0, pos(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Formation(7, Set.of(7L), 16_385, pos(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Formation(7, Set.of(8L), 1, pos(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> pos(Double.NaN, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> pos(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new ServerGameTick(-1));
        var f = new Fixture(); f.formation = new Formation(7, Set.of(7L), 32, pos(0, 500, 0));
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, Long.MAX_VALUE)).isEmpty());
    }
    @Test void gameTickClockCannotGoBackwardOrBeSubstitutedByPhysicsEpoch() {
        var f = new Fixture(); f.policy.observe(ascent(f, OVERWORLD, 1_000));
        assertFalse(f.policy.confirm(UUID.randomUUID(), f.actor, 7, OVERWORLD, SPACE, new ServerGameTick(0)));
        assertTrue(f.policy.observe(ascent(f, OVERWORLD, 999)).isEmpty());
        assertEquals(600, NONCE_TTL_GAME_TICKS);
        assertEquals(200, COOLDOWN_GAME_TICKS);
    }
}
