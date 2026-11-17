package com.bettercontent.spaceflight.vs.compat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ordered handoff protocol. All native callbacks execute on the physics owner, and all
 * journal fsyncs on the game owner, OUTSIDE the short claim monitor. No destructive
 * dispatch entrypoint exists in batch 1. One registry owns one pipeline/world token;
 * startup journal reconciliation must precede any future request admission.
 * Time/deadlines are exclusively the owning FN's monotonic physics-boundary epoch,
 * never server ticks, wall clock, or an epoch reused after process restart.
 */
public final class ReservationRegistry {
    public enum Stage {
        REQUESTED, CLAIMED, SUSPENDED, WRITING_PREPARING, PREPARING,
        WRITING_TRANSFERRING, TRANSFERRING, WRITING_VERIFYING, VERIFYING,
        WRITING_COMMITTED, COMMITTED, RELEASING, RELEASE_ACK, WRITING_RELEASED,
        RELEASED, RELEASE_PERSIST_UNKNOWN, ABORT_REQUESTED, ABORTING, ABORTED,
        QUARANTINE_REQUESTED, QUARANTINING, RECOVERY_REQUIRED,
        // Reserved for a separately reviewed, exclusive no-game-mutation gate.
        // No production code can enter these cancellation states yet.
        RESTORED_AWAITING_CANCEL, WRITING_CANCELLED, CANCELLED
    }
    public interface Physics {
        /** Physics owner after pending frames drain; selection MUST NOT change bodies. */
        JointMigrationJournal.Record snapshot(UUID nonce, long seed, String source, String destination,
                                              long deadlineBoundaryEpoch) throws Exception;
        /** Freeze ALL claimed members before an ACK may leave the physics owner. */
        void suspend(JointMigrationJournal.Record snapshot) throws Exception;
        /** Pre-dispatch abort: restore source's exact original flags AND velocities, verify. */
        void restoreBeforeDispatch(JointMigrationJournal.Record snapshot) throws Exception;
        /** Post-COMMITTED release, on physics owner only. */
        void release(JointMigrationJournal.Record snapshot) throws Exception;
        /** On release error/cancellation: re-freeze every surviving source/destination body. */
        void quarantine(JointMigrationJournal.Record snapshot) throws Exception;
    }
    public interface Journal { void persist(JointMigrationJournal.Record record) throws IOException; }
    public record Notice(UUID nonce, Stage stage, JointMigrationJournal.Record snapshot, String reason) {}
    private static final int MAX_EPOCHS = 200, MAX_TRACKED = 128;
    private static final Object GLOBAL_LOCK = new Object();
    private record ShipKey(UUID owner, long id) {}
    private record JointKey(UUID owner, int id) {}
    private static final Map<ShipKey, UUID> GLOBAL_SHIPS = new HashMap<>();
    private static final Map<JointKey, UUID> GLOBAL_JOINTS = new HashMap<>();
    private final UUID owner;
    private final Map<UUID, Transfer> transfers = new HashMap<>();
    private final List<Notice> notices = new ArrayList<>();
    private final AtomicBoolean boundaryActive = new AtomicBoolean();
    private boolean closed, retired;

    /**
     * Deliberately unissuable: no current game-thread dispatcher has exclusive
     * no-mutation evidence. The future reviewed gate must be implemented HERE,
     * before a production cancellation request may exist. A caller-provided
     * boolean/callback/UUID is never accepted as such evidence.
     */
    private static final class NoMutationPermit {
        private final UUID nonce, owner;
        private final JointMigrationJournal.Record snapshot;
        private NoMutationPermit(UUID nonce, UUID owner, JointMigrationJournal.Record snapshot) {
            this.nonce = nonce; this.owner = owner; this.snapshot = snapshot;
        }
        private boolean matches(Transfer t, UUID registryOwner) {
            return nonce.equals(t.nonce) && owner.equals(registryOwner) && snapshot == t.snapshot
                && owner.equals(snapshot.owner()) && snapshot.phase() == JointMigrationJournal.Phase.PREPARING;
        }
    }

