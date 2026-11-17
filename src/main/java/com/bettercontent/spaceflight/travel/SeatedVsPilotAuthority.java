package com.bettercontent.spaceflight.travel;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Game-thread-only, authoritative SEAT pilot selection; on-deck assertions are
 * intentionally unavailable. This checks a live controller seat/passenger and VS
 * loaded registry identity, not client position packets or the GTPA joint graph.
 * Selection is NOT transfer readiness or a physics-authoritative assembly list.
 */
public final class SeatedVsPilotAuthority implements ServerTravelPolicy.PilotAuthority {
    public record Selection(UUID actor, long mountedShipId, String source, ServerTravelPolicy.Position center) {}
    private final MinecraftServer server;
    public SeatedVsPilotAuthority(MinecraftServer server) { this.server = Objects.requireNonNull(server); }

    public Optional<Selection> select(UUID actor) {
        if (actor == null || !server.isSameThread()) return Optional.empty();
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(actor);
            if (player == null || !player.isAlive() || player.isRemoved()
                || player.serverLevel().getServer() != server
                || !(player.getVehicle() instanceof ShipMountingEntity seat)
                || !seat.isAlive() || seat.isRemoved() || !seat.isController()
                || seat.level() != player.level() || seat.getPassengers().size() != 1
                || seat.getControllingPassenger() != player) return Optional.empty();
            // VS getShipMountedTo(player) reads player.getVehicle() and resolves
            // the mount's loaded managing ship; calling it on the seat is wrong.
            LoadedShip mounted = VSGameUtilsKt.getShipMountedTo(player);
            if (!(mounted instanceof LoadedServerShip loaded)) return Optional.empty();
            var gameWorld = VSGameUtilsKt.getShipObjectWorld(player.serverLevel());
            if (gameWorld.getLoadedShips().getById(loaded.getId()) != loaded) return Optional.empty();
            String vsDimension = VSGameUtilsKt.getDimensionId(player.serverLevel());
            if (!vsDimension.equals(loaded.getChunkClaimDimension())) return Optional.empty();
            // Public policy uses ResourceLocation IDs, NOT VS internal
            // "minecraft:dimension:<namespace>:<path>" identifiers.
            String policySource = player.serverLevel().dimension().location().toString();
            var position = loaded.getTransform().getPositionInWorld();
            return Optional.of(new Selection(actor, loaded.getId(), policySource,
                new ServerTravelPolicy.Position(position.x(), position.y(), position.z())));
        } catch (RuntimeException | LinkageError ex) { return Optional.empty(); }
    }
    @Override public boolean isCurrentPilot(UUID actor, String source, ServerTravelPolicy.Formation formation) {
        if (formation == null || source == null) return false;
        return select(actor).filter(s -> s.source.equals(source)
            && s.mountedShipId == formation.rootShipId()).isPresent();
    }
}
