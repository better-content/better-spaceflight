package com.bettercontent.spaceflight.vs.compat.internal;

import com.bettercontent.spaceflight.vs.compat.mixin.VsFnAccess;
import com.bettercontent.spaceflight.vs.compat.mixin.VsFuAccess;
import com.bettercontent.spaceflight.vs.compat.JointMigrationJournal;
import com.bettercontent.spaceflight.vs.compat.PinnedCallScope;
import com.bettercontent.spaceflight.vs.compat.PinnedCallScope.Mode;
import com.bettercontent.spaceflight.vs.compat.ReservationRegistry;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.events.JointSerializedEvent;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.impl.game.ships.PhysShipImpl;
import org.valkyrienskies.core.impl.shadow.FG;
import org.valkyrienskies.core.impl.shadow.FN;
import org.valkyrienskies.core.impl.shadow.FU;
import org.valkyrienskies.core.impl.shadow.HM;
import org.valkyrienskies.core.impl.shadow.Hg;
import org.valkyrienskies.core.internal.joints.VSJoint;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Isolated INTERNAL bridge to exact pinned VS shadow bytecode. No public upstream API,
 * no reflection, no upstream JAR changes. This batch has NO world-owner binding or
 * destructive dispatch caller: all hooks are inert until a later separately reviewed
 * recovery/backup-aware game-thread service registers one world owner.
 */
public final class PinnedVs2411Bridge {
    private static final Map<FN, Owner> OWNERS = new IdentityHashMap<>();
    // FN is final and inherits Object identity equals/hashCode; weak keys prevent
    // unbound per-tick heartbeat retention after an abandoned server instance.
    private static final Map<FN, WeakReference<Thread>> HEARTBEATS = new WeakHashMap<>();
    private static final Map<UUID, Armed> ARMED = new HashMap<>();
    private static final ThreadLocal<Scope> SCOPED = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> RELEASING = ThreadLocal.withInitial(() -> false);
    private static final int MAX_SHIPS = 64, MAX_JOINTS = 512;
    private record Armed(Owner owner, Set<Long> removedShips) {}
    private record Scope(FU level, int id, VSJoint joint, Mode mode) {}
    private record Snapshot(Map<Integer, VSJoint> liveJoints, Map<Integer, byte[]> frozenBytes,
                            JointMigrationJournal.Record journal) {}
    private static final class Owner {
        final FN physics;
        final Object serverIdentity; // exact MinecraftServer reference, no cross-world ID collision
        final Thread physicsThread; // exact previously observed FQ.h, not arbitrary callback thread
        final ReservationRegistry registry;
        final Map<UUID, Snapshot> snapshots = new HashMap<>();
        final AtomicLong epoch = new AtomicLong(); // physics-boundary sequence; never server ticks
        Owner(FN physics, ReservationRegistry registry, Object serverIdentity, Thread physicsThread) {
            this.physics = physics; this.registry = registry;
            this.serverIdentity = serverIdentity; this.physicsThread = physicsThread;
        }
    }
    private PinnedVs2411Bridge() {}

