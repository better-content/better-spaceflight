package com.bettercontent.spaceflight.travel;

import com.bettercontent.spaceflight.vs.compat.JointMigrationJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ServerOwnerPreflightTest {
    @TempDir Path root;
    private JointMigrationJournal.Record record() {
        return new JointMigrationJournal.Record(UUID.randomUUID(), "minecraft:overworld",
            "better_spaceflight:space", 10, JointMigrationJournal.Phase.PREPARING,
            List.of(new JointMigrationJournal.Body(9, false, 0, 0, 0, 0, 0, 0)), List.of());
    }
    @Test void missingDirectoryIsNotAnAdmissionGrant() {
        var result = ServerOwnerPreflight.scan(root);
        assertEquals(ServerOwnerPreflight.Result.CLEAR_BUT_UNBOUND, result.result());
        assertFalse(result.transferAvailable());
        assertEquals(0, result.releasedRecords());
        assertEquals(0, result.cancelledRecords());
    }
    @Test void everyIncompletePhaseBlocksBeforeOwnerBinding() throws Exception {
        for (var phase : new JointMigrationJournal.Phase[]{JointMigrationJournal.Phase.PREPARING,
            JointMigrationJournal.Phase.TRANSFERRING, JointMigrationJournal.Phase.VERIFYING,
            JointMigrationJournal.Phase.COMMITTED, JointMigrationJournal.Phase.RECOVERY_REQUIRED}) {
            var subworld = root.resolve(phase.name()); var store = new JointMigrationJournal(subworld);
            var value = record();
            store.write(value);
            if (phase == JointMigrationJournal.Phase.RECOVERY_REQUIRED) store.write(value.withPhase(phase));
            else for (var step : new JointMigrationJournal.Phase[]{JointMigrationJournal.Phase.TRANSFERRING,
                JointMigrationJournal.Phase.VERIFYING, JointMigrationJournal.Phase.COMMITTED}) {
                if (step.ordinal() > phase.ordinal()) break;
                store.write(value.withPhase(step));
            }
            var result = ServerOwnerPreflight.scan(subworld);
            assertEquals(ServerOwnerPreflight.Result.JOURNAL_BLOCKED, result.result());
            assertTrue(result.diagnostic().contains(value.nonce().toString()));
            assertFalse(result.transferAvailable());
        }
    }
    @Test void terminalRecordIsReadButNotAnEndToEndWorldRecoveryProof() throws Exception {
        var store = new JointMigrationJournal(root); var value = record(); store.write(value);
        for (var phase : new JointMigrationJournal.Phase[]{JointMigrationJournal.Phase.TRANSFERRING,
            JointMigrationJournal.Phase.VERIFYING, JointMigrationJournal.Phase.COMMITTED,
            JointMigrationJournal.Phase.RELEASED}) store.write(value.withPhase(phase));
        var result = ServerOwnerPreflight.scan(root);
        assertEquals(ServerOwnerPreflight.Result.CLEAR_BUT_UNBOUND, result.result());
        assertEquals(1, result.releasedRecords());
        assertEquals(0, result.cancelledRecords());
        assertFalse(result.transferAvailable());
    }
    @Test void ownerBoundCancelledCountsSeparatelyAndLegacyPreparingStillBlocks() throws Exception {
        var store = new JointMigrationJournal(root);
        var old = record();
        var bound = new JointMigrationJournal.Record(old.nonce(), old.source(), old.destination(),
            old.deadlineTick(), old.phase(), old.bodies(), old.joints(), UUID.randomUUID());
        store.write(bound); store.write(bound.withPhase(JointMigrationJournal.Phase.CANCELLED));
        var terminal = ServerOwnerPreflight.scan(root);
        assertEquals(ServerOwnerPreflight.Result.CLEAR_BUT_UNBOUND, terminal.result());
        assertEquals(0, terminal.releasedRecords());
        assertEquals(1, terminal.cancelledRecords());
        assertFalse(terminal.transferAvailable());
        store.write(record()); // separate legacy PREPARING blocks, despite terminal cancellation
        assertEquals(ServerOwnerPreflight.Result.JOURNAL_BLOCKED, ServerOwnerPreflight.scan(root).result());
    }

    @Test void corruptPendingAndUnknownFilesFailClosedWithActionableLocation() throws Exception {
        var store = new JointMigrationJournal(root); var value = record(); store.write(value);
        Path file = store.directory().resolve(value.nonce() + ".bsjm");
        Files.write(file, new byte[]{1, 2});
        var corrupt = ServerOwnerPreflight.scan(root);
        assertEquals(ServerOwnerPreflight.Result.JOURNAL_BLOCKED, corrupt.result());
        assertTrue(corrupt.diagnostic().contains(store.directory().toString()));
        Files.delete(file);
        Files.writeString(store.directory().resolve("unknown-file"), "do not ignore");
        assertEquals(ServerOwnerPreflight.Result.JOURNAL_BLOCKED, ServerOwnerPreflight.scan(root).result());
        Files.delete(store.directory().resolve("unknown-file"));
        Files.writeString(store.directory().resolve(".bsjm-incomplete.pending"), "do not ignore");
        assertEquals(ServerOwnerPreflight.Result.JOURNAL_BLOCKED, ServerOwnerPreflight.scan(root).result());
    }
}
