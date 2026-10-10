package dev.mineedge.client.movement;

import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementVector;
import java.util.ArrayDeque;

/** Render one simulation interval behind completed movement; never extrapolate past a collision. */
public final class SimulationInterpolation {
    private static final int CAPACITY = 8;
    private record Sample(MovementVector position, long time) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public void record(MovementVector position, long now) {
        var last = samples.peekLast();
        if (last != null && now < last.time()) samples.clear();
        else if (last != null && now == last.time()) samples.removeLast();
        samples.addLast(new Sample(position, now));
        while (samples.size() > CAPACITY) samples.removeFirst();
    }

    public MovementVector position(long now) {
        return position(now, MovementRate.INTERVAL_NANOS);
    }

    public MovementVector position(long now, long intervalNanos) {
        long target = now - intervalNanos;
        Sample previous = null;
        for (var next : samples) {
            if (next.time() >= target) {
                if (previous == null) return next.position();
                double alpha = (double) (target - previous.time()) / (next.time() - previous.time());
                return previous.position().add(next.position().subtract(previous.position()).scale(alpha));
            }
            previous = next;
        }
        return previous == null ? null : previous.position();
    }

    /** Keep the interpolation path in the corrected physics coordinate system. */
    public void translate(MovementVector displacement) {
        var translated = new ArrayDeque<Sample>();
        for (var sample : samples) translated.addLast(new Sample(sample.position().add(displacement), sample.time()));
        samples.clear();
        samples.addAll(translated);
    }

    public void reset(MovementVector position, long now) {
        clear();
        record(position, now);
    }

    public void clear() { samples.clear(); }
}
