package dev.mineedge.network;

import dev.mineedge.movement.MovementInput;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputOutboxTest {
    @Test void tapsSurviveMultipleFrameSamplesAndAreSentOnlyOnce() {
        var outbox = new InputOutbox();
        var sent = new ArrayList<InputPayload>();
        var idle = MovementInput.idle(1, 0, 0);
        outbox.submit(1, idle, sent::add);
        outbox.poll(0).send();
        outbox.submit(1, idle.withEdges(true, true, true), sent::add);
        outbox.submit(1, idle, sent::add);
        assertNull(outbox.poll(10_000_000));
        outbox.poll(50_000_000).send();
        var change = assertInstanceOf(InputChangePayload.class, sent.get(1));
        assertTrue(change.apply(idle).jumpPressed());
        assertTrue(change.apply(idle).crouchPressed());
        assertTrue(change.apply(idle).turnPressed());
        assertNull(outbox.poll(100_000_000));
        assertEquals(2, sent.size());
    }

    @Test void menuInterruptionDropsPendingPressesAndEpochChangesResetTransport() {
        var outbox = new InputOutbox();
        var idle = MovementInput.idle(1, 0, 0);
        outbox.submit(1, idle, payload -> {});
        outbox.poll(0);
        outbox.submit(1, idle.withEdges(true, true, true), payload -> {});
        outbox.suppressEdges();
        assertNull(outbox.poll(25_000_000));
        outbox.clear();
        assertNull(outbox.poll(50_000_000));
        outbox.submit(2, idle, payload -> {});
        assertEquals(new FullInputPayload(2, 1, idle, false), outbox.poll(50_000_000).payload());
    }
}
