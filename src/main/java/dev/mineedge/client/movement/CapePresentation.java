package dev.mineedge.client.movement;

import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementVector;

/** Native cape drag sampled on the same simulation timeline as the local player model. */
public final class CapePresentation {
    private static final double KEEP = Math.pow(0.75, MovementRate.STEP_SECONDS * MovementRate.GAME_TICK_HZ);
    private final SimulationInterpolation offsets = new SimulationInterpolation();
    private MovementVector position;
    private MovementVector offset = MovementVector.ZERO;

    public void step(MovementVector position, long now) {
        if (this.position == null) {
            reset(position, now);
            return;
        }
        var lag = offset.subtract(position.subtract(this.position));
        if (Math.abs(lag.x()) > 10 || Math.abs(lag.y()) > 10 || Math.abs(lag.z()) > 10) {
            reset(position, now);
            return;
        }
        this.position = position;
        offset = lag.scale(KEEP);
        offsets.record(offset, now);
    }

    public MovementVector sample(long now) { return offsets.position(now); }

    /** A physics correction shifts both the player and its trailing cape, without adding motion. */
    public void translate(MovementVector displacement) {
        if (position != null) position = position.add(displacement);
    }

    public void reset(MovementVector position, long now) {
        this.position = position;
        offset = MovementVector.ZERO;
        offsets.reset(offset, now);
    }

    public void clear() {
        position = null;
        offset = MovementVector.ZERO;
        offsets.clear();
    }
}
