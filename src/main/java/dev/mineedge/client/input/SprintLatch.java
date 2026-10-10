package dev.mineedge.client.input;

/** Native sprint decisions survive movement/metadata writes to the player's sprint flag. */
public final class SprintLatch {
    private boolean requested;

    public void update(boolean sprinting) { requested = sprinting; }

    public boolean capture(boolean keyHeld, boolean continuing, boolean allowed, boolean blocked) {
        if (!continuing || !allowed || blocked) requested = false;
        return !blocked && allowed && (keyHeld || requested);
    }
}
