package dev.mineedge.network;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementRate;
import java.util.ArrayDeque;

/** Packet receipt buffers data only. Local connections preserve one command per physics step. */
public final class CommandInbox {
    public static final int CAPACITY = 32;
    public static final int MAX_SEQUENCE_ADVANCE = 128;
    private final ArrayDeque<MovementInput> pending = new ArrayDeque<>();
    private final boolean ordered;
    private long received;
    private long receivedUpdate;
    private long acknowledged;
    private long lastReceivedTick = Long.MIN_VALUE;
    private MovementInput held = MovementInput.idle(0, 0, 0);
    private MovementInput baseline;

    public CommandInbox() { this(0, 0); }

    public CommandInbox(float yaw, float pitch) {
        this(yaw, pitch, false);
    }

    public CommandInbox(float yaw, float pitch, boolean ordered) {
        this.ordered = ordered;
        held = MovementInput.idle(0, yaw, pitch);
    }

    public boolean offer(MovementInput input, long tick) {
        return offer(input, input.sequence(), tick);
    }

    private boolean offer(MovementInput input, long updateSequence, long tick) {
        if (updateSequence <= receivedUpdate || updateSequence - receivedUpdate > MAX_SEQUENCE_ADVANCE
                || input.sequence() < received || input.sequence() - received > MAX_SEQUENCE_ADVANCE
                || (ordered && input.sequence() != received + 1)
                || pending.size() >= CAPACITY) {
            return false;
        }
        pending.addLast(input);
        baseline = input.withoutEdges();
        received = input.sequence();
        receivedUpdate = updateSequence;
        lastReceivedTick = tick;
        return true;
    }

    public boolean offer(InputChangePayload payload, long tick) {
        // Reconstruct against the last accepted packet, including pending commands.
        // Rejected packets must not alter the baseline used by subsequent deltas.
        return baseline != null && offer(payload.apply(baseline), payload.updateSequence(), tick);
    }

    public boolean offer(FullInputPayload payload, long tick) {
        return offer(payload.input(), payload.updateSequence(), tick);
    }

    public MovementInput consume(long tick, int staleTicks) {
        return consume(tick, staleTicks, InputSchedule.FULL_INTERVAL_TICKS);
    }

    /** Returns null on an ordered connection when no actual client command is available. */
    public MovementInput consumeSimulation(long step, int staleGameTicks) {
        return consume(step, staleGameTicks * MovementRate.STEPS_PER_GAME_TICK, InputSchedule.FULL_INTERVAL_STEPS);
    }

    private MovementInput consume(long tick, int staleTicks, int fullInterval) {
        if (ordered) {
            // Separate game-thread clocks can run in either order or stall independently.
            // Never invent acknowledgements or collapse multiple predicted steps locally.
            var next = pending.pollFirst();
            if (next == null) return null;
            held = next;
            acknowledged = next.sequence();
            return next;
        }
        if (!pending.isEmpty()) {
            boolean jump = false;
            boolean crouch = false;
            boolean turn = false;
            while (!pending.isEmpty()) {
                MovementInput next = pending.removeFirst();
                // Explicit edges can include a tap released between ticks. Do not synthesize
                // an edge from held state: a new epoch/menu handoff deliberately latches it.
                jump |= next.jumpPressed();
                crouch |= next.crouchPressed();
                turn |= next.turnPressed();
                held = next;
            }
            acknowledged = held.sequence();
            return held.withEdges(jump, crouch, turn);
        }
        if (lastReceivedTick == Long.MIN_VALUE
                || tick - lastReceivedTick >= fullInterval + staleTicks) {
            held = MovementInput.idle(acknowledged, held.yaw(), held.pitch());
        } else {
            // Silent ticks still simulate client commands. Acknowledge them so prediction
            // does not replay movement that the server has already simulated.
            held = held.withSequence(++acknowledged);
        }
        return held.withoutEdges();
    }

    public long acknowledged() {
        return acknowledged;
    }

    public MovementInput latest() {
        return baseline;
    }

    public int size() {
        return pending.size();
    }
}