    // Deliberately package-private: no service is authorized to bind/arm/dispatch in batch 1.
    static synchronized void bindReviewedWorld(FN fn, ReservationRegistry registry,
                                               Object serverIdentity, Thread physicsThread) {
        if (fn == null || registry == null || serverIdentity == null || physicsThread == null
            || !physicsThread.isAlive() || !hasPhysicsHeartbeat(fn, physicsThread))
            throw new IllegalArgumentException("Exact server/native physics-thread owner required");
        if (OWNERS.putIfAbsent(fn, new Owner(fn, registry, serverIdentity, physicsThread)) != null)
            throw new IllegalStateException("Duplicate physics owner");
    }
    static synchronized boolean hasPhysicsHeartbeat(FN fn, Thread expected) {
        var observed = HEARTBEATS.get(fn);
        return expected != null && observed != null && observed.get() == expected;
    }
    static synchronized long currentBoundaryEpoch(FN fn) {
        Owner owner = OWNERS.get(fn);
        if (owner == null) throw new IllegalStateException("No reviewed world owner");
        return owner.epoch.get(); // game/physics thread safe; never mix Minecraft tick count
    }
    static synchronized void armAfterDurableTransferring(FN fn, UUID nonce) {
        Owner owner = OWNERS.get(fn);
        if (owner == null || owner.registry.stage(nonce) != ReservationRegistry.Stage.TRANSFERRING)
            throw new IllegalStateException("Unjournaled frame");
        var snap = owner.registry.snapshot(nonce);
        if (snap == null) throw new IllegalStateException("Missing sealed snapshot");
        Set<Long> ids = new HashSet<>();
        for (var body : snap.bodies()) ids.add(body.shipId());
        for (Armed other : ARMED.values()) if (other.owner.physics == fn)
            throw new IllegalStateException("Only one in-flight assembly per producer frame");
        ARMED.put(nonce, new Armed(owner, Set.copyOf(ids)));
    }

    /**
     * Exact owner cleanup ONLY after the native pipeline has stopped (caller lifecycle proof).
     * Incomplete journals remain on disk; this method does not resume or delete any bodies.
     */
    static synchronized void retireAfterVerifiedPipelineStop(FN fn, ReservationRegistry registry) {
        Owner owner = OWNERS.get(fn);
        if (owner == null || owner.registry != registry) throw new IllegalStateException("Wrong pipeline owner");
        registry.retireAfterPipelineStopped();
        ARMED.entrySet().removeIf(entry -> entry.getValue().owner == owner);
        owner.snapshots.clear();
        OWNERS.remove(fn);
        HEARTBEATS.remove(fn);
    }

    /** Exact FG instance produced by FH.b(), NOT a global latest-intent lookup. */
    public static synchronized void onGameFrame(FN producer, FG frame) {
        if (producer == null) throw new IllegalStateException("No frame producer physics owner");
        Set<Long> removals = new HashSet<>(frame.b());
        ARMED.entrySet().removeIf(entry ->
            entry.getValue().owner.registry.stageIfKnown(entry.getKey()) != ReservationRegistry.Stage.TRANSFERRING);
        Armed match = null;
        UUID nonce = null;
        for (var entry : ARMED.entrySet()) {
            if (entry.getValue().owner.physics == producer && removals.containsAll(entry.getValue().removedShips)) {
                if (match != null) throw new IllegalStateException("Ambiguous VS frame intentions");
                match = entry.getValue(); nonce = entry.getKey();
            }
        }
        if (match == null) return;
        if (!match.owner.registry.tagFrame(nonce, frame, removals, match.owner.epoch.get()))
            throw new IllegalStateException("Frame rejected by reservation");
        ((FrameNonce) (Object) frame).spaceflight$nonce(nonce);
        ARMED.remove(nonce);
    }

    /** Injected after FN.a drains the native frame queue, before FU.physTick/listeners/stepping. */
    public static void onPhysicsBoundary(FN fn) {
        Owner owner;
        synchronized (PinnedVs2411Bridge.class) {
            HEARTBEATS.put(fn, new WeakReference<>(Thread.currentThread()));
            // Both unbound FN key and callback thread value are weak; no retention cycle.
            owner = OWNERS.get(fn);
        }
        if (owner == null) return;
        long epoch = owner.epoch.incrementAndGet();
        owner.registry.timeout(epoch);
        owner.registry.physicsBoundary(new NativePhysics(owner), epoch);
        owner.snapshots.entrySet().removeIf(entry -> {
            var stage = owner.registry.stageIfKnown(entry.getKey());
            return stage == null || stage == ReservationRegistry.Stage.ABORTED
                || stage == ReservationRegistry.Stage.RELEASED;
        });
        synchronized (PinnedVs2411Bridge.class) {
            ARMED.entrySet().removeIf(entry -> entry.getValue().owner == owner
                && owner.registry.stageIfKnown(entry.getKey()) != ReservationRegistry.Stage.TRANSFERRING);
        }
    }

