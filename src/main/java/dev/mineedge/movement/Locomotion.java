package dev.mineedge.movement;

/** Adapted from faith_move/src/locomotion.rs (GetSprint/WalkAcceleration and CalcVelocity).
 * Minecraft's sprint binding gates the reference's automatic forward sprint selection.
 * Calculations use the reference's 4 m/s baseline, then scale to effective walk speed.
 */
public final class Locomotion {
    private static final double[][] SPEED = {{0, 0}, {0.4, 4}, {1, 5.2}, {3.5, 6.5}, {7, 7.2}};
    private static final double[][] ACCEL = accelerationCurve();
    private static final double FRICTION = 8;
    private static final double MIN_BASE = 0.1;
    private static final double MAX_BASE = 4;
    private static final double TOP_SPEED = 7.2;

    public record Result(MovementVector horizontal, double energy, double accelerationTime, double stoppingTime) {}

    public static Result step(MovementVector velocity, MovementInput input, MovementTuning tuning,
                              LocomotionState state, double turn, boolean grounded, double dt) {
        return step(velocity, input, tuning, state, turn, grounded, dt, 1);
    }

    public static Result step(MovementVector velocity, MovementInput input, MovementTuning tuning,
                              LocomotionState state, double turn, boolean grounded, double dt, double speedModifier) {
        return step(velocity, input, tuning, state, turn, grounded, dt, speedModifier, 1);
    }

    public static Result step(MovementVector velocity, MovementInput input, MovementTuning tuning,
                              LocomotionState state, double turn, boolean grounded, double dt, double speedModifier,
                              double traction) {
        double friction = FRICTION * traction;
        double scale = tuning.walkSpeed() / MAX_BASE;
        double sprintScale = tuning.runSpeedMultiplier() / (TOP_SPEED / MAX_BASE);
        double topSpeed = TOP_SPEED * sprintScale;
        if (scale == 0) {
            MovementVector horizontal = new MovementVector(velocity.x(), 0, velocity.z());
            return new Result(grounded ? horizontal.scale(Math.max(0, 1 - dt * friction)) : horizontal, 0, 0, 0);
        }
        MovementVector v = new MovementVector(velocity.x(), 0, velocity.z()).scale(1 / scale);
        MovementVector direction = input.direction().normalized();
        MovementVector forward = input.forwardDirection();
        MovementVector left = new MovementVector(forward.z(), 0, -forward.x());
        double energy = state.energy() / scale;
        double accelerationTime = state.accelerationTime();
        double stoppingTime = state.stoppingTime();
        MovementVector acceleration;
        if (!grounded) {
            energy = direction.equals(MovementVector.ZERO) ? 0 : Math.max(0, v.length() - MAX_BASE);
            acceleration = sprintAcceleration(v, direction, turn, false, dt, sprintScale);
            double maximum = tuning.airAcceleration() / scale;
            if (v.length() < MIN_BASE && maximum > 0) {
                maximum += (MIN_BASE - v.length()) / dt;
            } else if (v.length() >= topSpeed && maximum <= 0.05 * tuning.groundAcceleration()) {
                maximum = 0.01 / scale;
            }
            v = v.add(acceleration.clampLength(maximum).scale(dt));
        } else {
            // The short-tap stop snaps velocity down on grippy ground; ice must keep coasting.
            if (traction < 1) stoppingTime = 0;
            if (stoppingTime > 0) {
                stoppingTime = Math.max(0, stoppingTime - dt);
                // Keep braking during the stop window; resetting a fixed crawl
                // speed here would sustain motion after the key was released.
                acceleration = MovementVector.ZERO;
            } else if (!direction.equals(MovementVector.ZERO)) {
                accelerationTime += dt;
                boolean sprint = input.sprintHeld() && input.forward() > 0.7
                        && Math.hypot(input.forward(), input.strafe()) > 0.7 && v.dot(direction) > 0;
                if (sprint) {
                    energy = Math.max(0, v.length() - MAX_BASE);
                    acceleration = sprintAcceleration(v, direction, turn, true, dt, sprintScale);
                } else {
                    double along = Math.clamp(direction.dot(forward), 0, 0.9);
                    energy = Math.max(0, energy - (topSpeed - MAX_BASE) * (1 - Math.sqrt(along)) / 3 * dt);
                    double top = MAX_BASE - MIN_BASE + energy;
                    double wantForward = direction.dot(forward) * MIN_BASE + top * input.forward();
                    double wantLeft = direction.dot(left) * MIN_BASE + top * input.strafe();
                    acceleration = forward.scale((wantForward - v.dot(forward)) * 7)
                            .add(left.scale((wantLeft - v.dot(left)) * 10)).add(v.scale(FRICTION * 0.1));
                    acceleration = rounded(acceleration);
                }
            } else {
                acceleration = MovementVector.ZERO;
                energy = 0;
                if (traction >= 1 && accelerationTime > 0 && accelerationTime < 0.15) {
                    stoppingTime = 0.25;
                    v = v.clampLength(0.35);
                }
                accelerationTime = 0;
            }
            acceleration = acceleration.clampLength(tuning.groundAcceleration() * speedModifier).scale(traction);
            if (acceleration.equals(MovementVector.ZERO)) {
                // dt=1/120 is already below the reference braking slice limit of 0.03 s.
                v = v.scale(Math.max(0, 1 - 2 * dt * friction * 0.5));
                if (v.length() < MIN_BASE * traction) {
                    v = MovementVector.ZERO;
                }
            } else {
                v = v.scale(1 - dt * friction * 0.1);
            }
            // External horizontal launches may exceed running speed. Brake their excess
            // over time instead of deleting the impulse at the first grounded substep.
            double limit = Math.max(topSpeed * speedModifier, v.length() * Math.exp(-friction * dt));
            v = v.add(acceleration.scale(dt)).clampLength(limit);
        }
        return new Result(v.scale(scale), energy * scale, accelerationTime, stoppingTime);
    }

