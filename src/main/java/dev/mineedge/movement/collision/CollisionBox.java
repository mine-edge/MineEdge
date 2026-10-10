package dev.mineedge.movement.collision;

import dev.mineedge.movement.MovementVector;

/** World-space bounds. Face touching is not penetration (faith_move/world.rs). */
public record CollisionBox(MovementVector min, MovementVector max) {
    public CollisionBox {
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(min.axis(axis)) || !Double.isFinite(max.axis(axis))
                    || min.axis(axis) >= max.axis(axis)) {
                throw new IllegalArgumentException("Invalid collision box");
            }
        }
    }

    public static CollisionBox body(MovementVector feet, double width, double height) {
        return new CollisionBox(feet.add(new MovementVector(-width / 2, 0, -width / 2)),
                feet.add(new MovementVector(width / 2, height, width / 2)));
    }

    public CollisionBox move(MovementVector delta) {
        return new CollisionBox(min.add(delta), max.add(delta));
    }

    public CollisionBox swept(MovementVector delta, double margin) {
        return new CollisionBox(new MovementVector(min.x() + Math.min(0, delta.x()) - margin,
                min.y() + Math.min(0, delta.y()) - margin, min.z() + Math.min(0, delta.z()) - margin),
                new MovementVector(max.x() + Math.max(0, delta.x()) + margin,
                        max.y() + Math.max(0, delta.y()) + margin, max.z() + Math.max(0, delta.z()) + margin));
    }

    public boolean overlaps(CollisionBox other) {
        return max.x() > other.min.x() + BoxWorld.TOUCH && min.x() < other.max.x() - BoxWorld.TOUCH
                && max.y() > other.min.y() + BoxWorld.TOUCH && min.y() < other.max.y() - BoxWorld.TOUCH
                && max.z() > other.min.z() + BoxWorld.TOUCH && min.z() < other.max.z() - BoxWorld.TOUCH;
    }
}
