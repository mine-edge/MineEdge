package dev.mineedge.client.input;

/** Button taps survive between ticks; focus/menu interruption suppresses edges on resumption. */
public final class InputLatch {
    public record Edges(boolean jump, boolean crouch, boolean turn) {
        public Edges(boolean jump, boolean crouch) { this(jump, crouch, false); }
    }
    private boolean jumpHeld;
    private boolean crouchHeld;
    private boolean pendingJump;
    private boolean pendingCrouch;
    private boolean interrupted;
    private boolean turnHeld, pendingTurn;

    public void pressJump() { pendingJump = true; }
    public void pressCrouch() { pendingCrouch = true; }
    public void pressTurn() { pendingTurn = true; }
    public boolean interrupted() { return interrupted; }

    public void interrupt() {
        pendingJump = false;
        pendingCrouch = false;
        pendingTurn = false;
        interrupted = true;
    }

    public void resume(boolean jump, boolean crouch) {
        jumpHeld = jump;
        crouchHeld = crouch;
        interrupt();
    }

    public Edges capture(boolean jump, boolean crouch, boolean blocked) {
        return capture(jump, crouch, false, blocked);
    }

    public Edges capture(boolean jump, boolean crouch, boolean turn, boolean blocked) {
        boolean suppress = blocked || interrupted;
        Edges edges = new Edges(!suppress && (pendingJump || jump && !jumpHeld),
                !suppress && (pendingCrouch || crouch && !crouchHeld), !suppress && (pendingTurn || turn && !turnHeld));
        jumpHeld = jump;
        crouchHeld = crouch;
        turnHeld = turn;
        pendingJump = false;
        pendingCrouch = false;
        pendingTurn = false;
        interrupted = blocked;
        return edges;
    }
}