    private static MovementVector sprintAcceleration(MovementVector v, MovementVector direction,
                                                      double turn, boolean grounded, double dt, double sprintScale) {
        if (direction.equals(MovementVector.ZERO)) {
            return MovementVector.ZERO;
        }
        // Stretch the reference sprint curve to the configured top speed; 1.8x keeps it unchanged.
        MovementVector acceleration = direction.scale(evaluate(ACCEL, v.length() / sprintScale) * sprintScale);
        if (grounded) {
            MovementVector difference = direction.scale(v.length()).subtract(v);
            acceleration = acceleration.add(difference.normalized().scale(Math.min(30 * difference.length(),
                    difference.length() / dt))).add(v.scale(FRICTION * 0.1));
        }
        // Reference: turn radians * 65536 / TAU / 32768 * SpeedTurnDecelerationFactor (10).
        return rounded(acceleration.subtract(v.scale(10 * Math.abs(turn) / Math.PI)));
    }

    private static MovementVector rounded(MovementVector a) {
        return new MovementVector(Math.floor(a.x() * 1000 + 0.5) / 1000, 0,
                Math.floor(a.z() * 1000 + 0.5) / 1000);
    }

    private static double evaluate(double[][] curve, double x) {
        if (x <= curve[0][0]) {
            return curve[0][1];
        }
        for (int i = 1; i < curve.length; i++) {
            if (x < curve[i][0]) {
                double[] a = curve[i - 1];
                double[] b = curve[i];
                return a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]);
            }
        }
        return curve[curve.length - 1][1];
    }

    private static double[][] accelerationCurve() {
        // Exact reference conversion: 51 time-at-speed samples, then 11 acceleration keys.
        double[][] inverse = new double[51][2];
        for (int i = 0; i <= 50; i++) {
            double time = i * 7.0 / 50;
            inverse[i] = new double[] {evaluate(SPEED, time), time};
        }
        double[][] curve = new double[11][2];
        for (int i = 0; i <= 10; i++) {
            double speed = i * TOP_SPEED / 10;
            double time = evaluate(inverse, speed);
            curve[i] = new double[] {speed, (evaluate(SPEED, time + 0.05) - evaluate(SPEED, time)) / 0.05};
        }
        return curve;
    }

    private Locomotion() {}
}