    public static void beforeFrame(FN fn, FG frame) {
        Owner owner;
        synchronized (PinnedVs2411Bridge.class) { owner = OWNERS.get(fn); }
        if (owner == null) return;
        UUID nonce = ((FrameNonce) (Object) frame).spaceflight$nonce();
        for (Long id : frame.b()) if (owner.registry.shouldSealShipMutation(id)
            && (nonce == null || owner.registry.stageIfKnown(nonce) != ReservationRegistry.Stage.TRANSFERRING)) {
            owner.registry.abortByShip(id, "unexpected reserved-body frame; native recovery required");
            // NEVER cancel/drop an entire mixed FG: unrelated native work must still run.
            // The guarded FU delete refuses our ID; implicit backend body deletion remains
            // an abnormal partial state requiring full-world backup/reconciliation.
        }
    }

    public static boolean removeFromFrame(FN fn, FG frame, FU level, int id) {
        Owner owner;
        synchronized (PinnedVs2411Bridge.class) { owner = OWNERS.get(fn); }
        if (owner == null) return level.removeJoint(id);
        UUID nonce = ((FrameNonce) (Object) frame).spaceflight$nonce();
        if (nonce == null || owner.registry.stageIfKnown(nonce) != ReservationRegistry.Stage.TRANSFERRING) {
            if (owner.registry.shouldSealJointMutation(id)) owner.registry.abortByJoint(id, "unmatched native joint removal");
            return level.removeJoint(id);
        }
        Snapshot snap = owner.snapshots.get(nonce);
        if (snap != null && !snap.liveJoints.containsKey(id) && !owner.registry.shouldSealJointMutation(id))
            return level.removeJoint(id); // unrelated joint in the SAME mixed frame
        VSJoint joint = snap == null ? null : snap.liveJoints.get(id);
        byte[] frozen = snap == null ? null : snap.frozenBytes.get(id);
        boolean unchanged = false;
        if (joint != null && frozen != null) {
            try { unchanged = JointDefinitionCodec.stillMatches(joint, frozen); }
            catch (IOException ex) { owner.registry.abort(nonce, "joint pre-removal codec failed"); }
        }
        if (joint == null || frozen == null || joint.getShipId0() == null
            || !owner.registry.eligibleRemoval(nonce, frame, joint.getShipId0(), id)
            || level.getJointById(id) != joint || !unchanged || SCOPED.get() != null) {
            owner.registry.abort(nonce, "unexpected/duplicate/mutated native removal");
            return level.removeJoint(id); // preserve unrelated FG semantics; sealed FU rejects member
        }
        boolean removed = false;
        try {
            SCOPED.set(new Scope(level, id, joint, Mode.REMOVE));
            removed = level.removeJoint(id); // ORIGINAL path removes old backend joint and FU membership
            return removed;
        } catch (RuntimeException | Error ex) {
            owner.registry.abort(nonce, "native removal threw"); throw ex;
        } finally {
            SCOPED.remove();
            owner.registry.didRemove(nonce, frame, joint.getShipId0(), id, removed);
        }
    }

