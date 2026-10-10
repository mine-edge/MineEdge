package dev.mineedge.movement.landing;

import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementFeatures;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.BoxWorld;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionMover;
import dev.mineedge.movement.collision.CollisionWorld;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.wall.WallState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandingTest {
    private final MovementController controller = new MovementController();
    private final List<MovementEvent> events = new ArrayList<>();
    private static CollisionBox box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new CollisionBox(new MovementVector(x0, y0, z0), new MovementVector(x1, y1, z1));
    }
    private static final CollisionBox FLOOR = box(-50, -1, -50, 50, 0, 50);
    private static final class World implements MovementController.MotionResolver {
        BoxWorld world;
        MovementBody body = MovementBody.STANDING;
        World(CollisionBox... shapes) { world = new BoxWorld(List.of(shapes)); }
        @Override public void body(MovementBody body) { this.body = body; }
        @Override public boolean canOccupy(MovementVector feet, MovementBody body) { return !world.overlaps(bounds(feet, body)); }
        @Override public CollisionWorld geometry(CollisionBox region) { return world; }
        @Override public MovementController.Motion resolve(MovementVector feet, MovementVector velocity, double dt) {
            var move = CollisionMover.move(world, bounds(feet, body), velocity.scale(dt));
            return new MovementController.Motion(feet.add(move.recovery()).add(move.displacement()), move.clip(velocity), move.grounded());
        }
    }
    private static MovementInput input(boolean press, boolean held, boolean jump) {
        return new MovementInput(1, 1, 0, 0, jump, jump, held, press, true, 0, 0);
    }
    private MovementState tick(MovementState state, World world, MovementInput input) {
        var next = controller.tick(state, input, MovementTuning.DEFAULT, world, events::add);
        assertFalse(world.world.overlaps(world.bounds(next.position(), next.body())), "Landing body penetrated geometry");
        return next;
    }
    private static MovementState imminent(double fall, LandingState.Source source) {
        var feet = new MovementVector(0, 0.1, 0);
        return new MovementState(feet, new MovementVector(0, -12, 6), MovementState.Mode.AIR, 1, false, (float) (fall - 0.1),
                LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, WallState.EMPTY,
                new LandingState(fall, fall - 0.1, 0, source, 6, 60, 60, true));
    }
    private MovementState drop(double... pressHeights) {
        World world = new World(FLOOR);
        var state = new MovementState(new MovementVector(0, 6, 0), new MovementVector(0, 0, 4), MovementState.Mode.AIR, 0, false, 0);
        boolean[] done = new boolean[pressHeights.length];
        for (int i = 0; i < 60; i++) {
            boolean press = false;
            for (int p = 0; p < done.length; p++) {
                if (!done[p] && !state.grounded() && state.position().y() < pressHeights[p]) { done[p] = true; press = true; }
            }
            state = tick(state, world, input(press, press, false));
        }
        return state;
    }

    @Test void latePressRollsEarlyPressAndMashingHardLand() {
        // faith_move/tests.rs::roll_needs_a_late_crouch_press.
        drop(1.2);
        assertTrue(events.contains(MovementEvent.ROLL_START));
        assertFalse(events.contains(MovementEvent.HARD_LAND));
        events.clear();
        drop(4);
        assertFalse(events.contains(MovementEvent.ROLL_START));
        assertTrue(events.contains(MovementEvent.HARD_LAND));
        events.clear();
        drop(4, 1.2);
        assertFalse(events.contains(MovementEvent.ROLL_START));
        assertTrue(events.contains(MovementEvent.HARD_LAND));
    }

    @Test void heldCrouchDoesNotArmRollAndMinimumFallIsRequired() {
        var state = tick(imminent(6, LandingState.Source.OTHER), new World(FLOOR), input(false, true, false));
        assertEquals(MovementState.Mode.HARD_LANDING, state.mode());
        events.clear();
        state = tick(imminent(1.9, LandingState.Source.OTHER), new World(FLOOR), input(true, true, false));
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertFalse(events.contains(MovementEvent.ROLL_START));
    }

    @Test void airTurnStillAllowsTimedRollsAndNormalHardLandings() {
        for (boolean roll : new boolean[]{false, true}) {
            events.clear();
            var command = new MovementInput(1, 1, 0, 0, false, false, roll, roll, true, 0, 0, true);
            var state = tick(imminent(6, LandingState.Source.JUMP), new World(FLOOR), command);
            assertTrue(events.contains(MovementEvent.TURN));
            assertEquals(roll ? MovementState.Mode.ROLL : MovementState.Mode.HARD_LANDING, state.mode());
            assertTrue(events.contains(roll ? MovementEvent.ROLL_START : MovementEvent.HARD_LAND));
            assertEquals(6, state.landing().distance(), 1.0E-9);
        }
    }

    @Test void rollWindowAndRetriggerBoundariesAreSimulationTime() {
        var feet = MovementVector.ZERO;
        var state = new LandingState(6, 6, 0, LandingState.Source.OTHER, 0, 0.199, 0.6, true);
        assertTrue(LandingController.canRoll(state, feet, 12, true));
        state = new LandingState(6, 6, 0, LandingState.Source.OTHER, 0, 0.2, 0.6, true);
        assertFalse(LandingController.canRoll(state, feet, 12, true));
        assertEquals(MovementTuning.SUBSTEP_SECONDS, state.advance(MovementTuning.SUBSTEP_SECONDS, true).triggerAge());
        var tooSoon = new LandingState(6, 6, 0, LandingState.Source.OTHER, 0, 0.4, 0.599, true);
        assertTrue(tooSoon.advance(MovementTuning.SUBSTEP_SECONDS, true).triggerAge() > 0.4);
    }

    @Test void rollIgnoresJumpAndSteeringMaintainsMomentumForHalfSecond() {
        World world = new World(FLOOR);
        var state = tick(imminent(6, LandingState.Source.OTHER), world, input(true, true, false));
        double speed = state.velocity().horizontalSpeed();
        double z = state.position().z();
        for (int i = 0; i < 8; i++) {
            state = tick(state, world, new MovementInput(1, -1, 0, 0, true, true, false, false, false, 90, 0));
            assertEquals(MovementState.Mode.ROLL, state.mode());
            assertEquals(speed, state.velocity().horizontalSpeed(), 1.0E-9);
            assertEquals(MovementBody.LOW, state.body());
        }
        assertEquals(speed * 0.4, state.position().z() - z, 1.0E-6);
        for (int i = 0; i < 3; i++) state = tick(state, world, input(false, false, false));
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertEquals(MovementBody.STANDING, state.body());
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.ROLL_START).count());
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.ROLL_END).count());
        assertFalse(events.contains(MovementEvent.JUMP));
    }

    @Test void rollStopsAtWallAndCannotStandUnderLowGap() {
        World wall = new World(FLOOR, box(-3, 0, 1, 3, 4, 2));
        var state = tick(imminent(6, LandingState.Source.OTHER), wall, input(true, true, false));
        for (int i = 0; i < 12; i++) state = tick(state, wall, input(false, false, false));
        assertTrue(state.position().z() <= 0.7 + 1.0E-6);
        World gap = new World(FLOOR, box(-3, 1, 1, 3, 3, 10));
        state = tick(imminent(6, LandingState.Source.OTHER), gap, input(true, true, false));
        for (int i = 0; i < 12; i++) state = tick(state, gap, input(false, false, false));
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertEquals(MovementBody.LOW, state.body());
    }

    @Test void rollLeavingEdgeReturnsToAirWithoutModeLock() {
        World world = new World(box(-3, -1, -3, 3, 0, 1));
        var state = tick(imminent(6, LandingState.Source.OTHER), world, input(true, true, false));
        for (int i = 0; i < 8; i++) state = tick(state, world, input(false, false, false));
        assertEquals(MovementState.Mode.AIR, state.mode());
        assertTrue(state.velocity().y() < 0);
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.ROLL_END).count());
    }

    @Test void hardLandingWalksAtHalfSpeedButBlocksSprintAndJumpUntilRecoveryEnds() {
        World world = new World(FLOOR);
        var state = tick(imminent(6, LandingState.Source.OTHER), world, input(false, false, false));
        assertEquals(MovementState.Mode.HARD_LANDING, state.mode());
        double start = state.position().z();
        var walking = new MovementState(MovementVector.ZERO, state.velocity().scale(2), MovementState.Mode.GROUND, 0, true, 0, state.locomotion());
        for (int i = 0; i < 34; i++) {
            state = tick(state, world, input(false, false, true));
            walking = controller.tick(walking, input(false, false, false).withSprint(false), MovementTuning.DEFAULT, world, e -> {});
            assertEquals(MovementState.Mode.HARD_LANDING, state.mode());
            assertTrue(state.velocity().horizontalSpeed() > 0);
            assertEquals(walking.velocity().horizontalSpeed() / 2, state.velocity().horizontalSpeed(), 1.0E-9);
            assertEquals(walking.position().z() / 2, state.position().z() - start, 1.0E-9);
            assertTrue(state.grounded());
            assertEquals(0, state.locomotion().jumpBuffer());
        }
        for (int i = 0; i < 3; i++) state = tick(state, world, input(false, false, false));
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertTrue(events.contains(MovementEvent.LANDING_RECOVERED));
        assertFalse(events.contains(MovementEvent.JUMP));
    }

    @Test void hardLandingCanSteerStopAndWalkOffAnEdge() {
        World world = new World(FLOOR);
        var state = tick(imminent(6, LandingState.Source.OTHER), world, input(false, false, false));
        for (int i = 0; i < 8; i++) state = tick(state, world, input(false, false, false));
        double z = state.position().z();
        for (int i = 0; i < 8; i++) state = tick(state, world, new MovementInput(1, -1, 0, 0, true, true, false, false, true, 0, 0));
        assertTrue(state.position().z() < z);
        assertEquals(MovementState.Mode.HARD_LANDING, state.mode());
        for (int i = 0; i < 16; i++) state = tick(state, world, MovementInput.idle(1, 0, 0));
        assertEquals(0, state.velocity().horizontalSpeed());
        World edge = new World(box(-3, -1, -3, 3, 0, 1));
        state = tick(imminent(6, LandingState.Source.OTHER), edge, input(false, false, false));
        for (int i = 0; i < 20; i++) state = tick(state, edge, input(false, false, false));
        assertEquals(MovementState.Mode.AIR, state.mode());
        assertTrue(state.velocity().y() < 0);
        assertTrue(events.contains(MovementEvent.LANDING_RECOVERED));
    }

    @Test void disablingHardLandingSkipsAndCancelsRecoveryWithoutDisablingRolls() {
        var tuning = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(true, true, true, true, true, true, false));
        World world = new World(FLOOR);
        var state = controller.tick(imminent(6, LandingState.Source.OTHER), input(false, false, false), tuning, world, events::add);
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertFalse(events.contains(MovementEvent.HARD_LAND));
        state = controller.tick(imminent(6, LandingState.Source.OTHER), input(true, true, false), tuning, world, events::add);
        assertEquals(MovementState.Mode.ROLL, state.mode());
        state = tick(imminent(6, LandingState.Source.OTHER), world, input(false, false, false));
        state = controller.tick(state, input(false, false, false), tuning, world, events::add);
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertTrue(events.contains(MovementEvent.LANDING_RECOVERED));
    }

    @Test void normalJumpLandingRemovesBoostButWallJumpAndDropsKeepMomentum() {
        World world = new World(FLOOR);
        var motion = new MovementController.Motion(MovementVector.ZERO, new MovementVector(0, 0, 7), true);
        for (var source : new LandingState.Source[]{LandingState.Source.JUMP, LandingState.Source.WALL_JUMP, LandingState.Source.OTHER}) {
            var history = new LandingState(1, 1, 0, source, 6, 60, 60, true);
            var landed = LandingController.land(history, motion, 6, MovementBody.STANDING, input(false, false, false), MovementTuning.DEFAULT, world, events::add);
            assertEquals(source == LandingState.Source.JUMP ? 5.2984875 : 7, landed.velocity().horizontalSpeed(), 1.0E-9);
        }
    }

    @Test void apexMeasuresNetDropAndNativeResetDoesNotFabricateHardLanding() {
        var state = LandingState.capture(new MovementVector(0, 6, 0), false, 0);
        state = state.observe(new MovementVector(0, 7, 0), false, false);
        state = state.observe(new MovementVector(0, 5, 0), false, false);
        assertEquals(7, state.apex());
        assertEquals(2, state.distance());
        state = state.observe(new MovementVector(0, 5, 0), false, true);
        assertEquals(0, state.distance());
        var result = LandingController.land(state, new MovementController.Motion(new MovementVector(0, 4, 0), MovementVector.ZERO, true),
                3, MovementBody.STANDING, input(false, false, false), MovementTuning.DEFAULT, new World(FLOOR), events::add);
        assertEquals(1, result.state().distance());
        assertEquals(3, result.state().impact());
        assertEquals(MovementState.Mode.GROUND, result.mode());
    }

    @Test void authoritativeRollAndRecoveryBaselinesReplayExactly() {
        for (boolean roll : new boolean[]{false, true}) {
            World world = new World(FLOOR);
            var state = tick(imminent(6, LandingState.Source.OTHER), world, input(roll, roll, false));
            var baseline = state;
            for (int i = 0; i < 45; i++) state = tick(state, world, input(false, false, false));
            for (int i = 0; i < 45; i++) baseline = controller.tick(baseline, input(false, false, false), MovementTuning.DEFAULT, world, e -> {});
            assertEquals(state, baseline);
        }
    }
}
