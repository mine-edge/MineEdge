package dev.mineedge.client.render;

import dev.mineedge.movement.FootTargets;
import dev.mineedge.movement.MovementVector;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FootSupportTest {
    private final Player player = mock(Player.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final Level level = mock(Level.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final MovementVector left = new MovementVector(0.7, 1, 0.98);
    private final MovementVector right = new MovementVector(0.3, 1, 0.98);

    private void geometry(VoxelShape... shapes) {
        when(player.level()).thenReturn(level);
        when(level.hasChunksAt(any(BlockPos.class), any(BlockPos.class))).thenReturn(true);
        when(level.getBlockCollisions(eq(player), any(AABB.class))).thenReturn(List.of(shapes));
    }

    @Test void ledgeAboveTheFeetDoesNotExtendIntoAnInvisibleWall() {
        geometry(Shapes.box(0, 2, 1, 1, 3, 2));
        assertFalse(FootSupport.supported(player, left));
        assertFalse(FootSupport.supported(player, right));
        geometry(Shapes.box(0, 0, 1, 1, 3, 2));
        assertTrue(FootSupport.supported(player, left));
        assertTrue(FootSupport.supported(player, right));
    }

    @Test void eachFootChecksTheActualShapeRatherThanItsOverallBounds() {
        geometry(Shapes.or(Shapes.box(0.6, 0, 1, 1, 2, 2), Shapes.box(0, 2, 1, 1, 3, 2)));
        var contacts = new FootSupport().sample(player, new FootTargets(left, right, 1, 1));
        assertTrue(contacts.left());
        assertFalse(contacts.right());
    }

    @Test void narrowPipeAndLadderShapesStillSupportFeet() {
        geometry(Shapes.box(0.4375, 0, 0.4375, 0.5625, 3, 0.5625));
        assertTrue(FootSupport.supported(player, new MovementVector(0.6265, 1, 0.25)));
        geometry(Shapes.box(0, 0, 0.875, 1, 3, 1));
        assertTrue(FootSupport.supported(player, new MovementVector(0.7, 1, 0.855)));
    }

    @Test void contactsAreReusedWithinATickAndRecheckedAfterBlockChanges() {
        geometry(Shapes.box(0, 0, 1, 1, 3, 2));
        var cache = new FootSupport();
        var feet = new FootTargets(left, right, 1, 1);
        var contacts = cache.sample(player, feet);
        assertSame(contacts, cache.sample(player, feet));
        verify(level, times(2)).getBlockCollisions(eq(player), any(AABB.class));
        geometry();
        when(level.getGameTime()).thenReturn(1L);
        var removed = cache.sample(player, feet);
        assertFalse(removed.left());
        assertFalse(removed.right());
    }

    @Test void unloadedChunksDoNotProvideSupportOrTriggerShapeQueries() {
        geometry(Shapes.box(0, 0, 1, 1, 3, 2));
        when(level.hasChunksAt(any(BlockPos.class), any(BlockPos.class))).thenReturn(false);
        assertFalse(FootSupport.supported(player, left));
        verify(level, never()).getBlockCollisions(eq(player), any(AABB.class));
    }
}
