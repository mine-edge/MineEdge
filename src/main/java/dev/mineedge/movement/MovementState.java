package dev.mineedge.movement;

import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.wall.WallState;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.surface.SurfaceState;

/** Complete replay and persistent state for locomotion, slide, traversal and wall contacts. */
public record MovementState(MovementVector position, MovementVector velocity, Mode mode,
                            double modeTime, boolean grounded, float fallDistance, LocomotionState locomotion,
                             BlockMotionState blocks, MovementBody body, float slideYaw, TraversalState traversal, WallState wall,
                               LandingState landing, AdvancedState advanced, SurfaceState surfaces) {
    public enum Mode { GROUND, AIR, SLIDE, VAULT, MANTLE, WALLRUN, WALLCLIMB, LEDGE_HANG, PULL_UP, ROLL, HARD_LANDING,
         WALLCLIMB_TURNED, SPRINGBOARD, BACK_LANDING, CLIMB_ATTACH, LADDER_CLIMB, PIPE_CLIMB, CLIMB_EXIT,
         STEP_UP, STEEP_SLIDE, SOFT_LANDING }

    public MovementState(MovementVector position, MovementVector velocity, Mode mode, double modeTime, boolean grounded,
                         float fallDistance, LocomotionState locomotion, BlockMotionState blocks, MovementBody body,
                         float slideYaw, TraversalState traversal, WallState wall, LandingState landing, AdvancedState advanced) {
        this(position, velocity, mode, modeTime, grounded, fallDistance, locomotion, blocks, body, slideYaw, traversal, wall, landing, advanced, SurfaceState.EMPTY);
    }

    public MovementState(MovementVector position, MovementVector velocity, Mode mode, double modeTime, boolean grounded,
                         float fallDistance, LocomotionState locomotion, BlockMotionState blocks, MovementBody body,
                         float slideYaw, TraversalState traversal, WallState wall, LandingState landing) {
        this(position, velocity, mode, modeTime, grounded, fallDistance, locomotion, blocks, body, slideYaw, traversal, wall, landing, AdvancedState.EMPTY);
    }

    public MovementState(MovementVector position, MovementVector velocity, Mode mode,
                         double modeTime, boolean grounded, float fallDistance, LocomotionState locomotion,
                         BlockMotionState blocks, MovementBody body, float slideYaw, TraversalState traversal, WallState wall) {
        this(position, velocity, mode, modeTime, grounded, fallDistance, locomotion, blocks, body, slideYaw, traversal, wall,
                LandingState.capture(position, grounded, fallDistance));
    }

    public MovementState(MovementVector position, MovementVector velocity, Mode mode,
                         double modeTime, boolean grounded, float fallDistance, LocomotionState locomotion,
                         BlockMotionState blocks, MovementBody body, float slideYaw, TraversalState traversal) {
        this(position, velocity, mode, modeTime, grounded, fallDistance, locomotion, blocks, body, slideYaw, traversal, WallState.EMPTY);
    }

    public MovementState(MovementVector position, MovementVector velocity, Mode mode,
                         double modeTime, boolean grounded, float fallDistance, LocomotionState locomotion,
                         BlockMotionState blocks, MovementBody body, float slideYaw) {
        this(position, velocity, mode, modeTime, grounded, fallDistance, locomotion, blocks, body, slideYaw, TraversalState.EMPTY);
    }

    public MovementState(MovementVector position, MovementVector velocity, Mode mode,
                         double modeTime, boolean grounded, float fallDistance) {
        this(position, velocity, mode, modeTime, grounded, fallDistance, LocomotionState.EMPTY, BlockMotionState.EMPTY);
    }

    public MovementState(MovementVector position, MovementVector velocity, Mode mode,
                         double modeTime, boolean grounded, float fallDistance, LocomotionState locomotion,
                         BlockMotionState blocks) {
        this(position, velocity, mode, modeTime, grounded, fallDistance, locomotion, blocks, MovementBody.STANDING, 0);
    }

    public MovementState(MovementVector position, MovementVector velocity, Mode mode,
                         double modeTime, boolean grounded, float fallDistance, LocomotionState locomotion) {
        this(position, velocity, mode, modeTime, grounded, fallDistance, locomotion, BlockMotionState.EMPTY);
    }

    public MovementState withVelocity(MovementVector newVelocity) {
        return new MovementState(position, newVelocity, mode, modeTime, grounded, fallDistance, locomotion, blocks, body, slideYaw, traversal, wall, landing, advanced, surfaces);
    }
}
