package dev.mineedge.client.camera;

import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementState;
import dev.mineedge.client.movement.RollPresentation;

/** Render-time body inertia, gait and parkour feedback; angles are Minecraft degrees. */
public final class CameraEffects {
    public record Settings(boolean enabled, boolean roll, double rollStrength, double fovStrength,
                           double landingStrength, double shakeStrength, double swayStrength,
                           double bobStrength, double parkourStrength) {}
    public record Shot(double roll, double pitch, double yaw, double height, double fov) {
        public static final Shot NONE = new Shot(0, 0, 0, 0, 0);
    }
    private final Spring dip = new Spring(), kick = new Spring();
    private final Spring actionPitch = new Spring(), actionRoll = new Spring(), actionDip = new Spring();
    private final Spring velocityX = new Spring(), velocityY = new Spring(), velocityZ = new Spring();
    private final Spring posturePitch = new Spring();
    private double roll, fov, landingFov, trauma, time, swayRoll, swayPitch, bobWeight, gaitPhase;
    private boolean hasVelocity;
    private double tumbleTime = -1;
    private boolean wasRolling;

    public void event(MovementEvent event, double impact) {
        switch (event) {
            case LAND -> {
                dip.impulse(-Math.clamp(impact * 0.07, 0, 1.8));
                kick.impulse(Math.clamp(impact * 0.06, 0, 1.2));
                trauma = Math.min(1, trauma + Math.clamp((impact - 5) * 0.05, 0, 0.35));
            }
            case HARD_LAND -> { dip.impulse(-2.4); kick.impulse(3); trauma = 0.8; }
            case SOFT_LAND -> { dip.impulse(-0.6); kick.impulse(0.6); trauma = Math.min(1, trauma + 0.15); }
            case LEDGE_GRAB -> { dip.impulse(-1); trauma = Math.min(1, trauma + 0.35); }
            case ROLL_START, WALL_JUMP, SLIDE_START, WALL_KICK, SPRINGBOARD_START, STEEP_SLIDE_START -> trauma = Math.min(1, trauma + 0.18);
            default -> {}
        }
        switch (event) {
            case JUMP -> actionPitch.impulse(-18);
            case WALL_JUMP, WALL_KICK -> { actionPitch.impulse(-28); actionDip.impulse(0.6); }
            case VAULT_START, MANTLE_START, PULL_UP_START, GRAB_TRANSFER, CLIMB_EXIT -> {
                actionPitch.impulse(24); actionDip.impulse(-0.5);
            }
            case SLIDE_START, STEEP_SLIDE_START, COIL_START -> actionPitch.impulse(18);
            case ROLL_START -> actionRoll.impulse(-32);
            case TURN, WALLRUN_TURN, SHIMMY_CORNER -> actionRoll.impulse(24);
            case SPRINGBOARD_START -> { actionPitch.impulse(-30); actionDip.impulse(-0.7); }
            case LEDGE_GRAB, LADDER_GRAB, PIPE_GRAB -> actionPitch.impulse(16);
            case CLIMB_STEP -> { actionPitch.impulse(-4); actionDip.impulse(-0.12); }
            case STEP_UP_START -> { actionPitch.impulse(6); actionDip.impulse(-0.18); }
            default -> {}
        }
    }

