package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mineedge.integration.ResolvedMovement;
import dev.mineedge.integration.MinecraftBlockMotion;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    @WrapOperation(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;updateEntityAfterFallOn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;)V"))
    private void mineedge$bounce(Block block, net.minecraft.world.level.BlockGetter level, Entity entity, Operation<Void> original) {
        Vec3 before = entity.getDeltaMovement();
        original.call(block, level, entity);
        if (ResolvedMovement.active(entity) && (block == Blocks.SLIME_BLOCK || block instanceof BedBlock)) {
            // Apply only the difference from vanilla's bounce, retaining callback edits.
            double nativeY = entity.isSuppressingBounce() ? 0
                    : before.y < 0 ? -before.y * (block instanceof BedBlock ? 0.66F : 1) : before.y;
            double managedY = MinecraftBlockMotion.bounce(block, before, entity.isSuppressingBounce()).y;
            entity.setDeltaMovement(entity.getDeltaMovement().add(0, managedY - nativeY, 0));
        }
    }

    @WrapOperation(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;stepOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;)V"))
    private void mineedge$slimeStep(Block block, Level level, BlockPos pos, BlockState state, Entity entity, Operation<Void> original) {
        Vec3 before = entity.getDeltaMovement();
        original.call(block, level, pos, state, entity);
        if (ResolvedMovement.active(entity) && block == Blocks.SLIME_BLOCK) {
            double factor = !entity.isSteppingCarefully() && Math.abs(before.y) < 0.1
                    ? 0.4 + Math.abs(before.y) * 0.2 : 1;
            Vec3 managed = MinecraftBlockMotion.slimeStep(before, entity.isSteppingCarefully(), ResolvedMovement.seconds(entity));
            entity.setDeltaMovement(entity.getDeltaMovement().add(managed.subtract(before.multiply(factor, 1, factor))));
        }
    }

    @ModifyExpressionValue(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBlockSpeedFactor()F"))
    private float mineedge$surfaceSlowdown(float factor) {
        Entity entity = (Entity) (Object) this;
        return ResolvedMovement.active(entity) ? (float) MinecraftBlockMotion.slowdown(factor, ResolvedMovement.seconds(entity)) : factor;
    }

    // ServerPlayer's normal override is empty; managed damage must run at this original
    // boundary, before bounce/inside-block callbacks mutate velocity and fall distance.
    @WrapOperation(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"))
    private void mineedge$fallDamage(Entity entity, double dy, boolean grounded, BlockState state, BlockPos pos, Operation<Void> original) {
        var result = ResolvedMovement.takeFallResult(entity);
        // ServerPlayer's native method is empty, but its modded hooks must still run.
        original.call(entity, dy, grounded, state, pos);
        if (entity instanceof ServerPlayer player && result != null) {
            if (!result.blockedByOverlap()) {
                var displacement = result.displacement();
                player.doCheckFallDamage(displacement.x(), displacement.y(), displacement.z(), result.grounded());
            }
        }
    }

    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), cancellable = true)
    private void mineedge$collide(Vec3 requested, CallbackInfoReturnable<Vec3> callback) {
        Vec3 resolved = ResolvedMovement.collide((Entity) (Object) this, requested);
        if (resolved != null) {
            callback.setReturnValue(resolved);
        }
    }

    // Entity.move ignores resolved displacements whose squared length <= 1e-7.
    // At 100 Hz that would discard low-speed movement in live play but not replay.
    @ModifyConstant(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            constant = @Constant(doubleValue = 1.0E-7, ordinal = 1))
    private double mineedge$substepThreshold(double original) {
        return ResolvedMovement.active((Entity) (Object) this) ? 0 : original;
    }
}