    /** Journal binding only; not proof of a live owner or permission to cancel/dispatch. */
    public UUID journalOwnerToken() { return owner; }

    /** Same-world coordinators MUST share the exact token; unrelated pipelines use distinct tokens. */
    ReservationRegistry(UUID owner) { this.owner = Objects.requireNonNull(owner, "owner"); }
    /** World owner tokens are unique for one native pipeline lifecycle, never reused after restart. */
    public static ReservationRegistry forWorld(UUID owner) { return new ReservationRegistry(owner); }
    ReservationRegistry() { this(UUID.randomUUID()); } // isolated pure test harness only

    private static final class Transfer {
        final UUID nonce;
        final long seed, deadline;
        final String source, destination;
        Stage stage = Stage.REQUESTED;
        JointMigrationJournal.Record snapshot;
        Object frame;
        String abortReason = "";
        boolean abortRequested;
        // Once a PREPARING write was attempted, even a thrown fsync may have
        // published it. Never clear claims after physical restore without a
        // durable cancellation proof; the production no-mutation gate is absent.
        boolean preparingWriteAttempted;
        // Physics-owner ACK only: distinguishes restored source from genuinely
        // quarantined/uncertain post-dispatch RECOVERY_REQUIRED. Claims remain held.
        boolean sourceOriginalsVerifiedRestored;
        NoMutationPermit cancellationPermit; // only a future exclusive, monotonic game gate could issue this
        final Set<Integer> removed = new HashSet<>(), restored = new HashSet<>();
        Transfer(UUID nonce, long seed, String source, String destination, long deadline) {
            this.nonce = nonce; this.seed = seed; this.source = source; this.destination = destination; this.deadline = deadline;
        }
    }

    /** Package-private: future world service must first scan durable records and bind lifecycle. */
    synchronized UUID request(long seed, String source, String destination,
                              long currentBoundaryEpoch, long deadlineBoundaryEpoch) {
        if (closed || retired || transfers.size() >= MAX_TRACKED || seed <= 0
            || source == null || source.isBlank()
            || destination == null || destination.isBlank() || source.equals(destination)
            || deadlineBoundaryEpoch <= currentBoundaryEpoch
            || deadlineBoundaryEpoch - currentBoundaryEpoch > MAX_EPOCHS)
            throw new IllegalStateException("No bounded open reservation");
        synchronized (GLOBAL_LOCK) {
            if (GLOBAL_SHIPS.containsKey(new ShipKey(owner, seed))) throw new IllegalStateException("Ship already reserved");
            UUID nonce = UUID.randomUUID();
            GLOBAL_SHIPS.put(new ShipKey(owner, seed), nonce);
            transfers.put(nonce, new Transfer(nonce, seed, source, destination, deadlineBoundaryEpoch));
            return nonce;
        }
    }

    /** Native callbacks are serialized by the actual physics owner, never by a game-thread lock. */
    public void physicsBoundary(Physics physics, long boundaryEpoch) {
        if (!boundaryActive.compareAndSet(false, true)) throw new IllegalStateException("Concurrent physics boundary");
        try {
            List<Transfer> selecting = new ArrayList<>(), releasing = new ArrayList<>();
            List<Transfer> aborting = new ArrayList<>(), quarantining = new ArrayList<>();
            synchronized (this) {
                if (retired) throw new IllegalStateException("Retired pipeline owner");
                for (Transfer t : transfers.values()) {
                    if (boundaryEpoch > t.deadline) requestAbort(t, "deadline expired");
                    if (t.stage == Stage.REQUESTED) { t.stage = Stage.CLAIMED; selecting.add(t); }
                    else if (t.stage == Stage.COMMITTED) { t.stage = Stage.RELEASING; releasing.add(t); }
                    else if (t.stage == Stage.ABORT_REQUESTED) { t.stage = Stage.ABORTING; aborting.add(t); }
                    else if (t.stage == Stage.QUARANTINE_REQUESTED) {
                        t.stage = Stage.QUARANTINING; quarantining.add(t);
                    }
                }
            }
            for (Transfer t : selecting) selectAndSuspend(physics, t);
            for (Transfer t : aborting) restoreBeforeDispatch(physics, t);
            for (Transfer t : releasing) releaseOrQuarantine(physics, t);
            for (Transfer t : quarantining) quarantine(physics, t);
        } finally { boundaryActive.set(false); }
    }

