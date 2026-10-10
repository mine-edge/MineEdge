package dev.mineedge.network;

import java.util.ArrayDeque;
import java.util.List;

/** Bounded network-thread handoff; player and simulation state stay on the game thread. */
public final class InputMailbox {
    public static final int CAPACITY = 64;
    private final ArrayDeque<InputPayload> pending = new ArrayDeque<>();

    public synchronized boolean offer(InputPayload payload) {
        if (pending.size() >= CAPACITY) return false;
        pending.addLast(payload);
        return true;
    }

    public synchronized List<InputPayload> drain() {
        var batch = List.copyOf(pending);
        pending.clear();
        return batch;
    }
}
