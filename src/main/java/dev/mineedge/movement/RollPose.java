package dev.mineedge.movement;

import dev.mineedge.movement.landing.LandingController;

/** Compact fall-roll presentation state for the owner and tracking players. */
public record RollPose(float yaw, float elapsed) {
    public static final RollPose EMPTY = new RollPose(0, -1);

    public boolean active() { return elapsed >= 0; }

    public static RollPose sample(MovementState state) {
        if (state.mode() != MovementState.Mode.ROLL) return EMPTY;
        float yaw = state.velocity().horizontalSpeed() > 0.001
                ? (float) Math.toDegrees(Math.atan2(-state.velocity().x(), state.velocity().z()))
                : state.locomotion().previousYaw();
        return new RollPose(yaw, (float) Math.clamp(state.modeTime(), 0, LandingController.ROLL_TIME));
    }
}
