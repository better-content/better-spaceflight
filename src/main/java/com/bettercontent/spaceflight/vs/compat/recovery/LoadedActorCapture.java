package com.bettercontent.spaceflight.vs.compat.recovery;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Loaded CANDIDATE riding graph capture. Does not assert world-wide onboard
 * enumeration: on-deck players/shipyard storage may escape a caller's root set.
 * Player NBT is separate because vanilla Entity.saveAsPassenger may omit players.
 * Unknown/unserializable nested passengers cause refusal, never silent omission.
 */
public final class LoadedActorCapture {
    public record Result(List<CaptureEnvelope.Piece> pieces, List<UUID> actorIds,
                         List<String> ridingEdges, boolean onboardClosureProven) {
        public Result { pieces = List.copyOf(pieces); actorIds = List.copyOf(actorIds); ridingEdges = List.copyOf(ridingEdges); }
    }
    private LoadedActorCapture() {}
    public static Result candidates(ServerLevel source, List<? extends Entity> roots) throws IOException {
        if (!source.getServer().isSameThread()) throw new IllegalStateException("Actor capture outside game thread");
        if (roots == null || roots.size() > CaptureEnvelope.MAX_ENTITIES) throw new IOException("Unbounded actor roots");
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var identityByUuid = new java.util.HashMap<UUID, Entity>();
        var edges = new ArrayList<String>();
        for (Entity root : roots) {
            if (root == null || root.isPassenger()) throw new IOException("Missing root or riding graph crosses selection");
            walk(source, root, seen, identityByUuid, edges);
        }
        var pieces = new ArrayList<CaptureEnvelope.Piece>();
        // Each root serializer is invoked ONCE. It writes nonplayer nested
        // passengers; verify that it did not silently omit any of them.
        for (Entity root : roots) {
            CompoundTag nbt = new CompoundTag();
            if (root instanceof ServerPlayer player) {
                if (source.getServer().getPlayerList().getPlayer(player.getUUID()) != player)
                    throw new IOException("Player no longer online/canonical");
                try { player.saveWithoutId(nbt); }
                catch (RuntimeException ex) { throw new IOException("Player save failed", ex); }
            } else {
                try { if (!root.saveAsPassenger(nbt)) throw new IOException("Unserializable root actor " + root.getUUID()); }
                catch (RuntimeException ex) { throw new IOException("Actor save failed", ex); }
            }
            verifyGraph(root, nbt);
            pieces.add(new CaptureEnvelope.Piece(root instanceof ServerPlayer
                ? CaptureEnvelope.Kind.PLAYER : CaptureEnvelope.Kind.ENTITY,
                root.getUUID().toString(), encode(nbt)));
        }
        // Players riding a nonplayer root do not have an encoded Entity ID;
        // persist their complete player data separately, preserving UUID edges.
        for (Entity node : seen) if (node instanceof ServerPlayer player && !roots.contains(player)) {
            if (source.getServer().getPlayerList().getPlayer(player.getUUID()) != player)
                throw new IOException("Disconnected/offline mounted player");
            var nbt = new CompoundTag();
            try { player.saveWithoutId(nbt); }
            catch (RuntimeException ex) { throw new IOException("Mounted player save failed", ex); }
            verifyGraph(player, nbt);
            pieces.add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.PLAYER, player.getUUID().toString(), encode(nbt)));
        }
        var ordered = seen.stream().sorted(java.util.Comparator.comparing(Entity::getUUID)).toList();
        var ids = ordered.stream().map(Entity::getUUID).toList();
        edges.sort(String::compareTo);
        if (!ids.isEmpty()) pieces.add(new CaptureEnvelope.Piece(CaptureEnvelope.Kind.RIDING_GRAPH,
            "selected-loaded-actors", encodeGraph(ordered, edges)));
        return new Result(pieces, ids, edges, false); // no independent all-onboard proof
    }
    private static void walk(ServerLevel source, Entity entity, Set<Entity> seen,
                             java.util.Map<UUID, Entity> identityByUuid,
                             List<String> edges) throws IOException {
        if (entity.level() != source || entity.isRemoved())
            throw new IOException("Invalid/external actor graph");
        Entity duplicate = identityByUuid.putIfAbsent(entity.getUUID(), entity);
        if (duplicate != null && duplicate != entity)
            throw new IOException("Distinct selected loaded actors share UUID " + entity.getUUID());
        if (!seen.add(entity) || seen.size() > CaptureEnvelope.MAX_ENTITIES)
            throw new IOException("Cyclic/unbounded actor graph");
        for (Entity child : entity.getPassengers()) {
            if (child.getVehicle() != entity) throw new IOException("Inconsistent mounted actor edge");
            edges.add(entity.getUUID() + "->" + child.getUUID());
            walk(source, child, seen, identityByUuid, edges);
        }
    }
    private static void verifyGraph(Entity actor, CompoundTag nbt) throws IOException {
        if (!nbt.hasUUID("UUID") || !actor.getUUID().equals(nbt.getUUID("UUID")))
            throw new IOException("Actor UUID omitted/changed by persistence codec");
        ListTag nested = nbt.getList("Passengers", Tag.TAG_COMPOUND);
        var children = actor.getPassengers().stream().filter(child -> !(child instanceof ServerPlayer)).toList();
        if (nested.size() != children.size()) throw new IOException("Serializer omitted nested nonplayer passenger");
        for (int i = 0; i < children.size(); i++) {
            var child = nested.getCompound(i);
            if (!child.hasUUID("UUID") || !child.getUUID("UUID").equals(children.get(i).getUUID()))
                throw new IOException("Nested passenger UUID/ordering changed");
            verifyGraph(children.get(i), child);
        }
    }
    private static byte[] encodeGraph(List<Entity> actors, List<String> edges) throws IOException {
        var bytes = new CaptureEnvelope.BoundedBytes(CaptureEnvelope.MAX_PART_BYTES);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x42534147); out.writeInt(1); // BSAG; explicit live pose differs from mounted NBT Pos
            out.writeInt(actors.size());
            for (Entity actor : actors) {
                var motion = actor.getDeltaMovement();
                double[] values = {actor.getX(), actor.getY(), actor.getZ(), motion.x, motion.y, motion.z};
                for (double value : values) if (!Double.isFinite(value))
                    throw new IOException("Nonfinite loaded actor position/velocity");
                if (!Float.isFinite(actor.getYRot()) || !Float.isFinite(actor.getXRot()))
                    throw new IOException("Nonfinite loaded actor rotation");
                UUID id = actor.getUUID();
                out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
                for (double value : values) out.writeDouble(value);
                out.writeFloat(actor.getYRot()); out.writeFloat(actor.getXRot());
            }
            out.writeInt(edges.size()); for (String edge : edges) out.writeUTF(edge);
        }
        return bytes.toByteArray();
    }
    private static byte[] encode(CompoundTag nbt) throws IOException {
        var bytes = new CaptureEnvelope.BoundedBytes(CaptureEnvelope.MAX_PART_BYTES);
        try (var out = new DataOutputStream(bytes)) { NbtIo.write(nbt, out); }
        catch (CaptureEnvelope.BoundedBytes.Overflow ex) { throw new IOException("Actor NBT exceeds part bound", ex); }
        return bytes.toByteArray();
    }
}
