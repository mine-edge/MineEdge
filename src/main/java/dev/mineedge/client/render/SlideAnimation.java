package dev.mineedge.client.render;

import net.minecraft.util.Mth;

/** Frame-clock slide blending shared by every model/layer pass for a player. */
public final class SlideAnimation {
    public record Shot(float weight, float yaw) {}

    private Shot shot = new Shot(0, 0);
    private boolean sliding;
    private float targetYaw;
    private long received, sampled;

    public void record(boolean active, float yaw, long now) {
        sliding = active;
        received = now;
        if (active) {
            targetYaw = yaw;
            if (shot.weight() == 0) shot = new Shot(0, yaw);
        }
    }

    public Shot sample(long frame, boolean paused) {
        if (sampled == frame) return shot;
        double dt = sampled == 0 ? 1.0 / 60 : Math.clamp((frame - sampled) / 1.0E9, 0, 0.1);
        sampled = frame;
        if (paused) return shot;
        // Lost tracking updates must not leave an observer stuck in a slide.
        boolean active = sliding && frame - received < 1_000_000_000L;
        if (!active && shot.weight() == 0) return shot;
        float alpha = (float) (1 - Math.exp(-dt / (active ? 0.045 : 0.09)));
        float weight = Mth.lerp(alpha, shot.weight(), active ? 1 : 0);
        if (weight < 0.001F) weight = 0;
        else if (weight > 0.999F) weight = 1;
        float yaw = Mth.rotLerp((float) (1 - Math.exp(-dt / 0.045)), shot.yaw(), targetYaw);
        return shot = new Shot(weight, Mth.wrapDegrees(yaw));
    }
}
