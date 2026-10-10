package dev.mineedge.movement;

import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionWorld;
import dev.mineedge.movement.collision.TraversalProbes;
import dev.mineedge.movement.landing.LandingController;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfaceContact;
import dev.mineedge.movement.surface.SurfaceController;
import dev.mineedge.movement.surface.SurfaceState;
import dev.mineedge.movement.traversal.TraversalController;
import dev.mineedge.movement.traversal.TraversalPath;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.traversal.VaultPlanner;
import dev.mineedge.movement.traversal.VaultType;
import dev.mineedge.movement.wall.WallController;
import dev.mineedge.movement.wall.WallState;
import java.util.function.Consumer;

/** Shared fixed-step locomotion, wall movement and bounded traversal. The adapter owns collision once. */
public final class MovementController {
    @FunctionalInterface
    public interface MotionResolver {
        Motion resolve(MovementVector position, MovementVector velocity, double seconds);
        default Motion resolve(MovementVector position, MovementVector velocity, double seconds, double stepHeight) {
            return resolve(position, velocity, seconds);
        }
        default Motion resolve(MovementVector position, MovementVector velocity, double seconds, double stepHeight, boolean grounded) {
            return resolve(position, velocity, seconds, stepHeight);
        }
        default double maxStepHeight() { return 0.6; }
        default double jumpFactor(MovementVector position, MovementBody body) { return 1; }
        default double groundTraction(MovementVector position, MovementBody body) { return 1; }

        default void begin(MovementState state) {}
        default void input(MovementInput input) {}
        default void body(MovementBody body) {}
        default void facing(float yaw) {}
        default void relocate(MovementVector position) {}
        default boolean canOccupy(MovementVector position, MovementBody body) { return true; }
        default CollisionBox bounds(MovementVector position, MovementBody body) { return CollisionBox.body(position, 0.6, body.height()); }
        default CollisionWorld geometry(CollisionBox region) { return null; }
        default MovementVector position(MovementVector simulated) { return simulated; }
        default void synchronize(MovementVector velocity, boolean grounded, MovementBody body) { body(body); }
        default void landing(LandingState state, MovementTuning tuning) {}
        default SurfaceContact surface(MovementVector feet, MovementBody body) { return null; }
        default boolean stepSurface(CollisionBox shape) { return false; }
        default boolean stepSource(MovementVector source) { return false; }
        default ClimbRoute climbRoute(MovementVector feet, MovementVector preferredNormal) { return null; }
        default boolean climbValid(ClimbRoute route) { return false; }
        default ClimbRoute climbSideways(ClimbRoute route, double height, double distance) { return null; }
        default void clearFallDistance() {}
        /** Settle a fall on a successful ledge grab; replay only resets the shared fall history. */
        default void ledgeGrab(float fallDistance, MovementTuning tuning) { clearFallDistance(); }
        default void pushDoors(MovementVector position, MovementVector velocity, MovementBody body, double seconds) {}
    }

    public record Motion(MovementVector position, MovementVector velocity, boolean grounded,
                         BlockMotionState blocks, boolean resetFallDistance) {
        public Motion(MovementVector position, MovementVector velocity, boolean grounded) {
            this(position, velocity, grounded, BlockMotionState.EMPTY, false);
        }
    }

    public MovementState tick(MovementState state, MovementInput input, MovementTuning tuning,
                              MotionResolver resolver, Consumer<MovementEvent> events) {
        return advance(state, input, tuning, resolver, events, MovementTuning.SUBSTEPS);
    }

    /** One fixed input/simulation step, independent of Minecraft's native tick length. */
    public MovementState step(MovementState state, MovementInput input, MovementTuning tuning,
                              MotionResolver resolver, Consumer<MovementEvent> events) {
        return advance(state, input, tuning, resolver, events, MovementRate.SUBSTEPS);
    }