    private void selectAndSuspend(Physics physics, Transfer t) {
        JointMigrationJournal.Record snap = null;
        boolean claimed = false;
        try {
            snap = Objects.requireNonNull(physics.snapshot(t.nonce, t.seed, t.source, t.destination, t.deadline));
            if (!snap.nonce().equals(t.nonce) || !snap.source().equals(t.source)
                || !snap.destination().equals(t.destination) || snap.phase() != JointMigrationJournal.Phase.PREPARING
                || snap.deadlineTick() != t.deadline || snap.bodies().stream().noneMatch(b -> b.shipId() == t.seed))
                throw new IllegalStateException("Invalid physics-boundary snapshot");
            synchronized (this) {
                // Atomically expose all member claims AND the immutable snapshot to
                // game-thread helm predicates BEFORE calling physics.suspend outside
                // the monitor. Lock order is registry -> GLOBAL_LOCK everywhere.
                synchronized (GLOBAL_LOCK) {
                    for (var b : snap.bodies()) {
                        UUID holder = GLOBAL_SHIPS.get(new ShipKey(owner, b.shipId()));
                        if (holder != null && !holder.equals(t.nonce)) throw new IllegalStateException("Overlapping ship");
                    }
                    for (var j : snap.joints()) {
                        UUID holder = GLOBAL_JOINTS.get(new JointKey(owner, j.id()));
                        if (holder != null && !holder.equals(t.nonce)) throw new IllegalStateException("Overlapping ID");
                    }
                    for (var b : snap.bodies()) GLOBAL_SHIPS.put(new ShipKey(owner, b.shipId()), t.nonce);
                    for (var j : snap.joints()) GLOBAL_JOINTS.put(new JointKey(owner, j.id()), t.nonce);
                    claimed = true;
                }
                t.snapshot = snap;
            }
            // Even if shutdown raced snapshot, the physics owner alone decides rollback.
            if (!t.abortRequested && !closed) physics.suspend(snap);
            synchronized (this) {
                if (t.abortRequested || closed) {
                    // A shutdown could have arrived DURING suspend. Undo on physics owner now.
                    t.stage = Stage.ABORTING;
                } else {
                    t.stage = Stage.SUSPENDED;
                    notices.add(new Notice(t.nonce, t.stage, snap, ""));
                    return;
                }
            }
            restoreBeforeDispatch(physics, t);
        } catch (Throwable ex) {
            if (snap != null && claimed) {
                // A partial native freeze is possible. Never publish an ACK until
                // source originals are restored and verified on this same physics owner.
                synchronized (this) { t.snapshot = snap; t.stage = Stage.ABORTING; }
                restoreBeforeDispatch(physics, t);
            } else {
                synchronized (this) { abortWithoutSuspension(t, "snapshot failed: " + ex.getClass().getSimpleName()); }
            }
        }
    }
    private void restoreBeforeDispatch(Physics physics, Transfer t) {
        try {
            physics.restoreBeforeDispatch(t.snapshot);
            synchronized (this) {
                t.sourceOriginalsVerifiedRestored = true; // exact physics owner verified originals
                if (t.preparingWriteAttempted && t.cancellationPermit != null
                    && t.cancellationPermit.matches(t, owner) && !closed) {
                    t.stage = Stage.RESTORED_AWAITING_CANCEL;
                    notices.add(new Notice(t.nonce, t.stage, t.snapshot, "physical originals restored; terminal fsync pending"));
                } else if (t.preparingWriteAttempted) {
                    closed = true;
                    fail(t, "PREPARING may be durable; no authoritative no-mutation cancellation gate");
                } else {
                    t.stage = Stage.ABORTED;
                    notices.add(new Notice(t.nonce, t.stage, t.snapshot, t.abortReason));
                    clearClaims(t);
                }
            }
        } catch (Throwable ex) {
            synchronized (this) {
                closed = true; // Ambiguous physical state: hard-stop this world owner.
                fail(t, "pre-dispatch physical rollback failed: " + ex.getClass().getSimpleName());
            }
        }
    }
    private void releaseOrQuarantine(Physics physics, Transfer t) {
        Throwable failure = null;
        try { physics.release(t.snapshot); }
        catch (Throwable ex) { failure = ex; }
        boolean cancelled;
        synchronized (this) {
            cancelled = failure != null || t.abortRequested || closed;
            if (cancelled) t.stage = Stage.QUARANTINING;
            else {
                t.stage = Stage.RELEASE_ACK;
                notices.add(new Notice(t.nonce, t.stage, t.snapshot, ""));
            }
        }
        if (cancelled) quarantine(physics, t); // SAME physics boundary, before returning to simulation
    }
    private void quarantine(Physics physics, Transfer t) {
        try {
            physics.quarantine(t.snapshot);
            synchronized (this) { fail(t, t.abortReason); }
        } catch (Throwable ex) {
            synchronized (this) {
                closed = true;
                fail(t, "AMBIGUOUS quarantine failure: " + ex.getClass().getSimpleName());
            }
        }
    }

