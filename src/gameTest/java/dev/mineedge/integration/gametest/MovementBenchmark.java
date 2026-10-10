package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import com.sun.management.ThreadMXBean;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementProfile;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.network.SnapshotPayload;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Reproducible transformed-server microbenchmark; not a timing assertion or a real network load test. */
@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MovementBenchmark {
    private static final int PLAYERS = 32, WARMUP = 12, FRAMES = 40;
    private static final MovementController CONTROLLER = new MovementController();
    private static MovementVector position(GameTestHelper helper, double x, double y, double z) {
        return MinecraftMotionAdapter.core(helper.absoluteVec(new Vec3(x, y, z)));
    }
    private static MovementInput input(float yaw, boolean jump, boolean crouch) {
        return new MovementInput(1, 1, 0, 0, jump, jump, crouch, crouch, true, yaw, 0);
    }
    private static void clear(GameTestHelper helper) {
        for (int x = 1; x <= 13; x++) for (int z = 1; z <= 13; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 8; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        var origin = helper.absolutePos(new BlockPos(7, 0, 7));
        for (int x = (origin.getX() >> 4) - 2; x <= (origin.getX() >> 4) + 2; x++)
            for (int z = (origin.getZ() >> 4) - 2; z <= (origin.getZ() >> 4) + 2; z++) helper.getLevel().getChunk(x, z);
    }
    private static MovementState initial(GameTestHelper helper, String scenario) {
        var ground = new MovementState(position(helper, 4.5, 1, 3.5), new MovementVector(0, 0, 6), MovementState.Mode.GROUND, 1, true, 0);
        return switch (scenario) {
            case "air" -> new MovementState(position(helper, 4.5, 3, 3.5), new MovementVector(0, -2, 6), MovementState.Mode.AIR, 0.2, false, 0);
            case "slide" -> new MovementState(ground.position(), ground.velocity(), MovementState.Mode.SLIDE, 0.2, true, 0,
                    LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.LOW, 0);
            case "vault" -> {
                helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
                // Start within the planner's reach, as in the traversal game tests.
                yield new MovementState(position(helper, 4.5, 1, 4.1), ground.velocity(),
                        MovementState.Mode.GROUND, 1, true, 0);
            }
            case "wallrun" -> {
                for (int y = 1; y <= 7; y++) for (int z = 2; z <= 12; z++) helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
                yield new MovementState(position(helper, 5.6, 1.3, 3.5), new MovementVector(2, 3, 6), MovementState.Mode.AIR, 0.1, false, 0);
            }
            case "hang" -> {
                for (int x = 3; x <= 7; x++) for (int y = 1; y <= 3; y++) for (int z = 5; z <= 10; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                yield new MovementState(position(helper, 4.5, 2.2, 4.65), new MovementVector(0, -1, 1), MovementState.Mode.AIR, 0.2, false, 0);
            }
            case "roll" -> new MovementState(position(helper, 4.5, 1.1, 3.5), new MovementVector(0, -12, 6), MovementState.Mode.AIR, 1, false, 5.9F);
            default -> ground;
        };
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void profileThirtyTwoPlayerMovement(GameTestHelper helper) {
        if (!Boolean.getBoolean("mineedge.benchmark")) { helper.succeed(); return; }
        ThreadMXBean allocations = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        if (allocations == null || !allocations.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        allocations.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (String scenario : new String[]{"ground", "air", "slide", "vault", "wallrun", "hang", "roll"}) {
            clear(helper);
            MovementState seed = initial(helper, scenario);
            var primer = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgePrime"));
            MinecraftMotionAdapter.apply(primer, seed);
            MovementInput prime = scenario.equals("hang") ? MovementInput.idle(1, 0, 0)
                    : input(scenario.equals("wallrun") ? -25 : 0, scenario.equals("vault"), scenario.equals("roll"));
            if (!scenario.equals("ground") && !scenario.equals("air") && !scenario.equals("slide"))
                seed = CONTROLLER.tick(seed, prime, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(primer), e -> {});
            var expected = switch (scenario) {
                case "slide" -> MovementState.Mode.SLIDE;
                case "vault" -> MovementState.Mode.VAULT;
                case "wallrun" -> MovementState.Mode.WALLRUN;
                case "hang" -> MovementState.Mode.LEDGE_HANG;
                case "roll" -> MovementState.Mode.ROLL;
                case "air" -> MovementState.Mode.AIR;
                default -> MovementState.Mode.GROUND;
            };
            helper.assertTrue(seed.mode() == expected, "Invalid benchmark baseline: " + scenario + " / " + seed.mode());
            FakePlayer[] players = new FakePlayer[PLAYERS];
            for (int p = 0; p < PLAYERS; p++) {
                players[p] = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgePerf"));
                var session = MovementRuntime.session(players[p]);
                session.active = true;
                session.tuning = MovementTuning.DEFAULT;
                session.state = seed;
            }
            MovementInput command = scenario.equals("hang") ? MovementInput.idle(1, 0, 0)
                    : input(scenario.equals("wallrun") ? -25 : 0, false, scenario.equals("slide"));
            long[] frames = new long[FRAMES];
            long allocated = 0;
            MovementProfile.Sample sample = null;
            MovementState result = seed;
            try {
                for (int frame = -WARMUP; frame < FRAMES; frame++) {
                    if (frame == 0) sample = MovementProfile.begin();
                    long beforeBytes = allocations.getThreadAllocatedBytes(thread);
                    long before = System.nanoTime();
                    for (FakePlayer player : players) {
                        MinecraftMotionAdapter.apply(player, seed);
                        result = CONTROLLER.tick(seed, command, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(player), e -> {});
                        MovementRuntime.session(player).state = result;
                    }
                    if (frame >= 0) {
                        frames[frame] = System.nanoTime() - before;
                        allocated += allocations.getThreadAllocatedBytes(thread) - beforeBytes;
                    }
                }
            } finally { MovementProfile.end(); }
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
            int bytes;
            try {
                SnapshotPayload.CODEC.encode(buffer, SnapshotPayload.capture(players[0], MovementRuntime.session(players[0])));
                bytes = buffer.readableBytes();
            } finally { buffer.release(); }
            Arrays.sort(frames);
            double ticks = PLAYERS * FRAMES;
            MineEdge.LOGGER.info("MOVEMENT_BENCH {}: players={} samples={} us/playerTick={} p50/p95_ms/32players={}/{} allocated_B/playerTick={} queries/gathers/hits_perTick={}/{}/{} boxes/tick={} gather_us/tick={} snapshot_B={}",
                    scenario, PLAYERS, FRAMES, Math.round(Arrays.stream(frames).sum() / ticks / 1000),
                    frames[FRAMES / 2] / 1_000_000.0, frames[(int) (FRAMES * 0.95) - 1] / 1_000_000.0,
                    Math.round(allocated / ticks), sample.queries / ticks, sample.gathers / ticks, sample.cacheHits / ticks,
                    sample.boxes / ticks, Math.round(sample.gatherNanos / ticks / 1000), bytes);
        }
        helper.succeed();
    }
}
