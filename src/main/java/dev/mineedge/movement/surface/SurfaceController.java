package dev.mineedge.movement.surface;

import dev.mineedge.movement.AdvancedMoves;
import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.TraversalProbes;
import java.util.function.Consumer;

/** Ladder/pipe climbing and steep-surface sliding, resolved through swept motion. */
public final class SurfaceController {
    private static final double DT = MovementTuning.SUBSTEP_SECONDS;
    private static final double CLIMB_TRANSITION_SPEED = 2.5;
    public record Step(MovementController.Motion motion, MovementState.Mode mode, MovementBody body,
                       double time, SurfaceState state, boolean resetHistory) {}

    public static Step step(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                            boolean grounded, MovementState.Mode mode, MovementBody body, double time,
                            SurfaceState state, BlockMotionState blocks, MovementInput input, MovementTuning tuning,
                            boolean first, boolean eligible, Consumer<MovementEvent> events) {
        if (state.climb() != null && state.climb().route().pipe() && !tuning.features().pipeClimbing())
            return release(resolver, feet, velocity, state, blocks, input, events);
        if (state.path() != null) return path(resolver, feet, velocity, state, blocks, input, first, events);
        if (state.climb() != null) return climb(resolver, feet, velocity, state, blocks, input, first, events);
        if (mode == MovementState.Mode.STEEP_SLIDE) return slope(resolver, feet, velocity, time, state, blocks, input, tuning, first, events);
        if (!eligible) return null;
        if (state.climbCooldown() == 0 && body == MovementBody.STANDING && !input.jumpHeld() && !input.crouchHeld() && input.forward() >= -0.3
                && (mode != MovementState.Mode.GROUND || input.forward() > 0.3)) {
            var route = resolver.climbRoute(feet, null);
            if (route != null && (!route.pipe() || tuning.features().pipeClimbing())) {
                boolean above = feet.y() > route.lastHeight() + 0.1;
                boolean facingRoute = above ? !route.pipe() && TraversalProbes.facesClimb(input.forwardDirection(), route.normal().scale(-1))
                        : TraversalProbes.facesClimb(input.forwardDirection(), route.normal())
                            && feet.subtract(route.base()).dot(route.normal()) >= 0;
                if (facingRoute) {
                    double height = route.height(route.closest(feet.y()));
                    SurfacePath attachment = attachment(feet, route, height, above);
                    double hold = route.pipe() || velocity.y() < -2 || mode == MovementState.Mode.WALLRUN || above ? 0.5 : 0;
                    var climb = new SurfaceState.Climb(route, feet, hold, height, height, 0, 0, false);
                    var next = state.climbing(climb, attachment, velocity);
                    if (valid(resolver, attachment, route)) {
                        events.accept(route.pipe() ? MovementEvent.PIPE_GRAB : MovementEvent.LADDER_GRAB);
                        resolver.clearFallDistance();
                        return path(resolver, feet, velocity, next, blocks, input, false, events);
                    }
                }
            }
        }
        if (tuning.features().sliding() && (mode == MovementState.Mode.GROUND || mode == MovementState.Mode.AIR)
                && velocity.y() <= 0 && state.slopeCooldown() == 0) {
            var contact = resolver.surface(feet, body);
            if (contact != null && contact.steep() && resolver.canOccupy(feet, MovementBody.LOW)) {
                MovementVector down = contact.downhill().normalized();
                float yaw = AdvancedMoves.yaw(down);
                var along = down.scale(0.75 * velocity.length());
                events.accept(MovementEvent.STEEP_SLIDE_START);
                return slope(resolver, feet, along, 0, state.slope(down, yaw, 0, 0, along), blocks, input, tuning, first, events);
            }
        }
        return null;
    }