    /**
     * Unreachable until a future exclusive game-dispatch ledger is reviewed.
     * This method intentionally cannot be called from a Forge owner or tests:
     * there is no constructor/issuer for its private proof in current source.
     */
    private synchronized boolean beginCancellation(UUID nonce, NoMutationPermit proof) {
        Transfer t = find(nonce);
        if (proof == null || !proof.matches(t, owner) || t.stage != Stage.PREPARING
            || t.frame != null || !t.removed.isEmpty() || !t.restored.isEmpty() || closed) return false;
        t.cancellationPermit = proof;
        t.abortRequested = true;
        t.stage = Stage.ABORT_REQUESTED; // mutually exclusive with WRITING_TRANSFERRING
        return true;
    }

    /** No callable route until the private permit is issued by a real exclusive game gate. */
    private boolean persistCancelled(UUID nonce, JointMigrationJournal disk, NoMutationPermit proof) {
        JointMigrationJournal.Record snap;
        synchronized (this) {
            Transfer t = find(nonce);
            if (proof == null || proof != t.cancellationPermit || !proof.matches(t, owner)
                || t.stage != Stage.RESTORED_AWAITING_CANCEL || closed) return false;
            t.stage = Stage.WRITING_CANCELLED;
            snap = t.snapshot;
        }
        try {
            // Re-read the actual per-world file; a callback/in-memory snapshot
            // cannot prove PREPARING is still the durable predecessor.
            var previous = disk.read(nonce);
            if (previous.phase() != JointMigrationJournal.Phase.PREPARING
                || !previous.sameIdentity(snap) || previous.owner() == null)
                throw new IOException("Durable PREPARING owner or snapshot changed");
            disk.write(snap.withPhase(JointMigrationJournal.Phase.CANCELLED));
        } catch (Throwable ex) {
            synchronized (this) {
                closed = true; // disk may already contain CANCELLED; NEVER re-freeze restored bodies
                fail(find(nonce), "CANCELLED persistence unknown: " + ex.getClass().getSimpleName());
            }
            return false;
        }
        synchronized (this) {
            Transfer t = find(nonce);
            if (t.stage != Stage.WRITING_CANCELLED) {
                closed = true;
                fail(t, "unexpected terminal cancellation ACK state");
                return false;
            }
            clearClaims(t); // only AFTER terminal data AND directory fsync returned
            t.stage = Stage.CANCELLED;
            notices.add(new Notice(nonce, t.stage, snap, "no destructive call; physical originals verified"));
            return true;
        }
    }

