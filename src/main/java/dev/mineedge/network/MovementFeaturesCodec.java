package dev.mineedge.network;

import dev.mineedge.movement.MovementFeatures;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Shared feature order for session tuning and server settings. */
final class MovementFeaturesCodec {
    static final StreamCodec<FriendlyByteBuf, MovementFeatures> CODEC = StreamCodec.of(
            (buffer, features) -> {
                buffer.writeBoolean(features.sliding());
                buffer.writeBoolean(features.wallRunning());
                buffer.writeBoolean(features.vaulting());
                buffer.writeBoolean(features.mantling());
                buffer.writeBoolean(features.pipeClimbing());
                buffer.writeBoolean(features.doorPush());
                buffer.writeBoolean(features.hardLanding());
            }, buffer -> new MovementFeatures(buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean()));

    private MovementFeaturesCodec() {}
}
