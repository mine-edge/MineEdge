package dev.mineedge.movement.traversal;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.CollisionBox;

/** Immutable feet-space three-phase path. Its timer lives in MovementState.modeTime. */
public record TraversalPath(VaultType kind, MovementVector from, MovementVector hand, MovementVector over,
                            MovementVector end, MovementVector exitVelocity, CollisionBox support, CollisionBox landing,
                            MovementBody body) {
    public TraversalPath(VaultType kind, MovementVector from, MovementVector hand, MovementVector over,
                         MovementVector end, MovementVector exitVelocity, CollisionBox support, CollisionBox landing) {
        this(kind, from, hand, over, end, exitVelocity, support, landing, MovementBody.CROUCHING);
    }

    public boolean canCancelOnRelease(double seconds) {
        return kind.requiresJumpHold() && seconds + 1.0E-9 < kind.duration() * 0.5;
    }

    public MovementVector position(double seconds) {
        if (seconds < kind.upSeconds()) {
            return lerp(from, hand, seconds / kind.upSeconds());
        }
        if (seconds < kind.upSeconds() + kind.overSeconds()) {
            return lerp(hand, over, (seconds - kind.upSeconds()) / kind.overSeconds());
        }
        return lerp(over, end, (seconds - kind.upSeconds() - kind.overSeconds()) / kind.downSeconds());
    }

    public CollisionBox region(double width, double height) {
        double minX = Math.min(Math.min(from.x(), hand.x()), Math.min(over.x(), end.x()));
        double minY = Math.min(Math.min(from.y(), hand.y()), Math.min(over.y(), end.y()));
        double minZ = Math.min(Math.min(from.z(), hand.z()), Math.min(over.z(), end.z()));
        double maxX = Math.max(Math.max(from.x(), hand.x()), Math.max(over.x(), end.x()));
        double maxY = Math.max(Math.max(from.y(), hand.y()), Math.max(over.y(), end.y()));
        double maxZ = Math.max(Math.max(from.z(), hand.z()), Math.max(over.z(), end.z()));
        return new CollisionBox(new MovementVector(minX - width - 0.1, minY - 0.1, minZ - width - 0.1),
                new MovementVector(maxX + width + 0.1, maxY + height + 0.1, maxZ + width + 0.1));
    }

    private static MovementVector lerp(MovementVector a, MovementVector b, double fraction) {
        return a.add(b.subtract(a).scale(Math.clamp(fraction, 0, 1)));
    }
}