    /** Game owner: fsync/atomic replace OUTSIDE shared monitor. */
    public boolean persistPreparing(UUID nonce, Journal journal, long epoch) {
        return persist(nonce, journal, epoch, Stage.SUSPENDED, Stage.WRITING_PREPARING,
            JointMigrationJournal.Phase.PREPARING, Stage.PREPARING);
    }
    public boolean persistTransferring(UUID nonce, Journal journal, long epoch) {
        return persist(nonce, journal, epoch, Stage.PREPARING, Stage.WRITING_TRANSFERRING,
            JointMigrationJournal.Phase.TRANSFERRING, Stage.TRANSFERRING);
    }
    public boolean persistVerified(UUID nonce, Journal journal, boolean exactStateMatches, long epoch) {
        synchronized (this) {
            Transfer t = find(nonce);
            if (t.stage != Stage.TRANSFERRING || t.frame == null || t.snapshot == null
                || t.removed.size() != t.snapshot.joints().size()
                || t.restored.size() != t.snapshot.joints().size() || !exactStateMatches) {
                requestAbort(t, "incomplete native or entity verification"); return false;
            }
        }
        return persist(nonce, journal, epoch, Stage.TRANSFERRING, Stage.WRITING_VERIFYING,
            JointMigrationJournal.Phase.VERIFYING, Stage.VERIFYING);
    }
    public boolean commit(UUID nonce, Journal journal, long epoch) {
        return persist(nonce, journal, epoch, Stage.VERIFYING, Stage.WRITING_COMMITTED,
            JointMigrationJournal.Phase.COMMITTED, Stage.COMMITTED);
    }
    /**
     * A successful physics-owner RELEASE_ACK is the physical release linearization point.
     * From that point no administrative timeout/shutdown may re-freeze bodies: an in-flight
     * RELEASED fsync could already have made a terminal record durable. Complete the
     * marker even after the original deadline; an ambiguous write hard-stops admission.
     */
    public boolean persistReleaseAck(UUID nonce, Journal journal, long epoch) {
        return persist(nonce, journal, epoch, Stage.RELEASE_ACK, Stage.WRITING_RELEASED,
            JointMigrationJournal.Phase.RELEASED, Stage.RELEASED);
    }
    private boolean persist(UUID nonce, Journal journal, long epoch, Stage required, Stage writing,
                            JointMigrationJournal.Phase phase, Stage success) {
        JointMigrationJournal.Record snap;
        synchronized (this) {
            Transfer t = find(nonce);
            if (t.stage != required || (phase != JointMigrationJournal.Phase.RELEASED
                && (epoch > t.deadline || closed))) {
                requestAbort(t, "late/out-of-order " + phase); return false;
            }
            t.stage = writing;
            if (phase == JointMigrationJournal.Phase.PREPARING) t.preparingWriteAttempted = true;
            snap = t.snapshot;
        }
        try { journal.persist(snap.withPhase(phase)); }
        catch (Throwable ex) {
            synchronized (this) {
                Transfer t = find(nonce);
                if (phase == JointMigrationJournal.Phase.RELEASED) {
                    // Atomic replace may have happened before fsync/exception. We do NOT
                    // know whether disk says COMMITTED or RELEASED; either way the bodies
                    // were already verified released. Never quarantine on this ambiguity.
                    releasePersistenceUnknown(t, "RELEASED persistence unknown: "
                        + ex.getClass().getSimpleName());
                } else {
                    if (phase == JointMigrationJournal.Phase.PREPARING
                        || phase == JointMigrationJournal.Phase.TRANSFERRING) closed = true;
                    requestAbort(t, "durable " + phase + " failed");
                }
            }
            return false;
        }
        synchronized (this) {
            Transfer t = find(nonce);
            if (t.stage != writing || (closed && phase != JointMigrationJournal.Phase.RELEASED)) {
                if (phase == JointMigrationJournal.Phase.RELEASED)
                    releasePersistenceUnknown(t, "unexpected terminal ACK state");
                else requestAbort(t, "late journal ACK");
                return false;
            }
            // A successful fsync, claim release, terminal state and notice are ONE
            // monitor-protected decision; drainNotices cannot prune a live transfer.
            if (phase == JointMigrationJournal.Phase.RELEASED) clearClaims(t);
            t.stage = success;
            notices.add(new Notice(nonce, success, snap, ""));
            return true;
        }
    }