    public static boolean guardedMutation(FU level, int id, VSJoint joint) {
        Scope scope = SCOPED.get();
        if (scope != null && PinnedCallScope.matches(scope.level, scope.id, scope.joint, scope.mode,
            level, id, joint, scope.mode)) return true;
        synchronized (PinnedVs2411Bridge.class) {
            for (Owner owner : OWNERS.values()) {
                if (!ownerOwnsLevel(owner, level)) continue;
                if (owner.registry.shouldSealJointMutation(id)) return false;
                if (joint != null && ((joint.getShipId0() != null && owner.registry.shouldSealShipMutation(joint.getShipId0()))
                    || (joint.getShipId1() != null && owner.registry.shouldSealShipMutation(joint.getShipId1())))) return false;
            }
        }
        return true;
    }
    /** Exact-owner+actual-PhysShip identity guard for optional pinned Eureka physTick. */
    public static boolean suspendEurekaPhysTick(Object listener, PhysShip physShip) {
        if (!(physShip instanceof PhysShipImpl actual) || listener == null) return false;
        synchronized (PinnedVs2411Bridge.class) {
            for (Owner owner : OWNERS.values()) {
                if (Thread.currentThread() != owner.physicsThread
                    || !owner.registry.shouldHoldPhysicsCallback(actual.getId())) continue;
                // FN map traversal is valid only on its exact observed physics owner.
                for (var worldBodies : owner.physics.b().values()) {
                    if (worldBodies.get(actual.getId()) == actual) return true;
                }
            }
        }
        return false;
    }
    /** Game-thread helm mutations: immutable lease metadata only, never physical FN maps. */
    public static boolean denyHeldHelmMutation(Object server, String dimension, long shipId) {
        if (server == null || dimension == null || shipId <= 0) return false;
        synchronized (PinnedVs2411Bridge.class) {
            for (Owner owner : OWNERS.values()) if (owner.serverIdentity == server
                && owner.registry.shouldSealGameMutation(shipId, dimension)) return true;
        }
        return false;
    }
    public static boolean allowStaticChange(PhysShipImpl body, boolean requestedStatic) {
        if (requestedStatic || RELEASING.get()) return true;
        synchronized (PinnedVs2411Bridge.class) {
            for (Owner owner : OWNERS.values()) for (var map : owner.physics.b().values())
                if (map.get(body.getId()) == body && owner.registry.shouldSealShipMutation(body.getId())) return false;
        }
        return true;
    }
    public static boolean nativeMutationAllowed(FU level, HM rawJoint) {
        Scope scope = SCOPED.get();
        Integer native0 = rawJoint.b().a(), native1 = rawJoint.b().c();
        if (scope != null && PinnedCallScope.matches(scope.level, scope.id, scope.joint, scope.mode,
            level, rawJoint.a(), scope.joint, Mode.RESTORE) && native0 != null && native1 != null
            && scope.joint.getShipId0() != null && scope.joint.getShipId1() != null) {
            synchronized (PinnedVs2411Bridge.class) {
                for (Owner owner : OWNERS.values()) if (ownerOwnsLevel(owner, level)) {
                    var bodies = owner.physics.b().get(level.getDimension());
                    if (bodies != null) {
                        var a = bodies.get(scope.joint.getShipId0());
                        var b = bodies.get(scope.joint.getShipId1());
                        if (a != null && b != null && native0 == a.getRigidBodyReference$impl().a()
                            && native1 == b.getRigidBodyReference$impl().a()) return true;
                    }
                }
            }
        }
        if (!guardedMutation(level, rawJoint.a(), null)) return false;
        synchronized (PinnedVs2411Bridge.class) {
            for (Owner owner : OWNERS.values()) if (ownerOwnsLevel(owner, level)) for (var map : owner.physics.b().values()) {
                for (PhysShipImpl body : map.values()) {
                    if (owner.registry.shouldSealShipMutation(body.getId())
                        && ((native0 != null && native0 == body.getRigidBodyReference$impl().a())
                            || (native1 != null && native1 == body.getRigidBodyReference$impl().a()))) return false;
                }
            }
        }
        return true;
    }
    private static boolean ownerOwnsLevel(Owner owner, FU level) {
        return ((VsFnAccess) (Object) owner.physics).spaceflight$levels().get(level.getDimension()) == level;
    }
    public static boolean scopedDelete(FU level, int id) {
        Scope scope = SCOPED.get();
        return scope != null && PinnedCallScope.matches(scope.level, scope.id, scope.joint, scope.mode,
            level, id, scope.joint, Mode.REMOVE);
    }
    public static boolean scopedEvent(FU level, int id, VSJoint joint, JointSerializedEvent.Operation operation) {
        Scope scope = SCOPED.get();
        return scope != null && PinnedCallScope.suppressEvent(scope.level, scope.id, scope.joint, scope.mode,
            level, id, joint, operation.name());
    }

