package dev.mineedge.movement.traversal;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionWorld;
import dev.mineedge.movement.collision.TraversalProbes;

/** Shape-query adaptation of faith_move/vault.rs::plan. All paths are clearance-tested, not teleported. */
public final class VaultPlanner {
    public static final double MAX_HEIGHT = 1.92;
    /** Horizontal player-center to obstacle-face distance, independent of body width and speed. */
    public static final double MAX_REACH = 1;
    public static final double MAX_END_ADVANCE = 4.5;
    public static final double SUPPORTED_MANTLE_SLIDE = 1;
    private static final double CLEARANCE = 0.08;
    private static final double GAP_LOOKAHEAD = 0.5;

    public static TraversalPath plan(MovementController.MotionResolver resolver, MovementVector feet,
                                     MovementVector velocity, MovementInput input) {
        return plan(resolver, feet, velocity, input, MovementTuning.DEFAULT);
    }

    public static TraversalPath plan(MovementController.MotionResolver resolver, MovementVector feet,
                                     MovementVector velocity, MovementInput input, MovementTuning tuning) {
        double scale = tuning.walkSpeed() / 4;
        if (scale <= 0 || !tuning.features().vaulting() && !tuning.features().mantling()) {
            return null;
        }
        double speed = velocity.horizontalSpeed();
        double reach = Math.min(MAX_REACH, 0.4 * Math.max(3 * scale, speed));
        MovementVector forward = input.forwardDirection();
        CollisionBox standing = resolver.bounds(feet, MovementBody.STANDING);
        double width = standing.max().x() - standing.min().x();
        CollisionWorld world = resolver.geometry(region(feet, forward, reach, width, standing.max().y() - feet.y()));
        if (world == null) {
            return null;
        }
        CollisionWorld.Hit face = TraversalProbes.wall(world, feet, width, forward, reach);
        if (face == null || !TraversalProbes.facesClimb(forward, face.normal())) {
            return null;
        }
        double pitch = Math.toRadians(input.pitch());
        MovementVector view = forward.scale(Math.cos(pitch)).add(new MovementVector(0, -Math.sin(pitch), 0));
        if (view.dot(face.normal()) > -0.2) {
            return null;
        }
        MovementVector inside = face.point().subtract(face.normal().scale(0.02));
        CollisionWorld.Hit top = TraversalProbes.top(world, inside, 0.01, feet.y() + MAX_HEIGHT + 0.05, feet.y() + 0.01);
        if (top == null && face.shape().max().y() <= feet.y() + MAX_HEIGHT + 1.0E-6) {
            // A low entrance can put the normal top probe inside its ceiling.
            // Probe just above the hit shape; stacked walls still reject an embedded probe.
            top = TraversalProbes.top(world, inside, 0.01, face.shape().max().y() + 0.03, feet.y() + 0.01);
        }
        if (top == null) {
            return null;
        }
        double topY = top.point().y();
        double height = topY - feet.y();
        if (height < 0 || height > MAX_HEIGHT + 1.0E-6) {
            return null;
        }
        MovementVector ledge = new MovementVector(face.point().x(), topY, face.point().z());
        MovementVector toLedge = new MovementVector(ledge.x() - feet.x(), 0, ledge.z() - feet.z());
        MovementVector direction = toLedge.normalized();
        if (direction.equals(MovementVector.ZERO) || toLedge.length() > MAX_REACH + 1.0E-6
                || toLedge.length() / Math.max(3 * scale, speed) > 0.4) {
            return null;
        }
        double limit = Math.clamp(speed * 0.2, 0.6, 1.8);
        double depth = thickness(world, ledge, direction, limit);
        boolean onto = depth < 0 || speed <= 2 * scale;
        VaultType kind = null;
        for (VaultType candidate : VaultType.values()) {
            if (candidate.accepts(onto, height, speed / scale, velocity.y())) {
                kind = candidate;
                break;
            }
        }
        if (kind == null) {
            return null;
        }
        onto = kind.onto();
        double exitSpeed = kind.exitSpeed(speed / scale) * scale;
        double overDistance = onto ? width / 2 + CLEARANCE : depth + width / 2 + CLEARANCE;
        double endDistance = Math.min(MAX_END_ADVANCE, overDistance + Math.max(0.48, exitSpeed * kind.downSeconds()));
        if (onto && depth >= 0) {
            // Slow jump/mantle onto a narrow rail: keep some of the landing footprint on its top.
            endDistance = Math.min(endDistance, Math.max(overDistance, depth + width / 2 - 0.15));
        }
        if (onto && endDistance - overDistance > SUPPORTED_MANTLE_SLIDE
                && continuousTop(world, ledge, direction, overDistance, endDistance + width / 2 + GAP_LOOKAHEAD)) {
            // Once safely over the lip, do not carry the player across a continuous platform.
            // A gap in or just beyond the original slide keeps the longer traversal route.
            endDistance = overDistance + SUPPORTED_MANTLE_SLIDE;
        }
        MovementVector hand = new MovementVector(feet.x(), Math.max(feet.y(), topY + CLEARANCE), feet.z());
        MovementVector over = ledge.add(direction.scale(overDistance)).add(new MovementVector(0, CLEARANCE, 0));
        MovementVector end = ledge.add(direction.scale(endDistance));
        CollisionWorld.Hit floor = TraversalProbes.top(world, end, width / 2, topY + 0.32,
                topY - (onto ? 0.32 : 2.4) - CLEARANCE);
        end = new MovementVector(end.x(), floor == null ? topY : floor.point().y(), end.z());
        // Keep the original last-leg exit-speed rule, with geometry-bounded Minecraft endpoints.
        double finalSpeed = end.subtract(over).horizontalSpeed() / kind.downSeconds();
        TraversalPath path = new TraversalPath(kind, feet, hand, over, end, direction.scale(finalSpeed),
                top.shape(), floor == null ? null : floor.shape());
        if (tuning.features().allowsTraversal(kind) && (!onto || floor != null && Math.abs(floor.point().y() - topY) <= 0.05)) {
            var fitted = fit(resolver, world, path);
            if (fitted != null) return fitted;
        }
        // A thin rail or a fence behind the lip may support a shorter mantle even when
        // the normal over/slide endpoint does not. Require real support and full clearance.
        if (!tuning.features().mantling()) return null;
        for (VaultType candidate : VaultType.values()) {
            if (!candidate.onto() || !candidate.accepts(true, height, speed / scale, velocity.y())) continue;
            double advance = width / 2 + CLEARANCE;
            int samples = (int) Math.ceil((advance - 0.02) / 0.05);
            for (int i = 0; i <= samples; i++) {
                var at = ledge.add(direction.scale(Math.max(0.02, advance - i * 0.05)));
                var landing = TraversalProbes.top(world, at, width / 2, topY + 0.32, topY - 0.05);
                if (landing == null || Math.abs(landing.point().y() - topY) > 0.05) continue;
                var target = new MovementVector(at.x(), landing.point().y(), at.z());
                var shortPath = new TraversalPath(candidate, feet, hand, target.add(new MovementVector(0, CLEARANCE, 0)),
                        target, MovementVector.ZERO, top.shape(), landing.shape());
                var fitted = fit(resolver, world, shortPath);
                if (fitted != null) return fitted;
            }
        }
        return null;
    }