    private static SurfacePath attachment(MovementVector feet, ClimbRoute route, double height, boolean above) {
        MovementVector target = route.feet(height);
        if (above) {
            double clearanceHeight = Math.max(feet.y(), route.top()) + 0.02;
            var lift = new MovementVector(feet.x(), clearanceHeight, feet.z());
            var over = new MovementVector(target.x(), clearanceHeight, target.z());
            return new SurfacePath(SurfacePath.Kind.ENTER_TOP, feet, lift, over, target,
                    MovementVector.ZERO, null, route.source(), 1.67 / CLIMB_TRANSITION_SPEED, 0, MovementBody.STANDING);
        }
        MovementVector offset = target.subtract(feet);
        double duration = Math.max(0.1, Math.min(0.3, offset.length() / Math.max(2, offset.horizontalSpeed() / 0.15)));
        return new SurfacePath(SurfacePath.Kind.ATTACH, feet, feet.add(offset.scale(0.4)), feet.add(offset.scale(0.9)),
                target, MovementVector.ZERO, null, route.source(), duration / CLIMB_TRANSITION_SPEED, 0, MovementBody.STANDING);
    }

    private static Step path(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                             SurfaceState state, BlockMotionState blocks, MovementInput input, boolean first, Consumer<MovementEvent> events) {
        var path = state.path();
        boolean ladder = path.kind() != SurfacePath.Kind.STEP_UP;
        boolean displaced = feet.subtract(path.position(path.elapsed())).length() > 1.0E-5;
        boolean impulse = velocity.subtract(state.expectedVelocity()).length() > 1.0E-5;
        if (ladder && first && input.jumpPressed()) return release(resolver, feet, impulse ? velocity : MovementVector.ZERO, state, blocks, input, events);
        if (displaced || impulse || !valid(resolver, path, state.climb() == null ? null : state.climb().route())) {
            if (ladder) return release(resolver, feet, impulse ? velocity : MovementVector.ZERO, state, blocks, input, events);
            events.accept(MovementEvent.STEP_UP_ABORT);
            var body = MovementController.exitBody(resolver, feet, input.crouchHeld());
            var exit = impulse ? velocity : MovementVector.ZERO;
            resolver.synchronize(exit, false, body);
            return new Step(new MovementController.Motion(feet, exit, false, blocks, false),
                    MovementState.Mode.AIR, body, 0, state.release(false, MovementVector.ZERO), false);
        }
        double time = Math.min(path.duration(), path.elapsed() + DT);
        boolean done = time + 1.0E-9 >= path.duration();
        var target = path.position(time);
        var requested = done && path.support() != null ? target.add(new MovementVector(0, -1.0E-6, 0)) : target;
        var command = requested.subtract(feet).scale(1 / DT);
        if (ladder) resolver.clearFallDistance();
        resolver.body(path.body());
        var motion = resolver.resolve(feet, command, DT);
        if (motion.position().subtract(target).length() > 1.0E-5) {
            if (ladder) return release(resolver, motion.position(), MovementVector.ZERO, state, motion.blocks(), input, events);
            events.accept(MovementEvent.STEP_UP_ABORT);
            resolver.synchronize(MovementVector.ZERO, motion.grounded(), path.body());
            return new Step(new MovementController.Motion(motion.position(), MovementVector.ZERO, motion.grounded(), motion.blocks(), motion.resetFallDistance()),
                    motion.grounded() ? MovementState.Mode.GROUND : MovementState.Mode.AIR, path.body(), 0, state.release(false, MovementVector.ZERO), false);
        }
        if (done && (path.kind() == SurfacePath.Kind.ATTACH || path.kind() == SurfacePath.Kind.ENTER_TOP)) {
            var previousClimb = state.climb();
            var climb = new SurfaceState.Climb(previousClimb.route(), target, previousClimb.hold(), target.y(), target.y(), 0, 0, false);
            resolver.synchronize(MovementVector.ZERO, false, path.body());
            return new Step(new MovementController.Motion(target, MovementVector.ZERO, false, motion.blocks(), true), climbMode(previousClimb.route()), path.body(), 0,
                    state.climbing(climb, null, MovementVector.ZERO), true);
        }
        if (done) {
            double factor = command.horizontalSpeed() > 1.0E-9 ? motion.velocity().horizontalSpeed() / command.horizontalSpeed() : 1;
            var exit = path.exitVelocity().scale(Math.clamp(factor, 0, 1));
            if (ladder && input.forward() <= 0.3) exit = MovementVector.ZERO;
            exit = new MovementVector(exit.x(), Math.max(0, motion.velocity().y()), exit.z());
            var body = MovementController.exitBody(resolver, target, input.crouchHeld());
            resolver.synchronize(exit, motion.grounded(), body);
            events.accept(ladder ? MovementEvent.CLIMB_EXIT : MovementEvent.STEP_UP_COMPLETE);
            return new Step(new MovementController.Motion(target, exit, motion.grounded(), motion.blocks(), ladder || motion.resetFallDistance()),
                    motion.grounded() ? MovementState.Mode.GROUND : MovementState.Mode.AIR, body, 0, state.release(ladder, exit), true);
        }
        var next = state.path(path.at(time), motion.velocity());
        return new Step(new MovementController.Motion(motion.position(), motion.velocity(), motion.grounded(), motion.blocks(), ladder || motion.resetFallDistance()),
                ladder ? path.kind() == SurfacePath.Kind.EXIT_TOP ? MovementState.Mode.CLIMB_EXIT : MovementState.Mode.CLIMB_ATTACH : MovementState.Mode.STEP_UP,
                path.body(), time, next, ladder);
    }

