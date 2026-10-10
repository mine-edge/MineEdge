package dev.mineedge.movement.surface;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.CollisionBox;

/** Collision-checked rise/cross/settle paths for knee steps and climb entry/exit. */
public record SurfacePath(Kind kind, MovementVector from, MovementVector lift, MovementVector over,
                          MovementVector end, MovementVector exitVelocity, CollisionBox support,
                          MovementVector source, double duration, double elapsed, MovementBody body) {
    public enum Kind { STEP_UP, ATTACH, ENTER_TOP, EXIT_TOP }
    public MovementVector position(double time) {
        double k = Math.clamp(time / duration, 0, 1);
        if (k < 0.4) return lerp(from, lift, k / 0.4);
        if (k < 0.9) return lerp(lift, over, (k - 0.4) / 0.5);
        return lerp(over, end, (k - 0.9) / 0.1);
    }
    public SurfacePath at(double time) { return new SurfacePath(kind, from, lift, over, end, exitVelocity, support, source, duration, time, body); }
    private static MovementVector lerp(MovementVector a, MovementVector b, double k) { return a.add(b.subtract(a).scale(Math.clamp(k, 0, 1))); }
}
