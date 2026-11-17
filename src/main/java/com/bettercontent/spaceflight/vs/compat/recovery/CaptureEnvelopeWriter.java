package com.bettercontent.spaceflight.vs.compat.recovery;

import com.bettercontent.spaceflight.vs.compat.JointMigrationJournal;
import com.bettercontent.spaceflight.vs.compat.ReservationRegistry;
import com.bettercontent.spaceflight.vs.compat.internal.ForgeVsServerOwner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One capture lease per exact server reference. The caller supplies ONLY detached
 * bounded bytes; all fsyncs and atomic renames run off game/physics threads.
 * The completion view cannot control the actual worker task/lease. Failed staging
 * dirs remain untrusted evidence, not completed snapshots.
 */
public final class CaptureEnvelopeWriter implements AutoCloseable {
    @FunctionalInterface interface DiskAction { Path persist(CaptureEnvelope envelope) throws IOException; }
    private static final Map<Object, CaptureEnvelopeWriter> LEASES = new IdentityHashMap<>();
    private final Object server;
    private final ReservationRegistry boundRegistry; // null only in package-private pure tests
    private final DiskAction action;
    private final ThreadPoolExecutor worker;
    private boolean submitted, closed;
    private volatile boolean taskRunning;
    private volatile Path durablePath; // set by real worker ONLY after complete fsync/rename
    private CaptureEnvelope submittedEnvelope;
    /** Production entry requires exact current server lifetime/owner/registry. */
    public CaptureEnvelopeWriter(MinecraftServer server, ReservationRegistry registry) {
        this(server, registry, CaptureEnvelopeWriter::persist);
        if (!ForgeVsServerOwner.ownsCaptureRegistry(server, registry)) {
            close();
            throw new IllegalStateException("Unbound/stopping VS server registry cannot own capture");
        }
    }
    /** Package-private deterministic I/O hook for pure concurrency tests; no production caller. */
    CaptureEnvelopeWriter(Object server) { this(server, null, CaptureEnvelopeWriter::persist); }
    CaptureEnvelopeWriter(Object server, DiskAction action) { this(server, null, action); }
    private CaptureEnvelopeWriter(Object server, ReservationRegistry registry, DiskAction action) {
        this.server = Objects.requireNonNull(server);
        this.boundRegistry = registry;
        this.action = Objects.requireNonNull(action);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "better-spaceflight-capture-io");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        synchronized (LEASES) {
            if (LEASES.containsKey(server)) { worker.shutdown(); throw new IllegalStateException("Another server capture already active"); }
            LEASES.put(server, this);
        }
    }
    /** Admission token for a game-thread capture before detached I/O submission. */
    public synchronized boolean ownsOpenCapture(Object expectedServer) {
        synchronized (LEASES) { return !closed && !submitted && server == expectedServer && LEASES.get(server) == this; }
    }
    /**
     * Non-blocking submission; the stage is a read-only completion VIEW, never
     * the lease authority. Even mutating toCompletableFuture() only affects a
     * dependent mirror, not the actual disk task or the server lease.
     */
    public synchronized CompletionStage<Path> submit(CaptureEnvelope envelope) {
        if (closed || submitted) throw new IllegalStateException("Capture lease closed/submitted");
        Objects.requireNonNull(envelope);
        if (boundRegistry != null) {
            MinecraftServer owner = (MinecraftServer) server;
            if (!owner.isSameThread()) throw new IllegalStateException("Capture submission outside server game thread");
            var current = boundRegistry.snapshot(envelope.nonce());
            try {
                if (!ForgeVsServerOwner.ownsCaptureRegistry(owner, boundRegistry)
                    || boundRegistry.stageIfKnown(envelope.nonce()) != ReservationRegistry.Stage.PREPARING
                    || !envelope.worldRoot().equals(owner.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize())
                    || current == null || current.phase() != JointMigrationJournal.Phase.PREPARING
                    || !envelope.matchesBoundJournal(current))
                    throw new IllegalStateException("Capture envelope no longer belongs to server world/registry/journal");
            } catch (IOException ex) { throw new IllegalStateException("Capture journal byte binding inaccessible", ex); }
        }
        submitted = true;
        submittedEnvelope = envelope;
        taskRunning = true;
        var internal = new CompletableFuture<Path>();
        try {
            worker.execute(() -> {
                Path result = null;
                Throwable failure = null;
                try { result = action.persist(envelope); }
                catch (Throwable ex) { failure = ex; }
                finally {
                    // Lease release is controlled ONLY by this worker's finally,
                    // never future.isDone(), cancellation or a caller callback.
                    synchronized (CaptureEnvelopeWriter.this) {
                        if (failure == null) durablePath = result;
                        taskRunning = false;
                        if (closed) releaseLease();
                    }
                }
                if (failure == null) internal.complete(result);
                else internal.completeExceptionally(failure);
            });
        } catch (RuntimeException rejection) {
            taskRunning = false;
            submitted = false; submittedEnvelope = null; // no worker was accepted
            throw new IllegalStateException("Capture I/O worker rejected submission", rejection);
        }
        return internal.minimalCompletionStage();
    }
    /** Nonblocking game-thread check, never a caller-controlled future completion. */
    public synchronized boolean durableCaptureReady(CaptureEnvelope expected) {
        return !closed && submitted && !taskRunning && durablePath != null && expected == submittedEnvelope
            && durablePath.equals(expected.worldRoot().resolve("better-spaceflight-capture")
                .resolve(expected.nonce() + ".capture"));
    }
    // Package-private deterministic hooks: pure tests never exercise real Forge or wait on game/physics threads.
    boolean actualTaskRunningForTest() { return taskRunning; }
    void rejectSubmissionsForTest() { worker.shutdown(); }
    private static Path persist(CaptureEnvelope evidence) throws IOException {
        Path root = evidence.worldRoot();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("World root absent or symlink: " + root);
        Path base = root.resolve("better-spaceflight-capture");
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(base);
            forceDirectory(root);
        }
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(base))
            throw new IOException("Capture directory absent or symlink: " + base);
        Path target = base.resolve(evidence.nonce() + ".capture");
        Path pending = base.resolve(evidence.nonce() + ".pending");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.exists(pending, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Capture operation already exists; never replace it: " + evidence.nonce());
        Files.createDirectory(pending); forceDirectory(base);
        int i = 0;
        for (CaptureEnvelope.Piece p : evidence.pieces()) {
            byte[] bytes = p.bytes();
            if (bytes.length > CaptureEnvelope.MAX_PART_BYTES) throw new IOException("Part changed after detachment");
            writeDurable(pending.resolve(String.format("%04d.bin", i++)), bytes);
        }
        byte[] manifest = evidence.manifest();
        writeDurable(pending.resolve("manifest.bscp"), manifest);
        writeDurable(pending.resolve("manifest.sha256"),
            CaptureEnvelope.sha256(manifest).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        forceDirectory(pending);
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE); // never REPLACE_EXISTING
        forceDirectory(base);
        return target;
    }
    private static void writeDurable(Path target, byte[] bytes) throws IOException {
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) out.write(buffer);
            out.force(true);
        }
    }
    private static void forceDirectory(Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) { channel.force(true); }
    }
    /** No disk waits. An accepted task keeps the lease until its actual finally. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        worker.shutdown(); // DOES NOT interrupt or join a running fsync
        if (!taskRunning) releaseLease();
    }
    private void releaseLease() {
        synchronized (LEASES) { LEASES.remove(server, this); }
    }
}
