package dev.mineedge.network;

import dev.mineedge.MineEdge;
import dev.mineedge.movement.HandTargets;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.WallRunPose;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Compact observer poses; prediction state stays private to its owner. */
public record HandTargetsPayload(int entityId, UUID playerId, HandTargets targets, boolean ledgeHanging,
                                 boolean sliding, float slideYaw, WallRunPose wallrun) implements CustomPacketPayload {
    public HandTargetsPayload(int entityId, UUID playerId, HandTargets targets) {
        this(entityId, playerId, targets, false);
    }
    public HandTargetsPayload(int entityId, UUID playerId, HandTargets targets, boolean ledgeHanging) {
        this(entityId, playerId, targets, ledgeHanging, false, 0);
    }
    public HandTargetsPayload(int entityId, UUID playerId, HandTargets targets, boolean ledgeHanging, boolean sliding, float slideYaw) {
        this(entityId, playerId, targets, ledgeHanging, sliding, slideYaw, WallRunPose.EMPTY);
    }
    public boolean active() { return targets.active() || sliding || wallrun.active(); }
    public static final Type<HandTargetsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MineEdge.MOD_ID, "hand_targets"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HandTargetsPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.entityId);
                buffer.writeUUID(payload.playerId);
                buffer.writeByte((payload.targets.active() ? 1 : 0) | (payload.ledgeHanging ? 2 : 0)
                        | (payload.sliding ? 4 : 0) | (payload.wallrun.active() ? 8 : 0));
                if (payload.targets.active()) {
                    vector(buffer, payload.targets.left());
                    vector(buffer, payload.targets.right());
                    buffer.writeFloat(payload.targets.leftWeight());
                    buffer.writeFloat(payload.targets.rightWeight());
                }
                if (payload.sliding) buffer.writeFloat(payload.slideYaw);
                if (payload.wallrun.active()) {
                    buffer.writeFloat(payload.wallrun.yaw());
                    buffer.writeFloat(payload.wallrun.side());
                    buffer.writeFloat(payload.wallrun.speed());
                }
            }, buffer -> {
                int id = buffer.readVarInt();
                UUID uuid = buffer.readUUID();
                int flags = buffer.readUnsignedByte();
                var targets = (flags & 1) != 0 ? new HandTargets(vector(buffer), vector(buffer),
                        buffer.readFloat(), buffer.readFloat()) : HandTargets.EMPTY;
                boolean sliding = (flags & 4) != 0;
                float slideYaw = sliding ? buffer.readFloat() : 0;
                var wallrun = (flags & 8) != 0 ? new WallRunPose(buffer.readFloat(), buffer.readFloat(), buffer.readFloat()) : WallRunPose.EMPTY;
                return new HandTargetsPayload(id, uuid, targets, (flags & 2) != 0, sliding, slideYaw, wallrun);
            });

    private static void vector(RegistryFriendlyByteBuf buffer, MovementVector v) {
        buffer.writeFloat((float) v.x()); buffer.writeFloat((float) v.y()); buffer.writeFloat((float) v.z());
    }
    private static MovementVector vector(RegistryFriendlyByteBuf buffer) {
        return new MovementVector(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
    }
    @Override public Type<HandTargetsPayload> type() { return TYPE; }
}
