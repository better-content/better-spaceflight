package com.bettercontent.spaceflight.vs.compat.recovery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * One REAL, loaded FULL source chunk's original serializer payload, detached on
 * server game thread. This does not prove that active-chunk selection covers
 * unloaded/on-disk meaningful claim state or arbitrary provider/BE content fidelity.
 * No chunk loads, generation, source mutation, or disk I/O.
 */
public final class LoadedChunkCapture {
    public record Result(CaptureEnvelope.Piece part, CaptureEnvelope.Piece exactTickDetails,
                         boolean capabilityPayloadObserved,
                         int blockEntitiesObserved, int blockTicksObserved, int fluidTicksObserved) {}
    private LoadedChunkCapture() {}
    public static Result capture(ServerLevel source, String expectedVsDimension, int x, int z) throws IOException {
        if (!source.getServer().isSameThread()) throw new IllegalStateException("Chunk capture must run on server game thread");
        if (!VSGameUtilsKt.getDimensionId(source).equals(expectedVsDimension))
            throw new IOException("Wrong source VS dimension");
        LevelChunk chunk = source.getChunkSource().getChunkNow(x, z);
        if (chunk == null || chunk.getStatus() != ChunkStatus.FULL || !chunk.getPos().equals(new net.minecraft.world.level.ChunkPos(x, z)))
            throw new IOException("Chunk not currently loaded FULL; refusing generation/load: " + x + "," + z);
        // Supplemental exact trigger/sub-order evidence is necessary because
        // vanilla SavedTick writes only an int relative delay and omits the
        // original subTickOrder. Refuse pending un-unpacked tick containers and
        // any trigger difference the int serializer could truncate.
        var tickEvidence = captureExactTicks(chunk, source.getGameTime(), expectedVsDimension + ":" + x + "," + z);
        // Call the actual vanilla+Forge writer ONCE, not a second arbitrary BE/cap
        // serializer pass. Verify its serialized tick inventory against the
        // previously observed live ScheduledTick identities after it returns.
        Set<BlockPos> expectedBePositions = Set.copyOf(chunk.getBlockEntitiesPos());
        var capabilityBefore = ForgeChunkCapabilityEvidence.inspect(chunk);
        CompoundTag nbt;
        try { nbt = ChunkSerializer.write(source, chunk); }
        catch (RuntimeException ex) { throw new IOException("Chunk serialization failed", ex); }
        var capabilityAfter = ForgeChunkCapabilityEvidence.inspect(chunk);
        boolean capabilityPayloadObserved = ForgeChunkCapabilityEvidence.validate(capabilityBefore, capabilityAfter, nbt);
        if (nbt.getInt("xPos") != x || nbt.getInt("zPos") != z || nbt.getInt("yPos") != source.getMinSection())
            throw new IOException("Chunk serializer shifted coords/layout");
        if (!nbt.contains("LastUpdate", Tag.TAG_LONG) || nbt.getLong("LastUpdate") != source.getGameTime())
            throw new IOException("Chunk serializer source game time mismatch");
        if (!nbt.contains("sections", Tag.TAG_LIST) || !nbt.contains("block_entities", Tag.TAG_LIST)
            || !nbt.contains("block_ticks", Tag.TAG_LIST) || !nbt.contains("fluid_ticks", Tag.TAG_LIST)
            || !nbt.contains("PostProcessing", Tag.TAG_LIST) || !nbt.contains("Heightmaps", Tag.TAG_COMPOUND)
            || !nbt.contains("structures", Tag.TAG_COMPOUND))
            throw new IOException("Required complete FULL chunk fields missing");
        validateSerializedTicks(tickEvidence.blockRows(), nbt, "block_ticks", source.getGameTime());
        validateSerializedTicks(tickEvidence.fluidRows(), nbt, "fluid_ticks", source.getGameTime());
        ListTag entities = nbt.getList("block_entities", Tag.TAG_COMPOUND);
        Set<BlockPos> savedBePositions = new HashSet<>();
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag be = entities.getCompound(i);
            if (!be.contains("id", Tag.TAG_STRING) || be.getString("id").isBlank()
                || !be.contains("x", Tag.TAG_INT) || !be.contains("y", Tag.TAG_INT)
                || !be.contains("z", Tag.TAG_INT)) throw new IOException("Incomplete block-entity NBT");
            var position = new BlockPos(be.getInt("x"), be.getInt("y"), be.getInt("z"));
            if (!savedBePositions.add(position)) throw new IOException("Duplicate block-entity NBT");
        }
        if (!savedBePositions.equals(expectedBePositions))
            throw new IOException("Chunk writer silently dropped block-entity payload");
        var bytes = new CaptureEnvelope.BoundedBytes(CaptureEnvelope.MAX_PART_BYTES);
        try (var data = new DataOutputStream(bytes)) { NbtIo.write(nbt, data); }
        catch (CaptureEnvelope.BoundedBytes.Overflow ex) { throw new IOException("Chunk NBT exceeds bounded part", ex); }
        return new Result(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK,
            expectedVsDimension + ":" + x + "," + z, bytes.toByteArray()), tickEvidence.exact(),
            capabilityPayloadObserved,
            entities.size(), nbt.getList("block_ticks", Tag.TAG_COMPOUND).size(),
            nbt.getList("fluid_ticks", Tag.TAG_COMPOUND).size());
    }
    private record TickEvidence(CaptureEnvelope.Piece exact, java.util.List<String> blockRows,
                                java.util.List<String> fluidRows) {}
    private static TickEvidence captureExactTicks(LevelChunk chunk, long gameTick, String key) throws IOException {
        var ticks = chunk.getTicksForSerialization();
        if (!(ticks.blocks() instanceof LevelChunkTicks<?> blocks)
            || !(ticks.fluids() instanceof LevelChunkTicks<?> fluids))
            throw new IOException("Unknown live tick container implementation");
        var rows = new ArrayList<String>();
        var blockRows = new ArrayList<String>();
        var fluidRows = new ArrayList<String>();
        collectTicks(blocks, blocks.count(), gameTick, "block", rows, blockRows);
        collectTicks(fluids, fluids.count(), gameTick, "fluid", rows, fluidRows);
        rows.sort(Comparator.naturalOrder());
        var sink = new CaptureEnvelope.BoundedBytes(CaptureEnvelope.MAX_PART_BYTES);
        try (var out = new DataOutputStream(sink)) {
            out.writeInt(0x42535444); out.writeInt(1); out.writeInt(rows.size());
            for (String row : rows) out.writeUTF(row);
        } catch (CaptureEnvelope.BoundedBytes.Overflow ex) {
            throw new IOException("Exact tick inventory exceeds capture part bound", ex);
        }
        return new TickEvidence(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.CHUNK_TICK_DETAILS,
            key, sink.toByteArray()), blockRows, fluidRows);
    }
    private static void collectTicks(LevelChunkTicks<?> container, int expected, long gameTick,
                                     String kind, ArrayList<String> rows, ArrayList<String> serializedRows) throws IOException {
        var all = container.getAll().toList();
        if (expected != all.size() || expected > 65_536)
            throw new IOException("Pending/unknown live tick queue cannot prove exact sub-order");
        for (ScheduledTick<?> tick : all) {
            long delay;
            try { delay = Math.subtractExact(tick.triggerTick(), gameTick); }
            catch (ArithmeticException ex) { throw new IOException("Tick deadline difference overflow", ex); }
            if (delay < Integer.MIN_VALUE || delay > Integer.MAX_VALUE)
                throw new IOException("Vanilla int tick-delay encoding would truncate true deadline");
            Object type = tick.type();
            var id = "block".equals(kind) && type instanceof Block b ? BuiltInRegistries.BLOCK.getKey(b)
                : "fluid".equals(kind) && type instanceof Fluid f ? BuiltInRegistries.FLUID.getKey(f) : null;
            if (id == null) throw new IOException("Unregistered/unknown scheduled tick type");
            String identity = id + "|" + tick.pos().getX() + "|" + tick.pos().getY() + "|"
                + tick.pos().getZ() + "|" + tick.triggerTick() + "|" + tick.priority().getValue();
            serializedRows.add(identity);
            rows.add(kind + "|" + identity + "|" + tick.subTickOrder());
            if (rows.size() > 65_536) throw new IOException("Total exact tick evidence bound exceeded");
        }
    }
    /** Strict one-to-one matching of vanilla SavedTick identity/count to live queue. */
    static void validateSerializedTicks(java.util.List<String> expected, CompoundTag root,
                                        String key, long gameTick) throws IOException {
        if (!root.contains(key, Tag.TAG_LIST)) throw new IOException("Missing serialized tick list " + key);
        ListTag raw = (ListTag) root.get(key);
        if (!raw.isEmpty() && raw.getElementType() != Tag.TAG_COMPOUND)
            throw new IOException("Unknown serialized tick element type");
        if (raw.size() != expected.size()) throw new IOException("Tick serializer lost/added entries");
        var actual = new ArrayList<String>();
        var fields = Set.of("i", "x", "y", "z", "t", "p");
        for (int i = 0; i < raw.size(); i++) {
            CompoundTag tick = raw.getCompound(i);
            if (!tick.getAllKeys().equals(fields) || !tick.contains("i", Tag.TAG_STRING)
                || tick.getString("i").isBlank() || !tick.contains("x", Tag.TAG_INT)
                || !tick.contains("y", Tag.TAG_INT) || !tick.contains("z", Tag.TAG_INT)
                || !tick.contains("t", Tag.TAG_INT) || !tick.contains("p", Tag.TAG_INT)
                || tick.getInt("p") < -3 || tick.getInt("p") > 3)
                throw new IOException("Unknown mandatory serialized tick encoding");
            long deadline;
            try { deadline = Math.addExact(gameTick, tick.getInt("t")); }
            catch (ArithmeticException ex) { throw new IOException("Serialized tick deadline overflow", ex); }
            actual.add(tick.getString("i") + "|" + tick.getInt("x") + "|" + tick.getInt("y") + "|"
                + tick.getInt("z") + "|" + deadline + "|" + tick.getInt("p"));
        }
        actual.sort(Comparator.naturalOrder());
        var sortedExpected = new ArrayList<>(expected);
        sortedExpected.sort(Comparator.naturalOrder());
        if (!actual.equals(sortedExpected)) throw new IOException("Serialized tick identity/trigger/priority changed");
    }
}
