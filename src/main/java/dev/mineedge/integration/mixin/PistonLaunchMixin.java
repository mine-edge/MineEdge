package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mineedge.integration.MovementRuntime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PistonMovingBlockEntity.class)
abstract class PistonLaunchMixin {
    // Vanilla delegates player launches to client travel. Managed movement is server-owned,
    // so its players need the same native axis velocity assignment as other entities.
    @WrapOperation(method = "moveCollidedEntities",
            at = @At(value = "CONSTANT", args = "classValue=net.minecraft.server.level.ServerPlayer"))
    private static boolean mineedge$clientOwnedLaunch(Object entity, Operation<Boolean> original) {
        return original.call(entity) && !(entity instanceof Player player && MovementRuntime.owns(player));
    }
}
