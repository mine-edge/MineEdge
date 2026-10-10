package dev.mineedge.integration.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityMotionAccessor {
    @Accessor("dimensions")
    EntityDimensions mineedge$dimensions();

    @Accessor("stuckSpeedMultiplier")
    Vec3 mineedge$stuckMultiplier();

    @Accessor("stuckSpeedMultiplier")
    void mineedge$stuckMultiplier(Vec3 multiplier);
}
