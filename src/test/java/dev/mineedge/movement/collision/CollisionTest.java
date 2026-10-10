package dev.mineedge.movement.collision;

import dev.mineedge.movement.MovementVector;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollisionTest {
    private static CollisionBox box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new CollisionBox(new MovementVector(x0, y0, z0), new MovementVector(x1, y1, z1));
    }

    private static CollisionBox body(double x, double y, double z) {
        return CollisionBox.body(new MovementVector(x, y, z), 0.6, 1.8);
    }

    @Test
    void sweepStopsAtThinObstacleEvenAtHighSpeedAndReportsContact() {
        CollisionBox obstacle = box(2, -1, -2, 2.01, 3, 2);
        var hit = new BoxWorld(List.of(obstacle)).sweep(body(0, 0, 0), new MovementVector(100, 0, 0));
        assertNotNull(hit);
        assertEquals(0.017, hit.fraction(), 1.0E-12);
        assertEquals(new MovementVector(-1, 0, 0), hit.normal());
        assertEquals(2, hit.point().x(), 1.0E-12);
        assertEquals(obstacle, hit.shape());
    }

    @Test
    void touchingFacesAllowParallelAndOutwardMotionButBlockInwardMotion() {
        var world = new BoxWorld(List.of(box(-2, -1, -2, 2, 0, 2)));
        CollisionBox body = body(0, 0, 0);
        assertFalse(world.overlaps(body));
        assertNull(world.sweep(body, new MovementVector(1, 0, 0)));
        assertNull(world.sweep(body, new MovementVector(0, 1, 0)));
        var down = world.sweep(body, new MovementVector(0, -1, 0));
        assertNotNull(down);
        assertEquals(0, down.fraction());
        assertEquals(new MovementVector(0, 1, 0), down.normal());
        // Merely reaching a face does not clip motion; the next inward sweep does.
        assertNull(world.sweep(body(0, 1, 0), new MovementVector(0, -1, 0)));
    }

    @Test
    void wallsSlideAndNarrowCornersAreIndependentOfShapeOrder() {
        var x = box(1, -1, -5, 2, 5, 5);
        var z = box(-5, -1, 1, 5, 5, 2);
        var delta = new MovementVector(2, 0, 2);
        var a = CollisionMover.move(new BoxWorld(List.of(x, z)), body(0, 0, 0), delta);
        var b = CollisionMover.move(new BoxWorld(List.of(z, x)), body(0, 0, 0), delta);
        assertEquals(a, b);
        assertEquals(0.7, a.displacement().x(), 1.0E-12);
        assertEquals(0.7, a.displacement().z(), 1.0E-12);
        assertEquals(MovementVector.ZERO, a.clip(delta));
        assertEquals(2, a.contacts().size());
        var slide = CollisionMover.move(new BoxWorld(List.of(x)), body(0, 0, 0), delta);
        assertEquals(2, slide.displacement().z(), 1.0E-12);
    }

    @Test
    void groundAndCeilingClipOnlyTheirIncomingVelocity() {
        var world = new BoxWorld(List.of(box(-10, -1, -10, 10, 0, 10), box(-10, 2, -10, 10, 3, 10)));
        var ceiling = CollisionMover.move(world, body(0, 0, 0), new MovementVector(0.1, 1, 0.1));
        assertEquals(0.2, ceiling.displacement().y(), 1.0E-12);
        assertFalse(ceiling.grounded());
        assertEquals(new MovementVector(1, 0, 1), ceiling.clip(new MovementVector(1, 5, 1)));
        var floor = CollisionMover.move(world, body(0, 0, 0), new MovementVector(0.1, -1, 0.1));
        assertTrue(floor.grounded());
        assertEquals(new MovementVector(1, 0, 1), floor.clip(new MovementVector(1, -5, 1)));
    }

    @Test
    void slabSeamsStayClearAndFencePostDoesNotBecomeAFullBlock() {
        var world = new BoxWorld(List.of(box(-2, 0, -2, 0, 0.5, 2), box(0, 0, -2, 2, 0.5, 2),
                box(0.375, 0.5, 0.375, 0.625, 2, 0.625)));
        var result = CollisionMover.move(world, body(-1, 0.5, -0.1), new MovementVector(2, -0.01, 0));
        assertEquals(2, result.displacement().x(), 1.0E-12);
        assertTrue(result.grounded());
        var fence = CollisionMover.move(world, body(-1, 0.5, 0.5), new MovementVector(2, 0, 0));
        assertEquals(1.075, fence.displacement().x(), 1.0E-12);
    }

    @Test
    void boundedOverlapRecoverySeparatesSmallPenetrationAndFreezesDeepEnclosure() {
        var floor = new BoxWorld(List.of(box(-10, -1, -10, 10, 0, 10)));
        var recovered = CollisionMover.move(floor, body(0, -0.05, 0), new MovementVector(1, -0.1, 0));
        assertFalse(recovered.blockedByOverlap());
        assertEquals(0.05, recovered.recovery().y(), 1.0E-12);
        assertEquals(1, recovered.displacement().x(), 1.0E-12);
        assertTrue(recovered.grounded());
        var enclosed = CollisionMover.move(new BoxWorld(List.of(box(-2, -2, -2, 2, 4, 2))),
                body(0, 0, 0), new MovementVector(100, 0, 0));
        assertTrue(enclosed.blockedByOverlap());
        assertEquals(MovementVector.ZERO, enclosed.recovery());
        assertEquals(MovementVector.ZERO, enclosed.displacement());
        assertEquals(MovementVector.ZERO, enclosed.clip(new MovementVector(100, 0, 0)));
    }

    @Test
    void removedSupportIsNotRetainedBetweenQueries() {
        var floor = new BoxWorld(List.of(box(-1, -1, -1, 1, 0, 1)));
        assertTrue(CollisionMover.move(floor, body(0, 0, 0), new MovementVector(0, -0.1, 0)).grounded());
        var falling = CollisionMover.move(new BoxWorld(List.of()), body(0, 0, 0), new MovementVector(0, -0.1, 0));
        assertFalse(falling.grounded());
        assertEquals(-0.1, falling.displacement().y());
    }

    @Test
    void collisionStepsKeepHorizontalMotionAndUseVanillaCandidateHeights() {
        var floor = box(-10, -1, -10, 10, 0, 10);
        for (double height : new double[]{0.0625, 0.5, 0.6}) {
            var step = box(0.5, 0, -2, 2, height, 2);
            var world = new BoxWorld(List.of(floor, step));
            var delta = new MovementVector(0.4, -0.01, 0);
            var blocked = CollisionMover.move(world, body(0, 0, 0), delta, 0);
            assertEquals(0, blocked.displacement().y(), 1.0E-9);
            assertEquals(0.2, blocked.displacement().x(), 1.0E-9);
            var stepped = CollisionMover.move(world, body(0, 0, 0), delta, 0.6);
            assertEquals(0.4, stepped.displacement().x(), 1.0E-9);
            assertEquals((double) (float) height, stepped.displacement().y(), 1.0E-9);
            assertTrue(stepped.grounded()); assertFalse(world.overlaps(body(0, 0, 0).move(stepped.displacement())));
            assertEquals(6, stepped.clip(new MovementVector(6, -1, 0)).x(), 1.0E-9);
        }
    }

    @Test
    void collisionStepsRespectHeightAndHeadroomWithoutNeedingExtraAnimationClearance() {
        var floor = box(-10, -1, -10, 10, 0, 10);
        var slab = box(0.5, 0, -2, 2, 0.5, 2);
        var delta = new MovementVector(0.4, -0.01, 0);
        var lowRoof = new BoxWorld(List.of(floor, slab, box(-2, 2.3, -2, 3, 3, 2)));
        assertEquals(0.5, CollisionMover.move(lowRoof, body(0, 0, 0), delta, 0.6).displacement().y(), 1.0E-9);
        var blockedRoof = new BoxWorld(List.of(floor, slab, box(-2, 2.2, -2, 3, 3, 2)));
        assertEquals(0, CollisionMover.move(blockedRoof, body(0, 0, 0), delta, 0.6).displacement().y(), 1.0E-9);
        var tall = box(0.5, 0, -2, 2, 0.7, 2);
        assertEquals(0, CollisionMover.move(new BoxWorld(List.of(floor, tall)), body(0, 0, 0), delta, 0.6).displacement().y(), 1.0E-9);
    }

    @Test
    void stepCandidatesIncludeHigherShapesBehindTheFirstContact() {
        var carpet = box(0.5, 0, -2, 0.55, 0.0625, 2);
        var slab = box(0.6, 0, -2, 2, 0.5, 2);
        var world = new BoxWorld(List.of(box(-10, -1, -10, 10, 0, 10), carpet, slab));
        var moved = CollisionMover.move(world, body(0, 0, 0), new MovementVector(0.4, -0.01, 0), 0.6);
        // Vanilla accepts the first height that improves on the normal move,
        // even when a higher candidate would allow more horizontal progress.
        assertEquals(0.0625, moved.displacement().y(), 1.0E-9);
        assertEquals(0.3, moved.displacement().x(), 1.0E-9);
        assertFalse(world.overlaps(body(0, 0, 0).move(moved.displacement())));
    }

    @Test
    void landingCanStepButUnsupportedAirMovementCannot() {
        var step = box(0.5, 0, -2, 2, 0.5, 2);
        var delta = new MovementVector(0.4, -0.3, 0);
        var landing = CollisionMover.move(new BoxWorld(List.of(box(-10, -1, -10, 10, 0, 10), step)),
                body(0, 0.2, 0), delta, 0.6);
        assertEquals(0.4, landing.displacement().x(), 1.0E-9);
        assertEquals(0.3, landing.displacement().y(), 1.0E-9);
        assertTrue(landing.grounded());
        var airborne = CollisionMover.move(new BoxWorld(List.of(step)), body(0, 0.2, 0),
                new MovementVector(0.4, -0.1, 0), 0.6);
        assertEquals(0.2, airborne.displacement().x(), 1.0E-9);
        assertEquals(-0.1, airborne.displacement().y(), 1.0E-9);
        assertFalse(airborne.grounded());
    }

    @Test
    void stepHeightAttributeCanEnableFullBlocksAndZeroHeightDisablesStepping() {
        var world = new BoxWorld(List.of(box(-10, -1, -10, 10, 0, 10), box(0.5, 0, -2, 2, 1, 2)));
        var delta = new MovementVector(0.4, -0.01, 0);
        assertEquals(0, CollisionMover.move(world, body(0, 0, 0), delta, 0.6).displacement().y(), 1.0E-9);
        assertEquals(1, CollisionMover.move(world, body(0, 0, 0), delta, 1).displacement().y(), 1.0E-9);
        assertEquals(0, CollisionMover.move(world, body(0, 0, 0), delta, 0).displacement().y(), 1.0E-9);
    }
}
