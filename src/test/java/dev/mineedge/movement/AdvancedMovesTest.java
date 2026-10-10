package dev.mineedge.movement;

import dev.mineedge.movement.collision.*;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.wall.WallState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedMovesTest {
    private final MovementController controller = new MovementController();
    private final List<MovementEvent> events = new ArrayList<>();
    private static CollisionBox box(double x, double y, double z, double xx, double yy, double zz) {
        return new CollisionBox(new MovementVector(x, y, z), new MovementVector(xx, yy, zz));
    }
    private static final CollisionBox FLOOR = box(-30, -1, -30, 30, 0, 30);
    private static class World implements MovementController.MotionResolver {
        BoxWorld world;
        MovementBody body = MovementBody.STANDING;
        float yaw;
        World(CollisionBox... boxes) { world = new BoxWorld(List.of(boxes)); }
        @Override public void body(MovementBody value) { body = value; }
        @Override public void facing(float value) { yaw = value; }
        @Override public CollisionWorld geometry(CollisionBox region) { return world; }
        @Override public boolean canOccupy(MovementVector p, MovementBody b) { return !world.overlaps(bounds(p, b)); }
        @Override public MovementController.Motion resolve(MovementVector p, MovementVector v, double dt) {
            var result = CollisionMover.move(world, bounds(p, body), v.scale(dt));
            return new MovementController.Motion(p.add(result.recovery()).add(result.displacement()), result.clip(v), result.grounded());
        }
    }
    private static MovementInput input(long sequence, float forward, float strafe, boolean jump, boolean crouch, boolean turn, float yaw) {
        return new MovementInput(sequence, forward, strafe, strafe, jump, jump, crouch, crouch, true, yaw, 0, turn);
    }
    private static MovementState ground() { return new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 6), MovementState.Mode.GROUND, 0, true, 0); }
    private MovementState tick(MovementState state, World world, MovementInput input) {
        var next = controller.tick(state, input, MovementTuning.DEFAULT, world, events::add);
        assertFalse(world.world.overlaps(world.bounds(next.position(), next.body())), next.toString());
        return next;
    }
    private static MovementState advanced(MovementState s, AdvancedState a) {
        return new MovementState(s.position(), s.velocity(), s.mode(), s.modeTime(), s.grounded(), s.fallDistance(), s.locomotion(), s.blocks(), s.body(), s.slideYaw(), s.traversal(), s.wall(), s.landing(), a);
    }
    private static MovementState hanging(CollisionBox ledge, double x) {
        var feet = new MovementVector(x, ledge.max().y() - 1.828, ledge.min().z() - 0.3);
        return new MovementState(feet, MovementVector.ZERO, MovementState.Mode.LEDGE_HANG, 1, false, 0, LocomotionState.EMPTY,
                BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY,
                WallState.EMPTY.contact(new MovementVector(0, 0, -1), ledge, feet, MovementVector.ZERO, false, 0), LandingState.capture(feet, false, 0));
    }

    @Test void diagonalSprintJumpUsesNormalLaunchAndRetainsAirControl() {
        var world = new World(FLOOR);
        // Leave room below the sprint cap for ordinary air steering after the jump boost.
        var s = tick(ground().withVelocity(new MovementVector(0, 0, 5)), world, input(1, 1, 1, true, false, false, 0));
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.JUMP).count());
        assertEquals(LandingState.Source.JUMP, s.landing().source());
        assertEquals(MovementTuning.DEFAULT.jumpSpeed() - MovementTuning.DEFAULT.gravity() / 20, s.velocity().y(), 1.0E-9);
        assertTrue(s.velocity().x() > 0 && s.velocity().x() < 1, "Strafe injected a sideways launch");
        var launch = s.velocity();
        s = tick(s, world, input(2, -1, -1, false, false, false, 0));
        assertTrue(s.velocity().x() < launch.x(), "Air steering was locked");
    }
    @Test void sprintStrafingKeepsOrdinaryWalkingAndJumping() {
        var world = new World(FLOOR);
        for (int sign : new int[]{-1, 1}) {
            var s = tick(ground().withVelocity(MovementVector.ZERO), world, input(1, 0, sign, false, false, false, 0));
            assertTrue(s.grounded()); assertEquals(0, s.velocity().y());
            assertTrue(s.position().x() * sign > 0);
            s = tick(s, world, input(2, 0, sign, true, false, false, 0));
            assertEquals(LandingState.Source.JUMP, s.landing().source()); assertTrue(s.velocity().y() > 5);
        }
    }
    @Test void coilNeedsFreshAirPressAfterJumpAndKeepsHeadHeight() {
        var world = new World(FLOOR);
        var s = tick(ground(), world, input(1, 1, 0, true, false, false, 0));
        var baseline = tick(s, world, input(2, 1, 0, false, false, false, 0));
        s = tick(s, world, input(2, 1, 0, false, true, false, 0));
        assertTrue(s.advanced().coiled()); assertEquals(MovementBody.CROUCHING, s.body());
        assertEquals(baseline.position().y() + 0.3, s.position().y(), 1.0E-9);
        s = tick(s, world, input(3, 1, 0, false, false, false, 0)); assertFalse(s.advanced().coiled());
        var drop = new MovementState(new MovementVector(0, 3, 0), new MovementVector(0, -1, 4), MovementState.Mode.AIR, 0, false, 0);
        assertFalse(tick(drop, world, input(4, 1, 0, false, true, false, 0)).advanced().coiled());
    }
    @Test void coilCannotExpandThroughFloorOrCeiling() {
        var world = new World(FLOOR, box(-2, 1.65, -2, 2, 3, 2));
        var p = new MovementVector(0, 0.15, 0);
        var s = new MovementState(p, new MovementVector(0, -0.1, 1), MovementState.Mode.AIR, 0, false, 0,
                LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.CROUCHING, 0, TraversalState.EMPTY, WallState.EMPTY.jump(p), LandingState.capture(p, false, 0));
        s = advanced(s, new AdvancedState(true, false, null, null, null));
        s = tick(s, world, input(1, 0, 0, false, false, false, 0));
        assertTrue(s.advanced().coiled());
    }
    @Test void turnsGoRightAreBoundedAndCannotRetriggerDuringCurve() {
        var world = new World(FLOOR); var s = ground().withVelocity(MovementVector.ZERO);
        for (int i = 1; i <= 4; i++) s = tick(s, world, input(i, 0, 0, false, false, true, world.yaw));
        assertEquals(180, Math.abs(world.yaw), 0.001);
        assertEquals(1, events.stream().filter(e -> e == MovementEvent.TURN).count());
    }
    @Test void airTurnLandsNormallyAndAllowsImmediateWalkingAndJumping() {
        var world = new World(FLOOR);
        var s = tick(ground(), world, input(1, 1, 0, true, false, false, 0));
        s = tick(s, world, input(2, 0, 0, false, false, true, 0));
        assertEquals(LandingState.Source.JUMP, s.landing().source());
        int sequence = 3;
        while (!s.grounded() && sequence < 35)
            s = tick(s, world, input(sequence++, 0, 0, false, false, false, world.yaw));
        assertEquals(MovementState.Mode.GROUND, s.mode()); assertEquals(MovementBody.STANDING, s.body());
        assertEquals(180, Math.abs(world.yaw), 0.001);
        assertTrue(events.containsAll(List.of(MovementEvent.TURN, MovementEvent.LAND)));
        assertFalse(events.contains(MovementEvent.LANDING_RECOVERED));
        var idle = controller.tick(s, input(sequence, 0, 0, false, false, false, world.yaw),
                MovementTuning.DEFAULT, new World(FLOOR), e -> {});
        s = tick(s, world, input(sequence++, 1, 0, false, false, false, world.yaw));
        assertTrue(s.position().subtract(idle.position()).dot(new MovementVector(0, 0, -1)) > 0,
                "Walking input was blocked after landing");
        s = tick(s, world, input(sequence, 1, 0, true, false, false, world.yaw));
        assertEquals(MovementState.Mode.AIR, s.mode()); assertTrue(s.velocity().y() > 0);
        assertEquals(2, events.stream().filter(e -> e == MovementEvent.JUMP).count());
    }
    @Test void shimmyFinishesStepAfterReleaseAndStopsAtObstructions() {
        var ledge = box(-4, 0, 1, 4, 3, 5); var world = new World(ledge);
        var s = tick(hanging(ledge, 0), world, input(1, 0, 1, false, false, false, 0));
        assertNotNull(s.advanced().ledge());
        for (int i = 2; i <= 24; i++) s = tick(s, world, input(i, 0, 0, false, false, false, 0));
        assertEquals(0.6, s.position().x(), 0.001); assertEquals(MovementState.Mode.LEDGE_HANG, s.mode());
        world.world = new BoxWorld(List.of(ledge, box(0.95, 0, 0, 2, 5, 2)));
        for (int i = 25; i < 55; i++) s = tick(s, world, input(i, 0, 1, false, false, false, 0));
        assertTrue(s.position().x() <= 0.65 + 1.0E-5);
    }
    @Test void shimmyReleasesWhenItsTargetGripIsCoveredWithoutBlockingTheBody() {
        var ledge = box(-4, 0, 1, 4, 3, 5); var world = new World(ledge);
        var s = tick(hanging(ledge, 0), world, input(1, 0, 1, false, false, false, 0));
        assertNotNull(s.advanced().ledge());
        world.world = new BoxWorld(List.of(ledge, box(0.4, 3, 0, 1, 3.1875, 1)));
        assertTrue(world.canOccupy(s.advanced().ledge().to(), s.body()));
        events.clear();
        s = tick(s, world, input(2, 0, 0, false, false, false, 0));
        assertEquals(MovementState.Mode.AIR, s.mode());
        assertNull(s.advanced().ledge());
        assertTrue(events.contains(MovementEvent.WALL_DETACH));
    }
    @Test void shimmyFollowsSmallLedgeHeightChangesSmoothlyInBothDirectionsAndReplays() {
        for (int sign : new int[]{-1, 1}) for (double rise : new double[]{-0.25, -0.1875, 0.0625, 0.1875, 0.25}) {
            events.clear();
            var ledge = box(-1, 2.8125, 1, 1, 3, 2);
            var next = sign > 0 ? box(1, 3 + rise - 0.1875, 1, 3, 3 + rise, 2)
                    : box(-3, 3 + rise - 0.1875, 1, -1, 3 + rise, 2);
            var world = new World(ledge, next);
            var start = hanging(ledge, sign * 0.7);
            var state = start; var replay = start;
            for (int n = 1; n <= 24; n++) {
                var command = input(n, 0, n == 1 ? sign : 0, false, false, false, 0);
                var previous = state;
                state = tick(state, world, command);
                replay = controller.tick(replay, command, MovementTuning.DEFAULT, world, e -> {});
                assertEquals(state, replay);
                assertEquals(MovementState.Mode.LEDGE_HANG, state.mode());
                double dy = state.position().y() - previous.position().y();
                assertTrue(dy * rise >= -1.0E-9, "Height transition reversed");
                assertTrue(Math.abs(dy) < 0.03, "Height transition snapped");
            }
            assertEquals(sign * 1.3, state.position().x(), 1.0E-9);
            assertEquals(start.position().y() + rise, state.position().y(), 1.0E-9);
            assertEquals(next, state.wall().support());
            assertEquals(0, state.fallDistance());
            var hands = HandTargets.sample(state);
            assertEquals(next.max().y() + 0.02, hands.left().y(), 1.0E-9);
            assertEquals(next.max().y() + 0.02, hands.right().y(), 1.0E-9);
            for (int n = 25; n <= 48; n++) state = tick(state, world,
                    input(n, 0, n == 25 ? -sign : 0, false, false, false, 0));
            assertEquals(start.position(), state.position());
            assertEquals(ledge, state.wall().support());
            assertFalse(events.contains(MovementEvent.WALL_DETACH));
            assertFalse(events.contains(MovementEvent.GRAB_TRANSFER));
        }
    }
    @Test void heightChangingShimmyStillRequiresNearbyReachableGripsAndBodyClearance() {
        var ledge = box(-1, 2.8125, 1, 1, 3, 2);
        for (int scenario = 0; scenario < 4; scenario++) {
            events.clear();
            double rise = scenario == 0 ? 0.3125 : 0.1875;
            var next = box(scenario == 1 ? 1.25 : 1, 3 + rise - 0.1875, 1, 3, 3 + rise, 2);
            var world = switch (scenario) {
                case 2 -> new World(ledge, next, box(1, 3.1875, 0.95, 3, 3.375, 1));
                case 3 -> new World(ledge, next, box(-2, 3.12, 0, 4, 4, 0.6));
                default -> new World(ledge, next);
            };
            var state = hanging(ledge, 0.7);
            for (int n = 1; n <= 60; n++) {
                state = tick(state, world, input(n, 0, 1, false, false, false, world.yaw));
                if (state.advanced().ledge() != null) assertEquals(ledge, state.advanced().ledge().support(),
                        "Targeted invalid ledge in scenario " + scenario);
            }
            assertEquals(MovementState.Mode.LEDGE_HANG, state.mode(), "Lost grip in scenario " + scenario);
            assertEquals(3 - 1.828, state.position().y(), 1.0E-9);
            assertEquals(ledge, state.wall().support());
        }
    }
    @Test void shimmyCornerChangesContactAndFacing() {
        var ledge = box(-2, 0, 1, 0.6, 3, 5); var world = new World(ledge); var s = hanging(ledge, 0.6);
        for (int i = 1; i < 31; i++) s = tick(s, world, input(i, 0, i == 1 ? 1 : 0, false, false, false, world.yaw));
        assertTrue(events.contains(MovementEvent.SHIMMY_CORNER));
        assertEquals(0, s.wall().normal().subtract(new MovementVector(1, 0, 0)).length(), 1.0E-9); assertEquals(90, world.yaw, 0.001);
    }
    @Test void innerCornersChangeContactAndFacingInBothDirectionsAndAllowContinuedClimbing() {
        for (int sign : new int[]{-1, 1}) {
            events.clear();
            var ledge = box(-4, 0, 1, 4, 3, 5);
            var adjoining = sign > 0 ? box(1, 0, -3, 4, 3, 5) : box(-4, 0, -3, -1, 3, 5);
            var world = new World(ledge, adjoining);
            var s = tick(hanging(ledge, sign * 0.65), world, input(1, 0, sign, false, false, false, 0));
            assertNotNull(s.advanced().ledge());
            assertEquals(AdvancedState.LedgeKind.CORNER, s.advanced().ledge().kind());
            for (int i = 2; i <= 31; i++) s = tick(s, world, input(i, 0, 0, false, false, false, world.yaw));
            assertEquals(MovementState.Mode.LEDGE_HANG, s.mode());
            assertEquals(new MovementVector(-sign, 0, 0), s.wall().normal());
            assertEquals(adjoining, s.wall().support());
            assertEquals(-sign * 90, world.yaw, 0.001);
            assertTrue(events.contains(MovementEvent.SHIMMY_CORNER));
            assertFalse(events.contains(MovementEvent.WALL_DETACH));
            double z = s.position().z();
            s = tick(s, world, input(32, 0, sign, false, false, false, world.yaw));
            assertEquals(AdvancedState.LedgeKind.SHIMMY, s.advanced().ledge().kind());
            for (int i = 33; i <= 56; i++) s = tick(s, world, input(i, 0, 0, false, false, false, world.yaw));
            assertEquals(z - 0.6, s.position().z(), 0.001);
        }
    }
    @Test void innerCornersRequireMatchingLipHeightAndClearBodyPath() {
        var ledge = box(-4, 0, 1, 4, 3, 5); var start = hanging(ledge, 0.65);
        for (double height : new double[]{2.5, 3.5}) {
            var world = new World(ledge, box(1, 0, -3, 4, height, 5));
            assertNull(AdvancedMoves.ledge(world, start.position(), start.body(), start.wall(), input(1, 0, 1, false, false, false, 0), false));
        }
        var world = new World(ledge, box(1, 0, -3, 4, 3, 5), box(0.4, 0, -1, 0.9, 2, 0.3));
        assertNull(AdvancedMoves.ledge(world, start.position(), start.body(), start.wall(), input(1, 0, 1, false, false, false, 0), false));
    }
    @Test void innerCornerRevalidatesAdjoiningSupportDuringTurn() {
        var ledge = box(-4, 0, 1, 4, 3, 5); var adjoining = box(1, 0, -3, 4, 3, 5);
        var world = new World(ledge, adjoining);
        var s = tick(hanging(ledge, 0.65), world, input(1, 0, 1, false, false, false, 0));
        assertNotNull(s.advanced().ledge());
        world.world = new BoxWorld(List.of(ledge));
        s = tick(s, world, input(2, 0, 0, false, false, false, world.yaw));
        assertEquals(MovementState.Mode.AIR, s.mode());
        assertNull(s.advanced().ledge());
        assertTrue(events.contains(MovementEvent.WALL_DETACH));
    }
    @Test void upwardTransferWinsOverPullUpAndRevalidatesSupport() {
        var ledge = box(-3, 0, 1, 3, 3, 5); var high = box(-3, 3, 1.2, 3, 4, 5);
        var world = new World(ledge, high); var s = tick(hanging(ledge, 0), world, input(1, 1, 0, true, false, false, 0));
        assertTrue(events.contains(MovementEvent.GRAB_TRANSFER)); assertFalse(events.contains(MovementEvent.PULL_UP_START));
        assertNotNull(s.advanced().ledge());
        for (int i = 2; i < 14; i++) s = tick(s, world, input(i, 0, 0, false, false, false, 0));
        assertEquals(4 - 1.828, s.position().y(), 0.001); assertEquals(MovementState.Mode.LEDGE_HANG, s.mode());
        var start = tick(hanging(ledge, 0), world, input(1, 1, 0, true, false, false, 0));
        world.world = new BoxWorld(List.of(ledge));
        assertEquals(MovementState.Mode.AIR, tick(start, world, input(2, 0, 0, false, false, false, 0)).mode());
    }
    @Test void springboardRequiresTwoShapesLaunchesAndRejectsMissingStepOrHeadroom() {
        var step = box(-2, 0, 2, 2, 0.5, 3); var top = box(-2, 0, 3, 2, 1, 5);
        var world = new World(FLOOR, step, top); var s = ground();
        for (int i = 1; i < 20 && !events.contains(MovementEvent.TRAVERSAL_COMPLETE); i++) s = tick(s, world, input(i, 1, 0, i == 1, false, false, 0));
        assertTrue(events.contains(MovementEvent.SPRINGBOARD_START), events.toString());
        assertEquals(MovementState.Mode.AIR, s.mode()); assertTrue(s.velocity().y() > 8);
        assertNull(AdvancedMoves.springboard(new World(FLOOR, top), ground().position(), ground().velocity(), input(1, 1, 0, true, false, false, 0)));
        assertNull(AdvancedMoves.springboard(new World(FLOOR, step, top, box(-3, 2, 1, 3, 4, 6)), ground().position(), ground().velocity(), input(1, 1, 0, true, false, false, 0)));
    }
    @Test void disabledMantlingKeepsLedgeHangButPreventsPullUps() {
        var ledge = box(-3, 0, 1, 3, 3, 5); var world = new World(ledge);
        var start = hanging(ledge, 0);
        var command = input(1, 1, 0, true, false, false, 0).withoutEdges();
        var enabled = tick(start, world, command);
        assertEquals(MovementState.Mode.PULL_UP, enabled.mode());
        events.clear();
        var tuning = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(true, true, true, false, true));
        var disabled = controller.tick(start, command, tuning, world, events::add);
        assertEquals(MovementState.Mode.LEDGE_HANG, disabled.mode());
        assertNull(disabled.traversal().path());
        assertFalse(events.contains(MovementEvent.PULL_UP_START));
    }
    @Test void disabledVaultingUsesOrdinaryJumpAndCancelsSpringboardRunIn() {
        var step = box(-2, 0, 2, 2, 0.5, 3); var top = box(-2, 0, 3, 2, 1, 5);
        var world = new World(FLOOR, step, top);
        var tuning = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(true, true, false, true, true));
        var command = input(1, 1, 0, true, false, false, 0);
        var disabled = controller.tick(ground(), command, tuning, world, events::add);
        assertNull(disabled.advanced().spring());
        assertTrue(events.contains(MovementEvent.JUMP));
        assertFalse(events.contains(MovementEvent.SPRINGBOARD_START));
        var runIn = tick(ground(), world, command);
        assertNotNull(runIn.advanced().spring());
        events.clear();
        var canceled = controller.tick(runIn, input(2, 1, 0, false, false, false, 0), tuning, world, events::add);
        assertNull(canceled.advanced().spring());
        assertFalse(events.contains(MovementEvent.SPRINGBOARD_START));
    }
    @Test void chainedCommandsReplayIdenticallyFromMidStrafeJumpAndMidTurn() {
        var world = new World(FLOOR); var s = tick(ground(), world, input(1, 1, 1, true, false, false, 0));
        var replay = s;
        for (int i = 2; i < 30; i++) {
            var command = input(i, 1, 0, false, false, i == 12, 0);
            s = tick(s, world, command);
            replay = controller.tick(replay, command, MovementTuning.DEFAULT, new World(FLOOR), e -> {});
            assertEquals(s, replay);
        }
    }

    @Test void wallrunTurnThenJumpPushesOutAndClimbTurnKicksWithinWindow() {
        var wall = box(1, -5, -20, 2, 10, 40); var world = new World(wall);
        var feet = new MovementVector(0.7, 2, 0);
        var contact = WallState.EMPTY.contact(new MovementVector(-1, 0, 0), wall, feet, new MovementVector(0, 1, 6), false, 0);
        var s = new MovementState(feet, new MovementVector(0, 1, 6), MovementState.Mode.WALLRUN, 0.2, false, 0,
                LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, contact);
        s = tick(s, world, input(1, 1, 0, false, false, true, 0));
        assertEquals(MovementState.Mode.WALLRUN, s.mode()); assertTrue(events.contains(MovementEvent.WALLRUN_TURN));
        s = tick(s, world, input(2, 1, 0, true, false, false, world.yaw));
        assertTrue(s.velocity().x() < -5); assertEquals(MovementState.Mode.AIR, s.mode());
        feet = new MovementVector(0.7, 2, 0); var v = new MovementVector(0, 2, 0);
        contact = WallState.EMPTY.contact(new MovementVector(-1, 0, 0), wall, feet, v, true, 0);
        s = new MovementState(feet, v, MovementState.Mode.WALLCLIMB, 0.1, false, 0,
                LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, contact);
        s = tick(s, world, input(3, 1, 0, false, false, true, -90));
        assertEquals(MovementState.Mode.WALLCLIMB_TURNED, s.mode());
        s = tick(s, world, input(4, 1, 0, true, false, false, world.yaw));
        assertTrue(s.velocity().x() < -3.8 && s.velocity().y() > 8);
        assertTrue(events.contains(MovementEvent.WALL_KICK));
    }

    @Test void strafeWallJumpKeepsNormalPushAndTurnedHangKicksAway() {
        var wall = box(1, -5, -20, 2, 10, 40); var world = new World(wall); var feet = new MovementVector(0.7, 2, 0);
        var v = new MovementVector(0, 1, 6);
        var contact = WallState.EMPTY.contact(new MovementVector(-1, 0, 0), wall, feet, v, false, 0);
        var s = new MovementState(feet, v, MovementState.Mode.WALLRUN, 0.2, false, 0,
                LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, contact);
        s = tick(s, world, input(1, 1, -1, true, false, false, 0));
        assertTrue(events.contains(MovementEvent.WALL_JUMP));
        assertTrue(s.velocity().x() < -1 && s.velocity().x() > -2, "Strafe injected a wall dodge");
        var ledge = box(-3, 0, 1, 3, 3, 5); world = new World(ledge);
        s = tick(hanging(ledge, 0), world, input(2, 0, 0, true, false, false, 180));
        assertEquals(MovementState.Mode.AIR, s.mode()); assertTrue(s.velocity().z() < -3.8);
        assertTrue(events.contains(MovementEvent.WALL_KICK));
    }

    @Test void springboardAbortsIfSupportDisappearsDuringRunInOrPlant() {
        var step = box(-2, 0, 4, 2, 0.5, 5); var top = box(-2, 0, 5, 2, 1, 7); var world = new World(FLOOR, step, top);
        var s = tick(ground(), world, input(1, 1, 0, true, false, false, 0));
        assertNotNull(s.advanced().spring());
        world.world = new BoxWorld(List.of(FLOOR, step));
        s = tick(s, world, input(2, 1, 0, false, false, false, 0));
        assertNull(s.advanced().spring()); assertNull(s.traversal().path());
        assertFalse(events.contains(MovementEvent.SPRINGBOARD_START));
    }

    @Test void strafeJumpDoesNotLaunchFromClimbAndTurnedClimbExpires() {
        var wall = box(-4, 0, 1, 4, 10, 3); var world = new World(wall);
        var feet = new MovementVector(0, 2, 0.7); var v = new MovementVector(0, 2, 0);
        var contact = WallState.EMPTY.contact(new MovementVector(0, 0, -1), wall, feet, v, true, 0);
        var start = new MovementState(feet, v, MovementState.Mode.WALLCLIMB, 0.1, false, 0,
                LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, contact);
        var s = tick(start, world, input(1, 0, 1, true, false, false, 0));
        assertEquals(MovementState.Mode.WALLCLIMB, s.mode());
        assertEquals(0, s.velocity().x(), 1.0E-9); assertTrue(s.velocity().y() < 2);
        events.clear(); s = tick(start, world, input(2, 1, 0, false, false, true, 0));
        for (int i = 3; i < 17; i++) s = tick(s, world, input(i, 0, 0, false, false, false, world.yaw));
        assertEquals(MovementState.Mode.AIR, s.mode()); assertTrue(events.contains(MovementEvent.WALL_DETACH));
    }

    @Test void earlyShimmyAndBlockedTransferDoNotCreatePaths() {
        var ledge = box(-3, 0, 1, 3, 3, 5); var world = new World(ledge);
        var h = hanging(ledge, 0);
        var early = new MovementState(h.position(), h.velocity(), h.mode(), 0.1, false, 0,
                h.locomotion(), h.blocks(), h.body(), 0, h.traversal(), h.wall());
        assertNull(tick(early, world, input(1, 0, 1, false, false, false, 0)).advanced().ledge());
        var high = box(-3, 3, 1.2, 3, 4, 5);
        var roof = box(-2, 3, -1, 2, 3.3, 1.2);
        world = new World(ledge, high, roof);
        assertNull(AdvancedMoves.ledge(world, h.position(), h.body(), h.wall(), input(2, 1, 0, true, false, false, 0), true));
    }
}
