package com.bettercontent.spaceflight.vs.compat.recovery;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountedToDataProvider;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

import java.io.IOException;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded single-game-tick scan of ACTUAL loaded source entities and online
 * players. Membership comes from VS claim ownership, mounted-to-ship or
 * active drag/contact identity, never an AABB-only nearby-actor guess. Offline
 * players and unloaded entities are not discovered/captured. A cross-member
 * riding root, unknown mounted-provider or ambiguous VS identity REFUSES.
 */
public final class LoadedOnboardActorSelection {
    private LoadedOnboardActorSelection() {}
    public static List<Entity> current(ServerLevel source, Set<Long> memberIds) throws IOException {
        if (!source.getServer().isSameThread()) throw new IllegalStateException("Actor scan off server game thread");
        if (memberIds.isEmpty() || memberIds.size() > CaptureEnvelope.MAX_MEMBERS)
            throw new IOException("Unbounded connected member identity set");
        var all = new ArrayList<Entity>();
        var seen = new HashMap<UUID, Entity>();
        for (Entity entity : source.getAllEntities()) { addSource(source, entity, all, seen); }
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers())
            if (player.serverLevel() == source) addSource(source, player, all, seen);
        var selected = new HashMap<UUID, Entity>();
        for (Entity entity : all) {
            Long owner = owner(source, entity);
            if (owner != null && memberIds.contains(owner)) {
                Entity root = entity.getRootVehicle();
                Long rootOwner = owner(source, root);
                if (rootOwner == null || !owner.equals(rootOwner))
                    throw new IOException("Onboard actor has detached/foreign riding root " + entity.getUUID());
                Entity prev = selected.putIfAbsent(root.getUUID(), root);
                if (prev != null && prev != root) throw new IOException("Duplicate riding root UUID");
            }
        }
        var roots = new ArrayList<>(selected.values());
        roots.sort(java.util.Comparator.comparing(Entity::getUUID));
        int members = 0;
        for (Entity root : roots) {
            var branch = new ArrayList<Entity>();
            root.getSelfAndPassengers().forEach(branch::add);
            for (Entity node : branch) {
                if (++members > CaptureEnvelope.MAX_ENTITIES) throw new IOException("Loaded onboard actor count exceeds 128");
                Long owner = owner(source, node);
                if (owner != null && !memberIds.contains(owner))
                    throw new IOException("Riding graph crosses unrelated VS ship " + node.getUUID());
                if (node.level() != source || node.isRemoved() || seen.get(node.getUUID()) != node)
                    throw new IOException("Riding actor disconnected/unloaded during source scan");
            }
        }
        return roots;
    }
    /** Exact VS claim/mount/drag topology beside vanilla riding NBT and live pose. */
    public static CaptureEnvelope.Piece membershipEvidence(ServerLevel source, List<Entity> roots) throws IOException {
        if (!source.getServer().isSameThread()) throw new IllegalStateException("VS actor metadata off game thread");
        var actors = new ArrayList<Entity>();
        for (Entity root : roots) root.getSelfAndPassengers().forEach(actors::add);
        if (actors.size() > CaptureEnvelope.MAX_ENTITIES) throw new IOException("Unbounded onboard topology");
        actors.sort(java.util.Comparator.comparing(Entity::getUUID));
        var sink = new CaptureEnvelope.BoundedBytes(CaptureEnvelope.MAX_PART_BYTES);
        try (var out = new DataOutputStream(sink)) {
            out.writeInt(0x4253414d); out.writeInt(1); out.writeInt(actors.size());
            for (Entity actor : actors) {
                var claim = VSGameUtilsKt.getShipManaging(actor);
                var mounted = VSGameUtilsKt.getShipMountedTo(actor);
                var info = actor instanceof IEntityDraggingInformationProvider p ? p.getDraggingInformation() : null;
                Long drag = info == null ? null : info.getLastShipStoodOn();
                Long serverContact = info == null ? null : info.getLastShipStoodOnServerWriteOnly();
                out.writeUTF(actor.getUUID().toString());
                out.writeUTF(actor.getRootVehicle().getUUID().toString());
                out.writeUTF(actor.getVehicle() == null ? "none" : actor.getVehicle().getUUID().toString());
                out.writeLong(claim == null ? -1 : claim.getId());
                out.writeLong(mounted == null ? -1 : mounted.getId());
                out.writeLong(drag == null ? -1 : drag);
                out.writeLong(serverContact == null ? -1 : serverContact);
                out.writeBoolean(info != null && info.isEntityBeingDraggedByAShip());
                out.writeInt(info == null ? -1 : info.getTicksSinceStoodOnShip());
            }
        } catch (CaptureEnvelope.BoundedBytes.Overflow ex) { throw new IOException("Onboard topology exceeds part bound", ex); }
        return new CaptureEnvelope.Piece(CaptureEnvelope.Kind.ACTOR_MEMBERSHIP,
            "selected-loaded-actors", sink.toByteArray());
    }
    private static void addSource(ServerLevel source, Entity entity, List<Entity> all,
                                  Map<UUID, Entity> seen) throws IOException {
        if (entity.level() != source || entity.isRemoved()) return;
        Entity prior = seen.putIfAbsent(entity.getUUID(), entity);
        if (prior != null && prior != entity) throw new IOException("Duplicate loaded source actor UUID " + entity.getUUID());
        if (prior == null) {
            if (all.size() >= 4096) throw new IOException("Loaded entity scan exceeds confirmation bound");
            all.add(entity);
        }
    }
    private static Long owner(ServerLevel source, Entity actor) throws IOException {
        // The pinned vanilla VS ShipMountingEntity pilot/seat path is supported:
        // its original class has no provider interface and VS falls back to
        // shipyard claim ownership. A transformed exact built-in class may gain
        // that interface, so allow ONLY the exact known VS seat class; unknown
        // addon/provider callbacks (including Eureka) remain unreviewed.
        if (actor.getVehicle() instanceof ShipMountedToDataProvider
            && actor.getVehicle().getClass() != ShipMountingEntity.class)
            throw new IOException("Unknown mounted-provider source unsupported: " + actor.getUUID());
        Long managing = null, mounted = null, dragged = null;
        var ship = VSGameUtilsKt.getShipManaging(actor);
        if (ship != null) managing = ship.getId();
        var mountedShip = VSGameUtilsKt.getShipMountedTo(actor);
        if (mountedShip != null) mounted = mountedShip.getId();
        if (actor instanceof IEntityDraggingInformationProvider provider) {
            var info = provider.getDraggingInformation();
            if (info != null && info.isEntityBeingDraggedByAShip()) dragged = info.getLastShipStoodOn();
        }
        Set<Long> sources = new HashSet<>();
        if (managing != null) sources.add(managing);
        if (mounted != null) sources.add(mounted);
        if (dragged != null) sources.add(dragged);
        if (sources.size() > 1) throw new IOException("Conflicting VS mount/claim/contact owners " + actor.getUUID());
        return sources.isEmpty() ? null : sources.iterator().next();
    }
}
