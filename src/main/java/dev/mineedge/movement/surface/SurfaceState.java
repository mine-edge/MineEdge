package dev.mineedge.movement.surface;

import dev.mineedge.movement.MovementVector;

/** Complete replay state for climbing, knee steps and tagged steep-surface slides. */
public record SurfaceState(Climb climb, SurfacePath path, double climbCooldown, double stepCooldown,
                           double slopeCooldown, double slopeAir, double stalled, MovementVector downhill,
                           float slopeYaw, MovementVector expectedVelocity) {
    public static final SurfaceState EMPTY = new SurfaceState(null, null, 0, 0, 0, 0, 0, MovementVector.ZERO, 0, MovementVector.ZERO);
    public record Climb(ClimbRoute route, MovementVector anchor, double hold, double from, double to,
                        double elapsed, double duration, boolean fast) {}
    public SurfaceState advance(double dt) {
        double grab = Math.max(0, climbCooldown - dt), step = Math.max(0, stepCooldown - dt), slope = Math.max(0, slopeCooldown - dt);
        if (climb == null && path == null && grab == 0 && step == 0 && slope == 0 && downhill.horizontalSpeed() == 0) return EMPTY;
        return new SurfaceState(climb, path, grab, step, slope, slopeAir, stalled, downhill, slopeYaw, expectedVelocity);
    }
    public SurfaceState climbing(Climb value, SurfacePath motion, MovementVector expected) {
        return new SurfaceState(value, motion, climbCooldown, stepCooldown, slopeCooldown, 0, 0, MovementVector.ZERO, 0, canonical(expected));
    }
    public SurfaceState path(SurfacePath value, MovementVector expected) { return climbing(climb, value, expected); }
    public SurfaceState release(boolean ladder, MovementVector expected) {
        return new SurfaceState(null, null, ladder ? 0.5 : climbCooldown, ladder ? stepCooldown : 0.2,
                slopeCooldown, 0, 0, MovementVector.ZERO, 0, canonical(expected));
    }
    public SurfaceState slope(MovementVector direction, float yaw, double air, double stopped, MovementVector expected) {
        return new SurfaceState(null, null, climbCooldown, stepCooldown, slopeCooldown, air, stopped, direction, yaw, canonical(expected));
    }
    public SurfaceState slopeExit() {
        return new SurfaceState(null, null, climbCooldown, stepCooldown, 1, 0, 0, MovementVector.ZERO, 0, MovementVector.ZERO);
    }
    private static MovementVector canonical(MovementVector v) {
        return new MovementVector(v.x() == 0 ? 0 : v.x(), v.y() == 0 ? 0 : v.y(), v.z() == 0 ? 0 : v.z());
    }
}
