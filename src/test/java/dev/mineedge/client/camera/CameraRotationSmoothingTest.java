package dev.mineedge.client.camera;

import dev.mineedge.movement.AdvancedState;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfaceState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraRotationSmoothingTest {
    private static final long FRAME = 5_000_000;

    @Test
    void exactHalfTurnsEaseInBothDirectionsWithoutOvershooting() {
        for (float direction : new float[]{-1, 1}) {
            var smoothing = new CameraRotationSmoothing();
            smoothing.update(0, false, 0, false);
            assertEquals(0, smoothing.update(180 * direction, true, 0, false));
            float previous = 0;
            for (int frame = 1; frame <= 100; frame++) {
                float yaw = smoothing.update(180 * direction, true, frame * FRAME, false);
                assertTrue((yaw - previous) * direction >= 0);
                assertTrue(yaw * direction <= 180);
                previous = yaw;
            }
            assertEquals(180 * direction, previous, 1.0E-3);
        }
    }

    @Test
    void scriptedTwentyHertzTurnHasContinuousRenderFrameRotation() {
        var smoothing = new CameraRotationSmoothing();
        smoothing.update(179, false, 0, false);
        float previous = 179;
        for (int frame = 1; frame <= 200; frame++) {
            long now = frame * FRAME;
            double elapsed = Math.min(0.2, (now / 50_000_000) * 0.05);
            float target = new AdvancedState.Turn(179, 180, elapsed, 0.2).yaw();
            float yaw = smoothing.update(target, now <= 250_000_000, now, false);
            assertTrue(yaw >= previous, "The half-turn reversed at the yaw wrap");
            assertTrue(yaw - previous < 10, "The render camera stepped with the simulation yaw");
            previous = yaw;
        }
        assertEquals(359, previous, 1.0E-3);
    }

    @Test
    void attachmentAndMovingPipeLimitsEaseInsteadOfSnapping() {
        var smoothing = new CameraRotationSmoothing();
        var pipe = pipe(0);
        smoothing.update(120, false, 0, false);
        float limited = CameraLookLimits.yaw(pipe, 120);
        float yaw = smoothing.update(limited, CameraLookLimits.restricted(pipe), FRAME, false);
        assertTrue(yaw > limited && yaw < 120, "Attachment snapped immediately to the new limit");
        assertEquals(90, smoothing.update(limited, true, 500_000_000, false), 1.0E-3);

        float previous = 90;
        for (int frame = 1; frame <= 200; frame++) {
            if (frame % 10 == 0) limited = CameraLookLimits.yaw(pipe(-frame * 0.36F), limited);
            yaw = smoothing.update(limited, true, 500_000_000 + frame * FRAME, false);
            assertTrue(previous - yaw < 1, "A new pipe normal snapped the camera at a simulation boundary");
            previous = yaw;
        }
        assertEquals(18, limited, 1.0E-4);
        assertEquals(limited, smoothing.update(limited, true, 2_000_000_000, false), 1.0E-3);
    }

    @Test
    void restrictedYawWrapTakesTheShortPath() {
        var smoothing = new CameraRotationSmoothing();
        smoothing.update(179, true, 0, false);
        float yaw = smoothing.update(-179, true, FRAME, false);
        // The returned revolution follows the entity, but its physical angle barely moves.
        assertTrue(yaw + 360 > 179 && yaw + 360 < 181);
        assertEquals(-179, smoothing.update(-179, true, 500_000_000, false), 1.0E-3);
    }

    @Test
    void freeMouseLookIsImmediateEvenWhileAForcedRotationSettles() {
        var smoothing = new CameraRotationSmoothing();
        assertEquals(10, smoothing.update(10, false, 0, false));
        assertEquals(60, smoothing.update(60, false, FRAME, false));
        assertEquals(-179, smoothing.update(-179, false, 2 * FRAME, false));
        assertEquals(179, smoothing.update(179, false, 3 * FRAME, false));

        float before = smoothing.update(269, true, 4 * FRAME, false);
        float after = smoothing.update(284, false, 4 * FRAME, false);
        assertEquals(15, after - before, 1.0E-4, "Releasing a climb delayed normal mouse input");
        assertEquals(284, smoothing.update(284, false, 1_000_000_000, false), 1.0E-3);
    }

    @Test
    void pausesFreezeRotationAndResetStartsAtTheNewView() {
        var smoothing = new CameraRotationSmoothing();
        smoothing.update(0, false, 0, false);
        float before = smoothing.update(90, true, FRAME, false);
        assertEquals(before, smoothing.update(90, true, 5_000_000_000L, true));
        float resumed = smoothing.update(90, true, 5_000_000_000L + FRAME, false);
        assertTrue(resumed > before && resumed < 10, "Paused time was integrated on resume");
        smoothing.reset();
        assertEquals(-120, smoothing.update(-120, true, 6_000_000_000L, false));
    }

    @Test
    void springResponseIsIndependentOfRenderFrequency() {
        var slow = new CameraRotationSmoothing();
        var fast = new CameraRotationSmoothing();
        slow.update(0, false, 0, false);
        fast.update(0, false, 0, false);
        slow.update(90, true, 0, false);
        fast.update(90, true, 0, false);
        float slowYaw = 0, fastYaw = 0;
        for (int frame = 1; frame <= 10; frame++) slowYaw = slow.update(90, true, frame * 16_000_000L, false);
        for (int frame = 1; frame <= 40; frame++) fastYaw = fast.update(90, true, frame * 4_000_000L, false);
        assertEquals(slowYaw, fastYaw, 1.0E-4);
    }

    private static MovementState pipe(float centerYaw) {
        double angle = Math.toRadians(centerYaw);
        var normal = new MovementVector(Math.sin(angle), 0, -Math.cos(angle));
        var route = new ClimbRoute(MovementVector.ZERO, 5, normal, true, MovementVector.ZERO);
        var grip = new SurfaceState.Climb(route, route.feet(2), 0, 2, 2, 0, 0, false);
        var state = new MovementState(route.feet(2), MovementVector.ZERO, MovementState.Mode.PIPE_CLIMB, 0, false, 0);
        return new MovementState(state.position(), state.velocity(), state.mode(), state.modeTime(), state.grounded(),
                state.fallDistance(), state.locomotion(), state.blocks(), state.body(), state.slideYaw(), state.traversal(),
                state.wall(), state.landing(), state.advanced(), SurfaceState.EMPTY.climbing(grip, null, MovementVector.ZERO));
    }
}
