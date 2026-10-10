package dev.mineedge.network;

import dev.mineedge.MineEdge;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementSession;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementStateCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;

/** Complete lossless replay state within an epoch; session metadata travels separately. */
public record SnapshotPayload(long epoch, long acknowledged, long serverTick, MovementState state, Pose pose)
        implements CustomPacketPayload {
    public static final Type<SnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MineEdge.MOD_ID, "snapshot"));
    private static final Pose[] POSES = Pose.values();
    public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarLong(payload.epoch);
                buffer.writeVarLong(payload.acknowledged);
                buffer.writeVarLong(payload.serverTick);
                MovementStateCodec.CODEC.encode(buffer, payload.state);
                buffer.writeEnum(payload.pose);
            }, buffer -> new SnapshotPayload(buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readVarLong(), MovementStateCodec.CODEC.decode(buffer), POSES[buffer.readVarInt()]));

    public static SnapshotPayload capture(ServerPlayer player, MovementSession session) {
        MovementState state = session.state;
        if (session.active) {
            var blocks = MinecraftMotionAdapter.blockState(player);
            if (state.grounded() != player.onGround() || Float.compare(state.fallDistance(), player.fallDistance) != 0 || !state.blocks().equals(blocks)) {
                state = new MovementState(state.position(), state.velocity(), state.mode(), state.modeTime(), player.onGround(), player.fallDistance,
                        state.locomotion(), blocks, state.body(), state.slideYaw(), state.traversal(), state.wall(), state.landing(), state.advanced(), state.surfaces());
            }
        }
        return new SnapshotPayload(session.epoch, session.inbox.acknowledged(),
                player.level().getGameTime(), state, player.getPose());
    }

    @Override
    public Type<SnapshotPayload> type() {
        return TYPE;
    }
}
