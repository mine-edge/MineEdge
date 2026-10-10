package dev.mineedge.client.camera;

/** Render-only critically damped yaw for scripted turns and surface-relative look limits. */
public final class CameraRotationSmoothing {
    private static final double RESPONSE = 35;
    private boolean initialized;
    private long lastFrame;
    private float previousYaw, presentedYaw;
    private double target, yaw, velocity;

    public float update(float requested, boolean controlled, long now, boolean paused) {
        if (!initialized) {
            initialized = true;
            lastFrame = now;
            previousYaw = presentedYaw = requested;
            target = yaw = requested;
            return requested;
        }
        double seconds = Math.max(0, (now - lastFrame) / 1.0E9);
        lastFrame = now;
        if (paused) return presentedYaw;

        double delta = requested - (double) previousYaw;
        // Preserve the direction of an exact scripted half-turn, but unwrap +/-180 crossings.
        if (Math.abs(delta) > 180) delta = ((delta % 360) + 540) % 360 - 180;
        target += delta;
        previousYaw = requested;
        // Free mouse look remains immediate, including while a prior forced turn settles.
        if (!controlled) yaw += delta;

        double offset = yaw - target;
        double change = velocity + RESPONSE * offset;
        double decay = Math.exp(-RESPONSE * seconds);
        yaw = target + (offset + change * seconds) * decay;
        velocity = (velocity - RESPONSE * change * seconds) * decay;
        if (Math.abs(yaw - target) < 1.0E-4 && Math.abs(velocity) < 1.0E-3) {
            yaw = target;
            velocity = 0;
        }
        // Return in the entity's current revolution; the internal spring uses continuous angles.
        presentedYaw = requested + (float) (yaw - target);
        return presentedYaw;
    }

    public void reset() {
        initialized = false;
        lastFrame = 0;
        previousYaw = presentedYaw = 0;
        target = yaw = velocity = 0;
    }
}
