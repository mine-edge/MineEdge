package dev.mineedge.movement.collision;

import dev.mineedge.movement.MovementVector;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Bounded swept movement with optional Minecraft 1.21.1 collision stepping. */
public final class CollisionMover {
    public static final int MAX_CONTACTS = 4;
    public static final int MAX_RECOVERY_STEPS = 6;
    public static final double MAX_RECOVERY = 0.5;

    public record Result(MovementVector recovery, MovementVector displacement,
                         List<CollisionWorld.Hit> contacts, boolean blockedByOverlap) {
        public MovementVector clip(MovementVector velocity) {
            if (blockedByOverlap) {
                return MovementVector.ZERO;
            }
            for (CollisionWorld.Hit contact : contacts) {
                double into = velocity.dot(contact.normal());
                if (into < 0) {
                    velocity = velocity.subtract(contact.normal().scale(into));
                }
            }
            return velocity;
        }

        public boolean grounded() {
            for (CollisionWorld.Hit hit : contacts) if (hit.normal().y() >= 0.7) return true;
            return false;
        }

        public boolean verticalContact() {
            for (CollisionWorld.Hit hit : contacts) if (hit.normal().y() != 0) return true;
            return false;
        }
    }

    public static Result move(CollisionWorld world, CollisionBox body, MovementVector delta) {
        MovementVector recovery = MovementVector.ZERO;
        double recovered = 0;
        int recoverySteps = 0;
        for (; recoverySteps < MAX_RECOVERY_STEPS; recoverySteps++) {
            MovementVector separation = world.separation(body);
            if (separation == null) {
                break;
            }
            recovered += separation.length();
            if (recovered > MAX_RECOVERY) {
                return new Result(MovementVector.ZERO, MovementVector.ZERO, List.of(), true);
            }
            body = body.move(separation);
            recovery = recovery.add(separation);
        }
        // separation() returning null already establishes that the body is clear.
        // Only a recovery that exhausted its budget needs another overlap query.
        if (recoverySteps == MAX_RECOVERY_STEPS && world.overlaps(body)) {
            return new Result(MovementVector.ZERO, MovementVector.ZERO, List.of(), true);
        }
        MovementVector displacement = MovementVector.ZERO;
        MovementVector remaining = delta;
        List<CollisionWorld.Hit> contacts = null;
        for (int i = 0; i < MAX_CONTACTS && remaining.length() > 1.0E-12; i++) {
            CollisionWorld.Hit hit = world.sweep(body, remaining);
            if (hit == null) {
                displacement = displacement.add(remaining);
                break;
            }
            MovementVector traveled = remaining.scale(hit.fraction());
            displacement = displacement.add(traveled);
            body = body.move(traveled);
            remaining = remaining.scale(1 - hit.fraction());
            remaining = remaining.subtract(hit.normal().scale(Math.min(0, remaining.dot(hit.normal()))));
            if (contacts == null) contacts = new ArrayList<>(MAX_CONTACTS);
            contacts.add(hit);
        }
        return new Result(recovery, displacement, contacts == null ? List.of() : List.copyOf(contacts), false);
    }

    public static Result move(CollisionWorld world, CollisionBox body, MovementVector delta,
                              double stepHeight) {
        return move(world, body, delta, stepHeight, false);
    }

    public static Result move(CollisionWorld world, CollisionBox body, MovementVector delta,
                              double stepHeight, boolean grounded) {
        var swept = move(world, body, delta);
        if (stepHeight <= 0 || swept.blockedByOverlap() || swept.recovery().length() > 1.0E-9
                || !grounded && !swept.grounded()
                || Math.abs(swept.displacement().x() - delta.x()) <= 1.0E-9
                    && Math.abs(swept.displacement().z() - delta.z()) <= 1.0E-9) return swept;
        // Vanilla resolves Y first, then the larger horizontal component. The same
        // ordering must be used for the baseline and every candidate step.
        var normal = moveAxes(world, body, delta);
        boolean landed = delta.y() < 0 && normal.displacement().y() != delta.y();
        if ((!landed && !grounded) || normal.displacement().x() == delta.x()
                && normal.displacement().z() == delta.z()) return swept;
        var base = landed ? body.move(new MovementVector(0, normal.displacement().y(), 0)) : body;
        var region = base.swept(new MovementVector(delta.x(), stepHeight, delta.z()), 0);
        if (!landed) region = region.swept(new MovementVector(0, -1.0E-5F, 0), 0);
        var shapes = world.shapes(region);
        var heights = new TreeSet<Float>();
        for (var shape : shapes) {
            for (int face = 0; face < 2; face++) {
                double y = face == 0 ? shape.min().y() : shape.max().y();
                float height = (float) (y - base.min().y());
                if (height >= 0 && height <= (float) stepHeight && height != (float) normal.displacement().y()) heights.add(height);
            }
        }
        double distance = horizontalDistanceSquared(normal.displacement());
        for (float height : heights) {
            var step = moveAxes(world, base, new MovementVector(delta.x(), height, delta.z()));
            if (horizontalDistanceSquared(step.displacement()) <= distance) continue;
            var displacement = step.displacement().add(new MovementVector(0, base.min().y() - body.min().y(), 0));
            var contacts = new ArrayList<>(step.contacts());
            // Entity.move treats a step as a downward vertical collision when the
            // requested Y is negative. Preserve that ground/velocity response.
            if (delta.y() < 0 && displacement.y() != delta.y()) {
                var end = body.move(displacement);
                var support = world.sweep(end, new MovementVector(0, -1.0E-5F, 0));
                contacts.add(support != null ? support : new CollisionWorld.Hit(0, new MovementVector(0, 1, 0),
                        end.min(), shapes.getFirst()));
            }
            return new Result(MovementVector.ZERO, displacement, List.copyOf(contacts), false);
        }
        return swept;
    }

    private static double horizontalDistanceSquared(MovementVector delta) {
        return delta.x() * delta.x() + delta.z() * delta.z();
    }

    private static Result moveAxes(CollisionWorld world, CollisionBox body, MovementVector delta) {
        var displacement = MovementVector.ZERO;
        var contacts = new ArrayList<CollisionWorld.Hit>(3);
        boolean zFirst = Math.abs(delta.x()) < Math.abs(delta.z());
        for (int index = 0; index < 3; index++) {
            int axis = switch (index) {
                case 0 -> 1;
                case 1 -> zFirst ? 2 : 0;
                default -> zFirst ? 0 : 2;
            };
            if (delta.axis(axis) == 0) continue;
            var motion = MovementVector.axis(axis, delta.axis(axis));
            var hit = world.sweep(body, motion);
            if (hit != null) {
                motion = motion.scale(hit.fraction());
                contacts.add(hit);
            }
            body = body.move(motion);
            displacement = displacement.add(motion);
        }
        return new Result(MovementVector.ZERO, displacement, List.copyOf(contacts), false);
    }

    private CollisionMover() {}
}
