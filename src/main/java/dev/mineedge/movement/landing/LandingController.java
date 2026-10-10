package dev.mineedge.movement.landing;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.Slide;
import java.util.function.Consumer;

/** Selects landing responses from fall history, input timing and contact clearance. */
public final class LandingController {
    public static final double WINDOW = 0.2, RETRIGGER = 0.6, ROLL_HEIGHT = 2, HARD_HEIGHT = 5.3;
    public static final double ROLL_TIME = 0.5, RECOVERY_TIME = 1.8, SPEED_REDUCTION = 0.65;
    public static final double SOFT_RECOVERY_TIME = 1.5;
    public static final double HARD_SPEED_MODIFIER = 0.5;
    public record Result(LandingState state, MovementVector velocity, MovementState.Mode mode, MovementBody body) {}

    public static boolean locked(MovementState.Mode mode) {
        return mode == MovementState.Mode.ROLL || mode == MovementState.Mode.HARD_LANDING || mode == MovementState.Mode.SOFT_LANDING;
    }

    /** Shared exact-contact test, also used before vanilla damage callbacks on the server. */
    public static boolean canRoll(LandingState state, MovementVector feet, double impact, boolean clearance) {
        return state.airborne() && state.source() != LandingState.Source.TRAVERSAL && impact > 0
                && state.apex() - feet.y() + 1.0E-9 >= ROLL_HEIGHT
                && state.triggerAge() < WINDOW - 1.0E-9 && clearance;
    }

    public static Result land(LandingState state, MovementController.Motion motion, double impact,
                              MovementBody body, MovementInput input, MovementTuning tuning,
                              MovementController.MotionResolver resolver, Consumer<MovementEvent> events) {
        MovementVector feet = motion.position();
        MovementVector velocity = motion.velocity();
        double fall = Math.max(0, state.apex() - feet.y());
        // Scripted traversal and native upward bounces retain their specified exit response.
        boolean ordinary = state.source() != LandingState.Source.TRAVERSAL && velocity.y() <= 0;
        boolean roll = ordinary && canRoll(state, feet, impact, resolver.canOccupy(feet, MovementBody.LOW));
        MovementState.Mode mode = MovementState.Mode.GROUND;
        if (roll) {
            MovementVector horizontal = new MovementVector(velocity.x(), 0, velocity.z());
            MovementVector direction = horizontal.length() > 1.0E-9 ? horizontal.normalized() : input.forwardDirection();
            velocity = direction.scale(Math.max(horizontal.length(), tuning.walkSpeed()));
            body = MovementBody.LOW;
            mode = MovementState.Mode.ROLL;
            events.accept(MovementEvent.ROLL_START);
        } else if (ordinary && fall >= HARD_HEIGHT && impact > 0 && tuning.features().hardLanding()) {
            velocity = MovementVector.ZERO;
            var support = resolver.surface(feet, body);
            boolean soft = support != null && support.soft();
            mode = soft ? MovementState.Mode.SOFT_LANDING : MovementState.Mode.HARD_LANDING;
            events.accept(soft ? MovementEvent.SOFT_LAND : MovementEvent.HARD_LAND);
        } else if (ordinary && state.source() == LandingState.Source.JUMP) {
            double cap = Math.max(0, state.takeoffSpeed() - SPEED_REDUCTION * tuning.walkSpeed() / 4);
            MovementVector horizontal = new MovementVector(velocity.x(), 0, velocity.z()).clampLength(cap);
            velocity = new MovementVector(horizontal.x(), velocity.y(), horizontal.z());
            if (input.crouchHeld() && Slide.canStart(velocity, input, tuning) && resolver.canOccupy(feet, MovementBody.LOW)) {
                mode = MovementState.Mode.SLIDE;
                body = MovementBody.LOW;
                events.accept(MovementEvent.SLIDE_START);
            }
        }
        resolver.synchronize(velocity, motion.grounded(), body);
        return new Result(state.landed(feet, impact, roll), velocity, mode, body);
    }

    private LandingController() {}
}
