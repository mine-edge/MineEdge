package dev.mineedge.movement;

/** Shared fixed game-time frequency for input updates, prediction and authoritative movement. */
public final class MovementRate {
    public static final int HZ = 20;
    /** Number of physics substeps within each input/simulation step. */
    public static final int SUBSTEPS = 5;
    public static final long INTERVAL_NANOS = 1_000_000_000L / HZ;
    public static final double STEP_SECONDS = 1.0 / HZ;
    public static final int SUBSTEP_HZ = HZ * SUBSTEPS;
    public static final int GAME_TICK_HZ = 20;
    public static final int STEPS_PER_GAME_TICK = HZ / GAME_TICK_HZ;

    static {
        if (HZ < GAME_TICK_HZ || HZ % GAME_TICK_HZ != 0 || SUBSTEPS < 1) {
            throw new IllegalStateException("Movement rate must be a multiple of the game tick rate with at least one substep");
        }
    }

    /** Wall-clock interval scaled by Minecraft's configured tick rate, not measured server TPS. */
    public static long intervalNanos(float tickRate) {
        return Math.max(1, (long) (INTERVAL_NANOS * (double) GAME_TICK_HZ / Math.max(1, tickRate)));
    }

    private MovementRate() {}
}
