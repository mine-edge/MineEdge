package dev.mineedge.movement;

/** faith_move/controller.rs::slide and tuning.rs: native slide timers, braking and steering. */
public final class Slide {
    public static final double MIN_SPEED = 3.5;
    public static final double ABORT_SPEED = 2.5;
    public static final double MIN_SECONDS = 0.5;
    public static final double EXIT_KEEP = 0.5;
    public static final double FRICTION = 8 * 0.1;
    public static final double LOOK_TURN = 0.2;
    public static final double STRAFE_DEGREES = 2000.0 / 65536 * 360;

    public record Step(MovementVector horizontal, float yaw, boolean exit) {}

    public static boolean canStart(MovementVector velocity, MovementInput input, MovementTuning tuning) {
        return tuning.features().sliding() && input.sprintHeld() && velocity.horizontalSpeed() > 0
                && velocity.dot(input.forwardDirection()) >= MIN_SPEED * tuning.walkSpeed() / 4;
    }

    public static Step step(MovementVector velocity, float yaw, MovementInput input,
                            MovementTuning tuning, double elapsed, double seconds) {
        return step(velocity, yaw, input, tuning, elapsed, seconds, 1);
    }

    public static Step step(MovementVector velocity, float yaw, MovementInput input,
                            MovementTuning tuning, double elapsed, double seconds, double traction) {
        // Minecraft's positive strafe is left; increasing yaw turns toward that side.
        int side = input.strafe() > 0.3 ? 1 : input.strafe() < -0.3 ? -1 : 0;
        boolean abort = side == 0 && input.forward() < -0.8
                || velocity.horizontalSpeed() < ABORT_SPEED * tuning.walkSpeed() / 4;
        double turn = ((input.yaw() - yaw) % 360 + 540) % 360 - 180;
        yaw += (float) (seconds * traction * (LOOK_TURN * turn + side * STRAFE_DEGREES));
        yaw = (yaw % 360 + 540) % 360 - 180;
        double speed = velocity.horizontalSpeed() * Math.max(0, 1 - seconds * FRICTION * traction);
        if (speed < 0.1 * tuning.walkSpeed() / 4) {
            speed = 0;
        }
        speed = Math.min(speed, tuning.sprintSpeed());
        double angle = Math.toRadians(yaw);
        boolean exit = abort || !input.crouchHeld() && elapsed + 1.0E-9 >= MIN_SECONDS;
        return new Step(new MovementVector(-Math.sin(angle) * speed, 0, Math.cos(angle) * speed), yaw, exit);
    }

    private Slide() {}
}
