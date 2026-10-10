package dev.mineedge.network;

/** Network-thread ingress. Retains at most one session and one complete snapshot across slow frames. */
public final class SnapshotMailbox {
    public record Updates(SessionPayload session, SnapshotPayload snapshot) {}
    private SessionPayload session;
    private SnapshotPayload snapshot;

    public synchronized void offer(SessionPayload next) {
        if (session == null || newer(next.epoch(), next.serverTick(), session.epoch(), session.serverTick())) session = next;
    }

    public synchronized void offer(SnapshotPayload next) {
        if (snapshot == null || newer(next.epoch(), next.serverTick(), snapshot.epoch(), snapshot.serverTick())) snapshot = next;
    }

    // Retain metadata/baseline until the local player and dimension exist. The client rejects already-applied ticks.
    public synchronized Updates latest() { return new Updates(session, snapshot); }

    public synchronized void clear() {
        session = null;
        snapshot = null;
    }

    private static boolean newer(long epoch, long tick, long previousEpoch, long previousTick) {
        return epoch > previousEpoch || epoch == previousEpoch && tick > previousTick;
    }
}
