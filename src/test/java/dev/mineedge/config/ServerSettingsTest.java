package dev.mineedge.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.mineedge.movement.MovementFeatures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerSettingsTest {
    @TempDir Path directory;

    @AfterEach
    void unloadTestConfig() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Test
    void defaultGameModesEnableSurvivalAndAdventureOnly() {
        assertTrue(ServerMovementSettings.DEFAULT.enabledFor(GameType.SURVIVAL));
        assertTrue(ServerMovementSettings.DEFAULT.enabledFor(GameType.ADVENTURE));
        assertFalse(ServerMovementSettings.DEFAULT.enabledFor(GameType.CREATIVE));
        assertFalse(ServerMovementSettings.DEFAULT.enabledFor(GameType.SPECTATOR));
    }

    @Test
    void masterSwitchGatesEveryGameModeToggle() {
        for (int flags = 0; flags < 16; flags++) {
            boolean enabled = (flags & 1) != 0;
            boolean survival = (flags & 2) != 0;
            boolean adventure = (flags & 4) != 0;
            boolean creative = (flags & 8) != 0;
            var settings = new ServerMovementSettings(enabled, survival, adventure, creative, 4, 6.3, 16.4, 0.5);
            assertEquals(enabled && survival, settings.enabledFor(GameType.SURVIVAL));
            assertEquals(enabled && adventure, settings.enabledFor(GameType.ADVENTURE));
            assertEquals(enabled && creative, settings.enabledFor(GameType.CREATIVE));
            assertFalse(settings.enabledFor(GameType.SPECTATOR));
        }
    }

    @Test
    void unauthorizedRequestCannotMutateOrSaveSettings() {
        var request = new ServerMovementSettings(true, 8, 10, 25);
        assertEquals(ServerSettingsUpdate.Result.DENIED,
                ServerSettingsUpdate.apply(false, request, settings -> fail("Unauthorized save")));
    }

    @Test
    void invalidRequestsAreRejectedAsAWholeBeforeSaving() {
        ServerMovementSettings[] invalid = {
                new ServerMovementSettings(true, -1, 6.3, 16.4),
                new ServerMovementSettings(true, 21, 6.3, 16.4),
                new ServerMovementSettings(true, 4, 21, 16.4),
                new ServerMovementSettings(true, 4, 6.3, 101),
                new ServerMovementSettings(true, Double.NaN, 6.3, 16.4),
                new ServerMovementSettings(true, 4, Double.POSITIVE_INFINITY, 16.4),
                new ServerMovementSettings(true, 4, 6.3, Double.NEGATIVE_INFINITY),
                new ServerMovementSettings(true, 4, 6.3, 16.4, -0.1),
                new ServerMovementSettings(true, 4, 6.3, 16.4, 1.1),
                new ServerMovementSettings(true, 4, 6.3, 16.4, Double.NaN)
        };
        for (ServerMovementSettings request : invalid) {
            assertEquals(ServerSettingsUpdate.Result.INVALID,
                    ServerSettingsUpdate.apply(true, request, settings -> fail("Invalid save")));
        }
    }

    @Test
    void invalidRunSpeedMultipliersCannotMutateOrSaveSettings() {
        for (double multiplier : new double[]{0.99, 5.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            var request = new ServerMovementSettings(true, true, true, false, 4, 6.3, 16.4, 0.5, multiplier);
            assertEquals(ServerSettingsUpdate.Result.INVALID,
                    ServerSettingsUpdate.apply(true, request, settings -> fail("Invalid run speed save")));
        }
    }

    @Test
    void invalidFallRulesCannotMutateOrSaveSettings() {
        for (double multiplier : new double[]{-0.1, 10.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            var request = new ServerMovementSettings(true, true, true, false, 4, 6.3, 16.4, 0.5, 1.8, multiplier, 3);
            assertEquals(ServerSettingsUpdate.Result.INVALID,
                    ServerSettingsUpdate.apply(true, request, settings -> fail("Invalid fall multiplier save")));
        }
        for (double height : new double[]{-0.1, 256.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            var request = new ServerMovementSettings(true, true, true, false, 4, 6.3, 16.4, 0.5, 1.8, 1, height);
            assertEquals(ServerSettingsUpdate.Result.INVALID,
                    ServerSettingsUpdate.apply(true, request, settings -> fail("Invalid fall height save")));
        }
    }

    @Test
    void authorizedUpdatePersistsAllValuesToTheWorldOverride() throws IOException {
        Path defaults = Files.createDirectories(directory.resolve("config"));
        Path overrides = Files.createDirectories(directory.resolve("serverconfig"));
        Path defaultPath = defaults.resolve("mineedge-server.toml");
        Path overridePath = overrides.resolve("mineedge-server.toml");
        try (CommentedFileConfig config = CommentedFileConfig.builder(defaultPath).sync().build()) {
            MineEdgeServerConfig.SPEC.correct(config);
            config.save();
        }
        Files.copy(defaultPath, overridePath);
        ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.SERVER, defaults, overrides);
        var request = new ServerMovementSettings(true, false, false, true, 8, 10, 25, 0.75, 2.4, 1.5, 5,
                new MovementFeatures(false, false, false, false, false, false, false));
        assertEquals(ServerSettingsUpdate.Result.APPLIED,
                ServerSettingsUpdate.apply(true, request, MineEdgeServerConfig::saveSettings));
        assertEquals(request, MineEdgeServerConfig.settings());
        assertEquals(19.2, MineEdgeServerConfig.tuning().sprintSpeed(), 1.0E-12);
        assertEquals(1.5, MineEdgeServerConfig.tuning().fallDamageMultiplier());
        assertEquals(5, MineEdgeServerConfig.tuning().minimumFallHeight());
        assertEquals(request.features(), MineEdgeServerConfig.tuning().features());
        try (CommentedFileConfig reloaded = CommentedFileConfig.builder(overridePath).sync().build()) {
            reloaded.load();
            assertEquals(Boolean.TRUE, reloaded.get("movement.enabled"));
            assertEquals(Boolean.FALSE, reloaded.get("movement.survival"));
            assertEquals(Boolean.FALSE, reloaded.get("movement.adventure"));
            assertEquals(Boolean.TRUE, reloaded.get("movement.creative"));
            assertEquals(Double.valueOf(8), reloaded.get("movement.walkSpeed"));
            assertEquals(Double.valueOf(2.4), reloaded.get("movement.runSpeedMultiplier"));
            assertEquals(Double.valueOf(10), reloaded.get("movement.jumpSpeed"));
            assertEquals(Double.valueOf(25), reloaded.get("movement.gravity"));
            assertEquals(Double.valueOf(0.75), reloaded.get("movement.rollDamageReduction"));
            assertEquals(Double.valueOf(1.5), reloaded.get("movement.fallDamageMultiplier"));
            assertEquals(Double.valueOf(5), reloaded.get("movement.minimumFallHeight"));
            for (String feature : new String[]{"sliding", "wallRunning", "vaulting", "mantling", "pipeClimbing", "doorPush", "hardLanding"}) {
                assertEquals(Boolean.FALSE, reloaded.get("movement.features." + feature));
            }
        }
        try (CommentedFileConfig unchanged = CommentedFileConfig.builder(defaultPath).sync().build()) {
            unchanged.load();
            assertEquals(Boolean.TRUE, unchanged.get("movement.enabled"));
            assertEquals(Boolean.TRUE, unchanged.get("movement.survival"));
            assertEquals(Boolean.TRUE, unchanged.get("movement.adventure"));
            assertEquals(Boolean.FALSE, unchanged.get("movement.creative"));
            assertEquals(Double.valueOf(4.317), unchanged.get("movement.walkSpeed"));
            assertEquals(Double.valueOf(1.5), unchanged.get("movement.runSpeedMultiplier"));
            assertEquals(Double.valueOf(7), unchanged.get("movement.jumpSpeed"));
            assertEquals(Double.valueOf(20.0), unchanged.get("movement.gravity"));
            assertEquals(Double.valueOf(1), unchanged.get("movement.fallDamageMultiplier"));
            assertEquals(Double.valueOf(4), unchanged.get("movement.minimumFallHeight"));
            for (String feature : new String[]{"sliding", "wallRunning", "vaulting", "mantling", "pipeClimbing", "doorPush", "hardLanding"}) {
                assertEquals(Boolean.TRUE, unchanged.get("movement.features." + feature));
            }
        }
    }
}
