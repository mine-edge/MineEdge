package dev.mineedge.client.render;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Render-time smoothing in hip-relative model space; never lags the player's world position. */
final class LegAnimation {
    private static final double RESPONSE_SECONDS = 0.09;
    private static final long RESET_GAP_NANOS = 250_000_000L;
    private final Vector3f offset = new Vector3f();
    private Quaternionf rotation;
    private long sampled;

    RigidLimbIk.Pose sample(Vector3f hip, Quaternionf vanilla, RigidLimbIk.Pose target,
                           float maxShift, long frame, boolean paused) {
        if (rotation == null || frame < sampled || !paused && frame - sampled > RESET_GAP_NANOS) {
            offset.zero();
            rotation = new Quaternionf(vanilla);
            sampled = frame;
        }
        if (frame != sampled) {
            double dt = Math.clamp((frame - sampled) / 1.0E9, 0, 0.1);
            sampled = frame;
            // Released contacts no longer describe a foot target. Hold the last
            // hip-relative pose while the caller fades it into current vanilla animation.
            if (!paused && target != null) {
                float alpha = (float) -Math.expm1(-dt / RESPONSE_SECONDS);
                offset.lerp(new Vector3f(target.pivot()).sub(hip), alpha);
                rotation.slerp(target.rotation(), alpha).normalize();
            }
        }
        // Preserve the connected-hip limit even when another mod changes model scale.
        float length = offset.length();
        if (length > maxShift) offset.mul(maxShift / length);
        return new RigidLimbIk.Pose(new Vector3f(hip).add(offset), new Quaternionf(rotation));
    }

    void reset() {
        offset.zero();
        rotation = null;
    }
}
