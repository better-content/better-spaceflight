package com.bettercontent.spaceflight.vs.compat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, bounded record of a pinned-VS joint handoff. NOT a backup of shipyard chunks,
 * block entities, scheduled ticks, passengers, or a license to auto-recover an interrupted
 * native teleport. The caller must independently establish complete world recovery BEFORE
 * dispatch. `deadlineTick` stores the owning FN physics-boundary epoch, NOT a Minecraft
 * server tick nor a deadline usable after process restart. Every incomplete record blocks
 * new admission until manual world/assembly reconciliation. Any unreadable/ambiguous record
 * must keep travel disabled.
 */
public final class JointMigrationJournal {
    private static final int MAGIC = 0x42534a4d; // BSJM
    private static final int LEGACY_SCHEMA = 1;
    private static final int OWNER_SCHEMA = 2;
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_SHIPS = 64;
    private static final int MAX_JOINTS = 512;
    private static final int MAX_JOINT_BYTES = 16_384;
    private final Path directory;

    // Disk codes below are fixed, independent of this enum's declaration order.
    public enum Phase { PREPARING, TRANSFERRING, VERIFYING, COMMITTED, RELEASED, RECOVERY_REQUIRED, CANCELLED }
    private static int phaseCode(Phase phase) {
        return switch (phase) {
            case PREPARING -> 0;
            case TRANSFERRING -> 1;
            case VERIFYING -> 2;
            case COMMITTED -> 3;
            case RELEASED -> 4;
            case RECOVERY_REQUIRED -> 5;
            case CANCELLED -> 6; // schema 2 only
        };
    }
    private static Phase readPhase(int code, int schema) throws IOException {
        return switch (code) {
            case 0 -> Phase.PREPARING;
            case 1 -> Phase.TRANSFERRING;
            case 2 -> Phase.VERIFYING;
            case 3 -> Phase.COMMITTED;
            case 4 -> Phase.RELEASED;
            case 5 -> Phase.RECOVERY_REQUIRED;
            case 6 -> {
                if (schema != OWNER_SCHEMA) throw new IOException("Legacy cancellation phase forbidden");
                yield Phase.CANCELLED;
            }
            default -> throw new IOException("Unknown phase");
        };
    }

    public record Joint(int id, long ship0, long ship1, byte[] definition) {
        public Joint {
            if (id < 0 || id == Integer.MAX_VALUE || ship0 <= 0 || ship1 <= 0 || ship0 == ship1
                || definition == null || definition.length == 0 || definition.length > MAX_JOINT_BYTES) {
                throw new IllegalArgumentException("Invalid joint definition/endpoint");
            }
            definition = definition.clone();
        }
        @Override public byte[] definition() { return definition.clone(); }
        @Override public boolean equals(Object o) {
            return o instanceof Joint j && id == j.id && ship0 == j.ship0 && ship1 == j.ship1
                && Arrays.equals(definition, j.definition);
        }
        @Override public int hashCode() { return 31 * Objects.hash(id, ship0, ship1) + Arrays.hashCode(definition); }
    }

