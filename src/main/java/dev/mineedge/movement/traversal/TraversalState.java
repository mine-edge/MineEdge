package dev.mineedge.movement.traversal;

import dev.mineedge.movement.MovementVector;

/** Shared transient/replay state; expected velocity detects imported external impulses. */
public record TraversalState(TraversalPath path, double cooldown, MovementVector expectedVelocity, MovementVector entryVelocity) {
    public static final TraversalState EMPTY = new TraversalState(null, 0, MovementVector.ZERO);
    public static final double RETRIGGER_SECONDS = 0.2;

    public TraversalState(TraversalPath path, double cooldown, MovementVector expectedVelocity) {
        this(path, cooldown, expectedVelocity, path == null ? MovementVector.ZERO : expectedVelocity);
    }

    public TraversalState advance(double dt) {
        return cooldown == 0 ? this : new TraversalState(path, Math.max(0, cooldown - dt), expectedVelocity, entryVelocity);
    }

    public TraversalState {
        if (!Double.isFinite(cooldown) || cooldown < 0 || cooldown > RETRIGGER_SECONDS) {
            throw new IllegalArgumentException("Invalid traversal cooldown");
        }
        if (entryVelocity == null || !Double.isFinite(entryVelocity.x()) || !Double.isFinite(entryVelocity.y())
                || !Double.isFinite(entryVelocity.z())) throw new IllegalArgumentException("Invalid traversal entry velocity");
    }
}
