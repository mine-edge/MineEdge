package dev.mineedge.network;

import dev.mineedge.MineEdge;
import dev.mineedge.movement.MovementTuning;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Sent on lifecycle/tuning changes, or as a small heartbeat while vanilla owns movement. */
public record SessionPayload(ResourceLocation dimension, long epoch, long serverTick,
                             boolean enabled, boolean active, MovementTuning tuning, long tuningRevision) implements CustomPacketPayload {
    public SessionPayload(ResourceLocation dimension, long epoch, long serverTick,
                          boolean enabled, boolean active, MovementTuning tuning) {
        this(dimension, epoch, serverTick, enabled, active, tuning, 0);
    }

    public static final Type<SessionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MineEdge.MOD_ID, "session"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SessionPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeVarLong(payload.epoch);
                buffer.writeVarLong(payload.serverTick);
                buffer.writeVarLong(payload.tuningRevision);
                buffer.writeBoolean(payload.enabled);
                buffer.writeBoolean(payload.active);
                writeTuning(buffer, payload.tuning);
            }, buffer -> {
                var dimension = buffer.readResourceLocation();
                long epoch = buffer.readVarLong(), tick = buffer.readVarLong(), revision = buffer.readVarLong();
                return new SessionPayload(dimension, epoch, tick, buffer.readBoolean(), buffer.readBoolean(), readTuning(buffer), revision);
            });

    private static void writeTuning(RegistryFriendlyByteBuf buffer, MovementTuning tuning) {
        boolean defaults = tuning.equals(MovementTuning.DEFAULT);
        buffer.writeBoolean(defaults);
        if (defaults) return;
        buffer.writeDouble(tuning.walkSpeed());
        buffer.writeDouble(tuning.jumpSpeed());
        buffer.writeDouble(tuning.gravity());
        buffer.writeDouble(tuning.groundAcceleration());
        buffer.writeDouble(tuning.airAcceleration());
        buffer.writeVarInt(tuning.staleInputTicks());
        buffer.writeDouble(tuning.crouchSpeedModifier());
        buffer.writeDouble(tuning.rollDamageReduction());
        buffer.writeDouble(tuning.runSpeedMultiplier());
        buffer.writeDouble(tuning.fallDamageMultiplier());
        buffer.writeDouble(tuning.minimumFallHeight());
        MovementFeaturesCodec.CODEC.encode(buffer, tuning.features());
    }

    private static MovementTuning readTuning(RegistryFriendlyByteBuf buffer) {
        return buffer.readBoolean() ? MovementTuning.DEFAULT : new MovementTuning(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readVarInt(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), MovementFeaturesCodec.CODEC.decode(buffer));
    }

    @Override
    public Type<SessionPayload> type() { return TYPE; }
}
