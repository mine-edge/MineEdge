package dev.mineedge.network;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementRate;

/** Wall-clock input transport: 20 Hz updates and 1 Hz full baselines. */
public final class InputSchedule {
    public static final int UPDATES_PER_SECOND = MovementRate.HZ;
    public static final long UPDATE_INTERVAL_NANOS = MovementRate.INTERVAL_NANOS;
    public static final long FULL_INTERVAL_NANOS = 1_000_000_000L;
    public static final int FULL_INTERVAL_TICKS = MovementRate.GAME_TICK_HZ;
    public static final int FULL_INTERVAL_STEPS = MovementRate.HZ;
    private MovementInput previous;
    private long lastFullTime;
    private long nextUpdateTime;
    private long updateSequence;

    public InputPayload next(long epoch, MovementInput input, long now) {
        if (previous != null && now - nextUpdateTime < 0) return null;
        if (previous == null || now - nextUpdateTime >= 2 * UPDATE_INTERVAL_NANOS) {
            // Skip missed slots rather than sending a catch-up burst after a stall.
            nextUpdateTime = now + UPDATE_INTERVAL_NANOS;
        } else {
            nextUpdateTime += UPDATE_INTERVAL_NANOS;
        }
        InputPayload payload;
        if (previous == null || now - lastFullTime >= FULL_INTERVAL_NANOS) {
            payload = new FullInputPayload(epoch, ++updateSequence, input, false);
            lastFullTime = now;
        } else {
            payload = InputChangePayload.between(epoch, updateSequence + 1, previous, input);
            if (payload != null) updateSequence++;
        }
        previous = input.withoutEdges();
        return payload;
    }

    public void reset() {
        previous = null;
        lastFullTime = nextUpdateTime = updateSequence = 0;
    }
}
