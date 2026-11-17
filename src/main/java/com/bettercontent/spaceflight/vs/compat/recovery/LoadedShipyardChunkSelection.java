package com.bettercontent.spaceflight.vs.compat.recovery;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.valkyrienskies.core.api.ships.ServerShip;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * At a single game-thread confirmation, enumerate actual in-memory visible,
 * updating and pending-unload holders. Never loop over the enormous unused
 * ship-claim rectangle or force a chunk load/generation/disk read. Incomplete
 * holders or inaccessible exact fields refuse rather than missing pending-only
 * shipyard state. On-disk/unloaded coverage remains outside this proof.
 */
public final class LoadedShipyardChunkSelection {
    private LoadedShipyardChunkSelection() {}
    public static List<ChunkPos> current(ServerLevel source, List<? extends ServerShip> members) throws IOException {
        if (!source.getServer().isSameThread()) throw new IllegalStateException("Chunk enumeration off game thread");
        if (members.isEmpty() || members.size() > CaptureEnvelope.MAX_MEMBERS) throw new IOException("Unbounded member set");
        var selected = new HashSet<ChunkPos>();
        try {
            ChunkMap map = source.getChunkSource().chunkMap;
            var lookup = MethodHandles.privateLookupIn(ChunkMap.class, MethodHandles.lookup());
            for (String field : List.of("visibleChunkMap", "updatingChunkMap", "pendingUnloads")) {
                @SuppressWarnings("unchecked")
                var holders = (Long2ObjectLinkedOpenHashMap<ChunkHolder>) lookup.findGetter(
                    ChunkMap.class, field, Long2ObjectLinkedOpenHashMap.class).invoke(map);
                if (holders.size() > 4096) throw new IOException("World holder scan exceeds confirmation bound");
                for (var holder : holders.values()) {
                    ChunkPos pos = holder.getPos();
                    if (members.stream().noneMatch(ship -> ship.getChunkClaim().contains(pos.x, pos.z))) continue;
                    if (holder.getFullChunk() == null) throw new IOException("Member claim has pending/non-FULL loaded holder " + pos);
                    if (!selected.add(pos)) continue;
                    if (selected.size() > CaptureEnvelope.MAX_CHUNKS) throw new IOException("Loaded member claim exceeds 128 chunks");
                    if (source.getChunkSource().getChunkNow(pos.x, pos.z) != holder.getFullChunk())
                        throw new IOException("Pending/unloading chunk not exactly visible FULL " + pos);
                }
            }
        } catch (IOException ex) { throw ex; }
        catch (Throwable ex) { throw new IOException("Pinned loaded chunk holder enumeration inaccessible", ex); }
        var ordered = new ArrayList<>(selected);
        ordered.sort(java.util.Comparator.comparingInt((ChunkPos p) -> p.x).thenComparingInt(p -> p.z));
        return ordered;
    }
}
