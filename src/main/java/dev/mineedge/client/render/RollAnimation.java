package dev.mineedge.client.render;

import dev.mineedge.movement.RollPose;
import dev.mineedge.client.movement.RollPresentation;

/** One render-clock revolution, shared by the model and all equipment passes. */
public final class RollAnimation {
    public record Shot(float weight, float yaw, float angle) {
        public static final Shot NONE = new Shot(0, 0, 0);
    }

    private Shot shot = Shot.NONE;
    private boolean rolling;
    private double elapsed = -1;
    private double proneRecovery;
    private float yaw;
    private long clock, proneClock, sampled = Long.MIN_VALUE;

    public void record(RollPose pose, long now) {
        // Vanilla's swim amount can take half a second to decay after leaving the low
        // collision pose. Suppress that unrelated prone rotation through its recovery.
        if (pose.active()) {
            proneRecovery = 0.6;
            proneClock = now;
        }
        if (pose.active() && !rolling) {
            elapsed = pose.elapsed();
            yaw = pose.yaw();
            clock = now;
            sampled = Long.MIN_VALUE;
        }
        // Repeated tracking updates must never restart a completed revolution.
        rolling = pose.active();
    }

    public Shot sample(long frame, boolean paused) {
        if (sampled == frame) return shot;
        sampled = frame;
        double dt = Math.max(0, (frame - clock) / 1.0E9);
        double proneDt = Math.max(0, (frame - proneClock) / 1.0E9);
        clock = Math.max(clock, frame);
        proneClock = Math.max(proneClock, frame);
        if (paused) return shot;
        proneRecovery = Math.max(0, proneRecovery - proneDt);
        if (elapsed < 0) return shot = Shot.NONE;
        elapsed += dt;
        if (elapsed + 1.0E-9 >= RollPresentation.DURATION) {
            elapsed = -1;
            return shot = Shot.NONE;
        }
        return shot = at((float) (elapsed / RollPresentation.DURATION), yaw);
    }

    public boolean suppressesProne() { return elapsed >= 0 || proneRecovery > 0; }

    static Shot at(float progress, float yaw) {
        progress = Math.clamp(progress, 0, 1);
        float tuck = ease(Math.min(progress / 0.18F, (1 - progress) / 0.18F));
        return new Shot(tuck, yaw, (float) (Math.PI * 2 * RollPresentation.turn(progress)));
    }

    private static float ease(float value) {
        value = Math.clamp(value, 0, 1);
        return value * value * value * (value * (value * 6 - 15) + 10);
    }
}
