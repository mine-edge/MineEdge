package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.CollisionQueries;
import dev.mineedge.integration.MinecraftCollisionWorld;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementPoses;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
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
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void temporaryAttributesPreserveSessionMomentumTimersAndQueuedInputs(GameTestHelper helper) {
        var player = player(helper);
        var session = MovementRuntime.session(player);
        var id = ResourceLocation.fromNamespaceAndPath(MineEdge.MOD_ID, "test_aim_slowdown");
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        try {
            player.setOnGround(true);
            var captured = MinecraftMotionAdapter.capture(player);
            var state = new MovementState(captured.position(), new MovementVector(0, 0, 6), MovementState.Mode.SLIDE,
                    0.35, true, 4.5F, new LocomotionState(2.4, 1.5, 0.2, 0.1, 179, true),
                    captured.blocks(), MovementBody.LOW, 179);
            MinecraftMotionAdapter.apply(player, state);
            session.active = true;
            session.state = state;
            session.tuning = MovementRuntime.effectiveTuning(player);
            session.writtenVelocity = state.velocity().scale(1.0 / 20);
            session.simulationStep = 73;
            session.input = new MovementInput(1, 1, 0, 0, false, false, true, false, false, 179, 0);
            session.inbox.offer(session.input, 73);
            session.inbox.consumeSimulation(73, 5);
            session.inbox.offer(session.input.withSequence(2), 74);
            MovementRuntime.beforeTick(new PlayerTickEvent.Pre(player));
            var clock = session.simulation;
            var inbox = session.inbox;
            long epoch = session.epoch;
            var originalTuning = session.tuning;
            double walkSpeed = originalTuning.walkSpeed();

            for (double factor : new double[] {0.35, 0, 0.6, 1}) {
                speed.removeModifier(id);
                if (factor != 1) speed.addTransientModifier(new AttributeModifier(id, factor - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                long revision = session.tuningRevision;
                MovementRuntime.beforeTick(new PlayerTickEvent.Pre(player));
                helper.assertTrue(session.active && session.epoch == epoch && session.simulation == clock,
                        "Attribute change restarted the movement session or its clock");
                helper.assertTrue(session.state.equals(state), "Attribute change discarded momentum, pose, fall history or timers");
                helper.assertTrue(session.inbox == inbox && inbox.size() == 1 && inbox.acknowledged() == 1
                                && session.input.sequence() == 1 && session.simulationStep == 73,
                        "Attribute change discarded held/queued inputs or sequence bookkeeping");
                helper.assertTrue(Math.abs(session.tuning.walkSpeed() - walkSpeed * factor) < 1.0E-9,
                        "Temporary slowdown did not reach effective tuning");
                helper.assertTrue(session.tuningRevision == revision + 1, "Attribute change did not advance its tuning revision");
                MovementRuntime.beforeTick(new PlayerTickEvent.Pre(player));
                helper.assertTrue(session.tuningRevision == revision + 1, "Unchanged attributes advanced the tuning revision");
            }

            for (var attribute : List.of(Attributes.JUMP_STRENGTH, Attributes.GRAVITY, Attributes.SNEAKING_SPEED)) {
                player.getAttribute(attribute).addTransientModifier(new AttributeModifier(id, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            MovementRuntime.beforeTick(new PlayerTickEvent.Pre(player));
            helper.assertTrue(Math.abs(session.tuning.jumpSpeed() - originalTuning.jumpSpeed() * 1.5) < 1.0E-6
                            && Math.abs(session.tuning.gravity() - originalTuning.gravity() * 1.5) < 1.0E-6
                            && Math.abs(session.tuning.crouchSpeedModifier() - originalTuning.crouchSpeedModifier() * 1.5) < 1.0E-6,
                    "Jump, gravity or sneaking attribute changes did not reach the active simulation");
            helper.assertTrue(session.epoch == epoch && session.state.equals(state) && session.inbox == inbox && inbox.size() == 1,
                    "Non-speed attribute changes discarded the active state or commands");
            for (var attribute : List.of(Attributes.JUMP_STRENGTH, Attributes.GRAVITY, Attributes.SNEAKING_SPEED)) {
                player.getAttribute(attribute).removeModifier(id);
            }

            session.resetRequested = true;
            MovementRuntime.beforeTick(new PlayerTickEvent.Pre(player));
            helper.assertTrue(session.epoch != epoch && session.inbox != inbox && session.tuningRevision == 0,
                    "Explicit lifecycle resets stopped invalidating stale commands");
        } finally {
            speed.removeModifier(id);
            for (var attribute : List.of(Attributes.JUMP_STRENGTH, Attributes.GRAVITY, Attributes.SNEAKING_SPEED)) {
                player.getAttribute(attribute).removeModifier(id);
            }
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
