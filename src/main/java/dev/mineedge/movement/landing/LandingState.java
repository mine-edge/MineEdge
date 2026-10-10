package dev.mineedge.movement.landing;

import dev.mineedge.movement.MovementVector;

/** Fall apex/history independent of vanilla damage distance, plus fresh-press roll timing. */
public record LandingState(double apex, double distance, double impact, Source source, double takeoffSpeed,
                           double triggerAge, double retriggerAge, boolean airborne) {
    public enum Source { OTHER, JUMP, WALL_JUMP, TRAVERSAL, BACK_TURN }
    private static final double EXPIRED = 60;

    public static LandingState capture(MovementVector position, boolean grounded, float nativeFall) {
        return new LandingState(position.y() + (grounded ? 0 : nativeFall), nativeFall, 0, Source.OTHER,
                0, EXPIRED, EXPIRED, !grounded);
    }

    public LandingState advance(double dt, boolean pressed) {
        boolean accept = pressed && retriggerAge + 1.0E-9 >= LandingController.RETRIGGER;
        return new LandingState(apex, distance, impact, source, takeoffSpeed,
                accept ? dt : Math.min(EXPIRED, triggerAge + dt),
                accept ? dt : Math.min(EXPIRED, retriggerAge + dt), airborne);
    }

    public LandingState backward() {
        return new LandingState(apex, distance, impact, Source.BACK_TURN, takeoffSpeed, EXPIRED, retriggerAge, airborne);
    }

    public LandingState shift(double offset) {
        return new LandingState(apex + offset, distance, impact, source, takeoffSpeed, triggerAge, retriggerAge, airborne);
    }

    public LandingState takeoff(MovementVector position, Source kind, double speed) {
        return new LandingState(position.y(), 0, 0, kind, speed, triggerAge, retriggerAge, true);
    }

    public LandingState observe(MovementVector position, boolean grounded, boolean reset) {
        if (grounded) return new LandingState(position.y(), distance, impact, Source.OTHER, 0, triggerAge, retriggerAge, false);
        double peak = reset || !airborne ? position.y() : Math.max(apex, position.y());
        return new LandingState(peak, Math.max(0, peak - position.y()), impact, source, takeoffSpeed, triggerAge, retriggerAge, true);
    }

    public LandingState landed(MovementVector position, double speed, boolean rolled) {
        return new LandingState(apex, Math.max(0, apex - position.y()), speed, Source.OTHER, 0,
                rolled ? EXPIRED : triggerAge, retriggerAge, false);
    }
}
