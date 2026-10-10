package dev.mineedge.movement;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocomotionTest {
    @Test
    void groundedLaunchKeepsExcessSpeedAndBrakesGradually() {
        var controller = new MovementController();
        var state = MovementControllerTest.grounded().withVelocity(new MovementVector(0, 0, 20));
        var next = controller.tick(state, MovementInput.idle(1, 0, 0), MovementTuning.DEFAULT,
                MovementControllerTest::floor, event -> {});
        assertTrue(next.velocity().horizontalSpeed() > MovementTuning.DEFAULT.sprintSpeed());
        assertTrue(next.velocity().horizontalSpeed() < 20);
        assertTrue(next.position().z() - state.position().z() > 0.5);
    }

    private final MovementController controller = new MovementController();

    private MovementState tick(MovementState state, MovementInput input) {
        return controller.tick(state, input, MovementTuning.DEFAULT, MovementControllerTest::floor, event -> {});
    }

    private static MovementInput sprint(int tick) {
        return MovementControllerTest.forward(tick).withSprint(true);
    }

    @Test
    void sprintFollowsReferenceSpeedCurveAndWalkRemainsBelowBaseSpeed() {
        // faith_move/locomotion.rs::sprint_follows_the_speed_curve, sampled at 20 TPS / 100 Hz physics.
        MovementState sprinting = MovementControllerTest.grounded();
        MovementState walking = sprinting;
        var reference = new MovementTuning(4, 6.3, 16.4, 61.44, 1.536, 5, 0.2, 0.5, 1.8);
        for (int tick = 1; tick <= 160; tick++) {
            sprinting = controller.tick(sprinting, sprint(tick), reference, MovementControllerTest::floor, event -> {});
            walking = controller.tick(walking, MovementControllerTest.forward(tick), reference, MovementControllerTest::floor, event -> {});
            double expected = switch (tick) { case 8 -> 4; case 20 -> 5.2; case 70 -> 6.5; case 140 -> 7.2;
                default -> -1; };
            if (expected > 0) {
                assertEquals(expected, sprinting.velocity().horizontalSpeed(), 0.25, "tick " + tick);
            }
            assertTrue(walking.velocity().horizontalSpeed() <= 4.001);
            assertTrue(sprinting.velocity().horizontalSpeed() <= 7.200001);
        }
        assertTrue(sprinting.locomotion().energy() > 3);
    }

    @Test
    void configuredRunMultiplierSetsSprintTopSpeedWithoutChangingWalking() {
        for (double walkSpeed : new double[]{2, 4, 4.317, 8}) {
            var baseline = new MovementTuning(walkSpeed, 6.3, 16.4, 61.44, 1.536, 5);
            for (double multiplier : new double[]{1, 1.5, 1.8, 3, 5}) {
                var tuning = new MovementTuning(walkSpeed, 6.3, 16.4, 61.44, 1.536, 5, 0.2, 0.5, multiplier);
                MovementState sprinting = MovementControllerTest.grounded();
                MovementState walking = sprinting;
                MovementState baselineWalking = sprinting;
                for (int tick = 1; tick <= 400; tick++) {
                    sprinting = controller.tick(sprinting, sprint(tick), tuning, MovementControllerTest::floor, event -> {});
                    walking = controller.tick(walking, MovementControllerTest.forward(tick), tuning,
                            MovementControllerTest::floor, event -> {});
                    baselineWalking = controller.tick(baselineWalking, MovementControllerTest.forward(tick), baseline,
                            MovementControllerTest::floor, event -> {});
                    assertTrue(sprinting.velocity().horizontalSpeed() <= walkSpeed * multiplier + 1.0E-6);
                    assertEquals(baselineWalking, walking);
                }
                assertEquals(walkSpeed * multiplier, sprinting.velocity().horizontalSpeed(), 0.05,
                        "walk speed " + walkSpeed + ", multiplier " + multiplier);
            }
        }
    }

    @Test
    void releaseBrakesAndShortTapUsesReferenceStopWindow() {
        MovementState state = MovementControllerTest.grounded();
        for (int tick = 1; tick <= 40; tick++) {
            state = tick(state, sprint(tick));
        }
        for (int tick = 41; tick <= 52; tick++) {
            state = tick(state, MovementInput.idle(tick, 0, 0));
        }
        assertEquals(0, state.velocity().horizontalSpeed());
        assertEquals(0, state.locomotion().energy());
        state = tick(MovementControllerTest.grounded(), sprint(1));
        state = tick(state, MovementInput.idle(2, 0, 0));
        assertTrue(state.locomotion().stoppingTime() > 0.2);
        state = tick(state, sprint(3));
        assertTrue(state.velocity().horizontalSpeed() < 0.4);
        for (int tick = 4; tick <= 12; tick++) {
            state = tick(state, sprint(tick));
        }
        assertEquals(0, state.locomotion().stoppingTime());
        assertTrue(state.velocity().horizontalSpeed() > 1);
    }

    @Test
    void shortMovementTapsAt20HzBrakeContinuouslyToRest() {
        for (float[] axes : new float[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int heldSteps = 1; heldSteps <= MovementRate.HZ / 8; heldSteps++) {
                MovementState state = MovementControllerTest.grounded();
                for (int step = 1; step <= heldSteps; step++) {
                    var input = new MovementInput(step, axes[0], axes[1], axes[1],
                            false, false, false, false, false, 0, 0);
                    state = controller.step(state, input, MovementTuning.DEFAULT,
                            MovementControllerTest::floor, event -> {});
                }
                assertTrue(state.velocity().horizontalSpeed() > 0);
                for (int step = 1; step <= MovementRate.HZ / 4 + 1; step++) {
                    double previousSpeed = state.velocity().horizontalSpeed();
                    state = controller.step(state, MovementInput.idle(heldSteps + step, 0, 0),
                            MovementTuning.DEFAULT, MovementControllerTest::floor, event -> {});
                    double speed = state.velocity().horizontalSpeed();
                    if (previousSpeed > 0) assertTrue(speed < previousSpeed,
                            "Released movement must keep slowing down, step " + step);
                    else assertEquals(0, speed, "Stopped movement must stay stopped");
                }
                assertEquals(0, state.velocity().horizontalSpeed());
            }
        }
    }

    @Test
    void releasingVerySlowShortTapDoesNotAccelerateToTheStopSpeed() {
        var tuning = new MovementTuning(4.317, 7, 20, 0.1, 1.536, 5);
        var moving = controller.step(MovementControllerTest.grounded(), MovementControllerTest.forward(1),
                tuning, MovementControllerTest::floor, event -> {});
        assertTrue(moving.velocity().horizontalSpeed() > 0);
        assertTrue(moving.velocity().horizontalSpeed() < 0.1);
        var released = controller.step(moving, MovementInput.idle(2, 0, 0),
                tuning, MovementControllerTest::floor, event -> {});
        assertEquals(0, released.velocity().horizontalSpeed());
    }

    @Test
    void shortTapOnIceCoastsWithoutTheGroundStopSnap() {
        var resolver = new MovementController.MotionResolver() {
            @Override public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds) {
                return MovementControllerTest.floor(position, velocity, seconds);
            }
            @Override public double groundTraction(MovementVector position, MovementBody body) { return 0.19; }
        };
        var moving = controller.tick(MovementControllerTest.grounded(), MovementControllerTest.forward(1),
                MovementTuning.DEFAULT, resolver, e -> {});
        var released = controller.tick(moving, MovementInput.idle(2, 0, 0), MovementTuning.DEFAULT, resolver, e -> {});
        assertEquals(0, released.locomotion().stoppingTime());
        assertTrue(released.velocity().horizontalSpeed() > moving.velocity().horizontalSpeed() * 0.9);
        assertTrue(released.velocity().horizontalSpeed() < moving.velocity().horizontalSpeed());
    }

    @Test
    void strafingDrainsSprintEnergyAndHardTurnCostsMomentum() {
        MovementState state = MovementControllerTest.grounded();
        for (int tick = 1; tick <= 140; tick++) {
            state = tick(state, sprint(tick));
        }
        double energy = state.locomotion().energy();
        MovementState straight = tick(state, sprint(141));
        MovementState turned = tick(state, new MovementInput(141, 1, 0, 0,
                false, false, false, false, true, 90, 0));
        assertTrue(turned.velocity().horizontalSpeed() < straight.velocity().horizontalSpeed() - 0.5);
        for (int tick = 141; tick <= 200; tick++) {
            state = tick(state, new MovementInput(tick, 0, 1, 1, false, false, false, false, true, 0, 0));
        }
        assertTrue(state.locomotion().energy() < energy - 2.1);
        assertTrue(state.velocity().x() > 3.9);
        assertEquals(0, state.velocity().z(), 0.01);
    }

    @Test
    void yawWrapDoesNotBecomeAFullTurnAndInitialFacingDoesNotDamp() {
        MovementState baseline = new MovementState(MovementVector.ZERO, new MovementVector(0, 0, -6),
                MovementState.Mode.GROUND, 1, true, 0, new LocomotionState(2, 1, 0, 0, 179, true));
        MovementInput input = new MovementInput(1, 1, 0, 0, false, false, false, false, true, -179, 0);
        assertTrue(tick(baseline, input).velocity().horizontalSpeed() > 5.8);
        MovementState initial = new MovementState(MovementVector.ZERO, new MovementVector(0, 0, -6),
                MovementState.Mode.GROUND, 1, true, 0);
        assertTrue(tick(initial, input).velocity().horizontalSpeed() > 5.9);
    }

    @Test
    void airControlIsLimitedAndNeverBrakesMomentumWhenInputIsReleased() {
        MovementState state = new MovementState(new MovementVector(0, 10, 0), new MovementVector(0, 0, 5),
                MovementState.Mode.AIR, 1, false, 0);
        MovementState released = tick(state, MovementInput.idle(1, 0, 0));
        assertEquals(5, released.velocity().horizontalSpeed(), 1.0E-12);
        MovementState steer = tick(state, new MovementInput(1, 0, 1, 1, false, false, false, false, false, 0, 0));
        assertTrue(steer.velocity().x() > 0);
        assertTrue(steer.velocity().x() <= MovementTuning.DEFAULT.airAcceleration() * 0.05 + 1.0E-6);
        MovementState fast = tick(state.withVelocity(new MovementVector(0, 0, 8)),
                new MovementInput(1, 0, 1, 1, false, false, false, false, false, 0, 0));
        assertTrue(fast.velocity().x() <= 0.01 * 0.05 + 1.0E-6);
        assertEquals(8, fast.velocity().z(), 1.0E-12);
    }

    @Test
    void bufferedPressLaunchesOnLandingButExpiredPressAndHeldJumpDoNot() {
        MovementState falling = new MovementState(new MovementVector(0, 0.1, 0), new MovementVector(0, -3, 0),
                MovementState.Mode.AIR, 1, false, 0);
        List<MovementEvent> events = new ArrayList<>();
        MovementInput press = new MovementInput(1, 0, 0, 0, true, true, false, false, false, 0, 0);
        MovementState state = controller.tick(falling, press, MovementTuning.DEFAULT,
                MovementControllerTest::floor, events::add);
        assertTrue(state.velocity().y() > 0);
        assertEquals(List.of(MovementEvent.LAND, MovementEvent.JUMP), events);
        assertEquals(0, state.locomotion().jumpBuffer());
        events.clear();
        state = new MovementState(new MovementVector(0, 3, 0), MovementVector.ZERO,
                MovementState.Mode.AIR, 1, false, 0);
        for (int tick = 1; tick <= 30; tick++) {
            state = controller.tick(state, new MovementInput(tick, 0, 0, 0, true, tick == 1,
                    false, false, false, 0, 0), MovementTuning.DEFAULT, MovementControllerTest::floor, events::add);
        }
        assertEquals(0, events.stream().filter(e -> e == MovementEvent.JUMP).count());
        assertTrue(state.grounded());
    }

    @Test
    void jumpAddsReferenceForwardSpeedOnlyWhenAlreadyMovingForward() {
        MovementState moving = MovementControllerTest.grounded().withVelocity(new MovementVector(0, 0, 4));
        MovementInput press = new MovementInput(1, 0, 0, 0, true, true, false, false, false, 0, 0);
        assertEquals(5.07925, tick(moving, press).velocity().z(), 1.0E-12);
        assertEquals(0, tick(MovementControllerTest.grounded(), press).velocity().horizontalSpeed());
    }
}