    public static boolean valid(MovementController.MotionResolver resolver, TraversalPath path, double elapsed) {
        CollisionBox standing = resolver.bounds(path.end(), MovementBody.STANDING);
        double width = standing.max().x() - standing.min().x();
        double height = standing.max().y() - standing.min().y();
        CollisionWorld world = resolver.geometry(path.region(width, height));
        return world != null && world.contains(path.support()) && (path.landing() == null || world.contains(path.landing()))
                && clear(world, resolver.bounds(path.end(), mantle(path) ? path.body() : MovementBody.STANDING))
                && pathClear(world, resolver, path, elapsed);
    }

    /** Prefer the original path, then tuck into a crouched or crawling entrance without raising above the lip. */
    public static TraversalPath fit(MovementController.MotionResolver resolver, CollisionWorld world, TraversalPath path) {
        MovementBody landingBody = path.kind() == VaultType.PULL_UP ? MovementBody.CROUCHING : MovementBody.STANDING;
        if (clear(world, resolver.bounds(path.end(), landingBody)) && pathClear(world, resolver, path, 0)) return path;
        if (!mantle(path)) return null;
        var hand = new MovementVector(path.hand().x(), Math.max(path.from().y(), path.hand().y() - CLEARANCE), path.hand().z());
        var over = path.over().subtract(new MovementVector(0, CLEARANCE, 0));
        for (MovementBody body : new MovementBody[]{MovementBody.CROUCHING, MovementBody.LOW}) {
            var tucked = new TraversalPath(path.kind(), path.from(), hand, over, path.end(), path.exitVelocity(),
                    path.support(), path.landing(), body);
            if (clear(world, resolver.bounds(path.end(), body)) && pathClear(world, resolver, tucked, 0)) return tucked;
        }
        return null;
    }

