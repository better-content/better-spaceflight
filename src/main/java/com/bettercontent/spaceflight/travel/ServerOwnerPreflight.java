package com.bettercontent.spaceflight.travel;

import com.bettercontent.spaceflight.vs.compat.JointMigrationJournal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * World-local startup scan before ANY later hook binding or travel admission.
 * This is a conservative policy gate, not native startup reconciliation. The
 * production server owner must additionally prove the exact running pipeline,
 * transformed hooks and physical thread lifecycle before binding. No method here
 * grants transfer readiness or calls a teleport adapter.
 */
public final class ServerOwnerPreflight {
    public enum Result { CLEAR_BUT_UNBOUND, JOURNAL_BLOCKED }
    public record Inspection(Result result, String diagnostic, Path directory,
                             int releasedRecords, int cancelledRecords) {
        public Inspection { Objects.requireNonNull(result); Objects.requireNonNull(diagnostic);
            Objects.requireNonNull(directory); }
        /** Terminal joint records alone never prove full-world recovery or a safe transfer. */
        public boolean transferAvailable() { return false; }
    }
    private ServerOwnerPreflight() {}

    public static Inspection scan(Path worldDirectory) {
        Objects.requireNonNull(worldDirectory);
        var journal = new JointMigrationJournal(worldDirectory);
        List<JointMigrationJournal.Record> records;
        try { records = journal.scan(); }
        catch (IOException | RuntimeException ex) {
            return new Inspection(Result.JOURNAL_BLOCKED,
                "Joint migration journal unreadable at " + journal.directory() + ": " + ex.getMessage()
                    + "; stop transfer admission and restore/inspect whole-world backup",
                journal.directory(), 0, 0);
        }
        int released = 0, cancelled = 0;
        for (var record : records) {
            if (record.phase() == JointMigrationJournal.Phase.RELEASED) { released++; continue; }
            if (record.phase() == JointMigrationJournal.Phase.CANCELLED && record.owner() != null) {
                cancelled++; continue; // terminal intent only, not a transfer or world recovery proof
            }
            return new Inspection(Result.JOURNAL_BLOCKED,
                "Incomplete joint migration " + record.nonce() + " phase=" + record.phase()
                    + " at " + journal.directory() + "; preserve the world and reconcile chunks, entities,"
                    + " block entities, ticks, cargo and native bodies before any travel",
                journal.directory(), 0, 0);
        }
        return new Inspection(Result.CLEAR_BUT_UNBOUND,
            "Joint records terminal/absent; no native owner, recovery provider or transport is qualified",
            journal.directory(), released, cancelled);
    }
}
