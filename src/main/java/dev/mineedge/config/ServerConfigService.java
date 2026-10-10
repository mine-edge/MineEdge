package dev.mineedge.config;

import dev.mineedge.MineEdge;
import dev.mineedge.network.ServerConfigPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerConfigService {
    private static ServerMovementSettings lastBroadcast;

    public static void receive(ServerPlayer player, ServerConfigPayload payload) {
        boolean authorized = player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile());
        ServerSettingsUpdate.Result result;
        try {
            result = ServerSettingsUpdate.apply(authorized, payload.settings(), MineEdgeServerConfig::saveSettings);
        } catch (RuntimeException exception) {
            MineEdge.LOGGER.error("Could not save MineEdge server settings", exception);
            player.sendSystemMessage(Component.translatable("mineedge.config.server.save_failed"));
            send(player);
            return;
        }
        switch (result) {
            case APPLIED -> {
                broadcast();
                player.sendSystemMessage(Component.translatable("mineedge.config.server.saved"));
            }
            case DENIED -> {
                player.sendSystemMessage(Component.translatable("mineedge.config.server.denied"));
                send(player);
            }
            case INVALID -> {
                player.sendSystemMessage(Component.translatable("mineedge.config.server.invalid"));
                send(player);
            }
        }
    }

    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player);
        }
    }

    public static void tick(ServerTickEvent.Post event) {
        // Include external TOML edits; NeoForge's built-in file sync only runs during login.
        if (MineEdgeServerConfig.SPEC.isLoaded() && !MineEdgeServerConfig.settings().equals(lastBroadcast)) {
            broadcast();
        }
    }

    public static void stopped(ServerStoppedEvent event) {
        lastBroadcast = null;
    }

    private static void send(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new ServerConfigPayload(MineEdgeServerConfig.settings()));
    }

    private static void broadcast() {
        lastBroadcast = MineEdgeServerConfig.settings();
        PacketDistributor.sendToAllPlayers(new ServerConfigPayload(lastBroadcast));
    }

    private ServerConfigService() {}
}
