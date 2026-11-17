package com.bettercontent.spaceflight.vs.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class JointMigrationJournalTest {
    @TempDir Path root;

    private static JointMigrationJournal.Record snapshot() {
        return new JointMigrationJournal.Record(UUID.randomUUID(), "overworld", "space", 100,
            JointMigrationJournal.Phase.PREPARING,
            List.of(new JointMigrationJournal.Body(1, true, 0, 0, 0, 0, 0, 0),
                new JointMigrationJournal.Body(2, false, 4, -3, 7, 1, 2, -1)),
            List.of(new JointMigrationJournal.Joint(32, 1, 2, new byte[]{1, 2, 3})));
    }

    @Test void durableRoundTripAndFinitePhases() throws Exception {
        var store = new JointMigrationJournal(root);
        var record = snapshot();
        store.write(record);
        assertEquals(record, store.read(record.nonce()));
        store.write(record.withPhase(JointMigrationJournal.Phase.TRANSFERRING));
        store.write(record.withPhase(JointMigrationJournal.Phase.VERIFYING));
        store.write(record.withPhase(JointMigrationJournal.Phase.COMMITTED));
        store.write(record.withPhase(JointMigrationJournal.Phase.RELEASED));
        assertEquals(JointMigrationJournal.Phase.RELEASED, store.scan().get(0).phase());
        assertThrows(Exception.class, () -> store.write(record.withPhase(JointMigrationJournal.Phase.PREPARING)));
        assertThrows(Exception.class, () -> store.write(record.withPhase(JointMigrationJournal.Phase.RECOVERY_REQUIRED)));
    }

    @Test void ownerBoundCancellationIsTerminalOnlyFromUnchangedSchema2Preparing() throws Exception {
        var store = new JointMigrationJournal(root);
        var old = snapshot();
        var prepared = new JointMigrationJournal.Record(old.nonce(), old.source(), old.destination(),
            old.deadlineTick(), old.phase(), old.bodies(), old.joints(), UUID.randomUUID());
        store.write(prepared);
        assertEquals(prepared, store.read(prepared.nonce()));
        var cancelled = prepared.withPhase(JointMigrationJournal.Phase.CANCELLED);
        assertThrows(Exception.class, () -> store.write(new JointMigrationJournal.Record(
            prepared.nonce(), prepared.source(), prepared.destination(), prepared.deadlineTick(),
            JointMigrationJournal.Phase.CANCELLED, prepared.bodies(), prepared.joints(), UUID.randomUUID())));
        assertThrows(Exception.class, () -> store.write(new JointMigrationJournal.Record(
            prepared.nonce(), prepared.source(), prepared.destination(), prepared.deadlineTick(),
            JointMigrationJournal.Phase.CANCELLED, prepared.bodies(), List.of(), prepared.owner())));
        store.write(cancelled);
        assertEquals(cancelled, store.scan().get(0));
        assertThrows(Exception.class, () -> store.write(prepared));
        assertThrows(Exception.class, () -> store.write(prepared.withPhase(JointMigrationJournal.Phase.RELEASED)));
        assertThrows(Exception.class, () -> store.write(prepared.withPhase(JointMigrationJournal.Phase.RECOVERY_REQUIRED)));
        assertThrows(Exception.class, () -> store.write(cancelled));
    }

    @Test void legacyPreparingCannotAcquireCancelledPhaseButReleasedRemainsReadable() throws Exception {
        var store = new JointMigrationJournal(root);
        var legacy = snapshot();
        store.write(legacy);
        assertNull(store.read(legacy.nonce()).owner());
        assertThrows(Exception.class, () -> store.write(legacy.withPhase(JointMigrationJournal.Phase.CANCELLED)));
        store.write(legacy.withPhase(JointMigrationJournal.Phase.TRANSFERRING));
        store.write(legacy.withPhase(JointMigrationJournal.Phase.VERIFYING));
        store.write(legacy.withPhase(JointMigrationJournal.Phase.COMMITTED));
        store.write(legacy.withPhase(JointMigrationJournal.Phase.RELEASED));
        assertEquals(legacy.withPhase(JointMigrationJournal.Phase.RELEASED), store.read(legacy.nonce()));
    }

    @Test void failClosedOnTruncationChecksumAndUnknownFiles() throws Exception {
        var store = new JointMigrationJournal(root);
        var record = snapshot();
        store.write(record);
        var file = store.directory().resolve(record.nonce() + ".bsjm");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 33] ^= 1;
        Files.write(file, bytes);
        assertThrows(Exception.class, () -> store.read(record.nonce()));
        assertThrows(Exception.class, store::scan);
        Files.write(file, new byte[5]);
        assertThrows(Exception.class, store::scan);
        Files.delete(file);
        Files.writeString(store.directory().resolve(".bsjm-crash.pending"), "interrupted");
        assertThrows(Exception.class, store::scan);
    }

    @Test void rejectsUnboundedInvalidOrExternallyConnectedDefinitions() {
        var record = snapshot();
        var input = new byte[]{5, 6};
        var joint = new JointMigrationJournal.Joint(5, 1, 2, input);
        input[0] = 42;
        assertArrayEquals(new byte[]{5, 6}, joint.definition());
        joint.definition()[1] = 42;
        assertArrayEquals(new byte[]{5, 6}, joint.definition());
        assertThrows(IllegalArgumentException.class, () -> new JointMigrationJournal.Joint(Integer.MAX_VALUE, 1, 2, input));
        assertThrows(IllegalArgumentException.class, () -> new JointMigrationJournal.Joint(7, 1, 3, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new JointMigrationJournal.Record(record.nonce(),
            record.source(), record.destination(), 100, record.phase(), record.bodies(),
            List.of(new JointMigrationJournal.Joint(42, 1, 3, input))));
    }

    @Test void nonceInFileMustMatchRequestedNonceAndPhaseCannotSkip() throws Exception {
        var store = new JointMigrationJournal(root);
        var record = snapshot();
        store.write(record);
        UUID another = UUID.randomUUID();
        Files.copy(store.directory().resolve(record.nonce() + ".bsjm"), store.directory().resolve(another + ".bsjm"));
        assertThrows(Exception.class, () -> store.read(another));
        Files.delete(store.directory().resolve(another + ".bsjm"));
        assertThrows(Exception.class, () -> store.write(record.withPhase(JointMigrationJournal.Phase.COMMITTED)));
    }

    @Test void recordsRetainOriginalStaticFlagsAndAllSixVelocityComponents() throws Exception {
        var store = new JointMigrationJournal(root);
        var record = snapshot();
        store.write(record);
        assertTrue(store.read(record.nonce()).bodies().get(0).wasStatic());
        var dynamic = store.read(record.nonce()).bodies().get(1);
        assertFalse(dynamic.wasStatic());
        assertEquals(List.of(4.0, -3.0, 7.0, 1.0, 2.0, -1.0),
            List.of(dynamic.vx(), dynamic.vy(), dynamic.vz(), dynamic.wx(), dynamic.wy(), dynamic.wz()));
    }
}