    /** Called after EACH FG, including later ones where new destination bodies finally appear. */
    public static void afterFrame(FN fn) {
        Owner owner;
        synchronized (PinnedVs2411Bridge.class) { owner = OWNERS.get(fn); }
        if (owner == null) return;
        for (var entry : List.copyOf(owner.snapshots.entrySet())) {
            UUID nonce = entry.getKey();
            var stage = owner.registry.stageIfKnown(nonce);
            if (stage == null || stage == ReservationRegistry.Stage.RELEASED
                || stage == ReservationRegistry.Stage.ABORTED) continue;
            var snap = entry.getValue();
            var dest = ((VsFnAccess) (Object) fn).spaceflight$levels().get(snap.journal.destination());
            var bodies = fn.b().get(snap.journal.destination());
            // FG.a can create bodies in DIFFERENT frames. Quarantine destination
            // births even AFTER failure, before physics stepping, until release ACK.
            if (stage != ReservationRegistry.Stage.RELEASE_ACK
                && stage != ReservationRegistry.Stage.WRITING_RELEASED
                && stage != ReservationRegistry.Stage.RELEASE_PERSIST_UNKNOWN
                && !owner.registry.sourceOriginalsVerifiedRestored(nonce)
                && bodies != null) for (var member : snap.journal.bodies()) {
                PhysShipImpl born = bodies.get(member.shipId());
                if (born != null) born.setStatic(true);
            }
            if (stage != ReservationRegistry.Stage.TRANSFERRING || dest == null) continue;
            var oldBodies = fn.b().get(snap.journal.source());
            boolean ready = bodies != null && snap.journal.bodies().stream().allMatch(b -> bodies.get(b.shipId()) != null)
                && (oldBodies == null || snap.journal.bodies().stream().allMatch(b -> oldBodies.get(b.shipId()) == null));
            if (!ready || owner.registry.restoredComplete(nonce)) continue;
            Set<Integer> nativeIds = new HashSet<>();
            for (HM nativeJoint : dest.d()) nativeIds.add(nativeJoint.a());
            boolean collision = snap.journal.joints().stream().anyMatch(j -> dest.getJointById(j.id()) != null
                || nativeIds.contains(j.id()));
            if (!owner.registry.readyToRestore(nonce, true, collision)) {
                if (collision) owner.registry.abort(nonce, "destination native/logical ID collision");
                continue;
            }
            for (var joint : snap.journal.joints()) {
                VSJoint definition;
                try { definition = JointDefinitionCodec.decode(snap.frozenBytes.get(joint.id())); }
                catch (IOException | RuntimeException ex) {
                    owner.registry.abort(nonce, "missing/invalid detached journal joint"); break;
                }
                if (SCOPED.get() != null) {
                    owner.registry.abort(nonce, "nested restore"); break;
                }
                boolean restored = false;
                try {
                    SCOPED.set(new Scope(dest, joint.id(), definition, Mode.RESTORE));
                    restored = dest.a(joint.id(), definition); // exact-ID RESTORED backend path
                } catch (RuntimeException | Error ex) {
                    owner.registry.abort(nonce, "native restore threw"); throw ex;
                } finally { SCOPED.remove(); }
                boolean verified = restored && restoredDefinition(dest, joint.id(), definition);
                owner.registry.restored(nonce, joint.id(), verified);
                if (!verified) break;
            }
        }
    }

    /** Native backend + dimensional membership + authoritative persisted ID/allocator. */
    private static boolean restoredDefinition(FU dest, int id, VSJoint definition) {
        if (dest.getJointById(id) != definition || !dest.getJointsFromShip(definition.getShipId0()).contains(id)
            || !dest.getJointsFromShip(definition.getShipId1()).contains(id)) return false;
        boolean found = false;
        for (HM nativeJoint : dest.d()) if (nativeJoint.a() == id) {
            if (found) return false;
            found = true;
        }
        if (!found) return false;
        var persisted = ((VsFuAccess) (Object) dest).spaceflight$persisted().b();
        if (persisted.a() <= id) return false;
        return persisted.b().stream().anyMatch(pair -> pair.getJointId() == id && pair.getJoint().equals(definition));
    }

