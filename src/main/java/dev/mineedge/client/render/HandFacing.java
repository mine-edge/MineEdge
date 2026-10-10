package dev.mineedge.client.render;

import dev.mineedge.movement.HandTargets;
import dev.mineedge.movement.MovementVector;

/** Releases one far-side grip as the player's look turns sideways to the contact plane. */
final class HandFacing {
    private static final double RELEASE_START_COS = Math.cos(Math.toRadians(70));
    private static final double SIDE_HYSTERESIS = 0.01;
    record Weights(float left, float right) {}

    private boolean releaseLeft;

    Weights sample(HandTargets grips, MovementVector feet, float yaw,
                   MovementVector leftShoulder, MovementVector rightShoulder) {
        // Never release the only attached hand.
        if (grips.leftWeight() <= 0 || grips.rightWeight() <= 0)
            return new Weights(grips.leftWeight(), grips.rightWeight());
        var center = grips.left().add(grips.right()).scale(0.5);
        var toward = center.subtract(feet);
        var span = grips.left().subtract(grips.right());
        var normal = new MovementVector(-span.z(), 0, span.x());
        if (normal.horizontalSpeed() < 1.0E-6) normal = new MovementVector(toward.x(), 0, toward.z());
        if (normal.horizontalSpeed() < 1.0E-6)
            return new Weights(grips.leftWeight(), grips.rightWeight());
        if (normal.dot(toward) < 0) normal = normal.scale(-1);
        normal = normal.normalized();
        // Shoulder distance to the wall plane, rather than staggered grip heights, picks the far hand.
        double side = leftShoulder.subtract(rightShoulder).dot(normal);
        if (side < -SIDE_HYSTERESIS) releaseLeft = true;
        else if (side > SIDE_HYSTERESIS) releaseLeft = false;

        double radians = Math.toRadians(yaw);
        double facing = -Math.sin(radians) * normal.x() + Math.cos(radians) * normal.z();
        double release = Math.clamp((RELEASE_START_COS - facing) / RELEASE_START_COS, 0, 1);
        float keep = (float) (1 - release * release * (3 - 2 * release));
        return new Weights(grips.leftWeight() * (releaseLeft ? keep : 1),
                grips.rightWeight() * (releaseLeft ? 1 : keep));
    }
}