    /** Exact FG object, nonce, producer pipeline; mixed unrelated removals remain unchanged. */
    public synchronized boolean tagFrame(UUID nonce, Object frame, Set<Long> removedShips, long epoch) {
        Transfer t = find(nonce);
        if (frame == null || t.stage != Stage.TRANSFERRING || t.frame != null || epoch > t.deadline
            || !removedShips.containsAll(shipIds(t.snapshot))) {
            requestAbort(t, "ambiguous or mismatched frame"); return false;
        }
        t.frame = frame;
        return true;
    }
    public synchronized boolean eligibleRemoval(UUID nonce, Object frame, long shipId, int jointId) {
        Transfer t = transfers.get(nonce);
        return t != null && t.stage == Stage.TRANSFERRING && t.frame == frame
            && nonce.equals(ownerOfShip(shipId)) && nonce.equals(ownerOfJoint(jointId))
            && t.snapshot.joints().stream().anyMatch(j -> j.id() == jointId)
            && !t.removed.contains(jointId);
    }
    public synchronized void didRemove(UUID nonce, Object frame, long shipId, int jointId, boolean backendResult) {
        Transfer t = find(nonce);
        if (!backendResult || !eligibleRemoval(nonce, frame, shipId, jointId) || !t.removed.add(jointId))
            requestAbort(t, "native removal false/duplicate/unexpected");
    }
    public synchronized boolean readyToRestore(UUID nonce, boolean allBodiesPresent, boolean idCollision) {
        Transfer t = find(nonce);
        return t.stage == Stage.TRANSFERRING && t.frame != null && !idCollision && allBodiesPresent
            && t.removed.size() == t.snapshot.joints().size();
    }
    public synchronized void restored(UUID nonce, int jointId, boolean backendResult) {
        Transfer t = find(nonce);
        if (!backendResult || t.stage != Stage.TRANSFERRING || !t.removed.contains(jointId)
            || !t.restored.add(jointId)) requestAbort(t, "restore false/throw/duplicate");
    }
    public synchronized boolean restoredComplete(UUID nonce) {
        Transfer t = find(nonce);
        return t.stage == Stage.TRANSFERRING && t.snapshot != null
            && t.restored.size() == t.snapshot.joints().size();
    }

    public synchronized void abort(UUID nonce, String reason) { requestAbort(find(nonce), Objects.requireNonNull(reason)); }
    public synchronized void abortByShip(long id, String reason) {
        for (Transfer t : transfers.values()) if (t.stage != Stage.RELEASED && t.stage != Stage.ABORTED
            && (t.seed == id || t.snapshot != null && shipIds(t.snapshot).contains(id))) requestAbort(t, reason);
    }
    public synchronized void abortByJoint(int id, String reason) {
        for (Transfer t : transfers.values()) if (t.stage != Stage.RELEASED && t.stage != Stage.ABORTED && t.snapshot != null
            && t.snapshot.joints().stream().anyMatch(j -> j.id() == id)) requestAbort(t, reason);
    }
    public synchronized void timeout(long epoch) {
        for (Transfer t : transfers.values()) if (epoch > t.deadline)
            requestAbort(t, "deadline expired");
    }
    /** Never changes body state on game thread. Queues physical cleanup where safe. */
    public synchronized void shutdown() {
        closed = true;
        for (Transfer t : transfers.values()) requestAbort(t, "shutdown");
    }

