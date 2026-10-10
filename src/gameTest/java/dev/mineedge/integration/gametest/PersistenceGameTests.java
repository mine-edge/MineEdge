package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.integration.MovementSession;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementVector;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PersistenceGameTests {
    @GameTest(template = "empty", batch = "persistence", timeoutTicks = 40)
    public static void logoutRejoinRestoresSavedPlayerMovement(GameTestHelper helper) throws IOException {
        roundTrip(helper, true);
    }

    @GameTest(template = "empty", batch = "persistence", timeoutTicks = 40)
    public static void activePlayerSaveRestoresAfterRestart(GameTestHelper helper) throws IOException {
        roundTrip(helper, false);
    }

    private static void roundTrip(GameTestHelper helper, boolean logout) throws IOException {
        var profile = new GameProfile(UUID.randomUUID(), "PersistenceTest");
        var player = connectedPlayer(helper, profile);
        player.setPos(helper.absoluteVec(new Vec3(7.5, 2, 7.5)));
        var captured = MinecraftMotionAdapter.capture(player);
        var state = new MovementState(captured.position(), new MovementVector(0, -7.5, 6),
                MovementState.Mode.SLIDE, 0.35, false, 8.5F,
                new LocomotionState(2.4, 1.5, 0.2, 0, 179, true), captured.blocks(), MovementBody.LOW, 179);
        var original = MovementRuntime.session(player);
        original.active = true;
        original.state = state;
        original.dimension = player.level().dimension().location();
        original.simulationStep = 150;
        MinecraftMotionAdapter.apply(player, state);
        if (logout) {
            MovementRuntime.onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
            helper.assertTrue(original.resumePending && !original.active, "Logout discarded the resumable state");
        }

        // Exercise the actual entity attachment save/load hooks through compressed player NBT.
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(player.saveWithoutId(new CompoundTag()), bytes);
        MovementRuntime.reset(player);
        var rejoined = connectedPlayer(helper, profile);
        rejoined.load(NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), NbtAccounter.unlimitedHeap()));
        var restored = MovementRuntime.session(rejoined);
        helper.assertTrue(restored.resumePending && state.equals(restored.state), "Player NBT did not restore complete movement state");
        helper.assertTrue(restored.epoch != original.epoch && restored.simulationStep == 0, "Rejoin reused stale connection bookkeeping");

        // Exercise the real login teleport, then model acknowledgment by restoring
        // the fixture's idle handler before movement activation.
        var idleConnection = rejoined.connection;
        rejoined.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(), new Connection(PacketFlow.SERVERBOUND),
                rejoined, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) {}
        };
        try {
            rejoined.connection.teleport(rejoined.getX(), rejoined.getY(), rejoined.getZ(), rejoined.getYRot(), rejoined.getXRot());
            helper.assertTrue(restored.resumePending && state.equals(restored.state), "Login teleport cleared saved movement");
        } finally {
            rejoined.connection = idleConnection;
        }
        // Minecraft may alter motion/pose during login. The authoritative saved state wins on activation.
        rejoined.setDeltaMovement(Vec3.ZERO);
        try {
            MovementRuntime.beforeTick(new PlayerTickEvent.Pre(rejoined));
            helper.assertTrue(restored.active && !restored.resumePending, "Saved movement did not activate");
            helper.assertTrue(state.equals(restored.state), "Activation reset movement mode or timers");
            helper.assertTrue(rejoined.getDeltaMovement().equals(MinecraftMotionAdapter.minecraft(state.velocity().scale(1.0 / 20))),
                    "Activation lost saved momentum");
            helper.assertTrue(rejoined.fallDistance == state.fallDistance(), "Activation lost fall history");
        } finally {
            MovementRuntime.reset(rejoined);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "persistence", timeoutTicks = 40)
    public static void relocationAndDeathInvalidateSavedPaths(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "ResetTest"));
        player.setPos(helper.absoluteVec(new Vec3(7.5, 2, 7.5)));
        var session = MovementRuntime.session(player);
        prepare(player, session);
        MovementRuntime.teleport(player, session.state.position().add(new MovementVector(0, 1, 0)));
        helper.assertTrue(session.state == null && !session.resumePending, "Actual teleport retained an old movement path");
        prepare(player, session);
        MovementRuntime.onDeath(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(player, player.damageSources().generic()));
        helper.assertTrue(session.state == null && !session.resumePending, "Death retained saved movement");
        helper.succeed();
    }

    private static void prepare(FakePlayer player, MovementSession session) {
        session.active = true;
        session.state = MinecraftMotionAdapter.capture(player);
        session.dimension = player.level().dimension().location();
        session.suspend();
    }

    private static ServerPlayer connectedPlayer(GameTestHelper helper, GameProfile profile) {
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, ClientInformation.createDefault());
        player.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(), new Connection(PacketFlow.SERVERBOUND),
                player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) {}
        };
        return player;
    }

    private PersistenceGameTests() {}
}
