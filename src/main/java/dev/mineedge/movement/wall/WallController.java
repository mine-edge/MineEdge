package dev.mineedge.movement.wall;

import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.AdvancedState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionWorld;
import dev.mineedge.movement.collision.TraversalProbes;
import dev.mineedge.movement.traversal.TraversalPath;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.traversal.VaultPlanner;
import dev.mineedge.movement.traversal.VaultType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Consumer;

/** Feet-space adaptation of faith_move/controller.rs::air_transitions, find_wallrun, wallrun,
 * wallclimb and hang. Constants are tuning.rs defaults (100 Unreal units = one block). */
public final class WallController {
    private static final double DT = MovementTuning.SUBSTEP_SECONDS;
    private static final double HANDS = 1.828, SETTLE = 0.5;
    private static final double DROP_WALL_HEIGHT = 1.5;

    public record Step(MovementController.Motion motion, MovementState.Mode mode, MovementBody body,
                       double time, WallState wall, TraversalState traversal, AdvancedState.LedgeMove ledge) {
        public Step(MovementController.Motion motion, MovementState.Mode mode, MovementBody body,
                    double time, WallState wall, TraversalState traversal) {
            this(motion, mode, body, time, wall, traversal, null);
        }
    }

    public static boolean active(MovementState.Mode mode) {
        return mode == MovementState.Mode.WALLRUN || mode == MovementState.Mode.WALLCLIMB
                || mode == MovementState.Mode.WALLCLIMB_TURNED || mode == MovementState.Mode.LEDGE_HANG;
    }

