package com.bettercontent.spaceflight.vs.compat.recovery;

import com.bettercontent.spaceflight.vs.compat.JointMigrationJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

final class CaptureEnvelopeTest {
    @TempDir Path root;
    private JointMigrationJournal.Record joint() {
        return new JointMigrationJournal.Record(UUID.randomUUID(), "minecraft:dimension:minecraft:overworld",
            "minecraft:dimension:better_spaceflight:space", 30, JointMigrationJournal.Phase.PREPARING,
            List.of(new JointMigrationJournal.Body(100, true, 0, 0, 0, 0, 0, 0),
                new JointMigrationJournal.Body(101, false, 1, 2, 3, 4, 5, 6)),
            List.of(new JointMigrationJournal.Joint(99, 100, 101, new byte[]{1, 2, 3})));
    }
    private CaptureEnvelope.Builder builder() throws IOException {
        return new CaptureEnvelope.Builder(root, joint(), 30);
    }
    @Test void detachedPartsAndManifestBindPhysicalJournalWithoutClaimingWholeRecovery() throws Exception {
        var source = new byte[]{1, 2};
        var envelope = builder().add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK, "overworld:1,2", source))
            .add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_CAPABILITY_STATUS, "overworld:1,2", new byte[]{0}))
            .add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_TICK_DETAILS, "overworld:1,2", new byte[]{1}))
            .build(new CaptureEnvelope.Status(false, false, false,
                List.of("Ship codec/attachment inventory unqualified", "Chunk coverage unproven")));
        source[0] = 99;
        assertArrayEquals(new byte[]{1, 2}, envelope.pieces().get(1).bytes());
        var exposed = envelope.pieces().get(1).bytes(); exposed[1] = 50;
        assertArrayEquals(new byte[]{1, 2}, envelope.pieces().get(1).bytes());
        assertEquals(CaptureEnvelope.Kind.JOINT_JOURNAL, envelope.pieces().get(0).kind());
        assertFalse(envelope.status().chunkCoverageProven());
        assertFalse(envelope.status().actorClosureProven());
        assertFalse(envelope.status().shipMetadataProven());
        assertTrue(envelope.manifest().length > 120);
    }
    @Test void duplicateAndOversizedPartsFailWithoutPartialAcceptance() throws Exception {
        var b = builder();
        var part = new CaptureEnvelope.Piece(CaptureEnvelope.Kind.ENTITY, "uuid", new byte[]{1});
        b.add(part);
        assertThrows(IllegalArgumentException.class, () -> b.add(part));
        assertThrows(IllegalArgumentException.class, () -> new CaptureEnvelope.Piece(
            CaptureEnvelope.Kind.ENTITY, "huge", new byte[CaptureEnvelope.MAX_PART_BYTES + 1]));
        var bytes = new CaptureEnvelope.BoundedBytes(3);
        bytes.write(new byte[]{1, 2}, 0, 2);
        assertThrows(CaptureEnvelope.BoundedBytes.Overflow.class, () -> bytes.write(new byte[]{3, 4}, 0, 2));
        assertArrayEquals(new byte[]{1, 2}, bytes.toByteArray());
    }
    @Test void detachedOperationMustMatchExactServerRegistryJournalBytes() throws Exception {
        var j = joint();
        var envelope = new CaptureEnvelope.Builder(root, j, 30).build(new CaptureEnvelope.Status(
            false, false, false, List.of("Partial")));
        assertTrue(envelope.matchesBoundJournal(j));
        assertFalse(envelope.matchesBoundJournal(new JointMigrationJournal.Record(j.nonce(), j.source(),
            j.destination(), j.deadlineTick() + 1, j.phase(), j.bodies(), j.joints())));
        assertFalse(envelope.matchesBoundJournal(j.withPhase(JointMigrationJournal.Phase.TRANSFERRING)));
    }
    @Test void noFabricatedShipKeysChunkCapabilityOrActorClosure() throws Exception {
        var j = joint();
        var b = new CaptureEnvelope.Builder(root, j, 30);
        b.add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.VS_SHIP, j.source() + ":100", new byte[]{1}));
        b.add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.VS_SHIP, j.source() + ":102", new byte[]{1}));
        assertThrows(IllegalStateException.class, () -> b.build(
            new CaptureEnvelope.Status(false, false, true, List.of("Wrong source/member"))));
        var chunk = new CaptureEnvelope.Builder(root, j, 30).add(new CaptureEnvelope.Piece(
            CaptureEnvelope.Kind.CHUNK, "chunk", new byte[]{1}));
        assertThrows(IllegalStateException.class, () -> chunk.build(
            new CaptureEnvelope.Status(false, false, false, List.of("Caps not recorded"))));
        chunk.add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_CAPABILITY_STATUS, "chunk", new byte[]{0}));
        chunk.add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_TICK_DETAILS, "chunk", new byte[]{1}));
        assertThrows(IllegalStateException.class, () -> chunk.build(
            new CaptureEnvelope.Status(true, false, false, List.of("Cannot certify ForgeCaps"))));
        assertThrows(IllegalStateException.class, () -> chunk.build(
            new CaptureEnvelope.Status(false, true, false, List.of("Cannot certify onboard actors"))));
    }
    @Test void blockedWorkerCannotLoseLeaseOnCallerFutureCompletionCancellationOrFailure() throws Exception {
        Object server = new Object();
        var envelope = builder().build(new CaptureEnvelope.Status(false, false, false, List.of("No coverage")));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writer = new CaptureEnvelopeWriter(server, ignored -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("I/O timeout"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
            throw new IOException("Durable write failed");
        });
        try {
            var stage = writer.submit(envelope);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(stage.toCompletableFuture().cancel(true));
            assertTrue(stage.toCompletableFuture().complete(root));
            assertTrue(stage.toCompletableFuture().completeExceptionally(new IOException("fake")));
            writer.close(); // nonblocking; actual I/O is still inside the action
            assertTrue(writer.actualTaskRunningForTest());
            assertFalse(writer.durableCaptureReady(envelope));
            assertThrows(IllegalStateException.class, () -> new CaptureEnvelopeWriter(server));
            release.countDown();
            assertThrows(ExecutionException.class, () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
            try (var next = new CaptureEnvelopeWriter(server)) { assertTrue(next.ownsOpenCapture(server)); }
        } finally { release.countDown(); writer.close(); }
    }
    @Test void rejectedSubmissionDoesNotStrandServerLease() throws Exception {
        Object server = new Object();
        var envelope = builder().build(new CaptureEnvelope.Status(false, false, false, List.of("No coverage")));
        var writer = new CaptureEnvelopeWriter(server);
        writer.rejectSubmissionsForTest();
        assertThrows(IllegalStateException.class, () -> writer.submit(envelope));
        assertTrue(writer.ownsOpenCapture(server));
        writer.close();
        try (var next = new CaptureEnvelopeWriter(server)) { assertTrue(next.ownsOpenCapture(server)); }
    }
    @Test void offThreadFsyncAndAtomicPublishRetainSingleServerLeaseUntilClose() throws Exception {
        Object server = new Object();
        var envelope = builder().add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK, "1,2", new byte[]{3, 4, 5}))
            .add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_CAPABILITY_STATUS, "1,2", new byte[]{0}))
            .add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_TICK_DETAILS, "1,2", new byte[]{1}))
            .build(new CaptureEnvelope.Status(false, false, false, List.of("Member coverage not proven")));
        try (var writer = new CaptureEnvelopeWriter(server)) {
            assertThrows(IllegalStateException.class, () -> new CaptureEnvelopeWriter(server));
            var future = writer.submit(envelope);
            assertThrows(IllegalStateException.class, () -> writer.submit(envelope));
            Path published = future.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(writer.durableCaptureReady(envelope));
            assertTrue(Files.isDirectory(published));
            assertArrayEquals(envelope.pieces().get(0).bytes(), Files.readAllBytes(published.resolve("0000.bin")));
            byte[] manifest = Files.readAllBytes(published.resolve("manifest.bscp"));
            assertEquals(CaptureEnvelope.sha256(manifest),
                Files.readString(published.resolve("manifest.sha256"), StandardCharsets.US_ASCII));
            assertThrows(IllegalStateException.class, () -> new CaptureEnvelopeWriter(server));
        }
        try (var released = new CaptureEnvelopeWriter(server)) {
            assertTrue(Files.isDirectory(root.resolve("better-spaceflight-capture")));
        }
    }
}
