package dev.mineedge.movement;

/** Immutable effective tuning; distances in blocks, times in seconds. */
public record MovementTuning(double walkSpeed, double jumpSpeed, double gravity,
                             double groundAcceleration, double airAcceleration, int staleInputTicks,
                             double crouchSpeedModifier, double rollDamageReduction, double runSpeedMultiplier,
                             double fallDamageMultiplier, double minimumFallHeight, MovementFeatures features) {
    public static final double DEFAULT_RUN_SPEED_MULTIPLIER = 1.5;
    public static final double MAX_RUN_SPEED_MULTIPLIER = 5;
    public static final double DEFAULT_FALL_DAMAGE_MULTIPLIER = 1;
    public static final double DEFAULT_MINIMUM_FALL_HEIGHT = 4;
    public static final double MAX_FALL_DAMAGE_MULTIPLIER = 10;
    public static final double MAX_MINIMUM_FALL_HEIGHT = 256;
    // Native-tick helpers remain useful for block callbacks and game-test fixtures.
    public static final int SUBSTEPS = MovementRate.SUBSTEP_HZ / MovementRate.GAME_TICK_HZ;
    public static final double SUBSTEP_SECONDS = 1.0 / MovementRate.SUBSTEP_HZ;
    public static final double TICK_SECONDS = 1.0 / MovementRate.GAME_TICK_HZ;
    // faith_move/src/locomotion.rs: AccelRate=61.44; tuning.rs: AirControl=0.025.
    public static final MovementTuning DEFAULT = new MovementTuning(4.317, 7, 20.0, 61.44, 0.025 * 61.44, 5);
    public static final double JUMP_BUFFER_SECONDS = 0.15;

    public double sprintSpeed() { return walkSpeed * runSpeedMultiplier; }

    public MovementTuning(double walkSpeed, double jumpSpeed, double gravity, double groundAcceleration,
                          double airAcceleration, int staleInputTicks, double crouchSpeedModifier, double rollDamageReduction,
                          double runSpeedMultiplier, double fallDamageMultiplier, double minimumFallHeight) {
        this(walkSpeed, jumpSpeed, gravity, groundAcceleration, airAcceleration, staleInputTicks,
                crouchSpeedModifier, rollDamageReduction, runSpeedMultiplier, fallDamageMultiplier, minimumFallHeight,
                MovementFeatures.DEFAULT);
    }

    public MovementTuning withFeatures(MovementFeatures features) {
        return new MovementTuning(walkSpeed, jumpSpeed, gravity, groundAcceleration, airAcceleration, staleInputTicks,
                crouchSpeedModifier, rollDamageReduction, runSpeedMultiplier, fallDamageMultiplier, minimumFallHeight, features);
    }

    public MovementTuning(double walkSpeed, double jumpSpeed, double gravity, double groundAcceleration,
                          double airAcceleration, int staleInputTicks, double crouchSpeedModifier, double rollDamageReduction,
                          double runSpeedMultiplier) {
        this(walkSpeed, jumpSpeed, gravity, groundAcceleration, airAcceleration, staleInputTicks,
                crouchSpeedModifier, rollDamageReduction, runSpeedMultiplier,
                DEFAULT_FALL_DAMAGE_MULTIPLIER, DEFAULT_MINIMUM_FALL_HEIGHT);
    }

    public MovementTuning(double walkSpeed, double jumpSpeed, double gravity, double groundAcceleration,
                          double airAcceleration, int staleInputTicks, double crouchSpeedModifier, double rollDamageReduction) {
        this(walkSpeed, jumpSpeed, gravity, groundAcceleration, airAcceleration, staleInputTicks,
                crouchSpeedModifier, rollDamageReduction, DEFAULT_RUN_SPEED_MULTIPLIER);
    }

    public MovementTuning(double walkSpeed, double jumpSpeed, double gravity, double groundAcceleration,
                          double airAcceleration, int staleInputTicks, double crouchSpeedModifier) {
        this(walkSpeed, jumpSpeed, gravity, groundAcceleration, airAcceleration, staleInputTicks, crouchSpeedModifier, 0.5);
    }

    public MovementTuning(double walkSpeed, double jumpSpeed, double gravity, double groundAcceleration,
                          double airAcceleration, int staleInputTicks) {
        // faith_move/tuning.rs: crouch_speed_modifier = 0.2 of GroundSpeed.
        this(walkSpeed, jumpSpeed, gravity, groundAcceleration, airAcceleration, staleInputTicks, 0.2);
    }

    public MovementTuning {
        if (!valid(walkSpeed) || !valid(jumpSpeed) || !valid(gravity)
                || !valid(groundAcceleration) || !valid(airAcceleration) || !valid(crouchSpeedModifier)
                || !Double.isFinite(rollDamageReduction) || rollDamageReduction < 0 || rollDamageReduction > 1
                || !Double.isFinite(runSpeedMultiplier) || runSpeedMultiplier < 1 || runSpeedMultiplier > MAX_RUN_SPEED_MULTIPLIER
                || !valid(fallDamageMultiplier) || fallDamageMultiplier > MAX_FALL_DAMAGE_MULTIPLIER
                || !valid(minimumFallHeight) || minimumFallHeight > MAX_MINIMUM_FALL_HEIGHT
                || features == null || staleInputTicks < 1 || staleInputTicks > 100) {
            throw new IllegalArgumentException("Invalid movement tuning");
        }
    }

    private static boolean valid(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1000;
    }
}
