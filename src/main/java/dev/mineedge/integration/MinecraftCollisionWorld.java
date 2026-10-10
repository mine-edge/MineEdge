package dev.mineedge.integration;

import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.collision.BoxWorld;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionMover;
import dev.mineedge.movement.collision.CollisionWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Actual shape-box collection; controller-tick reuse is owned by CollisionQueries. */
public final class MinecraftCollisionWorld {
    public static CollisionBox body(Player player, MovementVector position) {
        return core(player.getBoundingBox().move(MinecraftMotionAdapter.minecraft(position).subtract(player.position())));
    }

    public static CollisionMover.Result resolve(Player player, MovementVector position, MovementVector delta) {
        CollisionBox body = body(player, position);
        return resolve(player, body, delta);
    }

    public static CollisionMover.Result resolve(Player player, MovementVector position, MovementVector delta, MovementBody body) {
        return resolve(player, core(MovementPoses.dimensions(player, body).makeBoundingBox(MinecraftMotionAdapter.minecraft(position))), delta);
    }

    public static boolean canOccupy(Player player, MovementVector position, MovementBody body) {
        CollisionBox bounds = core(MovementPoses.dimensions(player, body).makeBoundingBox(MinecraftMotionAdapter.minecraft(position)));
        BoxWorld world = gather(player, minecraft(bounds.swept(MovementVector.ZERO, BoxWorld.TOUCH)));
        return world != null && !world.overlaps(bounds);
    }

    public static CollisionWorld geometry(Player player, CollisionBox region) {
        // Only persistent block/border geometry may select or anchor a traversal.
        // Entities still participate in each live/replay displacement's collision sweep.
        return gather(player, minecraft(region), false);
    }

    public static CollisionBox bounds(Player player, MovementVector position, MovementBody body) {
        return core(MovementPoses.dimensions(player, body).makeBoundingBox(MinecraftMotionAdapter.minecraft(position)));
    }

    private static CollisionMover.Result resolve(Player player, CollisionBox body, MovementVector delta) {
        AABB region = minecraft(body.swept(delta, CollisionMover.MAX_RECOVERY + BoxWorld.TOUCH));
        CollisionWorld world = gather(player, region);
        return world == null ? new CollisionMover.Result(MovementVector.ZERO, MovementVector.ZERO, java.util.List.of(), true)
                : CollisionMover.move(world, body, delta);
    }

    private static BoxWorld gather(Player player, AABB region) {
        return gather(player, region, true);
    }

    private static BoxWorld gather(Player player, AABB region, boolean entities) {
        MovementProfile.query();
        return supplement(player, region, gatherBlocks(player, region), entities);
    }

    static boolean loaded(Player player, AABB region) {
        return player.level().hasChunksAt(BlockPos.containing(region.minX - 1, region.minY, region.minZ - 1),
                BlockPos.containing(region.maxX + 1, region.maxY, region.maxZ + 1));
    }

    static BoxWorld gatherBlocks(Player player, AABB region) {
        long profileStart = MovementProfile.startGather();
        // BlockCollisions scans an adjacent ring. Refuse the whole query before touching absent chunks.
        if (!loaded(player, region)) return null;
        var boxes = new ArrayList<CollisionBox>();
        for (VoxelShape shape : player.level().getBlockCollisions(player, region)) append(boxes, shape);
        BoxWorld world = new BoxWorld(boxes);
        MovementProfile.gathered(profileStart, boxes.size());
        return world;
    }

    /** Entity contacts and finite border clipping are always evaluated against the current query. */
    static BoxWorld supplement(Player player, AABB region, BoxWorld blocks, boolean entities) {
        if (blocks == null) return null;
        var boxes = new ArrayList<CollisionBox>();
        if (entities) for (VoxelShape shape : player.level().getEntityCollisions(player, region)) append(boxes, shape);
        var border = player.level().getWorldBorder();
        // Replay's entity remains at its baseline. Test the simulated region, not entity.position().
        // Vanilla's border collision shape rounds its inner faces outward to block coordinates.
        if (region.minX <= Math.floor(border.getMinX()) || region.maxX >= Math.ceil(border.getMaxX())
                || region.minZ <= Math.floor(border.getMinZ()) || region.maxZ >= Math.ceil(border.getMaxZ())) {
            for (AABB box : border.getCollisionShape().toAabbs()) {
                // Border shapes extend to infinity; clip them to the finite broad phase.
                if (!box.intersects(region)) {
                    continue;
                }
                AABB clipped = box.intersect(region);
                if (clipped.getXsize() > 0 && clipped.getYsize() > 0 && clipped.getZsize() > 0) {
                    boxes.add(core(clipped));
                }
            }
        }
        return blocks.withAdditional(boxes);
    }

    private static void append(List<CollisionBox> boxes, VoxelShape shape) {
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> boxes.add(new CollisionBox(
                new MovementVector(x0, y0, z0), new MovementVector(x1, y1, z1))));
    }

    public static CollisionBox core(AABB box) {
        return new CollisionBox(new MovementVector(box.minX, box.minY, box.minZ),
                new MovementVector(box.maxX, box.maxY, box.maxZ));
    }

    static AABB minecraft(CollisionBox box) {
        return new AABB(box.min().x(), box.min().y(), box.min().z(), box.max().x(), box.max().y(), box.max().z());
    }

    private MinecraftCollisionWorld() {}
}
