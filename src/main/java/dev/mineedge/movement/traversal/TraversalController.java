package dev.mineedge.movement.traversal;

import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionWorld;

/** Three bounded scripted phases, resolving every displacement through the normal collision owner. */
public final class TraversalController {
    public record Step(MovementController.Motion motion, TraversalState traversal, MovementState.Mode mode,
                       MovementBody body, double time, boolean completed, boolean aborted) {}

    public static Step step(TraversalState state, double elapsed, MovementVector position, MovementVector velocity,
                            BlockMotionState blocks, MovementInput input, MovementController.MotionResolver resolver) {
        return step(state, elapsed, position, velocity, blocks, input, resolver, MovementTuning.DEFAULT);
    }

    public static Step step(TraversalState state, double elapsed, MovementVector position, MovementVector velocity,
                            BlockMotionState blocks, MovementInput input, MovementController.MotionResolver resolver,
                            MovementTuning tuning) {
        TraversalPath path = state.path();
        boolean displaced = position.subtract(path.position(elapsed)).length() > 1.0E-5;
        boolean impulse = velocity.subtract(state.expectedVelocity()).length() > 1.0E-5;
        if (!tuning.features().allowsTraversal(path.kind()) || displaced || impulse || !VaultPlanner.valid(resolver, path, elapsed)) {
            return abort(resolver, position, impulse ? velocity : MovementVector.ZERO, blocks, input);
        }
        boolean released = path.kind().requiresJumpHold() && !input.jumpHeld();
        if (released) {
            // Reaching a supported top in the fitted pose takes priority over the cancellation timer.
            Step settled = finishOnSupport(resolver, path, position, false, input);
            if (settled != null) return settled;
            if (path.canCancelOnRelease(elapsed)) return abort(resolver, position, MovementVector.ZERO, blocks, input);
        }
        double time = Math.min(path.kind().duration(), elapsed + MovementTuning.SUBSTEP_SECONDS);
        boolean done = time + 1.0E-9 >= path.kind().duration();
        MovementVector target = path.position(time);
        MovementVector requested = target;
        if (done && path.landing() != null) {
            // End exactly on the floor via a real downward contact, including native fall/bounce callbacks.
            requested = requested.add(new MovementVector(0, -1.0E-6, 0));
        }
        MovementVector command = requested.subtract(position).scale(1 / MovementTuning.SUBSTEP_SECONDS);
        resolver.body(path.body());
        MovementController.Motion motion = resolver.resolve(position, command, MovementTuning.SUBSTEP_SECONDS);
        if (motion.position().subtract(target).length() > 1.0E-5) {
            Step aborted = abort(resolver, motion.position(), MovementVector.ZERO, motion.blocks(), input);
            return new Step(new MovementController.Motion(aborted.motion().position(), aborted.motion().velocity(),
                    aborted.motion().grounded(), motion.blocks(), motion.resetFallDistance()), aborted.traversal(),
                    aborted.mode(), aborted.body(), 0, false, true);
        }
        if (released) {
            Step settled = finishOnSupport(resolver, path, motion.position(), motion.resetFallDistance(), input);
            if (settled != null) return settled;
        }
        if (done) {
            double factor = command.horizontalSpeed() > 1.0E-8 ? motion.velocity().horizontalSpeed() / command.horizontalSpeed() : 1;
            MovementVector exit = path.exitVelocity().scale(Math.clamp(factor, 0, 1));
            boolean spring = path.kind() == VaultType.SPRINGBOARD;
            exit = new MovementVector(exit.x(), spring ? path.exitVelocity().y() : Math.max(0, motion.velocity().y()), exit.z());
            MovementBody body = MovementController.exitBody(resolver, motion.position(), input.crouchHeld());
            boolean grounded = !spring && motion.grounded();
            resolver.synchronize(exit, grounded, body);
            MovementState.Mode mode = grounded ? MovementState.Mode.GROUND : MovementState.Mode.AIR;
            return new Step(new MovementController.Motion(motion.position(), exit, grounded, motion.blocks(), motion.resetFallDistance() || spring && motion.grounded()),
                    new TraversalState(null, TraversalState.RETRIGGER_SECONDS, MovementVector.ZERO), mode, body, 0, true, false);
        }
        return new Step(motion, new TraversalState(path, 0, motion.velocity(), state.entryVelocity()),
                 path.kind() == VaultType.SPRINGBOARD ? MovementState.Mode.SPRINGBOARD : path.kind() == VaultType.PULL_UP ? MovementState.Mode.PULL_UP
                         : path.kind().onto() ? MovementState.Mode.MANTLE : MovementState.Mode.VAULT, path.body(), time, false, false);
    }

    /** A release stops at the first supported footprint that fits the traversal pose. */
    private static Step finishOnSupport(MovementController.MotionResolver resolver, TraversalPath path,
                                        MovementVector position, boolean resetFallDistance,
                                        MovementInput input) {
        double drop = position.y() - path.support().max().y();
        if (drop < -1.0E-5) return null;
        MovementVector down = new MovementVector(0, -Math.max(1.0E-6, drop + 1.0E-6), 0);
        CollisionBox bounds = resolver.bounds(position, path.body());
        CollisionWorld world = resolver.geometry(bounds.swept(down, 0.01));
        CollisionWorld.Hit hit = world == null ? null : world.sweep(bounds, down);
        if (hit == null || hit.normal().y() < 0.7) return null;
        MovementVector target = position.add(down.scale(hit.fraction()));
        if (!resolver.canOccupy(target, path.body())) return null;
        resolver.body(path.body());
        MovementController.Motion motion = resolver.resolve(position, down.scale(1 / MovementTuning.SUBSTEP_SECONDS), MovementTuning.SUBSTEP_SECONDS);
        MovementBody body = MovementController.exitBody(resolver, motion.position(), input.crouchHeld());
        resolver.synchronize(MovementVector.ZERO, motion.grounded(), body);
        return new Step(new MovementController.Motion(motion.position(), MovementVector.ZERO, motion.grounded(), motion.blocks(),
                resetFallDistance || motion.resetFallDistance()),
                new TraversalState(null, TraversalState.RETRIGGER_SECONDS, MovementVector.ZERO),
                motion.grounded() ? MovementState.Mode.GROUND : MovementState.Mode.AIR, body, 0, true, false);
    }

    private static Step abort(MovementController.MotionResolver resolver, MovementVector position, MovementVector velocity,
                               BlockMotionState blocks, MovementInput input) {
        MovementBody body = MovementController.exitBody(resolver, position, input.crouchHeld());
        CollisionBox bounds = resolver.bounds(position, body);
        CollisionWorld world = resolver.geometry(bounds.swept(new MovementVector(0, -0.02, 0), 0.01));
        CollisionWorld.Hit hit = world == null ? null : world.sweep(bounds, new MovementVector(0, -0.02, 0));
        boolean grounded = hit != null && hit.fraction() < 0.01 && hit.normal().y() >= 0.7 && velocity.y() <= 0;
        resolver.synchronize(velocity, grounded, body);
        return new Step(new MovementController.Motion(position, velocity, grounded, blocks, false),
                new TraversalState(null, TraversalState.RETRIGGER_SECONDS, MovementVector.ZERO),
                grounded ? MovementState.Mode.GROUND : MovementState.Mode.AIR, body, 0, false, true);
    }

    private TraversalController() {}
}
