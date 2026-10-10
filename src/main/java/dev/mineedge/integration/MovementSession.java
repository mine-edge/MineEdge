package dev.mineedge.integration;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.SimulationLoop;
import dev.mineedge.network.CommandInbox;
import dev.mineedge.network.SnapshotSchedule;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;

/** Server-thread-only session. Movement state is saved; connection and simulation bookkeeping are transient. */
public final class MovementSession {
    private static final AtomicLong EPOCHS = new AtomicLong();
    public long epoch = EPOCHS.incrementAndGet();
    public boolean active;
    public boolean resetRequested;
    // Retained across reset until tracking clients receive the hand/slide release pose.
    public boolean posePublished;
    public long simulationStep;
    public SimulationLoop simulation;
    public CommandInbox inbox = new CommandInbox();
    public MovementInput input = MovementInput.idle(0, 0, 0);
    public MovementState state;
    public ResourceLocation dimension;
    public boolean resumePending;
    public MovementTuning tuning;
    public MovementVector writtenVelocity = MovementVector.ZERO;
    public final SnapshotSchedule snapshots = new SnapshotSchedule();

    public void reset() {
        if (simulation != null) simulation.close();
        simulation = null;
        epoch = EPOCHS.incrementAndGet();
        active = false;
        resetRequested = false;
        inbox = new CommandInbox();
        input = MovementInput.idle(0, 0, 0);
        state = null;
        dimension = null;
        resumePending = false;
        tuning = null;
        writtenVelocity = MovementVector.ZERO;
        simulationStep = 0;
    }

    /** Stop the connection's clock and inputs without discarding the last authoritative state. */
    public void suspend() {
        MovementState saved = state;
        ResourceLocation savedDimension = dimension;
        boolean resumable = (active || resumePending) && saved != null && savedDimension != null;
        reset();
        if (resumable) {
            state = saved;
            dimension = savedDimension;
            resumePending = true;
        }
    }

    public boolean canResume(ResourceLocation currentDimension, MovementVector position) {
        return resumePending && state != null && currentDimension.equals(dimension)
                && state.position().subtract(position).length() < 1.0E-6;
    }
}
