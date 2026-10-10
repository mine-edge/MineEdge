package dev.mineedge.movement;

/** Compact cosmetic wallrun state, shared by local prediction and tracking players. */
public record WallRunPose(float yaw, float side, float speed) {
    public static final WallRunPose EMPTY = new WallRunPose(0, 0, 0);

    public boolean active() { return speed > 0; }

    public static WallRunPose sample(MovementState state) {
        if (state.mode() != MovementState.Mode.WALLRUN) return EMPTY;
        var normal = state.wall().normal();
        var horizontal = new MovementVector(state.velocity().x(), 0, state.velocity().z());
        var along = horizontal.subtract(normal.scale(horizontal.dot(normal)));
        double speed = along.horizontalSpeed();
        if (speed < 0.001) return EMPTY;
        double yaw = Math.atan2(-along.x(), along.z());
        // Positive model X is the player's left. The wall normal points away from the wall.
        float side = (float) Math.clamp(-normal.x() * Math.cos(yaw) - normal.z() * Math.sin(yaw), -1, 1);
        return new WallRunPose((float) Math.toDegrees(yaw), side, (float) speed);
    }
}