    private MovementState advance(MovementState state, MovementInput input, MovementTuning tuning,
                                  MotionResolver resolver, Consumer<MovementEvent> events, int substeps) {
        MovementVector position = state.position();
        MovementVector velocity = state.velocity();
        boolean grounded = state.grounded();
        float fallDistance = state.fallDistance();
        MovementState.Mode mode = state.mode();
        double time = state.modeTime();
        LocomotionState locomotion = state.locomotion();
        BlockMotionState blocks = state.blocks();
        MovementBody body = state.body();
        float slideYaw = state.slideYaw();
        resolver.begin(state);
        resolver.input(input);
        position = resolver.position(position);
        TraversalState traversal = state.traversal();
        WallState wall = state.wall();
        LandingState landing = state.landing();
        AdvancedState advanced = state.advanced();
        SurfaceState surfaces = state.surfaces();
        MovementInput originalInput = input;
        if (advanced.turn() != null && !advanced.turn().active()) {
            advanced = advanced.turn(null, advanced.wallTurned());
        }
        double jumpBuffer = mode == MovementState.Mode.SLIDE || LandingController.locked(mode) || traversal.path() != null ? 0
                : input.jumpPressed() ? MovementTuning.JUMP_BUFFER_SECONDS : locomotion.jumpBuffer();
        double turn = locomotion.yawInitialized() ? Math.toRadians(wrappedDegrees(input.yaw() - locomotion.previousYaw()))
                / substeps : 0;
        for (int step = 0; step < substeps; step++) {
            input = originalInput;
            if (step == 0 && input.turnPressed() && (mode != MovementState.Mode.GROUND || body == MovementBody.STANDING)
                    && advanced.turn() == null && advanced.ledge() == null && advanced.spring() == null) {
                var turnState = AdvancedMoves.turn(mode, velocity, input, wall);
                if (turnState != null) {
                    advanced = advanced.turn(turnState, mode == MovementState.Mode.WALLRUN);
                    if (mode == MovementState.Mode.WALLCLIMB) {
                        mode = MovementState.Mode.WALLCLIMB_TURNED;
                        time = 0;
                    }
                    events.accept(mode == MovementState.Mode.WALLRUN ? MovementEvent.WALLRUN_TURN : MovementEvent.TURN);
                }
            }
            float beforeYaw = advanced.turn() == null ? input.yaw() : advanced.turn().yaw();
            advanced = advanced.advance(MovementTuning.SUBSTEP_SECONDS);
            surfaces = surfaces.advance(MovementTuning.SUBSTEP_SECONDS);
            if (advanced.turn() != null) {
                input = input.withYaw(advanced.turn().yaw());
                resolver.facing(input.yaw());
                turn = Math.toRadians(wrappedDegrees(input.yaw() - beforeYaw));
            }
            landing = landing.advance(MovementTuning.SUBSTEP_SECONDS, step == 0 && input.crouchPressed()
                    && !LandingController.locked(mode));
            wall = grounded ? WallState.EMPTY : wall.advance(MovementTuning.SUBSTEP_SECONDS);
            traversal = traversal.advance(MovementTuning.SUBSTEP_SECONDS);
            // An imported upward impulse is already airborne; don't brake/cap knockback as ground motion.
            if (velocity.y() > 0) {
                grounded = false;
            }
            landing = landing.observe(position, grounded, false);
            resolver.landing(landing, tuning);
            boolean surfaceEligible = traversal.path() == null && advanced.ledge() == null && advanced.spring() == null && !advanced.coiled()
                    && (mode == MovementState.Mode.GROUND || mode == MovementState.Mode.AIR || mode == MovementState.Mode.WALLRUN);
            var surfaceStep = SurfaceController.step(resolver, position, velocity, grounded, mode, body, time, surfaces,
                    blocks, input, tuning, step == 0, surfaceEligible, events);
            if (surfaceStep != null) {
                Motion motion = surfaceStep.motion();
                if (surfaceStep.mode() == MovementState.Mode.STEP_UP && mode != MovementState.Mode.STEP_UP)
                    landing = landing.takeoff(position, LandingState.Source.TRAVERSAL, velocity.horizontalSpeed());
                if (mode == MovementState.Mode.STEP_UP && surfaceStep.mode() != MovementState.Mode.STEP_UP && !surfaceStep.resetHistory())
                    landing = landing.takeoff(position, LandingState.Source.OTHER, velocity.horizontalSpeed());
                LandingController.Result contact = !surfaceStep.resetHistory() && !grounded && motion.grounded()
                        && surfaceStep.mode() != MovementState.Mode.STEEP_SLIDE
                        ? LandingController.land(landing, motion, Math.max(0, -velocity.y()), surfaceStep.body(), input, tuning, resolver, events) : null;
                if (!surfaceStep.resetHistory() && !grounded && motion.grounded()) events.accept(MovementEvent.LAND);
                fallDistance = surfaceStep.resetHistory() ? 0 : updateFallDistance(fallDistance, position, motion);
                position = motion.position();
                velocity = motion.velocity();
                grounded = motion.grounded();
                blocks = motion.blocks();
                body = surfaceStep.body();
                mode = surfaceStep.mode();
                time = surfaceStep.time();
                surfaces = surfaceStep.state();
                landing = surfaceStep.resetHistory() ? LandingState.capture(position, grounded, 0)
                        : landing.observe(position, grounded, motion.resetFallDistance());
                if (contact != null) {
                    landing = contact.state();
                    velocity = contact.velocity();
                    body = contact.body();
                    mode = contact.mode();
                    time = 0;
                    if (mode == MovementState.Mode.SLIDE) slideYaw = input.yaw();
                }
                advanced = AdvancedState.EMPTY;
                wall = surfaces.climbCooldown() > 0 ? WallState.EMPTY.release(false, position, surfaces.climbCooldown()) : WallState.EMPTY;
                traversal = TraversalState.EMPTY;
                jumpBuffer = 0;
                locomotion = new LocomotionState(Math.max(0, velocity.horizontalSpeed() - tuning.walkSpeed()), 0, 0, 0,
                        mode == MovementState.Mode.STEEP_SLIDE ? surfaces.slopeYaw() : input.yaw(), true);
                continue;
            }
            // A fresh jump can retry the pull-up or kick away before the return finishes.
            boolean returnInterrupted = false;
            if (advanced.ledge() != null && advanced.ledge().kind() == AdvancedState.LedgeKind.RETURN
                    && step == 0 && input.jumpPressed() && !input.crouchHeld()) {
                var move = advanced.ledge();
                var world = resolver.geometry(resolver.bounds(position, body).swept(move.to().subtract(position), 1.5));
                if (position.subtract(move.position(move.elapsed())).length() <= 1.0E-5 && velocity.length() <= 1.0E-5
                        && world != null && world.contains(move.support()) && !world.overlaps(resolver.bounds(position, body))
                        && (input.forwardDirection().dot(wall.normal().scale(-1)) < 0
                                || input.jumpHeld() && tuning.features().mantling() && WallController.canPullUp(resolver, position, wall))) {
                    advanced = advanced.ledge(null);
                    time = 0.5;
                    returnInterrupted = true;
                }
            }
            if (advanced.ledge() != null) {
                var move = advanced.ledge();
                boolean returning = move.kind() == AdvancedState.LedgeKind.RETURN;
                CollisionWorld world = resolver.geometry(resolver.bounds(position, body).swept(move.to().subtract(position), 1.5));
                boolean disturbed = position.subtract(move.position(move.elapsed())).length() > 1.0E-5 || velocity.length() > 1.0E-5;
                double elapsed = Math.min(move.duration(), move.elapsed() + MovementTuning.SUBSTEP_SECONDS);
                MovementVector target = move.position(elapsed);
                // Clear the lip horizontally before descending; never sweep diagonally through its corner.
                boolean crossLip = returning && position.y() >= move.via().y() - 1.0E-9
                        && target.y() < move.via().y() && position.subtract(move.via()).horizontalSpeed() > 1.0E-9;
                boolean valid = !disturbed && world != null && world.contains(move.support()) && world.contains(move.originSupport())
                        && TraversalProbes.reachableLedge(world, move.to(), move.normal(), move.support())
                        && !(step == 0 && input.crouchPressed())
                        && (crossLip ? AdvancedMoves.clear(resolver, world, position, move.via(), body)
                                && AdvancedMoves.clear(resolver, world, move.via(), target, body)
                                : AdvancedMoves.clear(resolver, world, position, target, body));
                if (valid && crossLip) {
                    resolver.body(body);
                    Motion retreat = resolver.resolve(position, move.via().subtract(position).scale(1 / MovementTuning.SUBSTEP_SECONDS), MovementTuning.SUBSTEP_SECONDS);
                    valid = retreat.position().subtract(move.via()).length() <= 1.0E-5;
                    position = retreat.position();
                    blocks = retreat.blocks();
                    grounded = false;
                }
                if (valid) {
                    resolver.body(body);
                    Motion motion = resolver.resolve(position, target.subtract(position).scale(1 / MovementTuning.SUBSTEP_SECONDS), MovementTuning.SUBSTEP_SECONDS);
                    valid = motion.position().subtract(target).length() <= 1.0E-5;
                    if (returning) fallDistance = motion.resetFallDistance() ? 0
                            : fallDistance + (float) Math.max(0, position.y() - motion.position().y());
                    position = motion.position();
                    blocks = motion.blocks();
                    grounded = false;
                }
                if (!valid) {
                    advanced = advanced.ledge(null);
                    mode = MovementState.Mode.AIR;
                    time = 0;
                    wall = wall.release(false, position, 0.35);
                    body = exitBody(resolver, position, input.crouchHeld());
                    events.accept(returning && step == 0 && input.crouchPressed() ? MovementEvent.LEDGE_DROP : MovementEvent.WALL_DETACH);
                    resolver.synchronize(velocity, false, body);
                    continue;
                }
                velocity = MovementVector.ZERO;
                time += MovementTuning.SUBSTEP_SECONDS;
                double progress = elapsed / move.duration();
                if (move.kind() == AdvancedState.LedgeKind.CORNER) {
                    float yaw = move.yawFrom() + move.yawDelta() * (float) (progress * progress * (3 - 2 * progress));
                    resolver.facing(yaw);
                    advanced = advanced.turn(new AdvancedState.Turn(move.yawFrom(), move.yawDelta(), elapsed, move.duration()), false);
                }
                boolean done = elapsed + 1.0E-9 >= move.duration();
                if (returning && done) {
                    body = exitBody(resolver, position, false);
                    events.accept(MovementEvent.LEDGE_GRAB);
                    resolver.ledgeGrab(fallDistance, tuning);
                    fallDistance = 0;
                    time = 0;
                    landing = landing.takeoff(position, LandingState.Source.OTHER, 0);
                }
                wall = wall.contact(done ? move.normal() : wall.normal(), done ? move.support() : wall.support(), position, velocity, false, wall.chain());
                advanced = advanced.ledge(done ? null : new AdvancedState.LedgeMove(move.kind(), move.from(), move.via(), move.to(), move.normal(),
                        move.support(), move.originSupport(), elapsed, move.duration(), move.yawFrom(), move.yawDelta()));
                if (done && move.kind() == AdvancedState.LedgeKind.TRANSFER) {
                    time = 0;
                    landing = landing.takeoff(position, LandingState.Source.OTHER, 0);
                }
                resolver.synchronize(velocity, false, body);
                jumpBuffer = 0;
                continue;
            }
            if (advanced.spring() != null) {
                var spring = advanced.spring();
                var path = spring.path();
                MovementVector delta = path.from().subtract(position);
                boolean valid = tuning.features().vaulting() && grounded && spring.elapsed() < 1.5 && VaultPlanner.valid(resolver, path, 0)
                        && velocity.subtract(traversal.expectedVelocity()).length() < 1.0E-5;
                if (!valid) {
                    advanced = advanced.spring(null);
                    traversal = new TraversalState(null, TraversalState.RETRIGGER_SECONDS, MovementVector.ZERO);
                } else if (delta.horizontalSpeed() <= 0.01) {
                    traversal = new TraversalState(new TraversalPath(path.kind(), position, path.hand(), path.over(), path.end(), path.exitVelocity(), path.support(), path.landing(), path.body()), 0,
                            new MovementVector(velocity.x(), 0, velocity.z()));
                    advanced = advanced.spring(null);
                    mode = MovementState.Mode.SPRINGBOARD;
                    time = 0;
                    landing = landing.takeoff(position, LandingState.Source.TRAVERSAL, velocity.horizontalSpeed());
                    events.accept(MovementEvent.SPRINGBOARD_START);
                } else {
                    double distance = Math.min(delta.horizontalSpeed(), spring.speed() * 1.2 * MovementTuning.SUBSTEP_SECONDS);
                    MovementVector requested = new MovementVector(delta.x(), 0, delta.z()).normalized().scale(distance / MovementTuning.SUBSTEP_SECONDS)
                            .add(new MovementVector(0, -tuning.gravity() * MovementTuning.SUBSTEP_SECONDS, 0));
                    resolver.body(body);
                    Motion motion = resolver.resolve(position, requested, MovementTuning.SUBSTEP_SECONDS);
                    if (motion.position().subtract(position).horizontalSpeed() < distance * 0.5 || !motion.grounded()) advanced = advanced.spring(null);
                    else advanced = advanced.spring(new AdvancedState.Springboard(path, spring.speed(), spring.elapsed() + MovementTuning.SUBSTEP_SECONDS));
                    position = motion.position();
                    velocity = motion.velocity();
                    grounded = motion.grounded();
                    blocks = motion.blocks();
                    // Native floor clipping may produce -0.0, while replay produces +0.0.
                    traversal = new TraversalState(null, 0, new MovementVector(velocity.x(), 0, velocity.z()));
                    jumpBuffer = 0;
                    resolver.synchronize(velocity, grounded, body);
                    continue;
                }
            }
            if (LandingController.locked(mode)) {
                boolean rolling = mode == MovementState.Mode.ROLL;
                boolean hard = mode == MovementState.Mode.HARD_LANDING;
                time += MovementTuning.SUBSTEP_SECONDS;
                boolean interrupted = !grounded || (!rolling && !hard && velocity.horizontalSpeed() > 1.0E-5)
                        || (!rolling && !tuning.features().hardLanding());
                boolean done = time + 1.0E-9 >= (rolling ? LandingController.ROLL_TIME
                        : mode == MovementState.Mode.SOFT_LANDING ? LandingController.SOFT_RECOVERY_TIME : LandingController.RECOVERY_TIME);
                if (interrupted || done) {
                    mode = grounded ? MovementState.Mode.GROUND : MovementState.Mode.AIR;
                    body = exitBody(resolver, position, input.crouchHeld());
                    time = 0;
                    events.accept(rolling ? MovementEvent.ROLL_END : MovementEvent.LANDING_RECOVERED);
                } else {
                    body = rolling ? MovementBody.LOW : exitBody(resolver, position, input.crouchHeld());
                    MovementVector horizontal = rolling ? new MovementVector(velocity.x(), 0, velocity.z()) : MovementVector.ZERO;
                    Locomotion.Result recovery = null;
                    if (hard) {
                        // Simulate walking at its usual scale, then halve the resulting motion.
                        recovery = Locomotion.step(velocity.scale(1 / LandingController.HARD_SPEED_MODIFIER),
                                input.withSprint(false), tuning, locomotion, turn, true, MovementTuning.SUBSTEP_SECONDS,
                                body == MovementBody.STANDING ? 1 : tuning.crouchSpeedModifier(), resolver.groundTraction(position, body));
                        horizontal = recovery.horizontal().scale(LandingController.HARD_SPEED_MODIFIER)
                                .clampLength(tuning.walkSpeed() * LandingController.HARD_SPEED_MODIFIER);
                    }
                    velocity = new MovementVector(horizontal.x(),
                            Math.min(0, velocity.y()) - tuning.gravity() * MovementTuning.SUBSTEP_SECONDS,
                            horizontal.z());
                    resolver.body(body);
                    double stepHeight = hard && tuning.walkSpeed() > 0 && body != MovementBody.LOW ? resolver.maxStepHeight() : 0;
                    Motion motion = resolver.resolve(position, velocity, MovementTuning.SUBSTEP_SECONDS, stepHeight, grounded);
                    fallDistance = updateFallDistance(fallDistance, position, motion);
                    position = motion.position();
                    velocity = motion.velocity();
                    grounded = motion.grounded();
                    blocks = motion.blocks();
                    landing = landing.observe(position, grounded, motion.resetFallDistance());
                    if (!grounded) {
                        mode = MovementState.Mode.AIR;
                        body = exitBody(resolver, position, input.crouchHeld());
                        time = 0;
                        events.accept(rolling ? MovementEvent.ROLL_END : MovementEvent.LANDING_RECOVERED);
                    }
                    resolver.synchronize(velocity, grounded, body);
                    locomotion = new LocomotionState(rolling ? Math.max(0, velocity.horizontalSpeed() - tuning.walkSpeed()) : 0,
                            recovery == null ? 0 : recovery.accelerationTime(), recovery == null ? 0 : recovery.stoppingTime(),
                            0, input.yaw(), true);
                    jumpBuffer = 0;
                    continue;
                }
                jumpBuffer = 0;
            }
            if (mode == MovementState.Mode.SLIDE && (!grounded || !tuning.features().sliding())) {
                velocity = velocity.scale(Slide.EXIT_KEEP);
                events.accept(MovementEvent.SLIDE_END);
                mode = grounded ? MovementState.Mode.GROUND : MovementState.Mode.AIR;
                time = 0;
                body = exitBody(resolver, position, input.crouchHeld());
            }
            if (traversal.path() == null && mode != MovementState.Mode.SLIDE && !WallController.active(mode) && !advanced.coiled()) {
                if (step == 0 && input.crouchPressed() && grounded && body == MovementBody.STANDING
                        && Slide.canStart(velocity, input, tuning) && resolver.canOccupy(position, MovementBody.LOW)) {
                    mode = MovementState.Mode.SLIDE;
                    time = 0;
                    slideYaw = input.yaw();
                    body = MovementBody.LOW;
                    jumpBuffer = 0;
                    events.accept(MovementEvent.SLIDE_START);
                } else {
                    body = exitBody(resolver, position, input.crouchHeld());
                }
            }
            if (traversal.path() == null && mode != MovementState.Mode.SLIDE && jumpBuffer > 0 && grounded && tuning.jumpSpeed() > 0
                    && resolver.canOccupy(position, MovementBody.STANDING)) {
                body = MovementBody.STANDING;
                MovementVector facing = input.forwardDirection();
                var spring = tuning.features().vaulting() && traversal.cooldown() == 0
                        ? AdvancedMoves.springboard(resolver, position, velocity, input) : null;
                if (spring != null) {
                    advanced = advanced.spring(spring);
                    traversal = new TraversalState(null, 0, velocity);
                    jumpBuffer = 0;
                    continue;
                }
                landing = landing.takeoff(position, LandingState.Source.JUMP, velocity.horizontalSpeed());
                if (velocity.dot(facing) > 0.1 * tuning.walkSpeed() / 4) {
                    // Add forward momentum scaled to the configured walking speed.
                    velocity = velocity.add(facing.scale(tuning.walkSpeed() / 4));
                }
                velocity = new MovementVector(velocity.x(), tuning.jumpSpeed() * resolver.jumpFactor(position, body), velocity.z());
                grounded = false;
                jumpBuffer = 0;
                wall = wall.jump(position);
                events.accept(MovementEvent.JUMP);
            }
            if (grounded && mode == MovementState.Mode.GROUND && tuning.jumpSpeed() > 0 && tuning.walkSpeed() > 0
                    && tuning.features().vaulting() && input.jumpHeld() && traversal.cooldown() == 0 && advanced.spring() == null) {
                var spring = AdvancedMoves.springboard(resolver, position, velocity, input);
                if (spring != null) {
                    advanced = advanced.spring(spring);
                    traversal = new TraversalState(null, 0, velocity);
                    continue;
                }
            }
            if (!grounded && mode == MovementState.Mode.AIR) {
                // Native crouch shrinks by 0.3 blocks: raise feet by that amount to keep the head stable.
                double lift = MovementBody.STANDING.height() - MovementBody.CROUCHING.height();
                if (!advanced.coiled() && !state.grounded() && state.mode() == MovementState.Mode.AIR
                        && step == 0 && input.crouchPressed() && input.crouchHeld() && state.body() == MovementBody.STANDING
                        && wall.jumped() && velocity.dot(input.forwardDirection()) >= tuning.walkSpeed() / 4) {
                    var target = position.add(new MovementVector(0, lift, 0));
                    if (resolver.canOccupy(target, MovementBody.CROUCHING)) {
                        position = target;
                        body = MovementBody.CROUCHING;
                        resolver.relocate(position);
                        landing = landing.shift(lift);
                        advanced = advanced.coil(true);
                        events.accept(MovementEvent.COIL_START);
                    }
                } else if (advanced.coiled() && !input.crouchHeld()) {
                    var target = position.add(new MovementVector(0, -lift, 0));
                    if (resolver.canOccupy(target, MovementBody.STANDING)) {
                        position = target;
                        body = MovementBody.STANDING;
                        resolver.relocate(position);
                        landing = landing.shift(-lift);
                        advanced = advanced.coil(false);
                        events.accept(MovementEvent.COIL_END);
                    }
                }
            }
            if (traversal.path() == null && traversal.cooldown() == 0 && !grounded && mode != MovementState.Mode.SLIDE && !WallController.active(mode)
                    && body == MovementBody.STANDING && !input.crouchHeld() && input.forward() > 0.8) {
                TraversalPath path = VaultPlanner.plan(resolver, position, velocity, input, tuning);
                if (path != null && (!path.kind().requiresJumpHold() || input.jumpHeld())) {
                    traversal = new TraversalState(path, 0, velocity);
                    landing = landing.takeoff(position, LandingState.Source.TRAVERSAL, velocity.horizontalSpeed());
                    mode = path.kind().onto() ? MovementState.Mode.MANTLE : MovementState.Mode.VAULT;
                    time = 0;
                    jumpBuffer = 0;
                    events.accept(path.kind().onto() ? MovementEvent.MANTLE_START : MovementEvent.VAULT_START);
                }
            }
            resolver.landing(landing, tuning);
            if (!returnInterrupted && mode == MovementState.Mode.LEDGE_HANG && time >= 0.35 && input.forwardDirection().dot(wall.normal().scale(-1)) >= 0) {
                boolean transfer = jumpBuffer > 0 && input.forward() > 0.5;
                var move = transfer || time >= 0.6 && Math.abs(input.strafe()) > 0.3 && input.forward() <= 0.5 && jumpBuffer == 0
                        ? AdvancedMoves.ledge(resolver, position, body, wall, input, transfer) : null;
                if (move != null) {
                    advanced = advanced.ledge(move);
                    jumpBuffer = 0;
                    events.accept(move.kind() == AdvancedState.LedgeKind.TRANSFER ? MovementEvent.GRAB_TRANSFER
                            : move.kind() == AdvancedState.LedgeKind.CORNER ? MovementEvent.SHIMMY_CORNER : MovementEvent.SHIMMY_START);
                    continue;
                }
            }
            if (traversal.path() == null && !grounded && mode != MovementState.Mode.SLIDE) {
                WallController.Step next = WallController.step(resolver, position, velocity, mode, body, time, wall,
                        blocks, input, tuning, jumpBuffer, step == 0, advanced.wallTurned(), returnInterrupted, events);
                if (next != null) {
                    Motion motion = next.motion();
                    if (WallController.active(mode) && !WallController.active(next.mode())) {
                        advanced = advanced.turn(advanced.turn(), false);
                    }
                    if (WallController.active(mode) && next.mode() == MovementState.Mode.AIR && next.wall().wallJump()) {
                        landing = landing.takeoff(position, LandingState.Source.WALL_JUMP, velocity.horizontalSpeed());
                        advanced = advanced.turn(advanced.turn(), false);
                    }
                    if (mode == MovementState.Mode.WALLRUN && next.mode() == MovementState.Mode.AIR
                            && next.wall().wallJump() && next.wall().cooldown() <= 0.1) {
                        landing = landing.takeoff(position, LandingState.Source.WALL_JUMP, velocity.horizontalSpeed());
                    } else if (next.mode() == MovementState.Mode.LEDGE_HANG && mode != MovementState.Mode.LEDGE_HANG) {
                        landing = landing.takeoff(motion.position(), LandingState.Source.OTHER, 0);
                    } else if (next.traversal().path() != null) {
                        landing = landing.takeoff(position, LandingState.Source.TRAVERSAL, 0);
                    }
                    LandingController.Result contact = !grounded && motion.grounded()
                            ? LandingController.land(landing, motion, Math.max(0, -velocity.y()), next.body(), input, tuning, resolver, events) : null;
                    fallDistance = updateFallDistance(fallDistance, position, motion);
                    if (next.mode() == MovementState.Mode.LEDGE_HANG && mode != MovementState.Mode.LEDGE_HANG) {
                        resolver.ledgeGrab(fallDistance, tuning);
                        fallDistance = 0;
                    }
                    position = motion.position();
                    velocity = motion.velocity();
                    grounded = motion.grounded();
                    blocks = motion.blocks();
                    body = next.body();
                    mode = next.mode();
                    time = next.time();
                    wall = next.wall();
                    traversal = next.traversal();
                    if (contact != null) {
                        landing = contact.state();
                        velocity = contact.velocity();
                        body = contact.body();
                        mode = contact.mode();
                        time = 0;
                        if (mode == MovementState.Mode.SLIDE) slideYaw = input.yaw();
                        advanced = advanced.landed();
                    } else {
                        landing = landing.observe(position, grounded, motion.resetFallDistance());
                    }
                    jumpBuffer = mode == MovementState.Mode.LEDGE_HANG
                            || mode == MovementState.Mode.WALLRUN && WallController.active(state.mode())
                            ? Math.max(0, jumpBuffer - MovementTuning.SUBSTEP_SECONDS) : 0;
                    locomotion = new LocomotionState(Math.max(0, velocity.horizontalSpeed() - tuning.walkSpeed()), 0, 0, jumpBuffer, input.yaw(), true);
                    continue;
                }
            }
            if (traversal.path() != null) {
                boolean spring = traversal.path().kind() == VaultType.SPRINGBOARD;
                TraversalPath releasedPath = traversal.path().canCancelOnRelease(time) && !input.jumpHeld()
                        && tuning.features().allowsTraversal(traversal.path().kind())
                        && position.subtract(traversal.path().position(time)).length() <= 1.0E-5
                        && velocity.subtract(traversal.expectedVelocity()).length() <= 1.0E-5
                        && VaultPlanner.valid(resolver, traversal.path(), time) ? traversal.path() : null;
                var next = TraversalController.step(traversal, time, position, velocity, blocks, input, resolver, tuning);
                boolean resumedJump = false;
                if (releasedPath != null && next.aborted() && !next.motion().grounded()) {
                    var hang = WallController.dropToHang(resolver, position, releasedPath, wall, blocks, input, events);
                    if (hang != null) {
                        wall = hang.wall();
                        advanced = advanced.ledge(hang.ledge());
                        next = new TraversalController.Step(hang.motion(), hang.traversal(), hang.mode(), hang.body(), hang.time(), false, true);
                    } else if (releasedPath.kind() != VaultType.PULL_UP && traversal.entryVelocity().y() > 0) {
                        // The scripted climb replaces velocity, but a quick release must not erase the original jump.
                        var launch = traversal.entryVelocity().subtract(new MovementVector(0, tuning.gravity() * time, 0));
                        resolver.synchronize(launch, false, next.body());
                        next = new TraversalController.Step(new Motion(position, launch, false, blocks, false),
                                next.traversal(), next.mode(), next.body(), next.time(), false, true);
                        // Finish this jump before allowing another automatic wall-climb attachment.
                        double cooldown = tuning.gravity() > 0 ? Math.max(0, launch.y()) / tuning.gravity() + MovementTuning.SUBSTEP_SECONDS : 0.35;
                        wall = wall.release(false, position, Math.max(TraversalState.RETRIGGER_SECONDS, cooldown));
                        resumedJump = true;
                    }
                }
                Motion motion = next.motion();
                if (!grounded && motion.grounded()) {
                    events.accept(MovementEvent.LAND);
                }
                fallDistance = updateFallDistance(fallDistance, position, motion);
                if (next.mode() == MovementState.Mode.LEDGE_HANG && mode != MovementState.Mode.LEDGE_HANG && advanced.ledge() == null) {
                    resolver.ledgeGrab(fallDistance, tuning);
                    fallDistance = 0;
                }
                landing = !grounded && motion.grounded() ? landing.landed(motion.position(), Math.max(0, -velocity.y()), false)
                        : landing.observe(motion.position(), motion.grounded(), motion.resetFallDistance());
                position = motion.position();
                velocity = motion.velocity();
                grounded = motion.grounded();
                blocks = motion.blocks();
                traversal = next.traversal();
                body = next.body();
                mode = next.mode();
                time = next.time();
                locomotion = new LocomotionState(Math.max(0, velocity.horizontalSpeed() - tuning.walkSpeed()), 0, 0, 0, input.yaw(), true);
                jumpBuffer = 0;
                if (next.completed()) {
                    events.accept(MovementEvent.TRAVERSAL_COMPLETE);
                    if (spring) wall = wall.jump(position);
                } else if (next.aborted()) {
                    events.accept(MovementEvent.TRAVERSAL_ABORT);
                }
                if (!grounded && (next.completed() || next.aborted())) {
                    landing = landing.takeoff(position, resumedJump && wall.jumped()
                            ? wall.wallJump() ? LandingState.Source.WALL_JUMP : LandingState.Source.JUMP
                            : LandingState.Source.OTHER, velocity.horizontalSpeed());
                }
                // Resume ordinary air movement in this substep too, so release does not pause gravity or collision.
                if (!resumedJump) continue;
            }
            boolean sliding = mode == MovementState.Mode.SLIDE;
            double traction = grounded ? resolver.groundTraction(position, body) : 1;
            Locomotion.Result result;
            if (sliding) {
                jumpBuffer = 0; // Never queue a jump during a slide, including its exit substep.
                time += MovementTuning.SUBSTEP_SECONDS;
                Slide.Step slide = Slide.step(velocity, slideYaw, input, tuning, time, MovementTuning.SUBSTEP_SECONDS, traction);
                slideYaw = slide.yaw();
                MovementVector horizontal = slide.horizontal();
                if (slide.exit()) {
                    horizontal = horizontal.scale(Slide.EXIT_KEEP);
                    velocity = new MovementVector(velocity.x(), velocity.y() * Slide.EXIT_KEEP, velocity.z());
                    mode = grounded ? MovementState.Mode.GROUND : MovementState.Mode.AIR;
                    time = 0;
                    body = exitBody(resolver, position, input.crouchHeld());
                    events.accept(MovementEvent.SLIDE_END);
                }
                result = new Locomotion.Result(horizontal, Math.max(0, horizontal.horizontalSpeed() - tuning.walkSpeed()), 0, 0);
            } else {
                boolean standing = body == MovementBody.STANDING;
                result = Locomotion.step(velocity, standing ? input : input.withSprint(false), tuning, locomotion, turn, grounded,
                        MovementTuning.SUBSTEP_SECONDS, standing ? 1 : tuning.crouchSpeedModifier(), traction);
            }
            jumpBuffer = Math.max(0, jumpBuffer - MovementTuning.SUBSTEP_SECONDS);
            locomotion = new LocomotionState(result.energy(), result.accelerationTime(), result.stoppingTime(),
                    jumpBuffer, input.yaw(), true);
            velocity = new MovementVector(result.horizontal().x(),
                    velocity.y() - tuning.gravity() * MovementTuning.SUBSTEP_SECONDS,
                    result.horizontal().z());
            resolver.body(body);
            double stepHeight = (mode == MovementState.Mode.GROUND || mode == MovementState.Mode.AIR) && tuning.walkSpeed() > 0
                    ? resolver.maxStepHeight() : 0;
            if (tuning.features().doorPush() && grounded && mode == MovementState.Mode.GROUND
                    && body == MovementBody.STANDING && input.sprintHeld() && input.forward() > 0.8
                    && !input.crouchHeld() && velocity.dot(input.forwardDirection()) > 0) {
                resolver.pushDoors(position, velocity, body, MovementTuning.SUBSTEP_SECONDS);
            }
            Motion motion = resolver.resolve(position, velocity, MovementTuning.SUBSTEP_SECONDS, stepHeight, grounded);
            if (stepHeight > 0 && motion.grounded() && motion.position().y() > position.y() + 1.0E-7) {
                events.accept(MovementEvent.STEP_UP_START);
                events.accept(MovementEvent.STEP_UP_COMPLETE);
            }
            LandingController.Result contact = !grounded && motion.grounded()
                    ? LandingController.land(landing, motion, Math.max(0, -velocity.y()), body, input, tuning, resolver, events) : null;
            if (!grounded && motion.grounded()) {
                events.accept(MovementEvent.LAND);
            }
            fallDistance = updateFallDistance(fallDistance, position, motion);
            position = motion.position();
            velocity = motion.velocity();
            grounded = motion.grounded();
            blocks = motion.blocks();
            MovementState.Mode nextMode = mode == MovementState.Mode.SLIDE && grounded ? MovementState.Mode.SLIDE
                    : grounded ? MovementState.Mode.GROUND : MovementState.Mode.AIR;
            if (contact != null) {
                landing = contact.state();
                velocity = contact.velocity();
                body = contact.body();
                nextMode = contact.mode();
                boolean slideLanding = nextMode == MovementState.Mode.SLIDE;
                if (LandingController.locked(nextMode) || slideLanding) jumpBuffer = 0;
                locomotion = new LocomotionState(Math.max(0, velocity.horizontalSpeed() - tuning.walkSpeed()), 0, 0,
                        jumpBuffer, input.yaw(), true);
                if (advanced.coiled()) {
                    if (!slideLanding) body = exitBody(resolver, position, input.crouchHeld());
                    events.accept(MovementEvent.COIL_END);
                }
                if (slideLanding) {
                    time = 0;
                    slideYaw = input.yaw();
                }
                advanced = advanced.landed();
            } else {
                landing = landing.observe(position, grounded, motion.resetFallDistance());
            }
            if (mode == MovementState.Mode.SLIDE && nextMode != MovementState.Mode.SLIDE) {
                velocity = velocity.scale(Slide.EXIT_KEEP);
                events.accept(MovementEvent.SLIDE_END);
            }
            if (nextMode != MovementState.Mode.SLIDE) {
                time = nextMode == mode ? time + MovementTuning.SUBSTEP_SECONDS : 0;
            }
            mode = nextMode;
        }
        return new MovementState(position, velocity, mode, time, grounded, fallDistance, locomotion, blocks, body, slideYaw, traversal,
                grounded ? WallState.EMPTY : wall, landing, advanced, surfaces);
    }

    private static float updateFallDistance(float fallDistance, MovementVector position, Motion motion) {
        return motion.grounded() || motion.resetFallDistance() ? 0
                : fallDistance + (float) Math.max(0, position.y() - motion.position().y());
    }

    public static MovementBody exitBody(MotionResolver resolver, MovementVector position, boolean crouch) {
        if (!crouch && resolver.canOccupy(position, MovementBody.STANDING)) {
            return MovementBody.STANDING;
        }
        if (resolver.canOccupy(position, MovementBody.CROUCHING)) {
            return MovementBody.CROUCHING;
        }
        return MovementBody.LOW;
    }

    private static double wrappedDegrees(double angle) {
        return ((angle % 360) + 540) % 360 - 180;
    }
}
