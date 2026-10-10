package dev.mineedge.movement.collision;

import dev.mineedge.movement.MovementVector;

/** Bounded shape probes adapted from faith_move/world.rs::trace_wall, column_top and tops_below. */
public final class TraversalProbes {
    private static final double CLIMB_FACING_DOT = Math.cos(Math.toRadians(45));

    /** Yaw-only climb entry cone; forward and the outward surface normal are horizontal unit vectors. */
    public static boolean facesClimb(MovementVector forward, MovementVector normal) {
        return -forward.dot(normal) >= CLIMB_FACING_DOT - 1.0E-9;
    }

    public static CollisionWorld.Hit wall(CollisionWorld world, MovementVector feet, double width,
                                         MovementVector forward, double reach) {
        for (int i = 0; i < 20; i++) {
            double y = feet.y() + 0.05 + i * 0.1;
            CollisionBox slice = new CollisionBox(new MovementVector(feet.x() - width / 2, y - 0.02, feet.z() - width / 2),
                    new MovementVector(feet.x() + width / 2, y + 0.02, feet.z() + width / 2));
            CollisionWorld.Hit hit = world.sweep(slice, forward.scale(reach));
            if (hit != null && Math.abs(hit.normal().y()) < 0.7 && hit.normal().dot(forward) <= -0.2) {
                return hit;
            }
        }
        return null;
    }

    public static CollisionWorld.Hit top(CollisionWorld world, MovementVector at, double radius,
                                        double above, double below) {
        if (above <= below) {
            return null;
        }
        CollisionBox column = new CollisionBox(new MovementVector(at.x() - radius, above, at.z() - radius),
                new MovementVector(at.x() + radius, above + 0.01, at.z() + radius));
        // A taller wall/overhang must not masquerade as a short ledge by starting inside it.
        if (world.overlaps(column)) {
            return null;
        }
        CollisionWorld.Hit hit = world.sweep(column, new MovementVector(0, below - above, 0));
        return hit != null && hit.normal().y() >= 0.7 ? hit : null;
    }

    /** A clear body below a lip does not guarantee space to reach and wrap a hand over it. */
    public static boolean reachableLedge(CollisionWorld world, MovementVector feet,
                                         MovementVector normal, CollisionBox support) {
        double x = Math.clamp(feet.x(), support.min().x(), support.max().x());
        double z = Math.clamp(feet.z(), support.min().z(), support.max().z());
        if (normal.x() > 0.5) x = support.max().x();
        if (normal.x() < -0.5) x = support.min().x();
        if (normal.z() > 0.5) z = support.max().z();
        if (normal.z() < -0.5) z = support.min().z();
        double top = support.max().y();
        var edge = new MovementVector(x, top, z);
        // Check the outside of the grip too: a contiguous trapdoor floor can expose
        // a constituent box's top while covering its internal edge from below.
        var grip = CollisionBox.body(edge.add(normal.scale(0.04)).add(new MovementVector(0, -0.07, 0)), 0.06, 0.14);
        if (world.overlaps(grip)) return false;
        var from = new MovementVector(feet.x(), top + 0.01, feet.z());
        var to = edge.subtract(normal.scale(0.03)).add(new MovementVector(0, 0.01, 0));
        var hand = CollisionBox.body(from, 0.02, 0.06);
        return !world.overlaps(hand) && !world.overlaps(hand.move(to.subtract(from)))
                && world.sweep(hand, to.subtract(from)) == null;
    }

    private TraversalProbes() {}
}
