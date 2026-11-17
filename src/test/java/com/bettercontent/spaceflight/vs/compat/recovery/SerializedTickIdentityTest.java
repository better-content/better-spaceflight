package com.bettercontent.spaceflight.vs.compat.recovery;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure exact-multiset check against ONE original serialized tick-list output. */
final class SerializedTickIdentityTest {
    private static CompoundTag tag(int delay, int priority, String id) {
        var root = new CompoundTag(); var list = new ListTag(); var tick = new CompoundTag();
        tick.putString("i", id); tick.putInt("x", 3); tick.putInt("y", 4);
        tick.putInt("z", 5); tick.putInt("t", delay); tick.putInt("p", priority);
        list.add(tick); root.put("block_ticks", list); return root;
    }
    @Test void exactlySavedTriggerPriorityAndIdentityMatchPreReadLiveInventory() throws Exception {
        var expected = List.of("minecraft:stone|3|4|5|120|0");
        LoadedChunkCapture.validateSerializedTicks(expected, tag(20, 0, "minecraft:stone"), "block_ticks", 100);
        assertThrows(IOException.class, () -> LoadedChunkCapture.validateSerializedTicks(expected,
            tag(19, 0, "minecraft:stone"), "block_ticks", 100));
        assertThrows(IOException.class, () -> LoadedChunkCapture.validateSerializedTicks(expected,
            tag(20, 1, "minecraft:stone"), "block_ticks", 100));
        assertThrows(IOException.class, () -> LoadedChunkCapture.validateSerializedTicks(expected,
            tag(20, 0, "minecraft:dirt"), "block_ticks", 100));
        assertThrows(IOException.class, () -> LoadedChunkCapture.validateSerializedTicks(expected,
            new CompoundTag(), "block_ticks", 100));
    }
    @Test void omittedOrDuplicateLiveTickOrUnknownEncodingRefuses() throws Exception {
        var expected = List.of("minecraft:stone|3|4|5|120|0", "minecraft:stone|3|4|5|120|0");
        assertThrows(IOException.class, () -> LoadedChunkCapture.validateSerializedTicks(expected,
            tag(20, 0, "minecraft:stone"), "block_ticks", 100));
        var unknown = tag(20, 0, "minecraft:stone");
        unknown.getList("block_ticks", 10).getCompound(0).putLong("t", 20);
        assertThrows(IOException.class, () -> LoadedChunkCapture.validateSerializedTicks(expected.subList(0, 1),
            unknown, "block_ticks", 100));
        var overflowing = tag(10, 0, "minecraft:stone");
        assertThrows(IOException.class, () -> LoadedChunkCapture.validateSerializedTicks(
            List.of("minecraft:stone|3|4|5|" + Long.MAX_VALUE + "|0"),
            overflowing, "block_ticks", Long.MAX_VALUE - 1));
    }
}
