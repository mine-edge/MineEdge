package dev.mineedge.integration.mixin;

import dev.mineedge.integration.ChunkCollisionRevision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No event covers all direct chunk writes (including block callbacks during a managed move). */
@Mixin(LevelChunk.class)
public abstract class ChunkCollisionMixin implements ChunkCollisionRevision {
    @Unique private long mineedge$collisionRevision;
    @Override public long mineedge$collisionRevision() { return mineedge$collisionRevision; }
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void mineedge$invalidateCollision(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> callback) {
        mineedge$collisionRevision++;
    }
}