    public static Step step(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                            MovementState.Mode mode, MovementBody body, double time, WallState state,
                            BlockMotionState blocks, MovementInput input, MovementTuning tuning, double jumpBuffer,
                              boolean first, boolean wallTurned, boolean returnInterrupted, Consumer<MovementEvent> events) {
        if (mode == MovementState.Mode.WALLRUN && !tuning.features().wallRunning())
            return release(resolver, feet, velocity, blocks, input, state, events);
        CollisionBox bounds = resolver.bounds(feet, MovementBody.STANDING);
        double width = bounds.max().x() - bounds.min().x();
        // Reach/headroom/pull-up footprint only; the longer ground-ahead trace has its own region.
        CollisionWorld world = resolver.geometry(bounds.swept(new MovementVector(0, 2, 0), Math.max(1.1, width + 0.15)));
        if (world == null) {
            return active(mode) ? release(resolver, feet, velocity, blocks, input, state, events) : null;
        }
        MovementVector forward = input.forwardDirection();
        MovementVector horizontal = new MovementVector(velocity.x(), 0, velocity.z());
        double speed = horizontal.length();
        MovementVector normal = state.normal();
        if (active(mode)) {
            boolean disturbed = feet.subtract(state.anchor()).length() > 1.0E-5
                    || velocity.subtract(state.expectedVelocity()).length() > 1.0E-5;
            if (disturbed || !world.contains(state.support()) || world.overlaps(resolver.bounds(feet, body))) {
                return release(resolver, feet, velocity, blocks, input, state, events);
            }
            if (mode == MovementState.Mode.LEDGE_HANG && !returnInterrupted && grounded(world, resolver.bounds(feet, body))) {
                body = MovementController.exitBody(resolver, feet, input.crouchHeld());
                events.accept(MovementEvent.LAND);
                resolver.synchronize(MovementVector.ZERO, true, body);
                return new Step(new MovementController.Motion(feet, MovementVector.ZERO, true, blocks, false),
                        MovementState.Mode.GROUND, body, 0, WallState.EMPTY, TraversalState.EMPTY);
            }
            time += DT;
            if (mode == MovementState.Mode.LEDGE_HANG) {
                if (!returnInterrupted && !TraversalProbes.reachableLedge(world, feet, normal, state.support()))
                    return release(resolver, feet, velocity, blocks, input, state, events);
                // A return interruption still grips the lip, even while feet are above its platform.
                if (!returnInterrupted && shortWall(resolver, feet, state.support().max().y())) {
                    events.accept(MovementEvent.LEDGE_DROP);
                    return detached(resolver, feet, MovementVector.ZERO, blocks, input, state.release(false, feet, 0.35));
                }
                if (first && input.crouchPressed()) {
                    events.accept(MovementEvent.LEDGE_DROP);
                    return detached(resolver, feet, normal.scale(0.5), blocks, input, state.release(false, feet, 0.35));
                }
                if (forward.dot(normal.scale(-1)) < 0 && jumpBuffer > 0) {
                    double height = headroom(world, bounds, 1.6);
                    events.accept(MovementEvent.WALL_KICK);
                    return detached(resolver, feet, normal.scale(4).add(new MovementVector(0, Math.sqrt(2 * tuning.gravity() * height), 0)),
                            blocks, input, state.release(true, feet, 0.4));
                }
                if (tuning.features().mantling() && input.jumpHeld() && time >= SETTLE && (jumpBuffer > 0 || input.forward() > 0.5)
                        && forward.dot(normal.scale(-1)) >= 0) {
                    TraversalPath path = pullUp(resolver, world, feet, width, state);
                    if (path != null) {
                        events.accept(MovementEvent.PULL_UP_START);
                        resolver.synchronize(MovementVector.ZERO, false, path.body());
                        return new Step(new MovementController.Motion(feet, MovementVector.ZERO, false, blocks, false),
                                MovementState.Mode.PULL_UP, path.body(), 0, state.release(false, feet, 0.35),
                                new TraversalState(path, 0, MovementVector.ZERO));
                    }
                }
                resolver.synchronize(MovementVector.ZERO, false, body);
                return new Step(new MovementController.Motion(feet, MovementVector.ZERO, false, blocks, false), mode, body, time,
                        state.contact(normal, state.support(), feet, MovementVector.ZERO, false, state.chain()), TraversalState.EMPTY);
            }
            if (mode == MovementState.Mode.WALLCLIMB_TURNED) {
                if (jumpBuffer > 0) {
                    double height = headroom(world, bounds, 2.5);
                    events.accept(MovementEvent.WALL_KICK);
                    return detached(resolver, feet, normal.scale(4).add(new MovementVector(0, Math.sqrt(2 * tuning.gravity() * height), 0)),
                            blocks, input, state.release(true, feet, 0.4));
                }
                if (time > 0.6) return release(resolver, feet, velocity, blocks, input, state, events);
                var contact = wall(world, feet, width, normal.scale(-1), 0.12, 0.3);
                if (contact == null) return release(resolver, feet, velocity, blocks, input, state, events);
                velocity = normal.scale(-0.3).add(new MovementVector(0, Math.min(velocity.y(), 0.5) - tuning.gravity() * 0.25 * DT, 0));
                return integrate(resolver, feet, velocity, mode, time, body, state, contact, events);
            }
            if (mode == MovementState.Mode.WALLCLIMB) {
                Step grab = grab(resolver, world, feet, velocity, width, normal, state, blocks, 0.9, events);
                if (grab != null) return grab;
            }
            CollisionWorld.Hit contact = wall(world, feet, width, normal.scale(-1), 0.12, 0.3);
            if (contact == null || contact.normal().dot(normal) < 0.9
                    || (first && input.crouchPressed()) || input.forward() < -0.3
                    || (mode == MovementState.Mode.WALLCLIMB ? velocity.y() <= 0 : velocity.y() < -5)) {
                return release(resolver, feet, velocity, blocks, input, state, events);
            }
            if (mode == MovementState.Mode.WALLRUN && jumpBuffer > 0 && time > 0.1) {
                double push = wallTurned ? 1 : Math.max(0, forward.dot(normal));
                double height = headroom(world, bounds, 1 + 0.6 * push);
                MovementVector along = horizontal.subtract(normal.scale(horizontal.dot(normal)));
                MovementVector launch = normal.scale(1.2 + 4 * push).add(along.scale(1 - 0.9 * push))
                        .add(new MovementVector(0, Math.sqrt(2 * tuning.gravity() * height) / (1 + state.chain()), 0));
                events.accept(MovementEvent.WALL_JUMP);
                return detached(resolver, feet, launch, blocks, input, state.release(true, feet, 0.1));
            }
            if (mode == MovementState.Mode.WALLRUN) {
                MovementVector along = horizontal.subtract(normal.scale(horizontal.dot(normal)));
                if (along.length() < 0.1) return release(resolver, feet, velocity, blocks, input, state, events);
                double pull = (velocity.y() > 0 ? 8.2 : 5) * (1 + state.chain());
                velocity = along.scale(1 - 0.05 * DT).add(normal.scale(-0.5)).add(new MovementVector(0, velocity.y() - pull * DT, 0));
            } else {
                velocity = normal.scale(-0.3).add(new MovementVector(0, velocity.y() - 8 * DT, 0));
            }
            return integrate(resolver, feet, velocity, mode, time, body, state, contact, events);
        }
        if (state.cooldown() > 0 || body != MovementBody.STANDING || input.crouchHeld()) return null;
        // The executable reference order is vault (caller), wallclimb, ledge grab, wallrun.
        CollisionWorld.Hit front = wall(world, feet, width, forward, 0.08, 1.16);
        if (input.forward() > 0.8 && state.jumped() && !state.climbed() && velocity.y() > 0
                && horizontal.dot(forward) >= 0 && front != null
                && forward.dot(front.normal().scale(-1)) >= Math.cos(Math.toRadians(33))) {
            CollisionWorld.Hit tall = wall(world, feet, width, forward, 0.1, 1.78);
            if (tall != null && tall.normal().dot(front.normal()) > 0.9) {
                double xy = Math.clamp((speed - tuning.walkSpeed() / 4 - tuning.walkSpeed()) / Math.max(0.001, 2.5 * tuning.walkSpeed() / 4), 0, 1);
                double z = Math.clamp(velocity.y() / 3.2, 0, 1);
                double boost = Math.sqrt(4 * (0.6 * xy + 1.3 * z) * 8);
                events.accept(MovementEvent.WALLCLIMB_START);
                state = state.contact(front.normal(), front.shape(), feet, velocity, true, state.chain());
                return attach(resolver, feet, new MovementVector(0, boost, 0).add(front.normal().scale(-0.3)),
                        MovementState.Mode.WALLCLIMB, 0, body, state, front, events);
            }
        }
        front = ledgeFace(world, feet, width, forward, 1.48);
        if (front != null && TraversalProbes.facesClimb(forward, front.normal()) && velocity.y() < 2.5) {
            Step grab = grab(resolver, world, feet, velocity, width, front.normal(), state, blocks, 1.48, events);
            if (grab != null) return grab;
        }
        double scale = tuning.walkSpeed() / 4;
        if (!tuning.features().wallRunning() || !input.sprintHeld() || scale <= 0 || speed < 2 * scale
                || horizontal.dot(forward) < 0 || input.forward() <= 0.3 || velocity.y() <= -5) return null;
        CollisionWorld.Hit hit = null;
        double reach = 0.5;
        if (state.jumped() && feet.subtract(state.jumpOrigin()).length() < 0.3 && Math.abs(input.strafe()) > 0.3) {
            MovementVector side = new MovementVector(forward.z(), 0, -forward.x()).scale(Math.signum(input.strafe()));
            CollisionWorld.Hit hi = wall(world, feet, width, side, reach, 1.90);
            CollisionWorld.Hit lo = wall(world, feet, width, side, reach, 0.37);
            if (hi != null && lo != null && hi.normal().dot(lo.normal()) > 0.999
                    && Math.abs(hi.normal().dot(side)) >= 0.5
                    && Math.abs(hi.point().subtract(lo.point()).dot(hi.normal())) < 0.44) hit = hi;
        }
        if (hit == null) {
            reach = Math.clamp(0.5 * (1 + Math.max(0, speed / scale - 4) / 3.2), 0.5, 1);
            CollisionWorld.Hit mid = wall(world, feet, width, forward, reach, 0.9);
            if (mid != null) {
                double into = -mid.normal().dot(forward);
                CollisionWorld.Hit tall = wall(world, feet, width, forward, reach, 1.90 - (1 - into) * 0.5);
                if (into > 0 && into <= Math.cos(Math.toRadians(33)) && tall != null && tall.normal().dot(mid.normal()) > 0.9) hit = mid;
            }
        }
        if (hit == null || state.previousNormal().dot(hit.normal()) > 0.9 || horizontal.dot(hit.normal()) >= -1.0E-6) return null;
        normal = hit.normal();
        MovementVector along = new MovementVector(normal.z(), 0, -normal.x());
        if (along.dot(forward) < 0) along = along.scale(-1);
        if (horizontal.dot(along) < 0) return null;
        if (velocity.y() < 0) {
            MovementVector start = feet.add(new MovementVector(0, -0.9, 0));
            MovementVector delta = along.scale(Math.min(3.6, speed * 0.5)).add(new MovementVector(0, -0.45, 0));
            CollisionBox probe = CollisionBox.body(start, 0.04, 0.04);
            CollisionWorld ground = resolver.geometry(probe.swept(delta, 0.01));
            if (ground == null || ground.overlaps(probe) || ground.sweep(probe, delta) != null) return null;
        }
        int chain = state.wallJump() ? state.chain() + 1 : 0;
        double rise = Math.min(headroom(world, bounds, 1.7), Math.max(0, 1.7 - (feet.y() - state.jumpOrigin().y())));
        double vy = velocity.y() > 0 && !state.wallJump() ? Math.sqrt(2 * rise * 8.2) : velocity.y();
        MovementVector begin = along.scale(velocity.y() > 0 ? Math.max(speed, 3 * scale) : speed)
                .add(normal.scale(-0.5)).add(new MovementVector(0, vy, 0));
        events.accept(MovementEvent.WALLRUN_START);
        state = state.contact(normal, hit.shape(), feet, velocity, false, chain);
        return attach(resolver, feet, begin, MovementState.Mode.WALLRUN, 0, body, state, hit, events);
    }

