package dev.mineedge.client.render;

import dev.mineedge.movement.FootTargets;
import dev.mineedge.movement.MovementVector;
import java.lang.ref.WeakReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Per-player, tick-local foot contacts against actual block shapes, including modded shapes. */
final class FootSupport {
    record Contacts(boolean left, boolean right) {}

    private WeakReference<Level> level = new WeakReference<>(null);
    private long tick;
    private Contacts contacts;

    Contacts sample(Player player, FootTargets feet) {
        var currentLevel = player.level();
        long currentTick = currentLevel.getGameTime();
        if (contacts == null || level.get() != currentLevel || tick != currentTick) {
            // Presentation lives in a WeakHashMap; do not retain its player through the level.
            if (level.get() != currentLevel) level = new WeakReference<>(currentLevel);
            tick = currentTick;
            contacts = new Contacts(feet.leftWeight() > 0 && supported(player, feet.left()),
                    feet.rightWeight() > 0 && supported(player, feet.right()));
        }
        return contacts;
    }

    static boolean supported(Player player, MovementVector foot) {
        // Allow the foot's width and the offset used around narrow pipes, but do not
        // borrow support from a ledge or rung above/below the actual foot height.
        var probe = new AABB(foot.x() - 0.2, foot.y() - 0.03, foot.z() - 0.2,
                foot.x() + 0.2, foot.y() + 0.03, foot.z() + 0.2);
        var level = player.level();
        if (!level.hasChunksAt(BlockPos.containing(probe.minX - 1, probe.minY, probe.minZ - 1),
                BlockPos.containing(probe.maxX + 1, probe.maxY, probe.maxZ + 1))) return false;
        var shape = Shapes.create(probe);
        for (var block : level.getBlockCollisions(player, probe)) {
            if (Shapes.joinIsNotEmpty(shape, block, BooleanOp.AND)) return true;
        }
        return false;
    }
}
