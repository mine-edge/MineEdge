package dev.mineedge.integration;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.integration.mixin.EntityMotionAccessor;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.EventHooks;

/** Native poses synchronize collision bounds, eye height and observer presentation through entity data. */
public final class MovementPoses {
    public static Pose pose(MovementBody body) {
        return switch (body) {
            case STANDING -> Pose.STANDING;
            case CROUCHING -> Pose.CROUCHING;
            case LOW -> Pose.SWIMMING;
        };
    }

    public static MovementBody body(Pose pose) {
        return switch (pose) {
            case CROUCHING -> MovementBody.CROUCHING;
            case SWIMMING -> MovementBody.LOW;
            default -> MovementBody.STANDING;
        };
    }

    public static EntityDimensions dimensions(Player player, MovementBody body) {
        Pose pose = pose(body);
        EntityDimensions current = ((EntityMotionAccessor) player).mineedge$dimensions();
        // getDimensions alone omits EntityEvent.Size adjustments. Reuse the installed
        // size for the current pose; query alternate poses exactly as refreshDimensions does.
        return player.getPose() == pose ? current
                : EventHooks.getEntitySizeForge(player, pose, current, player.getDimensions(pose)).getNewSize();
    }

    public static void apply(Player player, MovementBody body) {
        player.setPose(pose(body));
    }

    private MovementPoses() {}
}
