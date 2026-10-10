package dev.mineedge.client.camera;

import dev.mineedge.movement.MovementVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CorrectionSmoothingTest {
    @Test
    void successiveCorrectionsPreserveRenderedPositionWhilePhysicsMovesImmediately() {
        var smoothing = new CorrectionSmoothing();
        var position = new MovementVector(3, 2, 1);
        for (var displacement : new MovementVector[]{new MovementVector(0.2, -0.1, 0.05),
                new MovementVector(-0.1, 0.05, 0.1)}) {
            var renderedBefore = position.add(smoothing.offset());
            position = position.add(displacement);
            smoothing.correct(displacement);
            assertTrue(renderedBefore.subtract(position.add(smoothing.offset())).length() < 1.0E-12);
            smoothing.update(1.0 / 60);
        }
        smoothing.update(1);
        assertEquals(MovementVector.ZERO, smoothing.offset());
    }

    @Test
    void decayIsFrameRateIndependentAndDoesNotOvershoot() {
        var lowFps = new CorrectionSmoothing();
        var highFps = new CorrectionSmoothing();
        var displacement = new MovementVector(0.3, -0.2, 0.1);
        lowFps.correct(displacement);
        highFps.correct(displacement);
        for (int frame = 0; frame < 6; frame++) lowFps.update(1.0 / 30);
        for (int frame = 0; frame < 48; frame++) highFps.update(1.0 / 240);
        assertTrue(lowFps.offset().subtract(highFps.offset()).length() < 1.0E-12);
        assertTrue(lowFps.offset().length() < displacement.length() * 0.03);
        assertTrue(lowFps.offset().dot(displacement) < 0);
        var before = lowFps.offset();
        lowFps.update(0);
        assertEquals(before, lowFps.offset());
    }

    @Test
    void largeAndAccumulatedCorrectionsAndLifecycleResetSnapImmediately() {
        var smoothing = new CorrectionSmoothing();
        smoothing.correct(new MovementVector(0.6, 0, 0));
        smoothing.correct(new MovementVector(0.6, 0, 0));
        assertEquals(MovementVector.ZERO, smoothing.offset());
        smoothing.correct(new MovementVector(0.5, 0, 0));
        smoothing.correct(new MovementVector(-1.2, 0, 0));
        assertEquals(MovementVector.ZERO, smoothing.offset());
        smoothing.correct(new MovementVector(0, 0.2, 0));
        smoothing.reset();
        assertEquals(MovementVector.ZERO, smoothing.offset());
    }
}