    private static boolean mantle(TraversalPath path) {
        return path.kind().onto() && path.kind() != VaultType.SPRINGBOARD;
    }

    private static boolean pathClear(CollisionWorld world, MovementController.MotionResolver resolver,
                                     TraversalPath path, double elapsed) {
        MovementVector previous = path.position(elapsed);
        double[] boundaries = {path.kind().upSeconds(), path.kind().upSeconds() + path.kind().overSeconds(), path.kind().duration()};
        for (double boundary : boundaries) {
            if (boundary <= elapsed + 1.0E-9) {
                continue;
            }
            MovementVector next = path.position(boundary);
            CollisionBox body = resolver.bounds(previous, path.body());
            if (!clear(world, body) || world.sweep(body, next.subtract(previous)) != null
                    || !clear(world, resolver.bounds(next, path.body()))) {
                return false;
            }
            previous = next;
        }
        return true;
    }

    private static boolean clear(CollisionWorld world, CollisionBox body) {
        return !world.overlaps(body);
    }

    /** Bounded centre-line support samples include a little beyond the final body footprint. */
    private static boolean continuousTop(CollisionWorld world, MovementVector ledge, MovementVector direction,
                                         double from, double to) {
        int samples = Math.min(32, Math.max(1, (int) Math.ceil((to - from) / 0.2)));
        for (int i = 0; i <= samples; i++) {
            double distance = from + (to - from) * i / samples;
            MovementVector at = ledge.add(direction.scale(distance));
            var surface = TraversalProbes.top(world, at, 0.01, ledge.y() + 0.05, ledge.y() - 0.05);
            if (surface == null || Math.abs(surface.point().y() - ledge.y()) > 0.05) return false;
        }
        return true;
    }

    /** Probe contiguous top geometry at 5 cm intervals, at most 37 samples. -1 means mantle-depth. */
    private static double thickness(CollisionWorld world, MovementVector ledge, MovementVector direction, double limit) {
        for (int i = 1; i <= 37; i++) {
            double distance = i * 0.05;
            if (distance > limit + 0.05) {
                break;
            }
            MovementVector at = ledge.add(direction.scale(distance));
            CollisionWorld.Hit surface = TraversalProbes.top(world, at, 0.005, ledge.y() + 0.03, ledge.y() - 0.1);
            if (surface == null || Math.abs(surface.point().y() - ledge.y()) > 0.02) {
                return Math.max(0.05, distance); // Conservative far edge, including the sample tolerance.
            }
        }
        return -1;
    }

    private static CollisionBox region(MovementVector feet, MovementVector forward, double reach, double width, double height) {
        MovementVector far = feet.add(forward.scale(reach + MAX_END_ADVANCE + width));
        return new CollisionBox(new MovementVector(Math.min(feet.x(), far.x()) - width - 0.1, feet.y() - 2.5,
                Math.min(feet.z(), far.z()) - width - 0.1),
                new MovementVector(Math.max(feet.x(), far.x()) + width + 0.1, feet.y() + MAX_HEIGHT + height + 0.1,
                        Math.max(feet.z(), far.z()) + width + 0.1));
    }

    private VaultPlanner() {}
}
