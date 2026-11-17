package com.bettercontent.spaceflight.probe;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

/** Operator-only fresh-world diagnostic commands in the NONSHIPPING probe mod. */
public final class ProbeCommands {
    private ProbeCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("spaceflight_probe").requires(source -> source.hasPermission(2))
            .then(Commands.literal("begin").executes(context -> run(context.getSource(), "begin")))
            .then(Commands.literal("hop").executes(context -> run(context.getSource(), "hop")))
            .then(Commands.literal("status").executes(context -> run(context.getSource(), "status")))
            .then(Commands.literal("remount").executes(context -> run(context.getSource(), "remount"))));
    }

    private static int run(CommandSourceStack source, String action) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var server = source.getServer();
        ProbeRig.Record record = ProbeRig.Record.get(server);
        try {
            if (action.equals("begin")) {
                if (record.rig != null) throw new IllegalStateException("Probe already exists; use a NEW world for another attempt");
                if (player.isPassenger()) throw new IllegalStateException("Dismount before beginning the fixture");
                if (!source.getLevel().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                    throw new IllegalStateException("Begin the fixture in the Overworld");
                }
                record.rig = ProbeRig.create(source.getLevel(), player.blockPosition().offset(8, 8, 8));
                record.setDirty();
                mount(player, record.rig);
            } else {
                if (record.rig == null) throw new IllegalStateException("No probe in this world; run begin first");
                ProbeRig rig = record.rig;
                if (action.equals("hop")) {
                    if (!(player.getVehicle() instanceof ShipMountingEntity seat)
                        || !seat.blockPosition().equals(rig.seat)) {
                        throw new IllegalStateException("Pilot must be seated in this fixture; use remount");
                    }
                    ServerLevel destination = server.getLevel(rig.dimension().equals(ProbeRig.SPACE) ? rig.home : ProbeRig.SPACE);
                    if (destination == null) throw new IllegalStateException("Missing probe destination datapack");
                    Vector3d target = rig.dimension().equals(ProbeRig.SPACE) ? new Vector3d(rig.homePosition) : new Vector3d(0, 160, 0);
                    // The low-level probe is deliberately not crash-transactional. On failure retain the whole world for diagnosis.
                    try { rig.hop(destination, target); } finally { record.setDirty(); }
                    player.stopRiding(); seat.discard();
                    mount(player, rig);
                } else if (action.equals("remount")) {
                    if (player.isPassenger()) throw new IllegalStateException("Dismount first; remount never deletes an existing seat");
                    mount(player, rig);
                }
                rig.assertState(server);
            }
            ProbeRig rig = record.rig;
            String message = "PROBE STATE CHECK ONLY: ship=" + rig.shipId + " dimension=" + rig.dimension().location()
                + " hops=" + rig.hops + " blocks=" + rig.states.size() + " blockEntities=" + rig.entities.size()
                + " marker=" + rig.marker + ". Cargo/fuel NBT match; client rendering, physics and restart acceptance NOT implied.";
            source.sendSuccess(() -> Component.literal(message), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("PROBE FAILED: " + exception.getMessage() + ". Preserve this world and logs; do not rebuild or retry in place."));
            com.mojang.logging.LogUtils.getLogger().error("Spaceflight probe failed: action={}", action, exception);
            return 0;
        }
    }

    private static void mount(ServerPlayer player, ProbeRig rig) {
        ServerLevel destination = rig.level(player.getServer());
        var ship = rig.ship(player.getServer());
        Vector3d world = ship.getTransform().getShipToWorld().transformPosition(
            new Vector3d(rig.seat.getX() + 0.5, rig.seat.getY(), rig.seat.getZ() + 0.5));
        player.teleportTo(destination, world.x, world.y, world.z, player.getYRot(), player.getXRot());
        ShipMountingEntity seat = new ShipMountingEntity(ValkyrienSkiesMod.INSTANCE.getSHIP_MOUNTING_ENTITY_TYPE(), destination);
        seat.setPos(rig.seat.getX() + 0.5, rig.seat.getY(), rig.seat.getZ() + 0.5);
        seat.setController(false); // Native driving packet must not become a second force controller.
        if (!destination.addFreshEntity(seat) || !player.startRiding(seat, true)) {
            seat.discard(); throw new IllegalStateException("Failed to create/remount destination seat");
        }
    }
}
