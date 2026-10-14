package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mineedge.client.render.ThirdPersonHands;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fade vanilla's prone transform along with its limbs; the low collision pose stays native. */
@Mixin(PlayerRenderer.class)
abstract class PlayerSlideMixin {
    @ModifyExpressionValue(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getSwimAmount(F)F"))
    private float mineedge$slideSwimming(float original, AbstractClientPlayer player, PoseStack poses,
                                        float bob, float bodyYaw, float partialTick, float scale) {
        var shot = ThirdPersonHands.slideShot(player);
        float weight = Math.max(shot == null ? 0 : shot.weight(), ThirdPersonHands.rollProneWeight(player));
        return original * (1 - weight);
    }

    @WrapOperation(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void mineedge$slideProneOffset(PoseStack poses, float x, float y, float z, Operation<Void> original,
                                          AbstractClientPlayer player, PoseStack renderPoses,
                                          float bob, float bodyYaw, float partialTick, float scale) {
        var shot = ThirdPersonHands.slideShot(player);
        float weight = 1 - Math.max(shot == null ? 0 : shot.weight(), ThirdPersonHands.rollProneWeight(player));
        original.call(poses, x * weight, y * weight, z * weight);
    }
}