    /** Release a traversal by clearing the lip and sweeping down; pull-ups use a short return animation. */
    public static Step dropToHang(MovementController.MotionResolver resolver, MovementVector feet, TraversalPath path,
                                   WallState state, BlockMotionState blocks, MovementInput input, Consumer<MovementEvent> events) {
        if (input.crouchHeld()) return null;
        var bounds = resolver.bounds(feet, MovementBody.STANDING);
        double width = bounds.max().x() - bounds.min().x();
        CollisionWorld world = resolver.geometry(path.region(width, MovementBody.STANDING.height())
                .swept(new MovementVector(0, -HANDS - 0.35, 0), 0.1));
        if (world == null || !world.contains(path.support())) return null;
        double top = path.support().max().y();
        var probe = new MovementVector(path.from().x(), top - HANDS, path.from().z());
        var direction = path.over().subtract(path.hand());
        direction = new MovementVector(direction.x(), 0, direction.z()).normalized();
        var face = wall(world, probe, width, direction, VaultPlanner.MAX_REACH, HANDS - 0.02);
        if (face == null || !face.shape().equals(path.support())) return null;
        var normal = face.normal();
        double radius = (Math.abs(normal.x()) * (bounds.max().x() - bounds.min().x())
                + Math.abs(normal.z()) * (bounds.max().z() - bounds.min().z())) / 2;
        var atFace = feet.add(normal.scale(face.point().subtract(feet).dot(normal)));
        var ledge = TraversalProbes.top(world, atFace.subtract(normal.scale(0.03)), 0.01, top + 0.05, top - 0.05);
        if (ledge == null || !ledge.shape().equals(path.support())) return null;
        var target = atFace.add(normal.scale(radius + 0.01));
        target = new MovementVector(target.x(), top - HANDS, target.z());
        MovementBody body = MovementBody.STANDING;
        if (world.overlaps(resolver.bounds(target, body))) {
            body = MovementBody.CROUCHING;
        }
        if (target.y() > feet.y() + 1.0E-5 || feet.y() - target.y() > HANDS + 0.35
                || target.subtract(feet).horizontalSpeed() > 1.1) return null;
        var outside = new MovementVector(target.x(), feet.y(), target.z());
        if (!TraversalProbes.reachableLedge(world, target, normal, ledge.shape())
                || world.overlaps(resolver.bounds(target, body)) || shortWall(resolver, target, top)
                || world.overlaps(resolver.bounds(feet, MovementBody.CROUCHING))
                || world.overlaps(resolver.bounds(outside, MovementBody.CROUCHING))
                || world.sweep(resolver.bounds(feet, MovementBody.CROUCHING), outside.subtract(feet)) != null
                 || world.sweep(resolver.bounds(outside, MovementBody.CROUCHING), target.subtract(outside)) != null) return null;
        if (path.kind() == VaultType.PULL_UP) {
            var move = new AdvancedState.LedgeMove(AdvancedState.LedgeKind.RETURN, feet, outside, target, normal,
                    ledge.shape(), path.support(), 0, 0.2, input.yaw(), 0);
            resolver.synchronize(MovementVector.ZERO, false, MovementBody.CROUCHING);
            return new Step(new MovementController.Motion(feet, MovementVector.ZERO, false, blocks, false),
                    MovementState.Mode.LEDGE_HANG, MovementBody.CROUCHING, 0,
                    state.contact(normal, ledge.shape(), feet, MovementVector.ZERO, false, state.chain()), TraversalState.EMPTY, move);
        }
        resolver.body(MovementBody.CROUCHING);
        var retreat = outside.subtract(feet).length() <= 1.0E-5
                ? new MovementController.Motion(feet, MovementVector.ZERO, false, blocks, false)
                : resolver.resolve(feet, outside.subtract(feet).scale(1 / DT), DT);
        if (retreat.position().subtract(outside).length() > 1.0E-5)
            return release(resolver, retreat.position(), MovementVector.ZERO, retreat.blocks(), input, state, events);
        var drop = resolver.resolve(retreat.position(), target.subtract(retreat.position()).scale(1 / DT), DT);
        if (drop.position().subtract(target).length() > 1.0E-5)
            return release(resolver, drop.position(), MovementVector.ZERO, drop.blocks(), input, state, events);
        events.accept(MovementEvent.LEDGE_GRAB);
        resolver.synchronize(MovementVector.ZERO, false, body);
        return new Step(new MovementController.Motion(drop.position(), MovementVector.ZERO, false, drop.blocks(),
                retreat.resetFallDistance() || drop.resetFallDistance()), MovementState.Mode.LEDGE_HANG, body, 0,
                state.contact(normal, ledge.shape(), drop.position(), MovementVector.ZERO, false, state.chain()), TraversalState.EMPTY);
    }

