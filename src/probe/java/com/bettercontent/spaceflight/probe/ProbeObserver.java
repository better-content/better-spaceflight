package com.bettercontent.spaceflight.probe;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ConnectionType;
import net.minecraftforge.network.NetworkHooks;

/** Normal PlayerList registration with a bound synthetic Netty transport, NOT a real client. */
final class ProbeObserver {
    private ProbeObserver() {}

    static ServerPlayer open(ServerLevel level) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel transport = ObserverTransport.open(connection);
        ServerPlayer player = null;
        try {
            if (connection.channel() != transport || !transport.isActive()
                    || transport.pipeline().get("packet_handler") != connection) {
                throw new IllegalStateException("Synthetic observer transport did not bind normally");
            }
            player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "sf-probe"));
            // Real public Forge intention entry initializes its version/handshake metadata.
            // This does NOT complete client negotiation or deliver any packets to a client.
            NetworkHooks.registerServerLoginChannel(connection,
                new ClientIntentionPacket("localhost", 0, ConnectionProtocol.LOGIN));
            if (NetworkHooks.getConnectionType(() -> connection) != ConnectionType.MODDED) {
                throw new IllegalStateException("Forge intention did not initialize the declared version");
            }
            connection.setProtocol(ConnectionProtocol.PLAY);
            transport.runPendingTasks();
            transport.checkException();
            // No reflective registry mutation, fake player-list injection, or Forge/VS patching.
            level.getServer().getPlayerList().placeNewPlayer(connection, player);
            requireActive(player);
            player.setNoGravity(true);
            player.setInvulnerable(true);
            return player;
        } catch (RuntimeException | Error failure) {
            LogUtils.getLogger().error("SPACEFLIGHT_OBSERVER_SETUP_FAILED", failure);
            if (player != null) {
                try { close(player); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            try { transport.finishAndReleaseAll(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static void requireActive(ServerPlayer observer) {
        if (observer.connection == null
                || !(observer.connection.connection.channel() instanceof EmbeddedChannel transport)
                || !transport.isActive() || !transport.isOpen()
                || transport.pipeline().get("packet_handler") != observer.connection.connection
                || observer.connection.connection.getPacketListener() != observer.connection
                || observer.getServer().getPlayerList().getPlayer(observer.getUUID()) != observer) {
            throw new IllegalStateException("Synthetic observer lost its active transport/listener/registry identity");
        }
        transport.runPendingTasks();
        transport.checkException();
    }

    static void close(ServerPlayer observer) {
        ProbeClock.end(observer);
        try {
            if (observer.getServer().getPlayerList().getPlayers().contains(observer)) {
                observer.getServer().getPlayerList().remove(observer);
            }
            if (!observer.isRemoved()) observer.discard();
        } finally {
            if (observer.connection != null
                    && observer.connection.connection.channel() instanceof EmbeddedChannel transport) {
                transport.finishAndReleaseAll();
            }
        }
    }
}
