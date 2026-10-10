package dev.mineedge.movement;

import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.wall.WallState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WallRunPoseTest {
    private static MovementState state(MovementState.Mode mode, MovementVector normal, MovementVector velocity) {
        return new MovementState(MovementVector.ZERO, velocity, mode, 0, false, 0, LocomotionState.EMPTY,
                BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY,
                WallState.EMPTY.contact(normal, null, MovementVector.ZERO, velocity, false, 0));
    }

    @Test void poseFacesTheWallTangentAndMirrorsForEitherWallAcrossWorldRotations() {
        for (int yaw : new int[]{-179, -90, 0, 90, 179}) for (int side : new int[]{-1, 1}) {
            double radians = Math.toRadians(yaw);
            var normal = new MovementVector(-side * Math.cos(radians), 0, -side * Math.sin(radians));
            var velocity = new MovementVector(-Math.sin(radians) * 6, -3, Math.cos(radians) * 6).add(normal.scale(-0.5));
            var pose = WallRunPose.sample(state(MovementState.Mode.WALLRUN, normal, velocity));
            assertTrue(pose.active());
            assertEquals(yaw, pose.yaw(), 0.0001);
            assertEquals(side, pose.side(), 0.0001);
            assertEquals(6, pose.speed(), 0.0001, "Wall adhesion or falling changed the stride speed");
            assertSame(WallRunPose.EMPTY, WallRunPose.sample(state(MovementState.Mode.AIR, normal, velocity)));
        }
    }
}
