package dev.mineedge.movement;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationLoopTest {
    @Test void configuredTickRateScalesTheWallClockWithoutChangingPhysicsTime() {
        assertEquals(1_000_000_000L, MovementRate.intervalNanos(1));
        assertEquals(200_000_000L, MovementRate.intervalNanos(5));
        assertEquals(MovementRate.INTERVAL_NANOS, MovementRate.intervalNanos(20));
        assertEquals(25_000_000L, MovementRate.intervalNanos(40));
        assertEquals(80_000_000L, MovementRate.intervalNanos(12.5F));
        assertEquals(100_000L, MovementRate.intervalNanos(10_000));
        assertEquals(0.05, MovementRate.STEP_SECONDS);
    }

    @Test void rateChangesRescheduleLiveWorkWithoutBacklogOrReopeningAClosedLoop() throws Exception {
        var queued = new LinkedBlockingQueue<Runnable>();
        var steps = new AtomicInteger();
        try (var loop = new SimulationLoop(queued::add, steps::incrementAndGet, 1)) {
            assertNull(queued.poll(150, TimeUnit.MILLISECONDS), "A 1 TPS simulation must not run at 20 Hz");
            loop.setTickRate(40);
            Runnable first = queued.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNull(queued.poll(100, TimeUnit.MILLISECONDS), "Changing rates must retain the one-job limit");
            loop.setTickRate(1);
            first.run();
            assertEquals(1, steps.get());
            assertNull(queued.poll(150, TimeUnit.MILLISECONDS), "The old fast timer must be cancelled");
            loop.close();
            loop.setTickRate(40);
            assertNull(queued.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test void clockQueuesWorkWithoutAnyGameTicksAndClosingInvalidatesQueuedWork() throws Exception {
        var queued = new LinkedBlockingQueue<Runnable>();
        var steps = new AtomicInteger();
        try (var loop = new SimulationLoop(queued::add, steps::incrementAndGet)) {
            Runnable first = queued.poll(2, TimeUnit.SECONDS);
            assertNotNull(first, "The movement clock must not depend on default game ticks");
            // Holding a queued step must not create a backlog of catch-up movement.
            assertNull(queued.poll(3 * MovementRate.INTERVAL_NANOS, TimeUnit.NANOSECONDS));
            first.run();
            assertEquals(1, steps.get());
            Runnable next = queued.poll(2, TimeUnit.SECONDS);
            assertNotNull(next);
            loop.close();
            next.run();
            assertEquals(1, steps.get());
        }
    }
}
