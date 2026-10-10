package dev.mineedge.movement;

/** Pending Minecraft stuck-speed multiplier, consumed by the next resolved substep. */
public record BlockMotionState(MovementVector stuckMultiplier) {
    public static final BlockMotionState EMPTY = new BlockMotionState(MovementVector.ZERO);
}
