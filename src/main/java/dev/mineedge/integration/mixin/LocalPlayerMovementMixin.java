package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.integration.MovementSprint;
import dev.mineedge.client.movement.ClientMovement;
import dev.mineedge.movement.MovementBody;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
abstract class LocalPlayerMovementMixin {
    @Shadow private int autoJumpTime;

    @WrapOperation(method = "aiStep()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/Input;tick(ZF)V"))
    private void mineedge$input(Input input, boolean slow, float modifier, Operation<Void> original) {
        // MineEdge scales crouch speed in its controller. Run the native input pipeline
        // once, without that duplicate scaling, before NeoForge's input-update event.
        original.call(input, MovementRuntime.owns((LocalPlayer) (Object) this) ? false : slow, modifier);
    }

    @Inject(method = "aiStep()V", at = @At("HEAD"))
    private void mineedge$nativeSprint(CallbackInfo callback) {
        ClientMovement.INSTANCE.beginNativeSprint((LocalPlayer) (Object) this);
    }

    @Inject(method = "aiStep()V", at = @At("RETURN"))
    private void mineedge$sprintDecision(CallbackInfo callback) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        ClientMovement.INSTANCE.sprintChanged(player, player.isSprinting());
    }

    @ModifyReturnValue(method = "canStartSprinting()Z", at = @At("RETURN"))
    private boolean mineedge$sprintEligibility(boolean original) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        // Native input crouch scaling is bypassed above. Restore its sprint gate
        // without replacing vanilla's predicate or another mod's veto.
        return original && (!MovementRuntime.owns(player) || !player.input.shiftKeyDown
                && ClientMovement.INSTANCE.body() == MovementBody.STANDING && MovementSprint.allowed(player));
    }

    @Inject(method = "updateAutoJump(FF)V", at = @At("HEAD"), cancellable = true)
    private void mineedge$autoJump(float x, float z, CallbackInfo callback) {
        if (MovementRuntime.owns((LocalPlayer) (Object) this)) {
            autoJumpTime = 0;
            callback.cancel();
        }
    }

    @Inject(method = "isCrouching()Z", at = @At("HEAD"), cancellable = true)
    private void mineedge$crouching(CallbackInfoReturnable<Boolean> callback) {
        if (MovementRuntime.owns((LocalPlayer) (Object) this)) {
            callback.setReturnValue(ClientMovement.INSTANCE.body() == MovementBody.CROUCHING);
        }
    }

}