    public Shot update(double dt, MovementState state, float yaw, Settings settings) {
        if (!settings.enabled()) { reset(); return Shot.NONE; }
        double tumbleDt = Math.max(0, dt);
        dt = Math.clamp(dt, 0, 0.1);
        time += dt;
        double speed = state.velocity().horizontalSpeed();
        double sprint = Math.clamp((speed - 4) / 3.2, 0, 1);
        boolean wallrun = state.mode() == MovementState.Mode.WALLRUN;
        double radians = Math.toRadians(yaw), sin = Math.sin(radians), cos = Math.cos(radians);
        // Minecraft right is (-cos(yaw), 0, -sin(yaw)); roll away from the contacted wall.
        double targetRoll = wallrun ? 12 * (-state.wall().normal().x() * cos
                - state.wall().normal().z() * sin)
                : state.mode() == MovementState.Mode.SLIDE || state.mode() == MovementState.Mode.STEEP_SLIDE ? -5 : 0;
        roll = settings.roll() ? approach(roll, targetRoll, 8, dt) : 0;
        fov = settings.fovStrength() > 0 ? approach(fov, 10 * sprint + (wallrun ? 4 : 0), 4, dt) : 0;
        landingFov = settings.landingStrength() > 0
                ? approach(landingFov, state.mode() == MovementState.Mode.HARD_LANDING ? -10 : 0, 8, dt) : 0;
        boolean moving = switch (state.mode()) {
            case GROUND, AIR, SLIDE, STEEP_SLIDE, WALLRUN -> true;
            default -> false;
        };
        // Filter in world space: turning the mouse must not masquerade as body acceleration.
        // Spring velocity supplies a continuous acceleration signal, including constant-speed reversals.
        if (!hasVelocity || !moving || settings.swayStrength() == 0) {
            velocityX.set(state.velocity().x());
            velocityY.set(state.velocity().y());
            velocityZ.set(state.velocity().z());
            hasVelocity = true;
        }
        velocityX.step(dt, 100, 20, state.velocity().x());
        velocityY.step(dt, 100, 20, state.velocity().y());
        velocityZ.step(dt, 100, 20, state.velocity().z());
        double sideSpeed = -velocityX.position * cos - velocityZ.position * sin;
        double sideAcceleration = -velocityX.velocity * cos - velocityZ.velocity * sin;
        double forwardAcceleration = -velocityX.velocity * sin + velocityZ.velocity * cos;
        if (settings.swayStrength() == 0) {
            swayRoll = swayPitch = 0;
        } else if (moving) {
            swayRoll = Math.clamp(-sideSpeed * 0.55 - sideAcceleration * 0.08, -4, 4);
            swayPitch = Math.clamp(forwardAcceleration * 0.12 - velocityY.position * 0.3, -3, 4);
        } else {
            // Attaching to a ledge or traversal releases inertia instead of cutting it abruptly.
            swayRoll = approach(swayRoll, 0, 12, dt);
            swayPitch = approach(swayPitch, 0, 12, dt);
        }

        // Distance-driven gait: speed changes the cadence, and stopping/airborne movement fades it out.
        if (settings.bobStrength() > 0) {
            boolean walking = state.grounded() && state.mode() == MovementState.Mode.GROUND && speed > 0.05;
            bobWeight = approach(bobWeight, walking ? Math.clamp(speed / 7.2, 0, 1) : 0, 10, dt);
            // Preserve the last footfall while airborne or stopped; only ground travel advances cadence.
            if (walking) gaitPhase = (gaitPhase + Math.min(speed, 12) * dt * Math.PI * 0.4) % (Math.PI * 2);
        } else { bobWeight = gaitPhase = 0; }
        double bob = bobWeight * settings.bobStrength();
        double gait = Math.sin(gaitPhase * 2);

        if (settings.landingStrength() == 0) { dip.reset(); kick.reset(); }
        double height = Math.clamp(dip.step(dt, 120, 17), -0.15, 0.1) * settings.landingStrength();
        double pitch = Math.toDegrees(Math.clamp(kick.step(dt, 110, 16), -0.2, 0.35)) * settings.landingStrength();
        if (settings.parkourStrength() == 0) {
            actionPitch.reset(); actionRoll.reset(); actionDip.reset(); posturePitch.reset();
        }
        double posture = settings.parkourStrength() > 0 ? posturePitch.step(dt, 144, 24, posture(state)) : 0;
        pitch += (Math.clamp(actionPitch.step(dt, 100, 16), -6, 6) + posture) * settings.parkourStrength()
                + swayPitch * settings.swayStrength() + 0.5 * Math.sin(gaitPhase * 2 - 0.35) * bob;
        pitch += tumble(tumbleDt, state, settings);
        height += Math.clamp(actionDip.step(dt, 100, 16), -0.06, 0.06) * settings.parkourStrength()
                // A soft second harmonic gives each footfall a compression/recovery shape.
                + 0.028 * (0.8 * gait + 0.2 * Math.sin(gaitPhase * 4)) * bob;
        double bank = Math.clamp(actionRoll.step(dt, 100, 16), -5, 5) * settings.parkourStrength()
                + swayRoll * settings.swayStrength() + 0.45 * Math.sin(gaitPhase) * bob;
        if (!settings.roll() || settings.rollStrength() == 0) { actionRoll.reset(); }
        trauma = Math.max(0, trauma - 1.5 * dt);
        double floor = state.mode() == MovementState.Mode.SLIDE || state.mode() == MovementState.Mode.STEEP_SLIDE ? 0.3 : wallrun ? 0.176 : 0.22 * sprint * sprint;
        double shake = Math.max(trauma * trauma, floor * floor) * settings.shakeStrength();
        return new Shot(settings.roll() ? (roll + bank + 4 * shake * noise(time, 13)) * settings.rollStrength() : 0,
                pitch + 3 * shake * noise(time, 1), 2.2 * shake * noise(time, 7), Math.clamp(height, -0.15, 0.1),
                 fov * settings.fovStrength() + landingFov * settings.landingStrength());
    }

