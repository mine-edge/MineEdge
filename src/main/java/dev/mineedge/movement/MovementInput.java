package dev.mineedge.movement;

/** Minecraft yaw: 0 = +Z, 90 = -X. Positive strafe is left, positive pitch is down. */
public record MovementInput(long sequence, float forward, float strafe, float rawStrafe,
                            boolean jumpHeld, boolean jumpPressed, boolean crouchHeld,
                            boolean crouchPressed, boolean sprintHeld, float yaw, float pitch, boolean turnPressed) {
    public MovementInput(long sequence, float forward, float strafe, float rawStrafe, boolean jumpHeld,
                         boolean jumpPressed, boolean crouchHeld, boolean crouchPressed, boolean sprintHeld, float yaw, float pitch) {
        this(sequence, forward, strafe, rawStrafe, jumpHeld, jumpPressed, crouchHeld, crouchPressed, sprintHeld, yaw, pitch, false);
    }
    public MovementInput {
        if (sequence < 0 || !Float.isFinite(forward) || !Float.isFinite(strafe)
                || !Float.isFinite(rawStrafe) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Invalid movement input");
        }
        forward = Math.clamp(forward, -1, 1);
        strafe = Math.clamp(strafe, -1, 1);
        rawStrafe = Math.clamp(rawStrafe, -1, 1);
        double length = Math.hypot(forward, strafe);
        if (length > 1) {
            forward /= (float) length;
            strafe /= (float) length;
        }
        yaw = ((yaw % 360) + 540) % 360 - 180;
        pitch = Math.clamp(pitch, -90, 90);
    }

    public static MovementInput idle(long sequence, float yaw, float pitch) {
        return new MovementInput(sequence, 0, 0, 0, false, false, false, false, false, yaw, pitch);
    }

    public MovementInput withoutEdges() {
        return withEdges(false, false, false);
    }

    public MovementInput withSequence(long sequence) {
        return new MovementInput(sequence, forward, strafe, rawStrafe, jumpHeld, jumpPressed,
                crouchHeld, crouchPressed, sprintHeld, yaw, pitch, turnPressed);
    }

    public MovementInput withEdges(boolean jump, boolean crouch) {
        return withEdges(jump, crouch, turnPressed);
    }

    public MovementInput withEdges(boolean jump, boolean crouch, boolean turn) {
        return new MovementInput(sequence, forward, strafe, rawStrafe, jumpHeld, jump,
                crouchHeld, crouch, sprintHeld, yaw, pitch, turn);
    }

    public MovementInput withSprint(boolean sprint) {
        return new MovementInput(sequence, forward, strafe, rawStrafe, jumpHeld, jumpPressed,
                crouchHeld, crouchPressed, sprint, yaw, pitch, turnPressed);
    }

    public MovementInput withYaw(float facing) {
        return new MovementInput(sequence, forward, strafe, rawStrafe, jumpHeld, jumpPressed,
                crouchHeld, crouchPressed, sprintHeld, facing, pitch, turnPressed);
    }

    public MovementVector direction() {
        double angle = Math.toRadians(yaw);
        return new MovementVector(-Math.sin(angle) * forward + Math.cos(angle) * strafe,
                0, Math.cos(angle) * forward + Math.sin(angle) * strafe);
    }

    public MovementVector forwardDirection() {
        double angle = Math.toRadians(yaw);
        return new MovementVector(-Math.sin(angle), 0, Math.cos(angle));
    }
}
