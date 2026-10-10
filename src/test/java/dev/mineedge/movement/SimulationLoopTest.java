package dev.mineedge.movement;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationLoopTest {
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
