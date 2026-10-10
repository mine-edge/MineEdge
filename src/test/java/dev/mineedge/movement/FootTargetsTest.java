package dev.mineedge.movement;

import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfaceState;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.wall.WallState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FootTargetsTest {
    private final MovementVector position = new MovementVector(0.5, 1.2, 0.69);
    private final MovementVector normal = new MovementVector(0, 0, -1);
    private final CollisionBox support = new CollisionBox(new MovementVector(0, 0, 1), new MovementVector(1, 4, 2));

    private MovementState state(MovementState.Mode mode, SurfaceState surfaces) {
        return new MovementState(position, new MovementVector(1, 2, 0), mode, 0.3, false, 0, LocomotionState.EMPTY,
                BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY,
                WallState.EMPTY.contact(normal, support, position, MovementVector.ZERO, false, 0),
                LandingState.capture(position, false, 0), AdvancedState.EMPTY, surfaces);
    }

    @Test void airborneLadderAndPipeClimbsHaveStaggeredFeetOnTheGripPlane() {
        for (boolean pipe : new boolean[]{false, true}) {
            var route = new ClimbRoute(new MovementVector(0.5, 0, 1), 4, normal, pipe, MovementVector.ZERO);
            var climb = new SurfaceState.Climb(route, position, 0, position.y(), position.y(), 0, 0, false);
            var state = state(pipe ? MovementState.Mode.PIPE_CLIMB : MovementState.Mode.LADDER_CLIMB,
                    SurfaceState.EMPTY.climbing(climb, null, MovementVector.ZERO));
            assertFalse(state.grounded());
            var hands = HandTargets.sample(state);
            var feet = FootTargets.fromGrips(hands);
            assertTrue(feet.active());
            assertEquals(1, feet.leftWeight());
            assertEquals(1, feet.rightWeight());
            assertNotEquals(feet.left().y(), feet.right().y());
            assertEquals(hands.left().z(), feet.left().z());
            assertEquals(hands.right().z(), feet.right().z());
            assertTrue(feet.left().x() > feet.right().x());
            assertTrue(feet.left().x() < hands.left().x());
            assertTrue(feet.right().x() > hands.right().x());
            assertEquals(Math.signum(hands.left().y() - hands.right().y()),
                    -Math.signum(feet.left().y() - feet.right().y()));
        }
    }

    @Test void wallClimbAndLedgeHangKeepBothFeetAgainstTheWall() {
        for (var mode : new MovementState.Mode[]{MovementState.Mode.WALLCLIMB, MovementState.Mode.LEDGE_HANG}) {
            var feet = FootTargets.fromGrips(HandTargets.sample(state(mode, SurfaceState.EMPTY)), mode == MovementState.Mode.LEDGE_HANG);
            assertEquals(1, feet.leftWeight());
            assertEquals(1, feet.rightWeight());
            for (var foot : new MovementVector[]{feet.left(), feet.right()}) {
                assertEquals(0.98, foot.z(), 1.0E-9);
                assertTrue(foot.x() >= support.min().x() && foot.x() <= support.max().x());
                assertTrue(foot.y() < support.max().y());
            }
        }
    }

    @Test void wallrunAndNormalMovementKeepTheNativeLimbAnimation() {
        for (var mode : new MovementState.Mode[]{MovementState.Mode.WALLRUN, MovementState.Mode.AIR, MovementState.Mode.GROUND}) {
            var hands = HandTargets.sample(state(mode, SurfaceState.EMPTY));
            assertSame(HandTargets.EMPTY, hands);
            assertSame(FootTargets.EMPTY, FootTargets.fromGrips(hands));
        }
    }

    @Test void relativeRemoteGripsProduceTheSameWorldFeetAsLocalGrips() {
        var hands = HandTargets.sample(state(MovementState.Mode.WALLCLIMB, SurfaceState.EMPTY));
        var feet = FootTargets.fromGrips(hands);
        var relative = FootTargets.fromGrips(hands.translate(position.scale(-1)));
        assertEquals(0, relative.left().add(position).subtract(feet.left()).length(), 1.0E-9);
        assertEquals(0, relative.right().add(position).subtract(feet.right()).length(), 1.0E-9);
    }

    @Test void traversalFeetAreRaisedHalfABlockFromThePreviousGripOffset() {
        var hands = new HandTargets(new MovementVector(0.23, 2, 0.3), new MovementVector(-0.23, 1.75, 0.3), 1, 1);
        var feet = FootTargets.fromGrips(hands);
        assertEquals(0.85, feet.left().y(), 1.0E-9);
        assertEquals(1.1, feet.right().y(), 1.0E-9);
        assertEquals(hands.right().y() - 1.4 + 0.5, feet.left().y(), 1.0E-9);
        assertEquals(hands.left().y() - 1.4 + 0.5, feet.right().y(), 1.0E-9);
    }

    @Test void onlyTheExplicitLedgeHangPoseLowersContactsWithIdenticalGrips() {
        var hands = new HandTargets(new MovementVector(0.23, 2, 0.3), new MovementVector(-0.23, 2, 0.3), 1, 1);
        var climbing = FootTargets.fromGrips(hands);
        for (var mode : MovementState.Mode.values()) {
            var feet = FootTargets.fromGrips(hands, mode == MovementState.Mode.LEDGE_HANG);
            if (mode != MovementState.Mode.LEDGE_HANG) {
                assertEquals(climbing, feet, "Unexpected lower contacts in " + mode);
                continue;
            }
            assertEquals(climbing.left().y() - 0.5, feet.left().y(), 1.0E-9);
            assertEquals(climbing.right().y() - 0.5, feet.right().y(), 1.0E-9);
            assertEquals(climbing.left().x(), feet.left().x());
            assertEquals(climbing.left().z(), feet.left().z());
            assertEquals(climbing.right().x(), feet.right().x());
            assertEquals(climbing.right().z(), feet.right().z());
            assertEquals(climbing.leftWeight(), feet.leftWeight());
            assertEquals(climbing.rightWeight(), feet.rightWeight());
        }
        assertSame(FootTargets.EMPTY, FootTargets.fromGrips(HandTargets.EMPTY, true));
    }
}
