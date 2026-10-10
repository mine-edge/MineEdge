package dev.mineedge.integration;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementVector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Opens only an actual horizontal door contact, before the authoritative movement sweep. */
public final class MinecraftDoorPush {
    public static void push(Player player, CollisionQueries queries, MovementVector position,
                            MovementVector velocity, MovementBody body, double seconds) {
        if (player.level().isClientSide()) return;
        var delta = new MovementVector(velocity.x() * seconds, 0, velocity.z() * seconds);
        var result = queries.resolve(position, delta, body);
        if (result.blockedByOverlap() || result.recovery().length() > 1.0E-9) return;
        for (var contact : result.contacts()) {
            if (contact.normal().y() != 0) continue;
            var center = contact.shape().min().add(contact.shape().max()).scale(0.5);
            var pos = BlockPos.containing(center.x(), center.y(), center.z());
            var level = player.level();
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock door) || !door.type().canOpenByHand()
                    || state.getValue(DoorBlock.OPEN)) continue;
            // Reject incidental edge contacts and entity boxes that happen to occupy a door block.
            var facing = state.getValue(DoorBlock.FACING);
            if (Math.abs(contact.normal().x() * facing.getStepX() + contact.normal().z() * facing.getStepZ()) < 0.9
                    || state.getCollisionShape(level, pos, CollisionContext.of(player)).toAabbs().stream()
                    .noneMatch(box -> MinecraftCollisionWorld.core(box.move(pos)).equals(contact.shape()))) continue;
            var lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            var bottom = level.getBlockState(lower);
            var top = level.getBlockState(lower.above());
            if (!bottom.is(door) || !top.is(door)
                    || bottom.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
                    || top.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER) continue;
            // Null source broadcasts the normal opening sound to the runner as well as nearby players.
            door.setOpen(null, level, bottom, lower, true);
        }
    }

    private MinecraftDoorPush() {}
}
