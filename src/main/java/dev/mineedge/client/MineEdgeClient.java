package dev.mineedge.client;

import dev.mineedge.MineEdge;
import dev.mineedge.client.config.MineEdgeClientConfig;
import dev.mineedge.client.config.MineEdgeConfigScreen;
import dev.mineedge.client.config.ClientServerConfig;
import dev.mineedge.client.movement.ClientMovement;
import dev.mineedge.client.camera.MovementCamera;
import dev.mineedge.client.input.MovementKeys;
import net.neoforged.bus.api.IEventBus;
import dev.mineedge.client.render.MovementOverlay;
import dev.mineedge.client.render.ThirdPersonHands;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.network.MovementNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = MineEdge.MOD_ID, dist = Dist.CLIENT)
public final class MineEdgeClient {
    public MineEdgeClient(ModContainer modContainer, IEventBus modBus) {
        modBus.addListener(MovementKeys::register);
        MovementRuntime.installClient(ClientMovement.INSTANCE);
        MovementNetwork.installClientReceiver(ClientMovement.INSTANCE::receive);
        MovementNetwork.installClientSessionReceiver(ClientMovement.INSTANCE::receive);
        MovementNetwork.installClientConfigReceiver(ClientServerConfig::receive);
        MovementNetwork.installClientHandReceiver(ThirdPersonHands::receive);
        NeoForge.EVENT_BUS.addListener(ThirdPersonHands::frame);
        NeoForge.EVENT_BUS.addListener(ThirdPersonHands::logout);
        NeoForge.EVENT_BUS.addListener(ClientServerConfig::logout);
        NeoForge.EVENT_BUS.addListener(ClientMovement.INSTANCE::capture);
        NeoForge.EVENT_BUS.addListener(ClientMovement.INSTANCE::key);
        NeoForge.EVENT_BUS.addListener(ClientMovement.INSTANCE::mouse);
        NeoForge.EVENT_BUS.addListener(ClientMovement.INSTANCE::clientTick);
        NeoForge.EVENT_BUS.addListener(ClientMovement.INSTANCE::renderFrame);
        NeoForge.EVENT_BUS.addListener(ClientMovement.INSTANCE::beforeTick);
        NeoForge.EVENT_BUS.addListener(ClientMovement.INSTANCE::logout);
        NeoForge.EVENT_BUS.addListener(ClientMovement.INSTANCE::login);
        NeoForge.EVENT_BUS.addListener(ClientMovement.INSTANCE::clonePlayer);
        NeoForge.EVENT_BUS.addListener(MovementOverlay::render);
        NeoForge.EVENT_BUS.addListener(MovementCamera::angles);
        NeoForge.EVENT_BUS.addListener(MovementCamera::fov);
        modContainer.registerConfig(ModConfig.Type.CLIENT, MineEdgeClientConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> MineEdgeConfigScreen.create(parent));
    }
}
