package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mineedge.client.movement.ClientMovement;
import dev.mineedge.client.config.MineEdgeClientConfig;
import dev.mineedge.client.render.ThirdPersonHands;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Local limbs share the simulation sample used for model and camera translation. */
@Mixin(LivingEntityRenderer.class)
abstract class PlayerAnimationMixin {
    @ModifyExpressionValue(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;rotLerp(FFF)F", ordinal = 0))
    private float mineedge$movementFacing(float original, LivingEntity entity,
                                      float yaw, float renderPartialTick, PoseStack poseStack,
                                      MultiBufferSource buffers, int light) {
        var wallrun = ThirdPersonHands.wallRunShot(entity);
        if (wallrun != null) original = Mth.rotLerp(wallrun.weight(), original, wallrun.yaw());
        var shot = ThirdPersonHands.slideShot(entity);
        // Vanilla derives head-relative yaw from this same value, so looking around
        // remains correct while the body follows the actual movement direction.
        return shot == null ? original : Mth.rotLerp(shot.weight(), original, shot.yaw());
    }

    @ModifyExpressionValue(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/WalkAnimationState;speed(F)F"))
    private float mineedge$walkSpeed(float original, LivingEntity entity,
                                    float yaw, float renderPartialTick, PoseStack poseStack,
                                    MultiBufferSource buffers, int light) {
        if (!MineEdgeClientConfig.THIRD_PERSON_ANIMATIONS.get()) return original;
        var shot = ClientMovement.INSTANCE.walkingPresentation(entity);
        float speed = shot == null ? original : shot.speed();
        var slide = ThirdPersonHands.slideShot(entity);
        return slide == null ? speed : speed * (1 - slide.weight());
    }

    @ModifyExpressionValue(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/WalkAnimationState;position(F)F"))
    private float mineedge$walkPosition(float original, LivingEntity entity,
                                       float yaw, float renderPartialTick, PoseStack poseStack,
                                       MultiBufferSource buffers, int light) {
        if (!MineEdgeClientConfig.THIRD_PERSON_ANIMATIONS.get()) return original;
        var shot = ClientMovement.INSTANCE.walkingPresentation(entity);
        return shot == null ? original : shot.position();
    }
}
