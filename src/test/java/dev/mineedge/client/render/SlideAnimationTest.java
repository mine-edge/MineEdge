package dev.mineedge.client.render;

import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlideAnimationTest {
    private static final long START = 2_000_000_000L;

    @Test void entryAndRecoveryAreFrameRateIndependentAndShareOneSamplePerFrame() {
        float[] entered = new float[3], released = new float[3];
        int index = 0;
        for (int fps : new int[]{30, 60, 144}) {
            var animation = new SlideAnimation();
            animation.sample(START, false);
            animation.record(true, 90, START);
            for (int i = 1; i <= fps / 2; i++) {
                long frame = START + Math.round(i * 1.0E9 / fps);
                var shot = animation.sample(frame, false);
                assertSame(shot, animation.sample(frame, false));
                assertTrue(shot.weight() > 0 && shot.weight() <= 1);
            }
            entered[index] = animation.sample(START + 500_000_000L, false).weight();
            animation.record(false, 0, START + 500_000_000L);
            for (int i = 1; i <= fps / 5; i++) animation.sample(START + 500_000_000L + Math.round(i * 1.0E9 / fps), false);
            released[index++] = animation.sample(START + 700_000_000L, false).weight();
        }
        assertEquals(1, entered[0]);
        for (int i = 1; i < entered.length; i++) {
            assertEquals(entered[0], entered[i], 0.001);
            assertEquals(released[0], released[i], 0.001);
        }
        assertTrue(released[0] > 0 && released[0] < 0.15F);
    }

    @Test void turningAcrossTheYawSeamTakesTheShortPathAndExitKeepsSlideDirection() {
        var animation = new SlideAnimation();
        animation.record(true, 179, START);
        animation.sample(START, false);
        animation.record(true, -179, START + 20_000_000L);
        var turn = animation.sample(START + 20_000_000L, false);
        assertTrue(Math.abs(Mth.wrapDegrees(turn.yaw() - 179)) < 2);
        assertTrue(Math.abs(turn.yaw()) > 178);
        animation.record(false, 0, START + 40_000_000L);
        var exit = animation.sample(START + 40_000_000L, false);
        assertTrue(Math.abs(exit.yaw()) > 178, "Release must not rotate the body toward an inactive packet's zero yaw");
    }

    @Test void pauseFreezesThePoseAndResumeDoesNotConsumePausedTime() {
        var animation = new SlideAnimation();
        animation.record(true, 45, START);
        var before = animation.sample(START, false);
        animation.record(false, 0, START + 10_000_000L);
        assertSame(before, animation.sample(START + 5_000_000_000L, true));
        var resumed = animation.sample(START + 5_010_000_000L, false);
        assertEquals(before.weight() * Math.exp(-0.01 / 0.09), resumed.weight(), 1.0E-6);
    }

    @Test void missingObserverUpdatesReleaseThePose() {
        var animation = new SlideAnimation();
        animation.record(true, 30, START);
        for (int i = 0; i < 150; i++) animation.sample(START + i * 10_000_000L, false);
        assertTrue(animation.sample(START + 1_500_000_000L, false).weight() < 0.005F);
        animation.sample(START + 1_600_000_000L, false);
        assertEquals(0, animation.sample(START + 1_700_000_000L, false).weight());
    }
}