    private static final class NativePhysics implements ReservationRegistry.Physics {
        private final Owner owner;
        NativePhysics(Owner owner) { this.owner = owner; }
        @Override public JointMigrationJournal.Record snapshot(UUID nonce, long seed, String source,
                                                                  String destination, long deadline) throws Exception {
            FN fn = owner.physics;
            FU level = ((VsFnAccess) (Object) fn).spaceflight$levels().get(source);
            var phys = fn.b().get(source);
            if (level == null || phys == null || phys.get(seed) == null) throw new IllegalStateException("unloaded seed");
            var queue = new ArrayDeque<Long>(); var ships = new HashSet<Long>();
            var saved = new HashMap<Integer, VSJoint>();
            ships.add(seed); queue.add(seed);
            while (!queue.isEmpty()) {
                long id = queue.removeFirst();
                if (phys.get(id) == null || ships.size() > MAX_SHIPS) throw new IllegalStateException("unloaded/huge component");
                for (int jointId : level.getJointsFromShip(id)) {
                    VSJoint joint = level.getJointById(jointId);
                    if (joint == null || jointId == Integer.MAX_VALUE || joint.getShipId0() == null
                        || joint.getShipId1() == null) throw new IllegalStateException("unknown/ground joint");
                    saved.put(jointId, joint);
                    long[] ends = {joint.getShipId0(), joint.getShipId1()};
                    for (long end : ends) {
                        if (phys.get(end) == null) throw new IllegalStateException("external/different-dimension joint");
                        if (ships.add(end)) queue.add(end);
                    }
                    if (saved.size() > MAX_JOINTS) throw new IllegalStateException("too many joints");
                }
            }
            // Backend enumeration detects native constraints absent from FU's logical maps,
            // including foreign/terrain attachments. No unknown connection may pass preflight.
            var nativeBodyIds = new HashMap<Integer, Long>();
            for (long id : ships) {
                PhysShipImpl body = phys.get(id);
                nativeBodyIds.put(body.getRigidBodyReference$impl().a(), id);
                if (!body.getPhysicsListeners$impl().isEmpty())
                    throw new IllegalStateException("unknown physics attachment/listener");
            }
            Set<Integer> nativeJointIds = new HashSet<>();
            for (HM nativeJoint : level.d()) {
                var first = nativeJoint.b().a(); var second = nativeJoint.b().c();
                boolean touches = (first != null && nativeBodyIds.containsKey(first))
                    || (second != null && nativeBodyIds.containsKey(second));
                if (!touches) continue;
                if (first == null || second == null || !nativeBodyIds.containsKey(first)
                    || !nativeBodyIds.containsKey(second) || !saved.containsKey(nativeJoint.a())
                    || !nativeJointIds.add(nativeJoint.a()))
                    throw new IllegalStateException("foreign/ground/untracked native constraint");
            }
            if (!nativeJointIds.equals(saved.keySet())) throw new IllegalStateException("missing native constraints");
            var bodies = new ArrayList<JointMigrationJournal.Body>();
            for (long id : ships) {
                PhysShipImpl body = phys.get(id);
                var v = body.getVelocity(); var w = body.getAngularVelocity();
                bodies.add(new JointMigrationJournal.Body(id, body.isStatic(), v.x(), v.y(), v.z(), w.x(), w.y(), w.z()));
            }
            var joints = new ArrayList<JointMigrationJournal.Joint>();
            var frozen = new HashMap<Integer, byte[]>();
            for (var entry : saved.entrySet()) {
                var capture = JointDefinitionCodec.capture(entry.getValue()); // NEVER mutates live.shouldBeSerialized
                VSJoint detached = capture.detached();
                byte[] encoded = capture.bytes();
                joints.add(new JointMigrationJournal.Joint(entry.getKey(),
                    detached.getShipId0(), detached.getShipId1(), encoded));
                frozen.put(entry.getKey(), encoded);
            }
            var record = new JointMigrationJournal.Record(nonce, source, destination, deadline,
                JointMigrationJournal.Phase.PREPARING, bodies, joints, owner.registry.journalOwnerToken());
            owner.snapshots.put(nonce, new Snapshot(Map.copyOf(saved), Map.copyOf(frozen), record));
            return record;
        }
        @Override public void suspend(JointMigrationJournal.Record record) {
            var bodies = owner.physics.b().get(record.source());
            try {
                for (var entry : record.bodies()) {
                    var body = bodies.get(entry.shipId());
                    if (body == null) throw new IllegalStateException("body disappeared during freeze");
                    body.setStatic(true);
                }
            } catch (RuntimeException | Error ex) {
                // A partially frozen component must NOT resume with missing joints. Force
                // all surviving bodies static before returning a failed ACK.
                for (var entry : record.bodies()) {
                    var body = bodies.get(entry.shipId());
                    if (body != null) body.setStatic(true);
                }
                throw ex;
            }
        }
        @Override public void restoreBeforeDispatch(JointMigrationJournal.Record record) {
            try { restoreExact(record, record.source(), false); }
            catch (RuntimeException | Error ex) {
                quarantine(record); // no ACK of safe rollback if this fails too
                throw ex;
            }
        }
        @Override public void release(JointMigrationJournal.Record record) {
            try { restoreExact(record, record.destination(), true); }
            catch (RuntimeException | Error ex) {
                quarantine(record); // same physics boundary, before physics stepping
                throw ex;
            }
        }
        private void restoreExact(JointMigrationJournal.Record record, String dimension,
                                  boolean requireSuspended) {
            var bodies = owner.physics.b().get(dimension);
            if (bodies == null) throw new IllegalStateException("restore dimension bodies absent");
            for (var entry : record.bodies()) {
                var body = bodies.get(entry.shipId());
                if (body == null || requireSuspended && !body.isStatic())
                    throw new IllegalStateException("restore body missing/not suspended");
            }
            // Native pose and six kinematic scalars are written before restoring each
            // *original* dynamic/static flag. No generic setStatic(false) for anchors.
            for (var entry : record.bodies()) {
                var body = bodies.get(entry.shipId()); var pose = body.getPoseVel();
                Hg updated = new Hg(pose.a(), pose.b(),
                    new Vector3d(entry.vx(), entry.vy(), entry.vz()),
                    new Vector3d(entry.wx(), entry.wy(), entry.wz()));
                body.getRigidBodyReference$impl().a(updated);
                body.setPoseVel(updated);
            }
            RELEASING.set(true);
            try {
                for (var entry : record.bodies()) bodies.get(entry.shipId()).setStatic(entry.wasStatic());
            } finally { RELEASING.remove(); }
            for (var entry : record.bodies()) {
                var body = bodies.get(entry.shipId());
                var pose = body.getRigidBodyReference$impl().d();
                if (body.isStatic() != entry.wasStatic() || pose.c().x() != entry.vx()
                    || pose.c().y() != entry.vy() || pose.c().z() != entry.vz()
                    || pose.d().x() != entry.wx() || pose.d().y() != entry.wy() || pose.d().z() != entry.wz())
                    throw new IllegalStateException("physical rollback/release verification mismatch");
            }
        }
        @Override public void quarantine(JointMigrationJournal.Record record) {
            var source = owner.physics.b().get(record.source());
            var dest = owner.physics.b().get(record.destination());
            for (var entry : record.bodies()) {
                var old = source == null ? null : source.get(entry.shipId());
                var born = dest == null ? null : dest.get(entry.shipId());
                if (old == null && born == null) throw new IllegalStateException("missing body during quarantine");
                if (old != null) old.setStatic(true);
                if (born != null) born.setStatic(true);
                if (old != null && !old.isStatic() || born != null && !born.isStatic())
                    throw new IllegalStateException("native quarantine did not hold");
            }
        }
    }
}
