package dev.mineedge.movement;

import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionWorld;
import dev.mineedge.movement.collision.TraversalProbes;
import dev.mineedge.movement.traversal.TraversalPath;
import dev.mineedge.movement.traversal.VaultPlanner;
import dev.mineedge.movement.traversal.VaultType;
import dev.mineedge.movement.wall.WallState;

/** Plans turns, springboard launches and collision-tested ledge transfers. */
public final class AdvancedMoves {
    private static final double SHIMMY_HEIGHT_REACH = 0.25;

    public static AdvancedState.Turn turn(MovementState.Mode mode, MovementVector velocity, MovementInput input, WallState wall) {
        boolean allowed = mode == MovementState.Mode.GROUND && !input.crouchHeld()
                || mode == MovementState.Mode.AIR && new MovementVector(velocity.x(), 0, velocity.z()).normalized().dot(input.forwardDirection()) >= 0.2
                || mode == MovementState.Mode.WALLRUN || mode == MovementState.Mode.WALLCLIMB || mode == MovementState.Mode.LEDGE_HANG;
        if (!allowed) return null;
        float delta = mode == MovementState.Mode.WALLRUN ? wrapped(yaw(wall.normal()) - input.yaw()) : 180;
        return new AdvancedState.Turn(input.yaw(), delta, 0, mode == MovementState.Mode.WALLRUN ? 0.15 : 0.2);
    }

    public static float yaw(MovementVector direction) { return (float) Math.toDegrees(Math.atan2(-direction.x(), direction.z())); }
    public static float wrapped(float angle) { return ((angle % 360) + 540) % 360 - 180; }
    public static MovementVector side(MovementInput input) {
        var forward = input.forwardDirection();
        return new MovementVector(forward.z(), 0, -forward.x()).scale(Math.signum(input.rawStrafe() == 0 ? input.strafe() : input.rawStrafe()));
    }

    public static AdvancedState.Springboard springboard(MovementController.MotionResolver resolver, MovementVector feet,
                                                         MovementVector velocity, MovementInput input) {
        double speed = velocity.horizontalSpeed(), reach = Math.min(7.2, speed);
        if (reach <= 0.3 || velocity.dot(input.forwardDirection()) <= 0 || input.forward() <= 0.3 || input.crouchHeld()) return null;
        var bounds = resolver.bounds(feet, MovementBody.STANDING);
        double width = bounds.max().x() - bounds.min().x();
        var forward = input.forwardDirection();
        var world = resolver.geometry(bounds.swept(forward.scale(reach + 2), 2));
        if (world == null) return null;
        var probe = CollisionBox.body(feet.add(new MovementVector(0, 0.05, 0)), width, 0.1);
        var face = world.sweep(probe, forward.scale(reach));
        if (face == null || face.normal().y() != 0 || -forward.dot(face.normal()) < 0.6) return null;
        var normal = face.normal();
        double gap = face.fraction() * reach;
        MovementVector edge = feet.subtract(normal.scale(gap + width / 2));
        if (gap + width / 2 < 0.2) return null;
        var step = TraversalProbes.top(world, edge.subtract(normal.scale(0.05)), 0.01, feet.y() + 1.58, feet.y());
        if (step == null || Math.abs(step.point().y() - feet.y() - 0.64) >= 0.2) return null;
        CollisionWorld.Hit top = null; double spacing = 0;
        for (int i = 1; i <= 80; i++) {
            double distance = i * 0.02;
            var candidate = TraversalProbes.top(world, edge.subtract(normal.scale(distance)), 0.005, feet.y() + 1.58, feet.y());
            if (candidate != null && candidate.point().y() > step.point().y() + 0.05) { top = candidate; spacing = distance; break; }
        }
        if (top == null || Math.abs(spacing - 1.12) >= 0.2 || top.point().y() - feet.y() < 0.8 || top.point().y() - feet.y() > 1.48) return null;
        MovementVector start = gap + width / 2 > 1.2 ? edge.add(normal.scale(1.2)) : feet;
        start = new MovementVector(start.x(), feet.y(), start.z());
        MovementVector end = edge.subtract(normal.scale(spacing + width / 2 + 0.02));
        end = new MovementVector(end.x(), top.point().y(), end.z());
        double clearY = top.point().y() + 0.08;
        MovementVector hand = new MovementVector(start.x(), clearY, start.z());
        MovementVector over = new MovementVector(end.x(), clearY, end.z());
        var launch = forward.scale(Math.max(4, Math.max(2, speed) - 1)).add(new MovementVector(0, 9.5, 0));
        var path = new TraversalPath(VaultType.SPRINGBOARD, start, hand, over, end, launch, step.shape(), top.shape());
        if (!resolver.canOccupy(end, MovementBody.STANDING) || !VaultPlanner.valid(resolver, path, 0)
                || world.sweep(bounds, start.subtract(feet)) != null) return null;
        return new AdvancedState.Springboard(path, Math.max(2, speed), 0);
    }

