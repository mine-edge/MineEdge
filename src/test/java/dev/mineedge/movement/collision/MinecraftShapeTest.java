package dev.mineedge.movement.collision;

import dev.mineedge.integration.MinecraftCollisionWorld;
import dev.mineedge.movement.MovementVector;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual 1.21.1 VoxelShape geometry in the transformed NeoForge JVM (no fake whole-block solidity). */
class MinecraftShapeTest {
    private static BoxWorld world(BlockState state) {
        var boxes = new ArrayList<CollisionBox>();
        var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        for (var box : shape.toAabbs()) {
            boxes.add(MinecraftCollisionWorld.core(box));
        }
        return new BoxWorld(boxes);
    }

    @Test
    void actualSlabAndStairShapesKeepTheirHalfBlockClearance() {
        var slab = world(Blocks.STONE_SLAB.defaultBlockState());
        CollisionBox body = CollisionBox.body(new MovementVector(-1, 0.5, 0.5), 0.6, 1.8);
        assertNull(slab.sweep(body, new MovementVector(3, 0, 0)));
        var stairs = world(Blocks.STONE_STAIRS.defaultBlockState());
        var result = CollisionMover.move(stairs, body, new MovementVector(3, 0, 0));
        assertTrue(result.displacement().x() < 3);
        assertTrue(result.displacement().x() >= 0.7);
        assertFalse(stairs.overlaps(body.move(result.displacement())));
    }

    @Test
    void actualFenceAndClosedTrapdoorUseTheirThinBoxes() {
        var fence = world(Blocks.OAK_FENCE.defaultBlockState());
        var hit = fence.sweep(CollisionBox.body(new MovementVector(-1, 0, 0.5), 0.6, 1.8),
                new MovementVector(3, 0, 0));
        assertNotNull(hit);
        assertEquals(0.375, hit.point().x(), 1.0E-12);
        assertEquals(1.5, hit.shape().max().y(), 1.0E-12);
        var trapdoor = world(Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP));
        var ceiling = CollisionMover.move(trapdoor,
                CollisionBox.body(new MovementVector(0.5, -1, 0.5), 0.6, 1.8), new MovementVector(0, 1, 0));
        assertEquals(0.0125, ceiling.displacement().y(), 1.0E-12);
        assertFalse(ceiling.grounded());
    }
}