    public void reset() {
        roll = fov = landingFov = trauma = time = swayRoll = swayPitch = bobWeight = gaitPhase = 0;
        hasVelocity = false;
        tumbleTime = -1;
        wasRolling = false;
        dip.reset(); kick.reset(); actionPitch.reset(); actionRoll.reset(); actionDip.reset();
        velocityX.reset(); velocityY.reset(); velocityZ.reset(); posturePitch.reset();
    }
    private double tumble(double dt, MovementState state, Settings settings) {
        boolean rolling = state.mode() == MovementState.Mode.ROLL;
        boolean enabled = settings.roll() && settings.rollStrength() > 0 && settings.parkourStrength() > 0;
        if (!enabled) tumbleTime = -1;
        else if (rolling && !wasRolling) tumbleTime = Math.clamp(state.modeTime(), 0, RollPresentation.DURATION);
        else if (tumbleTime >= 0) tumbleTime += dt;
        wasRolling = rolling;
        if (tumbleTime < 0) return 0;
        // Finish even if the physical roll leaves a ledge early. Render-time progress avoids
        // simulation-tick stair steps; camera and body share the same slower turn curve.
        double progress = tumbleTime + 1.0E-9 >= RollPresentation.DURATION
                ? 1 : Math.clamp(tumbleTime / RollPresentation.DURATION, 0, 1);
        if (progress >= 1) tumbleTime = -1;
        // Always complete a revolution: scaling this angle would snap back on exit at partial strength.
        return 360 * RollPresentation.turn(progress);
    }
    private static double posture(MovementState state) {
        return switch (state.mode()) {
            case SLIDE, STEEP_SLIDE -> 2.5;
            case ROLL -> 5;
            case WALLRUN -> -1.2;
            case WALLCLIMB, WALLCLIMB_TURNED -> -2;
            case VAULT, MANTLE, PULL_UP, CLIMB_EXIT -> {
                var path = state.traversal().path();
                // Lean over the support, then straighten before landing. Smooth tick changes below.
                double progress = path == null ? 0.5 : Math.clamp(state.modeTime() / path.kind().duration(), 0, 1);
                double arc = Math.sin(progress * Math.PI);
                yield 3 * arc * arc;
            }
            default -> 0;
        };
    }
    private static double approach(double value, double target, double rate, double dt) {
        return value + (target - value) * (1 - Math.exp(-rate * dt));
    }
    private static double noise(double t, double seed) {
        return 0.5 * Math.sin(t * 23 + seed) + 0.3 * Math.sin(t * 37.3 + seed * 1.7)
                + 0.2 * Math.sin(t * 53.1 + seed * 2.3);
    }
    private static final class Spring {
        double position, velocity;
        void impulse(double amount) { velocity = Math.clamp(velocity + amount, -60, 60); }
        double step(double dt, double stiffness, double damping) {
            return step(dt, stiffness, damping, 0);
        }
        double step(double dt, double stiffness, double damping, double target) {
            if (dt <= 0) return position;
            // Closed-form damped spring: no render-rate-dependent substeps or integration drift.
            double offset = position - target, halfDamping = damping * 0.5;
            double decay = Math.exp(-halfDamping * dt);
            double frequencySquared = stiffness - halfDamping * halfDamping;
            if (frequencySquared < 1.0E-8) {
                double change = velocity + halfDamping * offset;
                position = target + (offset + change * dt) * decay;
                velocity = (velocity - halfDamping * change * dt) * decay;
            } else {
                double frequency = Math.sqrt(frequencySquared);
                double sin = Math.sin(frequency * dt), cos = Math.cos(frequency * dt);
                position = target + decay * (offset * cos + (velocity + halfDamping * offset) * sin / frequency);
                velocity = decay * (velocity * cos - (halfDamping * velocity + stiffness * offset) * sin / frequency);
            }
            return position;
        }
        void set(double value) { position = value; velocity = 0; }
        void reset() { position = velocity = 0; }
    }
}
