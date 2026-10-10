package dev.mineedge.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MineEdgeClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CAMERA_EFFECTS, CAMERA_ROLL;
    public static final ModConfigSpec.DoubleValue ROLL_STRENGTH, SPEED_FOV, LANDING_STRENGTH, SHAKE_STRENGTH;
    public static final ModConfigSpec.DoubleValue SWAY_STRENGTH, BOB_STRENGTH, PARKOUR_STRENGTH;
    public static final ModConfigSpec.BooleanValue MOVEMENT_SOUNDS, DIAGNOSTICS;
    public static final ModConfigSpec.BooleanValue THIRD_PERSON_ANIMATIONS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.translation("mineedge.config.category.camera").push("camera");
        CAMERA_EFFECTS = builder.comment("Enable MineEdge cosmetic first-person camera effects.")
                .translation("mineedge.config.camera_effects").define("enabled", true);
        CAMERA_ROLL = builder
                .comment("Allow all cosmetic camera roll, including wallrun, slide, lean and parkour feedback.")
                .translation("mineedge.config.camera_roll")
                .define("cameraRoll", true);
        ROLL_STRENGTH = builder.comment("Camera roll strength (0 disables).").defineInRange("rollStrength", 1.0, 0.0, 1.0);
        SPEED_FOV = builder.comment("Additional speed FOV strength (0 disables).").defineInRange("speedFov", 1.0, 0.0, 1.0);
        LANDING_STRENGTH = builder.comment("Landing dip, pitch kick and hard-landing FOV decrease strength (0 disables).").defineInRange("landingStrength", 1.0, 0.0, 1.0);
        SHAKE_STRENGTH = builder.comment("Optional movement shake strength (0 disables).").defineInRange("shakeStrength", 0.0, 0.0, 1.0);
        SWAY_STRENGTH = builder.comment("Direction-aware body inertia, sideways lean and airborne pitch strength (0 disables).")
                .defineInRange("swayStrength", 0.6, 0.0, 1.0);
        BOB_STRENGTH = builder.comment("Additional ground-distance footfall bob strength (0 disables). Respects Minecraft View Bobbing.")
                .defineInRange("bobStrength", 0.5, 0.0, 1.0);
        PARKOUR_STRENGTH = builder.comment("Smooth parkour posture and jump, vault, mantle, roll, turn, climb and grab feedback strength (0 disables).")
                .defineInRange("parkourStrength", 0.7, 0.0, 1.0);
        builder.pop();
        builder.push("presentation");
        THIRD_PERSON_ANIMATIONS = builder.comment("Animate third-person parkour poses. Disable when another mod controls player animations.")
                .translation("mineedge.config.third_person_animations").define("thirdPersonAnimations", true);
        MOVEMENT_SOUNDS = builder.comment("Play local predicted parkour sounds.").define("movementSounds", true);
        builder.pop();
        builder.push("diagnostics");
        DIAGNOSTICS = builder.comment("Show movement diagnostics with the F3 overlay.").define("enabled", false);
        builder.pop();
        SPEC = builder.build();
    }

    private MineEdgeClientConfig() {
    }
}
