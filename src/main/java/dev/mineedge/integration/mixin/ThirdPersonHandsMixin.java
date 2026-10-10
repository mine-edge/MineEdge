package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.mineedge.client.config.MineEdgeClientConfig;
import dev.mineedge.client.render.ThirdPersonHands;
import dev.mineedge.client.render.ThirdPersonLegs;
import dev.mineedge.client.render.ThirdPersonSlide;
import dev.mineedge.client.render.ThirdPersonWallRun;
import dev.mineedge.client.render.ThirdPersonRoll;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply after vanilla/mod animation setup, using the actual renderer transform (including scale). */
@Mixin(LivingEntityRenderer.class)
abstract class ThirdPersonHandsMixin<T extends LivingEntity> {
    @Shadow protected EntityModel<T> model;
    @Unique private Matrix4f mineedge$origin;
    @Unique private Matrix4f mineedge$bodyBack;
    @Unique private float mineedge$swimAmount;
    @Unique private boolean mineedge$slideModel;

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"))
    private void mineedge$begin(T entity, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (model instanceof PlayerModel<?> playerModel) {
            ThirdPersonHands.clear(playerModel);
            ThirdPersonLegs.clear(playerModel);
            ThirdPersonSlide.clear(playerModel);
            mineedge$slideModel = false;
            mineedge$bodyBack = null;
            mineedge$origin = new Matrix4f(poses.last().pose());
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"))
    private void mineedge$slideBase(T entity, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        var shot = ThirdPersonHands.slideShot(entity);
        float weight = Math.max(shot == null ? 0 : shot.weight(), ThirdPersonHands.rollProneWeight(entity));
        if (weight > 0 && model instanceof PlayerModel<?> playerModel) {
            mineedge$swimAmount = playerModel.swimAmount;
            mineedge$slideModel = true;
            playerModel.swimAmount *= 1 - weight;
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V", shift = At.Shift.AFTER))
    private void mineedge$solve(T entity, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (!MineEdgeClientConfig.THIRD_PERSON_ANIMATIONS.get()) return;
        if (entity instanceof AbstractClientPlayer player && model instanceof PlayerModel<?> playerModel) {
            var roll = ThirdPersonHands.rollShot(player);
            if (roll != null) {
                mineedge$bodyBack = ThirdPersonRoll.apply(player, playerModel, roll);
                return;
            }
            mineedge$bodyBack = ThirdPersonWallRun.apply(player, playerModel, ThirdPersonHands.wallRunShot(player));
            var slideBack = ThirdPersonSlide.apply(player, playerModel, ThirdPersonHands.slideShot(player));
            if (slideBack != null) mineedge$bodyBack = mineedge$bodyBack == null ? slideBack : slideBack.mul(mineedge$bodyBack);
            var inverse = new Matrix4f(poses.last().pose()).invert().mul(mineedge$origin);
            @SuppressWarnings("unchecked")
            var renderer = (LivingEntityRenderer<T, ?>) (Object) this;
            var offset = renderer.getRenderOffset(entity, partialTick);
            ThirdPersonHands.apply(player, playerModel, partialTick, inverse, offset);
        }
    }

    @WrapOperation(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
    private void mineedge$bodyBackLayer(RenderLayer<?, ?> layer, PoseStack poses, MultiBufferSource buffers, int light,
                                        Entity entity, float swing, float speed, float partialTick,
                                        float age, float headYaw, float headPitch, Operation<Void> original) {
        if (mineedge$bodyBack == null || !(layer instanceof CapeLayer || layer instanceof ElytraLayer<?, ?>)) {
            original.call(layer, poses, buffers, light, entity, swing, speed, partialTick, age, headYaw, headPitch);
            return;
        }
        poses.pushPose();
        try {
            poses.mulPose(mineedge$bodyBack);
            original.call(layer, poses, buffers, light, entity, swing, speed, partialTick, age, headYaw, headPitch);
        } finally {
            poses.popPose();
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("RETURN"))
    private void mineedge$end(T entity, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (model instanceof PlayerModel<?> playerModel) {
            ThirdPersonHands.clear(playerModel);
            ThirdPersonLegs.clear(playerModel);
            ThirdPersonSlide.clear(playerModel);
            if (mineedge$slideModel) playerModel.swimAmount = mineedge$swimAmount;
            mineedge$slideModel = false;
            mineedge$bodyBack = null;
        }
    }
}
