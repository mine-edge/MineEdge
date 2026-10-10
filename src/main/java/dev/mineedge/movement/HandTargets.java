package dev.mineedge.movement;

import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfacePath;
import dev.mineedge.movement.traversal.VaultType;

/** World-space grips derived from replay state, without extra world queries or animation timers. */
public record HandTargets(MovementVector left, MovementVector right, float leftWeight, float rightWeight) {
    public static final HandTargets EMPTY = new HandTargets(MovementVector.ZERO, MovementVector.ZERO, 0, 0);

    public boolean active() { return leftWeight > 0 || rightWeight > 0; }

    public HandTargets translate(MovementVector delta) {
        return active() ? new HandTargets(left.add(delta), right.add(delta), leftWeight, rightWeight) : EMPTY;
    }

    public static HandTargets sample(MovementState state) {
        if (state == null) return EMPTY;
        // Wallruns use the native running gait, not planted climbing hands/feet or a hanging cape.
        if (state.mode() == MovementState.Mode.WALLRUN) return EMPTY;
        var feet = state.position();
        var climb = state.surfaces().climb();
        if (climb != null) {
            var route = climb.route();
            var path = state.surfaces().path();
            if (path != null && path.kind() == SurfacePath.Kind.EXIT_TOP) {
                float weight = release(path.elapsed() / path.duration(), 0.45, 0.8);
                return pair(route.base().add(route.normal().scale(route.pipe() ? 0.25 : 0.02)),
                        route.normal(), route.top(), route.top(), weight, weight);
            }
            double height = (path == null ? feet.y() : path.end().y()) + 1.55;
            double left = rung(height, route.base().y(), 0);
            double right = rung(height, route.base().y(), ClimbRoute.STEP);
            // Staggered grips let one hand stay planted while the other advances.
            float weight = path == null ? 1 : (float) smooth(path.elapsed() / path.duration());
            return pair(route.base().add(route.normal().scale(route.pipe() ? 0.25 : 0.02)), route.normal(),
                    Math.min(route.top(), left), Math.min(route.top(), right), weight, weight);
        }
        var traversal = state.traversal().path();
        if (traversal != null && traversal.kind() != VaultType.LOW_STEP && traversal.kind() != VaultType.SPRINGBOARD) {
            var forward = traversal.over().subtract(traversal.hand());
            var normal = new MovementVector(-forward.x(), 0, -forward.z()).normalized();
            if (normal.horizontalSpeed() < 0.5) return EMPTY;
            double progress = state.modeTime() / traversal.kind().duration();
            float weight = release(progress, 0.35, 0.7);
            if (traversal.kind() != VaultType.PULL_UP) weight *= (float) smooth(progress / 0.12);
            return lip(traversal.support(), traversal.from(), normal, weight);
        }
        var wall = state.wall();
        if (wall.support() == null) return EMPTY;
        return switch (state.mode()) {
            case LEDGE_HANG -> ledge(state);
            case WALLCLIMB -> face(wall.support(), feet, wall.normal(),
                    rung(feet.y() + 1.6, 0, 0), rung(feet.y() + 1.6, 0, ClimbRoute.STEP), 1, 1);
            default -> EMPTY;
        };
    }

    private static HandTargets ledge(MovementState state) {
        var wall = state.wall();
        var move = state.advanced().ledge();
        if (move == null) return lip(wall.support(), state.position(), wall.normal(), 1);
        if (move.kind() == AdvancedState.LedgeKind.RETURN) return lip(move.support(), move.to(), move.normal(), 1);
        var from = lip(move.originSupport(), move.from(), wall.normal(), 1);
        var to = lip(move.support(), move.to(), move.normal(), 1);
        double progress = move.elapsed() / move.duration();
        var left = new MovementVector(-wall.normal().z(), 0, wall.normal().x());
        boolean leadingLeft = move.to().subtract(move.from()).dot(left) >= 0;
        double leading = smooth(progress / 0.55), trailing = smooth((progress - 0.4) / 0.6);
        if (move.kind() == AdvancedState.LedgeKind.TRANSFER) leading = trailing = smooth(progress);
        return new HandTargets(lerp(from.left, to.left, leadingLeft ? leading : trailing),
                lerp(from.right, to.right, leadingLeft ? trailing : leading), 1, 1);
    }

    private static MovementVector lerp(MovementVector a, MovementVector b, double alpha) { return a.add(b.subtract(a).scale(alpha)); }

    private static double rung(double height, double base, double phase) {
        double stride = ClimbRoute.STEP * 2;
        double u = (height - base - phase) / stride;
        double step = Math.floor(u);
        // The brief reach phase is continuous at each rung boundary.
        return base + phase + stride * (step + smooth(Math.clamp((u - step - 0.7) / 0.3, 0, 1)));
    }

    private static HandTargets lip(CollisionBox box, MovementVector feet, MovementVector normal, float weight) {
        return face(box, feet, normal, box.max().y() + 0.02, box.max().y() + 0.02, weight, weight);
    }

    private static HandTargets face(CollisionBox box, MovementVector feet, MovementVector normal,
                                    double leftY, double rightY, float leftWeight, float rightWeight) {
        double x = Math.clamp(feet.x(), box.min().x(), box.max().x());
        double z = Math.clamp(feet.z(), box.min().z(), box.max().z());
        if (normal.x() > 0.5) x = box.max().x();
        if (normal.x() < -0.5) x = box.min().x();
        if (normal.z() > 0.5) z = box.max().z();
        if (normal.z() < -0.5) z = box.min().z();
        var grips = pair(new MovementVector(x, 0, z).add(normal.scale(0.02)), normal,
                leftY, rightY, leftWeight, rightWeight);
        return new HandTargets(bound(grips.left, box, normal), bound(grips.right, box, normal), leftWeight, rightWeight);
    }

    private static MovementVector bound(MovementVector hand, CollisionBox box, MovementVector normal) {
        return new MovementVector(Math.abs(normal.x()) > 0.5 ? hand.x() : Math.clamp(hand.x(), box.min().x(), box.max().x()),
                hand.y(), Math.abs(normal.z()) > 0.5 ? hand.z() : Math.clamp(hand.z(), box.min().z(), box.max().z()));
    }

    private static HandTargets pair(MovementVector center, MovementVector normal, double leftY, double rightY,
                                    float leftWeight, float rightWeight) {
        var left = new MovementVector(-normal.z(), 0, normal.x()).scale(0.23);
        return new HandTargets(new MovementVector(center.x() + left.x(), leftY, center.z() + left.z()),
                new MovementVector(center.x() - left.x(), rightY, center.z() - left.z()), leftWeight, rightWeight);
    }

    private static float release(double progress, double start, double end) {
        return (float) (1 - smooth(Math.clamp((progress - start) / (end - start), 0, 1)));
    }
    private static double smooth(double u) { u = Math.clamp(u, 0, 1); return u * u * (3 - 2 * u); }
}