    public static AdvancedState.LedgeMove ledge(MovementController.MotionResolver resolver, MovementVector feet,
                                               MovementBody body, WallState wall, MovementInput input, boolean transfer) {
        var normal = wall.normal(); double topY = wall.support().max().y();
        var bounds = resolver.bounds(feet, body); double width = bounds.max().x() - bounds.min().x();
        var world = resolver.geometry(bounds.swept(new MovementVector(0, 1.5, 0), 1.5));
        if (world == null) return null;
        if (transfer) {
            // Search higher lips, including a recessed face, within the reference's 1.4-block reach.
            for (int i = 0; i <= 20; i++) {
                var at = feet.subtract(normal.scale(i * 0.05));
                var lip = TraversalProbes.top(world, at.subtract(normal.scale(width / 2 + 0.05)), 0.01, topY + 1.4, topY + 0.3);
                if (lip == null) continue;
                // Keep the body outside the lower face; hands may reach a recessed upper lip.
                var to = feet.add(new MovementVector(0, lip.point().y() - topY, 0));
                if (!clear(resolver, world, feet, to, body)
                        || !TraversalProbes.reachableLedge(world, to, normal, lip.shape())) continue;
                double duration = Math.min(0.55, to.subtract(feet).length() / 2);
                return move(AdvancedState.LedgeKind.TRANSFER, feet, feet.add(to.subtract(feet).scale(0.5)), to, normal, lip.shape(), wall.support(), duration, input.yaw(), 0);
            }
            return null;
        }
        if (Math.abs(input.strafe()) <= 0.3) return null;
        MovementVector along = new MovementVector(normal.z(), 0, -normal.x());
        if (along.dot(side(input)) < 0) along = along.scale(-1);
        double reach = 0; CollisionWorld.Hit lip = null; MovementVector to = feet;
        for (int i = 1; i <= 12; i++) {
            var target = feet.add(along.scale(i * 0.05));
            var next = hangs(resolver, world, target, body, normal, topY, SHIMMY_HEIGHT_REACH);
            if (next != null) target = target.add(new MovementVector(0, next.point().y() - topY, 0));
            if (next == null || !clear(resolver, world, feet, target, body)) break;
            reach = i * 0.05; lip = next; to = target;
        }
        if (reach >= 0.1) {
            // Preserve the grip-to-feet offset and interpolate the rise/drop with
            // the existing shimmy profile, including its staggered hand targets.
            return move(AdvancedState.LedgeKind.SHIMMY, feet, feet.add(to.subtract(feet).scale(0.5)), to, normal,
                    lip.shape(), wall.support(), 1.07, input.yaw(), 0);
        }
        double offset = width / 2 + 0.05;
        // Inside corner: the adjoining face blocks the shimmy before the current lip ends.
        var hands = CollisionBox.body(new MovementVector(feet.x(), topY - 0.07, feet.z()), width, 0.02);
        var face = world.sweep(hands, along.scale(0.15));
        if (face != null && face.normal().dot(along) < -0.9) {
            to = feet.add(along.scale(face.fraction() * 0.15 - 0.05)).add(normal.scale(offset));
            var nextNormal = face.normal();
            lip = hangs(resolver, world, to, body, nextNormal, topY);
            if (lip != null && clear(resolver, world, feet, to, body)) {
                return move(AdvancedState.LedgeKind.CORNER, feet, feet.add(to.subtract(feet).scale(0.5)), to,
                        nextNormal, lip.shape(), wall.support(), 1.37, input.yaw(),
                        wrapped(yaw(nextNormal.scale(-1)) - input.yaw()));
            }
        }
        // Outside corner: sample where the hand witness ends, then move outside before crossing the new face.
        MovementVector edge = null;
        for (int i = 0; i <= 30; i++) {
            var at = feet.add(along.scale(i * 0.02)).subtract(normal.scale(offset));
            var witness = CollisionBox.body(new MovementVector(at.x(), topY - 0.08, at.z()), 0.02, 0.02);
            if (!world.overlaps(witness)) { edge = at.subtract(along.scale(0.02)); break; }
        }
        if (edge == null || edge.subtract(feet).dot(along) > width / 2 + 0.15) return null;
        var via = edge.add(along.scale(offset)).add(normal.scale(offset));
        to = edge.add(along.scale(offset)).subtract(normal.scale(offset));
        via = new MovementVector(via.x(), feet.y(), via.z()); to = new MovementVector(to.x(), feet.y(), to.z());
        lip = hangs(resolver, world, to, body, along, topY);
        if (lip == null || !clear(resolver, world, feet, via, body) || !clear(resolver, world, via, to, body)) return null;
        return move(AdvancedState.LedgeKind.CORNER, feet, via, to, along, lip.shape(), wall.support(), 1.37, input.yaw(),
                wrapped(yaw(along.scale(-1)) - input.yaw()));
    }

