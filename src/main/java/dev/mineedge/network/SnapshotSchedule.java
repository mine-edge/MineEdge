package dev.mineedge.network;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;

/** Independent session and replay cadences. Transitions bypass the routine replay interval. */
public final class SnapshotSchedule {
    public static final int INACTIVE_INTERVAL_TICKS = 20;
    private long lastTick = Long.MIN_VALUE, epoch;
    private boolean enabled, active;
    private MovementState.Mode mode;
    private MovementBody body;
    private Pose pose;
    private MovementTuning tuning;
    private ResourceLocation dimension;
    private SessionPayload session;

    public boolean sessionDue(SessionPayload next) {
        boolean changed = session == null || next.serverTick() < session.serverTick() || next.epoch() != session.epoch()
                || next.enabled() != session.enabled() || next.active() != session.active()
                || !next.tuning().equals(session.tuning()) || !next.dimension().equals(session.dimension());
        if (!changed && (next.active() || next.serverTick() - session.serverTick() < INACTIVE_INTERVAL_TICKS)) return false;
        session = next;
        return true;
    }

    public boolean due(long tick, long epoch, boolean enabled, boolean active, MovementState state,
                       Pose pose, MovementTuning tuning, ResourceLocation dimension, int activeInterval) {
        MovementState.Mode mode = active ? state.mode() : null;
        MovementBody body = active ? state.body() : null;
        boolean urgent = lastTick == Long.MIN_VALUE || tick < lastTick || this.epoch != epoch
                || this.enabled != enabled || this.active != active || this.mode != mode || this.body != body
                || active && this.pose != pose || !tuning.equals(this.tuning) || !dimension.equals(this.dimension);
        boolean send = active && (urgent || tick - lastTick >= activeInterval);
        if (!urgent && !send) return false;
        lastTick = tick;
        this.epoch = epoch;
        this.enabled = enabled;
        this.active = active;
        this.mode = mode;
        this.body = body;
        this.pose = pose;
        this.tuning = tuning;
        this.dimension = dimension;
        return send;
    }
}
