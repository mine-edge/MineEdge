package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.MinecraftCollisionWorld;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementPoses;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.traversal.VaultPlanner;
import dev.mineedge.movement.wall.WallState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WallGameTests {
    private static final MovementController CONTROLLER = new MovementController();
    private static final class TestPlayer extends FakePlayer {
        TestPlayer(GameTestHelper helper) { super(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeWall")); }
        void updatePose() { super.updatePlayerPose(); }
    }
    private record Result(MovementState state, List<MovementEvent> events) {}

    private static MovementVector position(GameTestHelper helper, double x, double y, double z) {
        return MinecraftMotionAdapter.core(helper.absoluteVec(new Vec3(x, y, z)));
    }
    private static MovementInput input(float forward, float yaw, boolean jump, boolean crouch) {
        return new MovementInput(1, forward, 0, 0, jump, jump, crouch, crouch, true, yaw, 0);
    }
    private static void floor(GameTestHelper helper) {
        for (int x = 1; x <= 13; x++) for (int z = 1; z <= 13; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        BlockPos origin = helper.absolutePos(new BlockPos(7, 0, 7));
        for (int x = (origin.getX() >> 4) - 2; x <= (origin.getX() >> 4) + 2; x++)
            for (int z = (origin.getZ() >> 4) - 2; z <= (origin.getZ() >> 4) + 2; z++) helper.getLevel().getChunk(x, z);
    }
    private static void platform(GameTestHelper helper) {
        floor(helper);
        for (int x = 3; x <= 7; x++) for (int y = 1; y <= 4; y++) for (int z = 5; z <= 10; z++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
    }
    private static MovementState air(GameTestHelper helper, double x, double y, double z, MovementVector velocity) {
        MovementVector feet = position(helper, x, y, z);
        return new MovementState(feet, velocity, MovementState.Mode.AIR, 0, false, 0, LocomotionState.EMPTY,
                BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY,
                WallState.EMPTY.jump(position(helper, x, 1, z)));
    }
    private static Result compare(GameTestHelper helper, MovementState initial, int ticks,
                                  BiFunction<Integer, MovementState, MovementInput> commands,
                                  BiConsumer<Integer, MovementState> change) {
        var live = new TestPlayer(helper);
        var owner = new TestPlayer(helper);
        MinecraftMotionAdapter.apply(live, initial);
        MinecraftMotionAdapter.apply(owner, initial);
        var session = MovementRuntime.session(live);
        session.active = true;
        session.tuning = MovementTuning.DEFAULT;
        session.state = initial;
        var actual = initial;
        var predicted = initial;
        var events = new ArrayList<MovementEvent>();
        for (int tick = 0; tick < ticks; tick++) {
            change.accept(tick, actual);
            var input = commands.apply(tick, actual);
            actual = CONTROLLER.tick(actual, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(live), events::add);
            session.state = actual;
            live.updatePose();
            predicted = CONTROLLER.tick(predicted, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.replay(owner), e -> {});
            helper.assertTrue(actual.position().subtract(predicted.position()).length() < 1.0E-6, "Wall position diverged at " + tick);
            helper.assertTrue(actual.velocity().subtract(predicted.velocity()).length() < 1.0E-6, "Wall velocity diverged at " + tick);
            helper.assertTrue(actual.mode() == predicted.mode() && actual.body() == predicted.body(), "Wall mode/body diverged");
            helper.assertTrue(actual.wall().equals(predicted.wall()) && actual.traversal().equals(predicted.traversal()),
                    "Contact/path replay diverged at " + tick + ": " + actual.traversal() + " / " + predicted.traversal());
            helper.assertTrue(actual.advanced().equals(predicted.advanced()), "Advanced move replay diverged");
            helper.assertTrue(Math.abs(actual.fallDistance() - predicted.fallDistance()) < 1.0E-5,
                    "Wall fall history diverged at " + tick + ": " + actual.fallDistance() + " / " + predicted.fallDistance());
            helper.assertTrue(MinecraftCollisionWorld.canOccupy(live, actual.position(), actual.body()), "Wall movement clipped geometry");
            helper.assertTrue(live.getPose() == MovementPoses.pose(actual.body()), "Native pose changed");
        }
        helper.assertTrue(owner.position().equals(MinecraftMotionAdapter.minecraft(initial.position())), "Replay moved owner");
        return new Result(actual, events);
    }
    private static Result compare(GameTestHelper helper, MovementState initial, int ticks, BiFunction<Integer, MovementState, MovementInput> commands) {
        return compare(helper, initial, ticks, commands, (tick, state) -> {});
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void leftRightWallrunAndViewDependentJump(GameTestHelper helper) {
        floor(helper);
        for (int x : new int[]{2, 6}) for (int y = 1; y <= 7; y++) for (int z = 2; z <= 12; z++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        for (int sign : new int[]{-1, 1}) {
            var initial = air(helper, sign == 1 ? 5.6 : 3.4, 1.3, 3.5, new MovementVector(sign * 2, 3, 6));
            var result = compare(helper, initial, 8, (tick, state) -> input(1, tick < 5 ? -sign * 25 : sign * 90, tick == 5, false));
            helper.assertTrue(result.events().containsAll(List.of(MovementEvent.WALLRUN_START, MovementEvent.WALL_JUMP)), "Wallrun/jump did not start");
            helper.assertTrue(result.state().velocity().x() * sign < -4, "Outward view did not produce strong jump");
            helper.assertTrue(result.state().mode() == MovementState.Mode.AIR, "Jump remained attached");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void diagonalWallrunCornersDoNotLaunchAlongTheAdjacentFace(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 9; y++) for (int z = 7; z <= 12; z++)
            helper.setBlock(new BlockPos(7, y, z), Blocks.STONE);
        for (int sign : new int[]{-1, 1}) {
            var initial = air(helper, sign == 1 ? 6.6 : 8.4, 1.3, 6.6, new MovementVector(sign * 6, 3, 2));
            var result = compare(helper, initial, 40, (tick, state) -> input(1, -sign * 65, false, false), (tick, state) -> {
                helper.assertTrue(state.velocity().horizontalSpeed() <= initial.velocity().horizontalSpeed() + 1,
                        "Corner retained a wall-snap launch velocity: " + state.velocity());
            });
            helper.assertTrue(result.events().contains(MovementEvent.WALLRUN_START), "Corner fixture did not enter a wallrun");
            helper.assertTrue(!result.events().contains(MovementEvent.WALL_JUMP), "Corner manufactured a wall jump");
            helper.assertTrue(result.state().velocity().horizontalSpeed() <= initial.velocity().horizontalSpeed() + 1,
                    "Corner ended with a sideways launch");
            helper.assertTrue(result.state().position().z() - initial.position().z() < 8, "Corner launched along the adjacent face");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void frontClimbGrabAndPullUpAcrossBlockSeams(GameTestHelper helper) {
        platform(helper);
        var result = compare(helper, air(helper, 4.5, 2.2, 4.65, new MovementVector(0, 3.2, 6)), 40,
                (tick, state) -> input(tick < 25 ? 1 : 0, 0, true, false).withoutEdges());
        helper.assertTrue(result.events().containsAll(List.of(MovementEvent.WALLCLIMB_START, MovementEvent.LEDGE_GRAB,
                MovementEvent.PULL_UP_START, MovementEvent.TRAVERSAL_COMPLETE)), "Climb/grab/pull-up chain failed: " + result.events());
        helper.assertTrue(Math.abs(result.state().position().y() - position(helper, 0, 5, 0).y()) < 1.0E-6, "Not atop platform");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void airborneMantleAndPullUpReleaseDropToHangAndReplay(GameTestHelper helper) {
        platform(helper);
        for (boolean pullUp : new boolean[]{false, true}) {
            int releaseTick = pullUp ? 13 : 5;
            var beforeRelease = new MovementState[1];
            var initial = pullUp ? air(helper, 4.5, 3.2, 4.65, new MovementVector(0, -1, 1))
                    : air(helper, 4.5, 3.4, 4.65, new MovementVector(0, 3, 6));
            var result = compare(helper, initial, releaseTick + 24,
                    (tick, state) -> input(1, 0, tick < releaseTick, false).withoutEdges(), (tick, state) -> {
                        if (tick == releaseTick) {
                            beforeRelease[0] = state;
                            helper.assertTrue(state.mode() == (pullUp ? MovementState.Mode.PULL_UP : MovementState.Mode.MANTLE),
                                    "Release fixture was not traversing");
                        }
                         if (tick > releaseTick) {
                            helper.assertTrue(state.mode() == MovementState.Mode.LEDGE_HANG && state.traversal().path() == null,
                                    "Released airborne traversal stayed in mantle/pull-up");
                             helper.assertTrue(state.position().y() < beforeRelease[0].position().y(), "Release did not lower the player");
                             if (pullUp && tick == releaseTick + 1) {
                                 helper.assertTrue(state.advanced().ledge() != null
                                         && state.position().y() > state.advanced().ledge().to().y(),
                                         "Cancelled pull-up snapped to hang instead of transitioning");
                             }
                        }
                    });
            helper.assertTrue(result.events().containsAll(List.of(MovementEvent.TRAVERSAL_ABORT, MovementEvent.LEDGE_GRAB)),
                    "Released traversal did not drop to a hanging contact");
            helper.assertTrue(!result.events().contains(MovementEvent.TRAVERSAL_COMPLETE), "Released traversal completed anyway");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void mantleAndPullUpReleaseAfterHalfwayFinishOnFirstSupportAndReplay(GameTestHelper helper) {
        platform(helper);
        for (boolean pullUp : new boolean[]{false, true}) {
            var released = new boolean[1];
            var end = new MovementVector[1];
            var initial = pullUp ? air(helper, 4.5, 3.2, 4.65, new MovementVector(0, -1, 1))
                    : air(helper, 4.5, 3.4, 4.65, new MovementVector(0, 3, 6));
            var result = compare(helper, initial, 40, (tick, state) -> {
                var path = state.traversal().path();
                if (!released[0] && path != null && state.modeTime() + 1.0E-9 >= path.kind().duration() * 0.5) {
                    released[0] = true;
                    end[0] = path.end();
                }
                return released[0] ? MovementInput.idle(tick + 1, 0, 0) : input(1, 0, true, false).withoutEdges();
            });
            helper.assertTrue(released[0], "Traversal did not reach the halfway release");
            helper.assertTrue(result.state().grounded() && result.state().mode() == MovementState.Mode.GROUND
                    && result.state().body() == MovementBody.STANDING, "Committed release did not finish standing on support");
            helper.assertTrue(result.state().position().z() < end[0].z(), "Released traversal continued past its first support");
            helper.assertTrue(result.state().velocity().length() < 1.0E-9, "Released traversal retained scripted exit momentum");
            helper.assertTrue(result.events().contains(MovementEvent.TRAVERSAL_COMPLETE), "Committed release did not complete");
            helper.assertTrue(!result.events().contains(MovementEvent.TRAVERSAL_ABORT), "Committed release cancelled back to hang");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void mantleReleaseOnStandableTopBeforeHalfwayStaysOnTopAndReplayMatches(GameTestHelper helper) {
        platform(helper);
        double top = position(helper, 0, 5, 0).y();
        double lip = position(helper, 0, 0, 5).z();
        for (double height : new double[]{1.3, 1.8}) for (double speed : new double[]{1, 6}) {
            var standing = new MovementVector[1];
            var initial = air(helper, 4.5, 5 - height, 4.65, new MovementVector(0, 3, speed));
            var result = compare(helper, initial, 40, (tick, state) -> {
                if (standing[0] == null && state.traversal().path() != null
                        && state.position().y() >= top && state.position().z() + 0.3 > lip) {
                    helper.assertTrue(state.traversal().path().canCancelOnRelease(state.modeTime()),
                            "Standable release fixture was already past the halfway timer");
                    standing[0] = new MovementVector(state.position().x(), top, state.position().z());
                }
                return standing[0] == null ? input(1, 0, true, false).withoutEdges() : MovementInput.idle(tick + 1, 0, 0);
            });
            helper.assertTrue(standing[0] != null, "Mantle never reached a standable top before halfway");
            helper.assertTrue(result.state().grounded() && result.state().mode() == MovementState.Mode.GROUND
                    && result.state().body() == MovementBody.STANDING, "Release pulled a standable mantle back into a hang");
            helper.assertTrue(result.state().position().subtract(standing[0]).length() < 1.0E-6,
                    "Standable release did not stop on the current support");
            helper.assertTrue(result.events().contains(MovementEvent.TRAVERSAL_COMPLETE), "Standable release did not complete");
            helper.assertTrue(!result.events().contains(MovementEvent.TRAVERSAL_ABORT)
                    && !result.events().contains(MovementEvent.LEDGE_GRAB), "Standable release cancelled back to hang");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void cancelledPullUpReturnCanDropRetryOrKickAndReplay(GameTestHelper helper) {
        platform(helper);
        for (int action = 0; action < 3; action++) {
            int choice = action;
            var initial = air(helper, 4.5, 3.2, 4.65, new MovementVector(0, -1, 1));
            var result = compare(helper, initial, 15, (tick, state) -> {
                if (tick < 13) return input(1, 0, true, false).withoutEdges();
                if (tick == 13) return input(1, 0, false, false);
                return choice == 0 ? input(0, 0, false, true) : input(1, choice == 2 ? 180 : 0, true, false);
            }, (tick, state) -> {
                if (tick == 14) helper.assertTrue(state.advanced().ledge() != null, "Missing interruptible return");
            });
            helper.assertTrue(result.state().advanced().ledge() == null, "Input did not interrupt return");
            helper.assertTrue(result.state().mode() == (choice == 1 ? MovementState.Mode.PULL_UP : MovementState.Mode.AIR),
                    "Wrong mode after return interruption");
            helper.assertTrue(result.events().contains(choice == 0 ? MovementEvent.LEDGE_DROP
                    : choice == 1 ? MovementEvent.PULL_UP_START : MovementEvent.WALL_KICK), "Missing interruption event");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void groundedMantleReleaseStopsOnSupportAndReplays(GameTestHelper helper) {
        floor(helper);
        for (int z = 5; z <= 10; z++) helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        var owner = new TestPlayer(helper);
        var feet = position(helper, 4.5, 1, 4.1);
        owner.setPos(MinecraftMotionAdapter.minecraft(feet));
        var path = VaultPlanner.plan(MinecraftMotionAdapter.replay(owner), feet, new MovementVector(0, 7, 7), input(1, 0, true, false));
        helper.assertTrue(path != null, "Grounded mantle fixture had no path");
        for (boolean onTop : new boolean[]{false, true}) {
            var at = onTop ? path.end() : path.from();
            var initial = new MovementState(at, MovementVector.ZERO, MovementState.Mode.MANTLE,
                    onTop ? path.kind().duration() : 0, true, 0, LocomotionState.EMPTY, BlockMotionState.EMPTY,
                    MovementBody.CROUCHING, 0, new TraversalState(path, 0, MovementVector.ZERO));
            var result = compare(helper, initial, 10, (tick, state) -> MovementInput.idle(tick + 1, 0, 0));
            helper.assertTrue(result.state().mode() == MovementState.Mode.GROUND && result.state().grounded(), "Grounded release left support");
            helper.assertTrue(result.state().position().equals(at) && result.state().traversal().path() == null,
                    "Grounded release moved or retained its mantle path");
            helper.assertTrue(!result.events().contains(MovementEvent.LEDGE_GRAB), "Grounded release dropped into a hang");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void shortWallsDropAndTallerWallsAllowHanging(GameTestHelper helper) {
        floor(helper);
        // Low walls still reject hangs whose feet would overlap the outside floor.
        for (double height : new double[]{1, 1.5}) {
            for (int y = 1; y <= 3; y++) for (int z = 5; z <= 10; z++)
                helper.setBlock(new BlockPos(4, y, z), y <= height ? Blocks.STONE : y - 0.5 == height ? Blocks.STONE_SLAB : Blocks.AIR);
            double top = height + 1;
            for (boolean releaseMantle : new boolean[]{false, true}) {
                var initial = air(helper, 4.5, Math.max(1.01, top - (releaseMantle ? 1.6 : 1.8)), 4.65,
                        releaseMantle ? new MovementVector(0, 3, 6) : new MovementVector(0, -1, 0));
                var result = compare(helper, initial, 50,
                        (tick, state) -> input(releaseMantle && tick < 1 ? 1 : 0, 0, releaseMantle && tick < 1, false).withoutEdges(),
                        (tick, state) -> helper.assertTrue(state.mode() != MovementState.Mode.LEDGE_HANG,
                                "Short wall created a hanging contact at height=" + height));
                helper.assertTrue(result.state().mode() == MovementState.Mode.GROUND && result.state().grounded(),
                        "Short-wall drop did not land");
                helper.assertTrue(!result.events().contains(MovementEvent.LEDGE_GRAB), "Short wall was grabbed");
                if (releaseMantle) helper.assertTrue(result.events().contains(MovementEvent.TRAVERSAL_ABORT), "Release fixture did not abort its mantle");
            }
        }
        for (int y = 1; y <= 3; y++) for (int z = 5; z <= 10; z++)
            helper.setBlock(new BlockPos(4, y, z), Blocks.STONE);
        var taller = compare(helper, air(helper, 4.5, 4 - 1.8, 4.65, new MovementVector(0, -1, 0)),
                10, (tick, state) -> input(0, 0, false, false).withoutEdges());
        helper.assertTrue(taller.state().mode() == MovementState.Mode.LEDGE_HANG && !taller.state().grounded()
                && taller.events().contains(MovementEvent.LEDGE_GRAB), "Three-block wall did not retain an airborne hang");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void blockAndSlabJumpReleaseCannotCreateAGroundedCrouchedHang(GameTestHelper helper) {
        floor(helper);
        for (int z = 5; z <= 10; z++) {
            helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
            helper.setBlock(new BlockPos(4, 2, z), Blocks.STONE_SLAB);
        }
        var initial = new MovementState(position(helper, 4.5, 1, 4.1), new MovementVector(0, 0, 6),
                MovementState.Mode.GROUND, 0, true, 0);
        for (int releaseTick : new int[]{1, 5, 12}) {
            var peak = new double[]{initial.position().y()};
            var result = compare(helper, initial, 60,
                    (tick, state) -> input(tick < releaseTick + 10 ? 1 : 0, 0, tick < releaseTick, false).withEdges(tick == 0, false),
                    (tick, state) -> {
                        peak[0] = Math.max(peak[0], state.position().y());
                        helper.assertTrue(state.mode() != MovementState.Mode.LEDGE_HANG,
                                "Block/slab jump created a hanging state near the floor");
                    });
            helper.assertTrue(result.state().mode() == MovementState.Mode.GROUND && result.state().grounded(),
                    "Released block/slab jump did not settle onto support at release tick " + releaseTick
                            + ": " + result.state().mode() + " / " + result.state().position());
            helper.assertTrue(result.state().body() == MovementBody.STANDING, "Released block/slab jump stayed crouched");
            helper.assertTrue(!result.events().contains(MovementEvent.LEDGE_GRAB), "Floor-blocked ledge was grabbed");
            helper.assertTrue(result.events().stream().filter(e -> e == MovementEvent.JUMP).count() == 1,
                    "Mantle release launched a second jump");
            if (releaseTick == 1) helper.assertTrue(peak[0] - initial.position().y() > 1,
                    "Tapping Space into the block/slab truncated the original jump");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void hangDropCooldownAndRemovedLedgeRelease(GameTestHelper helper) {
        platform(helper);
        var initial = air(helper, 4.5, 3.2, 4.65, new MovementVector(0, -1, 1));
        var dropped = compare(helper, initial, 4, (tick, state) -> input(0, 0, false, tick == 2));
        helper.assertTrue(dropped.events().containsAll(List.of(MovementEvent.LEDGE_GRAB, MovementEvent.LEDGE_DROP)), "Grab/drop did not start");
        helper.assertTrue(dropped.state().mode() == MovementState.Mode.AIR && dropped.state().wall().cooldown() > 0, "Drop reattached");
        var removed = compare(helper, initial, 4, (tick, state) -> input(0, 0, false, false), (tick, state) -> {
            if (tick == 2) helper.setBlock(new BlockPos(4, 4, 5), Blocks.AIR);
        });
        helper.assertTrue(removed.events().contains(MovementEvent.WALL_DETACH) && removed.state().mode() == MovementState.Mode.AIR, "Removed support did not release hang");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void blockedPullUpStaysHangingAndThinShapesAllowCrouchedExit(GameTestHelper helper) {
        platform(helper);
        for (int x = 3; x <= 7; x++) for (int z = 5; z <= 10; z++) helper.setBlock(new BlockPos(x, 5, z),
                Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,
                        net.minecraft.world.level.block.state.properties.SlabType.TOP));
        var initial = air(helper, 4.5, 3.2, 4.65, new MovementVector(0, -1, 1));
        var blocked = compare(helper, initial, 20, (tick, state) -> input(1, 0, true, false).withoutEdges());
        helper.assertTrue(blocked.state().mode() == MovementState.Mode.LEDGE_HANG, "Blocked pull-up lost hang");
        for (int x = 3; x <= 7; x++) for (int z = 5; z <= 10; z++) {
            helper.setBlock(new BlockPos(x, 5, z), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 4, z), Blocks.OAK_TRAPDOOR);
            helper.setBlock(new BlockPos(x, 5, z),
                    Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                            net.minecraft.world.level.block.state.properties.Half.TOP));
        }
        // Bottom trapdoor top at 4.1875, top trapdoor underside at 5.8125: 1.625 blocks of headroom.
        var crouched = compare(helper, air(helper, 4.5, 2.4, 4.65, new MovementVector(0, -1, 1)), 25,
                (tick, state) -> input(1, 0, true, false).withoutEdges());
        helper.assertTrue(crouched.events().contains(MovementEvent.TRAVERSAL_COMPLETE) && crouched.state().body() == MovementBody.CROUCHING,
                "Thin-shape roof did not allow crouched pull-up");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void pullUpIntoOneBlockDeepLandingsBeforeAWall(GameTestHelper helper) {
        platform(helper);
        for (int x = 3; x <= 7; x++) for (int y = 5; y <= 7; y++) for (int z = 6; z <= 10; z++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        for (int clearance : new int[]{1, 2}) {
            for (int x = 3; x <= 7; x++) for (int y = 5; y <= 7; y++)
                helper.setBlock(new BlockPos(x, y, 5), y >= 5 + clearance ? Blocks.STONE : Blocks.AIR);
            for (double x : new double[]{3.01, 3.5, 4, 4.5, 7.99}) {
                var result = compare(helper, air(helper, x, 3.2, 4.65, new MovementVector(0, -1, 1)), 35,
                        (tick, state) -> input(state.mode() == MovementState.Mode.GROUND ? 0 : 1, 0, true, false).withoutEdges());
                helper.assertTrue(result.events().containsAll(List.of(MovementEvent.LEDGE_GRAB,
                        MovementEvent.PULL_UP_START, MovementEvent.TRAVERSAL_COMPLETE)),
                        "One-block landing pull-up failed at x=" + x + ", clearance=" + clearance + ": " + result.events());
                helper.assertTrue(!result.events().contains(MovementEvent.TRAVERSAL_ABORT), "One-block landing aborted");
                helper.assertTrue(result.state().grounded() && result.state().mode() == MovementState.Mode.GROUND,
                        "One-block landing did not finish on support");
                helper.assertTrue(Math.abs(result.state().position().y() - position(helper, 0, 5, 0).y()) < 1.0E-6,
                        "Pull-up did not land in the open block");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void offCenterPullUpAlignsWithStoneOpeningBeforeGlassAndReplays(GameTestHelper helper) {
        floor(helper);
        for (int x = 5; x <= 10; x++) for (int y = 1; y <= 4; y++) for (int z = 4; z <= 6; z++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        for (int x = 5; x <= 10; x++) for (int y = 5; y <= 7; y++) for (int z : new int[]{4, 6})
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        for (int x = 6; x <= 10; x++) for (int y = 5; y <= 7; y++)
            helper.setBlock(new BlockPos(x, y, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 5, 5), Blocks.GLASS_PANE);
        var hanging = compare(helper, air(helper, 4.65, 3.2, 5.5, new MovementVector(1, -1, 0)), 1,
                (tick, state) -> input(0, -90, false, false)).state();
        helper.assertTrue(hanging.mode() == MovementState.Mode.LEDGE_HANG, "Opening fixture did not grab the stone lip");
        for (int clearance : new int[]{1, 2}) {
            helper.setBlock(new BlockPos(5, 6, 5), clearance == 1 ? Blocks.STONE : Blocks.AIR);
            helper.setBlock(new BlockPos(5, 7, 5), Blocks.STONE);
            for (double z : new double[]{5.109, 5.891}) {
                var feet = position(helper, 4.7, 5 - 1.828, z);
                var initial = new MovementState(feet, MovementVector.ZERO, MovementState.Mode.LEDGE_HANG, 0, false, 0,
                        hanging.locomotion(), hanging.blocks(), hanging.body(), 0, TraversalState.EMPTY,
                        hanging.wall().contact(hanging.wall().normal(), hanging.wall().support(), feet, MovementVector.ZERO, false, 0));
                var result = compare(helper, initial, 35,
                        (tick, state) -> input(state.mode() == MovementState.Mode.GROUND ? 0 : 1, -90, true, false).withoutEdges());
                helper.assertTrue(result.events().containsAll(List.of(MovementEvent.PULL_UP_START, MovementEvent.TRAVERSAL_COMPLETE)),
                        "Off-center pull-up failed at z=" + z + ", clearance=" + clearance + ": " + result.events());
                helper.assertTrue(!result.events().contains(MovementEvent.TRAVERSAL_ABORT), "Aligned pull-up aborted");
                helper.assertTrue(result.state().grounded() && result.state().mode() == MovementState.Mode.GROUND,
                        "Aligned pull-up did not finish on support");
                helper.assertTrue(Math.abs(result.state().position().y() - position(helper, 0, 5, 0).y()) < 1.0E-6,
                        "Aligned pull-up did not land before the pane");
                double alignedZ = result.state().position().z() - position(helper, 0, 0, 0).z();
                helper.assertTrue(alignedZ >= 5.3 && alignedZ <= 5.7, "Pull-up did not align with the opening");
                helper.assertTrue(result.state().body() == (clearance == 1 ? MovementBody.LOW : MovementBody.STANDING),
                        "Aligned pull-up did not use the fitting exit pose");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void removedRunningWallReleasesContact(GameTestHelper helper) {
        floor(helper);
        for (int y = 1; y <= 7; y++) for (int z = 2; z <= 12; z++) helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
        var result = compare(helper, air(helper, 5.6, 1.3, 3.5, new MovementVector(2, 3, 6)), 8,
                (tick, state) -> input(1, -25, false, false), (tick, state) -> {
                    if (tick == 4) for (int y = 1; y <= 7; y++) for (int z = 2; z <= 12; z++) helper.setBlock(new BlockPos(6, y, z), Blocks.AIR);
                });
        helper.assertTrue(result.events().containsAll(List.of(MovementEvent.WALLRUN_START, MovementEvent.WALL_DETACH)), "Removed wall did not detach");
        helper.assertTrue(result.state().mode() == MovementState.Mode.AIR, "Removed-wall mode remained attached");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void strafeJumpAndNativeCoilReplay(GameTestHelper helper) {
        floor(helper);
        var initial = new MovementState(position(helper, 4.5, 1, 3.5), new MovementVector(0, 0, 6), MovementState.Mode.GROUND, 0, true, 0);
        var jump = compare(helper, initial, 8, (tick, state) -> new MovementInput(tick + 1, 1, 1, 1,
                true, tick == 0, false, false, true, 0, 0));
        helper.assertTrue(jump.events().contains(MovementEvent.JUMP), "Diagonal sprint jump did not use ordinary launch");
        helper.assertTrue(Math.abs(jump.state().velocity().x()) < 1, "Diagonal sprint jump injected a sideways launch");
        var coil = compare(helper, initial, 10, (tick, state) -> new MovementInput(tick + 1, 1, 0, 0,
                tick == 0, tick == 0, tick >= 2 && tick < 5, tick == 2, true, 0, 0));
        helper.assertTrue(coil.events().containsAll(List.of(MovementEvent.COIL_START, MovementEvent.COIL_END)), "Coil/release did not occur");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void gameplayAirTurnAndOrdinaryLandingReplay(GameTestHelper helper) {
        floor(helper);
        var initial = new MovementState(position(helper, 4.5, 1, 3.5), new MovementVector(0, 0, 4), MovementState.Mode.GROUND, 0, true, 0);
        var result = compare(helper, initial, 80, (tick, state) -> new MovementInput(tick + 1, tick == 0 ? 1 : 0, 0, 0,
                tick == 0 || tick == 45, tick == 0 || tick == 45, false, false, true,
                tick < 2 ? 0 : 180, 0, tick == 1));
        helper.assertTrue(result.events().containsAll(List.of(MovementEvent.TURN, MovementEvent.LAND)), "Turn/landing chain failed");
        helper.assertTrue(result.events().stream().filter(event -> event == MovementEvent.JUMP).count() == 2, "Post-turn landing blocked another jump");
        helper.assertTrue(!result.events().contains(MovementEvent.LANDING_RECOVERED), "Air turn added a landing recovery");
        helper.assertTrue(result.state().mode() == MovementState.Mode.GROUND && result.state().body() == MovementBody.STANDING, "Turn did not finish standing on the ground");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void springboardUsesSlabAndBlockShape(GameTestHelper helper) {
        floor(helper);
        for (int x = 3; x <= 6; x++) {
            helper.setBlock(new BlockPos(x, 1, 5), Blocks.STONE_SLAB);
            for (int z = 6; z <= 8; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        }
        var initial = new MovementState(position(helper, 4.5, 1, 3.5), new MovementVector(0, 0, 6), MovementState.Mode.GROUND, 0, true, 0);
        var result = compare(helper, initial, 12, (tick, state) -> new MovementInput(tick + 1, 1, 0, 0,
                true, tick == 0, false, false, true, 0, 0));
        helper.assertTrue(result.events().containsAll(List.of(MovementEvent.SPRINGBOARD_START, MovementEvent.TRAVERSAL_COMPLETE)), "Slab/block springboard failed: " + result.events());
        helper.assertTrue(result.state().velocity().y() > 6, "Springboard launch lost upward speed");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ledgeShimmyCrossesRealBlockSeams(GameTestHelper helper) {
        platform(helper);
        var initial = air(helper, 4.5, 3.2, 4.65, new MovementVector(0, -1, 1));
        var result = compare(helper, initial, 50, (tick, state) -> new MovementInput(tick + 1, 0, tick >= 14 ? 1 : 0, tick >= 14 ? 1 : 0,
                false, false, false, false, false, 0, 0));
        helper.assertTrue(result.events().containsAll(List.of(MovementEvent.LEDGE_GRAB, MovementEvent.SHIMMY_START)), "Shimmy did not start");
        helper.assertTrue(result.state().position().x() > position(helper, 5.2, 1, 0).x(), "Shimmy stopped at a block seam");
        helper.assertTrue(result.state().mode() == MovementState.Mode.LEDGE_HANG, "Shimmy lost hang");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ledgeShimmyFollowsAdjacentTopAndBottomTrapdoorHeights(GameTestHelper helper) {
        floor(helper);
        var top = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                net.minecraft.world.level.block.state.properties.Half.TOP);
        for (int x = 3; x <= 4; x++) helper.setBlock(new BlockPos(x, 3, 5), top);
        for (int x = 5; x <= 7; x++) helper.setBlock(new BlockPos(x, 4, 5), Blocks.OAK_TRAPDOOR);
        // The upper half at Y=3 ends at 4; the lower half at Y=4 ends at 4.1875.
        for (int sign : new int[]{-1, 1}) {
            double startX = sign > 0 ? 4.7 : 5.3;
            double startTop = sign > 0 ? 4 : 4.1875;
            double endTop = sign > 0 ? 4.1875 : 4;
            var initial = air(helper, startX, startTop - 1.8, 4.65, new MovementVector(0, -1, 0));
            var previousY = new double[]{Double.NaN};
            var result = compare(helper, initial, 40,
                    (tick, state) -> new MovementInput(tick + 1, 0, tick == 14 ? sign : 0, tick == 14 ? sign : 0,
                            false, false, false, false, false, 0, 0),
                    (tick, state) -> {
                        if (tick >= 14) {
                            helper.assertTrue(state.mode() == MovementState.Mode.LEDGE_HANG, "Trapdoor shimmy lost its grip");
                            if (!Double.isNaN(previousY[0])) helper.assertTrue(Math.abs(state.position().y() - previousY[0]) < 0.03,
                                    "Trapdoor shimmy snapped to the new height");
                            previousY[0] = state.position().y();
                        }
                    });
            helper.assertTrue(result.events().containsAll(List.of(MovementEvent.LEDGE_GRAB, MovementEvent.SHIMMY_START)),
                    "Trapdoor shimmy did not start");
            helper.assertTrue(Math.abs(result.state().position().x() - position(helper, startX + sign * 0.6, 0, 0).x()) < 1.0E-6,
                    "Shimmy stopped at the trapdoor height change");
            helper.assertTrue(Math.abs(result.state().position().y() - position(helper, 0, endTop - 1.828, 0).y()) < 1.0E-6,
                    "Shimmy did not follow the destination trapdoor height");
            helper.assertTrue(!result.events().contains(MovementEvent.WALL_DETACH), "Trapdoor shimmy detached");
        }
        helper.succeed();
    }
}
