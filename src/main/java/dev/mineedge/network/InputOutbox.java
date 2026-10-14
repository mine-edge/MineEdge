package dev.mineedge.network;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementRate;
import java.util.function.Consumer;

/** Immutable input snapshots cross from the client thread to the simulation-rate sender. */
public final class InputOutbox {
    public record Dispatch(InputPayload payload, Consumer<InputPayload> sender) {
        public void send() { sender.accept(payload); }
    }
    private final InputSchedule schedule = new InputSchedule();
    private MovementInput pending;
    private long epoch;
    private Consumer<InputPayload> sender;

    public synchronized void submit(long epoch, MovementInput input, Consumer<InputPayload> sender) {
        if (this.epoch != epoch) clear();
        this.epoch = epoch;
        this.sender = sender;
        if (pending != null) {
            input = input.withEdges(input.jumpPressed() || pending.jumpPressed(),
                    input.crouchPressed() || pending.crouchPressed(), input.turnPressed() || pending.turnPressed());
        }
        pending = input;
    }

    public synchronized Dispatch poll(long now) {
        return poll(now, MovementRate.INTERVAL_NANOS);
    }

    public synchronized Dispatch poll(long now, long intervalNanos) {
        if (pending == null) return null;
        var payload = schedule.next(epoch, pending, now, intervalNanos);
        if (payload == null) return null;
        pending = pending.withoutEdges();
        return new Dispatch(payload, sender);
    }

    public synchronized void clear() {
        pending = null;
        sender = null;
        schedule.reset();
    }

    public synchronized void suppressEdges() {
        if (pending != null) pending = pending.withoutEdges();
    }
}
