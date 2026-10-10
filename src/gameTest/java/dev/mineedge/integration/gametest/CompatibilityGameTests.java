package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.CollisionQueries;
import dev.mineedge.integration.MinecraftCollisionWorld;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementPoses;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementVector;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.negotiation.NegotiableNetworkComponent;
import net.neoforged.neoforge.network.negotiation.NetworkComponentNegotiator;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

/** Compatibility hooks exercised in a real, transformed Minecraft world. */
@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompatibilityGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void mineEdgeIsRequiredOnClientAndServer(GameTestHelper helper) throws ReflectiveOperationException {
        // Read actual registrations after mod loading so optional payloads cannot silently bypass the requirement.
        var registrationsField = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        registrationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var registrations = (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) registrationsField.get(null);
        var channels = registrations.get(ConnectionProtocol.PLAY).values().stream()
                .filter(registration -> registration.id().getNamespace().equals(MineEdge.MOD_ID))
                .map(NegotiableNetworkComponent::new).toList();
        helper.assertTrue(!channels.isEmpty(), "MineEdge did not register its network payloads");
        helper.assertTrue(channels.stream().noneMatch(NegotiableNetworkComponent::optional),
                "MineEdge payloads must be required on both sides");
        helper.assertTrue(NetworkComponentNegotiator.negotiate(channels, channels).success(),
                "Matching MineEdge clients and servers could not negotiate");
        helper.assertTrue(!NetworkComponentNegotiator.negotiate(channels, List.of()).success(),
                "A client without MineEdge was accepted");
        helper.assertTrue(!NetworkComponentNegotiator.negotiate(List.of(), channels).success(),
                "A server without MineEdge was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void sizeEventAdjustmentsReachLiveReplayAndPoseClearance(GameTestHelper helper) {
        var player = player(helper);
        var feet = new MovementVector(player.getX(), player.getY(), player.getZ());
        helper.setBlock(8, 1, 7, Blocks.STONE);
        helper.setBlock(8, 2, 7, Blocks.STONE);
        Consumer<EntityEvent.Size> listener = event -> {
            if (event.getEntity() == player) event.setNewSize(event.getNewSize().scale(2, 1));
        };
        try {
            helper.assertTrue(MinecraftCollisionWorld.canOccupy(player, feet, MovementBody.STANDING),
                    "Native-size fixture already intersected the wall");
            NeoForge.EVENT_BUS.addListener(listener);
            player.refreshDimensions();
            var queries = new CollisionQueries(player);
            queries.begin();
            for (var body : MovementBody.values()) {
                var preview = MovementPoses.dimensions(player, body);
                helper.assertTrue(Math.abs(preview.width() - 1.2F) < 1.0E-6,
                        "Size event was lost or applied twice to " + body);
                helper.assertTrue(!queries.canOccupy(feet, body), "Pose preview ignored a modded width: " + body);
                MovementPoses.apply(player, body);
                var actual = MinecraftCollisionWorld.core(player.getBoundingBox());
                helper.assertTrue(MinecraftCollisionWorld.body(player, feet).equals(actual),
                        "Current collision body ignored installed dimensions");
                helper.assertTrue(MinecraftMotionAdapter.live(player).bounds(feet, body).equals(actual),
                        "Live bounds disagreed with the actual pose");
                helper.assertTrue(MinecraftMotionAdapter.replay(player).bounds(feet, body).equals(actual),
                        "Replay bounds disagreed with the actual pose");
            }
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void automationPlayersKeepNativeMovementWithoutAnInputClient(GameTestHelper helper) {
        var player = player(helper);
        var session = MovementRuntime.session(player);
        try {
            MovementRuntime.beforeTick(new PlayerTickEvent.Pre(player));
            MovementRuntime.afterTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(!session.active && session.simulation == null,
                    "Fake player was enrolled in client-driven movement");
            helper.assertTrue(!MovementRuntime.owns(player), "Fake player's native travel was suppressed");
            var before = player.position();
            player.setDeltaMovement(0.1, 0, 0);
            player.travel(Vec3.ZERO);
            helper.assertTrue(!player.position().equals(before), "Automation player's native movement did not run");
        } finally {
            session.reset();
            player.discard();
        }
        helper.succeed();
    }

    private static FakePlayer player(GameTestHelper helper) {
        for (int x = 1; x <= 13; x++) for (int z = 1; z <= 13; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        var origin = helper.absolutePos(new BlockPos(7, 1, 7));
        for (int x = (origin.getX() >> 4) - 2; x <= (origin.getX() >> 4) + 2; x++)
            for (int z = (origin.getZ() >> 4) - 2; z <= (origin.getZ() >> 4) + 2; z++) helper.getLevel().getChunk(x, z);
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeCompat"));
        player.setPos(Vec3.atLowerCornerOf(origin).add(0.5, 0, 0.5));
        return player;
    }

    private CompatibilityGameTests() {}
}
