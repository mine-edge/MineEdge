package dev.mineedge.movement;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementControllerTest {
    private final MovementController controller = new MovementController();
    private final MovementTuning tuning = MovementTuning.DEFAULT;

    static MovementState grounded() {
        return new MovementState(MovementVector.ZERO, MovementVector.ZERO, MovementState.Mode.GROUND, 0, true, 0);
    }

    static MovementController.Motion floor(MovementVector position, MovementVector velocity, double seconds) {
        MovementVector next = position.add(velocity.scale(seconds));
        if (next.y() <= 0 && velocity.y() <= 0) {
            return new MovementController.Motion(new MovementVector(next.x(), 0, next.z()),
                    new MovementVector(velocity.x(), 0, velocity.z()), true);
        }
        return new MovementController.Motion(next, velocity, false);
    }

    static MovementInput forward(long sequence) {
        return new MovementInput(sequence, 1, 0, 0, false, false, false, false, false, 0, 0);
    }

    @Test
    void cardinalDirectionsUseMinecraftYawAndStrafeSigns() {
        double[][] expected = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        for (int i = 0; i < expected.length; i++) {
            MovementInput input = new MovementInput(1, 1, 0, 0, false, false, false, false, false, i * 90, 0);
            assertEquals(expected[i][0], input.direction().x(), 1.0E-6);
            assertEquals(expected[i][1], input.direction().z(), 1.0E-6);
            MovementInput left = new MovementInput(1, 0, 1, 1, false, false, false, false, false, i * 90, 0);
            assertEquals(expected[i][1], left.direction().x(), 1.0E-6);
            assertEquals(-expected[i][0], left.direction().z(), 1.0E-6);
        }
    }

    @Test
    void diagonalIsNormalizedWithoutLosingRawStrafe() {
        MovementInput input = new MovementInput(1, 1, 1, 1, false, false, false, false, false, 0, 0);
        assertEquals(1, input.direction().length(), 1.0E-6);
        assertEquals(1, input.rawStrafe());
        assertThrows(IllegalArgumentException.class, () -> new MovementInput(1, Float.NaN, 0, 0,
                false, false, false, false, false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MovementInput(1, 0, 0, 0,
                false, false, false, false, false, Float.POSITIVE_INFINITY, 0));
    }

    @Test
    void oneTickAdvancesExactlyFiveSubstepsAndOneTwentiethSecond() {
        List<Double> steps = new ArrayList<>();
        MovementState state = controller.tick(grounded(), forward(1), tuning, (position, velocity, seconds) -> {
            steps.add(seconds);
            return floor(position, velocity, seconds);
        }, event -> {});
        assertEquals(5, steps.size());
        assertEquals(0.05, steps.stream().mapToDouble(Double::doubleValue).sum(), 1.0E-12);
        assertEquals(0.05, state.modeTime(), 1.0E-12);
    }

    @Test
    void walkReachesTargetSpeedAndStopsWithoutDoubleIntegration() {
        MovementState state = grounded();
        for (int tick = 1; tick <= 20; tick++) {
            state = controller.tick(state, forward(tick), tuning, MovementControllerTest::floor, event -> {});
        }
        assertEquals(4.317, state.velocity().horizontalSpeed(), 0.01);
        assertTrue(state.position().z() > 3.56 && state.position().z() < 3.89);
        for (int tick = 21; tick <= 32; tick++) {
            state = controller.tick(state, MovementInput.idle(tick, 0, 0), tuning,
                    MovementControllerTest::floor, event -> {});
        }
        assertEquals(0, state.velocity().horizontalSpeed(), 1.0E-6);
    }

    @Test
    void heldJumpHasOneLaunchAndReferenceHeightArc() {
        // Adapted from faith_move/src/tests.rs::jump_arc_and_land.
        MovementState state = grounded();
        List<MovementEvent> events = new ArrayList<>();
        double peak = 0;
        for (int tick = 1; tick <= 24; tick++) {
            MovementInput input = new MovementInput(tick, 0, 0, 0, true, tick == 1,
                    false, false, false, 0, 0);
            state = controller.tick(state, input, tuning, MovementControllerTest::floor, events::add);
            peak = Math.max(peak, state.position().y());
        }
        assertEquals(1, events.stream().filter(event -> event == MovementEvent.JUMP).count());
        assertEquals(1, events.stream().filter(event -> event == MovementEvent.LAND).count());
        assertEquals(tuning.jumpSpeed() * tuning.jumpSpeed() / (2 * tuning.gravity()), peak, 0.05);
        assertEquals(MovementState.Mode.GROUND, state.mode());
        assertEquals(0, state.position().y(), 1.0E-6);
    }

    @Test
    void ceilingStopsRiseAndAllowsLanding() {
        MovementState state = grounded();
        List<MovementEvent> events = new ArrayList<>();
        MovementController.MotionResolver ceiling = (position, velocity, seconds) -> {
            MovementController.Motion motion = floor(position, velocity, seconds);
            if (motion.position().y() > 0.3) {
                return new MovementController.Motion(new MovementVector(0, 0.3, motion.position().z()),
                        new MovementVector(0, 0, motion.velocity().z()), false);
            }
            return motion;
        };
        double peak = 0;
        for (int tick = 1; tick <= 20; tick++) {
            state = controller.tick(state, new MovementInput(tick, 0, 0.5F, 0.5F, true, tick == 1,
                    false, false, false, 0, 0), tuning, ceiling, events::add);
            peak = Math.max(peak, state.position().y());
        }
        // The exact contact can occur between tick samples; it must never pass the ceiling.
        assertTrue(peak > 0.25 && peak <= 0.3);
        assertEquals(0, state.position().y(), 1.0E-6);
        assertEquals(0, state.velocity().y(), 1.0E-6);
        assertEquals(1, events.stream().filter(event -> event == MovementEvent.JUMP).count());
    }

    @Test
    void importedImpulseDecaysThroughControlRatherThanBeingDiscarded() {
        MovementState state = grounded().withVelocity(new MovementVector(10, 4, 0));
        state = controller.tick(state, MovementInput.idle(1, 0, 0), tuning,
                MovementControllerTest::floor, event -> {});
        assertTrue(state.position().x() > 0.4);
        assertTrue(state.velocity().x() > 7);
        assertTrue(state.position().y() > 0);
    }
}
