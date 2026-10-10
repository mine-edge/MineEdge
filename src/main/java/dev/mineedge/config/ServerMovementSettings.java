package dev.mineedge.config;

import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementFeatures;
import net.minecraft.world.level.GameType;

/** Server-owned baseline values, distinct from each player's attribute-scaled tuning. */
public record ServerMovementSettings(boolean enabled, boolean survival, boolean adventure, boolean creative,
                                     double walkSpeed, double jumpSpeed, double gravity, double rollDamageReduction,
                                     double runSpeedMultiplier, double fallDamageMultiplier, double minimumFallHeight,
                                     MovementFeatures features) {
    public static final double MAX_SPEED = 20;
    public static final double MAX_GRAVITY = 100;
    public static final double MAX_RUN_SPEED_MULTIPLIER = MovementTuning.MAX_RUN_SPEED_MULTIPLIER;
    public static final double MAX_FALL_DAMAGE_MULTIPLIER = MovementTuning.MAX_FALL_DAMAGE_MULTIPLIER;
    public static final double MAX_MINIMUM_FALL_HEIGHT = MovementTuning.MAX_MINIMUM_FALL_HEIGHT;
    public static final ServerMovementSettings DEFAULT = new ServerMovementSettings(true, 4.317, 7, 20.0);

    public ServerMovementSettings(boolean enabled, double walkSpeed, double jumpSpeed, double gravity) {
        this(enabled, walkSpeed, jumpSpeed, gravity, 0.5);
    }

    public ServerMovementSettings(boolean enabled, double walkSpeed, double jumpSpeed, double gravity, double rollDamageReduction) {
        this(enabled, true, true, false, walkSpeed, jumpSpeed, gravity, rollDamageReduction);
    }

    public ServerMovementSettings(boolean enabled, boolean survival, boolean adventure, boolean creative,
                                  double walkSpeed, double jumpSpeed, double gravity, double rollDamageReduction) {
        this(enabled, survival, adventure, creative, walkSpeed, jumpSpeed, gravity, rollDamageReduction,
                MovementTuning.DEFAULT_RUN_SPEED_MULTIPLIER);
    }

    public ServerMovementSettings(boolean enabled, boolean survival, boolean adventure, boolean creative,
                                  double walkSpeed, double jumpSpeed, double gravity, double rollDamageReduction,
                                  double runSpeedMultiplier) {
        this(enabled, survival, adventure, creative, walkSpeed, jumpSpeed, gravity, rollDamageReduction,
                runSpeedMultiplier, MovementTuning.DEFAULT_FALL_DAMAGE_MULTIPLIER, MovementTuning.DEFAULT_MINIMUM_FALL_HEIGHT);
    }

    public ServerMovementSettings(boolean enabled, boolean survival, boolean adventure, boolean creative,
                                  double walkSpeed, double jumpSpeed, double gravity, double rollDamageReduction,
                                  double runSpeedMultiplier, double fallDamageMultiplier, double minimumFallHeight) {
        this(enabled, survival, adventure, creative, walkSpeed, jumpSpeed, gravity, rollDamageReduction,
                runSpeedMultiplier, fallDamageMultiplier, minimumFallHeight, MovementFeatures.DEFAULT);
    }

    public boolean enabledFor(GameType gameType) {
        return enabled && switch (gameType) {
            case SURVIVAL -> survival;
            case ADVENTURE -> adventure;
            case CREATIVE -> creative;
            case SPECTATOR -> false;
        };
    }

    public boolean isValid() {
        return inRange(walkSpeed, MAX_SPEED) && inRange(jumpSpeed, MAX_SPEED) && inRange(gravity, MAX_GRAVITY)
                && inRange(rollDamageReduction, 1)
                && inRange(runSpeedMultiplier, MAX_RUN_SPEED_MULTIPLIER) && runSpeedMultiplier >= 1
                && inRange(fallDamageMultiplier, MAX_FALL_DAMAGE_MULTIPLIER)
                && inRange(minimumFallHeight, MAX_MINIMUM_FALL_HEIGHT) && features != null;
    }

    private static boolean inRange(double value, double maximum) {
        return Double.isFinite(value) && value >= 0 && value <= maximum;
    }
}
