package dev.mineedge.integration.mixin;

import dev.mineedge.integration.MovementRuntime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientConnectionMixin {
    @Inject(method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V", at = @At("RETURN"))
    private void mineedge$teleport(ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        MovementRuntime.clientTeleport();
    }
}