    private void requestAbort(Transfer t, String reason) {
        if (t.stage == Stage.RELEASED || t.stage == Stage.ABORTED || t.stage == Stage.CANCELLED
            || t.stage == Stage.RECOVERY_REQUIRED || t.stage == Stage.RELEASE_PERSIST_UNKNOWN
            || t.stage == Stage.RELEASE_ACK || t.stage == Stage.WRITING_RELEASED
            || t.stage == Stage.WRITING_CANCELLED) return;
        t.abortReason = reason;
        t.abortRequested = true;
        switch (t.stage) {
            case REQUESTED -> abortWithoutSuspension(t, reason);
            case CLAIMED, ABORTING, RELEASING, QUARANTINING -> { /* physics owns in-flight action */ }
            case SUSPENDED, PREPARING, WRITING_PREPARING, WRITING_TRANSFERRING -> t.stage = Stage.ABORT_REQUESTED;
            case COMMITTED, VERIFYING, WRITING_COMMITTED, WRITING_VERIFYING, TRANSFERRING -> fail(t, reason);
            case ABORT_REQUESTED, QUARANTINE_REQUESTED -> { /* already queued */ }
            default -> fail(t, reason);
        }
    }
    private void abortWithoutSuspension(Transfer t, String reason) {
        t.stage = Stage.ABORTED;
        notices.add(new Notice(t.nonce, t.stage, null, reason));
        clearClaims(t);
    }
    private void releasePersistenceUnknown(Transfer t, String reason) {
        closed = true;
        t.stage = Stage.RELEASE_PERSIST_UNKNOWN;
        notices.add(new Notice(t.nonce, t.stage, t.snapshot, reason));
        // Keep claims: only a future startup reconciliation may interpret the disk.
        // Never suggest physical quarantine: a RELEASED file may already be durable.
    }
    private void fail(Transfer t, String reason) {
        if (t.stage == Stage.RECOVERY_REQUIRED || t.stage == Stage.RELEASED || t.stage == Stage.ABORTED
            || t.stage == Stage.CANCELLED) return;
        t.stage = Stage.RECOVERY_REQUIRED;
        notices.add(new Notice(t.nonce, t.stage, t.snapshot, reason));
    }
    private void clearClaims(Transfer t) {
        synchronized (GLOBAL_LOCK) {
            GLOBAL_SHIPS.remove(new ShipKey(owner, t.seed), t.nonce);
            if (t.snapshot != null) {
                for (var b : t.snapshot.bodies()) GLOBAL_SHIPS.remove(new ShipKey(owner, b.shipId()), t.nonce);
                for (var j : t.snapshot.joints()) GLOBAL_JOINTS.remove(new JointKey(owner, j.id()), t.nonce);
            }
        }
    }
    /** Only after exact physics pipeline STOP confirmed by its owning lifecycle service. */
    public synchronized void retireAfterPipelineStopped() {
        if (!closed || boundaryActive.get() || transfers.values().stream().anyMatch(t ->
            t.stage == Stage.WRITING_PREPARING || t.stage == Stage.WRITING_TRANSFERRING
                || t.stage == Stage.WRITING_VERIFYING || t.stage == Stage.WRITING_COMMITTED
                || t.stage == Stage.WRITING_RELEASED || t.stage == Stage.WRITING_CANCELLED))
            throw new IllegalStateException("Active physics/journal owner cannot retire");
        for (Transfer t : transfers.values()) clearClaims(t); // no physical release; pipeline no longer exists
        transfers.clear(); notices.clear(); retired = true;
    }
    public synchronized List<Notice> drainNotices() {
        var copy = List.copyOf(notices);
        notices.clear();
        // Terminal records have already delivered their ACK; durable journal remains.
        // The physics bridge prunes the matching detached snapshot at its next boundary.
        transfers.entrySet().removeIf(entry -> entry.getValue().stage == Stage.RELEASED
            || entry.getValue().stage == Stage.ABORTED || entry.getValue().stage == Stage.CANCELLED);
        return copy;
    }
    public synchronized Stage stageIfKnown(UUID nonce) {
        Transfer t = transfers.get(nonce);
        return t == null ? null : t.stage;
    }
    /** Physics-owned original-source ACK; never evidence of cleared admission claims. */
    public synchronized boolean sourceOriginalsVerifiedRestored(UUID nonce) {
        Transfer t = transfers.get(nonce);
        return t != null && t.sourceOriginalsVerifiedRestored;
    }
    public synchronized Stage stage(UUID nonce) { return find(nonce).stage; }
    public synchronized JointMigrationJournal.Record snapshot(UUID nonce) { return find(nonce).snapshot; }
    /** Claims can block admission while physically restored bodies must no longer be mutation-sealed. */
    private static boolean sealed(Transfer transfer) {
        Stage stage = transfer.stage;
        // RECOVERY_REQUIRED also covers post-dispatch quarantine. Only an
        // exact physics-verified predispatch restore ends mutation seals here.
        if (stage == Stage.RECOVERY_REQUIRED && transfer.sourceOriginalsVerifiedRestored) return false;
        return stage != Stage.REQUESTED && stage != Stage.ABORTED && stage != Stage.CANCELLED
            && stage != Stage.RESTORED_AWAITING_CANCEL && stage != Stage.WRITING_CANCELLED
            && stage != Stage.RELEASE_ACK && stage != Stage.WRITING_RELEASED
            && stage != Stage.RELEASED && stage != Stage.RELEASE_PERSIST_UNKNOWN;
    }
    /** Member ID is only a short monitor-protected, immutable lease-metadata lookup. */
    public synchronized boolean shouldSealShipMutation(long id) {
        for (Transfer t : transfers.values()) if (sealed(t) && t.snapshot != null
            && t.snapshot.bodies().stream().anyMatch(b -> b.shipId() == id)) return true;
        return false;
    }
    public synchronized boolean shouldSealJointMutation(int id) {
        for (Transfer t : transfers.values()) if (sealed(t) && t.snapshot != null
            && t.snapshot.joints().stream().anyMatch(j -> j.id() == id)) return true;
        return false;
    }
    /** Game-thread helm only: both source AND destination bodies may be held. No FN map access. */
    public synchronized boolean shouldSealGameMutation(long id, String dimension) {
        if (dimension == null) return false;
        for (Transfer t : transfers.values()) if (sealed(t) && t.snapshot != null
            && (dimension.equals(t.source) || dimension.equals(t.destination))
            && t.snapshot.bodies().stream().anyMatch(b -> b.shipId() == id)) return true;
        return false;
    }
    public boolean shouldHoldPhysicsCallback(long id) { return shouldSealShipMutation(id); }
    public boolean heldShip(long id) { return ownerOfShip(id) != null; }
    public boolean heldJoint(int id) { return ownerOfJoint(id) != null; }
    private UUID ownerOfShip(long id) { synchronized (GLOBAL_LOCK) { return GLOBAL_SHIPS.get(new ShipKey(owner, id)); } }
    private UUID ownerOfJoint(int id) { synchronized (GLOBAL_LOCK) { return GLOBAL_JOINTS.get(new JointKey(owner, id)); } }
    private synchronized Transfer find(UUID nonce) {
        Transfer t = transfers.get(nonce);
        if (t == null) throw new IllegalArgumentException("Unknown reservation");
        return t;
    }
    private static Set<Long> shipIds(JointMigrationJournal.Record snap) {
        var ids = new HashSet<Long>(); for (var b : snap.bodies()) ids.add(b.shipId()); return ids;
    }
}
