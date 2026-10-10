package dev.mineedge.client.camera;

import dev.mineedge.movement.MovementVector;

/** Render-only reconciliation offset. Physics always uses the corrected position immediately. */
public final class CorrectionSmoothing {
    private MovementVector offset = MovementVector.ZERO;

    public void correct(MovementVector displacement) {
        if (displacement.length() > 1) { reset(); return; }
        offset = offset.subtract(displacement);
        // Bound accumulated error as well as single corrections. Never ease a teleport.
        if (offset.length() > 1) reset();
    }

    public void update(double seconds) {
        offset = offset.scale(Math.exp(-18 * Math.max(0, seconds)));
        if (offset.length() < 1.0E-4) reset();
    }

    public MovementVector offset() { return offset; }
    public void reset() { offset = MovementVector.ZERO; }
}
