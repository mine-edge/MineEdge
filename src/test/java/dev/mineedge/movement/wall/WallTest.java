package dev.mineedge.movement.wall;

import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.AdvancedState;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementFeatures;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.BoxWorld;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionMover;
import dev.mineedge.movement.collision.CollisionWorld;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.integration.MinecraftCollisionWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WallTest {
    // Reference ledge geometry uses the original faith_move tuning.
    private static final MovementTuning REFERENCE = new MovementTuning(4, 6.3, 16.4, 61.44, 1.536, 5, 0.2, 0.5, 1.8);
    private final MovementController controller = new MovementController();
    private final List<MovementEvent> events = new ArrayList<>();
    private static CollisionBox box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new CollisionBox(new MovementVector(x0, y0, z0), new MovementVector(x1, y1, z1));
    }
    private static final CollisionBox RIGHT = box(1, -5, -20, 2, 10, 40);
    private static final CollisionBox LEDGE = box(-4, 0, 1, 4, 3.3, 8);

    private static final class World implements MovementController.MotionResolver {
        BoxWorld world;
        MovementBody body = MovementBody.STANDING;
        MovementVector offset = MovementVector.ZERO;
        World(CollisionBox... shapes) { world = new BoxWorld(List.of(shapes)); }
        @Override public void body(MovementBody body) { this.body = body; }
        @Override public boolean canOccupy(MovementVector feet, MovementBody body) { return !world.overlaps(bounds(feet, body)); }
        @Override public CollisionWorld geometry(CollisionBox region) { return world; }
        @Override public MovementVector position(MovementVector feet) { return feet.add(offset); }
        @Override public MovementController.Motion resolve(MovementVector feet, MovementVector velocity, double dt) {
            var result = CollisionMover.move(world, bounds(feet, body), velocity.scale(dt));
            return new MovementController.Motion(feet.add(result.recovery()).add(result.displacement()), result.clip(velocity), result.grounded());
        }
    }
    private static MovementInput input(float forward, float yaw, boolean jump, boolean crouch) {
        return new MovementInput(1, forward, 0, 0, jump, jump, crouch, crouch, true, yaw, 0);
    }
    private static MovementState airborne(MovementVector feet, MovementVector velocity) {
        return new MovementState(feet, velocity, MovementState.Mode.AIR, 0, false, 0, LocomotionState.EMPTY,
                BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, WallState.EMPTY.jump(new MovementVector(feet.x(), 0, feet.z())));
    }
    private static MovementState runStart() { return airborne(new MovementVector(0.6, 0.3, 0), new MovementVector(2, 3, 6)); }
    private MovementState tick(MovementState state, World world, MovementInput input) {
        var next = controller.tick(state, input, REFERENCE, world, events::add);
        assertFalse(world.world.overlaps(world.bounds(next.position(), next.body())), "Clipped geometry");
        return next;
    }

    @Test void leftAndRightRunsPreserveAlongSpeedAndHaveLimitedLift() {
        // faith_move/tests.rs::wallrun_along_wall, mesh_tests.rs::wallruns_along_a_turned_wall.
        for (int sign : new int[]{-1, 1}) {
            World world = new World(sign == 1 ? RIGHT : box(-2, -5, -20, -1, 10, 40));
            var start = airborne(new MovementVector(sign * 0.6, 0.3, 0), new MovementVector(sign * 2, 3, 6));
            var state = tick(start, world, input(1, -25 * sign, false, false));
            assertEquals(MovementState.Mode.WALLRUN, state.mode());
            assertEquals(-sign, state.wall().normal().x());
            double peak = state.position().y();
            for (int i = 0; i < 35; i++) {
                state = tick(state, world, input(1, 0, false, false));
                peak = Math.max(peak, state.position().y());
            }
            assertTrue(state.position().z() > 8, "Too little along-wall travel");
            assertTrue(peak <= 1.75, "Lift exceeded jump-origin cap");
            assertTrue(state.velocity().z() < start.velocity().length());
            assertEquals(MovementState.Mode.AIR, state.mode());
        }
    }

    @Test void parallelLowSpeedFrontOnAndShortWallApproachesRejectWallrun() {
        var parallel = airborne(new MovementVector(0.6, 0.3, 0), new MovementVector(0, 3, 6));
        assertNotEquals(MovementState.Mode.WALLRUN, tick(parallel, new World(RIGHT), input(1, 0, false, false)).mode());
        assertNotEquals(MovementState.Mode.WALLRUN, tick(runStart().withVelocity(new MovementVector(0.5, 2, 0.5)), new World(RIGHT), input(1, -25, false, false)).mode());
        assertNotEquals(MovementState.Mode.WALLRUN, tick(runStart(), new World(box(1, -5, -20, 2, 1, 40)), input(1, -25, false, false)).mode());
        var front = tick(runStart(), new World(RIGHT), input(1, -90, false, false));
        assertEquals(MovementState.Mode.WALLCLIMB, front.mode());
    }

    @Test void disabledWallRunningRejectsEntryAndDetachesWithoutDisablingWallClimbing() {
        var tuning = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(true, false, true, true, true));
        var world = new World(RIGHT);
        var blocked = controller.tick(runStart(), input(1, -25, false, false), tuning, world, events::add);
        assertNotEquals(MovementState.Mode.WALLRUN, blocked.mode());
        assertFalse(events.contains(MovementEvent.WALLRUN_START));
        var climbing = controller.tick(runStart(), input(1, -90, false, false), tuning, world, events::add);
        assertEquals(MovementState.Mode.WALLCLIMB, climbing.mode());

        var running = tick(runStart(), world, input(1, -25, false, false));
        assertEquals(MovementState.Mode.WALLRUN, running.mode());
        events.clear();
        var released = controller.tick(running, input(1, 0, false, false), tuning, world, events::add);
        assertEquals(MovementState.Mode.AIR, released.mode());
        assertTrue(events.contains(MovementEvent.WALL_DETACH));
        assertFalse(events.contains(MovementEvent.WALLRUN_START));
        assertFalse(world.world.overlaps(world.bounds(released.position(), released.body())));
    }

    @Test void fallingRunRejectsNearbyGroundButAcceptsGap() {
        var falling = runStart().withVelocity(new MovementVector(2, -1, 6));
        var floor = box(-10, -1, -20, 1, 0, 40);
        assertNotEquals(MovementState.Mode.WALLRUN, tick(falling, new World(RIGHT, floor), input(1, -25, false, false)).mode());
        assertEquals(MovementState.Mode.WALLRUN, tick(falling, new World(RIGHT), input(1, -25, false, false)).mode());
    }

    @Test void wallrunEntryRequiresSprintForBothFrontAndSideApproaches() {
        for (boolean side : new boolean[]{false, true}) {
            events.clear(); var world = new World(RIGHT);
            var start = side ? airborne(new MovementVector(0.6, 0.2, 0), new MovementVector(2, 3, 6)) : runStart();
            var command = side ? new MovementInput(1, 1, 1, 1, false, false, false, false, true, 0, 0)
                    : input(1, -25, false, false);
            var walking = tick(start, world, command.withSprint(false));
            assertNotEquals(MovementState.Mode.WALLRUN, walking.mode());
            assertFalse(events.contains(MovementEvent.WALLRUN_START));
            var sprinting = tick(start, world, command);
            assertEquals(MovementState.Mode.WALLRUN, sprinting.mode());
            assertTrue(events.contains(MovementEvent.WALLRUN_START));
            var continuing = tick(sprinting, world, input(1, 0, false, false).withSprint(false));
            assertEquals(MovementState.Mode.WALLRUN, continuing.mode());
        }
    }

    @Test void viewDependentJumpTradesAlongMomentumForOutwardAndUpwardPush() {
        // faith_move/tests.rs::wallrun_jump_off_trades_forward_speed_for_push.
        World world = new World(RIGHT);
        var baseline = runStart();
        for (int i = 0; i < 5; i++) baseline = tick(baseline, world, input(1, -25, false, false));
        var along = tick(baseline, world, input(1, 0, true, false));
        var outward = tick(baseline, world, input(1, 90, true, false));
        assertEquals(MovementState.Mode.AIR, along.mode());
        assertEquals(LandingState.Source.WALL_JUMP, along.landing().source());
        assertTrue(outward.velocity().x() < along.velocity().x() - 3.5);
        assertTrue(outward.velocity().y() > along.velocity().y());
        assertTrue(outward.velocity().z() < along.velocity().z() * 0.2);
        assertTrue(events.contains(MovementEvent.WALL_JUMP));
    }

    @Test void diagonalCornerAttachmentsDoNotTurnWallSnapIntoASidewaysLaunch() {
        for (int sign : new int[]{-1, 1}) for (float angle : new float[]{55, 65, 75}) {
            events.clear();
            var corner = sign == 1 ? box(1, -5, 1, 2, 10, 8) : box(-2, -5, 1, -1, 10, 8);
            var world = new World(corner);
            var start = airborne(new MovementVector(sign * 0.6, 0.3, 0.6), new MovementVector(sign * 6, 3, 2));
            var command = input(1, -sign * angle, false, false);
            var state = tick(start, world, command);
            assertTrue(events.contains(MovementEvent.WALLRUN_START), "Corner fixture did not enter a wallrun");
            assertTrue(state.velocity().horizontalSpeed() <= start.velocity().horizontalSpeed() + 1,
                    "Corner attachment launched the player: " + state.velocity());
            var replay = state;
            for (int n = 0; n < 40; n++) {
                state = tick(state, world, command);
                replay = controller.tick(replay, command, REFERENCE, new World(corner), e -> {});
                assertEquals(state, replay);
                assertTrue(state.velocity().horizontalSpeed() <= start.velocity().horizontalSpeed() + 1,
                        "Corner contact retained attachment speed: " + state.velocity());
            }
            assertTrue(state.position().z() - start.position().z() < 8, "Corner launched the player along the adjacent face");
            assertFalse(events.contains(MovementEvent.WALL_JUMP));
        }
    }

    @Test void sameWallCannotRefreshLiftAndOppositeWallChainsWithoutLift() {
        World world = new World(RIGHT);
        var baseline = runStart();
        for (int i = 0; i < 4; i++) baseline = tick(baseline, world, input(1, -25, false, false));
        var jumped = tick(baseline, world, input(1, 0, true, false));
        var backAtWall = new MovementState(runStart().position(), runStart().velocity(), MovementState.Mode.AIR, 0, false, 0,
                jumped.locomotion(), jumped.blocks(), MovementBody.STANDING, 0, TraversalState.EMPTY, jumped.wall().advance(1));
        events.clear();
        tick(backAtWall, world, input(1, -25, false, false));
        assertFalse(events.contains(MovementEvent.WALLRUN_START));
        var other = new MovementState(new MovementVector(-0.6, 0.3, 0), new MovementVector(-2, 1, 6), MovementState.Mode.AIR, 0, false, 0,
                LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, jumped.wall().advance(1));
        var chained = tick(other, new World(box(-2, -5, -20, -1, 10, 40)), input(1, 25, false, false));
        assertEquals(MovementState.Mode.WALLRUN, chained.mode());
        assertEquals(1, chained.wall().chain());
        assertTrue(chained.velocity().y() <= 1);
    }

    @Test void climbBoostGrowsWithRunUpAndExhaustedClimbCannotRestart() {
        World world = new World(box(-4, -5, 1, 4, 20, 8));
        var feet = new MovementVector(0, 0.2, 0.65);
        var slow = tick(airborne(feet, new MovementVector(0, 3, 2)), world, input(1, 0, false, false));
        var fast = tick(airborne(feet, new MovementVector(0, 3, 7)), world, input(1, 0, false, false));
        assertEquals(MovementState.Mode.WALLCLIMB, slow.mode());
        assertTrue(fast.velocity().y() > slow.velocity().y());
        for (int i = 0; i < 30; i++) fast = tick(fast, world, input(1, 0, false, false));
        assertEquals(MovementState.Mode.AIR, fast.mode());
        assertTrue(fast.wall().climbed());
        events.clear();
        tick(fast.withVelocity(new MovementVector(0, 2, 1)), world, input(1, 0, false, false));
        assertFalse(events.contains(MovementEvent.WALLCLIMB_START));
    }

    @Test void climbGrabsSettlesAndPullsUpOntoPlatform() {
        World world = new World(LEDGE, box(-10, -1, -10, 10, 0, 10));
        var state = airborne(new MovementVector(0, 0.2, 0.65), new MovementVector(0, 3.2, 6));
        for (int i = 0; i < 55; i++) state = tick(state, world, input(i < 35 ? 1 : 0, 0, true, false).withoutEdges());
        assertTrue(events.containsAll(List.of(MovementEvent.WALLCLIMB_START, MovementEvent.LEDGE_GRAB, MovementEvent.PULL_UP_START, MovementEvent.TRAVERSAL_COMPLETE)), events.toString());
        assertEquals(3.3, state.position().y(), 1.0E-6);
        assertEquals(MovementState.Mode.GROUND, state.mode());
    }

    private MovementState hang(World world) {
        return tick(airborne(new MovementVector(0, 1.5, 0.65), new MovementVector(0, -1, 1)), world, input(0, 0, false, false));
    }
    @Test void ledgeGrabRequiresFacingWithin45Degrees() {
        var world = new World(LEDGE);
        for (float yaw : new float[]{-55, -45.1f, -45, 0, 45, 45.1f, 55}) {
            var start = airborne(new MovementVector(0, 1.5, 0.65), new MovementVector(0, -1, 0));
            var state = tick(start, world, input(0, yaw, false, false));
            assertEquals(Math.abs(yaw) <= 45, state.mode() == MovementState.Mode.LEDGE_HANG, "Unexpected grab at yaw=" + yaw);
        }
    }

    @Test void pullUpFindsStandableLandingsOnNarrowRailsAndBeforeBarsOnAWall() {
        for (boolean narrow : new boolean[]{true, false}) {
            events.clear();
            var world = narrow ? new World(box(-0.0625, 0, 1, 0.0625, 3.3, 1.0625))
                    : new World(LEDGE, box(-4, 3.3, 1.4375, 4, 4.3, 1.5625));
            var state = hang(world);
            assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
            for (int n = 0; n < 30 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); n++) state = tick(state, world, input(1, 0, true, false).withoutEdges());
            assertTrue(events.contains(MovementEvent.PULL_UP_START));
            assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
            assertEquals(MovementState.Mode.GROUND, state.mode()); assertEquals(3.3, state.position().y(), 1.0E-6);
        }
    }

    @Test void floatingClosedTrapdoorsCanBeGrabbedAndMantledForBothHalvesAndMaterials() {
        for (var block : List.of(Blocks.OAK_TRAPDOOR, Blocks.IRON_TRAPDOOR)) for (var half : Half.values()) {
            events.clear();
            var state = block.defaultBlockState().setValue(BlockStateProperties.OPEN, false).setValue(BlockStateProperties.HALF, half);
            var offset = new MovementVector(0, 3, 1);
            var shapes = new ArrayList<CollisionBox>();
            shapes.add(box(-10, -1, -10, 10, 0, 10));
            for (var bounds : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs())
                shapes.add(MinecraftCollisionWorld.core(bounds).move(offset));
            var world = new World(shapes.toArray(CollisionBox[]::new));
            double top = half == Half.TOP ? 4 : 3.1875;
            var start = airborne(new MovementVector(0.5, top - 1.828 - 0.05, 0.65), new MovementVector(0, -1, 0));
            var hanging = tick(start, world, input(0, 0, false, false));
            assertEquals(MovementState.Mode.LEDGE_HANG, hanging.mode(), "Missed floating trapdoor: " + state);
            var result = hanging;
            for (int n = 0; n < 35 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); n++)
                result = tick(result, world, input(1, 0, true, false).withoutEdges());
            assertTrue(events.contains(MovementEvent.PULL_UP_START));
            assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
            assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertEquals(MovementState.Mode.GROUND, result.mode()); assertEquals(top, result.position().y(), 1.0E-6);

            shapes.subList(1, shapes.size()).clear();
            var open = state.setValue(BlockStateProperties.OPEN, true);
            for (var bounds : open.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs())
                shapes.add(MinecraftCollisionWorld.core(bounds).move(offset));
            world.world = new BoxWorld(shapes);
            assertEquals(MovementState.Mode.AIR, tick(hanging, world, input(0, 0, false, false)).mode(),
                    "Opening a trapdoor kept its old horizontal ledge attached");
        }
    }

    @Test void blockedFloatingTrapdoorKeepsTheHangInsteadOfClippingThroughTheCeiling() {
        var trapdoor = box(0, 3, 1, 1, 3.1875, 2);
        var world = new World(trapdoor, box(-1, 3.77, 0.9, 2, 5.2, 3));
        var state = tick(airborne(new MovementVector(0.5, 3.1875 - 1.828 - 0.05, 0.65), new MovementVector(0, -1, 0)),
                world, input(0, 0, false, false));
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        for (int n = 0; n < 35; n++) state = tick(state, world, input(1, 0, true, false).withoutEdges());
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        assertFalse(events.contains(MovementEvent.PULL_UP_START));
    }

    @Test void coveredStairFullSidesCannotBeGrabbedThroughAnOverhangingStair() {
        for (var upperHalf : Half.values()) for (var upperFacing : Direction.Plane.HORIZONTAL) {
            if (upperHalf == Half.TOP && upperFacing != Direction.SOUTH) continue;
            events.clear();
            var lower = Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
            var upper = lower.setValue(BlockStateProperties.HALF, upperHalf)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, upperFacing);
            var shapes = new ArrayList<CollisionBox>();
            for (var bounds : lower.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs())
                shapes.add(MinecraftCollisionWorld.core(bounds).move(new MovementVector(0, 3, 1)));
            for (var bounds : upper.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs())
                shapes.add(MinecraftCollisionWorld.core(bounds).move(new MovementVector(0, 4, 0)));
            var world = new World(shapes.toArray(CollisionBox[]::new));
            var state = tick(airborne(new MovementVector(0.5, 4 - 1.828 - 0.05, 0.65), new MovementVector(0, -1, 0)),
                    world, input(0, 0, false, false));
            assertNotEquals(MovementState.Mode.LEDGE_HANG, state.mode(), "Grabbed covered stair: " + upper);
            assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
        }
    }

    @Test void trapdoorFloorsCannotBeGrabbedAtAnInternalEdgeUnderTheFloor() {
        for (var half : Half.values()) {
            events.clear();
            var state = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF, half);
            var shapes = new ArrayList<CollisionBox>();
            shapes.add(box(0, 0, 1, 1, 3, 2));
            for (int z = 0; z <= 1; z++)
                for (var bounds : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs())
                    shapes.add(MinecraftCollisionWorld.core(bounds).move(new MovementVector(0, 3, z)));
            var world = new World(shapes.toArray(CollisionBox[]::new));
            double top = half == Half.TOP ? 4 : 3.1875;
            var result = tick(airborne(new MovementVector(0.5, top - 1.828 - 0.2, 0.65), new MovementVector(0, -1, 0)),
                    world, input(0, 0, false, false));
            assertNotEquals(MovementState.Mode.LEDGE_HANG, result.mode(), "Grabbed an internal trapdoor seam: " + half);
            assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
        }
    }

    @Test void exposedStairFullSidesRemainGrabbableWithClearSpaceForTheHands() {
        var stair = Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        var shapes = new ArrayList<CollisionBox>();
        for (var bounds : stair.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs())
            shapes.add(MinecraftCollisionWorld.core(bounds).move(new MovementVector(0, 3, 1)));
        var world = new World(shapes.toArray(CollisionBox[]::new));
        var state = tick(airborne(new MovementVector(0.5, 4 - 1.828 - 0.05, 0.65), new MovementVector(0, -1, 0)),
                world, input(0, 0, false, false));
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        assertEquals(4 - 1.828, state.position().y(), 1.0E-9);
    }

    @Test void coveringTheGripReleasesAnExistingHangEvenWhenTheBodyStillFits() {
        var world = new World(LEDGE);
        var state = hang(world);
        world.world = new BoxWorld(List.of(LEDGE, box(-4, 3.3, 0, 4, 3.4875, 1)));
        assertTrue(world.canOccupy(state.position(), state.body()));
        events.clear();
        state = tick(state, world, input(0, 0, false, false));
        assertEquals(MovementState.Mode.AIR, state.mode());
        assertTrue(events.contains(MovementEvent.WALL_DETACH));
    }

    @Test void hangSettlingDropAndRegrabCooldown() {
        World world = new World(LEDGE);
        var state = hang(world);
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        for (int i = 0; i < 5; i++) state = tick(state, world, input(1, 0, false, false));
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        var dropped = tick(state, world, input(0, 0, false, true));
        assertEquals(MovementState.Mode.AIR, dropped.mode());
        assertTrue(dropped.wall().cooldown() > 0.25);
        assertTrue(events.contains(MovementEvent.LEDGE_DROP));
    }

    @Test void spaceAndForwardHeldDuringGrabWaitHalfASecondBeforePullUpAndReplayTheDelay() {
        var world = new World(LEDGE);
        var forward = input(1, 0, true, false).withoutEdges();
        var state = tick(airborne(new MovementVector(0, 1.5, 0.65), new MovementVector(0, -1, 1)), world, forward);
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        assertTrue(events.contains(MovementEvent.LEDGE_GRAB));
        assertFalse(events.contains(MovementEvent.PULL_UP_START));
        var baseline = state;
        for (int i = 0; i < 9; i++) {
            state = tick(state, world, forward);
            assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
            assertTrue(state.modeTime() < 0.5);
            assertFalse(events.contains(MovementEvent.PULL_UP_START));
        }
        assertEquals(0.5 - MovementTuning.SUBSTEP_SECONDS, state.modeTime(), 1.0E-9);
        state = tick(state, world, forward);
        assertEquals(MovementState.Mode.PULL_UP, state.mode());
        assertEquals(1, events.stream().filter(event -> event == MovementEvent.PULL_UP_START).count());
        for (int i = 0; i < 10; i++) baseline = controller.tick(baseline, forward, REFERENCE, world, event -> {});
        assertEquals(state, baseline);
    }

    @Test void releasingSpaceDuringPullUpDropsToHangAndHeldSpaceCanStartANewPullUp() {
        var world = new World(LEDGE);
        var state = hang(world);
        for (int i = 0; i < 13; i++) state = tick(state, world, input(1, 0, true, false).withoutEdges());
        assertEquals(MovementState.Mode.PULL_UP, state.mode());
        var released = tick(state, world, input(1, 0, false, false));
        assertEquals(MovementState.Mode.LEDGE_HANG, released.mode());
        assertNull(released.traversal().path());
        assertNotNull(released.advanced().ledge());
        assertTrue(released.position().y() < state.position().y());
        assertTrue(released.position().y() > 3.3 - 1.828, "Cancellation snapped straight to hang");
        var hangPosition = released.advanced().ledge().to();
        for (int i = 0; i < 20; i++) released = tick(released, world, input(1, 0, false, false));
        assertEquals(MovementState.Mode.LEDGE_HANG, released.mode());
        assertNull(released.advanced().ledge());
        assertEquals(hangPosition, released.position());
        assertTrue(events.contains(MovementEvent.TRAVERSAL_ABORT));
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.PULL_UP_START).count());
        for (int i = 0; i < 20 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); i++)
            released = tick(released, world, input(1, 0, true, false).withoutEdges());
        assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
        assertEquals(MovementState.Mode.GROUND, released.mode());
        assertEquals(2, events.stream().filter(e -> e == MovementEvent.PULL_UP_START).count());
    }

    private MovementState pullUp(World world, int ticks) {
        var state = hang(world);
        for (int i = 0; i < ticks; i++) state = tick(state, world, input(1, 0, true, false).withoutEdges());
        assertEquals(MovementState.Mode.PULL_UP, state.mode());
        return state;
    }

    @Test void pullUpReturnBeforeHalfwayIsSmoothCollisionCheckedAndReplays() {
        for (int ticks : new int[]{11, 13, 14}) {
            events.clear();
            var world = new World(LEDGE);
            var climbing = pullUp(world, ticks);
            events.clear();
            var release = input(1, 0, false, false);
            var state = tick(climbing, world, release);
            assertNotNull(state.advanced().ledge());
            assertEquals(AdvancedState.LedgeKind.RETURN, state.advanced().ledge().kind());
            assertEquals(0, events.stream().filter(e -> e == MovementEvent.LEDGE_GRAB).count());
            assertEquals(state, controller.tick(climbing, release, REFERENCE, new World(LEDGE), e -> {}));
            var target = state.advanced().ledge().to();
            double returnDistance = state.advanced().ledge().via().subtract(climbing.position()).length()
                    + target.subtract(state.advanced().ledge().via()).length();
            assertTrue(state.position().subtract(target).length() > 0.01);
            var replay = state;
            int elapsedTicks = 1;
            while (state.advanced().ledge() != null && elapsedTicks < 6) {
                var before = state;
                state = tick(state, world, release);
                replay = controller.tick(replay, release, REFERENCE, new World(LEDGE), e -> {});
                assertEquals(state, replay);
                assertTrue(state.position().y() <= before.position().y() + 1.0E-9);
                assertTrue(state.position().subtract(before.position()).length() < returnDistance * 0.5, "Return moved too far in one tick");
                elapsedTicks++;
            }
            assertNull(state.advanced().ledge());
            assertTrue(elapsedTicks >= 4 && elapsedTicks <= 5);
            assertEquals(target, state.position());
            assertEquals(0, state.fallDistance());
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.LEDGE_GRAB).count());
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.TRAVERSAL_ABORT).count());
        }
    }

    @Test void pullUpReturnCanBeInterruptedByDropRetryAndKick() {
        for (int ticks : new int[]{11, 13, 14}) for (int action = 0; action < 3; action++) {
            var world = new World(LEDGE);
            var state = tick(pullUp(world, ticks), world, input(1, 0, false, false));
            var before = state.position();
            events.clear();
            state = tick(state, world, action == 0 ? input(0, 0, false, true)
                    : input(1, action == 2 ? 180 : 0, true, false));
            assertNull(state.advanced().ledge());
            if (action == 0) assertTrue(state.mode() == MovementState.Mode.AIR || state.mode() == MovementState.Mode.GROUND,
                    "Drop did not resume ordinary collision at pull-up tick " + ticks);
            else assertEquals(action == 1 ? MovementState.Mode.PULL_UP : MovementState.Mode.AIR, state.mode(),
                    "Wrong interruption at pull-up tick " + ticks + ", action " + action);
            assertTrue(events.contains(action == 0 ? MovementEvent.LEDGE_DROP
                    : action == 1 ? MovementEvent.PULL_UP_START : MovementEvent.WALL_KICK));
            assertTrue(state.position().y() > 3.3 - 1.828, "Interrupt snapped to the hang target");
            if (action == 1) assertTrue(state.position().y() >= before.y());
        }
    }

    @Test void releasingPullUpAfterHalfwayContinuesOntoFirstStandableSupportAndReplays() {
        for (int ticks : new int[]{15, 16, 18, 20}) {
            var world = new World(LEDGE);
            var climbing = pullUp(world, ticks);
            var path = climbing.traversal().path();
            assertTrue(climbing.modeTime() + 1.0E-9 >= path.kind().duration() * 0.5);
            events.clear();
            var release = input(0, 0, false, false);
            var state = tick(climbing, world, release);
            var replay = controller.tick(climbing, release, REFERENCE, new World(LEDGE), e -> {});
            assertEquals(state, replay);
            for (int i = 0; i < 15 && state.traversal().path() != null; i++) {
                assertNull(state.advanced().ledge());
                state = tick(state, world, release);
                replay = controller.tick(replay, release, REFERENCE, new World(LEDGE), e -> {});
                assertEquals(state, replay);
            }
            assertEquals(MovementState.Mode.GROUND, state.mode());
            assertTrue(state.grounded());
            assertEquals(MovementBody.STANDING, state.body());
            assertEquals(LEDGE.max().y(), state.position().y(), 1.0E-9);
            assertTrue(state.position().z() <= path.end().z(), "Pull-up moved past its support");
            if (ticks < 20) assertTrue(state.position().z() < path.end().z(), "Pull-up did not stop at first support");
            assertEquals(MovementVector.ZERO, state.velocity());
            assertNull(state.advanced().ledge());
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.TRAVERSAL_COMPLETE).count());
            assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
        }
    }

    @Test void committedPullUpReleaseSettlesCrouchedUnderLowHeadroom() {
        var world = new World(LEDGE, box(-4, 4.9, -1, 4, 6, 1.1));
        var state = pullUp(world, 16);
        events.clear();
        var release = input(0, 0, false, false);
        state = tick(state, world, release);
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertTrue(state.grounded());
        assertEquals(MovementBody.CROUCHING, state.body());
        assertTrue(state.position().z() < 1.4, "Release continued past the first fitting support");
        assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
        assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
    }

    @Test void blockedOrDisabledRetryKeepsReturningToTheActualHang() {
        for (boolean disabled : new boolean[]{false, true}) {
            var world = new World(LEDGE);
            var state = tick(pullUp(world, 13), world, input(1, 0, false, false));
            var target = state.advanced().ledge().to();
            var tuning = disabled ? REFERENCE.withFeatures(new MovementFeatures(true, true, true, false, true)) : REFERENCE;
            if (!disabled) world.world = new BoxWorld(List.of(LEDGE, box(-4, 3.89, 1, 4, 6, 8)));
            events.clear();
            state = controller.tick(state, input(1, 0, true, false), tuning, world, events::add);
            assertNotNull(state.advanced().ledge(), "Unavailable retry stopped the return midair");
            for (int i = 0; i < 5; i++) state = controller.tick(state, input(1, 0, true, false).withoutEdges(), tuning, world, events::add);
            assertNull(state.advanced().ledge());
            assertEquals(target, state.position());
            assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
            assertFalse(events.contains(MovementEvent.PULL_UP_START));
        }
    }

    @Test void pullUpReturnReleasesOnRemovedSupportKnockbackDisplacementOrNewObstruction() {
        for (int action = 0; action < 4; action++) {
            var world = new World(LEDGE);
            var state = tick(pullUp(world, 13), world, input(1, 0, false, false));
            var before = state.position();
            if (action == 0) world.world = new BoxWorld(List.of());
            if (action == 1) state = state.withVelocity(new MovementVector(2, 3, 0));
            if (action == 2) world.offset = new MovementVector(0.3, 0, 0);
            if (action == 3) world.world = new BoxWorld(List.of(LEDGE, box(-1, before.y() - 0.05, -1, 1, before.y() - 0.04, 0.99)));
            events.clear();
            state = tick(state, world, input(0, 0, false, false));
            assertEquals(MovementState.Mode.AIR, state.mode());
            assertNull(state.advanced().ledge());
            assertTrue(events.contains(MovementEvent.WALL_DETACH));
            assertTrue(state.position().y() > 3.3 - 1.828);
            if (action == 1) assertTrue(state.velocity().y() > 0 && state.velocity().x() > 0);
        }
    }

    @Test void lowLedgesCannotCreateCrouchedOrGroundedHangs() {
        for (double floor : new double[]{0, 7}) for (double height : new double[]{1.5, 1.6, 1.828}) {
            var world = new World(box(-4, floor, 1, 4, floor + height, 8), box(-10, floor - 1, -10, 10, floor, 10));
            var state = airborne(new MovementVector(0, floor + Math.max(0.01, height - 1.828 + 0.05), 0.65), new MovementVector(0, -1, 0));
            for (int i = 0; i < 10; i++) {
                state = tick(state, world, input(0, 0, false, false));
                assertNotEquals(MovementState.Mode.LEDGE_HANG, state.mode(), "Short wall created a hang at height=" + height);
            }
            assertEquals(MovementState.Mode.GROUND, state.mode());
            assertEquals(MovementBody.STANDING, state.body());
        }
    }

    @Test void wallsWithHangClearanceAllowHangingBelowTwoAndAHalfBlocks() {
        for (double floor : new double[]{0, 7}) for (double height : new double[]{1.85, 2, 2.5, 2.51, 3, 3.01}) {
            double top = floor + height;
            var world = new World(box(-4, floor, 1, 4, top, 8), box(-10, floor - 1, -10, 10, floor, 10));
            var state = tick(airborne(new MovementVector(0, top - 1.828 + 0.05, 0.65), new MovementVector(0, -1, 0)),
                    world, input(0, 0, false, false));
            assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
            assertEquals(top - 1.828, state.position().y(), 1.0E-6);
        }
    }

    @Test void aRaisedFloorBelowTheFeetKeepsAnExistingHang() {
        var world = new World(LEDGE);
        var hanging = hang(world);
        world.world = new BoxWorld(List.of(LEDGE, box(-4, 0, -1, 4, 0.8, 0.99)));
        var retained = tick(hanging, world, input(0, 0, false, false));
        assertEquals(MovementState.Mode.LEDGE_HANG, retained.mode());
        assertEquals(hanging.position(), retained.position());
        assertFalse(events.contains(MovementEvent.LEDGE_DROP));
    }

    @Test void crouchedHangForHeadroomKeepsTheSameFootHeight() {
        // An obstruction beside the hands can require crouching without covering the grip.
        var world = new World(LEDGE, box(0.28, 3.05, 0.96, 0.35, 4, 0.99));
        var state = hang(world);
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        assertEquals(MovementBody.CROUCHING, state.body());
        assertEquals(3.3 - 1.828, state.position().y(), 1.0E-6);
    }

    @Test void aFloorAppearingUnderAHangRestoresGroundMovement() {
        var world = new World(LEDGE);
        var hanging = hang(world);
        world.world = new BoxWorld(List.of(LEDGE, box(-4, 0, -1, 4, hanging.position().y(), 0.99)));
        var landed = tick(hanging, world, input(0, 0, false, false));
        assertEquals(MovementState.Mode.GROUND, landed.mode());
        assertTrue(landed.grounded());
        assertEquals(MovementBody.STANDING, landed.body());
        assertEquals(hanging.position(), landed.position());
        assertNull(landed.wall().support());
    }

    @Test void blockedPullUpKeepsValidHangAndCrouchOnlyLandingIsAllowed() {
        World blocked = new World(LEDGE, box(-4, 3.89, 1, 4, 6, 8));
        var state = hang(blocked);
        for (int i = 0; i < 20; i++) state = tick(state, blocked, input(1, 0, true, false).withoutEdges());
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        World crouchOnly = new World(LEDGE, box(-4, 4.95, 1, 4, 6, 8));
        state = hang(crouchOnly);
        for (int i = 0; i < 25; i++) state = tick(state, crouchOnly, input(1, 0, true, false).withoutEdges());
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertEquals(MovementBody.CROUCHING, state.body());
    }

    @Test void pullUpsFitExactCrouchingAndCrawlingEntrances() {
        for (double clearance : new double[]{1.5, 1, 0.6}) {
            events.clear();
            var world = new World(LEDGE, box(-4, 3.3 + clearance, 1, 4, 4.3 + clearance, 8));
            var state = hang(world);
            assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
            for (int i = 0; i < 40 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); i++)
                state = tick(state, world, input(1, 0, true, false).withoutEdges());
            assertTrue(events.contains(MovementEvent.PULL_UP_START), "No pull-up for clearance=" + clearance);
            assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE), "Incomplete pull-up for clearance=" + clearance);
            assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertEquals(MovementState.Mode.GROUND, state.mode());
            assertEquals(3.3, state.position().y(), 1.0E-9);
            assertEquals(clearance >= 1.5 ? MovementBody.CROUCHING : MovementBody.LOW, state.body());
        }
    }

    @Test void pullUpsFitOneBlockDeepLandingsBeforeAWall() {
        for (double clearance : new double[]{1, 1.5, 2}) {
            events.clear();
            var world = new World(LEDGE, box(-4, 3.3, 2, 4, 6.3, 8),
                    box(-4, 3.3 + clearance, 1, 4, 6.3 + clearance, 8));
            var state = hang(world);
            assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
            for (int i = 0; i < 40 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); i++)
                state = tick(state, world, input(1, 0, true, false).withoutEdges());
            assertTrue(events.contains(MovementEvent.PULL_UP_START), "No pull-up for clearance=" + clearance);
            assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE), "Incomplete pull-up for clearance=" + clearance);
            assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertEquals(MovementState.Mode.GROUND, state.mode());
            assertEquals(3.3, state.position().y(), 1.0E-9);
            assertTrue(state.position().z() + 0.3 <= 2);
        }
    }

    @Test void offCenterPullUpsAlignWithOneBlockWideLandingsBeforeGlass() {
        for (int sign : new int[]{-1, 1}) {
            events.clear();
            var world = new World(LEDGE, box(-4, 3.3, 1.4375, 4, 4.3, 1.5625),
                    box(-4, 3.3, 1, -0.5, 5.3, 8), box(0.5, 3.3, 1, 4, 5.3, 8));
            var state = tick(airborne(new MovementVector(sign * 0.4, 1.5, 0.65), new MovementVector(0, -1, 1)),
                    world, input(0, 0, false, false));
            assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
            var replay = state;
            for (int i = 0; i < 40 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); i++) {
                var command = input(1, 0, true, false).withoutEdges();
                state = tick(state, world, command);
                replay = controller.tick(replay, command, REFERENCE, world, e -> {});
                assertEquals(state, replay);
            }
            assertTrue(events.contains(MovementEvent.PULL_UP_START), "Off-center grab could not pull up, sign=" + sign);
            assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
            assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertEquals(MovementState.Mode.GROUND, state.mode());
            assertEquals(3.3, state.position().y(), 1.0E-9);
            assertTrue(Math.abs(state.position().x()) <= 0.2 + 1.0E-7);
        }
    }

    @Test void lateralPullUpAlignmentCannotEnterAnOpeningNarrowerThanTheBody() {
        var world = new World(LEDGE, box(-4, 3.3, 1.4375, 4, 4.3, 1.5625),
                box(-4, 3.3, 1, -0.275, 5.3, 8), box(0.275, 3.3, 1, 4, 5.3, 8));
        var state = hang(world);
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        var feet = state.position();
        for (int i = 0; i < 40; i++) state = tick(state, world, input(1, 0, true, false).withoutEdges());
        assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
        assertEquals(feet, state.position());
        assertFalse(events.contains(MovementEvent.PULL_UP_START));
    }

    @Test void removedSupportsAndExternalImpulsesReleaseWithoutTeleportingBack() {
        World world = new World(LEDGE);
        var state = hang(world);
        world.world = new BoxWorld(List.of());
        assertEquals(MovementState.Mode.AIR, tick(state, world, input(0, 0, false, false)).mode());
        world = new World(LEDGE);
        var pushed = tick(state.withVelocity(new MovementVector(-3, 1, 0)), world, input(0, 0, false, false));
        assertEquals(MovementState.Mode.AIR, pushed.mode());
        assertTrue(pushed.velocity().x() < -2.9);
        world.offset = new MovementVector(-0.5, 0, 0);
        assertTrue(tick(state, world, input(0, 0, false, false)).position().x() < -0.49);
        world = new World(RIGHT);
        var running = tick(runStart(), world, input(1, -25, false, false));
        world.world = new BoxWorld(List.of());
        assertEquals(MovementState.Mode.AIR, tick(running, world, input(1, 0, false, false)).mode());
    }

    @Test void midContactAndMidHangAuthoritativeBaselinesReplayExactly() {
        for (boolean hanging : new boolean[]{false, true}) {
            World world = new World(hanging ? LEDGE : RIGHT);
            var state = hanging ? hang(world) : tick(runStart(), world, input(1, -25, false, false));
            var baseline = state;
            var commands = new ArrayList<MovementInput>();
            for (int i = 0; i < 30; i++) {
                var command = input(1, 0, !hanging && i == 10, false);
                commands.add(command);
                state = tick(state, world, command);
            }
            for (var command : commands) baseline = controller.tick(baseline, command, REFERENCE, world, e -> {});
            assertEquals(state, baseline);
        }
    }

    @Test void earlyWallJumpPressBuffersOnceAndCeilingLimitsItsLift() {
        World world = new World(RIGHT, box(-10, 2.2, -20, 1, 3, 40));
        var state = tick(runStart(), world, input(1, -25, false, false));
        state = tick(state, world, input(1, 0, true, false));
        for (int i = 0; i < 3; i++) state = tick(state, world, input(1, 0, false, false));
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.WALL_JUMP).count());
        assertEquals(MovementState.Mode.AIR, state.mode());
        assertTrue(state.position().y() + state.body().height() <= 2.2 + 1.0E-6);
    }

    @Test void climbRequiresRisingJumpAndRunDetachConsumesCrouchEdgeOnce() {
        World front = new World(box(-4, -5, 1, 4, 20, 8));
        var feet = new MovementVector(0, 0.2, 0.65);
        tick(airborne(feet, new MovementVector(0, -1, 6)), front, input(1, 0, false, false));
        assertFalse(events.contains(MovementEvent.WALLCLIMB_START));
        tick(new MovementState(feet, new MovementVector(0, 3, 6), MovementState.Mode.AIR, 0, false, 0), front, input(1, 0, false, false));
        assertFalse(events.contains(MovementEvent.WALLCLIMB_START));
        World world = new World(RIGHT);
        var state = tick(runStart(), world, input(1, -25, false, false));
        state = tick(state, world, input(1, 0, false, true));
        assertEquals(MovementState.Mode.AIR, state.mode());
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.WALL_DETACH).count());
    }

    @Test void landingClearsWallAndClimbHistory() {
        World world = new World(box(-10, -1, -10, 10, 0, 10));
        var remembered = WallState.EMPTY.jump(MovementVector.ZERO).contact(new MovementVector(-1, 0, 0), RIGHT,
                MovementVector.ZERO, MovementVector.ZERO, true, 2).release(true, MovementVector.ZERO, 0.2);
        var state = new MovementState(new MovementVector(0, 0.05, 0), new MovementVector(0, -5, 0), MovementState.Mode.AIR,
                0, false, 2, LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, remembered);
        state = tick(state, world, input(0, 0, false, false));
        assertTrue(state.grounded());
        assertEquals(WallState.EMPTY, state.wall());
    }
}