    private static Step climb(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                              SurfaceState state, BlockMotionState blocks, MovementInput input, boolean first, Consumer<MovementEvent> events) {
        var climb = state.climb();
        var route = climb.route();
        if (!resolver.climbValid(route) || !resolver.canOccupy(feet, MovementBody.STANDING)
                || feet.subtract(climb.anchor()).length() > 1.0E-5 || velocity.length() > 1.0E-5 || first && input.jumpPressed())
            return release(resolver, feet, velocity, state, blocks, input, events);
        double hold = Math.max(0, climb.hold() - DT);
        double elapsed = climb.elapsed();
        double duration = climb.duration();
        double from = climb.from();
        double to = climb.to();
        double y = feet.y();
        boolean fast = climb.fast();
        double climbDt = DT * (input.sprintHeld() ? 2 : 1);
        boolean up = hold == 0 && input.forward() > 0.3;
        boolean down = hold == 0 && input.forward() < -0.3;
        boolean fullDown = down && input.forward() < -0.96 && !input.crouchHeld();
        if (duration > 0 && elapsed < duration) {
            elapsed = Math.min(duration, elapsed + climbDt);
            y = from + (to - from) * elapsed / duration;
        } else if (fast) {
            y -= 2 * climbDt;
            if (!fullDown) {
                fast = false;
                from = y;
                to = route.height(Math.max(0, (int) Math.floor((y - route.height(0)) / ClimbRoute.STEP)));
                duration = Math.max(DT, Math.abs(from - to));
                elapsed = 0;
                events.accept(MovementEvent.CLIMB_SLIDE_END);
            }
        } else if (fullDown && route.closest(y) > 4) {
            fast = true;
            events.accept(MovementEvent.CLIMB_SLIDE_START);
        } else if (up && route.closest(y) >= route.lastStep()) {
            var exit = exit(resolver, feet, route);
            if (exit != null) return path(resolver, feet, velocity, state.path(exit, MovementVector.ZERO), blocks, input, false, events);
        } else if (up || down) {
            int step = route.closest(y);
            int count = route.pipe() && (up ? route.lastStep() - step > 1 : step > 2) ? 2 : 1;
            if (down && step <= 1) return release(resolver, feet, velocity, state, blocks, input, events);
            from = y;
            to = route.height(step + (up ? count : -count));
            duration = Math.max(DT, Math.abs(to - from) / (route.pipe() ? 0.64 * count : 0.96));
            elapsed = 0;
            events.accept(MovementEvent.CLIMB_STEP);
        }
        if (y <= route.height(0) && down) return release(resolver, feet, velocity, state, blocks, input, events);
        if (hold == 0 && route.pipe() && Math.abs(input.strafe()) > 0.3) {
            // Route tangents point right when facing the pipe; Minecraft's positive strafe is left.
            var sideways = resolver.climbSideways(route, y, -input.strafe() * DT);
            if (sideways != null && resolver.climbValid(sideways)
                    && y >= sideways.height(0) && y <= sideways.lastHeight()
                    && from >= sideways.height(0) && from <= sideways.lastHeight()
                    && to >= sideways.height(0) && to <= sideways.lastHeight()) {
                var at = sideways.feet(y);
                var world = resolver.geometry(resolver.bounds(feet, MovementBody.STANDING).swept(at.subtract(feet), 0.01));
                if (world != null && resolver.canOccupy(at, MovementBody.STANDING)
                        && world.sweep(resolver.bounds(feet, MovementBody.STANDING), at.subtract(feet)) == null)
                    route = sideways;
            }
        }
        var target = route.feet(Math.clamp(y, route.height(0), route.lastHeight()));
        resolver.clearFallDistance();
        resolver.body(MovementBody.STANDING);
        var motion = resolver.resolve(feet, target.subtract(feet).scale(1 / DT), DT);
        if (motion.position().subtract(target).length() > 1.0E-5) return release(resolver, motion.position(), MovementVector.ZERO, state, motion.blocks(), input, events);
        resolver.synchronize(MovementVector.ZERO, false, MovementBody.STANDING);
        var next = new SurfaceState.Climb(route, target, hold, from, to, elapsed, duration, fast);
        return new Step(new MovementController.Motion(target, MovementVector.ZERO, false, motion.blocks(), true), climbMode(route), MovementBody.STANDING, 0,
                state.climbing(next, null, MovementVector.ZERO), true);
    }

