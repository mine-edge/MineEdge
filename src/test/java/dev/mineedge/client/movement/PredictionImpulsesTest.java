package dev.mineedge.client.movement;

import dev.mineedge.movement.MovementVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionImpulsesTest {
    @Test void correctionPreservesAnImportedPushUntilItsCommandWithoutCancellingIt() {
        var impulses = new PredictionImpulses();
        var initial = new MovementVector(4, 0, 2);
        var push = new MovementVector(2, 5, -1);
        var entityVelocity = impulses.written(initial).add(push.scale(1.0 / 20));
        assertVectorEquals(push, impulses.capture(entityVelocity));
        assertEquals(MovementVector.ZERO, impulses.capture(entityVelocity),
                "A native tick and a simulation step must not import the same push twice");

        // Two snapshots arrive before the next predicted command. Each restores an
        // authoritative velocity plus the still-unrecorded local push.
        for (var authoritative : new MovementVector[] {new MovementVector(3, -1, 4), new MovementVector(1, 2, 0)}) {
            var unimported = impulses.unimported(entityVelocity);
            assertEquals(MovementVector.ZERO, unimported);
            var corrected = authoritative.add(impulses.pending());
            entityVelocity = impulses.written(corrected).add(unimported);
            assertEquals(MovementVector.ZERO, impulses.capture(entityVelocity),
                    "Reconciliation must not introduce a negative impulse that cancels the push");
            assertVectorEquals(push, impulses.pending());
        }
        assertVectorEquals(push, impulses.consume());
        assertEquals(MovementVector.ZERO, impulses.consume());
    }

    @Test void velocityChangeArrivingAfterImportSurvivesCorrectionAndIsImportedOnce() {
        var impulses = new PredictionImpulses();
        var firstPush = new MovementVector(0, 4, 0);
        var laterPush = new MovementVector(2, 0, 0);
        var entityVelocity = impulses.written(new MovementVector(4, 0, 0));
        entityVelocity = entityVelocity.add(firstPush.scale(1.0 / 20));
        assertEquals(firstPush, impulses.capture(entityVelocity));
        entityVelocity = entityVelocity.add(laterPush.scale(1.0 / 20));

        var unimported = impulses.unimported(entityVelocity);
        assertVectorEquals(laterPush.scale(1.0 / 20), unimported);
        var corrected = new MovementVector(1, 2, 3).add(impulses.pending());
        entityVelocity = impulses.written(corrected).add(unimported);
        var captured = impulses.capture(entityVelocity);
        assertEquals(laterPush.x(), captured.x(), 1.0E-12);
        assertEquals(laterPush.y(), captured.y(), 1.0E-12);
        assertEquals(laterPush.z(), captured.z(), 1.0E-12);
        assertEquals(firstPush.add(captured), impulses.consume());
        assertEquals(MovementVector.ZERO, impulses.capture(entityVelocity));

        impulses.clear();
        assertEquals(MovementVector.ZERO, impulses.pending());
        assertEquals(MovementVector.ZERO, impulses.capture(impulses.written(MovementVector.ZERO)));
    }

    private static void assertVectorEquals(MovementVector expected, MovementVector actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-12);
        assertEquals(expected.y(), actual.y(), 1.0E-12);
        assertEquals(expected.z(), actual.z(), 1.0E-12);
    }
}