    private static Step grab(MovementController.MotionResolver resolver, CollisionWorld world, MovementVector feet,
                             MovementVector velocity, double width, MovementVector normal, WallState state,
                             BlockMotionState blocks, double low, Consumer<MovementEvent> events) {
        CollisionWorld.Hit face = ledgeFace(world, feet, width, normal.scale(-1), low);
        if (face == null) return null;
        MovementVector inside = face.point().subtract(normal.scale(0.03));
        CollisionWorld.Hit top = TraversalProbes.top(world, inside, 0.01, feet.y() + HANDS + 0.35, feet.y() + low);
        if (top == null) return null;
        MovementVector target = feet.subtract(normal.scale(face.fraction() * 0.35))
                .add(new MovementVector(0, top.point().y() - HANDS - feet.y(), 0));
        MovementBody body = MovementBody.STANDING;
        if (world.overlaps(resolver.bounds(target, body))) {
            body = MovementBody.CROUCHING;
        }
        if (!TraversalProbes.reachableLedge(world, target, normal, top.shape())
                || world.overlaps(resolver.bounds(target, body)) || shortWall(resolver, target, top.point().y())
                || world.sweep(resolver.bounds(feet, body), target.subtract(feet)) != null) return null;
        resolver.body(body);
        var moved = resolver.resolve(feet, target.subtract(feet).scale(1 / DT), DT);
        if (moved.position().subtract(target).length() > 1.0E-5) {
            return release(resolver, moved.position(), moved.velocity(), moved.blocks(), MovementInput.idle(0, 0, 0), state, events);
        }
        events.accept(MovementEvent.LEDGE_GRAB);
        resolver.synchronize(MovementVector.ZERO, false, body);
        return new Step(new MovementController.Motion(target, MovementVector.ZERO, false, moved.blocks(), moved.resetFallDistance()),
                MovementState.Mode.LEDGE_HANG, body, 0, state.contact(normal, top.shape(), target, MovementVector.ZERO, false, state.chain()), TraversalState.EMPTY);
    }

