package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.MinecraftCollisionWorld;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementPoses;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.traversal.VaultType;
import dev.mineedge.movement.traversal.VaultPlanner;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VaultGameTests {
    private static final MovementController CONTROLLER = new MovementController();

    private static final class TestPlayer extends FakePlayer {
        TestPlayer(GameTestHelper helper) {
            super(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeVault"));
        }
        void updatePose() { super.updatePlayerPose(); }
    }

    private record Result(MovementState state, VaultType kind, int completions, int aborts) {}

    private static void floor(GameTestHelper helper) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos origin = helper.absolutePos(new BlockPos(7, 0, 7));
        for (int x = (origin.getX() >> 4) - 2; x <= (origin.getX() >> 4) + 2; x++) {
            for (int z = (origin.getZ() >> 4) - 2; z <= (origin.getZ() >> 4) + 2; z++) {
                helper.getLevel().getChunk(x, z);
            }
        }
    }

    private static MovementState start(GameTestHelper helper) {
        return start(helper, 4.1);
    }

    private static MovementState start(GameTestHelper helper, double z) {
        var position = MinecraftMotionAdapter.core(helper.absoluteVec(new Vec3(4.5, 1, z)));
        return new MovementState(position, new MovementVector(0, 0, 6), MovementState.Mode.GROUND, 1, true, 0);
    }

    private static Result compare(GameTestHelper helper, MovementState baseline, int ticks,
                                   BiConsumer<Integer, MovementState> change) {
        TestPlayer live = new TestPlayer(helper);
        TestPlayer replayOwner = new TestPlayer(helper);
        MinecraftMotionAdapter.apply(live, baseline);
        MinecraftMotionAdapter.apply(replayOwner, baseline);
        var session = MovementRuntime.session(live);
        session.active = true;
        session.tuning = MovementTuning.DEFAULT;
        session.state = baseline;
        MovementState actual = baseline;
        MovementState predicted = baseline;
        MovementState replayBaseline = null;
        VaultType kind = null;
        var events = new ArrayList<MovementEvent>();
        var commands = new ArrayList<MovementInput>();
        Vec3 ownerPosition = replayOwner.position();
        var ownerPose = replayOwner.getPose();
        for (int tick = 1; tick <= ticks; tick++) {
            change.accept(tick, actual);
            var command = new MovementInput(tick, 1, 0, 0, true, tick == 1 || tick == 5,
                    false, false, true, 0, 0);
            commands.add(command);
            actual = CONTROLLER.tick(actual, command, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(live), events::add);
            session.state = actual;
            live.updatePose();
            predicted = CONTROLLER.tick(predicted, command, MovementTuning.DEFAULT, MinecraftMotionAdapter.replay(replayOwner), event -> {});
            if (kind == null && actual.traversal().path() != null) {
                kind = actual.traversal().path().kind();
            }
            helper.assertTrue(actual.position().subtract(predicted.position()).length() < 1.0E-6,
                    "Traversal position diverged at tick " + tick + ": " + actual.position() + " / " + predicted.position());
            helper.assertTrue(actual.velocity().subtract(predicted.velocity()).length() < 1.0E-6,
                    "Traversal velocity diverged at tick " + tick + ": " + actual.velocity() + " / " + predicted.velocity());
            helper.assertTrue(actual.mode() == predicted.mode() && actual.body() == predicted.body(), "Traversal mode/body diverged");
            helper.assertTrue(actual.traversal().equals(predicted.traversal()), "Replay path/witnesses/expected velocity diverged");
            helper.assertTrue(Math.abs(actual.fallDistance() - predicted.fallDistance()) < 1.0E-5, "Traversal fall history diverged");
            helper.assertTrue(live.getPose() == MovementPoses.pose(actual.body()), "Vanilla overwrote the traversal pose");
            helper.assertTrue(MinecraftCollisionWorld.canOccupy(live, actual.position(), actual.body()), "Traversal clipped fixture geometry");
            if (tick == 7) {
                replayBaseline = actual;
            }
        }
        helper.assertTrue(replayOwner.position().equals(ownerPosition) && replayOwner.getPose() == ownerPose,
                "Traversal replay mutated its owner");
        if (replayBaseline != null && events.stream().noneMatch(event -> event == MovementEvent.TRAVERSAL_ABORT)) {
            MovementState replay = replayBaseline;
            for (int i = 7; i < commands.size(); i++) {
                replay = CONTROLLER.tick(replay, commands.get(i), MovementTuning.DEFAULT, MinecraftMotionAdapter.replay(replayOwner), event -> {});
            }
            helper.assertTrue(actual.position().subtract(replay.position()).length() < 1.0E-6,
                    "Acknowledged traversal baseline did not replay");
        }
        return new Result(actual, kind, (int) events.stream().filter(event -> event == MovementEvent.TRAVERSAL_COMPLETE).count(),
                (int) events.stream().filter(event -> event == MovementEvent.TRAVERSAL_ABORT).count());
    }

    private static Result compare(GameTestHelper helper, MovementState baseline, int ticks) {
        return compare(helper, baseline, ticks, (tick, state) -> {});
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void vaultActualSlabFullBlockAndFence(GameTestHelper helper) {
        floor(helper);
        Block[] blocks = {Blocks.STONE_SLAB, Blocks.STONE, Blocks.OAK_FENCE};
        VaultType[] kinds = {VaultType.VAULT, VaultType.VAULT, VaultType.HIGH_VAULT};
        for (int i = 0; i < blocks.length; i++) {
            helper.setBlock(new BlockPos(4, 1, 5), blocks[i]);
            Result result = compare(helper, i == 2 ? start(helper, 4.55) : start(helper), i == 2 ? 22 : 14);
            helper.assertTrue(result.kind() == kinds[i], "Wrong type for " + blocks[i] + ": " + result.kind());
            helper.assertTrue(result.completions() == 1 && result.aborts() == 0, "Shape vault did not complete once");
            helper.assertTrue(result.state().position().z() > helper.absolutePos(new BlockPos(4, 1, 6)).getZ() + 0.3,
                    "Vault did not clear the obstacle");
            helper.assertTrue(result.state().traversal().path() == null, "Traversal remained locked");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void mantleDeepBlockAndHighSlabPlatform(GameTestHelper helper) {
        floor(helper);
        for (int z = 5; z <= 9; z++) {
            helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        }
        Result normal = compare(helper, start(helper), 13);
        helper.assertTrue(normal.kind() == VaultType.MANTLE && normal.completions() == 1 && normal.aborts() == 0,
                "Deep platform did not select/complete a mantle");
        helper.assertTrue(Math.abs(normal.state().position().y() - helper.absolutePos(new BlockPos(4, 2, 7)).getY()) < 1.0E-6,
                "Mantle did not finish on top");
        for (int z = 5; z <= 9; z++) {
            helper.setBlock(new BlockPos(4, 2, z), Blocks.STONE_SLAB);
        }
        Result high = compare(helper, start(helper), 24);
        helper.assertTrue(high.kind() == VaultType.HIGH_MANTLE && high.completions() == 1 && high.aborts() == 0,
                "High platform did not complete a high mantle");
        helper.assertTrue(Math.abs(high.state().position().y() - helper.absolutePos(new BlockPos(4, 2, 7)).getY() - 0.5) < 1.0E-6,
                "High mantle did not finish on its slab top");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void mantleIntoCrouchingAndCrawlingEntrances(GameTestHelper helper) {
        floor(helper);
        for (boolean crouching : new boolean[]{true, false}) {
            for (int z = 5; z <= 10; z++) {
                helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
                for (int x = 3; x <= 5; x++) helper.setBlock(new BlockPos(x, 3, z), crouching
                        ? Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
                        : Blocks.STONE.defaultBlockState());
            }
            var result = compare(helper, start(helper), 14);
            helper.assertTrue(result.kind() == VaultType.MANTLE && result.completions() == 1 && result.aborts() == 0,
                    "Low entrance mantle did not complete: crouching=" + crouching);
            helper.assertTrue(result.state().grounded() && result.state().body() == (crouching ? MovementBody.CROUCHING : MovementBody.LOW),
                    "Entrance did not retain its fitting native pose");
            helper.assertTrue(Math.abs(result.state().position().y() - helper.absolutePos(new BlockPos(4, 2, 7)).getY()) < 1.0E-6,
                    "Low entrance mantle missed the platform top");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void blockedHeadroomAndTallWallRejectTraversal(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
        for (int z = 5; z <= 9; z++) {
            helper.setBlock(new BlockPos(4, 2, z), Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
        }
        var owner = new TestPlayer(helper);
        var baseline = start(helper);
        MinecraftMotionAdapter.apply(owner, baseline);
        helper.assertTrue(VaultPlanner.plan(MinecraftMotionAdapter.replay(owner), baseline.position(),
                new MovementVector(0, MovementTuning.DEFAULT.jumpSpeed(), 6),
                new MovementInput(1, 1, 0, 0, true, true, false, false, true, 0, 0)) == null,
                "Traversal ignored headroom too low even for crawling");
        for (int z = 5; z <= 9; z++) {
            helper.setBlock(new BlockPos(4, 2, z), Blocks.AIR);
        }
        for (int y = 1; y <= 4; y++) {
            helper.setBlock(new BlockPos(4, y, 5), Blocks.STONE);
        }
        helper.assertTrue(VaultPlanner.plan(MinecraftMotionAdapter.replay(owner), baseline.position(),
                new MovementVector(0, MovementTuning.DEFAULT.jumpSpeed(), 6),
                new MovementInput(1, 1, 0, 0, true, true, false, false, true, 0, 0)) == null,
                "Four-block wall created a fake low top for a direct vault");
        // Phase 5 may climb this wall and then pull up; that is a real top reached from higher feet.
        var result = compare(helper, baseline, 20);
        helper.assertTrue(result.kind() == null || result.kind() == VaultType.PULL_UP,
                "Tall wall started a vault instead of the climbing/ledge route");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void removedAnchorAndLandingAbortActiveVault(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
        Result anchor = compare(helper, start(helper), 4, (tick, state) -> {
            if (tick == 2) {
                helper.assertTrue(state.traversal().path() != null, "Anchor fixture did not enter traversal");
                helper.setBlock(new BlockPos(4, 1, 5), Blocks.AIR);
            }
        });
        helper.assertTrue(anchor.aborts() == 1 && anchor.completions() == 0, "Removed anchor did not abort once");
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
        Result landing = compare(helper, start(helper), 4, (tick, state) -> {
            if (tick == 2) {
                var shape = state.traversal().path().landing();
                helper.assertTrue(shape != null, "Landing fixture had no floor");
                helper.getLevel().setBlockAndUpdate(BlockPos.containing(shape.min().x(), shape.min().y(), shape.min().z()),
                        Blocks.AIR.defaultBlockState());
            }
        });
        helper.assertTrue(landing.aborts() == 1 && landing.completions() == 0, "Removed landing did not abort once");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void newObstructionAndCobwebAbortWithoutClipping(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
        Result obstruction = compare(helper, start(helper), 4, (tick, state) -> {
            if (tick == 2) {
                helper.setBlock(new BlockPos(4, 3, 6), Blocks.STONE);
            }
        });
        helper.assertTrue(obstruction.aborts() == 1 && obstruction.completions() == 0, "New path obstruction did not abort");
        helper.setBlock(new BlockPos(4, 3, 6), Blocks.AIR);
        Result web = compare(helper, start(helper), 4, (tick, state) -> {
            if (tick == 2) {
                helper.setBlock(new BlockPos(4, 1, 3), Blocks.COBWEB);
                helper.setBlock(new BlockPos(4, 2, 3), Blocks.COBWEB);
            }
        });
        helper.assertTrue(web.aborts() == 1 && web.completions() == 0, "Stuck displacement ignored traversal abort");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void mantleSettlesOnSlimeAndReplaysItsSlowdown(GameTestHelper helper) {
        floor(helper);
        for (int z = 5; z <= 9; z++) {
            helper.setBlock(new BlockPos(4, 1, z), Blocks.SLIME_BLOCK);
        }
        Result result = compare(helper, start(helper), 13);
        helper.assertTrue(result.kind() == VaultType.MANTLE && result.completions() == 1 && result.aborts() == 0,
                "Slime mantle did not complete");
        helper.assertTrue(result.state().grounded() && result.state().velocity().y() == 0, "Mantle completion kept micro-bouncing on slime");
        helper.assertTrue(result.state().velocity().horizontalSpeed() < 4, "Traversal completion lost slime stepping slowdown");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void continuousPlatformUsesOneBlockMantleSlide(GameTestHelper helper) {
        floor(helper);
        for (int z = 5; z <= 10; z++) helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        Result result = compare(helper, start(helper), 13);
        helper.assertTrue(result.kind() == VaultType.MANTLE && result.completions() == 1 && result.aborts() == 0,
                "Continuous platform did not complete its short mantle");
        double expected = helper.absolutePos(new BlockPos(4, 1, 5)).getZ() + 0.38 + 1;
        helper.assertTrue(Math.abs(result.state().position().z() - expected) < 1.0E-6,
                "Supported mantle slid farther than one block after clearing the lip");
        helper.assertTrue(result.state().grounded(), "Short mantle did not finish supported");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void gapAfterPlatformRetainsLongerMantleSlide(GameTestHelper helper) {
        floor(helper);
        for (int z = 5; z <= 7; z++) helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 0, 8), Blocks.AIR);
        for (int z = 9; z <= 11; z++) helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        Result result = compare(helper, start(helper), 13);
        helper.assertTrue(result.kind() == VaultType.MANTLE && result.completions() == 1 && result.aborts() == 0,
                "Gap approach did not complete its mantle");
        // The 7.2 reference exit speed scales with Minecraft's default walking speed.
        double expected = helper.absolutePos(new BlockPos(4, 1, 5)).getZ()
                + 0.38 + 7.2 * (MovementTuning.DEFAULT.walkSpeed() / 4) * 0.3;
        helper.assertTrue(Math.abs(result.state().position().z() - expected) < 1.0E-6,
                "Nearby gap incorrectly shortened the mantle slide: expected=" + expected
                        + ", actual=" + result.state().position().z());
        helper.succeed();
    }

    private VaultGameTests() {}
}
