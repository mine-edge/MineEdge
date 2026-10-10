package dev.mineedge.movement.surface;

import dev.mineedge.movement.MovementVector;

/** A bounded contiguous tagged column; base anchors its attachment and source is its bottom block. */
public record ClimbRoute(MovementVector base, double top, MovementVector normal, boolean pipe, MovementVector source) {
    public static final double STEP = 0.32;
    // A 0.5-wide stone-wall post plus the player's 0.6-wide AABB needs
    // at least sqrt(2) * (0.25 + 0.3) clearance at its diagonal corners.
    public static final double PIPE_RADIUS = 0.8;
    public double lastHeight() { return Math.max(base.y(), top - (pipe ? 0.575 : 0.952)); }
    public int lastStep() { return Math.max(0, (int) Math.floor((lastHeight() - base.y()) / STEP)); }
    public double height(int step) { return lastHeight() - STEP * (lastStep() - Math.clamp(step, 0, lastStep())); }
    public int closest(double height) { return Math.clamp((int) Math.round((height - height(0)) / STEP), 0, lastStep()); }
    public MovementVector feet(double height) {
        return new MovementVector(base.x(), height, base.z()).add(normal.scale(pipe ? PIPE_RADIUS : 0.5));
    }
    public ClimbRoute orbit(double distance) {
        double angle = distance / PIPE_RADIUS, sin = Math.sin(angle), cos = Math.cos(angle);
        var rotated = new MovementVector(normal.x() * cos + normal.z() * sin, 0,
                normal.z() * cos - normal.x() * sin).normalized();
        return new ClimbRoute(base, top, rotated, pipe, source);
    }
}
