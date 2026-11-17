package com.bettercontent.spaceflight.vs.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ReservationRegistryTest {
    @TempDir Path root;
    private static class FakePhysics implements ReservationRegistry.Physics {
        final List<JointMigrationJournal.Record> suspended = new ArrayList<>(), released = new ArrayList<>(),
            restoredBeforeDispatch = new ArrayList<>(), quarantined = new ArrayList<>();
        final Map<Long, Boolean> staticBodies = new ConcurrentHashMap<>();
        volatile boolean failSnapshot, failSuspend, failRestore, failRelease, failWithLinkageError, failQuarantine;
        volatile CountDownLatch releaseEntered, finishRelease;
        @Override public JointMigrationJournal.Record snapshot(UUID nonce, long seed, String source,
                                                                String destination, long deadline) throws Exception {
            if (failSnapshot) throw new IOException("native snapshot failed");
            staticBodies.putIfAbsent(seed, true);
            staticBodies.putIfAbsent(seed + 1, false);
            return new JointMigrationJournal.Record(nonce, source, destination, deadline,
                JointMigrationJournal.Phase.PREPARING,
                List.of(new JointMigrationJournal.Body(seed, true, 0, 0, 0, 0, 0, 0),
                    new JointMigrationJournal.Body(seed + 1, false, 3, 4, 5, 6, 7, 8)),
                List.of(new JointMigrationJournal.Joint((int) (seed * 10 + 1), seed, seed + 1, new byte[]{1}),
                    new JointMigrationJournal.Joint((int) (seed * 10 + 2), seed, seed + 1, new byte[]{2})));
        }
        @Override public void suspend(JointMigrationJournal.Record snapshot) throws Exception {
            staticBodies.put(snapshot.bodies().get(0).shipId(), true);
            if (failSuspend) throw new IOException("partly frozen");
            for (var body : snapshot.bodies()) staticBodies.put(body.shipId(), true);
            suspended.add(snapshot);
        }
        @Override public void restoreBeforeDispatch(JointMigrationJournal.Record snapshot) {
            if (failRestore) throw new IllegalStateException("physics original restore failed");
            for (var body : snapshot.bodies()) staticBodies.put(body.shipId(), body.wasStatic());
            restoredBeforeDispatch.add(snapshot);
        }
        @Override public void release(JointMigrationJournal.Record snapshot) throws Exception {
            // Deliberately unfreeze a dynamic member BEFORE the controllable failure/race.
            staticBodies.put(snapshot.bodies().get(1).shipId(), false);
            if (releaseEntered != null) {
                releaseEntered.countDown();
                if (!finishRelease.await(5, TimeUnit.SECONDS)) throw new IOException("release latch timeout");
            }
            if (failWithLinkageError) throw new UnsatisfiedLinkError("native backend disappeared");
            if (failRelease) throw new IOException("partially released native body");
            for (var body : snapshot.bodies()) staticBodies.put(body.shipId(), body.wasStatic());
            released.add(snapshot);
        }
        @Override public void quarantine(JointMigrationJournal.Record snapshot) throws Exception {
            staticBodies.put(snapshot.bodies().get(0).shipId(), true);
            if (failQuarantine) throw new IOException("quarantine failed after first body");
            for (var body : snapshot.bodies()) staticBodies.put(body.shipId(), true);
            quarantined.add(snapshot);
        }
        void assertOriginal(JointMigrationJournal.Record snapshot) {
            for (var body : snapshot.bodies()) assertEquals(body.wasStatic(), staticBodies.get(body.shipId()));
        }
        void assertFrozen(JointMigrationJournal.Record snapshot) {
            for (var body : snapshot.bodies()) assertEquals(true, staticBodies.get(body.shipId()));
        }
    }
    private static class FakeJournal implements ReservationRegistry.Journal {
        final List<JointMigrationJournal.Phase> phases = new ArrayList<>();
        @Override public void persist(JointMigrationJournal.Record record) throws IOException { phases.add(record.phase()); }
    }
    private record Active(ReservationRegistry registry, FakePhysics physics, FakeJournal journal, UUID nonce, Object frame) {}
    private static Active transferring(long seed) {
        var r = new ReservationRegistry(); var physics = new FakePhysics(); var journal = new FakeJournal();
        var nonce = r.request(seed, "overworld", "space", 0, 20);
        r.physicsBoundary(physics, 1);
        assertTrue(r.persistPreparing(nonce, journal, 1));
        assertTrue(r.persistTransferring(nonce, journal, 1));
        var frame = new Object();
        assertTrue(r.tagFrame(nonce, frame, Set.of(seed, seed + 1, 999_999L), 1));
        return new Active(r, physics, journal, nonce, frame);
    }
    private static Active committed(long seed) {
        Active a = transferring(seed);
        var r = a.registry(); var nonce = a.nonce();
        int first = (int) (seed * 10 + 1), second = first + 1;
        r.didRemove(nonce, a.frame(), seed, first, true);
        r.didRemove(nonce, a.frame(), seed + 1, second, true);
        r.restored(nonce, first, true);
        r.restored(nonce, second, true);
        assertTrue(r.persistVerified(nonce, a.journal(), true, 1));
        assertTrue(r.commit(nonce, a.journal(), 1));
        return a;
    }
    private static JointMigrationJournal committedStore(Active a, Path root) throws Exception {
        var store = new JointMigrationJournal(root);
        var source = a.registry().snapshot(a.nonce());
        for (var phase : new JointMigrationJournal.Phase[]{
            JointMigrationJournal.Phase.PREPARING, JointMigrationJournal.Phase.TRANSFERRING,
            JointMigrationJournal.Phase.VERIFYING, JointMigrationJournal.Phase.COMMITTED})
            store.write(source.withPhase(phase));
        assertEquals(JointMigrationJournal.Phase.COMMITTED, store.read(a.nonce()).phase());
        return store;
    }

    @Test void exactFrameMixedUnrelatedAndAsyncPhysicalRelease() {
        var a = committed(10); var r = a.registry(); var p = a.physics();
        assertEquals(ReservationRegistry.Stage.COMMITTED, r.stage(a.nonce()));
        p.assertFrozen(r.snapshot(a.nonce()));
        assertFalse(r.eligibleRemoval(a.nonce(), new Object(), 10, 101));
        assertFalse(r.eligibleRemoval(a.nonce(), a.frame(), 999_999, 101));
        assertTrue(p.released.isEmpty());
        r.physicsBoundary(p, 2);
        assertEquals(ReservationRegistry.Stage.RELEASE_ACK, r.stage(a.nonce()));
        p.assertOriginal(r.snapshot(a.nonce()));
        assertTrue(r.persistReleaseAck(a.nonce(), a.journal(), 2));
        assertEquals(List.of(JointMigrationJournal.Phase.PREPARING, JointMigrationJournal.Phase.TRANSFERRING,
            JointMigrationJournal.Phase.VERIFYING, JointMigrationJournal.Phase.COMMITTED,
            JointMigrationJournal.Phase.RELEASED), a.journal().phases);
        assertFalse(r.heldJoint(101));
        assertFalse(r.drainNotices().isEmpty());
        assertNull(r.stageIfKnown(a.nonce())); // terminal snapshots are not retained indefinitely
    }
    @Test void sameWorldOverlapRejectedBEFORESecondFreezeAndOtherWorldIndependent() {
        UUID world = UUID.randomUUID();
        var first = new ReservationRegistry(world); var second = new ReservationRegistry(world);
        var physics = new FakePhysics();
        var a = first.request(30, "overworld", "space", 0, 20);
        var b = second.request(29, "overworld", "space", 0, 20);
        first.physicsBoundary(physics, 1); second.physicsBoundary(physics, 1);
        assertEquals(ReservationRegistry.Stage.SUSPENDED, first.stage(a));
        assertEquals(ReservationRegistry.Stage.ABORTED, second.stage(b));
        assertEquals(1, physics.suspended.size());
        var other = new ReservationRegistry(UUID.randomUUID());
        var c = other.request(30, "overworld", "space", 0, 20);
        other.physicsBoundary(new FakePhysics(), 1);
        assertEquals(ReservationRegistry.Stage.SUSPENDED, other.stage(c));
    }
    @Test void failedPreparingWriteRestoresOriginalsButRetainsClaimsAndClosesAdmission() {
        var r = new ReservationRegistry(); var p = new FakePhysics();
        var nonce = r.request(40, "overworld", "space", 0, 20);
        r.physicsBoundary(p, 1);
        p.assertFrozen(r.snapshot(nonce));
        assertFalse(r.persistPreparing(nonce, record -> { throw new IOException("disk full"); }, 1));
        assertEquals(ReservationRegistry.Stage.ABORT_REQUESTED, r.stage(nonce));
        assertTrue(r.heldShip(41));
        r.physicsBoundary(p, 2);
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, r.stage(nonce));
        p.assertOriginal(r.snapshot(nonce));
        assertTrue(r.sourceOriginalsVerifiedRestored(nonce));
        assertFalse(r.shouldSealShipMutation(41));
        assertFalse(r.shouldSealJointMutation(401));
        assertFalse(r.shouldSealGameMutation(41, "overworld"));
        assertFalse(r.shouldHoldPhysicsCallback(41));
        assertTrue(r.heldShip(41)); // fsync could have published PREPARING before throwing
        assertTrue(r.heldJoint(401));
        r.shutdown(); r.timeout(200); r.physicsBoundary(p, 201);
        p.assertOriginal(r.snapshot(nonce));
        assertFalse(r.shouldSealShipMutation(41));
        assertTrue(r.heldShip(41));
        assertThrows(IllegalStateException.class, () -> r.request(43, "overworld", "space", 2, 12));
    }
    @Test void cancellationPermitCannotBeMintedOrCalledByAnExternalProductionOwner() throws Exception {
        var begin = java.util.Arrays.stream(ReservationRegistry.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("beginCancellation")).findFirst().orElseThrow();
        var persist = java.util.Arrays.stream(ReservationRegistry.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("persistCancelled")).findFirst().orElseThrow();
        assertTrue(java.lang.reflect.Modifier.isPrivate(begin.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPrivate(persist.getModifiers()));
        var permit = java.util.Arrays.stream(ReservationRegistry.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("NoMutationPermit")).findFirst().orElseThrow();
        assertTrue(java.lang.reflect.Modifier.isPrivate(permit.getModifiers()));
        for (var constructor : permit.getDeclaredConstructors())
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        assertTrue(java.util.Arrays.stream(ReservationRegistry.class.getMethods())
            .noneMatch(m -> m.getName().contains("Cancel") || m.getName().contains("MutationPermit")));
    }
    @Test void durablePreparingAbortCannotBecomeInMemoryTerminalWithoutGameMutationGate() {
        var r = new ReservationRegistry(); var p = new FakePhysics(); var journal = new FakeJournal();
        var nonce = r.request(44, "overworld", "space", 0, 20);
        r.physicsBoundary(p, 1);
        assertTrue(r.persistPreparing(nonce, journal, 1));
        assertEquals(List.of(JointMigrationJournal.Phase.PREPARING), journal.phases);
        r.abort(nonce, "no dispatch was requested");
        assertEquals(ReservationRegistry.Stage.ABORT_REQUESTED, r.stage(nonce));
        assertFalse(r.persistTransferring(nonce, journal, 1));
        r.physicsBoundary(p, 2);
        p.assertOriginal(r.snapshot(nonce));
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, r.stage(nonce));
        assertTrue(r.sourceOriginalsVerifiedRestored(nonce));
        assertFalse(r.shouldSealShipMutation(45));
        assertFalse(r.shouldSealJointMutation(441));
        assertFalse(r.shouldSealGameMutation(45, "space"));
        assertTrue(r.heldShip(45)); assertTrue(r.heldJoint(441));
        assertFalse(journal.phases.contains(JointMigrationJournal.Phase.CANCELLED));
        assertFalse(r.drainNotices().stream().anyMatch(n -> n.stage() == ReservationRegistry.Stage.ABORTED));
        assertThrows(IllegalStateException.class, () -> r.request(46, "overworld", "space", 2, 12));
    }
    @Test void failedPhysicalRestoreNeverDisablesRecoveryMutationSeals() {
        var r = new ReservationRegistry(); var p = new FakePhysics(); p.failRestore = true;
        var nonce = r.request(47, "overworld", "space", 0, 20);
        r.physicsBoundary(p, 1);
        assertTrue(r.persistPreparing(nonce, new FakeJournal(), 1));
        r.abort(nonce, "pre-dispatch abort"); r.physicsBoundary(p, 2);
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, r.stage(nonce));
        assertFalse(r.sourceOriginalsVerifiedRestored(nonce));
        assertTrue(r.shouldSealShipMutation(48));
        assertTrue(r.shouldSealJointMutation(471));
        assertTrue(r.shouldSealGameMutation(48, "overworld"));
        assertTrue(r.heldShip(48));
        p.assertFrozen(r.snapshot(nonce));
        assertThrows(IllegalStateException.class, () -> r.request(49, "overworld", "space", 2, 10));
    }
    @Test void bridgeFrameQuarantineExcludesPhysicsVerifiedRestoredSource() throws Exception {
        String bridge = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/bettercontent/spaceflight/vs/compat/internal/PinnedVs2411Bridge.java"));
        assertTrue(bridge.contains("&& !owner.registry.sourceOriginalsVerifiedRestored(nonce)"));
    }
    @Test void partialSuspendExceptionRestoresBeforeAnySnapshotAck() {
        var r = new ReservationRegistry(); var p = new FakePhysics(); p.failSuspend = true;
        var nonce = r.request(50, "overworld", "space", 0, 20);
        r.physicsBoundary(p, 1);
        assertEquals(ReservationRegistry.Stage.ABORTED, r.stage(nonce));
        p.assertOriginal(r.snapshot(nonce));
        assertEquals(1, p.restoredBeforeDispatch.size());
        assertTrue(r.drainNotices().stream().noneMatch(n -> n.stage() == ReservationRegistry.Stage.SUSPENDED));
    }
    @Test void timeoutAndShutdownQueuePhysicsOwnerAbortAndLateAckCannotCommit() {
        var r = new ReservationRegistry(); var p = new FakePhysics();
        var nonce = r.request(60, "overworld", "space", 0, 2);
        r.physicsBoundary(p, 1);
        r.timeout(3);
        assertEquals(ReservationRegistry.Stage.ABORT_REQUESTED, r.stage(nonce));
        assertFalse(r.persistPreparing(nonce, new FakeJournal(), 3));
        r.physicsBoundary(p, 3);
        p.assertOriginal(r.snapshot(nonce));
        assertEquals(ReservationRegistry.Stage.ABORTED, r.stage(nonce));
        r.shutdown();
        assertThrows(IllegalStateException.class, () -> r.request(63, "overworld", "space", 3, 7));
    }
    @Test void shutdownBeforeReleaseSelectionLeavesBodiesFrozen() {
        var a = committed(70); a.registry().shutdown();
        a.registry().physicsBoundary(a.physics(), 2);
        a.physics().assertFrozen(a.registry().snapshot(a.nonce()));
        assertTrue(a.physics().released.isEmpty());
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, a.registry().stage(a.nonce()));
    }
    private static void cancelDuringRelease(boolean shutdown) throws Exception {
        var a = committed(shutdown ? 80 : 90); var p = a.physics(); var r = a.registry();
        p.releaseEntered = new CountDownLatch(1); p.finishRelease = new CountDownLatch(1);
        var runner = new Thread(() -> r.physicsBoundary(p, 2)); runner.start();
        try {
            assertTrue(p.releaseEntered.await(3, TimeUnit.SECONDS));
            assertEquals(false, p.staticBodies.get(r.snapshot(a.nonce()).bodies().get(1).shipId()));
            if (shutdown) r.shutdown(); else r.timeout(21);
            assertEquals(ReservationRegistry.Stage.RELEASING, r.stage(a.nonce())); // physics owns callback
        } finally { p.finishRelease.countDown(); runner.join(5_000); }
        assertFalse(runner.isAlive());
        p.assertFrozen(r.snapshot(a.nonce()));
        assertEquals(1, p.quarantined.size());
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, r.stage(a.nonce()));
        assertFalse(r.persistReleaseAck(a.nonce(), a.journal(), 2));
    }
    @Test void shutdownDuringPartialPhysicalReleaseReQuarantinesSameBoundary() throws Exception { cancelDuringRelease(true); }
    @Test void timeoutDuringPartialPhysicalReleaseReQuarantinesSameBoundary() throws Exception { cancelDuringRelease(false); }
    @Test void claimedSnapshotSealsBothDimensionsBeforeBlockedNativeSuspendCompletes() throws Exception {
        var r = new ReservationRegistry();
        var nonce = r.request(740, "overworld", "space", 0, 20);
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var physics = new FakePhysics() {
            @Override public void suspend(JointMigrationJournal.Record snapshot) throws Exception {
                entered.countDown();
                if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("suspend latch timeout");
                super.suspend(snapshot);
            }
        };
        var worker = new Thread(() -> {
            try { r.physicsBoundary(physics, 1); }
            catch (Throwable ex) { failure.set(ex); }
        }, "bounded-pure-physics-boundary");
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(ReservationRegistry.Stage.CLAIMED, r.stage(nonce));
            assertTrue(r.heldShip(740)); assertTrue(r.heldShip(741));
            assertTrue(r.shouldSealShipMutation(741)); assertTrue(r.shouldSealJointMutation(7401));
            assertTrue(r.shouldSealGameMutation(740, "overworld"));
            assertTrue(r.shouldSealGameMutation(741, "space"));
            assertFalse(r.shouldSealGameMutation(741, "the_end"));
        } finally { finish.countDown(); worker.join(5_000); }
        assertFalse(worker.isAlive()); assertNull(failure.get());
        assertEquals(ReservationRegistry.Stage.SUSPENDED, r.stage(nonce));
        r.abort(nonce, "before durable preparation");
        r.physicsBoundary(physics, 2);
        assertFalse(r.shouldSealGameMutation(740, "overworld"));
    }
    @Test void immutableHeldLeaseMetadataSeparatesSourceDestinationOwnerAndAdmission() {
        var first = new ReservationRegistry(UUID.randomUUID());
        var other = new ReservationRegistry(UUID.randomUUID());
        var physics = new FakePhysics();
        var nonce = first.request(700, "minecraft:dimension:minecraft:overworld",
            "minecraft:dimension:better_spaceflight:space", 0, 20);
        assertFalse(first.shouldSealGameMutation(700, "minecraft:dimension:minecraft:overworld"));
        first.physicsBoundary(physics, 1);
        assertTrue(first.shouldSealShipMutation(700));
        assertTrue(first.shouldSealJointMutation(7001));
        assertTrue(first.shouldSealGameMutation(700, "minecraft:dimension:minecraft:overworld"));
        assertTrue(first.shouldSealGameMutation(701, "minecraft:dimension:better_spaceflight:space"));
        assertFalse(first.shouldSealGameMutation(700, "minecraft:dimension:minecraft:the_end"));
        assertFalse(first.shouldSealGameMutation(702, "minecraft:dimension:minecraft:overworld"));
        assertFalse(other.shouldSealGameMutation(700, "minecraft:dimension:minecraft:overworld"));
        assertFalse(other.shouldSealShipMutation(700));
        assertFalse(other.shouldSealJointMutation(7001));
        assertTrue(first.heldShip(700)); // same owner's admission claim, separate from mutation seal
        first.abort(nonce, "before dispatch");
        first.physicsBoundary(physics, 2);
        assertFalse(first.shouldSealGameMutation(700, "minecraft:dimension:minecraft:overworld"));
    }
    @Test void releaseAckAndAmbiguousFsyncNeverReenterNativeOrHelmMutationSeal() {
        var a = committed(720); var r = a.registry(); var p = a.physics();
        assertTrue(r.shouldSealGameMutation(720, "overworld"));
        assertTrue(r.shouldSealGameMutation(721, "space"));
        assertTrue(r.shouldSealShipMutation(721));
        assertTrue(r.shouldSealJointMutation(7201));
        r.physicsBoundary(p, 2);
        assertEquals(ReservationRegistry.Stage.RELEASE_ACK, r.stage(a.nonce()));
        assertTrue(r.heldShip(720)); assertTrue(r.heldJoint(7201)); // block NEW admission only
        assertFalse(r.shouldSealShipMutation(720)); assertFalse(r.shouldSealJointMutation(7201));
        assertFalse(r.shouldSealGameMutation(720, "overworld"));
        assertFalse(r.shouldSealGameMutation(721, "space"));
        assertFalse(r.shouldHoldPhysicsCallback(720));
        assertFalse(r.persistReleaseAck(a.nonce(), record -> {
            assertEquals(ReservationRegistry.Stage.WRITING_RELEASED, r.stage(a.nonce()));
            assertFalse(r.shouldSealShipMutation(720));
            assertFalse(r.shouldSealJointMutation(7201));
            assertFalse(r.shouldSealGameMutation(720, "overworld"));
            assertFalse(r.shouldSealGameMutation(721, "space"));
            throw new IOException("after atomic replace, fsync unknown");
        }, 2));
        assertEquals(ReservationRegistry.Stage.RELEASE_PERSIST_UNKNOWN, r.stage(a.nonce()));
        assertTrue(r.heldShip(720)); assertTrue(r.heldJoint(7201));
        assertFalse(r.shouldSealShipMutation(720)); assertFalse(r.shouldSealJointMutation(7201));
        assertFalse(r.shouldSealGameMutation(720, "overworld"));
        assertFalse(r.shouldSealGameMutation(721, "space"));
        assertFalse(r.shouldHoldPhysicsCallback(721));
        r.shutdown(); r.timeout(200); r.physicsBoundary(p, 3);
        assertEquals(ReservationRegistry.Stage.RELEASE_PERSIST_UNKNOWN, r.stage(a.nonce()));
        p.assertOriginal(r.snapshot(a.nonce()));
        assertTrue(p.quarantined.isEmpty());
        assertFalse(r.shouldSealGameMutation(720, "overworld"));
        assertFalse(r.shouldSealJointMutation(7201));
        assertThrows(IllegalStateException.class, () -> r.request(720, "overworld", "space", 2, 12));
    }
    @Test void optionalListenerSealIsMemberScopedAndEndsAtVerifiedPhysicalReleaseAck() {
        var a = committed(400); var r = a.registry();
        assertTrue(r.shouldHoldPhysicsCallback(400));
        assertTrue(r.shouldHoldPhysicsCallback(401));
        assertFalse(r.shouldHoldPhysicsCallback(402));
        r.physicsBoundary(a.physics(), 2);
        assertEquals(ReservationRegistry.Stage.RELEASE_ACK, r.stage(a.nonce()));
        assertFalse(r.shouldHoldPhysicsCallback(401)); // release ACK is irrevocable
        assertTrue(r.persistReleaseAck(a.nonce(), a.journal(), 2));
        assertFalse(r.shouldHoldPhysicsCallback(400));
    }
    @Test void backendReleaseThrowAfterOneDynamicBodyReQuarantinesSameBoundary() {
        var a = committed(100); a.physics().failRelease = true;
        a.registry().physicsBoundary(a.physics(), 2);
        a.physics().assertFrozen(a.registry().snapshot(a.nonce()));
        assertEquals(1, a.physics().quarantined.size());
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, a.registry().stage(a.nonce()));
        assertFalse(a.journal().phases.contains(JointMigrationJournal.Phase.RELEASED));
        assertFalse(a.registry().persistReleaseAck(a.nonce(), a.journal(), 2));
    }
    @Test void nativeLinkageErrorDuringPartialReleaseAlsoReQuarantines() {
        var a = committed(105); a.physics().failWithLinkageError = true;
        a.registry().physicsBoundary(a.physics(), 2);
        a.physics().assertFrozen(a.registry().snapshot(a.nonce()));
        assertEquals(1, a.physics().quarantined.size());
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, a.registry().stage(a.nonce()));
    }
    @Test void ambiguousQuarantineFailureHardStopsAdmissionWithoutFalseFrozenClaim() {
        var a = committed(107); a.physics().failRelease = true; a.physics().failQuarantine = true;
        a.registry().physicsBoundary(a.physics(), 2);
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, a.registry().stage(a.nonce()));
        assertEquals(false, a.physics().staticBodies.get(108L)); // NOT incorrectly claimed frozen
        assertTrue(a.registry().heldShip(108L));
        assertThrows(IllegalStateException.class,
            () -> a.registry().request(109, "overworld", "space", 2, 12));
        assertTrue(a.registry().drainNotices().stream().anyMatch(n -> n.reason().contains("AMBIGUOUS")));
    }
    @Test void postReleaseDeadlineCannotUndoAckAndLateTerminalMarkerMayComplete() {
        var a = committed(110); var r = a.registry(); var p = a.physics();
        r.physicsBoundary(p, 2);
        p.assertOriginal(r.snapshot(a.nonce()));
        r.timeout(21);
        assertEquals(ReservationRegistry.Stage.RELEASE_ACK, r.stage(a.nonce()));
        r.physicsBoundary(p, 22);
        p.assertOriginal(r.snapshot(a.nonce()));
        assertTrue(p.quarantined.isEmpty());
        assertTrue(r.persistReleaseAck(a.nonce(), a.journal(), 22));
        assertEquals(ReservationRegistry.Stage.RELEASED, r.stage(a.nonce()));
        assertFalse(r.heldShip(111));
    }
    @Test void releaseTerminalFsyncRaceNeverWritesTerminalWhileReFreezing() throws Exception {
        var a = committed(112); var r = a.registry(); var p = a.physics();
        var store = committedStore(a, root);
        r.physicsBoundary(p, 2);
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var outcome = new AtomicReference<Boolean>(); var error = new AtomicReference<Throwable>();
        var writer = new Thread(() -> {
            try { outcome.set(r.persistReleaseAck(a.nonce(), record -> {
                entered.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("write latch timeout");
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                store.write(record); // real fsync + atomic replacement AFTER abort/timeout/shutdown
            }, 2)); }
            catch (Throwable ex) { error.set(ex); }
        });
        writer.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(ReservationRegistry.Stage.WRITING_RELEASED, r.stage(a.nonce()));
            assertEquals(JointMigrationJournal.Phase.COMMITTED, store.read(a.nonce()).phase());
            r.abort(a.nonce(), "late administrative abort"); r.timeout(21); r.shutdown();
            r.physicsBoundary(p, 21); // physics progresses while game writer is blocked
            assertEquals(ReservationRegistry.Stage.WRITING_RELEASED, r.stage(a.nonce()));
            p.assertOriginal(r.snapshot(a.nonce()));
            assertTrue(p.quarantined.isEmpty());
        } finally { finish.countDown(); writer.join(5_000); }
        assertFalse(writer.isAlive()); assertNull(error.get()); assertEquals(Boolean.TRUE, outcome.get());
        assertEquals(JointMigrationJournal.Phase.RELEASED, store.read(a.nonce()).phase());
        assertEquals(ReservationRegistry.Stage.RELEASED, r.stage(a.nonce()));
        assertFalse(r.heldShip(113));
        r.physicsBoundary(p, 22); p.assertOriginal(r.snapshot(a.nonce()));
        assertTrue(p.quarantined.isEmpty());
    }
    @Test void terminalWriteErrorBeforeOrAfterAtomicReplaceIsUnknownNotRequarantined()
        throws Exception {
        for (boolean afterAtomicReplace : new boolean[]{false, true}) {
            var a = committed(afterAtomicReplace ? 116 : 114);
            var r = a.registry(); var p = a.physics(); var store = committedStore(a, root);
            r.physicsBoundary(p, 2);
            assertFalse(r.persistReleaseAck(a.nonce(), record -> {
                if (afterAtomicReplace) store.write(record);
                throw new IOException("I/O outcome not trusted");
            }, 2));
            assertEquals(ReservationRegistry.Stage.RELEASE_PERSIST_UNKNOWN, r.stage(a.nonce()));
            assertEquals(afterAtomicReplace ? JointMigrationJournal.Phase.RELEASED
                : JointMigrationJournal.Phase.COMMITTED, store.read(a.nonce()).phase());
            p.assertOriginal(r.snapshot(a.nonce()));
            r.timeout(21); r.shutdown(); r.physicsBoundary(p, 21);
            p.assertOriginal(r.snapshot(a.nonce()));
            assertTrue(p.quarantined.isEmpty());
            assertTrue(r.heldShip(afterAtomicReplace ? 117 : 115));
            assertThrows(IllegalStateException.class,
                () -> r.request(119, "overworld", "space", 21, 31));
            assertTrue(r.drainNotices().stream().anyMatch(n -> n.stage()
                == ReservationRegistry.Stage.RELEASE_PERSIST_UNKNOWN && n.reason().contains("unknown")));
        }
    }
    @Test void wrongFrameDuplicateUnexpectedAndRestoreFalseOrThrowFailClosed() {
        var wrong = transferring(120); var r = wrong.registry(); var id = 1201;
        assertFalse(r.eligibleRemoval(wrong.nonce(), new Object(), 120, id));
        assertFalse(r.eligibleRemoval(wrong.nonce(), wrong.frame(), 121, 999));
        assertTrue(r.eligibleRemoval(UUID.fromString(wrong.nonce().toString()), wrong.frame(), 120, id));
        r.didRemove(wrong.nonce(), wrong.frame(), 120, id, true);
        r.didRemove(wrong.nonce(), wrong.frame(), 120, id, true);
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, r.stage(wrong.nonce()));
        var missing = transferring(130);
        missing.registry().didRemove(missing.nonce(), missing.frame(), 130, 1399, true);
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, missing.registry().stage(missing.nonce()));
        var failed = transferring(140);
        failed.registry().didRemove(failed.nonce(), failed.frame(), 140, 1401, false);
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, failed.registry().stage(failed.nonce()));
        var restore = transferring(150); var reg = restore.registry();
        reg.didRemove(restore.nonce(), restore.frame(), 150, 1501, true);
        reg.restored(restore.nonce(), 1501, false);
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, reg.stage(restore.nonce()));
        var thrown = transferring(160);
        try { throw new IOException("backend threw"); }
        catch (IOException ex) { thrown.registry().abort(thrown.nonce(), "native restore threw"); }
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, thrown.registry().stage(thrown.nonce()));
    }
    @Test void idempotentRestoredStateAndCollisionMustNotPass() {
        var a = transferring(170); var r = a.registry(); var nonce = a.nonce();
        r.didRemove(nonce, a.frame(), 170, 1701, true); r.didRemove(nonce, a.frame(), 171, 1702, true);
        assertFalse(r.readyToRestore(nonce, true, true));
        assertTrue(r.readyToRestore(nonce, true, false));
        r.restored(nonce, 1701, true);
        assertTrue(r.readyToRestore(nonce, true, false));
        r.restored(nonce, 1702, true);
        assertTrue(r.restoredComplete(nonce));
        r.restored(nonce, 1702, true); // second callback is NOT idempotently re-emitted
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, r.stage(nonce));
    }
    @Test void delayedWorldStartFasterGameTicksUseOnlyPhysicsEpoch() {
        var r = new ReservationRegistry(); var p = new FakePhysics(); var journal = new FakeJournal();
        long epoch = 5_000_000;
        UUID nonce = r.request(180, "overworld", "space", epoch, epoch + 20);
        r.physicsBoundary(p, epoch + 1);
        long serverGameTicks = 100_000_000L + 400; // NEVER passed as boundary epoch
        assertTrue(serverGameTicks > epoch + 20);
        assertTrue(r.persistPreparing(nonce, journal, epoch + 1));
        assertTrue(r.persistTransferring(nonce, journal, epoch + 1));
        r.timeout(epoch + 21);
        assertEquals(ReservationRegistry.Stage.RECOVERY_REQUIRED, r.stage(nonce));
        p.assertFrozen(r.snapshot(nonce));
    }
    @Test void journalCallbackDoesNotHoldPhysicsMonitor() throws Exception {
        var r = new ReservationRegistry(); var p = new FakePhysics();
        var nonce = r.request(190, "overworld", "space", 0, 20);
        r.physicsBoundary(p, 1);
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var writer = new Thread(() -> r.persistPreparing(nonce, record -> {
            entered.countDown();
            try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
        }, 1));
        writer.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var query = java.util.concurrent.CompletableFuture.supplyAsync(() -> r.stage(nonce));
            assertEquals(ReservationRegistry.Stage.WRITING_PREPARING, query.get(1, TimeUnit.SECONDS));
        } finally { resume.countDown(); writer.join(5_000); }
        assertFalse(writer.isAlive());
        assertEquals(ReservationRegistry.Stage.PREPARING, r.stage(nonce));
    }
    @Test void ownerHasFiniteAdmissionAndTerminalRetentionBounds() {
        var r = new ReservationRegistry();
        for (long i = 1; i <= 128; i++) r.request(10_000 + i, "overworld", "space", 0, 10);
        assertThrows(IllegalStateException.class,
            () -> r.request(20_000, "overworld", "space", 0, 10));
        r.shutdown(); // no bodies selected; returns ABORTED without native callbacks
        assertEquals(128, r.drainNotices().size());
        r.retireAfterPipelineStopped();
        assertFalse(r.heldShip(10_001));
    }
    @Test void retirementRequiresStoppedPipelineAndDoesNotReleaseNativeBodies() {
        var r = new ReservationRegistry(); var p = new FakePhysics();
        var nonce = r.request(200, "overworld", "space", 0, 20);
        r.physicsBoundary(p, 1);
        assertThrows(IllegalStateException.class, r::retireAfterPipelineStopped);
        r.shutdown(); // later exact native pipeline STOP must be established by caller
        r.retireAfterPipelineStopped();
        assertFalse(r.heldShip(200));
        p.assertFrozen(p.suspended.get(0)); // retirement only reclaims JVM references; no unfreeze
        assertThrows(IllegalArgumentException.class, () -> r.stage(nonce));
        assertThrows(IllegalStateException.class, () -> r.request(200, "overworld", "space", 1, 7));
    }
}
