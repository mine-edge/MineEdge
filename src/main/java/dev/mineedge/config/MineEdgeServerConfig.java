package dev.mineedge.config;

import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementFeatures;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class MineEdgeServerConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue SURVIVAL;
    public static final ModConfigSpec.BooleanValue ADVENTURE;
    public static final ModConfigSpec.BooleanValue CREATIVE;
    public static final ModConfigSpec.DoubleValue WALK_SPEED;
    public static final ModConfigSpec.DoubleValue RUN_SPEED_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue JUMP_SPEED;
    public static final ModConfigSpec.DoubleValue GRAVITY;
    public static final ModConfigSpec.DoubleValue ROLL_DAMAGE_REDUCTION;
    public static final ModConfigSpec.DoubleValue FALL_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue MINIMUM_FALL_HEIGHT;
    public static final ModConfigSpec.BooleanValue SLIDING;
    public static final ModConfigSpec.BooleanValue WALL_RUNNING;
    public static final ModConfigSpec.BooleanValue VAULTING;
    public static final ModConfigSpec.BooleanValue MANTLING;
    public static final ModConfigSpec.BooleanValue PIPE_CLIMBING;
    public static final ModConfigSpec.BooleanValue DOOR_PUSH;
    public static final ModConfigSpec.BooleanValue HARD_LANDING;
    public static final ModConfigSpec.IntValue SNAPSHOT_INTERVAL_TICKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("movement");
        ENABLED = builder.comment("Enable predicted/server-authoritative MineEdge locomotion.")
                .define("enabled", ServerMovementSettings.DEFAULT.enabled());
        SURVIVAL = builder.comment("Enable MineEdge movement in Survival when the master switch is enabled.")
                .define("survival", ServerMovementSettings.DEFAULT.survival());
        ADVENTURE = builder.comment("Enable MineEdge movement in Adventure when the master switch is enabled.")
                .define("adventure", ServerMovementSettings.DEFAULT.adventure());
        CREATIVE = builder.comment("Enable MineEdge movement in Creative when the master switch is enabled. Flying still uses vanilla movement.")
                .define("creative", ServerMovementSettings.DEFAULT.creative());
        WALK_SPEED = builder.comment("Baseline walking speed, blocks/second (before Minecraft attributes).")
                .defineInRange("walkSpeed", ServerMovementSettings.DEFAULT.walkSpeed(), 0.0, ServerMovementSettings.MAX_SPEED);
        RUN_SPEED_MULTIPLIER = builder.comment("Running top speed as a multiplier of walking speed. Scales the sprint acceleration curve.")
                .defineInRange("runSpeedMultiplier", ServerMovementSettings.DEFAULT.runSpeedMultiplier(), 1.0,
                        ServerMovementSettings.MAX_RUN_SPEED_MULTIPLIER);
        JUMP_SPEED = builder.comment("Baseline jump speed, blocks/second (before Minecraft jump power).")
                .defineInRange("jumpSpeed", ServerMovementSettings.DEFAULT.jumpSpeed(), 0.0, ServerMovementSettings.MAX_SPEED);
        GRAVITY = builder.comment("Baseline gravity, blocks/second squared (before Minecraft gravity attributes).")
                .defineInRange("gravity", ServerMovementSettings.DEFAULT.gravity(), 0.0, ServerMovementSettings.MAX_GRAVITY);
        ROLL_DAMAGE_REDUCTION = builder.comment("Fraction of vanilla fall damage multiplier removed by a correctly timed landing roll (0 disables mitigation, 1 removes damage). Applied before vanilla armor/enchantment reduction.")
                .defineInRange("rollDamageReduction", ServerMovementSettings.DEFAULT.rollDamageReduction(), 0.0, 1.0);
        FALL_DAMAGE_MULTIPLIER = builder.comment("Base fall damage multiplier for managed MineEdge movement (0 disables damage, 1 preserves vanilla damage). Composes with roll, block, attribute and enchantment modifiers.")
                .defineInRange("fallDamageMultiplier", ServerMovementSettings.DEFAULT.fallDamageMultiplier(), 0.0,
                        ServerMovementSettings.MAX_FALL_DAMAGE_MULTIPLIER);
        MINIMUM_FALL_HEIGHT = builder.comment("Baseline safe fall height in blocks before damage begins (3 preserves vanilla). Minecraft safe-fall attribute and Jump Boost modifiers still apply.")
                .defineInRange("minimumFallHeight", ServerMovementSettings.DEFAULT.minimumFallHeight(), 0.0,
                        ServerMovementSettings.MAX_MINIMUM_FALL_HEIGHT);
        SNAPSHOT_INTERVAL_TICKS = builder.comment("Ticks between routine active movement snapshots (2 = 10 Hz at 20 TPS). Mode/pose/lifecycle changes send immediately; session settings send only on change; physics always runs every tick.")
                .defineInRange("snapshotIntervalTicks", 2, 1, 5);
        builder.push("features");
        SLIDING = builder.comment("Enable crouch-triggered, landing and steep-slope slides.")
                .define("sliding", MovementFeatures.DEFAULT.sliding());
        WALL_RUNNING = builder.comment("Enable running along walls. Wall climbing and ledge hanging remain separate movement actions.")
                .define("wallRunning", MovementFeatures.DEFAULT.wallRunning());
        VAULTING = builder.comment("Enable vaulting over obstacles and springboards.")
                .define("vaulting", MovementFeatures.DEFAULT.vaulting());
        MANTLING = builder.comment("Enable mantling onto obstacles and pulling up from ledges.")
                .define("mantling", MovementFeatures.DEFAULT.mantling());
        PIPE_CLIMBING = builder.comment("Enable grabbing, climbing and circling tagged pipes. Ladder climbing is independent.")
                .define("pipeClimbing", MovementFeatures.DEFAULT.pipeClimbing());
        DOOR_PUSH = builder.comment("Enable automatically opening closed, hand-openable doors when sprinting into them on the ground.")
                .define("doorPush", MovementFeatures.DEFAULT.doorPush());
        HARD_LANDING = builder.comment("Enable recovery after large falls, with half-speed walking and no sprinting or jumping on hard ground.")
                .define("hardLanding", MovementFeatures.DEFAULT.hardLanding());
        builder.pop();
        builder.pop();
        SPEC = builder.build();
    }

    public static MovementTuning tuning() {
        return new MovementTuning(WALK_SPEED.get(), JUMP_SPEED.get(), GRAVITY.get(),
                MovementTuning.DEFAULT.groundAcceleration(), MovementTuning.DEFAULT.airAcceleration(), 5,
                MovementTuning.DEFAULT.crouchSpeedModifier(), ROLL_DAMAGE_REDUCTION.get(), RUN_SPEED_MULTIPLIER.get(),
                FALL_DAMAGE_MULTIPLIER.get(), MINIMUM_FALL_HEIGHT.get(), features());
    }

    public static ServerMovementSettings settings() {
        return new ServerMovementSettings(ENABLED.get(), SURVIVAL.get(), ADVENTURE.get(), CREATIVE.get(),
                WALK_SPEED.get(), JUMP_SPEED.get(), GRAVITY.get(), ROLL_DAMAGE_REDUCTION.get(), RUN_SPEED_MULTIPLIER.get(),
                FALL_DAMAGE_MULTIPLIER.get(), MINIMUM_FALL_HEIGHT.get(), features());
    }

    public static MovementFeatures features() {
        return new MovementFeatures(SLIDING.get(), WALL_RUNNING.get(), VAULTING.get(), MANTLING.get(), PIPE_CLIMBING.get(), DOOR_PUSH.get(), HARD_LANDING.get());
    }

    public static boolean enabledFor(GameType gameType) {
        return settings().enabledFor(gameType);
    }

    /** Called on the server thread after authorization; saves the currently loaded config/override. */
    public static void saveSettings(ServerMovementSettings settings) {
        ServerMovementSettings previous = settings();
        setValues(settings);
        try {
            SPEC.save();
        } catch (RuntimeException exception) {
            setValues(previous);
            throw exception;
        }
    }

    /** Also used to update a remote client's in-memory SERVER config from an authoritative payload. */
    public static void setValues(ServerMovementSettings settings) {
        if (!settings.isValid()) {
            throw new IllegalArgumentException("Invalid server movement settings");
        }
        ENABLED.set(settings.enabled());
        SURVIVAL.set(settings.survival());
        ADVENTURE.set(settings.adventure());
        CREATIVE.set(settings.creative());
        WALK_SPEED.set(settings.walkSpeed());
        RUN_SPEED_MULTIPLIER.set(settings.runSpeedMultiplier());
        JUMP_SPEED.set(settings.jumpSpeed());
        GRAVITY.set(settings.gravity());
        ROLL_DAMAGE_REDUCTION.set(settings.rollDamageReduction());
        FALL_DAMAGE_MULTIPLIER.set(settings.fallDamageMultiplier());
        MINIMUM_FALL_HEIGHT.set(settings.minimumFallHeight());
        SLIDING.set(settings.features().sliding());
        WALL_RUNNING.set(settings.features().wallRunning());
        VAULTING.set(settings.features().vaulting());
        MANTLING.set(settings.features().mantling());
        PIPE_CLIMBING.set(settings.features().pipeClimbing());
        DOOR_PUSH.set(settings.features().doorPush());
        HARD_LANDING.set(settings.features().hardLanding());
    }

    private MineEdgeServerConfig() {}
}
