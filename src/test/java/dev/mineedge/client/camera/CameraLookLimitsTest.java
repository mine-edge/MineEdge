package dev.mineedge.client.camera;

import dev.mineedge.movement.*;
import dev.mineedge.movement.surface.*;
import dev.mineedge.movement.traversal.*;
import dev.mineedge.movement.wall.WallState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraLookLimitsTest {
    private static MovementState state(MovementState.Mode mode, TraversalState traversal, WallState wall,
                                       AdvancedState advanced, SurfaceState surfaces) {
        return new MovementState(MovementVector.ZERO, MovementVector.ZERO, mode, 0, false, 0,
                LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, traversal, wall,
                dev.mineedge.movement.landing.LandingState.capture(MovementVector.ZERO, false, 0), advanced, surfaces);
    }

    private static MovementState climbing(MovementState.Mode mode, MovementVector normal) {
        var route = new ClimbRoute(MovementVector.ZERO, 5, normal, mode == MovementState.Mode.PIPE_CLIMB, MovementVector.ZERO);
        var grip = new SurfaceState.Climb(route, route.feet(2), 0, 2, 2, 0, 0, false);
        return state(mode, TraversalState.EMPTY, WallState.EMPTY, AdvancedState.EMPTY,
                SurfaceState.EMPTY.climbing(grip, null, MovementVector.ZERO));
    }

    @Test void climbingClampsBothSidesWithoutRatchetAndAllowsTurningBackFromTheLimit() {
        var state = climbing(MovementState.Mode.LADDER_CLIMB, new MovementVector(0, 0, -1));
        assertTrue(CameraLookLimits.restricted(state));
        assertEquals(45, CameraLookLimits.yaw(state, 45));
        assertEquals(90, CameraLookLimits.yaw(state, 120));
        assertEquals(-90, CameraLookLimits.yaw(state, -120));
        float yaw = 0;
        for (int i = 0; i < 100; i++) yaw = CameraLookLimits.yaw(state, yaw + 10);
        assertEquals(90, yaw);
        assertEquals(80, CameraLookLimits.yaw(state, yaw - 10));
    }

    @Test void ledgeHangingAllows135DegreesToEitherSideOfEveryWallFace() {
        for (var normal : new MovementVector[]{new MovementVector(0, 0, -1), new MovementVector(1, 0, 0),
                new MovementVector(0, 0, 1), new MovementVector(-1, 0, 0)}) {
            var wall = WallState.EMPTY.contact(normal, null, MovementVector.ZERO, MovementVector.ZERO, false, 0);
            var hanging = state(MovementState.Mode.LEDGE_HANG, TraversalState.EMPTY, wall, AdvancedState.EMPTY, SurfaceState.EMPTY);
            float center = AdvancedMoves.yaw(normal.scale(-1));
            assertTrue(CameraLookLimits.restricted(hanging));
            assertEquals(center + 90, CameraLookLimits.yaw(hanging, center + 90));
            assertEquals(center + 135, CameraLookLimits.yaw(hanging, center + 160), 1.0E-5);
            assertEquals(center - 135, CameraLookLimits.yaw(hanging, center - 160), 1.0E-5);
            for (int side : new int[]{-1, 1}) {
                float yaw = center;
                for (int i = 0; i < 100; i++) yaw = CameraLookLimits.yaw(hanging, yaw + side * 10);
                assertEquals(center + side * 135, yaw, 1.0E-5);
                assertEquals(center + side * 125, CameraLookLimits.yaw(hanging, yaw - side * 10), 1.0E-5);
            }
            assertEquals(center + 360 + 135, CameraLookLimits.yaw(hanging, center + 360 + 160), 1.0E-5);
            assertEquals(center - 360 - 135, CameraLookLimits.yaw(hanging, center - 360 - 160), 1.0E-5);
        }
    }

    @Test void limitsFollowAllPipeFacesAndOrbitNormals() {
        for (var normal : new MovementVector[]{new MovementVector(0, 0, -1), new MovementVector(1, 0, 0),
                new MovementVector(0, 0, 1), new MovementVector(-1, 0, 0), new MovementVector(1, 0, 1).normalized()}) {
            var state = climbing(MovementState.Mode.PIPE_CLIMB, normal);
            float center = AdvancedMoves.yaw(normal.scale(-1));
            assertEquals(center + 90, CameraLookLimits.yaw(state, center + 120), 1.0E-5);
            assertEquals(center - 90, CameraLookLimits.yaw(state, center - 120), 1.0E-5);
            assertEquals(center, CameraLookLimits.yaw(state, center));
        }
    }

    @Test void attachmentAndExitUseTheClimbedSurfaceFacing() {
        for (var mode : new MovementState.Mode[]{MovementState.Mode.CLIMB_ATTACH, MovementState.Mode.CLIMB_EXIT}) {
            var state = climbing(mode, new MovementVector(0, 0, -1));
            assertEquals(90, CameraLookLimits.yaw(state, 100));
        }
    }

    @Test void yawWrapKeepsTheSameRevolutionForInterpolation() {
        var south = climbing(MovementState.Mode.LADDER_CLIMB, new MovementVector(0, 0, -1));
        assertEquals(359, CameraLookLimits.yaw(south, 359));
        assertEquals(450, CameraLookLimits.yaw(south, 480));
        var north = climbing(MovementState.Mode.LADDER_CLIMB, new MovementVector(0, 0, 1));
        assertEquals(179, CameraLookLimits.yaw(north, 179));
        assertEquals(181, CameraLookLimits.yaw(north, 181));
        assertEquals(-179, CameraLookLimits.yaw(north, -179));
        assertEquals(90, CameraLookLimits.yaw(north, 60));
    }

    @Test void mantleAndPullUpUseThePathDirectionThroughoutTheMove() {
        var path = new TraversalPath(VaultType.MANTLE, MovementVector.ZERO, new MovementVector(0, 2, 0),
                new MovementVector(-1, 2, 0), new MovementVector(-1, 1, 0), MovementVector.ZERO, null, null);
        for (var mode : new MovementState.Mode[]{MovementState.Mode.MANTLE, MovementState.Mode.PULL_UP}) {
            var state = state(mode, new TraversalState(path, 0, MovementVector.ZERO), WallState.EMPTY, AdvancedState.EMPTY, SurfaceState.EMPTY);
            assertEquals(90, CameraLookLimits.yaw(state, 90));
            assertEquals(180, CameraLookLimits.yaw(state, 200));
            assertEquals(0, CameraLookLimits.yaw(state, -20));
        }
    }

    @Test void wallClimbLimitsRespectTheScriptedContextualTurn() {
        var wall = WallState.EMPTY.contact(new MovementVector(0, 0, -1), null, MovementVector.ZERO, MovementVector.ZERO, true, 0);
        var state = state(MovementState.Mode.WALLCLIMB, TraversalState.EMPTY, wall, AdvancedState.EMPTY, SurfaceState.EMPTY);
        assertEquals(90, CameraLookLimits.yaw(state, 120));
        var turn = AdvancedState.EMPTY.turn(new AdvancedState.Turn(0, 180, 0.1, 0.2), false);
        var turning = state(MovementState.Mode.WALLCLIMB_TURNED, TraversalState.EMPTY, wall, turn, SurfaceState.EMPTY);
        assertFalse(CameraLookLimits.restricted(turning));
        assertEquals(140, CameraLookLimits.yaw(turning, 140));
        var turned = state(MovementState.Mode.WALLCLIMB_TURNED, TraversalState.EMPTY, wall, AdvancedState.EMPTY, SurfaceState.EMPTY);
        assertTrue(CameraLookLimits.restricted(turned));
        assertEquals(180, CameraLookLimits.yaw(turned, 180));
        assertEquals(90, CameraLookLimits.yaw(turned, 60));
    }

    @Test void ordinaryMovementAndAbsentStateAllowFreeLook() {
        assertEquals(270, CameraLookLimits.yaw(null, 270));
        assertFalse(CameraLookLimits.restricted(null));
        for (var mode : new MovementState.Mode[]{MovementState.Mode.GROUND, MovementState.Mode.AIR, MovementState.Mode.VAULT, MovementState.Mode.WALLRUN}) {
            var state = state(mode, TraversalState.EMPTY, WallState.EMPTY, AdvancedState.EMPTY, SurfaceState.EMPTY);
            assertFalse(CameraLookLimits.restricted(state));
            assertEquals(270, CameraLookLimits.yaw(state, 270));
        }
    }
}