    public static boolean canPullUp(MovementController.MotionResolver resolver, MovementVector feet, WallState state) {
        var bounds = resolver.bounds(feet, MovementBody.STANDING);
        double width = bounds.max().x() - bounds.min().x();
        var world = resolver.geometry(bounds.swept(new MovementVector(0, 2, 0), Math.max(1.1, width + 0.15)));
        return world != null && pullUp(resolver, world, feet, width, state) != null;
    }

    private static TraversalPath pullUp(MovementController.MotionResolver resolver, CollisionWorld world,
                                         MovementVector feet, double width, WallState state) {
        var straight = pullUp(resolver, world, feet, width, state, MovementVector.ZERO);
        if (straight != null) return straight;
        // An off-centre grip can put the straight landing footprint into a neighbouring
        // wall. Try nearby shape edges, aligning outside the lip before moving onto it.
        var along = new MovementVector(-state.normal().z(), 0, state.normal().x());
        double radius = width / 2;
        double limit = radius + 0.15;
        double top = state.support().max().y();
        var region = resolver.bounds(new MovementVector(feet.x(), top, feet.z()), MovementBody.STANDING)
                .swept(state.normal().scale(-width - 0.15), limit);
        var offsets = new ArrayList<Double>();
        double origin = feet.dot(along);
        for (var shape : world.shapes(region)) {
            if (shape.max().y() <= top + 1.0E-6 || shape.min().y() >= top + MovementBody.STANDING.height() + 0.08) continue;
            double a = shape.min().dot(along), b = shape.max().dot(along);
            for (double offset : new double[]{Math.min(a, b) - radius - 0.01 - origin,
                    Math.max(a, b) + radius + 0.01 - origin}) {
                if (Math.abs(offset) > 1.0E-6 && Math.abs(offset) <= limit && !offsets.contains(offset)) offsets.add(offset);
            }
        }
        offsets.sort(Comparator.<Double>comparingDouble(Math::abs).thenComparingDouble(Double::doubleValue));
        for (double offset : offsets) {
            var path = pullUp(resolver, world, feet, width, state, along.scale(offset));
            if (path != null) return path;
        }
        return null;
    }

