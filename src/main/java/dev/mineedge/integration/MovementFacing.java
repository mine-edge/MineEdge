package dev.mineedge.integration;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/** Body rotation target for movement integrated outside the native entity tick. */
public final class MovementFacing {
    public static float bodyYaw(Player player) {
        if (player.attackAnim > 0) return player.getYRot();
        var motion = player.getDeltaMovement();
        // Motion is in blocks per Minecraft tick. Retain vanilla's idle threshold,
        // but do not infer direction from xo/zo: baseTick already reset those before
        // the cancelled travel call, so they miss the completed simulation motion.
        if (motion.horizontalDistanceSqr() <= 0.0025000002F) return player.yBodyRot;
        float direction = (float) Math.toDegrees(Math.atan2(-motion.x, motion.z));
        float relative = Mth.wrapDegrees(direction - player.getYRot());
        // Backpedalling faces the look direction, rather than turning the torso away.
        if (Math.abs(relative) > 95) direction += 180;
        return player.getYRot() + Mth.wrapDegrees(direction - player.getYRot());
    }

    private MovementFacing() {}
}
