package dev.mineedge.client.input;

import dev.mineedge.movement.Locomotion;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SprintLatchTest {
    @Test void doubleTapDecisionSurvivesRepeatedNetworkAndPredictionSamplesAndAccelerates() {
        var latch = new SprintLatch();
        latch.update(true);
        MovementVector velocity = MovementVector.ZERO;
        LocomotionState locomotion = LocomotionState.EMPTY;
        for (int tick = 1; tick <= 160; tick++) {
            // Sampling/presentation writes must not consume the native sprint decision.
            assertTrue(latch.capture(false, true, true, false));
            var input = new MovementInput(tick, 1, 0, 0, false, false, false, false,
                    latch.capture(false, true, true, false), 0, 0);
            var result = Locomotion.step(velocity, input, MovementTuning.DEFAULT, locomotion, 0, true, 0.05);
            velocity = result.horizontal();
            locomotion = new LocomotionState(result.energy(), result.accelerationTime(), result.stoppingTime(), 0, 0, false);
        }
        assertEquals(MovementTuning.DEFAULT.sprintSpeed(), velocity.horizontalSpeed(), 0.05);
    }

    @Test void nativeStopAndInputInterruptionRequireAFreshSprintDecision() {
        var latch = new SprintLatch();
        latch.update(true);
        latch.update(false);
        assertFalse(latch.capture(false, true, true, false));
        latch.update(true);
        assertFalse(latch.capture(false, false, true, false));
        assertFalse(latch.capture(false, true, true, false));
        latch.update(true);
        assertFalse(latch.capture(false, true, true, true));
        assertFalse(latch.capture(false, true, true, false));
    }

    @Test void sprintRestrictionsClearDoubleTapIntentButKeepTheSprintBindingUsable() {
        var latch = new SprintLatch();
        latch.update(true);
        assertFalse(latch.capture(true, true, false, false));
        assertFalse(latch.capture(false, true, true, false));
        assertTrue(latch.capture(true, true, true, false));
        assertFalse(latch.capture(false, true, true, false));
        assertTrue(latch.capture(true, false, true, false));
        assertFalse(latch.capture(true, true, true, true));
    }
}