    private static TraversalPath pullUp(MovementController.MotionResolver resolver, CollisionWorld world,
                                         MovementVector feet, double width, WallState state, MovementVector alignment) {
        double top = state.support().max().y();
        double advance = width + 0.15;
        var aligned = feet.add(alignment);
        int samples = (int) Math.ceil((advance - 0.02) / 0.05);
        for (int i = 0; i <= samples; i++) {
            var at = aligned.subtract(state.normal().scale(Math.max(0.02, advance - i * 0.05)));
            var floor = TraversalProbes.top(world, at, width / 2, top + 0.9, top - 0.1);
            if (floor == null) floor = TraversalProbes.top(world, at, width / 2, top + 0.03, top - 0.1);
            if (floor == null) continue;
            var end = new MovementVector(at.x(), floor.point().y(), at.z());
            var hand = new MovementVector(aligned.x(), Math.max(top, end.y()) + 0.08, aligned.z());
            var over = new MovementVector(end.x(), hand.y(), end.z());
            var path = new TraversalPath(VaultType.PULL_UP, feet, hand, over, end, MovementVector.ZERO, state.support(), floor.shape());
            var fitted = VaultPlanner.fit(resolver, world, path);
            if (fitted != null && VaultPlanner.valid(resolver, fitted, 0)) return fitted;
        }
        return null;
    }

    private static CollisionWorld.Hit ledgeFace(CollisionWorld world, MovementVector feet, double width,
                                               MovementVector direction, double low) {
        var face = wall(world, feet, width, direction, 0.35, 0.7);
        if (face != null) return face;
        // Floating trapdoors and other thin ledges have no lower wall to catch.
        // Probe their edges near hand height; grab() still validates the top and approach.
        for (int i = 0; i < 32; i++) {
            double height = low - 0.2 + i * 0.05;
            if (height > HANDS + 0.35) break;
            face = wall(world, feet, width, direction, 0.35, height);
            if (face != null) return face;
        }
        return null;
    }

    private static CollisionWorld.Hit wall(CollisionWorld world, MovementVector feet, double width,
                                           MovementVector direction, double reach, double height) {
        CollisionBox slice = CollisionBox.body(feet.add(new MovementVector(0, height, 0)), width, 0.02);
        CollisionWorld.Hit hit = world.sweep(slice, direction.scale(reach));
        return hit != null && Math.abs(hit.normal().y()) < 0.1 && hit.normal().dot(direction) < -0.01 ? hit : null;
    }

