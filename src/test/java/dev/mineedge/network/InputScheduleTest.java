package dev.mineedge.network;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementRate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputScheduleTest {
    private static final long MS = 1_000_000L;

    @Test void scaledTransportKeepsHeldInputAliveAndAcknowledgesEverySimulationStep() {
        for (float tickRate : new float[] {1, 5, 12.5F, 20, 40, 100}) {
            var schedule = new InputSchedule();
            var inbox = new CommandInbox();
            long interval = MovementRate.intervalNanos(tickRate);
            var held = new MovementInput(1, 1, 0, 0, false, false, false, false, false, 0, 0);
            for (int step = 0; step < 80; step++) {
                var input = held.withSequence(step + 1);
                var payload = schedule.next(1, input, step * interval, interval);
                if (step % InputSchedule.FULL_INTERVAL_STEPS == 0) {
                    assertTrue(inbox.offer(assertInstanceOf(FullInputPayload.class, payload), step));
                } else {
                    assertNull(payload);
                }
                assertEquals(input, inbox.consumeSimulation(step, 5), "Held input expired at " + tickRate + " TPS");
                assertEquals(step + 1, inbox.acknowledged());
            }
        }
    }

    @Test void rateChangesRefreshTheBaselineWithoutResettingTransportOrdering() {
        var schedule = new InputSchedule();
        var inbox = new CommandInbox();
        var held = new MovementInput(1, 1, 0, 0, false, false, false, false, false, 0, 0);
        long now = 0;
        long updates = 0;
        for (int step = 0; step < 120; step++) {
            float tickRate = step < 18 ? 100 : step < 57 ? 5 : 40;
            long interval = MovementRate.intervalNanos(tickRate);
            var input = held.withSequence(step + 1);
            var payload = schedule.next(1, input, now, interval);
            if (payload != null) {
                var full = assertInstanceOf(FullInputPayload.class, payload);
                assertEquals(++updates, full.updateSequence());
                assertTrue(inbox.offer(full, step));
            }
            if (step == 18 || step == 57) assertNotNull(payload, "Rate changes must immediately refresh the heartbeat");
            assertEquals(input, inbox.consumeSimulation(step, 5));
            now += interval;
        }
    }

    @Test void unchangedInputsSendOnlyOneFullPacketPerSecondAt20Hz() {
        var schedule = new InputSchedule();
        int fullPackets = 0;
        for (int sample = 0; sample < 100; sample++) {
            var input = MovementInput.idle(sample + 1, 90, 10);
            var payload = schedule.next(1, input, sample * 50 * MS);
            if (sample % 20 == 0) {
                var full = assertInstanceOf(FullInputPayload.class, payload);
                assertEquals(input, full.input());
                assertEquals(++fullPackets, full.updateSequence());
            } else {
                assertNull(payload);
            }
        }
        assertEquals(5, fullPackets);
    }

    @Test void changedInputsSend20UpdatesPerSecond() {
        var schedule = new InputSchedule();
        var inbox = new CommandInbox();
        MovementInput previous = MovementInput.idle(1, 0, 0);
        for (int sample = 0; sample <= 40; sample++) {
            var input = previous.withSequence(sample + 1).withYaw(sample);
            var payload = schedule.next(1, input, sample * 50 * MS);
            assertNotNull(payload);
            assertEquals(sample + 1, payload.updateSequence());
            if (sample % 20 == 0) {
                var full = assertInstanceOf(FullInputPayload.class, payload);
                assertTrue(inbox.offer(full, sample));
            } else {
                var change = assertInstanceOf(InputChangePayload.class, payload);
                assertEquals(InputChangePayload.YAW, change.changed());
                assertEquals(input, change.apply(previous));
                assertTrue(inbox.offer(change, sample));
            }
            assertEquals(input, inbox.consumeSimulation(sample, 5));
            assertEquals(sample + 1, inbox.acknowledged());
            previous = input;
        }
    }

    @Test void rapidPollingIsThrottledAndStallsDoNotSendCatchUpBursts() {
        var schedule = new InputSchedule();
        var input = MovementInput.idle(1, 0, 0);
        assertNotNull(schedule.next(1, input, 0));
        assertNull(schedule.next(1, input.withYaw(1), 49 * MS));
        assertNotNull(schedule.next(1, input.withYaw(2), 50 * MS));
        assertNull(schedule.next(1, input.withYaw(3), 51 * MS));
        assertNotNull(schedule.next(1, input.withYaw(4), 300 * MS));
        assertNull(schedule.next(1, input.withYaw(5), 300 * MS));
        assertNull(schedule.next(1, input.withYaw(6), 349 * MS));
        assertNotNull(schedule.next(1, input.withYaw(7), 350 * MS));
    }

    @Test void normalTimerJitterDoesNotHalveTheUpdateRate() {
        var schedule = new InputSchedule();
        int packets = 0;
        for (int sample = 0; sample < 20; sample++) {
            long jitter = sample % 2 == 0 ? 100_000 : -100_000;
            if (schedule.next(1, MovementInput.idle(1, sample, 0), sample * 50 * MS + jitter) != null) packets++;
        }
        assertTrue(packets >= 19);
    }

    @Test void resetSendsANewFullBaselineImmediately() {
        var schedule = new InputSchedule();
        schedule.next(1, MovementInput.idle(100, 90, 10), 0);
        schedule.reset();
        var input = MovementInput.idle(1, -90, -10);
        assertEquals(new FullInputPayload(2, 1, input, false), schedule.next(2, input, 1));
        assertNull(schedule.next(2, input.withSequence(2), 25 * MS + 1));
    }
}
