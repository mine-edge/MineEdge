package dev.mineedge.client.config;

import dev.mineedge.config.ServerMovementSettings;
import dev.mineedge.movement.MovementFeatures;
import dev.mineedge.network.ServerConfigPayload;
import java.util.Optional;
import java.util.function.Consumer;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MineEdgeConfigScreen {
    private MineEdgeConfigScreen() {
    }

    public static Screen create(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        var connection = minecraft.getConnection();
        boolean connected = connection != null && minecraft.player != null;
        boolean editable = connected && (minecraft.hasSingleplayerServer() || minecraft.player.hasPermissions(2));
        ServerMovementSettings initial = ClientServerConfig.settings();
        MovementDraft draft = new MovementDraft(initial);
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("mineedge.config.title"))
                .setSavingRunnable(() -> {
                    MineEdgeClientConfig.SPEC.save();
                    ServerMovementSettings updated = draft.settings();
                    if (editable && connection == minecraft.getConnection() && updated.isValid()
                            && !updated.equals(initial)) {
                        PacketDistributor.sendToServer(new ServerConfigPayload(updated));
                    }
                });
        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory movement = builder.getOrCreateCategory(
                Component.translatable("mineedge.config.category.server_movement"));
        movement.addEntry(entries.startTextDescription(Component.translatable(!connected
                ? "mineedge.config.server.unavailable" : editable
                ? "mineedge.config.server.editable" : "mineedge.config.server.read_only")).build());
        var general = entries.startSubCategory(Component.translatable("mineedge.config.server.general"))
                .setExpanded(true);
        addToggle(general, entries, "mineedge.config.server.enabled", initial.enabled(),
                ServerMovementSettings.DEFAULT.enabled(), editable, value -> draft.enabled = value);
        addToggle(general, entries, "mineedge.config.server.survival", initial.survival(),
                ServerMovementSettings.DEFAULT.survival(), editable, value -> draft.survival = value);
        addToggle(general, entries, "mineedge.config.server.adventure", initial.adventure(),
                ServerMovementSettings.DEFAULT.adventure(), editable, value -> draft.adventure = value);
        addToggle(general, entries, "mineedge.config.server.creative", initial.creative(),
                ServerMovementSettings.DEFAULT.creative(), editable, value -> draft.creative = value);
        movement.addEntry(general.build());
        var physics = entries.startSubCategory(Component.translatable("mineedge.config.server.physics_forces"))
                .setExpanded(true);
        addNumber(physics::add, entries, "mineedge.config.server.walk_speed", initial.walkSpeed(),
                ServerMovementSettings.DEFAULT.walkSpeed(), 0, ServerMovementSettings.MAX_SPEED, editable,
                value -> draft.walkSpeed = value);
        addNumber(physics::add, entries, "mineedge.config.server.run_speed_multiplier", initial.runSpeedMultiplier(),
                ServerMovementSettings.DEFAULT.runSpeedMultiplier(), 1, ServerMovementSettings.MAX_RUN_SPEED_MULTIPLIER, editable,
                value -> draft.runSpeedMultiplier = value);
        addNumber(physics::add, entries, "mineedge.config.server.jump_speed", initial.jumpSpeed(),
                ServerMovementSettings.DEFAULT.jumpSpeed(), 0, ServerMovementSettings.MAX_SPEED, editable,
                value -> draft.jumpSpeed = value);
        addNumber(physics::add, entries, "mineedge.config.server.gravity", initial.gravity(),
                ServerMovementSettings.DEFAULT.gravity(), 0, ServerMovementSettings.MAX_GRAVITY, editable,
                value -> draft.gravity = value);
        movement.addEntry(physics.build());
        var features = entries.startSubCategory(Component.translatable("mineedge.config.server.features"))
                .setExpanded(true);
        addToggle(features, entries, "mineedge.config.server.sliding", initial.features().sliding(),
                MovementFeatures.DEFAULT.sliding(), editable, value -> draft.sliding = value);
        addToggle(features, entries, "mineedge.config.server.wall_running", initial.features().wallRunning(),
                MovementFeatures.DEFAULT.wallRunning(), editable, value -> draft.wallRunning = value);
        addToggle(features, entries, "mineedge.config.server.vaulting", initial.features().vaulting(),
                MovementFeatures.DEFAULT.vaulting(), editable, value -> draft.vaulting = value);
        addToggle(features, entries, "mineedge.config.server.mantling", initial.features().mantling(),
                MovementFeatures.DEFAULT.mantling(), editable, value -> draft.mantling = value);
        addToggle(features, entries, "mineedge.config.server.pipe_climbing", initial.features().pipeClimbing(),
                MovementFeatures.DEFAULT.pipeClimbing(), editable, value -> draft.pipeClimbing = value);
        addToggle(features, entries, "mineedge.config.server.door_push", initial.features().doorPush(),
                MovementFeatures.DEFAULT.doorPush(), editable, value -> draft.doorPush = value);
        addToggle(features, entries, "mineedge.config.server.hard_landing", initial.features().hardLanding(),
                MovementFeatures.DEFAULT.hardLanding(), editable, value -> draft.hardLanding = value);
        movement.addEntry(features.build());
        var fallDamage = entries.startSubCategory(Component.translatable("mineedge.config.server.fall_damage"))
                .setExpanded(true);
        addNumber(fallDamage::add, entries, "mineedge.config.server.fall_damage_multiplier", initial.fallDamageMultiplier(),
                ServerMovementSettings.DEFAULT.fallDamageMultiplier(), 0, ServerMovementSettings.MAX_FALL_DAMAGE_MULTIPLIER, editable,
                value -> draft.fallDamageMultiplier = value);
        addNumber(fallDamage::add, entries, "mineedge.config.server.roll_damage_multiplier", 1 - initial.rollDamageReduction(),
                1 - ServerMovementSettings.DEFAULT.rollDamageReduction(), 0, 1, editable,
                value -> draft.rollDamageReduction = value == 1 - initial.rollDamageReduction()
                        ? initial.rollDamageReduction() : 1 - value);
        addNumber(fallDamage::add, entries, "mineedge.config.server.minimum_fall_height", initial.minimumFallHeight(),
                ServerMovementSettings.DEFAULT.minimumFallHeight(), 0, ServerMovementSettings.MAX_MINIMUM_FALL_HEIGHT, editable,
                value -> draft.minimumFallHeight = value);
        movement.addEntry(fallDamage.build());
        ConfigCategory camera = builder.getOrCreateCategory(
                Component.translatable("mineedge.config.category.camera"));

        camera.addEntry(entries.startTextDescription(
                Component.translatable("mineedge.config.description")).build());
        camera.addEntry(entries.startBooleanToggle(
                        Component.translatable("mineedge.config.camera_effects"),
                        MineEdgeClientConfig.CAMERA_EFFECTS.get())
                .setDefaultValue(MineEdgeClientConfig.CAMERA_EFFECTS.getDefault())
                .setTooltip(Component.translatable("mineedge.config.camera_effects.tooltip"))
                .setSaveConsumer(MineEdgeClientConfig.CAMERA_EFFECTS::set)
                .build());
        camera.addEntry(entries.startBooleanToggle(
                        Component.translatable("mineedge.config.camera_roll"),
                        MineEdgeClientConfig.CAMERA_ROLL.get())
                .setDefaultValue(MineEdgeClientConfig.CAMERA_ROLL.getDefault())
                .setTooltip(Component.translatable("mineedge.config.camera_roll.tooltip"))
                .setSaveConsumer(MineEdgeClientConfig.CAMERA_ROLL::set)
                .build());
        addNumber(camera, entries, "mineedge.config.roll_strength", MineEdgeClientConfig.ROLL_STRENGTH.get(),
                MineEdgeClientConfig.ROLL_STRENGTH.getDefault(), 1, true, MineEdgeClientConfig.ROLL_STRENGTH::set);
        addNumber(camera, entries, "mineedge.config.speed_fov", MineEdgeClientConfig.SPEED_FOV.get(),
                MineEdgeClientConfig.SPEED_FOV.getDefault(), 1, true, MineEdgeClientConfig.SPEED_FOV::set);
        addNumber(camera, entries, "mineedge.config.landing_strength", MineEdgeClientConfig.LANDING_STRENGTH.get(),
                MineEdgeClientConfig.LANDING_STRENGTH.getDefault(), 1, true, MineEdgeClientConfig.LANDING_STRENGTH::set);
        addNumber(camera, entries, "mineedge.config.shake_strength", MineEdgeClientConfig.SHAKE_STRENGTH.get(),
                MineEdgeClientConfig.SHAKE_STRENGTH.getDefault(), 1, true, MineEdgeClientConfig.SHAKE_STRENGTH::set);
        addNumber(camera, entries, "mineedge.config.sway_strength", MineEdgeClientConfig.SWAY_STRENGTH.get(),
                MineEdgeClientConfig.SWAY_STRENGTH.getDefault(), 1, true, MineEdgeClientConfig.SWAY_STRENGTH::set);
        addNumber(camera, entries, "mineedge.config.bob_strength", MineEdgeClientConfig.BOB_STRENGTH.get(),
                MineEdgeClientConfig.BOB_STRENGTH.getDefault(), 1, true, MineEdgeClientConfig.BOB_STRENGTH::set);
        addNumber(camera, entries, "mineedge.config.parkour_strength", MineEdgeClientConfig.PARKOUR_STRENGTH.get(),
                MineEdgeClientConfig.PARKOUR_STRENGTH.getDefault(), 1, true, MineEdgeClientConfig.PARKOUR_STRENGTH::set);
        ConfigCategory presentation = builder.getOrCreateCategory(Component.translatable("mineedge.config.category.presentation"));
        presentation.addEntry(entries.startBooleanToggle(Component.translatable("mineedge.config.third_person_animations"),
                        MineEdgeClientConfig.THIRD_PERSON_ANIMATIONS.get())
                .setDefaultValue(MineEdgeClientConfig.THIRD_PERSON_ANIMATIONS.getDefault())
                .setTooltip(Component.translatable("mineedge.config.third_person_animations.tooltip"))
                .setSaveConsumer(MineEdgeClientConfig.THIRD_PERSON_ANIMATIONS::set).build());
        presentation.addEntry(entries.startBooleanToggle(Component.translatable("mineedge.config.movement_sounds"),
                        MineEdgeClientConfig.MOVEMENT_SOUNDS.get())
                .setDefaultValue(MineEdgeClientConfig.MOVEMENT_SOUNDS.getDefault())
                .setSaveConsumer(MineEdgeClientConfig.MOVEMENT_SOUNDS::set).build());
        ConfigCategory diagnostics = builder.getOrCreateCategory(Component.translatable("mineedge.config.category.diagnostics"));
        diagnostics.addEntry(entries.startBooleanToggle(Component.translatable("mineedge.config.diagnostics"),
                        MineEdgeClientConfig.DIAGNOSTICS.get())
                .setDefaultValue(MineEdgeClientConfig.DIAGNOSTICS.getDefault())
                .setSaveConsumer(MineEdgeClientConfig.DIAGNOSTICS::set).build());

        return builder.build();
    }

    private static void addToggle(SubCategoryBuilder category, ConfigEntryBuilder entries, String key,
                                  boolean value, boolean defaultValue, boolean editable, Consumer<Boolean> save) {
        var entry = entries.startBooleanToggle(Component.translatable(key), value)
                .setDefaultValue(defaultValue)
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setSaveConsumer(save)
                .build();
        entry.setEditable(editable);
        category.add(entry);
    }

    private static void addNumber(ConfigCategory category, ConfigEntryBuilder entries, String key,
                                  double value, double defaultValue, double maximum, boolean editable,
                                  Consumer<Double> save) {
        addNumber(category::addEntry, entries, key, value, defaultValue, 0, maximum, editable, save);
    }

    private static void addNumber(Consumer<AbstractConfigListEntry<Double>> addEntry, ConfigEntryBuilder entries, String key,
                                  double value, double defaultValue, double minimum, double maximum, boolean editable,
                                  Consumer<Double> save) {
        var entry = entries.startDoubleField(Component.translatable(key), value)
                .setDefaultValue(defaultValue)
                .setMin(minimum).setMax(maximum)
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setErrorSupplier(number -> Double.isFinite(number) ? Optional.empty()
                        : Optional.of(Component.translatable("mineedge.config.server.invalid")))
                .setSaveConsumer(save)
                .build();
        entry.setEditable(editable);
        addEntry.accept(entry);
    }

    private static final class MovementDraft {
        private boolean enabled;
        private boolean survival;
        private boolean adventure;
        private boolean creative;
        private double walkSpeed;
        private double runSpeedMultiplier;
        private double jumpSpeed;
        private double gravity;
        private double rollDamageReduction;
        private double fallDamageMultiplier;
        private double minimumFallHeight;
        private boolean sliding;
        private boolean wallRunning;
        private boolean vaulting;
        private boolean mantling;
        private boolean pipeClimbing;
        private boolean doorPush;
        private boolean hardLanding;

        private MovementDraft(ServerMovementSettings settings) {
            enabled = settings.enabled();
            survival = settings.survival();
            adventure = settings.adventure();
            creative = settings.creative();
            walkSpeed = settings.walkSpeed();
            runSpeedMultiplier = settings.runSpeedMultiplier();
            jumpSpeed = settings.jumpSpeed();
            gravity = settings.gravity();
            rollDamageReduction = settings.rollDamageReduction();
            fallDamageMultiplier = settings.fallDamageMultiplier();
            minimumFallHeight = settings.minimumFallHeight();
            sliding = settings.features().sliding();
            wallRunning = settings.features().wallRunning();
            vaulting = settings.features().vaulting();
            mantling = settings.features().mantling();
            pipeClimbing = settings.features().pipeClimbing();
            doorPush = settings.features().doorPush();
            hardLanding = settings.features().hardLanding();
        }

        private ServerMovementSettings settings() {
            return new ServerMovementSettings(enabled, survival, adventure, creative,
                    walkSpeed, jumpSpeed, gravity, rollDamageReduction, runSpeedMultiplier, fallDamageMultiplier, minimumFallHeight,
                    new MovementFeatures(sliding, wallRunning, vaulting, mantling, pipeClimbing, doorPush, hardLanding));
        }
    }
}
