package dev.mineedge.client.render;

import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.RollPose;
import dev.mineedge.client.movement.RollPresentation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollAnimationTest {
    private static final long START = 2_000_000_000L;

    @Test void oneForwardRevolutionIsIndependentOfFrameRateRepeatedUpdatesAndLayerCount() {
        for (int fps : new int[]{30, 60, 144}) {
            var animation = new RollAnimation();
            animation.record(new RollPose(90, 0), START);
            float previous = 0;
            for (int frame = 0; frame < fps * RollPresentation.DURATION; frame++) {
                long now = START + Math.round(frame * 1.0E9 / fps);
                animation.record(new RollPose(90, Math.min(0.49F, (float) frame / fps)), now);
                var shot = animation.sample(now, false);
                assertSame(shot, animation.sample(now, false), "Equipment layers advanced the rotation twice");
                assertTrue(shot.angle() >= previous);
                assertEquals(90, shot.yaw());
                if (frame * 5 == fps * 2) {
                    assertEquals(Math.PI, shot.angle(), 1.0E-6);
                    assertEquals(1, shot.weight());
                }
                previous = shot.angle();
            }
            assertTrue(previous > Math.PI * 1.99);
            assertSame(RollAnimation.Shot.NONE, animation.sample(START + 800_000_000L, false));
            animation.record(new RollPose(90, 0.45F), START + 900_000_000L);
            assertSame(RollAnimation.Shot.NONE, animation.sample(START + 900_000_000L, false));
        }
    }

    @Test void lateTrackingStartsAtTheCurrentPhaseAndInterruptedRollsFinishBeforeRecovery() {
        var animation = new RollAnimation();
        animation.record(new RollPose(-45, 0.4F), START);
        assertEquals(Math.PI, animation.sample(START, false).angle(), 1.0E-6);
        animation.record(RollPose.EMPTY, START + 10_000_000L);
        assertTrue(animation.sample(START + 100_000_000L, false).angle() > Math.PI);
        assertSame(RollAnimation.Shot.NONE, animation.sample(START + 400_000_000L, false));
        animation.record(new RollPose(135, 0), START + 450_000_000L);
        assertEquals(0, animation.sample(START + 450_000_000L, false).angle());
        assertEquals(135, animation.sample(START + 550_000_000L, false).yaw());
    }

    @Test void pauseFreezesThePoseAndHitchesOrMissingReleasePacketsCannotLeaveItStuck() {
        var animation = new RollAnimation();
        animation.record(new RollPose(0, 0), START);
        var before = animation.sample(START + 100_000_000L, false);
        assertSame(before, animation.sample(START + 5_100_000_000L, true));
        var resumed = animation.sample(START + 5_110_000_000L, false);
        assertEquals(RollAnimation.at(0.1375F, 0), resumed);
        assertSame(RollAnimation.Shot.NONE, animation.sample(START + 6_000_000_000L, false));
    }

    @Test void simulationRecordedAfterTheFrameSampleDoesNotAdvanceTheRollTwice() {
        var animation = new RollAnimation();
        animation.record(new RollPose(0, 0), START + 10_000_000L);
        assertEquals(0, animation.sample(START, false).angle());
        assertEquals(RollAnimation.at(0.0125F, 0), animation.sample(START + 20_000_000L, false));
    }

    @Test void slowerTurnCapsAngularSpeedAndSuppressesNativeProneThroughRecovery() {
        var animation = new RollAnimation();
        animation.record(new RollPose(0, 0), START);
        float previous = 0;
        for (int frame = 0; frame < 96; frame++) {
            long now = START + Math.round(frame * 1.0E9 / 120);
            if (frame < 60) animation.record(new RollPose(0, frame / 120.0F), now);
            else animation.record(RollPose.EMPTY, now);
            var shot = animation.sample(now, false);
            assertTrue(Math.toDegrees(shot.angle() - previous) * 120 <= 563,
                    "The revolution still accelerates into an excessive mid-roll burst");
            previous = shot.angle();
            assertTrue(animation.suppressesProne(), "Vanilla's prone tilt added an extra rotation");
        }
        assertSame(RollAnimation.Shot.NONE, animation.sample(START + 800_000_000L, false));
        assertTrue(animation.suppressesProne(), "The residual low-pose tilt returned after the turn");
        animation.sample(START + 1_100_000_000L, false);
        assertFalse(animation.suppressesProne());
    }

    @Test void fallRollFacingUsesMovementRatherThanLookAndBlockedRollsStillHaveAPose() {
        var rolling = new MovementState(MovementVector.ZERO, new MovementVector(4, 0, 0), MovementState.Mode.ROLL, 0.25, true, 0);
        assertEquals(new RollPose(-90, 0.25F), RollPose.sample(rolling));
        assertTrue(RollPose.sample(rolling.withVelocity(MovementVector.ZERO)).active());
        assertSame(RollPose.EMPTY, RollPose.sample(new MovementState(MovementVector.ZERO, MovementVector.ZERO,
                MovementState.Mode.SLIDE, 0.25, true, 0)));
    }
}
