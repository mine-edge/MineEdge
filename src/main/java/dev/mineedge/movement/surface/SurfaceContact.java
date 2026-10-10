package dev.mineedge.movement.surface;

import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.CollisionBox;

/** Adapter-owned material/geometry classification of the actual supporting collision shape. */
public record SurfaceContact(CollisionBox shape, MovementVector block, MovementVector normal,
                             MovementVector downhill, boolean soft, boolean step) {
    public boolean steep() { return downhill.horizontalSpeed() > 0 && normal.y() >= 0.6 && normal.y() < 0.7; }
}
