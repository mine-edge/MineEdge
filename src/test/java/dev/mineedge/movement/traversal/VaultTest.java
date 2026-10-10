package dev.mineedge.movement.traversal;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementFeatures;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.BoxWorld;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionMover;
import dev.mineedge.movement.collision.CollisionWorld;
import dev.mineedge.integration.MinecraftCollisionWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VaultTest {
    // Reference geometry and exit-speed assertions use the original faith_move tuning.
    private static final MovementTuning REFERENCE = new MovementTuning(4, 6.3, 16.4, 61.44, 1.536, 5, 0.2, 0.5, 1.8);
    private static final CollisionBox FLOOR = box(-20, -1, -20, 20, 0, 20);
    private final MovementController controller = new MovementController();
    private final List<MovementEvent> events = new ArrayList<>();

    private static CollisionBox box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new CollisionBox(new MovementVector(x0, y0, z0), new MovementVector(x1, y1, z1));
    }

    private static class World implements MovementController.MotionResolver {
        BoxWorld world;
        MovementBody body = MovementBody.STANDING;
        MovementVector offset = MovementVector.ZERO;
        double largestQuery;

        World(CollisionBox... obstacles) {
            var shapes = new ArrayList<CollisionBox>(List.of(obstacles));
            shapes.add(FLOOR);
            world = new BoxWorld(shapes);
        }

        @Override
        public void body(MovementBody body) { this.body = body; }

        @Override
        public boolean canOccupy(MovementVector position, MovementBody body) {
            return !world.overlaps(bounds(position, body));
        }

        @Override
        public CollisionWorld geometry(CollisionBox region) {
            largestQuery = Math.max(largestQuery, region.max().subtract(region.min()).length());
            return world;
        }

        @Override
        public MovementVector position(MovementVector simulated) {
            return simulated.add(offset);
        }

        @Override
        public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds) {
            var result = CollisionMover.move(world, bounds(position, body), velocity.scale(seconds));
            return new MovementController.Motion(position.add(result.recovery()).add(result.displacement()), result.clip(velocity), result.grounded());
        }
    }

    private static CollisionBox obstacle(double height, double depth) {
        return box(-3, 0, 1, 3, height, 1 + depth);
    }

    private static MovementInput forward(int sequence, boolean jump) {
        return new MovementInput(sequence, 1, 0, 0, jump, jump && sequence == 1, false, false, true, 0, 0);
    }

    private static MovementState start() {
        return new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 6), MovementState.Mode.GROUND, 1, true, 0);
    }

    private MovementState tick(MovementState state, World world, int sequence, boolean jump) {
        MovementState next = controller.tick(state, forward(sequence, jump), REFERENCE, world, events::add);
        assertFalse(world.world.overlaps(world.bounds(next.position(), next.body())), "Body penetrated geometry");
        return next;
    }

    @Test
    void referenceSelectionDistinguishesRailPlatformSlowMantleAndHighMantle() {
        // faith_move/tests.rs::vault_over_a_rail_at_a_run, vault_onto_a_deep_block,
        // standing_jump_onto_a_rail_is_a_step_up, vault_onto_high_while_rising.
        var fast = new MovementVector(0, 6.3, 6);
        assertEquals(VaultType.VAULT, VaultPlanner.plan(new World(obstacle(0.9, 0.4)), MovementVector.ZERO, fast, forward(1, false)).kind());
        assertEquals(VaultType.MANTLE, VaultPlanner.plan(new World(obstacle(0.9, 4)), MovementVector.ZERO, fast, forward(1, false)).kind());
        assertEquals(VaultType.SLOW_MANTLE, VaultPlanner.plan(new World(obstacle(0.9, 0.4)), MovementVector.ZERO,
                new MovementVector(0, 6.3, 1), forward(1, false)).kind());
        assertEquals(VaultType.HIGH_MANTLE, VaultPlanner.plan(new World(obstacle(1.6, 4)), MovementVector.ZERO, fast, forward(1, false)).kind());
        assertEquals(VaultType.HIGH_VAULT, VaultPlanner.plan(new World(obstacle(1.6, 0.4)), MovementVector.ZERO, fast, forward(1, false)).kind());
    }

    @Test
    void traversalTogglesIndependentlyGatePlanningAndAbortDisabledActivePaths() {
        var fast = new MovementVector(0, 6.3, 6);
        var rail = new World(obstacle(0.9, 0.4));
        var platform = new World(obstacle(0.9, 4));
        var noVault = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(true, true, false, true, true));
        var noMantle = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(true, true, true, false, true));
        var noTraversal = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(true, true, false, false, true));
        var alternative = VaultPlanner.plan(rail, MovementVector.ZERO, fast, forward(1, false), noVault);
        assertTrue(alternative == null || alternative.kind().onto());
        assertEquals(VaultType.MANTLE, VaultPlanner.plan(platform, MovementVector.ZERO, fast, forward(1, false), noVault).kind());
        assertEquals(VaultType.VAULT, VaultPlanner.plan(rail, MovementVector.ZERO, fast, forward(1, false), noMantle).kind());
        assertNull(VaultPlanner.plan(platform, MovementVector.ZERO, fast, forward(1, false), noMantle));
        assertNull(VaultPlanner.plan(rail, MovementVector.ZERO, fast, forward(1, false), noTraversal));
        assertNull(VaultPlanner.plan(platform, MovementVector.ZERO, fast, forward(1, false), noTraversal));

        for (World world : new World[]{rail, platform}) {
            var path = VaultPlanner.plan(world, MovementVector.ZERO, fast, forward(1, false));
            var traversal = new TraversalState(path, 0, fast);
            var disabled = path.kind().onto() ? noMantle : noVault;
            var next = TraversalController.step(traversal, 0, MovementVector.ZERO, fast,
                    dev.mineedge.movement.BlockMotionState.EMPTY, forward(1, false), world, disabled);
            assertTrue(next.aborted());
            assertNull(next.traversal().path());
            assertFalse(world.world.overlaps(world.bounds(next.motion().position(), next.body())));
        }
    }

    @Test
    void halfBlockFullBlockAndThinFenceGeometrySelectWithoutWholeBlockApproximations() {
        var rising = new MovementVector(0, 6, 6);
        assertEquals(VaultType.VAULT, VaultPlanner.plan(new World(obstacle(0.5, 1)), MovementVector.ZERO, rising, forward(1, false)).kind());
        assertEquals(VaultType.VAULT, VaultPlanner.plan(new World(obstacle(1, 1)), MovementVector.ZERO, rising, forward(1, false)).kind());
        var fence = box(-0.125, 0, 1, 0.125, 1.5, 1.25);
        assertEquals(VaultType.HIGH_VAULT, VaultPlanner.plan(new World(fence), MovementVector.ZERO, rising, forward(1, false)).kind());
        assertEquals(VaultType.LOW_STEP, VaultPlanner.plan(new World(obstacle(0.5, 2)), MovementVector.ZERO,
                new MovementVector(0, -2, 3), forward(1, false)).kind());
    }

    @Test void traversalOnlyStartsWithinOneBlockEvenAtHighSpeedOrWithAWideBody() {
        var platform = obstacle(1, 4);
        for (double speed : new double[]{1, 6, 1000}) {
            var velocity = new MovementVector(0, 6, speed);
            assertNotNull(VaultPlanner.plan(new World(platform), MovementVector.ZERO, velocity, forward(1, false)), "One-block reach rejected");
            assertNull(VaultPlanner.plan(new World(platform), new MovementVector(0, 0, -0.01), velocity, forward(1, false)), "Reached beyond one block");
            assertNull(VaultPlanner.plan(new World(platform), new MovementVector(0, 0, -1), velocity, forward(1, false)), "Reached a two-block obstacle");
        }
        World wide = new World(platform) {
            @Override public CollisionBox bounds(MovementVector feet, MovementBody body) {
                return CollisionBox.body(feet, 1.2, body.height());
            }
        };
        assertNull(VaultPlanner.plan(wide, new MovementVector(0, 0, -0.01), new MovementVector(0, 6, 6), forward(1, false)),
                "Body width extended the activation range");
    }

    @Test void vaultAndMantleEntryRequireFacingWithin45Degrees() {
        for (double depth : new double[]{0.4, 4}) {
            var world = new World(obstacle(1, depth));
            for (float yaw : new float[]{-60, -45.1f, -45, 0, 45, 45.1f, 60}) {
                var command = new MovementInput(1, 1, 0, 0, false, false, false, false, true, yaw, 0);
                var path = VaultPlanner.plan(world, new MovementVector(0, 0, 0.5), new MovementVector(0, 6, 6), command);
                assertEquals(Math.abs(yaw) <= 45, path != null, "Unexpected traversal for depth=" + depth + ", yaw=" + yaw);
            }
        }
    }

    @Test void narrowBarsAndRailsAllowMantlingOntoTheirActualTop() {
        for (double depth : new double[]{0.0625, 0.125, 0.25}) {
            var rail = box(-0.0625, 0, 1, 0.0625, 1, 1 + depth);
            var world = new World(rail);
            var path = VaultPlanner.plan(world, MovementVector.ZERO, new MovementVector(0, 6, 1), forward(1, false));
            assertNotNull(path, "No mantle onto rail depth=" + depth);
            assertTrue(path.kind().onto()); assertEquals(1, path.end().y(), 1.0E-9);
            assertFalse(world.world.overlaps(world.bounds(path.end(), MovementBody.STANDING)));
            assertNotNull(world.world.sweep(world.bounds(path.end(), MovementBody.STANDING), new MovementVector(0, -0.01, 0)));
            var state = new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 1), MovementState.Mode.GROUND, 0, true, 0);
            for (int n = 1; n <= 20 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); n++) state = tick(state, world, n, true);
            assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
            assertEquals(MovementState.Mode.GROUND, state.mode()); assertEquals(1, state.position().y(), 1.0E-6);
            events.clear();
        }
    }

    @Test void barsOnAWallAllowAShortMantleWhenTheNearEdgeIsStandable() {
        var wall = obstacle(1, 4);
        var bars = box(-3, 1, 1.4375, 3, 2, 1.5625);
        var world = new World(wall, bars);
        var path = VaultPlanner.plan(world, MovementVector.ZERO, new MovementVector(0, 6, 6), forward(1, false));
        assertNotNull(path); assertTrue(path.kind().onto());
        assertEquals(1, path.end().y(), 1.0E-9);
        assertTrue(world.bounds(path.end(), MovementBody.STANDING).max().z() <= bars.min().z());
        var state = start();
        for (int n = 1; n <= 30 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); n++) state = tick(state, world, n, true);
        assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
        assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT)); assertEquals(1, state.position().y(), 1.0E-6);
    }

    @Test void runningJumpMantlesOntoActualClosedTrapdoorShapes() {
        for (var block : List.of(Blocks.OAK_TRAPDOOR, Blocks.IRON_TRAPDOOR)) for (var half : Half.values()) {
            events.clear(); var world = new World();
            var trapdoor = block.defaultBlockState().setValue(BlockStateProperties.OPEN, false).setValue(BlockStateProperties.HALF, half);
            var shapes = new ArrayList<CollisionBox>(List.of(FLOOR));
            for (var bounds : trapdoor.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs())
                shapes.add(MinecraftCollisionWorld.core(bounds).move(new MovementVector(0, 1, 1)));
            world.world = new BoxWorld(shapes);
            var state = new MovementState(new MovementVector(0.5, 0, 0), new MovementVector(0, 0, 1), MovementState.Mode.GROUND, 0, true, 0);
            for (int n = 1; n <= 35 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); n++) state = tick(state, world, n, true);
            assertTrue(events.contains(MovementEvent.MANTLE_START), "No mantle for " + trapdoor);
            assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE)); assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertEquals(MovementState.Mode.GROUND, state.mode());
            assertEquals(half == Half.TOP ? 2 : 1.1875, state.position().y(), 1.0E-6);
        }
    }

    @Test void mantleUsesOriginalTimingAndKeepsTheSupportedSlideShort() {
        var path = VaultPlanner.plan(new World(obstacle(1, 4)), MovementVector.ZERO,
                new MovementVector(0, 6, 7), forward(1, false));
        assertNotNull(path);
        assertEquals(VaultType.MANTLE, path.kind());
        assertEquals(0.65, path.kind().duration(), 1.0E-9);
        assertTrue(path.position(0.1).y() > 0.5, "Original climb speed was not restored");
        assertEquals(2.38, path.end().z(), 1.0E-9, "Supported mantle slide went beyond one block");
        assertEquals(1 / 0.3, path.exitVelocity().horizontalSpeed(), 1.0E-9);
        assertEquals(0.5, VaultType.LOW_STEP.duration(), 1.0E-9);
        assertEquals(0.65, VaultType.SLOW_MANTLE.duration(), 1.0E-9);
        assertEquals(1.17, VaultType.HIGH_MANTLE.duration(), 1.0E-9);
    }

    @Test void continuousPlatformsCapTheSlideAfterTheLipAtOneBlockAcrossSpeedsAndHeights() {
        for (double height : new double[]{0.5, 1, 1.6}) for (double speed : new double[]{1, 7, 1000}) {
            double vertical = height == 0.5 ? -1 : 6;
            var path = VaultPlanner.plan(new World(obstacle(height, 6)), MovementVector.ZERO,
                    new MovementVector(0, vertical, speed), forward(1, false));
            assertNotNull(path, "No mantle for height/speed " + height + "/" + speed);
            assertTrue(path.kind().onto());
            assertTrue(path.end().subtract(path.over()).horizontalSpeed() <= 1 + 1.0E-9,
                    "Continuous top caused a long slide");
            assertEquals(height, path.end().y(), 1.0E-9);
        }
    }

    @Test void gapInOrImmediatelyAfterTheMantleSlideRetainsLongerTravel() {
        var velocity = new MovementVector(0, 6, 7);
        // The first roof is mantle-depth; the original endpoint lands on a separate roof.
        World crossing = new World();
        crossing.world = new BoxWorld(List.of(box(-20, -1, -20, 20, 0, 1), obstacle(1, 1.6),
                box(-3, 0, 3.2, 3, 1, 8)));
        var path = VaultPlanner.plan(crossing, MovementVector.ZERO, velocity, forward(1, false), REFERENCE);
        assertNotNull(path); assertEquals(VaultType.MANTLE, path.kind());
        assertEquals(3.54, path.end().z(), 1.0E-9);
        assertTrue(path.end().subtract(path.over()).horizontalSpeed() > 1);
        World approaching = new World();
        approaching.world = new BoxWorld(List.of(box(-20, -1, -20, 20, 0, 1), obstacle(1, 2.8)));
        path = VaultPlanner.plan(approaching, MovementVector.ZERO, velocity, forward(1, false), REFERENCE);
        assertNotNull(path); assertEquals(3.54, path.end().z(), 1.0E-9);
        assertTrue(path.end().subtract(path.over()).horizontalSpeed() > 1);
    }

    @Test void thinObstacleVaultStillClearsTheFarEdgeWithOrWithoutGroundBeyondIt() {
        var rail = obstacle(1, 1);
        for (boolean gap : new boolean[]{false, true}) {
            var world = new World(rail);
            if (gap) world.world = new BoxWorld(List.of(box(-20, -1, -20, 20, 0, 1), rail));
            var path = VaultPlanner.plan(world, MovementVector.ZERO, new MovementVector(0, 6, 7), forward(1, false));
            assertNotNull(path); assertEquals(VaultType.VAULT, path.kind());
            assertTrue(path.over().z() > rail.max().z() + 0.3);
            assertTrue(path.end().subtract(path.over()).horizontalSpeed() > 1);
            assertEquals(gap, path.landing() == null);
        }
    }

    @Test
    void excessiveHeightFallingTooFastBadViewAndBlockedHeadroomReject() {
        var rising = new MovementVector(0, 6, 6);
        assertNull(VaultPlanner.plan(new World(obstacle(4, 1)), MovementVector.ZERO, rising, forward(1, false)));
        assertNull(VaultPlanner.plan(new World(obstacle(1.6, 4)), MovementVector.ZERO, new MovementVector(0, -1, 6), forward(1, false)));
        assertNull(VaultPlanner.plan(new World(obstacle(0.5, 2)), MovementVector.ZERO, new MovementVector(0, -8, 6), forward(1, false)));
        var lookingDown = new MovementInput(1, 1, 0, 0, false, false, false, false, true, 0, 90);
        assertNull(VaultPlanner.plan(new World(obstacle(1, 1)), MovementVector.ZERO, rising, lookingDown));
        var ceiling = box(-3, 1.59, -1, 3, 3, 7);
        assertNull(VaultPlanner.plan(new World(obstacle(1, 1), ceiling), MovementVector.ZERO, rising, forward(1, false)));
    }

    @Test
    void blockedLandingOrIntermediatePathCannotProduceTraversal() {
        var rising = new MovementVector(0, 6, 6);
        var blockedEnd = box(-3, 0, 1.1, 3, 3, 6);
        assertNull(VaultPlanner.plan(new World(obstacle(1, 1), blockedEnd), MovementVector.ZERO, rising, forward(1, false)));
        var blockedTop = box(-3, 1, 1.1, 3, 2.5, 2.2);
        assertNull(VaultPlanner.plan(new World(obstacle(0.9, 4), blockedTop), MovementVector.ZERO, rising, forward(1, false)));
    }

    @Test
    void jumpAtRailCompletesWithReferenceDurationExitSpeedAndNoDuplicateLaunch() {
        World world = new World(obstacle(1, 1));
        MovementState state = start();
        for (int sequence = 1; sequence <= 13; sequence++) {
            state = tick(state, world, sequence, sequence <= 12);
            if (sequence < 13) {
                assertEquals(MovementState.Mode.VAULT, state.mode());
            }
        }
        assertNull(state.traversal().path());
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertEquals(MovementBody.STANDING, state.body());
        assertEquals(7.2, state.velocity().horizontalSpeed(), 1.0E-9);
        assertTrue(state.position().z() > 2.3);
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.JUMP).count());
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.VAULT_START).count());
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.TRAVERSAL_COMPLETE).count());
        assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
    }

    @Test
    void deepPlatformMantleEndsOnTopAndWalkingAloneDoesNotStartTraversal() {
        World world = new World(obstacle(1, 4));
        MovementState state = start();
        for (int sequence = 1; sequence <= 13; sequence++) {
            state = tick(state, world, sequence, true);
        }
        assertEquals(1, state.position().y(), 1.0E-9);
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertTrue(events.contains(MovementEvent.MANTLE_START));
        events.clear();
        state = start();
        for (int sequence = 1; sequence <= 20; sequence++) {
            state = tick(state, world, sequence, false);
        }
        assertNull(state.traversal().path());
        assertFalse(events.contains(MovementEvent.MANTLE_START));
        assertEquals(0, state.position().y());
    }

    @Test void mantlesIntoLowEntrancesChooseTheLargestFittingPoseAndReplay() {
        for (double height : new double[]{0.75, 1, 1.6}) for (double clearance : new double[]{1.5, 1.499, 1, 0.6}) {
            events.clear();
            var platform = obstacle(height, 6);
            var ceiling = box(-3, height + clearance, 1, 3, height + clearance + 1, 7);
            var world = new World(platform, ceiling);
            MovementBody expected = clearance >= 1.5 ? MovementBody.CROUCHING : MovementBody.LOW;
            var state = start();
            for (int sequence = 1; sequence <= 30 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); sequence++) {
                var previous = state;
                state = tick(previous, world, sequence, true);
                assertEquals(state, controller.tick(previous, forward(sequence, true), REFERENCE,
                        new World(platform, ceiling), e -> {}), "Low-entrance prediction diverged");
                if (state.traversal().path() != null) {
                    assertEquals(expected, state.traversal().path().body());
                    assertEquals(expected, state.body());
                }
            }
            assertTrue(events.contains(MovementEvent.MANTLE_START), "No mantle at height/clearance=" + height + "/" + clearance);
            assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
            assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertEquals(MovementState.Mode.GROUND, state.mode());
            assertEquals(height, state.position().y(), 1.0E-9);
            assertEquals(expected, state.body());
            for (int i = 0; i < 5; i++) state = controller.tick(state, MovementInput.idle(31 + i, 0, 0), REFERENCE, world, events::add);
            assertEquals(expected, state.body(), "Expanded into the entrance ceiling");
            world.world = new BoxWorld(List.of(FLOOR, platform));
            state = controller.tick(state, MovementInput.idle(40, 0, 0), REFERENCE, world, events::add);
            assertEquals(MovementBody.STANDING, state.body(), "Did not stand when clearance returned");
        }
    }

    @Test void lowEntranceMantleReleaseSettlesInItsFittedPose() {
        for (double clearance : new double[]{1.5, 1}) {
            var world = new World(obstacle(1, 6), box(-3, 1 + clearance, 1, 3, 2 + clearance, 7));
            var path = VaultPlanner.plan(world, MovementVector.ZERO, new MovementVector(0, 3, 6), forward(1, true));
            assertNotNull(path);
            double elapsed = path.kind().duration() * 0.55;
            var climbing = new MovementState(path.position(elapsed), MovementVector.ZERO, MovementState.Mode.MANTLE,
                    elapsed, false, 0, dev.mineedge.movement.LocomotionState.EMPTY, dev.mineedge.movement.BlockMotionState.EMPTY,
                    path.body(), 0, new TraversalState(path, 0, MovementVector.ZERO));
            events.clear();
            var released = controller.tick(climbing, MovementInput.idle(2, 0, 0), REFERENCE, world, events::add);
            assertEquals(MovementState.Mode.GROUND, released.mode());
            assertEquals(path.body(), released.body());
            assertEquals(1, released.position().y(), 1.0E-9);
            assertNull(released.traversal().path());
            assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
            assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
        }
    }

    @Test void entrancesTooSmallToCrawlIntoRejectMantlingAndNewObstructionsAbort() {
        var platform = obstacle(1, 6);
        assertNull(VaultPlanner.plan(new World(platform, box(-3, 1.59, 1, 3, 3, 7)), MovementVector.ZERO,
                new MovementVector(0, 3, 6), forward(1, true)));
        var world = new World(platform, box(-3, 2, 1, 3, 3, 7));
        var climbing = tick(start(), world, 1, true);
        assertEquals(MovementBody.LOW, climbing.body());
        world.world = new BoxWorld(List.of(FLOOR, platform, box(-3, 1.59, 1, 3, 3, 7)));
        events.clear();
        var aborted = tick(climbing, world, 2, true);
        assertNull(aborted.traversal().path());
        assertTrue(events.contains(MovementEvent.TRAVERSAL_ABORT));
        assertFalse(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
    }

    @Test void releasingSpaceOnGroundStopsMantlingWithoutMovingThePlayerOffSupport() {
        var world = new World(obstacle(1, 4));
        var path = VaultPlanner.plan(world, MovementVector.ZERO, new MovementVector(0, 6, 7), forward(1, true));
        assertNotNull(path);
        for (boolean onTop : new boolean[]{false, true}) {
            events.clear();
            var feet = onTop ? path.end() : path.from();
            var state = new MovementState(feet, MovementVector.ZERO, MovementState.Mode.MANTLE,
                    onTop ? path.kind().duration() : 0, true, 0, dev.mineedge.movement.LocomotionState.EMPTY,
                    dev.mineedge.movement.BlockMotionState.EMPTY, MovementBody.CROUCHING, 0,
                    new TraversalState(path, 0, MovementVector.ZERO));
            var stopped = controller.tick(state, MovementInput.idle(2, 0, 0), REFERENCE, world, events::add);
            assertEquals(MovementState.Mode.GROUND, stopped.mode());
            assertTrue(stopped.grounded());
            assertNull(stopped.traversal().path());
            assertEquals(feet, stopped.position());
            assertEquals(MovementVector.ZERO, stopped.velocity());
            assertEquals(onTop ? 0 : 1, events.stream().filter(e -> e == MovementEvent.TRAVERSAL_ABORT).count());
            assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
            assertEquals(onTop, events.contains(MovementEvent.TRAVERSAL_COMPLETE));
        }
    }

    @Test void releasingSpaceBeforeHalfwayWithoutTopSupportDropsMantleToHangWithoutRestarting() {
        for (double height : new double[]{2, 2.5, 2.51, 3, 3.3}) for (int releaseTick : new int[]{2, 4, 5}) {
            events.clear();
            var world = new World(obstacle(height, 4));
            var state = new MovementState(new MovementVector(0, height - 1.3, 0.65), new MovementVector(0, 3, 6),
                    MovementState.Mode.AIR, 0, false, 0);
            for (int i = 1; i < releaseTick; i++) state = tick(state, world, i, true);
            var climbing = state;
            var released = tick(climbing, world, releaseTick, false);
            assertEquals(MovementState.Mode.LEDGE_HANG, released.mode());
            assertNull(released.traversal().path());
            assertTrue(released.position().y() < climbing.position().y());
            assertEquals(height - 1.828, released.position().y(), 1.0E-6);
            assertEquals(MovementVector.ZERO, released.velocity());
            assertEquals(released, controller.tick(climbing, forward(releaseTick, false), REFERENCE,
                    new World(obstacle(height, 4)), e -> {}));
            var hangPosition = released.position();
            for (int i = releaseTick + 1; i <= releaseTick + 20; i++) released = tick(released, world, i, false);
            assertEquals(MovementState.Mode.LEDGE_HANG, released.mode());
            assertEquals(hangPosition, released.position());
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.TRAVERSAL_ABORT).count());
            assertFalse(events.contains(MovementEvent.PULL_UP_START));
            assertFalse(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
        }
    }

    @Test void releasingMantleAlreadyOverSupportBeforeHalfwayStaysOnTopAndReplays() {
        for (double height : new double[]{3.3, 3.8}) for (double speed : new double[]{1, 6}) {
            var world = new World(obstacle(height, 4));
            var state = new MovementState(new MovementVector(0, 2, 0.65), new MovementVector(0, 3, speed),
                    MovementState.Mode.AIR, 0, false, 0);
            for (int i = 1; i <= 20; i++) {
                state = tick(state, world, i, true);
                if (state.position().y() >= height && world.bounds(state.position(), state.body()).max().z() > 1) break;
            }
            assertEquals(MovementState.Mode.MANTLE, state.mode());
            assertTrue(state.traversal().path().canCancelOnRelease(state.modeTime()), "Fixture was already past the halfway timer");
            var standing = new MovementVector(state.position().x(), height, state.position().z());
            assertTrue(world.canOccupy(standing, MovementBody.STANDING));
            var climbing = state;
            events.clear();
            var release = MovementInput.idle(21, 0, 0);
            var stopped = controller.tick(climbing, release, REFERENCE, world, events::add);
            assertEquals(MovementState.Mode.GROUND, stopped.mode(), "Release pulled a standable mantle back into a hang");
            assertTrue(stopped.grounded());
            assertNull(stopped.traversal().path());
            assertEquals(standing, stopped.position());
            assertEquals(MovementVector.ZERO, stopped.velocity());
            assertEquals(MovementBody.STANDING, stopped.body());
            assertEquals(stopped, controller.tick(climbing, release, REFERENCE, new World(obstacle(height, 4)), e -> {}));
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.TRAVERSAL_COMPLETE).count());
            assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
        }
    }

    @Test void mantleReleaseNearOrAfterHalfwayFinishesAtFirstStandablePositionAndReplays() {
        for (double height : new double[]{1, 1.8}) for (double offset : new double[]{-1.0E-6, 0, 0.1}) {
            events.clear();
            var world = new World(obstacle(height, 4));
            var path = VaultPlanner.plan(world, MovementVector.ZERO, new MovementVector(0, 3, 6), forward(1, true));
            assertNotNull(path);
            double elapsed = path.kind().duration() * 0.5 + offset;
            var feet = path.position(elapsed);
            var traversal = new TraversalState(path, 0, MovementVector.ZERO);
            var climbing = new MovementState(feet, MovementVector.ZERO, MovementState.Mode.MANTLE, elapsed, false, 0,
                    dev.mineedge.movement.LocomotionState.EMPTY, dev.mineedge.movement.BlockMotionState.EMPTY,
                    MovementBody.CROUCHING, 0, traversal);
            var release = MovementInput.idle(2, 0, 0);
            var state = controller.tick(climbing, release, REFERENCE, world, events::add);
            assertEquals(state, controller.tick(climbing, release, REFERENCE, new World(obstacle(height, 4)), e -> {}));
            for (int i = 0; i < 30 && state.traversal().path() != null; i++) {
                assertEquals(path, state.traversal().path());
                state = controller.tick(state, release, REFERENCE, world, events::add);
            }
            assertTrue(state.grounded());
            assertEquals(MovementState.Mode.GROUND, state.mode());
            assertEquals(MovementBody.STANDING, state.body());
            assertEquals(height, state.position().y(), 1.0E-9);
            assertTrue(state.position().z() < path.end().z(), "Release continued the mantle slide beyond its first support");
            assertEquals(MovementVector.ZERO, state.velocity());
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.TRAVERSAL_COMPLETE).count());
            assertFalse(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
        }
    }

    @Test void releasingSpaceWithoutHangClearanceContinuesTheOriginalJumpInsteadOfStayingInMantle() {
        var world = new World(obstacle(1, 4));
        var climbing = tick(start(), world, 1, true);
        var released = tick(climbing, world, 2, false);
        assertNull(released.traversal().path());
        assertEquals(MovementState.Mode.AIR, released.mode());
        assertTrue(released.position().y() > climbing.position().y());
        assertTrue(released.velocity().y() > 0);
        assertTrue(released.velocity().horizontalSpeed() > 6, "Cancel discarded the original forward momentum");
        assertFalse(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.JUMP).count());
    }

    @Test void aSingleSpaceTapIntoAWallResumesTheOriginalJumpArcAtTwentyHertzAndReplays() {
        for (double height : new double[]{1, 1.5, 1.8}) for (double factor : new double[]{0.5, 1, 1.4}) {
            events.clear();
            var world = new World(obstacle(height, 4)) {
                @Override public double jumpFactor(MovementVector feet, MovementBody body) { return factor; }
            };
            var empty = new World() {
                @Override public double jumpFactor(MovementVector feet, MovementBody body) { return factor; }
            };
            var state = controller.step(start(), forward(1, true), MovementTuning.DEFAULT, world, events::add);
            assertEquals(MovementState.Mode.MANTLE, state.mode());
            assertEquals(MovementTuning.DEFAULT.jumpSpeed() * factor, state.traversal().entryVelocity().y(), 1.0E-9);
            var baseline = state;
            var ordinary = controller.step(start(), forward(1, true), MovementTuning.DEFAULT, empty, e -> {});
            // The held step follows a scripted climb rather than the ordinary jump arc.
            // Release must preserve the remaining arc from that displaced height.
            double mantleHeightOffset = state.position().y() - ordinary.position().y();
            double peak = state.position().y(), ordinaryPeak = ordinary.position().y();
            for (int i = 2; i <= 80; i++) {
                var input = i == 2 ? forward(i, false) : MovementInput.idle(i, 0, 0);
                state = controller.step(state, input, MovementTuning.DEFAULT, world, events::add);
                ordinary = controller.step(ordinary, input, MovementTuning.DEFAULT, empty, e -> {});
                peak = Math.max(peak, state.position().y());
                ordinaryPeak = Math.max(ordinaryPeak, ordinary.position().y());
                if (i == 2) {
                    assertNull(state.traversal().path());
                    assertEquals(ordinary.velocity().y(), state.velocity().y(), 1.0E-9, "Cancel changed the original jump velocity");
                    assertEquals(state, controller.step(baseline, input, MovementTuning.DEFAULT, world, e -> {}));
                }
            }
            assertEquals(ordinaryPeak + mantleHeightOffset, peak, 1.0E-9,
                    "Mantle release changed the remaining jump arc at height/factor=" + height + "/" + factor);
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.JUMP).count());
            assertFalse(events.contains(MovementEvent.WALLCLIMB_START));
            assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
        }
    }

    @Test void releasingMantlesWithoutFloorClearanceResumesJumpInsteadOfHanging() {
        for (double height : new double[]{1.5, 1.8}) {
            events.clear();
            var world = new World(obstacle(height, 4));
            var initial = new MovementState(new MovementVector(0, height - 1.5, 0.65), new MovementVector(0, 3, 6),
                    MovementState.Mode.AIR, 0, false, 0);
            var climbing = tick(initial, world, 1, true);
            assertEquals(MovementState.Mode.MANTLE, climbing.mode());
            var released = tick(climbing, world, 2, false);
            assertEquals(MovementState.Mode.AIR, released.mode());
            assertNull(released.traversal().path());
            assertTrue(released.position().y() > climbing.position().y());
            assertEquals(initial.velocity().y() - REFERENCE.gravity() * 0.1, released.velocity().y(), 1.0E-9);
            for (int i = 3; i <= 40 && !released.grounded(); i++)
                released = controller.tick(released, MovementInput.idle(i, 0, 0), REFERENCE, world, events::add);
            assertEquals(MovementState.Mode.GROUND, released.mode());
            assertEquals(MovementBody.STANDING, released.body());
            assertEquals(0, released.position().y(), 1.0E-6);
            assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
        }
    }

    @Test void mantleCancelPreservesSidewaysMomentumAndElapsedGravityEvenAfterTheJumpApex() {
        for (int releaseStep : new int[]{2, 3, 5}) {
            events.clear();
            var hangObstruction = box(-3, 0, -1, 3, 0.25, 0.99);
            var world = new World(obstacle(2, 4), hangObstruction);
            var entry = new MovementVector(2, 3, 6);
            var state = new MovementState(new MovementVector(0, 0.5, 0.65), entry, MovementState.Mode.AIR, 0, false, 0);
            for (int i = 1; i < releaseStep; i++) {
                state = controller.step(state, forward(i, true), MovementTuning.DEFAULT, world, events::add);
                assertEquals(entry, state.traversal().entryVelocity());
            }
            var climbing = state;
            var command = MovementInput.idle(releaseStep, 0, 0);
            var released = controller.step(climbing, command, MovementTuning.DEFAULT, world, events::add);
            assertNull(released.traversal().path());
            assertEquals(MovementState.Mode.AIR, released.mode());
            assertEquals(entry.x(), released.velocity().x(), 1.0E-9, "Cancel erased sideways momentum");
            assertEquals(entry.y() - MovementTuning.DEFAULT.gravity() * releaseStep / MovementRate.HZ, released.velocity().y(), 1.0E-9);
            assertEquals(0, released.velocity().z(), 1.0E-9, "Restored momentum bypassed the wall collision");
            assertFalse(world.world.overlaps(world.bounds(released.position(), released.body())));
            assertEquals(released, controller.step(climbing, command, MovementTuning.DEFAULT,
                    new World(obstacle(2, 4), hangObstruction), e -> {}));
            assertEquals(1, events.stream().filter(e -> e == MovementEvent.TRAVERSAL_ABORT).count());
            assertFalse(events.contains(MovementEvent.JUMP));
        }
    }

    @Test void blockAndSlabMantleReleaseDoesNotCreateACrouchedHangAboveTheGround() {
        var world = new World(obstacle(1, 4), box(-3, 1, 1, 3, 1.5, 5));
        var climbing = tick(start(), world, 1, true);
        assertEquals(MovementState.Mode.MANTLE, climbing.mode());
        var released = tick(climbing, world, 2, false);
        assertNotEquals(MovementState.Mode.LEDGE_HANG, released.mode());
        for (int i = 3; i <= 40 && !released.grounded(); i++)
            released = controller.tick(released, MovementInput.idle(i, 0, 0), REFERENCE, world, events::add);
        assertEquals(MovementState.Mode.GROUND, released.mode());
        assertEquals(MovementBody.STANDING, released.body());
        assertEquals(0, released.position().y(), 1.0E-6);
        assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
    }

    @Test void mantleReleaseStillRespectsExternalImpulsesRemovedSupportAndBlockedHang() {
        var world = new World(obstacle(3.3, 4));
        var state = new MovementState(new MovementVector(0, 2, 0.65), new MovementVector(0, 3, 6), MovementState.Mode.AIR, 0, false, 0);
        for (int i = 1; i <= 4; i++) state = tick(state, world, i, true);
        var pushed = tick(state.withVelocity(state.velocity().add(new MovementVector(3, 0, 0))), world, 5, false);
        assertNull(pushed.traversal().path());
        assertNotEquals(MovementState.Mode.LEDGE_HANG, pushed.mode());
        assertTrue(pushed.velocity().x() >= 3 - 1.0E-6);
        world.world = new BoxWorld(List.of(FLOOR, obstacle(3.3, 4), box(-0.4, 1.3, 0.3, 0.4, 2, 0.9)));
        var blocked = tick(state, world, 5, false);
        assertNull(blocked.traversal().path());
        assertEquals(MovementState.Mode.AIR, blocked.mode());
        world.world = new BoxWorld(List.of(FLOOR));
        var removed = tick(state, world, 5, false);
        assertNull(removed.traversal().path());
        assertEquals(MovementState.Mode.AIR, removed.mode());
        assertTrue(events.contains(MovementEvent.TRAVERSAL_ABORT));
    }

    @Test void committedMantleReleaseStillAbortsOnKnockbackOrRemovedSupport() {
        for (boolean knockback : new boolean[]{false, true}) {
            var world = new World(obstacle(3.3, 4));
            var state = new MovementState(new MovementVector(0, 2, 0.65), new MovementVector(0, 3, 6), MovementState.Mode.AIR, 0, false, 0);
            for (int i = 1; i <= 7; i++) state = tick(state, world, i, true);
            assertFalse(state.traversal().path().canCancelOnRelease(state.modeTime()));
            if (knockback) state = state.withVelocity(state.velocity().add(new MovementVector(3, 0, 0)));
            else world.world = new BoxWorld(List.of(FLOOR));
            events.clear();
            var released = controller.tick(state, MovementInput.idle(8, 0, 0), REFERENCE, world, events::add);
            assertNull(released.traversal().path());
            assertEquals(MovementState.Mode.AIR, released.mode());
            assertTrue(events.contains(MovementEvent.TRAVERSAL_ABORT));
            assertFalse(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
            assertFalse(events.contains(MovementEvent.LEDGE_GRAB));
            if (knockback) assertTrue(released.velocity().x() >= 3 - 1.0E-9);
        }
    }

    @Test
    void removedAnchorLandingAndNewObstructionAbortWithoutStoredMomentumLaunch() {
        var support = obstacle(1, 1);
        World world = new World(support);
        MovementState baseline = tick(start(), world, 1, true);
        assertNotNull(baseline.traversal().path());
        world.world = new BoxWorld(List.of(FLOOR));
        var removed = tick(baseline, world, 2, false);
        assertNull(removed.traversal().path());
        assertTrue(events.contains(MovementEvent.TRAVERSAL_ABORT));
        assertTrue(removed.velocity().horizontalSpeed() < 0.2); // Only ordinary low-speed air assist resumes.
        events.clear();
        world.world = new BoxWorld(List.of(support, FLOOR, box(-3, 1, 2, 3, 3, 3)));
        var blocked = tick(baseline, world, 2, false);
        assertNull(blocked.traversal().path());
        assertTrue(events.contains(MovementEvent.TRAVERSAL_ABORT));
        events.clear();
        world.world = new BoxWorld(List.of(support));
        var lostLanding = tick(baseline, world, 2, false);
        assertNull(lostLanding.traversal().path());
        assertTrue(events.contains(MovementEvent.TRAVERSAL_ABORT));
    }

    @Test
    void externalImpulseOrDisplacementInvalidatesScriptInsteadOfPullingPlayerBack() {
        World world = new World(obstacle(1, 1));
        var baseline = tick(start(), world, 1, true);
        var pushed = tick(baseline.withVelocity(baseline.velocity().add(new MovementVector(3, 1, 0))), world, 2, false);
        assertNull(pushed.traversal().path());
        assertTrue(pushed.velocity().x() >= 3 - 1.0E-6);
        assertTrue(events.contains(MovementEvent.TRAVERSAL_ABORT));
        events.clear();
        world.offset = new MovementVector(0.5, 0, 0);
        var displaced = tick(baseline, world, 2, false);
        assertNull(displaced.traversal().path());
        assertTrue(displaced.position().x() >= 0.5);
        assertTrue(events.contains(MovementEvent.TRAVERSAL_ABORT));
    }

    @Test
    void absentLandingCanExitToAirAndProbeDistanceRemainsBoundedAtExtremeSpeed() {
        World world = new World(obstacle(1, 1));
        world.world = new BoxWorld(List.of(box(-20, -1, -20, 20, 0, 1), obstacle(1, 1)));
        var path = VaultPlanner.plan(world, MovementVector.ZERO, new MovementVector(0, 6, 6), forward(1, false));
        assertNotNull(path);
        assertNull(path.landing());
        var state = start();
        for (int sequence = 1; sequence <= 13; sequence++) {
            state = tick(state, world, sequence, sequence == 1);
        }
        assertEquals(MovementState.Mode.AIR, state.mode());
        assertFalse(state.grounded());
        world = new World(obstacle(1, 1));
        path = VaultPlanner.plan(world, MovementVector.ZERO, new MovementVector(0, 6, 1000), forward(1, false));
        assertNotNull(path);
        assertTrue(world.largestQuery < 12);
        assertTrue(path.kind().duration() <= 1.2);
        assertTrue(path.exitVelocity().horizontalSpeed() <= 7.7706 + 1.0E-6);
    }

    @Test
    void authoritativeMidPathBaselineReplaysAllRemainingPhasesExactly() {
        World world = new World(obstacle(1.6, 4));
        MovementState state = start();
        MovementState baseline = null;
        var inputs = new ArrayList<MovementInput>();
        for (int sequence = 1; sequence <= 24; sequence++) {
            MovementInput input = forward(sequence, true).withEdges(sequence == 1 || sequence == 10, false);
            inputs.add(input);
            state = controller.tick(state, input, MovementTuning.DEFAULT, world, events::add);
            if (sequence == 7) {
                baseline = state;
                assertEquals(MovementState.Mode.MANTLE, state.mode());
                assertNotNull(state.traversal().path());
            }
        }
        MovementState replay = baseline;
        for (int i = 7; i < inputs.size(); i++) {
            replay = controller.tick(replay, inputs.get(i), MovementTuning.DEFAULT, world, event -> {});
        }
        assertEquals(state, replay);
        assertNull(state.traversal().path());
        assertTrue(events.contains(MovementEvent.TRAVERSAL_COMPLETE));
    }
}