    /** Measure the wall above the floor outside its lip, independent of constituent block shapes. */
    private static boolean shortWall(MovementController.MotionResolver resolver, MovementVector feet, double top) {
        double reach = feet.y() - top + DROP_WALL_HEIGHT + 1.0E-6;
        if (reach <= 0) return false;
        var bounds = resolver.bounds(feet, MovementBody.STANDING);
        var probe = new CollisionBox(bounds.min(), new MovementVector(bounds.max().x(), feet.y() + 0.02, bounds.max().z()));
        var down = new MovementVector(0, -reach, 0);
        CollisionWorld ground = resolver.geometry(probe.swept(down, 0.01));
        if (ground == null) return false;
        if (ground.overlaps(probe)) return true;
        var hit = ground.sweep(probe, down);
        return hit != null && hit.normal().y() >= 0.7 && top - hit.point().y() <= DROP_WALL_HEIGHT + 1.0E-6;
    }

    private static boolean grounded(CollisionWorld world, CollisionBox bounds) {
        var hit = world.sweep(bounds, new MovementVector(0, -0.02, 0));
        return hit != null && hit.normal().y() >= 0.7 && hit.fraction() < 0.01;
    }

    private static double headroom(CollisionWorld world, CollisionBox bounds, double height) {
        var hit = world.sweep(bounds, new MovementVector(0, height, 0));
        return hit == null ? height : Math.max(0, height * hit.fraction() - 0.01);
    }

    private static Step integrate(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                                   MovementState.Mode mode, double time, MovementBody body, WallState state,
                                   CollisionWorld.Hit contact, Consumer<MovementEvent> events) {
        return integrate(resolver, feet, velocity, mode, time, body, state, contact, 0, events);
    }

    private static Step attach(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                               MovementState.Mode mode, double time, MovementBody body, WallState state,
                               CollisionWorld.Hit contact, Consumer<MovementEvent> events) {
        var bounds = resolver.bounds(feet, body); var normal = contact.normal();
        double radius = (Math.abs(normal.x()) * (bounds.max().x() - bounds.min().x())
                + Math.abs(normal.z()) * (bounds.max().z() - bounds.min().z())) / 2;
        double gap = Math.max(0, feet.subtract(contact.point()).dot(normal) - radius);
        return integrate(resolver, feet, velocity, mode, time, body, state, contact, gap, events);
    }

    private static Step integrate(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                                  MovementState.Mode mode, double time, MovementBody body, WallState state,
                                  CollisionWorld.Hit contact, double gap, Consumer<MovementEvent> events) {
        resolver.body(body);
        var normal = contact.normal();
        var motion = resolver.resolve(feet, velocity.subtract(normal.scale(gap / DT)), DT);
        if (gap > 0) {
            // Attachment is a swept position correction, not momentum. At a corner
            // the probed face can end before the body reaches it, leaving the snap
            // unclipped; never carry that correction into a run, release or new face.
            double excess = Math.min(0, motion.velocity().dot(normal) - velocity.dot(normal));
            if (excess < 0) {
                var physical = motion.velocity().subtract(normal.scale(excess));
                resolver.synchronize(physical, motion.grounded(), body);
                motion = new MovementController.Motion(motion.position(), physical, motion.grounded(), motion.blocks(), motion.resetFallDistance());
            }
        }
        if (motion.grounded()) {
            events.accept(MovementEvent.LAND);
            return new Step(motion, MovementState.Mode.GROUND, body, 0, WallState.EMPTY, TraversalState.EMPTY);
        }
        return new Step(motion, mode, body, time, state.contact(contact.normal(), contact.shape(), motion.position(), motion.velocity(),
                mode == MovementState.Mode.WALLCLIMB, state.chain()), TraversalState.EMPTY);
    }

    private static Step release(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                                BlockMotionState blocks, MovementInput input, WallState state, Consumer<MovementEvent> events) {
        events.accept(MovementEvent.WALL_DETACH);
        return detached(resolver, feet, velocity, blocks, input, state.release(false, feet, 0.15));
    }

    private static Step detached(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                                 BlockMotionState blocks, MovementInput input, WallState state) {
        MovementBody body = MovementController.exitBody(resolver, feet, input.crouchHeld());
        resolver.synchronize(velocity, false, body);
        return new Step(new MovementController.Motion(feet, velocity, false, blocks, false), MovementState.Mode.AIR, body, 0, state, TraversalState.EMPTY);
    }

    private WallController() {}
}
