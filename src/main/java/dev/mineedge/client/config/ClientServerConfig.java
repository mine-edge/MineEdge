package dev.mineedge.client.config;

import dev.mineedge.config.MineEdgeServerConfig;
import dev.mineedge.config.ServerMovementSettings;
import dev.mineedge.network.ServerConfigPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Client-thread cache of canonical baseline rules. Menu drafts do not mutate this cache. */
public final class ClientServerConfig {
    private static ServerMovementSettings settings;

    public static ServerMovementSettings settings() {
        if (Minecraft.getInstance().player == null) {
            return ServerMovementSettings.DEFAULT;
        }
        return settings != null ? settings : MineEdgeServerConfig.SPEC.isLoaded()
                ? MineEdgeServerConfig.settings() : ServerMovementSettings.DEFAULT;
    }

    public static void receive(ServerConfigPayload payload) {
        if (!payload.settings().isValid()) {
            throw new IllegalArgumentException("Invalid authoritative server settings");
        }
        settings = payload.settings();
        // Integrated servers share their config object with the client: only the server
        // thread writes it there. Remote clients update memory without saving a local file.
        if (!Minecraft.getInstance().hasSingleplayerServer() && MineEdgeServerConfig.SPEC.isLoaded()) {
            MineEdgeServerConfig.setValues(settings);
        }
    }

    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        settings = null;
    }

    private ClientServerConfig() {}
}
