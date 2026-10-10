package dev.mineedge.integration.mixin;

import dev.mineedge.client.camera.MovementCamera;
import dev.mineedge.client.movement.ClientMovement;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Use simulation-time camera interpolation before zoom clipping, then add cosmetic feedback. */
@Mixin(Camera.class)
public abstract class CameraPresentationMixin {
    @Shadow public abstract Vec3 getPosition();
    @Shadow protected abstract void setPosition(Vec3 position);

    @ModifyArgs(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
    private void mineedge$interpolate(Args args, BlockGetter level, Entity entity, boolean detached,
                                    boolean reverse, float partialTick) {
        Vec3 position = ClientMovement.INSTANCE.renderPosition(entity);
        if (position == null) return;
        double vanillaY = entity.yo + (entity.getY() - entity.yo) * partialTick;
        // Keep vanilla eye-height interpolation and immediate mouse look.
        args.set(0, position.x);
        args.set(1, (double) args.get(1) + position.y - vanillaY);
        args.set(2, position.z);
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void mineedge$dip(BlockGetter level, Entity entity, boolean detached, boolean reverse,
                             float partialTick, CallbackInfo ci) {
        Vec3 offset = MovementCamera.offset();
        if (!detached && !offset.equals(Vec3.ZERO)) {
            Vec3 from = getPosition(), to = from.add(offset);
            var hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (hit.getType() == HitResult.Type.MISS) setPosition(to);
            else {
                double distance = Math.max(0, hit.getLocation().distanceTo(from) - 0.05);
                setPosition(from.add(offset.normalize().scale(distance)));
            }
        }
    }
}