    private static SurfacePath exit(MovementController.MotionResolver resolver, MovementVector feet, ClimbRoute route) {
        var front = route.normal().scale(-1);
        var side = new MovementVector(front.z(), 0, -front.x());
        for (var direction : new MovementVector[]{front, side, side.scale(-1), route.normal()}) {
            var motion = exit(resolver, feet, route, direction);
            if (motion != null) return motion;
        }
        return null;
    }

    private static SurfacePath exit(MovementController.MotionResolver resolver, MovementVector feet, ClimbRoute route,
                                    MovementVector direction) {
        var world = resolver.geometry(resolver.bounds(feet, MovementBody.STANDING).swept(direction.scale(2.2).add(new MovementVector(0, 1.5, 0)), 0.2));
        if (world == null) return null;
        double width = resolver.bounds(feet, MovementBody.STANDING).max().x() - resolver.bounds(feet, MovementBody.STANDING).min().x();
        int closeSamples = (int) Math.ceil((width + 0.15 - 0.02) / 0.05);
        for (int i = 0; i < 9 + closeSamples; i++) {
            double advance = i < 9 ? width + 0.15 + i * 0.15 : Math.max(0.02, width + 0.15 - (i - 8) * 0.05);
            var at = feet.add(direction.scale(advance));
            var floor = TraversalProbes.top(world, at, width / 2, route.top() + 0.05, route.top() - 1.2);
            if (floor == null) continue;
            var end = new MovementVector(at.x(), floor.point().y(), at.z());
            var body = resolver.canOccupy(end, MovementBody.STANDING) ? MovementBody.STANDING : MovementBody.CROUCHING;
            if (!resolver.canOccupy(end, body)) continue;
            double high = Math.max(route.top(), Math.max(feet.y(), end.y())) + 0.08;
            var lift = new MovementVector(feet.x(), high, feet.z());
            var over = new MovementVector(end.x(), high, end.z());
            var motion = new SurfacePath(SurfacePath.Kind.EXIT_TOP, feet, lift, over, end, direction.scale(3), floor.shape(), route.source(),
                    (route.pipe() ? 1.83 : 1.53) / CLIMB_TRANSITION_SPEED, 0, body);
            if (valid(resolver, motion, route)) return motion;
        }
        return null;
    }

    private static Step release(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                                SurfaceState state, BlockMotionState blocks, MovementInput input, Consumer<MovementEvent> events) {
        events.accept(MovementEvent.CLIMB_RELEASE);
        var body = MovementController.exitBody(resolver, feet, input.crouchHeld());
        resolver.clearFallDistance();
        resolver.body(body);
        var world = resolver.geometry(resolver.bounds(feet, body).swept(new MovementVector(0, -0.32, 0), 0.01));
        var hit = world == null ? null : world.sweep(resolver.bounds(feet, body), new MovementVector(0, -0.32, 0));
        boolean snap = hit != null && hit.normal().y() >= 0.7 && velocity.y() <= 0;
        var motion = snap ? resolver.resolve(feet, new MovementVector(0, -0.32 / DT, 0), DT)
                : new MovementController.Motion(feet, velocity, false, blocks, true);
        resolver.synchronize(motion.velocity(), motion.grounded(), body);
        return new Step(new MovementController.Motion(motion.position(), motion.velocity(), motion.grounded(), motion.blocks(), true),
                motion.grounded() ? MovementState.Mode.GROUND : MovementState.Mode.AIR, body, 0, state.release(true, motion.velocity()), true);
    }

