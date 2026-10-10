package dev.mineedge.client.render;

import dev.mineedge.movement.WallRunPose;
import net.minecraft.util.Mth;

/** One frame-clock wallrun sample for the body, limbs and equipment layers. */
public final class WallRunAnimation {
    public record Shot(float weight, float yaw, float side, float phase) {}

    private Shot shot = new Shot(0, 0, 0, 0);
    private WallRunPose target = WallRunPose.EMPTY;
    private long received, sampled;
    private float yaw, side, speed;

    public void record(WallRunPose pose, long now) {
        target = pose;
        received = now;
        if (pose.active()) {
            yaw = pose.yaw();
            side = pose.side();
            speed = pose.speed();
            if (shot.weight() == 0) shot = new Shot(0, yaw, side, 0);
        }
    }

    public Shot sample(long frame, boolean paused) {
        if (sampled == frame) return shot;
        double dt = sampled == 0 ? 1.0 / 60 : Math.clamp((frame - sampled) / 1.0E9, 0, 0.1);
        sampled = frame;
        if (paused) return shot;
        boolean active = target.active() && frame - received < 1_000_000_000L;
        if (!active && shot.weight() == 0) return shot;
        float weight = Mth.lerp((float) (1 - Math.exp(-dt / (active ? 0.055 : 0.10))), shot.weight(), active ? 1 : 0);
        if (weight < 0.001F) weight = 0;
        else if (weight > 0.999F) weight = 1;
        float alpha = (float) (1 - Math.exp(-dt / 0.055));
        float facing = Mth.wrapDegrees(Mth.rotLerp(alpha, shot.yaw(), yaw));
        float wallSide = Mth.lerp(alpha, shot.side(), side);
        // Keep a stride alive during release; Minecraft's airborne walk cycle otherwise freezes.
        float phase = (float) ((shot.phase() + dt * Math.clamp(speed / 2.8, 0.8, 2.8) * Math.PI * 2) % (Math.PI * 2));
        return shot = new Shot(weight, facing, wallSide, phase);
    }
}
