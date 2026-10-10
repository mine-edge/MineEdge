package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.integration.ResolvedMovement;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class PlayerMovementMixin {
    @WrapOperation(method = "attack(Lnet/minecraft/world/entity/Entity;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void mineedge$retainKnockback(Entity target, Vec3 previousVelocity, Operation<Void> original) {
        // Vanilla restores the victim's server velocity after sending knockback to its client.
        // Managed players integrate it on the server, so retain it for the next simulation step.
        original.call(target, target instanceof Player player && MovementRuntime.owns(player)
                ? target.getDeltaMovement() : previousVelocity);
    }

    @Inject(method = "jumpFromGround()V", at = @At("HEAD"), cancellable = true)
    private void mineedge$jump(CallbackInfo callback) {
        if (MovementRuntime.owns((Player) (Object) this)) {
            callback.cancel();
        }
    }

    @Inject(method = "updatePlayerPose()V", at = @At("HEAD"), cancellable = true)
    private void mineedge$pose(CallbackInfo callback) {
        if (MovementRuntime.updatePose((Player) (Object) this)) {
            callback.cancel();
        }
    }

    @Inject(method = "maybeBackOffFromEdge(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/MoverType;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), cancellable = true)
    private void mineedge$edge(Vec3 movement, MoverType type, CallbackInfoReturnable<Vec3> callback) {
        // Sliding off an edge must enter AIR. Vanilla sneak trimming runs outside the
        // sweep and would otherwise disagree with replay whenever crouch is held.
        if (ResolvedMovement.active((Player) (Object) this)) {
            callback.setReturnValue(movement);
        }
    }
}
