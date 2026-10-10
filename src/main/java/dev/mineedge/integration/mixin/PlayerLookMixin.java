package dev.mineedge.integration.mixin;

import dev.mineedge.client.camera.CameraLookLimits;
import dev.mineedge.client.movement.ClientMovement;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Limit the mouse delta before vanilla applies it to both current and interpolated yaw. */
@Mixin(Entity.class)
abstract class PlayerLookMixin {
    @ModifyVariable(method = "turn(DD)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double mineedge$limitYaw(double mouseYaw) {
        if ((Object) this != Minecraft.getInstance().player) return mouseYaw;
        var state = ClientMovement.INSTANCE.presentationState();
        if (state == null) return mouseYaw;
        float current = ((Entity) (Object) this).getYRot();
        float requested = current + (float) mouseYaw * 0.15F;
        float limited = CameraLookLimits.yaw(state, requested);
        return limited == requested ? mouseYaw : (limited - current) / 0.15F;
    }
}
