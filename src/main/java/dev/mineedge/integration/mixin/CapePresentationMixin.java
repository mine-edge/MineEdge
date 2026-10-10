package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mineedge.client.movement.ClientMovement;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Replace local cape animation samples while retaining the layer's native rendering and rotations. */
@Mixin(CapeLayer.class)
abstract class CapePresentationMixin {
    @ModifyExpressionValue(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(DDD)D", ordinal = 0))
    private double mineedge$capeX(double original, PoseStack poses, MultiBufferSource buffers, int light,
                                  AbstractClientPlayer player, float swing, float speed, float partialTick,
                                  float age, float headYaw, float headPitch) {
        var offset = ClientMovement.INSTANCE.capePresentation(player);
        // Vanilla subtracts its player-position lerp next. Cancel that endpoint so
        // only the relative drag sampled with the model remains, on every axis.
        return offset == null ? original : Mth.lerp((double) partialTick, player.xo, player.getX()) + offset.x();
    }

    @ModifyExpressionValue(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(DDD)D", ordinal = 2))
    private double mineedge$capeY(double original, PoseStack poses, MultiBufferSource buffers, int light,
                                  AbstractClientPlayer player, float swing, float speed, float partialTick,
                                  float age, float headYaw, float headPitch) {
        var offset = ClientMovement.INSTANCE.capePresentation(player);
        return offset == null ? original : Mth.lerp((double) partialTick, player.yo, player.getY()) + offset.y();
    }

    @ModifyExpressionValue(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(DDD)D", ordinal = 4))
    private double mineedge$capeZ(double original, PoseStack poses, MultiBufferSource buffers, int light,
                                  AbstractClientPlayer player, float swing, float speed, float partialTick,
                                  float age, float headYaw, float headPitch) {
        var offset = ClientMovement.INSTANCE.capePresentation(player);
        return offset == null ? original : Mth.lerp((double) partialTick, player.zo, player.getZ()) + offset.z();
    }

    @ModifyExpressionValue(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal = 0))
    private float mineedge$capeBob(float original, PoseStack poses, MultiBufferSource buffers, int light,
                                   AbstractClientPlayer player, float swing, float speed, float partialTick,
                                   float age, float headYaw, float headPitch) {
        var shot = ClientMovement.INSTANCE.handPresentation(player);
        return shot == null ? original : shot.bob();
    }

    @ModifyExpressionValue(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal = 1))
    private float mineedge$capeWalk(float original, PoseStack poses, MultiBufferSource buffers, int light,
                                    AbstractClientPlayer player, float swing, float speed, float partialTick,
                                    float age, float headYaw, float headPitch) {
        var shot = ClientMovement.INSTANCE.handPresentation(player);
        return shot == null ? original : shot.walkDistance();
    }
}
