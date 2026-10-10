package dev.mineedge.network;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementRate;

/** Input transport: 20 updates and one full baseline per game-time second. */
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
    private long updateIntervalNanos = UPDATE_INTERVAL_NANOS;

    public InputPayload next(long epoch, MovementInput input, long now) {
        return next(epoch, input, now, UPDATE_INTERVAL_NANOS);
    }

    public InputPayload next(long epoch, MovementInput input, long now, long intervalNanos) {
        boolean rateChanged = intervalNanos != updateIntervalNanos;
        if (rateChanged) {
            // Refresh the full baseline immediately so the server's game-time stale
            // window never spans both the old and new wall-clock heartbeat intervals.
            nextUpdateTime = now;
            updateIntervalNanos = intervalNanos;
        }
        if (previous != null && now - nextUpdateTime < 0) return null;
        if (previous == null || now - nextUpdateTime >= 2 * intervalNanos) {
            // Skip missed slots rather than sending a catch-up burst after a stall.
            nextUpdateTime = now + intervalNanos;
        } else {
            nextUpdateTime += intervalNanos;
        }
        InputPayload payload;
        if (previous == null || rateChanged || now - lastFullTime >= FULL_INTERVAL_STEPS * intervalNanos) {
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
        updateIntervalNanos = UPDATE_INTERVAL_NANOS;
    }
}
