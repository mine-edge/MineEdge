package dev.mineedge.movement.surface;

import dev.mineedge.movement.*;
import dev.mineedge.movement.collision.*;
import dev.mineedge.movement.landing.LandingState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceControllerTest {
    @Test void expiredSurfaceCooldownsReturnToTheEmptyReplayState() {
        var cooldown = SurfaceState.EMPTY.release(true, new MovementVector(0, 0, 3));
        assertTrue(cooldown.advance(0.25).climbCooldown() > 0);
        assertEquals(SurfaceState.EMPTY, cooldown.advance(0.5));
    }
    private static CollisionBox box(double x, double y, double z, double xx, double yy, double zz) {
        return new CollisionBox(new MovementVector(x, y, z), new MovementVector(xx, yy, zz));
    }
    private static final CollisionBox FLOOR = box(-20, -1, -20, 20, 0, 20);
    private static final CollisionBox WALL = box(-2, 0, 1, 2, 4, 5);
    private static class World implements MovementController.MotionResolver {
        BoxWorld world;
        MovementBody body = MovementBody.STANDING;
        ClimbRoute route;
        MovementVector offset = MovementVector.ZERO;
        boolean valid = true, soft, steep;
        List<CollisionBox> steps = List.of();
        World(CollisionBox... shapes) { world = new BoxWorld(List.of(shapes)); }
        @Override public void body(MovementBody body) { this.body = body; }
        @Override public CollisionWorld geometry(CollisionBox region) { return world; }
        @Override public boolean canOccupy(MovementVector feet, MovementBody body) { return !world.overlaps(bounds(feet, body)); }
        @Override public MovementVector position(MovementVector feet) { return feet.add(offset); }
        @Override public boolean stepSurface(CollisionBox shape) { return steps.contains(shape); }
        @Override public boolean stepSource(MovementVector source) { return steps.stream().anyMatch(shape -> shape.min().equals(source)); }
        @Override public ClimbRoute climbRoute(MovementVector feet, MovementVector normal) { return valid ? route : null; }
        @Override public boolean climbValid(ClimbRoute route) { return valid && route.equals(this.route); }
        @Override public SurfaceContact surface(MovementVector feet, MovementBody body) {
            var probe = CollisionBox.body(feet.add(new MovementVector(0, 0.08, 0)), 0.6, 0.02);
            var hit = world.sweep(probe, new MovementVector(0, -0.25, 0));
            if (hit == null || hit.normal().y() < 0.7) return null;
            boolean slope = steep && feet.z() < 2;
            return new SurfaceContact(hit.shape(), hit.shape().min(), slope ? new MovementVector(0, 0.66, Math.sqrt(1 - 0.66 * 0.66)) : hit.normal(),
                    slope ? new MovementVector(0, 0, 1) : MovementVector.ZERO, soft, steps.contains(hit.shape()));
        }
        @Override public MovementController.Motion resolve(MovementVector feet, MovementVector velocity, double dt) {
            return resolve(feet, velocity, dt, 0);
        }
        @Override public MovementController.Motion resolve(MovementVector feet, MovementVector velocity, double dt, double stepHeight) {
            return resolve(feet, velocity, dt, stepHeight, false);
        }
        @Override public MovementController.Motion resolve(MovementVector feet, MovementVector velocity, double dt, double stepHeight, boolean grounded) {
            var moved = CollisionMover.move(world, bounds(feet, body), velocity.scale(dt), stepHeight, grounded);
            return new MovementController.Motion(feet.add(moved.recovery()).add(moved.displacement()), moved.clip(velocity), moved.grounded());
        }
    }
    private final MovementController controller = new MovementController();
    private final List<MovementEvent> events = new ArrayList<>();
    private static MovementInput input(int n, float forward, boolean jump, boolean crouch) {
        return new MovementInput(n, forward, 0, 0, jump, jump, crouch, crouch, false, 0, 0);
    }
    private static MovementState ground(MovementVector feet, double speed) { return new MovementState(feet, new MovementVector(0, 0, speed), MovementState.Mode.GROUND, 0, true, 0); }
    private MovementState tick(MovementState state, World world, MovementInput input) {
        var next = controller.tick(state, input, MovementTuning.DEFAULT, world, events::add);
        assertFalse(world.world.overlaps(world.bounds(next.position(), next.body())), next.toString());
        return next;
    }
    private static World ladder(boolean pipe) {
        var world = new World(FLOOR, WALL);
        world.route = new ClimbRoute(new MovementVector(0, 0, 1), 4, new MovementVector(0, 0, -1), pipe, new MovementVector(0, 0, 1));
        return world;
    }

    private static World sidewaysWorld(boolean connected, boolean blocked) {
        var world = new World(FLOOR, connected ? box(-3, 0, 0.9375, 3, 4, 1.0625) : box(-0.125, 0, 0.875, 0.125, 4, 1.125)) {
            @Override public boolean climbValid(ClimbRoute value) { return valid && value.pipe() && value.top() == 4; }
            @Override public ClimbRoute climbSideways(ClimbRoute value, double height, double distance) {
                if (!connected) return value.orbit(distance);
                var base = value.base().add(new MovementVector(value.normal().z(), 0, -value.normal().x()).scale(distance));
                return Math.abs(base.x()) <= 2.5 ? new ClimbRoute(base, value.top(), value.normal(), true,
                        new MovementVector(Math.floor(base.x() + 0.5), 0, 1)) : null;
            }
        };
        world.route = new ClimbRoute(new MovementVector(0, 0, 1), 4, new MovementVector(0, 0, -1), true, new MovementVector(0, 0, 1));
        if (blocked) world.world = new BoxWorld(List.of(FLOOR, box(-0.125, 0, 0.875, 0.125, 4, 1.125), box(-2, 0, -1, -0.4, 5, 3)));
        return world;
    }

    private MovementState attached(World world) {
        var state = new MovementState(world.route.feet(2), MovementVector.ZERO, MovementState.Mode.AIR, 0, false, 0);
        for (int n = 1; n <= 20; n++) state = tick(state, world, input(n, 0, false, false));
        assertNotNull(state.surfaces().climb()); assertNull(state.surfaces().path());
        return state;
    }

    @Test void sidewaysCirclesAnIsolatedPipeInBothDirectionsAndCanReverse() {
        for (float strafe : new float[]{-1, 1}) {
            var world = sidewaysWorld(false, false); var start = attached(world); var state = start;
            state = tick(state, world,
                    new MovementInput(21, 0, strafe, strafe, false, false, false, false, false, 0, 0));
            assertTrue((state.position().x() - start.position().x()) * strafe > 0, "Pipe strafe disagrees with left/right input");
            for (int n = 22; n <= 60; n++) state = tick(state, world,
                    new MovementInput(n, 0, strafe, strafe, false, false, false, false, false, 0, 0));
            assertNotNull(state.surfaces().climb()); assertEquals(start.position().y(), state.position().y(), 1.0E-9);
            assertTrue(state.position().z() > world.route.base().z(), "Did not circle to the back of the pipe");
            assertEquals(ClimbRoute.PIPE_RADIUS, state.position().subtract(new MovementVector(0, state.position().y(), 1)).horizontalSpeed(), 1.0E-9);
            for (int n = 61; n <= 100; n++) state = tick(state, world,
                    new MovementInput(n, 0, -strafe, -strafe, false, false, false, false, false, 0, 0));
            assertTrue(state.position().subtract(start.position()).length() < 1.0E-9);
            assertFalse(events.contains(MovementEvent.CLIMB_RELEASE));
        }
    }

    @Test void sidewaysClearsSquareStoneWallCornersForAFullRevolution() {
        for (float strafe : new float[]{-1, 1}) {
            var world = sidewaysWorld(false, false);
            world.world = new BoxWorld(List.of(FLOOR, box(-0.25, 0, 0.75, 0.25, 4, 1.25)));
            var start = attached(world);
            var state = start;
            boolean reachedRear = false;
            for (int n = 21; n <= 140; n++) {
                var previous = state;
                state = tick(state, world,
                        new MovementInput(n, 0, strafe, strafe, false, false, false, false, false, 0, 0));
                assertNotNull(state.surfaces().climb());
                assertTrue(state.position().subtract(previous.position()).horizontalSpeed() > 0.04,
                        "Sideways climbing stopped at a square post's corner");
                assertEquals(start.position().y(), state.position().y(), 1.0E-9);
                reachedRear |= state.position().z() > 1.5;
            }
            assertTrue(reachedRear, "Could not climb around to the rear of the stone wall");
            for (int n = 141; n <= 260; n++) {
                state = tick(state, world,
                        new MovementInput(n, 0, -strafe, -strafe, false, false, false, false, false, 0, 0));
            }
            assertTrue(state.position().subtract(start.position()).length() < 1.0E-9);
            assertFalse(events.contains(MovementEvent.CLIMB_RELEASE));
        }
    }

    @Test void sidewaysCrossesConnectedColumnsAndStopsAtAnEndWithoutDropping() {
        for (float strafe : new float[]{-1, 1}) {
            var world = sidewaysWorld(true, false); var start = attached(world); var state = start;
            for (int n = 21; n <= 100; n++) state = tick(state, world,
                    new MovementInput(n, 0, strafe, strafe, false, false, false, false, false, 0, 0));
            assertNotNull(state.surfaces().climb()); assertEquals(start.position().y(), state.position().y(), 1.0E-9);
            assertTrue(state.position().x() * strafe > 2.4);
            assertEquals(start.position().z(), state.position().z(), 1.0E-9);
            assertNotEquals(world.route.source(), state.surfaces().climb().route().source());
            var stopped = tick(state, world, new MovementInput(101, 0, strafe, strafe, false, false, false, false, false, 0, 0));
            assertEquals(state.position(), stopped.position());
            assertFalse(events.contains(MovementEvent.CLIMB_RELEASE));
        }
    }

    @Test void sidewaysObstacleKeepsGripAndAllowsReversingAway() {
        var world = sidewaysWorld(false, true); var start = attached(world); var state = start;
        for (int n = 21; n <= 60; n++) state = tick(state, world,
                new MovementInput(n, 0, -1, -1, false, false, false, false, false, 0, 0));
        assertNotNull(state.surfaces().climb()); assertTrue(state.position().x() > -0.1);
        var stopped = tick(state, world, new MovementInput(61, 0, -1, -1, false, false, false, false, false, 0, 0));
        assertEquals(state.position(), stopped.position());
        var reversed = tick(stopped, world, new MovementInput(62, 0, 1, 1, false, false, false, false, false, 0, 0));
        assertTrue(reversed.position().x() > stopped.position().x());
        assertFalse(events.contains(MovementEvent.CLIMB_RELEASE));
    }

    @Test void sidewaysAndVerticalClimbingReplayExactlyAndJumpStillReleases() {
        var world = sidewaysWorld(true, false); var state = attached(world); var replay = state;
        for (int n = 21; n <= 45; n++) {
            var command = new MovementInput(n, 0.5f, 0.5f, 0.5f, false, false, false, false, false, 0, 0);
            state = tick(state, world, command);
            replay = controller.tick(replay, command, MovementTuning.DEFAULT, world, e -> {});
            assertEquals(state, replay);
        }
        assertTrue(state.position().x() > 0.5 && state.position().y() > 2);
        state = tick(state, world, input(46, 0, true, false));
        assertNull(state.surfaces().climb()); assertTrue(events.contains(MovementEvent.CLIMB_RELEASE));
    }

    @Test void ladderAndPipeClimbInStepsAndExitOntoClearRoof() {
        for (boolean pipe : new boolean[]{false, true}) {
            events.clear(); var world = ladder(pipe); var state = ground(MovementVector.ZERO, 2);
            for (int n = 1; n <= 140; n++) state = tick(state, world, input(n, state.mode() == MovementState.Mode.GROUND && state.position().y() > 3.9 ? 0 : 1, false, false));
            assertEquals(MovementState.Mode.GROUND, state.mode()); assertEquals(4, state.position().y(), 1.0E-6);
            assertTrue(events.contains(pipe ? MovementEvent.PIPE_GRAB : MovementEvent.LADDER_GRAB));
            assertTrue(events.containsAll(List.of(MovementEvent.CLIMB_STEP, MovementEvent.CLIMB_EXIT)));
            assertNull(state.surfaces().climb());
        }
    }

    @Test void pipeToggleBlocksGrabsAndReleasesActivePipesWhileKeepingLaddersEnabled() {
        var tuning = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(true, true, true, true, false));
        var pipe = ladder(true);
        var feet = pipe.route.feet(2);
        var start = new MovementState(feet, MovementVector.ZERO, MovementState.Mode.AIR, 0, false, 0);
        var blocked = controller.tick(start, input(1, 0, false, false), tuning, pipe, events::add);
        assertNull(blocked.surfaces().climb());
        assertFalse(events.contains(MovementEvent.PIPE_GRAB));
        var gripping = attached(pipe);
        events.clear();
        var released = controller.tick(gripping, input(21, 0, false, false), tuning, pipe, events::add);
        assertNull(released.surfaces().climb());
        assertNull(released.surfaces().path());
        assertTrue(events.contains(MovementEvent.CLIMB_RELEASE));
        assertFalse(events.contains(MovementEvent.PIPE_GRAB));
        assertFalse(pipe.world.overlaps(pipe.bounds(released.position(), released.body())));

        events.clear();
        var ladder = ladder(false);
        var state = new MovementState(ladder.route.feet(2), MovementVector.ZERO, MovementState.Mode.AIR, 0, false, 0);
        for (int n = 1; n <= 20; n++) state = controller.tick(state, input(n, 0, false, false), tuning, ladder, events::add);
        assertEquals(MovementState.Mode.LADDER_CLIMB, state.mode());
        assertTrue(events.contains(MovementEvent.LADDER_GRAB));
    }

    @Test void slidingToggleBlocksSteepSlidesAndReleasesAnActiveSlopeSlide() {
        var world = new World(FLOOR); world.steep = true;
        var tuning = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(false, true, true, true, true));
        var start = ground(MovementVector.ZERO, 3);
        var blocked = controller.tick(start, input(1, 1, false, false), tuning, world, events::add);
        assertNotEquals(MovementState.Mode.STEEP_SLIDE, blocked.mode());
        assertFalse(events.contains(MovementEvent.STEEP_SLIDE_START));
        var sliding = tick(start, world, input(1, 1, false, false));
        assertEquals(MovementState.Mode.STEEP_SLIDE, sliding.mode());
        events.clear();
        var released = controller.tick(sliding, input(2, 1, false, false), tuning, world, events::add);
        assertNotEquals(MovementState.Mode.STEEP_SLIDE, released.mode());
        assertTrue(events.contains(MovementEvent.STEEP_SLIDE_END));
        assertFalse(events.contains(MovementEvent.STEEP_SLIDE_START));
        assertFalse(world.world.overlaps(world.bounds(released.position(), released.body())));
    }
    @Test void climbCanExitOntoStandableThinGeometryInItsOwnBlockColumn() {
        for (boolean pipe : new boolean[]{false, true}) {
            events.clear(); var world = ladder(pipe);
            world.world = new BoxWorld(List.of(FLOOR, box(-0.0625, 0, 0.9375, 0.0625, 4, 1.0625)));
            var state = ground(MovementVector.ZERO, 2);
            for (int n = 1; n <= 160 && !events.contains(MovementEvent.CLIMB_EXIT); n++) state = tick(state, world, input(n, 1, false, false));
            assertTrue(events.contains(MovementEvent.CLIMB_EXIT)); assertNull(state.surfaces().climb());
            assertEquals(MovementState.Mode.GROUND, state.mode()); assertEquals(4, state.position().y(), 1.0E-6);
        }
    }
    @Test void climbExitCanStopBeforeBarsWhenTheWallLipIsStandable() {
        for (boolean pipe : new boolean[]{false, true}) {
            events.clear(); var world = ladder(pipe);
            var bars = box(-2, 4, 1.4375, 2, 5, 1.5625);
            world.world = new BoxWorld(List.of(FLOOR, WALL, bars));
            var state = ground(MovementVector.ZERO, 2);
            for (int n = 1; n <= 160 && !events.contains(MovementEvent.CLIMB_EXIT); n++) state = tick(state, world, input(n, 1, false, false));
            assertTrue(events.contains(MovementEvent.CLIMB_EXIT)); assertNull(state.surfaces().climb());
            assertEquals(MovementState.Mode.GROUND, state.mode()); assertEquals(4, state.position().y(), 1.0E-6);
            assertTrue(world.bounds(state.position(), state.body()).max().z() <= bars.min().z());
        }
    }
    @Test void blockedFrontExitsOntoClearLeftRightOrRearPlatforms() {
        var directions = new MovementVector[]{new MovementVector(1, 0, 0), new MovementVector(-1, 0, 0), new MovementVector(0, 0, -1)};
        var platforms = new CollisionBox[]{box(0.8, 3.8, 0.05, 2.8, 4, 0.8), box(-2.8, 3.8, 0.05, -0.8, 4, 0.8), box(-0.5, 3.8, -2.8, 0.5, 4, -0.3)};
        for (boolean pipe : new boolean[]{false, true}) for (int i = 0; i < platforms.length; i++) {
            events.clear(); var world = ladder(pipe);
            var obstruction = box(-2, 4, 1, 2, 7, 5);
            world.world = new BoxWorld(List.of(FLOOR, WALL, obstruction, platforms[i]));
            var state = ground(MovementVector.ZERO, 2);
            for (int n = 1; n <= 160 && !events.contains(MovementEvent.CLIMB_EXIT); n++) state = tick(state, world, input(n, 1, false, false));
            assertTrue(events.contains(MovementEvent.CLIMB_EXIT), "No clear-side exit for pipe=" + pipe + ", side=" + i);
            assertFalse(events.contains(MovementEvent.CLIMB_RELEASE));
            assertNull(state.surfaces().climb()); assertEquals(MovementState.Mode.GROUND, state.mode());
            assertEquals(4, state.position().y(), 1.0E-6);
            assertTrue(state.position().subtract(world.route.feet(4)).dot(directions[i]) > 0.6);
        }
    }
    @Test void blockedSidePlatformsKeepTheClimbAttached() {
        for (boolean pipe : new boolean[]{false, true}) {
            events.clear(); var world = ladder(pipe);
            world.world = new BoxWorld(List.of(FLOOR, WALL, box(-2, 4, 1, 2, 7, 5),
                    box(0.8, 3.8, 0.05, 2.8, 4, 0.8), box(0.8, 4, 0.05, 2.8, 7, 0.8),
                    box(-2.8, 3.8, 0.05, -0.8, 4, 0.8), box(-2.8, 4, 0.05, -0.8, 7, 0.8),
                    box(-0.5, 3.8, -2.8, 0.5, 4, -0.3), box(-0.5, 4, -2.8, 0.5, 7, -0.3)));
            var state = ground(MovementVector.ZERO, 2);
            for (int n = 1; n <= 160; n++) state = tick(state, world, input(n, 1, false, false));
            assertNotNull(state.surfaces().climb()); assertNull(state.surfaces().path());
            assertFalse(events.contains(MovementEvent.CLIMB_EXIT));
        }
    }
    @Test void blockedTopHoldsTheLadderAndRemovedSupportReleasesIt() {
        var world = ladder(false); world.world = new BoxWorld(List.of(FLOOR, WALL, box(-3, 5, 0.8, 3, 6, 5)));
        var state = ground(MovementVector.ZERO, 2);
        for (int n = 1; n < 100; n++) state = tick(state, world, input(n, 1, false, false));
        assertEquals(MovementState.Mode.LADDER_CLIMB, state.mode()); assertFalse(events.contains(MovementEvent.CLIMB_EXIT));
        world.valid = false; state = tick(state, world, input(100, 0, false, false));
        assertNull(state.surfaces().climb()); assertEquals(MovementState.Mode.AIR, state.mode());
        assertTrue(state.surfaces().climbCooldown() > 0);
    }
    @Test void jumpLetsGoAndClimbCooldownPreventsImmediateRecapture() {
        var world = ladder(false); var state = ground(MovementVector.ZERO, 2);
        for (int n = 1; n <= 25; n++) state = tick(state, world, input(n, 1, false, false));
        assertNotNull(state.surfaces().climb());
        state = tick(state, world, input(26, 0, true, false));
        assertNull(state.surfaces().climb()); assertTrue(events.contains(MovementEvent.CLIMB_RELEASE));
        for (int n = 27; n < 34; n++) state = tick(state, world, input(n, 0, false, false));
        assertNull(state.surfaces().climb()); assertFalse(events.contains(MovementEvent.JUMP));
    }
    @Test void holdingJumpPreventsLadderAndPipeRecaptureUntilReleased() {
        for (boolean pipe : new boolean[]{false, true}) {
            events.clear(); var world = ladder(pipe); var state = ground(MovementVector.ZERO, 2);
            for (int n = 1; n <= 10; n++) state = tick(state, world, input(n, n == 1 ? 1 : 0, false, false));
            assertNotNull(state.surfaces().climb());
            state = tick(state, world, input(11, 1, true, false));
            assertNull(state.surfaces().climb()); assertTrue(events.contains(MovementEvent.CLIMB_RELEASE));
            events.clear();
            for (int n = 12; n <= 41; n++) {
                var heldJump = new MovementInput(n, 1, 0, 0, true, false, false, false, false, 0, 0);
                state = tick(state, world, heldJump);
                assertNull(state.surfaces().climb());
            }
            assertEquals(0, state.surfaces().climbCooldown());
            assertFalse(events.contains(pipe ? MovementEvent.PIPE_GRAB : MovementEvent.LADDER_GRAB));
            state = tick(state, world, input(42, 1, false, false));
            assertNotNull(state.surfaces().climb());
            assertTrue(events.contains(pipe ? MovementEvent.PIPE_GRAB : MovementEvent.LADDER_GRAB));
        }
    }
    @Test void sprintDoublesClimbSpeedAndReleasingItRestoresNormalSpeedMidStep() {
        for (boolean pipe : new boolean[]{false, true}) for (float forward : new float[]{0.5f, -0.5f}) {
            var world = ladder(pipe);
            var state = new MovementState(new MovementVector(0, 2, 0.5), MovementVector.ZERO, MovementState.Mode.AIR, 0, false, 0);
            for (int n = 1; n <= 20; n++) state = tick(state, world, input(n, 0, false, false));
            assertNotNull(state.surfaces().climb()); assertNull(state.surfaces().path());
            state = tick(state, world, input(21, forward, false, false));
            var normalInput = input(22, forward, false, false);
            var normal = tick(state, world, normalInput);
            var sprint = tick(state, world, normalInput.withSprint(true));
            double normalDistance = normal.position().y() - state.position().y();
            assertTrue(normalDistance * forward > 0);
            assertEquals(normalDistance * 2, sprint.position().y() - state.position().y(), 1.0E-9);
            var released = tick(sprint, world, input(23, forward, false, false));
            assertEquals(normalDistance, released.position().y() - sprint.position().y(), 1.0E-9);
        }
    }
    @Test void fastDescentAndControlledDescentReturnToTheFloor() {
        var world = ladder(false); var state = ground(MovementVector.ZERO, 2);
        for (int n = 1; n <= 50; n++) state = tick(state, world, input(n, 1, false, false));
        for (int n = 51; n <= 95; n++) state = tick(state, world, input(n, -1, false, false));
        assertTrue(events.contains(MovementEvent.CLIMB_SLIDE_START));
        assertTrue(events.contains(MovementEvent.CLIMB_RELEASE)); assertEquals(MovementState.Mode.GROUND, state.mode());
        assertEquals(0, state.position().y(), 1.0E-6);
    }
    @Test void approachingFromBehindOrHoldingBackDoesNotGrabAndTopEntryIsSwept() {
        var world = ladder(false);
        var backwards = new MovementInput(1, 1, 0, 0, false, false, false, false, false, 180, 0);
        assertNull(tick(ground(MovementVector.ZERO, 2), world, backwards).surfaces().climb());
        assertNull(tick(ground(MovementVector.ZERO, 2), world, input(1, -1, false, false)).surfaces().climb());
        var state = ground(new MovementVector(0, 4, 1.5), 0);
        state = tick(state, world, backwards);
        assertNotNull(state.surfaces().path()); assertEquals(SurfacePath.Kind.ENTER_TOP, state.surfaces().path().kind());
        for (int n = 2; n <= 40; n++) state = tick(state, world, new MovementInput(n, 0, 0, 0, false, false, false, false, false, 180, 0));
        assertEquals(MovementState.Mode.LADDER_CLIMB, state.mode());
    }
    @Test void ladderAndPipeGrabRequireFacingWithin45DegreesRegardlessOfPitch() {
        for (boolean pipe : new boolean[]{false, true}) {
            var world = ladder(pipe);
            for (float yaw : new float[]{-90, -45.1f, -45, 0, 45, 45.1f, 90}) for (float pitch : new float[]{-80, 0, 80}) {
                var command = new MovementInput(1, 1, 0, 0, false, false, false, false, false, yaw, pitch);
                var state = tick(ground(MovementVector.ZERO, 0), world, command);
                assertEquals(Math.abs(yaw) <= 45, state.surfaces().climb() != null,
                        "Unexpected grab for pipe=" + pipe + ", yaw=" + yaw + ", pitch=" + pitch);
            }
            var passing = ground(MovementVector.ZERO, 0);
            for (int n = 1; n <= 20; n++) {
                passing = tick(passing, world, new MovementInput(n, 1, 0, 0, false, false, false, false, false, 90, 0));
                assertNull(passing.surfaces().climb());
            }
            var attached = tick(ground(MovementVector.ZERO, 0), world, input(1, 1, false, false));
            attached = tick(attached, world, new MovementInput(2, 0, 0, 0, false, false, false, false, false, 90, 0));
            assertNotNull(attached.surfaces().climb(), "Looking aside detached an existing climb");
        }
    }
    @Test void catchingAFallClearsHistoryButExternalKnockbackReleasesClimbing() {
        var world = ladder(false);
        var state = new MovementState(new MovementVector(0, 2, 0.5), new MovementVector(0, -12, 0), MovementState.Mode.AIR, 1, false, 7);
        state = tick(state, world, input(1, 0, false, false));
        assertNotNull(state.surfaces().climb()); assertEquals(0, state.fallDistance());
        state = tick(state.withVelocity(state.velocity().add(new MovementVector(3, 2, 0))), world, input(2, 0, false, false));
        assertNull(state.surfaces().climb()); assertTrue(state.position().x() > 0.01 && state.velocity().x() > 0);
        assertNotEquals(MovementState.Mode.LEDGE_HANG, state.mode(), "A ledge recaptured the climb knockback");
    }
    @Test void untaggedStepsAreImmediateAndKeepNormalWalkingMomentum() {
        var slab = box(-3, 0, 1, 3, 0.5, 5); var world = new World(FLOOR, slab);
        var start = ground(new MovementVector(0, 0, 0.65), 6);
        var state = tick(start, world, input(1, 1, false, false));
        var flat = controller.tick(start, input(1, 1, false, false), MovementTuning.DEFAULT, new World(FLOOR), e -> {});
        assertTrue(events.containsAll(List.of(MovementEvent.STEP_UP_START, MovementEvent.STEP_UP_COMPLETE)));
        assertFalse(events.contains(MovementEvent.JUMP)); assertEquals(0.5, state.position().y(), 1.0E-6);
        assertEquals(MovementState.Mode.GROUND, state.mode()); assertEquals(MovementBody.STANDING, state.body());
        assertEquals(flat.position().z(), state.position().z(), 1.0E-9);
        assertEquals(flat.velocity().horizontalSpeed(), state.velocity().horizontalSpeed(), 1.0E-9);
        assertNull(state.surfaces().path());
    }
    @Test void crawlingStepsOverATrapdoorLipWithoutStandingOrJumping() {
        var trapdoor = box(-3, 0, 1, 3, 0.1875, 5);
        var world = new World(FLOOR, trapdoor, box(-3, 1, -2, 3, 2, 6));
        var state = ground(new MovementVector(0, 0, 0.7), 0);
        for (int n = 1; n <= 20; n++) {
            state = tick(state, world, input(n, 1, false, false));
            assertEquals(MovementBody.LOW, state.body());
            assertTrue(state.grounded());
        }
        assertTrue(state.position().z() > 1.3, "Crawling remained stuck at the trapdoor lip");
        assertEquals(0.1875, state.position().y(), 1.0E-9);
        assertTrue(events.containsAll(List.of(MovementEvent.STEP_UP_START, MovementEvent.STEP_UP_COMPLETE)));
        assertFalse(events.contains(MovementEvent.JUMP));
    }

    @Test void crawlingStepsStillRequireHeadroomAndRespectTheStepHeightLimit() {
        for (int scenario = 0; scenario < 3; scenario++) {
            events.clear();
            double height = scenario == 1 ? 0.7 : 0.1875;
            double roof = scenario == 0 ? 0.75 : 1;
            final double stepHeight = scenario == 2 ? 0 : 0.6;
            var world = new World(FLOOR, box(-3, 0, 1, 3, height, 5), box(-3, roof, -2, 3, 2, 6)) {
                @Override public double maxStepHeight() { return stepHeight; }
            };
            var state = ground(new MovementVector(0, 0, 0.7), 0);
            for (int n = 1; n <= 20; n++) state = tick(state, world, input(n, 1, false, false));
            assertEquals(MovementBody.LOW, state.body());
            assertEquals(0.7, state.position().z(), 1.0E-9);
            assertEquals(0, state.position().y(), 1.0E-9);
            assertFalse(events.contains(MovementEvent.STEP_UP_START));
        }
    }

    @Test void tallBlockedAndRisingStepCandidatesReject() {
        var slab = box(-3, 0, 1, 3, 0.5, 5); var world = new World(FLOOR, slab);
        var feet = new MovementVector(0, 0, 0.68);
        world.world = new BoxWorld(List.of(FLOOR, slab, box(-3, 2, 0, 3, 3, 6)));
        assertEquals(0, tick(ground(feet, 3), world, input(1, 1, false, false)).position().y(), 1.0E-9);
        var tall = box(-3, 0, 1, 3, 1, 5); world = new World(FLOOR, tall); world.steps = List.of(tall);
        assertEquals(0, tick(ground(feet, 3), world, input(1, 1, false, false)).position().y(), 1.0E-9);
        world = new World(FLOOR, slab); world.steps = List.of(slab);
        events.clear();
        var airborne = new MovementState(feet, new MovementVector(0, 3, 3), MovementState.Mode.AIR, 0, false, 0);
        tick(airborne, world, input(1, 0, false, false));
        assertFalse(events.contains(MovementEvent.STEP_UP_START));
        var disabledWalk = new MovementTuning(0, 6.3, 16.4, 61.44, 1.536, 5);
        var stopped = controller.tick(ground(feet, 0), input(1, 1, false, false), disabledWalk, world, e -> {});
        assertEquals(0, stopped.position().y(), 1.0E-9, "Step-up bypassed zero effective walking speed");
    }

    @Test void taggedStepDoesNotStartUntilNormalMovementHitsTheRiser() {
        var slab = box(-3, 0, 1, 3, 0.5, 5); var world = new World(FLOOR, slab); world.steps = List.of(slab);
        var state = tick(ground(new MovementVector(0, 0, 0.2), 6), world, input(1, 1, false, false));
        assertEquals(0, state.position().y(), 1.0E-9); assertNull(state.surfaces().path());
        assertFalse(events.contains(MovementEvent.STEP_UP_START));
    }

    @Test void heldMovementCanStepAfterTheRiserClipsApproachVelocityToZero() {
        var slab = box(-3, 0, 1, 3, 0.5, 5); var world = new World(FLOOR, slab); world.steps = List.of(slab);
        var state = ground(new MovementVector(0, 0, 0.7), 0);
        assertEquals(0, tick(state, world, input(1, 0, false, false)).position().y(), 1.0E-9);
        for (int n = 1; n < 30 && !events.contains(MovementEvent.STEP_UP_COMPLETE); n++) state = tick(state, world, input(n, 1, false, false));
        assertTrue(events.contains(MovementEvent.STEP_UP_COMPLETE));
        assertEquals(0.5, state.position().y(), 1.0E-6); assertFalse(events.contains(MovementEvent.JUMP));
    }
    @Test void steppedMovementRespondsNormallyToSupportRemovalImpulsesAndDisplacement() {
        var slab = box(-3, 0, 1, 3, 0.5, 5); var world = new World(FLOOR, slab); world.steps = List.of(slab);
        var baseline = tick(ground(new MovementVector(0, 0, 0.65), 6), world, input(1, 1, false, false));
        assertEquals(0.5, baseline.position().y(), 1.0E-9); assertNull(baseline.surfaces().path());
        world.world = new BoxWorld(List.of(FLOOR)); world.steps = List.of();
        var removed = tick(baseline, world, input(2, 1, false, false));
        assertEquals(MovementState.Mode.AIR, removed.mode()); assertTrue(removed.position().y() < baseline.position().y());
        assertNull(removed.surfaces().path()); assertFalse(events.contains(MovementEvent.STEP_UP_ABORT));
        world.world = new BoxWorld(List.of(FLOOR, slab)); world.steps = List.of(slab);
        var pushed = tick(baseline.withVelocity(baseline.velocity().add(new MovementVector(3, 2, 0))), world, input(2, 0, false, false));
        assertNull(pushed.surfaces().path()); assertTrue(pushed.position().x() > baseline.position().x());
        world.offset = new MovementVector(0.5, 0, 0);
        var displaced = tick(baseline, world, input(2, 0, false, false));
        assertNull(displaced.surfaces().path()); assertTrue(displaced.position().x() >= baseline.position().x() + 0.5);
        assertTrue(displaced.velocity().y() <= 0, "Stepping retained an upward launch velocity");
    }
    @Test void steepTaggedSupportSlidesWithLimitedSpeedAndRecoversOnFlatGround() {
        var world = new World(FLOOR); world.steep = true;
        var lookingSideways = new MovementInput(1, 0, 0, 0, false, false, false, false, false, 90, 0);
        var state = tick(ground(MovementVector.ZERO, 2), world, lookingSideways);
        assertEquals(MovementState.Mode.STEEP_SLIDE, state.mode()); assertEquals(MovementBody.LOW, state.body());
        assertTrue(state.surfaces().slopeYaw() > 75 && state.surfaces().slopeYaw() < 85, "Downhill facing did not advance across all six substeps");
        for (int n = 2; n < 50; n++) {
            state = tick(state, world, input(n, 0, false, false)); assertTrue(state.velocity().length() <= 10 + 1.0E-9);
        }
        assertTrue(events.containsAll(List.of(MovementEvent.STEEP_SLIDE_START, MovementEvent.STEEP_SLIDE_END)));
        assertEquals(MovementState.Mode.GROUND, state.mode()); assertEquals(MovementBody.STANDING, state.body());
        assertTrue(state.position().z() > 2);
    }
    @Test void ordinaryGroundDoesNotSlideAndAnImpulseInterruptsSteepMotion() {
        var world = new World(FLOOR);
        assertEquals(MovementState.Mode.GROUND, tick(ground(MovementVector.ZERO, 2), world, input(1, 0, false, false)).mode());
        world.steep = true;
        var state = tick(ground(MovementVector.ZERO, 2), world, input(2, 0, false, false));
        state = tick(state.withVelocity(state.velocity().add(new MovementVector(0, 3, 0))), world, input(3, 0, false, false));
        assertNotEquals(MovementState.Mode.STEEP_SLIDE, state.mode()); assertTrue(state.surfaces().slopeCooldown() > 0);
    }

    @Test void steepSlideJumpUsesSupportedContactAndReplaysWithoutReattaching() {
        var world = new World(FLOOR); world.steep = true;
        var start = tick(ground(MovementVector.ZERO, 2), world, input(1, 0, false, false));
        assertEquals(MovementState.Mode.STEEP_SLIDE, start.mode());
        events.clear();
        var command = input(2, 0, true, false);
        var state = tick(start, world, command);
        var replay = controller.tick(start, command, MovementTuning.DEFAULT, world, event -> {});
        assertEquals(state, replay);
        assertEquals(MovementState.Mode.AIR, state.mode()); assertFalse(state.grounded());
        assertEquals(MovementTuning.DEFAULT.jumpSpeed() - MovementTuning.DEFAULT.gravity() / 20, state.velocity().y(), 1.0E-9);
        assertTrue(state.velocity().horizontalSpeed() >= start.velocity().horizontalSpeed());
        assertEquals(LandingState.Source.JUMP, state.landing().source());
        assertTrue(state.surfaces().slopeCooldown() > 0);
        assertEquals(List.of(MovementEvent.STEEP_SLIDE_END, MovementEvent.JUMP), events);

        events.clear();
        var unsupported = new MovementState(start.position().add(new MovementVector(0, 1, 0)), start.velocity(), start.mode(),
                start.modeTime(), false, 0, start.locomotion(), start.blocks(), start.body(), start.slideYaw(), start.traversal(),
                start.wall(), start.landing(), start.advanced(), start.surfaces());
        tick(unsupported, world, command);
        assertFalse(events.contains(MovementEvent.JUMP), "Steep slide jumped without support");

        events.clear();
        var launched = tick(start.withVelocity(start.velocity().add(new MovementVector(0, 3, 0))), world, command);
        assertEquals(MovementState.Mode.AIR, launched.mode());
        assertFalse(events.contains(MovementEvent.JUMP), "Upward knockback received another slide jump");
    }
    @Test void softLandingUsesReferenceRecoveryAndIgnoresBufferedJump() {
        var world = new World(FLOOR); world.soft = true;
        var state = new MovementState(new MovementVector(0, 6, 0), new MovementVector(0, -12, 2), MovementState.Mode.AIR, 1, false, 0);
        int n = 1;
        while (state.mode() != MovementState.Mode.SOFT_LANDING && n < 30) state = tick(state, world, input(n++, 0, false, false));
        assertTrue(events.contains(MovementEvent.SOFT_LAND)); assertFalse(events.contains(MovementEvent.HARD_LAND));
        state = tick(state, world, input(n++, 1, true, false));
        for (int i = 0; i < 20; i++) state = tick(state, world, input(n++, 0, false, false));
        assertEquals(MovementState.Mode.SOFT_LANDING, state.mode()); assertEquals(0, state.velocity().horizontalSpeed());
        for (int i = 0; i < 15; i++) state = tick(state, world, input(n++, 0, false, false));
        assertEquals(MovementState.Mode.GROUND, state.mode()); assertFalse(events.contains(MovementEvent.JUMP));
    }
    @Test void midClimbAndWalkingAcrossTaggedStepsReplayExactly() {
        for (boolean climbing : new boolean[]{false, true}) {
            var slab = box(-3, 0, 1, 3, 0.5, 5); var world = climbing ? ladder(false) : new World(FLOOR, slab); world.steps = List.of(slab);
            var state = tick(ground(new MovementVector(0, 0, 0.2), 6), world, input(1, 1, false, false));
            var replay = state;
            for (int n = 2; n < 35; n++) {
                var command = input(n, 1, false, false);
                state = tick(state, world, command);
                replay = controller.tick(replay, command, MovementTuning.DEFAULT, world, e -> {});
                assertEquals(state, replay);
            }
        }
    }
}
