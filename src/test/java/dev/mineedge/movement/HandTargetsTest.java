package dev.mineedge.movement;

import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfaceState;
import dev.mineedge.movement.traversal.TraversalPath;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.traversal.VaultType;
import dev.mineedge.movement.wall.WallState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HandTargetsTest {
    private final CollisionBox ledge = new CollisionBox(new MovementVector(0, 0, 1), new MovementVector(1, 3, 2));
    private final MovementVector normal = new MovementVector(0, 0, -1);
    private final MovementVector feet = new MovementVector(0.5, 3 - 1.828, 0.69);

    private MovementState state(MovementState.Mode mode, double time, TraversalState traversal, AdvancedState advanced,
                                SurfaceState surfaces) {
        return new MovementState(feet, MovementVector.ZERO, mode, time, false, 0, LocomotionState.EMPTY,
                BlockMotionState.EMPTY, MovementBody.STANDING, 0, traversal,
                WallState.EMPTY.contact(normal, ledge, feet, MovementVector.ZERO, false, 0),
                LandingState.capture(feet, false, 0), advanced, surfaces);
    }

    @Test void hangingGripsStayOnTheLipAndRespectNarrowShapes() {
        var hands = HandTargets.sample(state(MovementState.Mode.LEDGE_HANG, 1, TraversalState.EMPTY, AdvancedState.EMPTY, SurfaceState.EMPTY));
        assertEquals(3.02, hands.left().y(), 1.0E-9);
        assertEquals(3.02, hands.right().y(), 1.0E-9);
        assertEquals(0.98, hands.left().z(), 1.0E-9);
        // Facing +Z, Minecraft's left arm is on +X (matching positive strafe).
        assertTrue(hands.left().x() > hands.right().x());
        assertTrue(hands.right().x() >= ledge.min().x());
        assertTrue(hands.left().x() <= ledge.max().x());
        assertEquals(1, hands.leftWeight());
        assertFalse(HandTargets.sample(state(MovementState.Mode.AIR, 0, TraversalState.EMPTY, AdvancedState.EMPTY, SurfaceState.EMPTY)).active());
    }

    @Test void wallrunReleasesClimbingContactsEvenDuringAClimbTransition() {
        var route = new ClimbRoute(new MovementVector(0.5, 0, 1), 3, normal, false, MovementVector.ZERO);
        var climb = new SurfaceState.Climb(route, feet, 0, feet.y(), feet.y(), 0, 0, false);
        var surfaces = SurfaceState.EMPTY.climbing(climb, null, MovementVector.ZERO);
        for (var surface : new SurfaceState[]{SurfaceState.EMPTY, surfaces}) {
            var running = state(MovementState.Mode.WALLRUN, 0.3, TraversalState.EMPTY, AdvancedState.EMPTY, surface);
            assertNotNull(running.wall().support());
            assertSame(HandTargets.EMPTY, HandTargets.sample(running));
            assertSame(FootTargets.EMPTY, FootTargets.fromGrips(HandTargets.sample(running)));
        }
        assertTrue(HandTargets.sample(state(MovementState.Mode.WALLCLIMB, 0.3,
                TraversalState.EMPTY, AdvancedState.EMPTY, SurfaceState.EMPTY)).active());
    }

    @Test void pullUpStartsFromTheHangingGripAndReleasesBeforeLanding() {
        var path = new TraversalPath(VaultType.PULL_UP, feet, feet.add(new MovementVector(0, 2, 0)),
                new MovementVector(0.5, 3.08, 1.5), new MovementVector(0.5, 3, 1.5), MovementVector.ZERO, ledge, ledge);
        var traversal = new TraversalState(path, 0, MovementVector.ZERO);
        var hang = HandTargets.sample(state(MovementState.Mode.LEDGE_HANG, 1, TraversalState.EMPTY, AdvancedState.EMPTY, SurfaceState.EMPTY));
        var pull = HandTargets.sample(state(MovementState.Mode.PULL_UP, 0, traversal, AdvancedState.EMPTY, SurfaceState.EMPTY));
        assertEquals(hang, pull);
        assertFalse(HandTargets.sample(state(MovementState.Mode.PULL_UP, VaultType.PULL_UP.duration() * 0.8,
                traversal, AdvancedState.EMPTY, SurfaceState.EMPTY)).active());
    }

    @Test void climbingGripsAreStaggeredAndBoundedByTheRouteTop() {
        var route = new ClimbRoute(new MovementVector(0.5, 0, 1), 3, normal, false, MovementVector.ZERO);
        var climb = new SurfaceState.Climb(route, feet, 0, feet.y(), feet.y(), 0, 0, false);
        var surfaces = SurfaceState.EMPTY.climbing(climb, null, MovementVector.ZERO);
        var hands = HandTargets.sample(state(MovementState.Mode.LADDER_CLIMB, 0, TraversalState.EMPTY, AdvancedState.EMPTY, surfaces));
        assertNotEquals(hands.left().y(), hands.right().y());
        assertTrue(hands.left().y() <= route.top());
        assertTrue(hands.right().y() <= route.top());
        assertEquals(0.98, hands.left().z(), 1.0E-9);
    }

    @Test void shimmyMovesTheLeadingHandBeforeReleasingTheTrailingGrip() {
        var move = new AdvancedState.LedgeMove(AdvancedState.LedgeKind.SHIMMY, feet, feet.add(new MovementVector(0.15, 0, 0)),
                feet.add(new MovementVector(0.3, 0, 0)), normal, ledge, ledge, 0.2, 1, 0, 0);
        var hanging = HandTargets.sample(state(MovementState.Mode.LEDGE_HANG, 0, TraversalState.EMPTY, AdvancedState.EMPTY, SurfaceState.EMPTY));
        var moving = HandTargets.sample(state(MovementState.Mode.LEDGE_HANG, 0, TraversalState.EMPTY,
                AdvancedState.EMPTY.ledge(move), SurfaceState.EMPTY));
        assertEquals(hanging.right(), moving.right());
        assertTrue(moving.left().x() > hanging.left().x());
    }
}
