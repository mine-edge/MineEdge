package dev.mineedge.movement;

import dev.mineedge.client.movement.CommandHistory;
import dev.mineedge.network.InputSchedule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OffThreadMovementTest {
    @Test void workerCalculationsKeepAllWorldCallbacksOnTheCallingThread() {
        Thread gameThread = Thread.currentThread();
        var calls = new ArrayList<Double>();
        var stepCalls = new ArrayList<Boolean>();
        MovementController.MotionResolver world = new MovementController.MotionResolver() {
            @Override public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds) {
                assertSame(gameThread, Thread.currentThread());
                calls.add(seconds);
                return MovementControllerTest.floor(position, velocity, seconds);
            }
            @Override public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds,
                                                                double stepHeight, boolean grounded) {
                assertSame(gameThread, Thread.currentThread());
                assertEquals(0.6, stepHeight);
                assertTrue(grounded, "Worker lost the grounded step-up context");
                stepCalls.add(grounded);
                return resolve(position, velocity, seconds);
            }
        };
        var state = OffThreadMovement.run(world, remote -> {
            assertNotSame(gameThread, Thread.currentThread());
            assertTrue(Thread.currentThread().isVirtual());
            return new MovementController().step(MovementControllerTest.grounded(),
                    MovementControllerTest.forward(1), MovementTuning.DEFAULT, remote, event -> {});
        });
        assertEquals(MovementRate.SUBSTEPS, calls.size());
        assertEquals(MovementRate.SUBSTEPS, stepCalls.size());
        assertEquals(MovementRate.STEP_SECONDS, calls.stream().mapToDouble(Double::doubleValue).sum(), 1.0E-12);
        assertEquals(MovementRate.STEP_SECONDS, state.modeTime(), 1.0E-12);
        assertEquals(MovementRate.HZ, InputSchedule.UPDATES_PER_SECOND);
        assertEquals(MovementRate.INTERVAL_NANOS, InputSchedule.UPDATE_INTERVAL_NANOS);
    }

    @Test void simulationStepsEqualOneNativeTickWithoutChangingMovementSpeed() {
        var controller = new MovementController();
        var initial = MovementControllerTest.grounded();
        var input = MovementControllerTest.forward(1);
        var expected = controller.tick(initial, input, MovementTuning.DEFAULT, MovementControllerTest::floor, event -> {});
        var actual = initial;
        for (int step = 0; step < MovementRate.STEPS_PER_GAME_TICK; step++) {
            actual = OffThreadMovement.step(controller, actual, input.withSequence(step + 1),
                    MovementTuning.DEFAULT, MovementControllerTest::floor).state();
        }
        assertEquals(expected, actual);
    }

    @Test void predictionReplayUsesTheSameWorkerStepAndPreservesPendingImpulses() {
        var controller = new MovementController();
        var history = new CommandHistory();
        var initial = MovementControllerTest.grounded();
        var state = initial;
        MovementState baseline = null;
        for (int step = 1; step <= 20; step++) {
            var input = MovementControllerTest.forward(step);
            var impulse = step == 10 ? new MovementVector(2, 4, 0) : MovementVector.ZERO;
            assertTrue(history.add(input, impulse));
            state = state.withVelocity(state.velocity().add(impulse));
            state = OffThreadMovement.step(controller, state, input, MovementTuning.DEFAULT, MovementControllerTest::floor).state();
            history.recordPrediction(step, state);
            if (step == 5) baseline = state;
        }
        history.acknowledge(5);
        assertEquals(state, history.replaySimulation(baseline, controller, MovementTuning.DEFAULT, MovementControllerTest::floor));
        assertTrue(history.matches(20, state));
    }

    @Test void workerAndWorldExceptionsPropagateWithoutDeadlockingTheBridge() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var exception = new IllegalStateException("fixture failure");
            var workerFailure = assertThrows(CompletionException.class,
                    () -> OffThreadMovement.run(MovementControllerTest::floor, remote -> { throw exception; }));
            assertSame(exception, workerFailure.getCause());
            var worldFailure = assertThrows(CompletionException.class,
                    () -> OffThreadMovement.step(new MovementController(), MovementControllerTest.grounded(),
                            MovementControllerTest.forward(1), MovementTuning.DEFAULT,
                            (position, velocity, seconds) -> { throw exception; }));
            assertSame(exception, worldFailure.getCause());
        });
    }

    @Test void wrappedReplayCommitsPredictionsOnlyAfterTheWholeWorkerBatchSucceeds() {
        var controller = new MovementController();
        var history = new CommandHistory();
        var baseline = MovementControllerTest.grounded();
        var state = baseline;
        var original = new ArrayList<MovementState>();
        for (int step = 1; step <= 160; step++) {
            var input = MovementControllerTest.forward(step);
            var impulse = step == 145 ? new MovementVector(2, 4, 0) : MovementVector.ZERO;
            assertTrue(history.add(input, impulse));
            state = state.withVelocity(state.velocity().add(impulse));
            state = controller.step(state, input, MovementTuning.DEFAULT, MovementControllerTest::floor, event -> {});
            history.recordPrediction(step, state);
            if (step <= 140) {
                baseline = state;
                history.acknowledge(step);
            } else original.add(state);
        }
        var correctedBaseline = baseline.withVelocity(baseline.velocity().add(new MovementVector(0.5, 0, 0)));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(CompletionException.class, () -> history.replaySimulation(correctedBaseline, controller,
                MovementTuning.DEFAULT, (position, velocity, seconds) -> {
                    if (calls.incrementAndGet() == MovementRate.SUBSTEPS + 1) throw new IllegalStateException("fixture failure");
                    return MovementControllerTest.floor(position, velocity, seconds);
                }));
        for (int i = 0; i < original.size(); i++) assertTrue(history.matches(141 + i, original.get(i)));
        assertEquals(state, history.replaySimulation(baseline, controller, MovementTuning.DEFAULT, MovementControllerTest::floor));
        assertTrue(history.matches(160, state));
    }
}
