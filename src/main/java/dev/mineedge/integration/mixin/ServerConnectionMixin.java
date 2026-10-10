package dev.mineedge.integration.mixin;

import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.movement.MovementVector;
import java.util.Set;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerConnectionMixin {
    @Shadow public ServerPlayer player;
    @Shadow private int receivedMovePacketCount;
    @Shadow private boolean clientIsFloating;

    // After PacketUtils thread dispatch AND invalid-value checks, before any position application.
    @Inject(method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;serverLevel()Lnet/minecraft/server/level/ServerLevel;", ordinal = 1),
            cancellable = true)
    private void mineedge$consumePosition(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        if (MovementRuntime.owns(player)) {
            receivedMovePacketCount++;
            // The server's own bounded gravity/collision simulation now owns airborne validity.
            clientIsFloating = false;
            callback.cancel();
        }
    }

    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void mineedge$teleport(double x, double y, double z, float yaw, float pitch,
                                  Set<RelativeMovement> relative, CallbackInfo callback) {
        MovementRuntime.teleport(player, new MovementVector(
                relative.contains(RelativeMovement.X) ? player.getX() + x : x,
                relative.contains(RelativeMovement.Y) ? player.getY() + y : y,
                relative.contains(RelativeMovement.Z) ? player.getZ() + z : z));
    }
}
