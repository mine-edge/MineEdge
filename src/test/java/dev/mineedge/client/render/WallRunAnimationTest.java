package dev.mineedge.client.render;

import dev.mineedge.movement.WallRunPose;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WallRunAnimationTest {
    private static final long START = 2_000_000_000L;

    @Test void airborneStrideKeepsMovingAndIsIndependentOfFrameRateAndLayerCount() {
        WallRunAnimation.Shot reference = null;
        for (int fps : new int[]{30, 60, 144}) {
            var animation = new WallRunAnimation();
            animation.sample(START, false);
            var pose = new WallRunPose(90, 1, 5.6F);
            animation.record(pose, START);
            var entered = animation.sample(START, false);
            for (int i = 1; i <= fps / 2; i++) {
                long frame = START + Math.round(i * 1.0E9 / fps);
                animation.record(pose, frame);
                var shot = animation.sample(frame, false);
                assertSame(shot, animation.sample(frame, false), "Equipment layers advanced the stride twice");
            }
            var result = animation.sample(START + 500_000_000L, false);
            assertEquals(1, result.weight());
            assertNotEquals(entered.phase(), animation.sample(START + 510_000_000L, false).phase());
            if (reference != null) {
                assertEquals(reference.weight(), result.weight(), 0.001);
                assertEquals(0, Math.IEEEremainder(reference.phase() - result.phase(), Math.PI * 2), 0.0001);
            }
            reference = result;
        }
    }

    @Test void wallChangesBlendAndYawSeamsTakeTheShortPath() {
        var animation = new WallRunAnimation();
        animation.record(new WallRunPose(179, 1, 4), START);
        var before = animation.sample(START, false);
        animation.record(new WallRunPose(-179, -1, 4), START + 10_000_000L);
        var changed = animation.sample(START + 10_000_000L, false);
        assertTrue(Math.abs(Mth.wrapDegrees(changed.yaw() - before.yaw())) < 2);
        assertTrue(changed.side() > -1 && changed.side() < 1);
        assertTrue(changed.phase() > before.phase(), "A new wall reset the running stride");
        animation.record(WallRunPose.EMPTY, START + 20_000_000L);
        var released = animation.sample(START + 20_000_000L, false);
        assertTrue(Math.abs(released.yaw()) > 178);
        assertTrue(released.weight() < changed.weight());
    }

    @Test void pauseFreezesTheStrideAndLostTrackingReleasesThePose() {
        var animation = new WallRunAnimation();
        animation.record(new WallRunPose(45, -1, 4), START);
        var before = animation.sample(START, false);
        assertSame(before, animation.sample(START + 5_000_000_000L, true));
        animation.record(new WallRunPose(45, -1, 4), START + 5_000_000_000L);
        var resumed = animation.sample(START + 5_010_000_000L, false);
        assertEquals(0.01 * 4 / 2.8 * Math.PI * 2, resumed.phase() - before.phase(), 1.0E-6);
        for (int i = 1; i <= 180; i++) animation.sample(START + 5_010_000_000L + i * 10_000_000L, false);
        assertEquals(0, animation.sample(START + 7_000_000_000L, false).weight());
    }
}
