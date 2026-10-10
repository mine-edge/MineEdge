package dev.mineedge.movement;

/** Replay-relevant timers and sprint energy (blocks/second above base running speed). */
public record LocomotionState(double energy, double accelerationTime, double stoppingTime,
                              double jumpBuffer, float previousYaw, boolean yawInitialized) {
    public static final LocomotionState EMPTY = new LocomotionState(0, 0, 0, 0, 0, false);
}
