package dev.mineedge.movement.collision;

import dev.mineedge.movement.MovementVector;
import java.util.List;

/** Queries never mutate the world. A hit identifies the actual constituent shape box. */
public interface CollisionWorld {
    record Hit(double fraction, MovementVector normal, MovementVector point, CollisionBox shape) {}

    Hit sweep(CollisionBox body, MovementVector delta);
    boolean overlaps(CollisionBox body);

    /** Minimum translation out of one initial overlap; null when clear. */
    MovementVector separation(CollisionBox body);

    /** Revalidate a traversal's witness geometry without relying on whole-block solidity. */
    boolean contains(CollisionBox shape);

    /** Constituent collision boxes in a bounded region, including potential step heights. */
    List<CollisionBox> shapes(CollisionBox region);
}
