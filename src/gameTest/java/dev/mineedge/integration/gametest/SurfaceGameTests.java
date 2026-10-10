package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.*;
import dev.mineedge.movement.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurfaceGameTests {
    private static final MovementController CONTROLLER = new MovementController();
    private static final class TestPlayer extends FakePlayer {
        int falls;
        TestPlayer(GameTestHelper helper) {
            super(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeSurfaces"));
            try {
                var grace = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime"); grace.setAccessible(true); grace.setInt(this, 0);
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        }
        @Override public boolean isInvulnerableTo(DamageSource source) { return false; }
        @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
            if (distance > 3) falls++;
            return super.causeFallDamage(distance, multiplier, source);
        }
        void updatePose() { super.updatePlayerPose(); }
    }
    private record Result(MovementState state, List<MovementEvent> events, float damage, int falls) {}
    private static MovementVector p(GameTestHelper h, double x, double y, double z) { return MinecraftMotionAdapter.core(h.absoluteVec(new Vec3(x, y, z))); }
    private static void floor(GameTestHelper h, Block block) {
        for (int x = 1; x <= 13; x++) for (int z = 1; z <= 13; z++) h.setBlock(new BlockPos(x, 0, z), block);
        var at = h.absolutePos(new BlockPos(7, 0, 7));
        for (int x = (at.getX() >> 4) - 2; x <= (at.getX() >> 4) + 2; x++)
            for (int z = (at.getZ() >> 4) - 2; z <= (at.getZ() >> 4) + 2; z++) h.getLevel().getChunk(x, z);
    }
    private static void roof(GameTestHelper h) {
        floor(h, Blocks.STONE);
        for (int x = 3; x <= 6; x++) for (int y = 1; y <= 4; y++) for (int z = 5; z <= 10; z++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
    }
    private static void ladder(GameTestHelper h) {
        roof(h);
        for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(4, y, 4), Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }
    private static MovementState ground(GameTestHelper h, double z, double speed) {
        return new MovementState(p(h, 4.5, 1, z), new MovementVector(0, 0, speed), MovementState.Mode.GROUND, 0, true, 0);
    }
    private static MovementInput input(int n, float forward, boolean jump, boolean crouch) {
        return new MovementInput(n + 1, forward, 0, 0, jump, jump, crouch, crouch, false, 0, 0);
    }
    private static Result compare(GameTestHelper h, MovementState initial, int ticks,
                                  BiFunction<Integer, MovementState, MovementInput> commands, BiConsumer<Integer, MovementState> change) {
        var live = new TestPlayer(h); var owner = new TestPlayer(h);
        MinecraftMotionAdapter.apply(live, initial); MinecraftMotionAdapter.apply(owner, initial);
        var session = MovementRuntime.session(live); session.active = true; session.tuning = MovementTuning.DEFAULT; session.state = initial;
        var actual = initial; var predicted = initial; var events = new ArrayList<MovementEvent>();
        Vec3 baseline = owner.position(); var pose = owner.getPose();
        for (int tick = 0; tick < ticks; tick++) {
            change.accept(tick, actual); var input = commands.apply(tick, actual);
            actual = CONTROLLER.tick(actual, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(live), events::add);
            session.state = actual; live.updatePose();
            predicted = CONTROLLER.tick(predicted, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.replay(owner), e -> {});
            h.assertTrue(actual.position().subtract(predicted.position()).length() < 1.0E-6, "Surface position replay diverged at " + tick + ": " + actual.position() + " / " + predicted.position());
            h.assertTrue(actual.velocity().subtract(predicted.velocity()).length() < 1.0E-6, "Surface velocity replay diverged at " + tick + ": " + actual.velocity() + " / " + predicted.velocity());
            h.assertTrue(actual.mode() == predicted.mode() && actual.body() == predicted.body(), "Surface mode/body replay diverged at " + tick);
            h.assertTrue(actual.surfaces().equals(predicted.surfaces()), "Surface contact/path replay diverged at " + tick);
            h.assertTrue(Math.abs(actual.fallDistance() - predicted.fallDistance()) < 1.0E-5, "Surface fall history replay diverged at " + tick);
            h.assertTrue(MinecraftCollisionWorld.canOccupy(live, actual.position(), actual.body()), "Surface move clipped geometry");
            h.assertTrue(live.getPose() == MovementPoses.pose(actual.body()), "Native pose overrode surface body");
        }
        h.assertTrue(owner.position().equals(baseline) && owner.getPose() == pose && owner.falls == 0 && owner.getHealth() == 20,
                "Surface replay mutated its owner or applied damage");
        return new Result(actual, events, 20 - live.getHealth(), live.falls);
    }
    private static Result compare(GameTestHelper h, MovementState initial, int ticks, BiFunction<Integer, MovementState, MovementInput> commands) {
        return compare(h, initial, ticks, commands, (n, state) -> {});
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ladderClimbsThroughManagedOwnershipOntoRoof(GameTestHelper h) {
        ladder(h);
        var result = compare(h, ground(h, 3.5, 2), 160,
                (n, s) -> input(n, s.mode() == MovementState.Mode.GROUND && s.position().y() > p(h, 0, 4.9, 0).y() ? 0 : 1, false, false));
        h.assertTrue(result.events.containsAll(List.of(MovementEvent.LADDER_GRAB, MovementEvent.CLIMB_STEP, MovementEvent.CLIMB_EXIT)), "Ladder did not grab/climb/exit: " + result.events);
        h.assertTrue(result.state.mode() == MovementState.Mode.GROUND && Math.abs(result.state.position().y() - p(h, 0, 5, 0).y()) < 1.0E-6, "Ladder did not finish atop roof");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ironBarsAndVerticalChainsAndLightningRodsActAsDrainpipes(GameTestHelper h) {
        for (BlockState block : new BlockState[]{Blocks.IRON_BARS.defaultBlockState(), Blocks.CHAIN.defaultBlockState(),
                Blocks.LIGHTNING_ROD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP),
                Blocks.LIGHTNING_ROD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN)}) {
            roof(h);
            for (int y = 1; y <= 5; y++) h.setBlock(new BlockPos(4, y, 4), block);
            var result = compare(h, ground(h, 3.5, 2), 180,
                    (n, s) -> input(n, s.mode() == MovementState.Mode.GROUND && s.position().y() > p(h, 0, 4.9, 0).y() ? 0 : 1, false, false));
            h.assertTrue(result.events.containsAll(List.of(MovementEvent.PIPE_GRAB, MovementEvent.CLIMB_STEP, MovementEvent.CLIMB_EXIT)), "Pipe did not climb/exit: " + block + " " + result.events);
            double height = result.state.position().y() - p(h, 0, 0, 0).y();
            h.assertTrue((Math.abs(height - 5) < 1.0E-6 || Math.abs(height - 6) < 1.0E-6)
                    && result.state.mode() == MovementState.Mode.GROUND, "Pipe did not finish on a standable roof or column top");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void stoneWallPipesCircleInBothDirectionsAndReverseWithoutDropping(GameTestHelper h) {
        floor(h, Blocks.STONE);
        for (Block block : new Block[]{Blocks.COBBLESTONE_WALL, Blocks.STONE_BRICK_WALL, Blocks.OAK_FENCE,
                Blocks.IRON_BARS, Blocks.CHAIN, Blocks.LIGHTNING_ROD}) {
            for (int y = 1; y <= 6; y++) h.setBlock(new BlockPos(6, y, 6), block);
            for (float strafe : new float[]{-1, 1}) {
                var initial = new MovementState(p(h, 6.5, 3, 5.6), MovementVector.ZERO,
                        MovementState.Mode.AIR, 0, false, 0);
                MovementVector[] start = {null};
                var result = compare(h, initial, 140,
                        (n, s) -> new MovementInput(n + 1, 0, n < 20 ? 0 : n < 80 ? strafe : -strafe,
                                n < 20 ? 0 : n < 80 ? strafe : -strafe,
                                false, false, false, false, false, 0, 0),
                        (n, s) -> {
                            if (n == 20) start[0] = s.position();
                            if (n >= 20) h.assertTrue(s.mode() == MovementState.Mode.PIPE_CLIMB
                                    && s.surfaces().climb() != null, "Sideways input dropped the pipe grip: " + block);
                            if (n == 21) h.assertTrue((s.position().x() - start[0].x()) * strafe > 0,
                                    "A/D moved in the wrong direction: " + block);
                            if (n == 80) h.assertTrue(s.position().z() > p(h, 6.5, 3, 6.5).z() + 0.5,
                                    "A/D could not pass the stone-wall corner to the rear: " + block);
                        });
                h.assertTrue(result.state.position().subtract(start[0]).length() < 1.0E-6,
                        "Reversing A/D did not return to the original pipe face: " + block);
                h.assertTrue(!result.events.contains(MovementEvent.CLIMB_RELEASE), "Circling released the pipe: " + block);
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void blockedRoofAndRemovedLadderReleaseSafely(GameTestHelper h) {
        ladder(h);
        for (int x = 3; x <= 6; x++) for (int z = 4; z <= 10; z++) h.setBlock(new BlockPos(x, 6, z), Blocks.STONE);
        var blocked = compare(h, ground(h, 3.8, 2), 120, (n, s) -> input(n, 1, false, false));
        h.assertTrue(blocked.state.mode() == MovementState.Mode.LADDER_CLIMB && !blocked.events.contains(MovementEvent.CLIMB_EXIT), "Blocked ladder roof did not retain attachment");
        var removed = compare(h, ground(h, 3.8, 2), 24, (n, s) -> input(n, n < 20 ? 1 : 0, false, false),
                (n, s) -> { if (n == 20) h.setBlock(new BlockPos(4, 2, 4), Blocks.AIR); });
        h.assertTrue(removed.events.contains(MovementEvent.CLIMB_RELEASE) && removed.state.surfaces().climb() == null, "Removed ladder did not release");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void tagsPreserveVineHandoffAndRejectHorizontalPipesWithoutLoadingChunks(GameTestHelper h) {
        ladder(h); var player = new TestPlayer(h); player.setPos(MinecraftMotionAdapter.minecraft(p(h, 4.5, 1, 4.4)));
        h.assertTrue(player.onClimbable() && MovementEligibility.supported(player), "Tagged ladder still yielded movement to vanilla");
        h.setBlock(new BlockPos(4, 1, 4), Blocks.VINE.defaultBlockState().setValue(BlockStateProperties.SOUTH, true));
        h.assertTrue(player.onClimbable() && !MovementEligibility.supported(player), "Untagged vines did not retain vanilla ownership");
        for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(4, y, 4), Blocks.CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        var surfaces = new MinecraftMovementSurfaces(player);
        h.assertTrue(surfaces.climb(p(h, 4.5, 1, 3.8), null) == null, "Horizontal chain became a drainpipe");
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(4, y, 4),
                    Blocks.LIGHTNING_ROD.defaultBlockState().setValue(BlockStateProperties.FACING, facing));
            h.assertTrue(surfaces.climb(p(h, 4.5, 1, 3.8), null) == null, "Horizontal lightning rod became a drainpipe: " + facing);
        }
        for (int y = 1; y <= 33; y++) {
            h.setBlock(new BlockPos(4, y, 5), Blocks.STONE);
            h.setBlock(new BlockPos(4, y, 4), Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        }
        h.assertTrue(player.onClimbable() && !MovementEligibility.supported(player), "Oversized ladder did not retain vanilla ownership");
        var distant = p(h, 65536, 1, 65536); int cx = (int) Math.floor(distant.x()) >> 4, cz = (int) Math.floor(distant.z()) >> 4;
        h.assertTrue(h.getLevel().getChunkForCollisions(cx, cz) == null, "Distant test chunk was already loaded");
        h.assertTrue(surfaces.climb(distant, null) == null && surfaces.support(distant, MovementBody.STANDING) == null, "Unloaded surface query returned a route");
        h.assertTrue(h.getLevel().getChunkForCollisions(cx, cz) == null, "Surface queries loaded a distant chunk");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void taggedAndUntaggedLowShapesStepWithoutJumping(GameTestHelper h) {
        var shapes = new net.minecraft.world.level.block.state.BlockState[]{Blocks.STONE_SLAB.defaultBlockState(),
                Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 5), Blocks.WHITE_CARPET.defaultBlockState(),
                Blocks.STONECUTTER.defaultBlockState(), Blocks.DAYLIGHT_DETECTOR.defaultBlockState()};
        double[] heights = {1.5, 1.5, 1.0625, 1.5625, 1.375};
        for (int i = 0; i < shapes.length; i++) {
            floor(h, Blocks.STONE); h.setBlock(new BlockPos(4, 1, 5), shapes[i]); final double high = heights[i];
            var result = compare(h, ground(h, 4.3, 3), 30,
                    (n, s) -> input(n, s.mode() == MovementState.Mode.GROUND && s.position().y() >= p(h, 0, high - 0.01, 0).y() ? 0 : 1, false, false));
            h.assertTrue(result.events.containsAll(List.of(MovementEvent.STEP_UP_START, MovementEvent.STEP_UP_COMPLETE)) && !result.events.contains(MovementEvent.JUMP), "Low shape did not step without jumping: " + shapes[i]);
            h.assertTrue(Math.abs(result.state.position().y() - p(h, 0, high, 0).y()) < 1.0E-6, "Step did not finish on the actual collision top");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void crawlingCanExitATunnelOverAClosedTrapdoor(GameTestHelper h) {
        floor(h, Blocks.STONE);
        for (int z = 3; z <= 5; z++) h.setBlock(new BlockPos(4, 2, z), Blocks.STONE);
        h.setBlock(new BlockPos(4, 1, 5), Blocks.OAK_TRAPDOOR);
        var result = compare(h, ground(h, 4.7, 0), 60,
                (n, s) -> input(n, s.position().z() < p(h, 0, 0, 6.5).z() ? 1 : 0, false, false));
        h.assertTrue(result.events.containsAll(List.of(MovementEvent.STEP_UP_START, MovementEvent.STEP_UP_COMPLETE)),
                "Crawling did not step over the trapdoor lip");
        h.assertTrue(result.state.position().z() >= p(h, 0, 0, 6.5).z() && result.state.body() == MovementBody.STANDING,
                "Player remained stuck inside the crawl tunnel");
        h.assertTrue(!result.events.contains(MovementEvent.JUMP), "Escaping the crawl tunnel required a jump");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void stepCollisionMatchesVanillaShapesHeadroomAndLanding(GameTestHelper h) {
        floor(h, Blocks.STONE);
        var vanilla = new TestPlayer(h);
        var simulated = new TestPlayer(h);
        var queries = new CollisionQueries(simulated);
        var states = new net.minecraft.world.level.block.state.BlockState[]{Blocks.STONE_SLAB.defaultBlockState(),
                Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
                Blocks.STONECUTTER.defaultBlockState(), Blocks.DAYLIGHT_DETECTOR.defaultBlockState(),
                Blocks.FARMLAND.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState()};
        for (var state : states) for (double height : new double[]{0, 0.6, 1})
            for (boolean landing : new boolean[]{false, true}) for (boolean roof : new boolean[]{false, true})
                for (double sideways : new double[]{0, 0.2, 0.5}) {
                    h.setBlock(new BlockPos(4, 1, 5), state);
                    h.setBlock(new BlockPos(4, 3, 4), roof ? Blocks.STONE : Blocks.AIR);
                    h.setBlock(new BlockPos(4, 3, 5), roof ? Blocks.STONE : Blocks.AIR);
                    var feet = p(h, 4.5, landing ? 1.2 : 1, 4.65);
                    var delta = new MovementVector(sideways, landing ? -0.3 : -0.01, 0.35);
                    vanilla.setPos(MinecraftMotionAdapter.minecraft(feet));
                    vanilla.setOnGround(!landing);
                    vanilla.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(height);
                    simulated.setPos(MinecraftMotionAdapter.minecraft(feet));
                    queries.begin(); queries.stepHeight(height); queries.grounded(!landing);
                    var actual = queries.resolve(feet, delta, MovementBody.STANDING);
                    vanilla.move(MoverType.SELF, MinecraftMotionAdapter.minecraft(delta));
                    var expected = MinecraftMotionAdapter.core(vanilla.position()).subtract(feet);
                    h.assertTrue(actual.displacement().subtract(expected).length() < 1.0E-7,
                            "Step differs from vanilla for " + state + ", height=" + height + ", landing=" + landing
                                    + ", roof=" + roof + ", sideways=" + sideways + ": " + actual.displacement() + " / " + expected);
                    h.assertTrue(actual.grounded() == vanilla.onGround(), "Step ground contact differs from vanilla for " + state);
                }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void stepHeadroomAndRemovedSupportUseNormalCollision(GameTestHelper h) {
        floor(h, Blocks.STONE); h.setBlock(new BlockPos(4, 1, 5), Blocks.STONE_SLAB);
        h.setBlock(new BlockPos(4, 3, 5), Blocks.STONE);
        var blocked = compare(h, ground(h, 4.3, 3), 8, (n, s) -> input(n, 1, false, false));
        h.assertTrue(!blocked.events.contains(MovementEvent.STEP_UP_START), "Step ignored low headroom");
        h.setBlock(new BlockPos(4, 3, 5), Blocks.AIR);
        var removed = compare(h, ground(h, 4.65, 3), 6, (n, s) -> input(n, 1, false, false),
                (n, s) -> { if (n == 2) h.setBlock(new BlockPos(4, 1, 5), Blocks.AIR); });
        h.assertTrue(removed.events.contains(MovementEvent.STEP_UP_COMPLETE) && !removed.events.contains(MovementEvent.STEP_UP_ABORT), "Instant step retained an animated abort path");
        h.assertTrue(removed.state.surfaces().path() == null && removed.state.position().y() < p(h, 0, 1.5, 0).y(), "Removed support did not return to normal falling");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ordinaryStairTreadsStepOntoAnUntaggedRoofWithoutSteepReaction(GameTestHelper h) {
        floor(h, Blocks.STONE);
        h.setBlock(new BlockPos(4, 1, 5), Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        for (int z = 6; z <= 10; z++) h.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        var result = compare(h, ground(h, 4.3, 3), 70,
                (n, s) -> input(n, s.mode() == MovementState.Mode.GROUND && s.position().y() >= p(h, 0, 1.99, 0).y() ? 0 : 1, false, false));
        h.assertTrue(result.events.stream().filter(e -> e == MovementEvent.STEP_UP_COMPLETE).count() >= 2,
                "Stair risers did not both step up: " + result.events + " / " + result.state.mode() + " at " + result.state.position());
        h.assertTrue(result.state.mode() == MovementState.Mode.GROUND && Math.abs(result.state.position().y() - p(h, 0, 2, 0).y()) < 1.0E-6, "Stairs did not reach the ordinary stone roof");
        h.assertTrue(!result.events.contains(MovementEvent.STEEP_SLIDE_START) && !result.events.contains(MovementEvent.JUMP), "Ordinary stairs caused a false steep slide or jump");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void flatIcePreservesMomentumAndBlueIceIsSlipperiest(GameTestHelper h) {
        var initial = ground(h, 4.5, 4);
        floor(h, Blocks.STONE);
        var stone = compare(h, initial, 20, (n, s) -> input(n, 0, false, false));
        h.assertTrue(stone.state.velocity().horizontalSpeed() == 0, "Ordinary ground no longer stops normally");
        double iceSpeed = 0;
        for (Block block : new Block[]{Blocks.ICE, Blocks.FROSTED_ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE}) {
            floor(h, block);
            var ice = compare(h, initial, 20, (n, s) -> input(n, 0, false, false));
            h.assertTrue(ice.state.grounded() && ice.state.mode() == MovementState.Mode.GROUND,
                    "Flat ice triggered an airborne or steep-slope reaction: " + block);
            h.assertTrue(ice.state.velocity().horizontalSpeed() > 0.5
                            && ice.state.position().z() > stone.state.position().z() + 1,
                    "Ice did not coast after releasing input: " + block);
            if (block == Blocks.BLUE_ICE) h.assertTrue(ice.state.velocity().horizontalSpeed() > iceSpeed,
                    "Blue ice did not retain more momentum than packed ice");
            iceSpeed = ice.state.velocity().horizontalSpeed();
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void iceReducesReversingGripAndStoneRestoresBraking(GameTestHelper h) {
        var initial = ground(h, 4.5, 4);
        floor(h, Blocks.STONE);
        var stone = compare(h, initial, 6, (n, s) -> input(n, -1, false, false));
        floor(h, Blocks.PACKED_ICE);
        var ice = compare(h, initial, 6, (n, s) -> input(n, -1, false, false));
        h.assertTrue(stone.state.velocity().z() < 0 && ice.state.velocity().z() > 0,
                "Ice allowed the same immediate reversal as stone");
        var restored = compare(h, initial, 20, (n, s) -> input(n, 0, false, false),
                (n, s) -> { if (n == 5) floor(h, Blocks.STONE); });
        h.assertTrue(restored.state.velocity().horizontalSpeed() == 0,
                "Moving off ice did not restore ordinary braking");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void crouchSlidesRetainMoreSpeedOnIce(GameTestHelper h) {
        var initial = new MovementState(p(h, 4.5, 1, 4.5), new MovementVector(0, 0, 6),
                MovementState.Mode.SLIDE, 0, true, 0, LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.LOW, 0);
        floor(h, Blocks.STONE);
        var stone = compare(h, initial, 10, (n, s) -> input(n, 0, false, true));
        floor(h, Blocks.PACKED_ICE);
        var ice = compare(h, initial, 10, (n, s) -> input(n, 0, false, true));
        h.assertTrue(ice.state.mode() == MovementState.Mode.SLIDE
                        && ice.state.velocity().horizontalSpeed() > stone.state.velocity().horizontalSpeed() + 1,
                "Ice did not reduce crouch-slide friction");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void descendingTaggedIceReactsAndFlatFloorRecovers(GameTestHelper h) {
        floor(h, Blocks.STONE);
        for (int z = 4; z <= 7; z++) for (int x = 3; x <= 6; x++) {
            int high = 7 - z;
            for (int y = 1; y < high; y++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            h.setBlock(new BlockPos(x, high, z), Blocks.PACKED_ICE);
        }
        var initial = new MovementState(p(h, 4.5, 4, 4.65), new MovementVector(0, 0, 2), MovementState.Mode.GROUND, 0, true, 0);
        var result = compare(h, initial, 160, (n, s) -> input(n, 0, false, false));
        h.assertTrue(result.events.containsAll(List.of(MovementEvent.STEEP_SLIDE_START, MovementEvent.STEEP_SLIDE_END)), "Tagged descending ice did not react/recover");
        h.assertTrue(result.state.mode() == MovementState.Mode.GROUND && result.state.body() == MovementBody.STANDING,
                "Steep reaction did not restore ordinary movement");
        h.assertTrue(Math.abs(result.state.position().y() - p(h, 0, 1, 0).y()) < 1.0E-6, "Steep reaction did not reach the flat floor");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void softHayRecoveryKeepsVanillaDamageAndSlimeBounce(GameTestHelper h) {
        var initial = new MovementState(p(h, 4.5, 1.1, 4.5), new MovementVector(0, -12, 0), MovementState.Mode.AIR, 1, false, 5.9F);
        floor(h, Blocks.STONE); var stone = compare(h, initial, 2, (n, s) -> input(n, 0, false, false));
        floor(h, Blocks.HAY_BLOCK); var hay = compare(h, initial, 34, (n, s) -> input(n, 0, n == 5, false));
        h.assertTrue(hay.events.contains(MovementEvent.SOFT_LAND) && !hay.events.contains(MovementEvent.HARD_LAND), "Hay did not select soft landing");
        h.assertTrue(hay.state.mode() == MovementState.Mode.GROUND && !hay.events.contains(MovementEvent.JUMP), "Soft recovery repeated an ignored jump");
        h.assertTrue(hay.falls == 1 && hay.damage > 0 && hay.damage < stone.damage, "Hay changed one-time vanilla damage behavior");
        floor(h, Blocks.SLIME_BLOCK); var slime = compare(h, initial, 2, (n, s) -> input(n, 0, false, false));
        h.assertTrue(slime.state.velocity().y() > 7 && slime.damage == 0 && !slime.events.contains(MovementEvent.SOFT_LAND), "Soft tag suppressed slime bounce");
        h.succeed();
    }
    private SurfaceGameTests() {}
}
