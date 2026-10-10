package dev.mineedge;

import com.mojang.logging.LogUtils;
import dev.mineedge.config.MineEdgeServerConfig;
import dev.mineedge.config.ServerConfigService;
import dev.mineedge.integration.MovementAttachments;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.integration.ResolvedMovement;
import dev.mineedge.network.MovementNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(MineEdge.MOD_ID)
public final class MineEdge {
    public static final String MOD_ID = "mineedge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MineEdge(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, MineEdgeServerConfig.SPEC);
        MovementAttachments.TYPES.register(modEventBus);
        modEventBus.addListener(MovementNetwork::register);
        NeoForge.EVENT_BUS.addListener(MovementRuntime::beforeTick);
        NeoForge.EVENT_BUS.addListener(MovementRuntime::afterTick);
        NeoForge.EVENT_BUS.addListener(MovementRuntime::startTracking);
        NeoForge.EVENT_BUS.addListener(MovementRuntime::onDeath);
        NeoForge.EVENT_BUS.addListener(MovementRuntime::onClone);
        NeoForge.EVENT_BUS.addListener(MovementRuntime::onDimension);
        NeoForge.EVENT_BUS.addListener(MovementRuntime::onLogout);
        NeoForge.EVENT_BUS.addListener(MovementRuntime::stopping);
        NeoForge.EVENT_BUS.addListener(MovementRuntime::stopped);
        NeoForge.EVENT_BUS.addListener(ResolvedMovement::onFall);
        NeoForge.EVENT_BUS.addListener(ServerConfigService::login);
        NeoForge.EVENT_BUS.addListener(ServerConfigService::tick);
        NeoForge.EVENT_BUS.addListener(ServerConfigService::stopped);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("MineEdge initialized");
    }
}
