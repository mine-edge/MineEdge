package dev.mineedge.client.movement;

import dev.mineedge.client.camera.CorrectionSmoothing;
import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationInterpolationTest {
    private static final long MS = 1_000_000L;
    private static MovementVector at(double x) { return new MovementVector(x, 0, 0); }

    @Test void scaledSimulationIntervalsRenderContinuouslyAtSlowAndFastTickRates() {
        for (float tickRate : new float[] {1, 5, 20, 80}) {
            var interpolation = new SimulationInterpolation();
            long interval = MovementRate.intervalNanos(tickRate);
            interpolation.reset(at(0), 0);
            for (int frame = 0; frame <= 160; frame++) {
                long now = frame * interval / 4;
                if (frame > 0 && frame % 4 == 0) interpolation.record(at(frame * 0.05), now);
                assertEquals(Math.max(0, frame - 4) * 0.05, interpolation.position(now, interval).x(), 1.0E-12);
            }
        }
    }

    @Test void twentyHertzSnapshotsRenderContinuouslyAcrossNativeTickBoundaries() {
        var interpolation = new SimulationInterpolation();
        interpolation.reset(at(0), 0);
        for (int frame = 0; frame <= 200; frame++) {
            if (frame > 0 && frame % 50 == 0) interpolation.record(at(frame * 0.004), frame * MS);
            // A 4 blocks/second trajectory must not snap when 20 Hz vanilla endpoints reset.
            assertEquals(Math.max(0, frame - 50) * 0.004, interpolation.position(frame * MS).x(), 1.0E-12);
        }
        assertEquals(50 * MS, MovementRate.INTERVAL_NANOS);
    }

    @Test void bufferedSnapshotsPreserveInterpolationWhenTheNextStepCompletesEarly() {
        var interpolation = new SimulationInterpolation();
        interpolation.record(at(0), 0);
        interpolation.record(at(0.208), 52 * MS);
        var before = interpolation.position(94 * MS);
        interpolation.record(at(0.376), 94 * MS);
        // Retaining more than two snapshots prevents an early completion from discarding
        // the pair which brackets the delayed render time and jumping to the latest pair.
        assertEquals(before, interpolation.position(94 * MS));
        assertEquals(0.004 * 45, interpolation.position(95 * MS).x(), 1.0E-12);
        assertEquals(0.004 * 55, interpolation.position(105 * MS).x(), 1.0E-12);
    }

    @Test void stallsAndCollisionsNeverExtrapolateBeyondTheLastCompletedPosition() {
        var interpolation = new SimulationInterpolation();
        interpolation.record(at(0), 0);
        interpolation.record(at(0.1), 25 * MS);
        assertEquals(at(0.1), interpolation.position(1_000 * MS));
        interpolation.record(at(0.1), 1_000 * MS);
        assertEquals(at(0.1), interpolation.position(1_010 * MS));
    }

    @Test void smallCorrectionsTranslateTheHistoryWithoutDoublingCameraSmoothing() {
        var interpolation = new SimulationInterpolation();
        var smoothing = new CorrectionSmoothing();
        interpolation.record(at(0), 0);
        interpolation.record(at(0.1), 25 * MS);
        interpolation.record(at(0.2), 50 * MS);
        var before = interpolation.position(55 * MS);
        var correction = new MovementVector(0.25, -0.1, 0.4);
        interpolation.translate(correction);
        smoothing.correct(correction);
        var displayed = interpolation.position(55 * MS).add(smoothing.offset());
        assertTrue(displayed.subtract(before).length() < 1.0E-12);
    }

    @Test void teleportsResetsAndClockRewindsDiscardTheOldTrajectory() {
        var interpolation = new SimulationInterpolation();
        assertNull(interpolation.position(0));
        interpolation.record(at(0), 0);
        interpolation.record(at(0.1), 25 * MS);
        interpolation.reset(at(1_000), 30 * MS);
        assertEquals(at(1_000), interpolation.position(30 * MS));
        assertEquals(at(1_000), interpolation.position(35 * MS));
        interpolation.record(at(5), 0);
        assertEquals(at(5), interpolation.position(0));
        interpolation.clear();
        assertNull(interpolation.position(100 * MS));
    }
}
