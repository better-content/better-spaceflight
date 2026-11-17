package com.bettercontent.spaceflight.vs.compat.recovery;

import com.bettercontent.spaceflight.vs.SectionLayout;
import com.bettercontent.spaceflight.vs.compat.JointMigrationJournal;
import com.bettercontent.spaceflight.vs.compat.ReservationRegistry;
import com.bettercontent.spaceflight.vs.compat.internal.ForgeVsServerOwner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Explicit read-only/game-thread capture entry for a selected set. There is NO
 * transport caller. Active chunks are a floor, not proof of all pending-only
 * loaded/unloaded shipyard chunks, actors, POI or external references. A
 * returned envelope is deliberately INCOMPLETE; it must never mean ready.
 */
public final class ReviewOnlyCapture {
    private ReviewOnlyCapture() {}
    public static CaptureEnvelope selected(ServerLevel source, ServerLevel destination,
                                           Path worldRoot, CaptureEnvelopeWriter lease,
                                           ReservationRegistry registry, JointMigrationJournal.Record joint,
                                           List<ChunkPos> candidateChunks,
                                           List<? extends Entity> candidateActorRoots) throws IOException {
        return capture(source, destination, worldRoot, lease, registry, joint,
            candidateChunks, candidateActorRoots, null);
    }
    /** Invoke on a later game tick only AFTER actual worker durability; no disk wait. */
    public static void revalidate(ServerLevel source, ServerLevel destination,
                                  Path worldRoot, CaptureEnvelopeWriter lease,
                                  ReservationRegistry registry, JointMigrationJournal.Record joint,
                                  List<ChunkPos> candidateChunks,
                                  List<? extends Entity> candidateActorRoots,
                                  CaptureEnvelope original) throws IOException {
        if (original == null || lease == null || !lease.durableCaptureReady(original))
            throw new IOException("Original envelope has not completed real durable publication");
        var now = capture(source, destination, worldRoot, lease, registry, joint,
            candidateChunks, candidateActorRoots, original);
        SemanticCaptureComparator.requireSame(original, now);
        if (!lease.durableCaptureReady(original) || !ForgeVsServerOwner.ownsCaptureRegistry(source.getServer(), registry)
            || registry.stageIfKnown(joint.nonce()) != ReservationRegistry.Stage.PREPARING)
            throw new IOException("Capture owner/journal changed during semantic source revalidation");
        // This read-only check has no handoff caller or readiness flag.
    }
    private static CaptureEnvelope capture(ServerLevel source, ServerLevel destination,
                                           Path worldRoot, CaptureEnvelopeWriter lease,
                                           ReservationRegistry registry, JointMigrationJournal.Record joint,
                                           List<ChunkPos> candidateChunks,
                                           List<? extends Entity> candidateActorRoots,
                                           CaptureEnvelope original) throws IOException {
        if (source.getServer() != destination.getServer() || !source.getServer().isSameThread())
            throw new IllegalStateException("Capture requires one server game-thread owner");
        if (lease == null || !(original == null ? lease.ownsOpenCapture(source.getServer())
                : lease.durableCaptureReady(original))
            || !ForgeVsServerOwner.ownsCaptureRegistry(source.getServer(), registry))
            throw new IOException("Another capture already holds the server or lease was submitted");
        if (worldRoot == null || !worldRoot.toAbsolutePath().normalize().equals(
            source.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()))
            throw new IOException("Capture manifest world root does not equal server-owned world");
        if (registry == null || joint == null || joint.phase() != JointMigrationJournal.Phase.PREPARING
            || registry.stageIfKnown(joint.nonce()) != ReservationRegistry.Stage.PREPARING
            || !joint.equals(registry.snapshot(joint.nonce()))
            || !joint.source().equals(VSGameUtilsKt.getDimensionId(source))
            || !joint.destination().equals(VSGameUtilsKt.getDimensionId(destination)))
            throw new IOException("Physical journal source/destination/phase mismatch");
        new SectionLayout(source.getMinBuildHeight(), source.getHeight()).requireCompatible(
            new SectionLayout(destination.getMinBuildHeight(), destination.getHeight()));
        if (candidateChunks == null || candidateChunks.isEmpty()
            || candidateChunks.size() > CaptureEnvelope.MAX_CHUNKS
            || new HashSet<>(candidateChunks).size() != candidateChunks.size())
            throw new IOException("Invalid bounded candidate chunk set");
        var candidates = Set.copyOf(candidateChunks);
        var world = VSGameUtilsKt.getShipObjectWorld(source);
        var requiredActive = new HashSet<ChunkPos>();
        var claims = new ArrayList<org.valkyrienskies.core.api.ships.ServerShip>();
        for (var body : joint.bodies()) {
            var canonical = world.getAllShips().getById(body.shipId());
            var loaded = world.getLoadedShips().getById(body.shipId());
            if (canonical == null || loaded == null || loaded.getId() != body.shipId()
                || !canonical.getChunkClaimDimension().equals(joint.source()))
                throw new IOException("Missing canonical loaded connected member " + body.shipId());
            claims.add(canonical);
            canonical.getActiveChunksSet().forEach((x, z) -> requiredActive.add(new ChunkPos(x, z)));
            if (requiredActive.size() > CaptureEnvelope.MAX_CHUNKS)
                throw new IOException("Active connected shipyard chunk count exceeds bound");
        }
        if (!candidates.containsAll(requiredActive))
            throw new IOException("Selected chunks omit known active connected shipyard chunks");
        var discoveredLoaded = LoadedShipyardChunkSelection.current(source, claims);
        if (!candidates.equals(new HashSet<>(discoveredLoaded)))
            throw new IOException("Selected chunks differ from bounded actual loaded claim holders; pending-only chunks omitted");
        var builder = new CaptureEnvelope.Builder(worldRoot, joint, source.getGameTime());
        for (ChunkPos pos : candidateChunks) {
            if (claims.stream().noneMatch(ship -> ship.getChunkClaim().contains(pos.x, pos.z)))
                throw new IOException("Candidate chunk outside ALL connected member claims: " + pos);
            var captured = LoadedChunkCapture.capture(source, joint.source(), pos.x, pos.z);
            builder.add(captured.part());
            builder.add(captured.exactTickDetails());
            // Persist exact per-chunk provider observation, validated against
            // read-only Forge registrations around ONE original serializer call.
            builder.add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_CAPABILITY_STATUS,
                captured.part().key(), new byte[]{(byte) (captured.capabilityPayloadObserved() ? 1 : 0)}));
        }
        var memberIds = new HashSet<Long>();
        for (var body : joint.bodies()) memberIds.add(body.shipId());
        var discoveredActors = LoadedOnboardActorSelection.current(source, memberIds);
        if (candidateActorRoots == null || candidateActorRoots.size() != discoveredActors.size()
            || !new HashSet<>(candidateActorRoots).equals(new HashSet<>(discoveredActors)))
            throw new IOException("Selected riding roots differ from bounded actual VS mount/contact/claim scan");
        var actors = LoadedActorCapture.candidates(source, discoveredActors);
        for (var piece : actors.pieces()) builder.add(piece);
        builder.add(LoadedOnboardActorSelection.membershipEvidence(source, discoveredActors));
        for (var body : joint.bodies()) builder.add(PinnedVsShipCapture.capture(source, body.shipId()));
        if (!(original == null ? lease.ownsOpenCapture(source.getServer()) : lease.durableCaptureReady(original))
            || !ForgeVsServerOwner.ownsCaptureRegistry(source.getServer(), registry)
            || registry.stageIfKnown(joint.nonce()) != ReservationRegistry.Stage.PREPARING
            || !joint.equals(registry.snapshot(joint.nonce())))
            throw new IOException("Capture lease or physical journal changed during source capture");
        // Lack of independent loaded/pending-tick chunk enumeration, complete
        // onboard actor discovery, and just-before-dispatch revalidation are
        // explicit blockers, NOT a fake green recovery/provision flag.
        var blockers = new ArrayList<String>();
        blockers.add("All visible/updating/pending-unload FULL holders checked; unloaded/on-disk meaningful claim chunks remain unproven");
        blockers.add("Forge provider omission checked for selected FULL chunks only; arbitrary provider/BE serializer fidelity unqualified");
        blockers.add("Loaded source/online actor scan performed; missing contact/offline/on-deck actor closure unproven");
        blockers.add("Full-world restoration/handoff and post-check state continuity unverified");
        return builder.build(new CaptureEnvelope.Status(false, false, true, blockers));
    }
}