    private static Step slope(MovementController.MotionResolver resolver, MovementVector feet, MovementVector velocity,
                              double time, SurfaceState state, BlockMotionState blocks, MovementInput input, MovementTuning tuning,
                              boolean first, Consumer<MovementEvent> events) {
        var contact = resolver.surface(feet, MovementBody.LOW);
        double air = contact == null ? state.slopeAir() + DT : 0;
        double stopped = velocity.length() < 0.05 ? state.stalled() + DT : 0;
        boolean disturbed = time > 0 && velocity.subtract(state.expectedVelocity()).length() > 1.0E-5;
        if (!tuning.features().sliding() || disturbed || contact != null && !contact.steep() || air > 0.5 || stopped > 0.5) {
            var body = MovementController.exitBody(resolver, feet, input.crouchHeld());
            boolean grounded = contact != null && !contact.steep() && velocity.y() <= 0;
            resolver.synchronize(velocity, grounded, body);
            events.accept(MovementEvent.STEEP_SLIDE_END);
            return new Step(new MovementController.Motion(feet, velocity, grounded, blocks, false),
                    grounded ? MovementState.Mode.GROUND : MovementState.Mode.AIR, body, 0, state.slopeExit(), false);
        }
        MovementVector down = contact != null ? contact.downhill().normalized() : state.downhill();
        var across = new MovementVector(down.z(), 0, -down.x());
        double acceleration = contact == null ? 0 : tuning.gravity() * 0.5 * contact.normal().y() * Math.sqrt(1 - contact.normal().y() * contact.normal().y());
        velocity = velocity.add(down.scale(acceleration * DT)).add(across.scale(input.strafe() * 3.5 * DT))
                .add(new MovementVector(0, -tuning.gravity() * 0.5 * DT, 0)).clampLength(10);
        resolver.body(MovementBody.LOW);
        var motion = resolver.resolve(feet, velocity, DT);
        float currentYaw = first || time == 0 ? input.yaw() : state.slopeYaw();
        float yaw = currentYaw + AdvancedMoves.wrapped(AdvancedMoves.yaw(down) - currentYaw) * (float) Math.min(1, DT / 0.5);
        resolver.facing(yaw);
        var next = state.slope(down, yaw, air, stopped, motion.velocity());
        return new Step(motion, MovementState.Mode.STEEP_SLIDE, MovementBody.LOW, time + DT, next, false);
    }

    private static boolean valid(MovementController.MotionResolver resolver, SurfacePath path, ClimbRoute route) {
        if (route != null && !resolver.climbValid(route)) return false;
        if (path.kind() == SurfacePath.Kind.STEP_UP && !resolver.stepSource(path.source())) return false;
        var region = resolver.bounds(path.from(), path.body()).swept(path.end().subtract(path.from()), 1.5);
        var world = resolver.geometry(region);
        if (world == null || path.support() != null && !world.contains(path.support()) || world.overlaps(resolver.bounds(path.end(), path.body()))) return false;
        MovementVector previous = path.position(path.elapsed());
        double[] times = {path.duration() * 0.4, path.duration() * 0.9, path.duration()};
        for (double boundary : times) {
            if (boundary <= path.elapsed() + 1.0E-9) continue;
            var next = path.position(boundary);
            var body = resolver.bounds(previous, path.body());
            if (world.overlaps(body) || world.sweep(body, next.subtract(previous)) != null || world.overlaps(resolver.bounds(next, path.body()))) return false;
            previous = next;
        }
        return true;
    }
    private static MovementState.Mode climbMode(ClimbRoute route) {
        return route.pipe() ? MovementState.Mode.PIPE_CLIMB : MovementState.Mode.LADDER_CLIMB;
    }

    private SurfaceController() {}
}