    private static AdvancedState.LedgeMove move(AdvancedState.LedgeKind kind, MovementVector from, MovementVector via,
                                               MovementVector to, MovementVector normal, CollisionBox support, CollisionBox origin,
                                               double duration, float yaw, float delta) {
        return new AdvancedState.LedgeMove(kind, from, via, to, normal, support, origin, 0, duration, yaw, delta);
    }
    public static CollisionWorld.Hit hangs(MovementController.MotionResolver resolver, CollisionWorld world,
                                           MovementVector feet, MovementBody body, MovementVector normal, double topY) {
        return hangs(resolver, world, feet, body, normal, topY, 0.05);
    }
    private static CollisionWorld.Hit hangs(MovementController.MotionResolver resolver, CollisionWorld world,
                                            MovementVector feet, MovementBody body, MovementVector normal,
                                            double topY, double heightReach) {
        double width = resolver.bounds(feet, body).max().x() - resolver.bounds(feet, body).min().x();
        var at = feet.subtract(normal.scale(width / 2 + 0.08));
        var lip = TraversalProbes.top(world, at, 0.01, topY + heightReach + 1.0E-6, topY - heightReach - 1.0E-6);
        if (lip == null || Math.abs(lip.point().y() - topY) > heightReach) return null;
        var target = feet.add(new MovementVector(0, lip.point().y() - topY, 0));
        var hands = CollisionBox.body(new MovementVector(at.x(), lip.point().y() - 0.07, at.z()), 0.02, 0.02);
        return !world.overlaps(resolver.bounds(target, body)) && world.overlaps(hands)
                && TraversalProbes.reachableLedge(world, target, normal, lip.shape()) ? lip : null;
    }
    public static boolean clear(MovementController.MotionResolver resolver, CollisionWorld world, MovementVector from, MovementVector to, MovementBody body) {
        return !world.overlaps(resolver.bounds(from, body)) && !world.overlaps(resolver.bounds(to, body))
                && world.sweep(resolver.bounds(from, body), to.subtract(from)) == null;
    }
    private AdvancedMoves() {}
}
