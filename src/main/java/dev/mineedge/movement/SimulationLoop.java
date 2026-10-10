package dev.mineedge.movement;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent movement clock. At most one job per session may be queued or running. */
public final class SimulationLoop implements AutoCloseable {
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "MineEdge movement clock");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean pending = new AtomicBoolean();
    private final Executor gameThread;
    private final Runnable step;
    private volatile boolean closed;
    private ScheduledFuture<?> timer;
    private long intervalNanos;

    public SimulationLoop(Executor gameThread, Runnable step) {
        this(gameThread, step, MovementRate.GAME_TICK_HZ);
    }

    public SimulationLoop(Executor gameThread, Runnable step, float tickRate) {
        this.gameThread = gameThread;
        this.step = step;
        setTickRate(tickRate);
    }

    /** Changing the rate preserves session state and never queues catch-up steps. */
    public synchronized void setTickRate(float tickRate) {
        long interval = MovementRate.intervalNanos(tickRate);
        if (closed || interval == intervalNanos) return;
        intervalNanos = interval;
        if (timer != null) timer.cancel(false);
        timer = CLOCK.scheduleAtFixedRate(this::queueStep, interval, interval, TimeUnit.NANOSECONDS);
    }

    private void queueStep() {
        if (closed || !pending.compareAndSet(false, true)) return;
        try {
            gameThread.execute(() -> {
                try {
                    if (!closed) step.run();
                } finally {
                    pending.set(false);
                }
            });
        } catch (RuntimeException exception) {
            pending.set(false);
            close();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (timer != null) timer.cancel(false);
    }
}
