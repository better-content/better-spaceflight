package com.bettercontent.spaceflight.vs.compat.recovery;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Set;

/**
 * Comparison ONLY; never rewrites raw capture parts. Minecraft 1.20.1
 * ChunkSerializer.write stores root LastUpdate=gameTime and SavedTick stores
 * (int)(triggerTick-gameTime) in t. Only these proven bookkeeping values may
 * differ. Tick identity, coordinates, priority, list order/count and every
 * other NBT node (including BE cargo and ForgeCaps) must be identical.
 */
public final class SemanticCaptureComparator {
    private SemanticCaptureComparator() {}
    private static final long MAX_REVALIDATION_TICKS = 200; // 10 seconds at nominal 20 TPS; fail closed if slower
    public static void requireSame(CaptureEnvelope before, CaptureEnvelope after) throws IOException {
        if (before == null || after == null || !before.samePhysicalIdentity(after))
            throw new IOException("Capture operation/world/journal changed");
        long elapsed;
        try { elapsed = Math.subtractExact(after.captureGameTick(), before.captureGameTick()); }
        catch (ArithmeticException ex) { throw new IOException("Capture game-tick elapsed overflow", ex); }
        if (elapsed < 1 || elapsed > MAX_REVALIDATION_TICKS)
            throw new IOException("Semantic recapture must be on a later bounded game tick");
        var left = new HashMap<String, CaptureEnvelope.Piece>();
        var right = new HashMap<String, CaptureEnvelope.Piece>();
        for (var part : before.pieces()) left.put(part.kind() + ":" + part.key(), part);
        for (var part : after.pieces()) right.put(part.kind() + ":" + part.key(), part);
        if (!left.keySet().equals(right.keySet())) throw new IOException("Required capture part set changed");
        for (var entry : left.entrySet()) {
            var a = entry.getValue(); var b = right.get(entry.getKey());
            if (a.kind() != CaptureEnvelope.Kind.CHUNK) {
                if (!java.util.Arrays.equals(a.bytes(), b.bytes()))
                    throw new IOException("Captured actor/ship/joint/capability payload changed: " + entry.getKey());
            } else {
                CompoundTag first = readChunk(a.bytes());
                CompoundTag second = readChunk(b.bytes());
                normalizeTimeOnly(first, before.captureGameTick());
                normalizeTimeOnly(second, after.captureGameTick());
                if (!first.equals(second)) throw new IOException("Meaningful chunk/tick/cargo state changed: " + entry.getKey());
            }
        }
    }
    private static CompoundTag readChunk(byte[] raw) throws IOException {
        if (raw.length == 0 || raw.length > CaptureEnvelope.MAX_PART_BYTES)
            throw new IOException("Chunk part exceeds detached bounds");
        try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
            CompoundTag tag = NbtIo.read(in, new NbtAccounter(CaptureEnvelope.MAX_PART_BYTES * 8L));
            if (tag == null || in.available() != 0) throw new IOException("Chunk NBT has missing root/trailing data");
            return tag; // parsed copy; original byte[] remains intact
        } catch (RuntimeException ex) { throw new IOException("Unknown/oversized chunk NBT", ex); }
    }
    private static void normalizeTimeOnly(CompoundTag root, long captureGameTick) throws IOException {
        if (!root.contains("LastUpdate", Tag.TAG_LONG) || root.getLong("LastUpdate") != captureGameTick)
            throw new IOException("Unknown LastUpdate encoding or capture game-tick mismatch");
        // No other node named LastUpdate is touched; no global string rewriting.
        root.putLong("LastUpdate", 0L);
        normalizeTickList(root, "block_ticks", captureGameTick);
        normalizeTickList(root, "fluid_ticks", captureGameTick);
    }
    private static void normalizeTickList(CompoundTag root, String key, long gameTick) throws IOException {
        if (!root.contains(key, Tag.TAG_LIST)) throw new IOException("Missing/unknown tick list " + key);
        ListTag ticks = root.getList(key, Tag.TAG_COMPOUND);
        if (ticks.size() > 65_536) throw new IOException("Scheduled tick count bound exceeded");
        // getList(key, COMPOUND) returns an empty list for mismatched element
        // type. Explicit element-type validation avoids accepting an unknown
        // nonempty tick encoding as an empty list.
        ListTag raw = (ListTag) root.get(key);
        if (!raw.isEmpty() && raw.getElementType() != Tag.TAG_COMPOUND)
            throw new IOException("Unknown scheduled tick list element type");
        Set<String> vanilla = Set.of("i", "x", "y", "z", "t", "p");
        for (int i = 0; i < ticks.size(); i++) {
            CompoundTag tick = ticks.getCompound(i);
            if (!tick.getAllKeys().equals(vanilla) || !tick.contains("i", Tag.TAG_STRING)
                || tick.getString("i").isBlank() || !tick.contains("x", Tag.TAG_INT)
                || !tick.contains("y", Tag.TAG_INT) || !tick.contains("z", Tag.TAG_INT)
                || !tick.contains("p", Tag.TAG_INT) || !tick.contains("t", Tag.TAG_INT)
                || tick.getInt("p") < -3 || tick.getInt("p") > 3)
                throw new IOException("Unknown scheduled tick encoding/priority");
            long deadline;
            try { deadline = Math.addExact(gameTick, tick.getInt("t")); }
            catch (ArithmeticException ex) { throw new IOException("Scheduled tick absolute deadline overflow", ex); }
            tick.putLong("t", deadline); // comparison copy ONLY, preserves list index and all other fields
        }
    }
}
