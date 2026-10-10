package dev.mineedge.integration.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mineedge.client.movement.ClientMovement;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** The local player model follows the same simulation sample as the first/third-person camera. */
@Mixin(EntityRenderDispatcher.class)
abstract class PlayerPresentationMixin {
    @ModifyArgs(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0))
    private void mineedge$interpolate(Args args, Entity entity, double x, double y, double z,
                                    float yaw, float partialTick, PoseStack poseStack,
                                    MultiBufferSource buffers, int light) {
        var position = ClientMovement.INSTANCE.renderPosition(entity);
        if (position == null) return;
        args.set(0, (double) args.get(0) + position.x - (entity.xOld + (entity.getX() - entity.xOld) * partialTick));
        args.set(1, (double) args.get(1) + position.y - (entity.yOld + (entity.getY() - entity.yOld) * partialTick));
        args.set(2, (double) args.get(2) + position.z - (entity.zOld + (entity.getZ() - entity.zOld) * partialTick));
    }
}