    public record Body(long shipId, boolean wasStatic, double vx, double vy, double vz,
                       double wx, double wy, double wz) {
        public Body {
            if (shipId <= 0 || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)
                || !Double.isFinite(wx) || !Double.isFinite(wy) || !Double.isFinite(wz)) {
                throw new IllegalArgumentException("Invalid body/velocity");
            }
        }
    }

    public record Record(UUID nonce, String source, String destination, long deadlineTick, Phase phase,
                         List<Body> bodies, List<Joint> joints, UUID owner) {
        /** Schema-1 constructor retained for existing records/tests; these cannot be cancelled. */
        public Record(UUID nonce, String source, String destination, long deadlineTick, Phase phase,
                      List<Body> bodies, List<Joint> joints) {
            this(nonce, source, destination, deadlineTick, phase, bodies, joints, null);
        }
        public Record {
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(phase, "phase");
            if (source == null || destination == null || source.isBlank() || destination.isBlank()
                || source.length() > 256 || destination.length() > 256 || source.equals(destination)
                || deadlineTick < 0 || bodies == null || bodies.isEmpty() || bodies.size() > MAX_SHIPS
                || joints == null || joints.size() > MAX_JOINTS) {
                throw new IllegalArgumentException("Invalid joint migration record");
            }
            bodies = List.copyOf(bodies);
            joints = List.copyOf(joints);
            var ships = new HashSet<Long>();
            var ids = new HashSet<Integer>();
            for (Body body : bodies) if (!ships.add(body.shipId())) throw new IllegalArgumentException("Duplicate ship");
            for (Joint joint : joints) {
                if (!ids.add(joint.id()) || !ships.contains(joint.ship0()) || !ships.contains(joint.ship1())) {
                    throw new IllegalArgumentException("Duplicate ID or external joint endpoint");
                }
            }
        }
        public Record withPhase(Phase next) {
            return new Record(nonce, source, destination, deadlineTick, next, bodies, joints, owner);
        }
        boolean sameIdentity(Record other) {
            return nonce.equals(other.nonce) && Objects.equals(owner, other.owner)
                && source.equals(other.source) && destination.equals(other.destination)
                && deadlineTick == other.deadlineTick && bodies.equals(other.bodies) && joints.equals(other.joints);
        }
    }

    /** Directory belongs to exactly one server world. The constructor does not create it. */
    public JointMigrationJournal(Path worldDirectory) {
        directory = Objects.requireNonNull(worldDirectory, "worldDirectory").resolve("better-spaceflight-joint-migration");
    }

    public Path directory() { return directory; }

    private Path path(UUID nonce) { return directory.resolve(nonce + ".bsjm"); }

    /** Fsync data and the parent directory before any native mutation may be dispatched. */
    public synchronized void write(Record record) throws IOException {
        Objects.requireNonNull(record, "record");
        boolean alreadyPresent = Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Refusing symlink journal directory");
        if (!alreadyPresent) {
            try (FileChannel parent = FileChannel.open(directory.getParent(), StandardOpenOption.READ)) {
                parent.force(true);
            }
        }
        Path target = path(record.nonce());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Record previous = read(record.nonce());
            if (!previous.sameIdentity(record) || !allowed(previous, record)) {
                throw new IOException("Conflicting journal replacement or phase regression");
            }
        } else if (record.phase() != Phase.PREPARING) {
            throw new IOException("First durable state must be PREPARING");
        }
        byte[] encoded = encode(record);
        Path temporary = Files.createTempFile(directory, ".bsjm-", ".pending");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean allowed(Record old, Record next) {
        if (next.phase() == Phase.CANCELLED)
            return old.owner() != null && old.phase() == Phase.PREPARING;
        if (next.phase() == Phase.RECOVERY_REQUIRED)
            return old.phase() != Phase.RELEASED && old.phase() != Phase.CANCELLED;
        return switch (old.phase()) {
            case PREPARING -> next.phase() == Phase.TRANSFERRING;
            case TRANSFERRING -> next.phase() == Phase.VERIFYING;
            case VERIFYING -> next.phase() == Phase.COMMITTED;
            case COMMITTED -> next.phase() == Phase.RELEASED;
            case RELEASED, RECOVERY_REQUIRED, CANCELLED -> false;
        };
    }

    public Record read(UUID nonce) throws IOException {
        Record result = readPath(path(nonce));
        if (!result.nonce().equals(nonce)) throw new IOException("Journal nonce/filename mismatch");
        return result;
    }

    /** Caller MUST treat unknown filenames and a decode error as a blocking recovery condition. */
    public List<Record> scan() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (Files.isSymbolicLink(directory)) throw new IOException("Refusing symlink journal directory");
        var result = new ArrayList<Record>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".bsjm-") && name.endsWith(".pending")) {
                    throw new IOException("Unfinished journal replacement: " + name);
                }
                if (!name.matches("[0-9a-fA-F-]{36}\\.bsjm")) throw new IOException("Unrecognized journal entry: " + name);
                Record record = readPath(entry);
                if (!name.equals(record.nonce() + ".bsjm")) throw new IOException("Journal identity/filename mismatch");
                result.add(record);
            }
        }
        return List.copyOf(result);
    }

    private static Record readPath(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) {
            throw new IOException("Missing, symlinked or oversized joint journal: " + path);
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 40) throw new IOException("Truncated journal");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(payload), Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length))) {
            throw new IOException("Journal checksum mismatch");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC) throw new IOException("Unsupported journal magic");
            int schema = in.readInt();
            if (schema != LEGACY_SCHEMA && schema != OWNER_SCHEMA) throw new IOException("Unsupported journal schema");
            UUID nonce = new UUID(in.readLong(), in.readLong());
            UUID owner = schema == OWNER_SCHEMA ? new UUID(in.readLong(), in.readLong()) : null;
            String source = in.readUTF(), destination = in.readUTF();
            long deadline = in.readLong();
            Phase phase = readPhase(in.readUnsignedByte(), schema);
            int bodyCount = in.readUnsignedByte();
            if (bodyCount < 1 || bodyCount > MAX_SHIPS) throw new IOException("Invalid body count");
            var bodies = new ArrayList<Body>(bodyCount);
            for (int i = 0; i < bodyCount; i++) bodies.add(new Body(in.readLong(), in.readBoolean(),
                in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()));
            int jointCount = in.readUnsignedShort();
            if (jointCount > MAX_JOINTS) throw new IOException("Invalid joint count");
            var joints = new ArrayList<Joint>(jointCount);
            for (int i = 0; i < jointCount; i++) {
                int id = in.readInt();
                long ship0 = in.readLong(), ship1 = in.readLong();
                int length = in.readUnsignedShort();
                if (length == 0 || length > MAX_JOINT_BYTES) throw new IOException("Invalid definition length");
                byte[] definition = new byte[length];
                in.readFully(definition);
                joints.add(new Joint(id, ship0, ship1, definition));
            }
            if (in.available() != 0) throw new IOException("Trailing journal bytes");
            return new Record(nonce, source, destination, deadline, phase, bodies, joints, owner);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid journal fields", e);
        }
    }

    /** Detached physical record bytes for a capture manifest; no disk I/O or journal phase change. */
    public static byte[] detachedBytes(Record record) throws IOException {
        return encode(Objects.requireNonNull(record));
    }
    private static byte[] encode(Record record) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            if (record.phase() == Phase.CANCELLED && record.owner() == null)
                throw new IOException("Legacy PREPARING cannot be cancelled");
            out.writeInt(MAGIC); out.writeInt(record.owner() == null ? LEGACY_SCHEMA : OWNER_SCHEMA);
            out.writeLong(record.nonce().getMostSignificantBits()); out.writeLong(record.nonce().getLeastSignificantBits());
            if (record.owner() != null) {
                out.writeLong(record.owner().getMostSignificantBits()); out.writeLong(record.owner().getLeastSignificantBits());
            }
            out.writeUTF(record.source()); out.writeUTF(record.destination());
            out.writeLong(record.deadlineTick()); out.writeByte(phaseCode(record.phase()));
            out.writeByte(record.bodies().size());
            for (Body body : record.bodies()) {
                out.writeLong(body.shipId()); out.writeBoolean(body.wasStatic());
                out.writeDouble(body.vx()); out.writeDouble(body.vy()); out.writeDouble(body.vz());
                out.writeDouble(body.wx()); out.writeDouble(body.wy()); out.writeDouble(body.wz());
            }
            out.writeShort(record.joints().size());
            for (Joint joint : record.joints()) {
                out.writeInt(joint.id()); out.writeLong(joint.ship0()); out.writeLong(joint.ship1());
                out.writeShort(joint.definition().length); out.write(joint.definition());
            }
        }
        byte[] payload = buffer.toByteArray();
        if (payload.length + 32 > MAX_BYTES) throw new IOException("Journal exceeds size bound");
        byte[] result = Arrays.copyOf(payload, payload.length + 32);
        System.arraycopy(digest(payload), 0, result, payload.length, 32);
        return result;
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
