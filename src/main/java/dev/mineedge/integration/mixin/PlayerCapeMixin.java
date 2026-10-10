package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mineedge.client.render.ClimbingCapePose;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerModel.class)
abstract class PlayerCapeMixin implements ClimbingCapePose {
    @Unique private float mineedge$capeWeight;

    @Override public void mineedge$capeWeight(float weight) { mineedge$capeWeight = weight; }

    @WrapOperation(method = "renderCloak", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
    private void mineedge$liftCape(ModelPart cape, PoseStack poses, VertexConsumer buffer, int light, int overlay,
                                   Operation<Void> original) {
        if (mineedge$capeWeight <= 0) {
            original.call(cape, poses, buffer, light, overlay);
            return;
        }
        poses.pushPose();
        try {
            poses.translate(0, -2.0 / 16 * mineedge$capeWeight, 0);
            original.call(cape, poses, buffer, light, overlay);
        } finally {
            poses.popPose();
        }
    }
}
