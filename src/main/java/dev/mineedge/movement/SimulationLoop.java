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
    private volatile boolean closed;
    private final ScheduledFuture<?> timer;

    public SimulationLoop(Executor gameThread, Runnable step) {
        timer = CLOCK.scheduleAtFixedRate(() -> {
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
        }, MovementRate.INTERVAL_NANOS, MovementRate.INTERVAL_NANOS, TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        closed = true;
        if (timer != null) timer.cancel(false);
    }
}
