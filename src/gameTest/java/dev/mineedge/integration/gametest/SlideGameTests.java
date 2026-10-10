package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.MinecraftCollisionWorld;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementEligibility;
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
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SlideGameTests {
    private static final MovementController CONTROLLER = new MovementController();

    private static final class TestPlayer extends FakePlayer {
        TestPlayer(GameTestHelper helper) {
            super(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeSlide"));
        }

        void updatePose() { super.updatePlayerPose(); }
    }

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

    private static MovementInput input(int tick, boolean crouch, boolean pressed, boolean jump, float forward) {
        return new MovementInput(tick, forward, 0, 0, jump, jump, crouch, pressed, true, 0, 0);
    }

    private static MovementVector position(GameTestHelper helper, double x, double y, double z) {
        return MinecraftMotionAdapter.core(helper.absoluteVec(new Vec3(x, y, z)));
    }

    private static MovementState moving(GameTestHelper helper, double z) {
        return new MovementState(position(helper, 4.5, 1, z), new MovementVector(0, 0, 6),
                MovementState.Mode.GROUND, 1, true, 0);
    }

    private static MovementState compare(GameTestHelper helper, MovementState baseline, int ticks, IntFunction<MovementInput> inputs) {
        TestPlayer live = new TestPlayer(helper);
        TestPlayer replayOwner = new TestPlayer(helper);
        MinecraftMotionAdapter.apply(live, baseline);
        MinecraftMotionAdapter.apply(replayOwner, baseline);
        var session = MovementRuntime.session(live);
        session.active = true;
        session.tuning = MovementTuning.DEFAULT;
        session.state = baseline;
        var ownerPosition = replayOwner.position();
        var ownerPose = replayOwner.getPose();
        var events = new ArrayList<MovementEvent>();
        MovementState actual = baseline;
        MovementState predicted = baseline;
        for (int tick = 1; tick <= ticks; tick++) {
            MovementInput input = inputs.apply(tick);
            actual = CONTROLLER.tick(actual, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(live), events::add);
            session.state = actual;
            live.updatePose(); // Player.tick's final pose update must not overwrite a slide or unsafe exit.
            predicted = CONTROLLER.tick(predicted, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.replay(replayOwner), event -> {});
            helper.assertTrue(actual.position().subtract(predicted.position()).length() < 1.0E-6,
                    "Slide position diverged at tick " + tick + ": " + actual.position() + " / " + predicted.position());
            helper.assertTrue(actual.velocity().subtract(predicted.velocity()).length() < 1.0E-6,
                    "Slide velocity diverged at tick " + tick + ": " + actual.velocity() + " / " + predicted.velocity());
            helper.assertTrue(actual.mode() == predicted.mode() && actual.body() == predicted.body(), "Slide replay mode/body disagreed");
            helper.assertTrue(Math.abs(actual.modeTime() - predicted.modeTime()) < 1.0E-9 && actual.slideYaw() == predicted.slideYaw(),
                    "Slide timer/yaw disagreed");
            helper.assertTrue(live.getPose() == MovementPoses.pose(actual.body()), "Vanilla overwrote the managed pose");
            helper.assertTrue(MinecraftCollisionWorld.canOccupy(live, actual.position(), actual.body()), "Body clipped the fixture");
        }
        helper.assertTrue(replayOwner.position().equals(ownerPosition) && replayOwner.getPose() == ownerPose,
                "Replay mutated its owner's position or pose");
        helper.assertTrue(events.stream().filter(event -> event == MovementEvent.SLIDE_START).count() <= 1,
                "Slide entered more than once from a held key");
        return actual;
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void slideThroughOneBlockGapWithSafeExitAndReplay(GameTestHelper helper) {
        floor(helper);
        for (int z = 4; z <= 6; z++) {
            helper.setBlock(new BlockPos(4, 2, z), Blocks.STONE);
        }
        MovementState state = compare(helper, moving(helper, 3.5), 65,
                tick -> input(tick, tick <= 3, tick == 1, tick == 8, 1));
        helper.assertTrue(state.position().z() > position(helper, 4.5, 1, 7.3).z(), "Player did not clear the gap");
        helper.assertTrue(state.body() == MovementBody.STANDING, "Player did not stand after clearing the gap");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void slideExitStaysLowUntilHeadroomReturns(GameTestHelper helper) {
        floor(helper);
        for (int z = 1; z <= 13; z++) {
            helper.setBlock(new BlockPos(4, 2, z), Blocks.STONE);
        }
        MovementState baseline = new MovementState(position(helper, 4.5, 1, 4.5), new MovementVector(0, 0, 4),
                MovementState.Mode.SLIDE, 0.45, true, 0, LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.LOW, 0);
        MovementState state = compare(helper, baseline, 8, tick -> input(tick, false, false, tick == 2, 0));
        helper.assertTrue(state.body() == MovementBody.LOW && state.mode() == MovementState.Mode.GROUND,
                "Slide did not safely exit to a low body");
        for (int z = 1; z <= 13; z++) {
            helper.setBlock(new BlockPos(4, 2, z), Blocks.AIR);
        }
        state = compare(helper, state, 1, tick -> input(tick, false, false, false, 0));
        helper.assertTrue(state.body() == MovementBody.STANDING, "Cleared headroom did not restore standing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void crouchFitsSlabHeadroomAndCannotSprint(GameTestHelper helper) {
        floor(helper);
        for (int z = 1; z <= 13; z++) {
            helper.setBlock(new BlockPos(4, 2, z), Blocks.STONE_SLAB.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
        }
        MovementState baseline = new MovementState(position(helper, 4.5, 1, 4.5), MovementVector.ZERO,
                MovementState.Mode.GROUND, 0, true, 0, LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.CROUCHING, 0);
        MovementState state = compare(helper, baseline, 35, tick -> input(tick, false, false, false, 1));
        helper.assertTrue(state.body() == MovementBody.CROUCHING, "Standing grew into slab headroom");
        helper.assertTrue(state.velocity().horizontalSpeed() <= 1.440001, "Crouch used sprint speed");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void heldCrouchSlideLeavesEdgeWithoutVanillaSneakTrimming(GameTestHelper helper) {
        floor(helper);
        for (int z = 5; z <= 13; z++) {
            for (int x = 1; x <= 13; x++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.AIR);
            }
        }
        // Observe before landing on the GameTest superflat ground one block below the fixture.
        MovementState state = compare(helper, moving(helper, 4.5), 4, tick -> input(tick, true, tick == 1, false, 0));
        helper.assertTrue(state.mode() == MovementState.Mode.AIR && !state.grounded(), "Slide did not leave the edge");
        helper.assertTrue(state.velocity().horizontalSpeed() > 2.4 && state.velocity().horizontalSpeed() < 3.1,
                "Edge exit did not halve momentum once");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void lowPoseMetadataEyeHeightAndVanillaHandoff(GameTestHelper helper) {
        floor(helper);
        TestPlayer player = new TestPlayer(helper);
        TestPlayer observer = new TestPlayer(helper);
        var session = MovementRuntime.session(player);
        session.active = true;
        session.tuning = MovementTuning.DEFAULT;
        session.state = new MovementState(position(helper, 4.5, 1, 4.5), new MovementVector(0, 0, 5),
                MovementState.Mode.SLIDE, 0.1, true, 0, LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.LOW, 0);
        MinecraftMotionAdapter.apply(player, session.state);
        player.updatePose();
        helper.assertTrue(player.getPose() == Pose.SWIMMING && Math.abs(player.getBbHeight() - 0.6) < 1.0E-6,
                "Low pose did not install native slide dimensions");
        helper.assertTrue(Math.abs(player.getEyeHeight() - 0.4) < 1.0E-6, "Low pose eye height was not updated");
        observer.getEntityData().assignValues(player.getEntityData().packDirty());
        helper.assertTrue(observer.getPose() == Pose.SWIMMING && observer.getBbHeight() == player.getBbHeight(),
                "Native tracked pose did not reproduce observer dimensions");
        player.getAbilities().flying = true;
        helper.assertTrue(!MovementEligibility.supported(player), "Flight did not yield ownership");
        session.reset();
        player.setShiftKeyDown(false);
        player.updatePose();
        helper.assertTrue(player.getPose() == Pose.STANDING, "Handoff left a managed low pose in open space");
        observer.getEntityData().assignValues(player.getEntityData().packDirty());
        helper.assertTrue(observer.getPose() == Pose.STANDING, "Observer did not receive restored standing pose");
        helper.succeed();
    }

    private SlideGameTests() {}
}
