package dev.mineedge.client.movement;

import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapePresentationTest {
    private static final long INTERVAL = MovementRate.INTERVAL_NANOS;

    @Test void dragTracksTheModelTimelineAcrossIndependentNativeTickBoundaries() {
        var cape = new CapePresentation();
        var model = new SimulationInterpolation();
        var motion = new MovementVector(0.2, 0.1, -0.3);
        cape.reset(MovementVector.ZERO, 0);
        model.reset(MovementVector.ZERO, 0);
        cape.step(motion, INTERVAL);
        model.record(motion, INTERVAL);
        for (int frame = 0; frame <= 100; frame++) {
            long now = INTERVAL + INTERVAL * frame / 100;
            double progress = model.position(now).x() / motion.x();
            var offset = cape.sample(now);
            assertEquals(-0.15 * progress, offset.x(), 1.0E-9);
            assertEquals(-0.075 * progress, offset.y(), 1.0E-9);
            assertEquals(0.225 * progress, offset.z(), 1.0E-9);
            // Repeated render passes must not advance the animation.
            assertEquals(offset, cape.sample(now));
        }
    }

    @Test void stoppingLetsTheCapeSettleInsteadOfFreezingItsDrag() {
        var cape = new CapePresentation();
        var position = new MovementVector(0, 0, 0.5);
        cape.reset(MovementVector.ZERO, 0);
        cape.step(position, INTERVAL);
        double drag = cape.sample(2 * INTERVAL).length();
        assertTrue(drag > 0);
        for (int step = 2; step <= 30; step++) cape.step(position, step * INTERVAL);
        assertTrue(cape.sample(31 * INTERVAL).length() < drag * 0.001);
    }

    @Test void correctionsMoveTheReferenceWithoutCreatingCapeImpulse() {
        var corrected = new CapePresentation();
        var control = new CapePresentation();
        var position = new MovementVector(0.2, 0, 0);
        corrected.reset(MovementVector.ZERO, 0);
        control.reset(MovementVector.ZERO, 0);
        corrected.step(position, INTERVAL);
        control.step(position, INTERVAL);
        var correction = new MovementVector(-0.3, 0.2, 0.1);
        corrected.translate(correction);
        assertEquals(control.sample(2 * INTERVAL), corrected.sample(2 * INTERVAL));
        corrected.step(position.add(correction), 2 * INTERVAL);
        control.step(position, 2 * INTERVAL);
        assertEquals(control.sample(3 * INTERVAL).x(), corrected.sample(3 * INTERVAL).x(), 1.0E-9);
        assertEquals(0, corrected.sample(3 * INTERVAL).y(), 1.0E-9);
        assertEquals(0, corrected.sample(3 * INTERVAL).z(), 1.0E-9);
    }

    @Test void teleportsAndSessionResetsDiscardOldDragAndClearReleasesTheOverride() {
        var cape = new CapePresentation();
        cape.reset(MovementVector.ZERO, 0);
        cape.step(new MovementVector(0, 0, 0.5), INTERVAL);
        cape.step(new MovementVector(-100, 40, -100), 2 * INTERVAL);
        assertEquals(MovementVector.ZERO, cape.sample(2 * INTERVAL));
        cape.step(new MovementVector(-100, 40, -99.5), 3 * INTERVAL);
        assertTrue(cape.sample(4 * INTERVAL).length() > 0);
        cape.reset(new MovementVector(10, 50, 20), 4 * INTERVAL);
        assertEquals(MovementVector.ZERO, cape.sample(4 * INTERVAL));
        cape.clear();
        assertNull(cape.sample(5 * INTERVAL));
    }
}
