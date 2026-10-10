package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.integration.MovementFacing;
import dev.mineedge.integration.ResolvedMovement;
import dev.mineedge.integration.MovementSprint;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class LivingEntityStepMixin {
    @Inject(method = "setSprinting(Z)V", at = @At("RETURN"))
    private void mineedge$sprintChanged(boolean sprinting, CallbackInfo callback) {
        if ((Object) this instanceof Player player) MovementSprint.changed(player);
    }

    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;tickHeadTurn(FF)F"))
    private float mineedge$bodyFacing(LivingEntity entity, float yaw, float animationStep, Operation<Float> original) {
        // Keep native smoothing, head limits and other mods' rotation hooks intact.
        if (entity instanceof Player player && MovementRuntime.owns(player)) {
            yaw = MovementFacing.bodyYaw(player);
        }
        return original.call(entity, yaw, animationStep);
    }

    @ModifyReturnValue(method = "maxUpStep()F", at = @At("RETURN"))
    private float mineedge$step(float original) {
        return ResolvedMovement.active((LivingEntity) (Object) this) ? 0 : original;
    }

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void mineedge$travel(Vec3 input, CallbackInfo callback) {
        // Keep Player.travel's hooks running; only suppress the underlying integration.
        if ((Object) this instanceof Player player && MovementRuntime.travel(player)) {
            callback.cancel();
        }
    }

    @WrapOperation(method = "aiStep()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 mineedge$damping(Vec3 velocity, double factor, Operation<Vec3> original) {
        return original.call(velocity, mineedge$managed() ? 1.0 : factor);
    }

    @ModifyExpressionValue(method = "aiStep()V", at = @At(value = "CONSTANT", args = "doubleValue=0.003"))
    private double mineedge$velocityThreshold(double original) {
        return mineedge$managed() ? 0 : original;
    }

    private boolean mineedge$managed() {
        return (Object) this instanceof Player player && MovementRuntime.owns(player);
    }
}
