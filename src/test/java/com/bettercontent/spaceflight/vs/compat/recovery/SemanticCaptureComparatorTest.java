package com.bettercontent.spaceflight.vs.compat.recovery;

import com.bettercontent.spaceflight.vs.compat.JointMigrationJournal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class SemanticCaptureComparatorTest {
    @TempDir Path root;
    private JointMigrationJournal.Record joint() {
        return new JointMigrationJournal.Record(UUID.randomUUID(), "vs:overworld", "vs:space", 10,
            JointMigrationJournal.Phase.PREPARING,
            List.of(new JointMigrationJournal.Body(1, false, 0, 0, 0, 0, 0, 0)), List.of());
    }
    private byte[] chunk(long now, int delay, String cargo, int priority) throws Exception {
        var root = new CompoundTag(); root.putLong("LastUpdate", now);
        root.putString("Cargo", cargo);
        var tick = new CompoundTag();
        tick.putString("i", "minecraft:stone"); tick.putInt("x", 3); tick.putInt("y", 4);
        tick.putInt("z", 5); tick.putInt("t", delay); tick.putInt("p", priority);
        var blocks = new ListTag(); blocks.add(tick);
        root.put("block_ticks", blocks); root.put("fluid_ticks", new ListTag());
        var out = new CaptureEnvelope.BoundedBytes(CaptureEnvelope.MAX_PART_BYTES);
        NbtIo.write(root, new java.io.DataOutputStream(out));
        return out.toByteArray();
    }
    private CaptureEnvelope evidence(JointMigrationJournal.Record joint, long now, byte[] chunk) throws Exception {
        return evidence(joint, now, chunk, new byte[]{1});
    }
    private CaptureEnvelope evidence(JointMigrationJournal.Record joint, long now, byte[] chunk,
                                     byte[] exactTicks) throws Exception {
        return new CaptureEnvelope.Builder(root, joint, now)
            .add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK, "vs:overworld:1,2", chunk))
            .add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_CAPABILITY_STATUS,
                "vs:overworld:1,2", new byte[]{0}))
            .add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_TICK_DETAILS,
                "vs:overworld:1,2", exactTicks))
            .build(new CaptureEnvelope.Status(false, false, false, List.of("Partial selected evidence")));
    }
    @Test void onlyProvenLastUpdateAndCheckedTickDelayMayChangeRawBytesRemainIntact() throws Exception {
        var j = joint(); byte[] raw = chunk(100, 20, "same", 0); byte[] original = raw.clone();
        var before = evidence(j, 100, raw); var after = evidence(j, 110, chunk(110, 10, "same", 0));
        SemanticCaptureComparator.requireSame(before, after);
        assertArrayEquals(original, before.pieces().get(1).bytes());
        assertFalse(java.util.Arrays.equals(before.pieces().get(1).bytes(), after.pieces().get(1).bytes()));
    }
    @Test void cargoPriorityDeadlineAndJournalIdentityChangesInvalidate() throws Exception {
        var j = joint(); var base = evidence(j, 100, chunk(100, 20, "full", 0));
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(base,
            evidence(j, 110, chunk(110, 10, "emptied", 0))));
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(base,
            evidence(j, 110, chunk(110, 10, "full", 1))));
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(base,
            evidence(j, 110, chunk(110, 9, "full", 0))));
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(base,
            evidence(joint(), 110, chunk(110, 10, "full", 0))));
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(base,
            evidence(j, 110, chunk(110, 10, "full", 0), new byte[]{2})));
    }
    @Test void invalidTimeEncodingOrDeadlineOverflowAndUnboundedWaitRefuse() throws Exception {
        var j = joint(); var base = evidence(j, Long.MAX_VALUE - 1, chunk(Long.MAX_VALUE - 1, 10, "full", 0));
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(base,
            evidence(j, Long.MAX_VALUE, chunk(Long.MAX_VALUE, 9, "full", 0))));
        var wrong = evidence(j, 100, chunk(90, 20, "full", 0));
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(wrong,
            evidence(j, 101, chunk(91, 19, "full", 0))));
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(wrong, wrong));
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(
            evidence(j, 100, chunk(100, 20, "full", 0)), evidence(j, 301, chunk(301, -181, "full", 0))));
    }
    @Test void compensatingRelativeDelayCannotHideChangedExactLiveTickTrigger() throws Exception {
        var j = joint();
        var before = evidence(j, 100, chunk(100, 20, "full", 0), new byte[]{0, 0, 0, 120});
        // Chunk t shifts 20 -> 10, so both normalized deadlines are 120;
        // independent live ScheduledTick evidence records a genuine 121 instead.
        var after = evidence(j, 110, chunk(110, 10, "full", 0), new byte[]{0, 0, 0, 121});
        assertThrows(java.io.IOException.class, () -> SemanticCaptureComparator.requireSame(before, after));
    }
}
