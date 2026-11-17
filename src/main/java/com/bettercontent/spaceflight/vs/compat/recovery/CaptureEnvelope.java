package com.bettercontent.spaceflight.vs.compat.recovery;

import com.bettercontent.spaceflight.vs.compat.JointMigrationJournal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Detached, bounded EVIDENCE only. No restore/dispatch/readiness API. */
public final class CaptureEnvelope {
    public static final int MAX_MEMBERS = 64, MAX_CHUNKS = 128, MAX_ENTITIES = 128;
    public static final int MAX_PART_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
    public enum Kind { CHUNK, CHUNK_TICK_DETAILS, CHUNK_CAPABILITY_STATUS, ENTITY, PLAYER, RIDING_GRAPH, ACTOR_MEMBERSHIP, VS_SHIP, JOINT_JOURNAL }
    public record Piece(Kind kind, String key, byte[] bytes) {
        public Piece {
            Objects.requireNonNull(kind); Objects.requireNonNull(key); Objects.requireNonNull(bytes);
            if (key.isBlank() || key.length() > 256 || bytes.length == 0 || bytes.length > MAX_PART_BYTES)
                throw new IllegalArgumentException("Invalid/oversized capture piece");
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record Status(boolean chunkCoverageProven, boolean actorClosureProven,
                         boolean shipMetadataProven, List<String> blockers) {
        public Status {
            blockers = List.copyOf(blockers);
            if (blockers.size() > 32 || blockers.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 256))
                throw new IllegalArgumentException("Invalid bounded blocker list");
        }
    }
    private final Path worldRoot;
    private final UUID nonce;
    private final String source, destination, jointPhase;
    private final long captureGameTick;
    private final List<Long> members;
    private final List<Piece> pieces;
    private final Status status;
    private CaptureEnvelope(Path worldRoot, UUID nonce, String source, String destination,
                            String jointPhase, long captureGameTick, List<Long> members,
                            List<Piece> pieces, Status status) {
        this.worldRoot = worldRoot; this.nonce = nonce; this.source = source;
        this.destination = destination; this.jointPhase = jointPhase;
        this.captureGameTick = captureGameTick; this.members = List.copyOf(members);
        this.pieces = List.copyOf(pieces); this.status = status;
    }
    public Path worldRoot() { return worldRoot; }
    public UUID nonce() { return nonce; }
    public long captureGameTick() { return captureGameTick; }
    boolean samePhysicalIdentity(CaptureEnvelope other) throws IOException {
        return other != null && worldRoot.equals(other.worldRoot) && nonce.equals(other.nonce)
            && source.equals(other.source) && destination.equals(other.destination)
            && jointPhase.equals(other.jointPhase) && members.equals(other.members)
            && java.util.Arrays.equals(journalBytes(), other.journalBytes());
    }
    private byte[] journalBytes() throws IOException {
        for (var piece : pieces) if (piece.kind == Kind.JOINT_JOURNAL) return piece.bytes();
        throw new IOException("Missing physical journal binding");
    }
    /** Compare immutable journal bytes to the exact live owner snapshot without any disk read. */
    boolean matchesBoundJournal(JointMigrationJournal.Record record) throws IOException {
        if (record == null || !nonce.equals(record.nonce())
            || !source.equals(record.source()) || !destination.equals(record.destination())
            || !jointPhase.equals(record.phase().name())
            || !members.equals(record.bodies().stream().map(JointMigrationJournal.Body::shipId).toList()))
            return false;
        for (var piece : pieces) if (piece.kind == Kind.JOINT_JOURNAL)
            return java.util.Arrays.equals(piece.bytes, JointMigrationJournal.detachedBytes(record));
        return false;
    }
    public List<Piece> pieces() { return pieces; }
    public Status status() { return status; }
    public byte[] manifest() throws IOException {
        var sink = new BoundedBytes(256 * 1024);
        try (var out = new DataOutputStream(sink)) {
            out.writeInt(0x42534350); out.writeInt(1); // BSCP v1; never a restore command
            out.writeUTF(worldRoot.toString()); out.writeLong(nonce.getMostSignificantBits());
            out.writeLong(nonce.getLeastSignificantBits()); out.writeUTF(source); out.writeUTF(destination);
            out.writeUTF(jointPhase); out.writeLong(captureGameTick);
            out.writeInt(members.size()); for (long id : members) out.writeLong(id);
            out.writeBoolean(status.chunkCoverageProven()); out.writeBoolean(status.actorClosureProven());
            out.writeBoolean(status.shipMetadataProven());
            out.writeInt(status.blockers().size()); for (String blocker : status.blockers()) out.writeUTF(blocker);
            out.writeInt(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                Piece p = pieces.get(i);
                out.writeUTF(p.kind().name()); out.writeUTF(p.key()); out.writeUTF(String.format("%04d.bin", i));
                out.writeInt(p.bytes.length); out.write(digest(p.bytes));
            }
        }
        return sink.toByteArray();
    }
    public static String sha256(byte[] bytes) { return java.util.HexFormat.of().formatHex(digest(bytes)); }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static final class Builder {
        private final Path worldRoot;
        private final UUID nonce;
        private final String source, destination, jointPhase;
        private final long captureGameTick;
        private final List<Long> members;
        private final List<Piece> pieces = new ArrayList<>();
        private final Set<String> keys = new HashSet<>();
        private int total, chunks, entities, ships;
        /** Member/nonce fields derive from journal; server-owner registry identity is checked by ReviewOnlyCapture. */
        public Builder(Path root, JointMigrationJournal.Record joint, long captureGameTick) throws IOException {
            Objects.requireNonNull(joint);
            this.worldRoot = Objects.requireNonNull(root).toAbsolutePath().normalize();
            this.nonce = joint.nonce();
            this.source = joint.source(); this.destination = joint.destination();
            this.jointPhase = joint.phase().name(); this.captureGameTick = captureGameTick;
            List<Long> members = joint.bodies().stream().map(JointMigrationJournal.Body::shipId).toList();
            if (source.isBlank() || destination.isBlank() || source.equals(destination)
                || source.length() > 256 || destination.length() > 256 || captureGameTick < 0
                || members == null || members.isEmpty() || members.size() > MAX_MEMBERS
                || members.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(members).size() != members.size())
                throw new IllegalArgumentException("Invalid bounded connected member set");
            this.members = List.copyOf(members);
            add(new Piece(Kind.JOINT_JOURNAL, nonce.toString(), JointMigrationJournal.detachedBytes(joint)));
        }
        public Builder add(Piece piece) {
            Objects.requireNonNull(piece);
            if (keys.contains(piece.kind + ":" + piece.key)) throw new IllegalArgumentException("Duplicate capture piece");
            int next = Math.addExact(total, piece.bytes.length);
            if (next > MAX_TOTAL_BYTES || piece.bytes.length > MAX_PART_BYTES)
                throw new IllegalArgumentException("Capture budget exceeded");
            switch (piece.kind) {
                case CHUNK -> { if (chunks >= MAX_CHUNKS) throw new IllegalArgumentException("Chunk count bound"); }
                case ENTITY, PLAYER -> { if (entities >= MAX_ENTITIES) throw new IllegalArgumentException("Actor count bound"); }
                case VS_SHIP -> { if (ships >= MAX_MEMBERS) throw new IllegalArgumentException("Ship count bound"); }
                case CHUNK_CAPABILITY_STATUS -> {
                    if (piece.bytes.length != 1 || piece.bytes[0] < 0 || piece.bytes[0] > 1)
                        throw new IllegalArgumentException("Invalid per-chunk capability observation marker");
                }
                case RIDING_GRAPH, ACTOR_MEMBERSHIP, JOINT_JOURNAL -> {
                    if (pieces.stream().anyMatch(p -> p.kind == piece.kind))
                        throw new IllegalArgumentException("Duplicate singleton evidence piece");
                }
            }
            keys.add(piece.kind + ":" + piece.key);
            switch (piece.kind) {
                case CHUNK -> chunks++;
                case ENTITY, PLAYER -> entities++;
                case VS_SHIP -> ships++;
                default -> { }
            }
            total = next; pieces.add(piece); return this;
        }
        public CaptureEnvelope build(Status status) {
            Objects.requireNonNull(status);
            if (pieces.stream().noneMatch(p -> p.kind == Kind.JOINT_JOURNAL))
                throw new IllegalStateException("Physical journal binding required even for incomplete evidence");
            if (entities > 0 && (pieces.stream().noneMatch(p -> p.kind == Kind.RIDING_GRAPH)
                || pieces.stream().noneMatch(p -> p.kind == Kind.ACTOR_MEMBERSHIP)))
                throw new IllegalStateException("Loaded actor riding graph/VS membership evidence missing");
            var expectedShipKeys = new HashSet<String>();
            for (long id : members) expectedShipKeys.add(source + ":" + id);
            var observedShipKeys = new HashSet<String>();
            var chunkKeys = new HashSet<String>();
            var capabilityKeys = new HashSet<String>();
            var tickKeys = new HashSet<String>();
            for (var piece : pieces) {
                if (piece.kind == Kind.VS_SHIP) observedShipKeys.add(piece.key);
                if (piece.kind == Kind.CHUNK) chunkKeys.add(piece.key);
                if (piece.kind == Kind.CHUNK_CAPABILITY_STATUS) capabilityKeys.add(piece.key);
                if (piece.kind == Kind.CHUNK_TICK_DETAILS) tickKeys.add(piece.key);
            }
            if (status.shipMetadataProven() && !observedShipKeys.equals(expectedShipKeys))
                throw new IllegalStateException("Exact source/member ship metadata keys differ");
            if (!chunkKeys.equals(capabilityKeys) || !chunkKeys.equals(tickKeys))
                throw new IllegalStateException("Each selected chunk needs capability + exact scheduled-tick evidence");
            // Neither missing/pending-only chunk and Forge serializer completeness
            // nor independent onboard actor discovery is established in 3a.
            if (status.chunkCoverageProven() || status.actorClosureProven())
                throw new IllegalStateException("Batch 3a cannot claim chunk/actor closure");
            return new CaptureEnvelope(worldRoot, nonce, source, destination, jointPhase,
                captureGameTick, members, pieces, status);
        }
    }
    /** Hard size bound DURING encoding; never call writeValueAsBytes on unbounded payloads. */
    public static final class BoundedBytes extends ByteArrayOutputStream {
        private final int max;
        public BoundedBytes(int max) { if (max < 1 || max > MAX_PART_BYTES) throw new IllegalArgumentException(); this.max = max; }
        @Override public synchronized void write(int value) { if (count == max) throw new Overflow(); super.write(value); }
        @Override public synchronized void write(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len > max - count) throw new Overflow();
            super.write(b, off, len);
        }
        public static final class Overflow extends IllegalArgumentException {
            public Overflow() { super("Encoded capture part exceeds hard bound"); }
        }
    }
}
