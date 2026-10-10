package dev.mineedge.movement.traversal;

/** Ordered faith_move/vault.rs VaultTypes in meters/seconds. Half-block thresholds adapt .48/.64 to .5. */
public enum VaultType {
    LOW_STEP(true, 0, 0.5, -6, 0, Double.POSITIVE_INFINITY, 1, 3, 0, 0, 0.3, 0.2),
    SLOW_MANTLE(true, 0.5, 1.48, 0, 7, 2, 2, 7, 0, 0, 0.4, 0.25),
    MANTLE(true, 0.5, 1.48, 0, 100, Double.POSITIVE_INFINITY, 4, 7.2, 0.8, 0, 0.35, 0.3),
    VAULT(false, 0.5, 1.48, 0, 100, Double.POSITIVE_INFINITY, 4, 7.2, 0.8, 0, 0.35, 0.3),
    HIGH_VAULT(false, 1.45, 1.92, 0.5, 100, Double.POSITIVE_INFINITY, 2, 4, 0, 0.28, 0.3, 0.45),
    HIGH_MANTLE(true, 1.45, 1.92, 0.5, 100, Double.POSITIVE_INFINITY, 2, 4, 0, 0.27, 0.3, 0.6),
    PULL_UP(true, 0, 0, 0, 0, 0, 0, 0, 0, 0.30, 0.20, 0.05),
    SPRINGBOARD(true, 0, 0, 0, 0, 0, 0, 0, 0, 0.16, 0.20, 0.04);

    private final boolean onto;
    private final double minHeight, maxHeight, minVertical, maxVertical, maxMomentum;
    private final double minSpeed, maxSpeed, addition, up, over, down;

    VaultType(boolean onto, double minHeight, double maxHeight, double minVertical, double maxVertical,
              double maxMomentum, double minSpeed, double maxSpeed, double addition, double up, double over, double down) {
        this.onto = onto;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.minVertical = minVertical;
        this.maxVertical = maxVertical;
        this.maxMomentum = maxMomentum;
        this.minSpeed = minSpeed;
        this.maxSpeed = maxSpeed;
        this.addition = addition;
        this.up = up;
        this.over = over;
        this.down = down;
    }

    public boolean onto() { return onto; }

    public boolean requiresJumpHold() { return onto && this != SPRINGBOARD; }

    public boolean accepts(boolean onto, double height, double speed, double vertical) {
        return this != PULL_UP && this != SPRINGBOARD && (this.onto == onto || this == LOW_STEP) && height >= minHeight - 1.0E-6 && height <= maxHeight + 1.0E-6
                && speed <= maxMomentum && vertical >= minVertical && vertical <= maxVertical;
    }

    public double exitSpeed(double entrySpeed) { return Math.clamp(entrySpeed + addition, minSpeed, maxSpeed); }

    // Native reference offsets assume an animation-coiled cylinder and SetPreciseLocation.
    // Minecraft AABBs must rise outside first; split a zero-up over phase without extending total time.
    public double upSeconds() { return up > 0 ? up : over * 0.55; }
    public double overSeconds() { return up > 0 ? over : over * 0.45; }
    public double downSeconds() { return down; }
    public double duration() { return up + over + down; }
}
