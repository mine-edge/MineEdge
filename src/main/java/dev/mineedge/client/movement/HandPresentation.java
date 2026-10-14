package dev.mineedge.client.movement;

import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementVector;

/** Render-time movement animation published through vanilla player fields for modded renderers too. */
public final class HandPresentation {
    private static final double LOOK_RESPONSE = -Math.log(0.5) * MovementRate.GAME_TICK_HZ;
    private static final double BOB_RESPONSE = -Math.log(0.6) * MovementRate.GAME_TICK_HZ;
    private final SimulationInterpolation walking = new SimulationInterpolation();
    private double pitch, yaw, bob;
    private long lastFrame;
    private Shot shot;

    public record Shot(float walkDistance, float bob, float pitchLag, float yawLag) {}

    public void record(float walkDistance, long now) {
        walking.record(new MovementVector(walkDistance, 0, 0), now);
    }

    public void reset(float walkDistance, float pitch, float yaw, long now) {
        walking.reset(new MovementVector(walkDistance, 0, 0), now);
        this.pitch = pitch;
        this.yaw = yaw;
        bob = 0;
        lastFrame = now;
        shot = new Shot(walkDistance, 0, 0, 0);
    }

    public Shot update(long now, float pitch, float yaw, double speed, boolean grounded, boolean paused) {
        return update(now, pitch, yaw, speed, grounded, paused, MovementRate.INTERVAL_NANOS);
    }

    public Shot update(long now, float pitch, float yaw, double speed, boolean grounded, boolean paused, long intervalNanos) {
        if (shot == null) return null;
        double seconds = Math.max(0, (now - lastFrame) / 1.0E9);
        lastFrame = now;
        if (paused) return shot;
        double lookAlpha = 1 - Math.exp(-LOOK_RESPONSE * seconds);
        this.pitch += (pitch - this.pitch) * lookAlpha;
        // Crossing +/-180 degrees must not send the hand swinging through a full turn.
        this.yaw = yaw - wrapped(yaw - this.yaw) * (1 - lookAlpha);
        double targetBob = grounded ? Math.min(0.1, speed / MovementRate.GAME_TICK_HZ) : 0;
        bob += (targetBob - bob) * (1 - Math.exp(-BOB_RESPONSE * seconds));
        var position = walking.position(now, intervalNanos);
        shot = new Shot(position == null ? shot.walkDistance() : (float) position.x(), (float) bob,
                (float) (pitch - this.pitch), (float) wrapped(yaw - this.yaw));
        return shot;
    }

    private static double wrapped(double degrees) { return ((degrees % 360) + 540) % 360 - 180; }

    public void clear() {
        walking.clear();
        shot = null;
        pitch = yaw = bob = 0;
        lastFrame = 0;
    }
}
