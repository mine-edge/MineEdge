package dev.mineedge.client.camera;

import dev.mineedge.movement.AdvancedMoves;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementVector;

/** Surface-relative yaw limits, independent of cosmetic camera settings. */
public final class CameraLookLimits {
    public static float yaw(MovementState state, float requested) {
        MovementVector facing = facing(state);
        if (facing.horizontalSpeed() < 1.0E-9) return requested;
        float offset = AdvancedMoves.wrapped(requested - AdvancedMoves.yaw(facing));
        float limit = state.mode() == MovementState.Mode.LEDGE_HANG ? 135 : 90;
        // Keep the existing revolution so vanilla interpolation never takes a full turn across +/-180.
        return requested + (Math.clamp(offset, -limit, limit) - offset);
    }

    public static boolean restricted(MovementState state) {
        return facing(state).horizontalSpeed() >= 1.0E-9;
    }

    private static MovementVector facing(MovementState state) {
        if (state == null || state.advanced().turn() != null && state.advanced().turn().active()) return MovementVector.ZERO;
        return switch (state.mode()) {
            case MANTLE, PULL_UP -> state.traversal().path() == null ? MovementVector.ZERO
                    : state.traversal().path().over().subtract(state.traversal().path().hand());
            case LEDGE_HANG, WALLCLIMB -> state.wall().normal().scale(-1);
            case WALLCLIMB_TURNED -> state.wall().normal();
            case CLIMB_ATTACH, LADDER_CLIMB, PIPE_CLIMB, CLIMB_EXIT -> state.surfaces().climb() == null ? MovementVector.ZERO
                    : state.surfaces().climb().route().normal().scale(-1);
            default -> MovementVector.ZERO;
        };
    }

    private CameraLookLimits() {}
}
