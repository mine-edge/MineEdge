package dev.mineedge.network;

import dev.mineedge.MineEdge;
import dev.mineedge.config.ServerMovementSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-bound canonical settings, or a server-bound update request. Receipt never advances physics. */
public record ServerConfigPayload(ServerMovementSettings settings) implements CustomPacketPayload {
    public static final Type<ServerConfigPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineEdge.MOD_ID, "server_config"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                ServerMovementSettings settings = payload.settings;
                buffer.writeBoolean(settings.enabled());
                buffer.writeBoolean(settings.survival());
                buffer.writeBoolean(settings.adventure());
                buffer.writeBoolean(settings.creative());
                buffer.writeDouble(settings.walkSpeed());
                buffer.writeDouble(settings.jumpSpeed());
                buffer.writeDouble(settings.gravity());
                buffer.writeDouble(settings.rollDamageReduction());
                buffer.writeDouble(settings.runSpeedMultiplier());
                buffer.writeDouble(settings.fallDamageMultiplier());
                buffer.writeDouble(settings.minimumFallHeight());
                MovementFeaturesCodec.CODEC.encode(buffer, settings.features());
            }, buffer -> new ServerConfigPayload(new ServerMovementSettings(buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), MovementFeaturesCodec.CODEC.decode(buffer))));

    @Override
    public Type<ServerConfigPayload> type() {
        return TYPE;
    }
}
