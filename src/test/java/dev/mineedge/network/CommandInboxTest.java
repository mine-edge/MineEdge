package dev.mineedge.network;

import dev.mineedge.movement.MovementInput;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandInboxTest {
    @Test void localCommandsWaitForInputAndPreserveEveryStepAndEdgeAcrossBursts() {
        var inbox = new CommandInbox(90, 10, true);
        assertNull(inbox.consumeSimulation(0, 5));
        assertEquals(0, inbox.acknowledged());
        var press = command(1, 1, true, true).withEdges(true, true, true);
        var release = command(2, -1, false, false);
        assertTrue(inbox.offer(new FullInputPayload(1, press), 0));
        assertTrue(inbox.offer(new FullInputPayload(1, release), 0));
        assertEquals(press, inbox.consumeSimulation(0, 5));
        assertEquals(1, inbox.acknowledged());
        assertEquals(1, inbox.size());
        assertEquals(release, inbox.consumeSimulation(1, 5));
        assertEquals(2, inbox.acknowledged());
        for (int step : new int[]{2, 20, 100}) {
            assertNull(inbox.consumeSimulation(step, 5));
            assertEquals(2, inbox.acknowledged(), "Waiting must not invent client commands");
        }
        assertFalse(inbox.offer(new FullInputPayload(1, 3, release, false), 100));
        assertFalse(inbox.offer(new FullInputPayload(1, command(4, 1, false, false)), 100),
                "A missing local physics step must not be silently skipped");
        var resumed = command(3, 1, true, false);
        assertTrue(inbox.offer(new FullInputPayload(1, resumed), 100));
        assertEquals(resumed, inbox.consumeSimulation(100, 5));
        assertNull(inbox.consumeSimulation(101, 5));
    }

    @Test void turnEdgeSurvivesBurstThenExpiresWithoutRetriggering() {
        var inbox = new CommandInbox();
        inbox.offer(command(1, 1, false, false).withEdges(false, false, true), 10);
        inbox.offer(command(2, 1, false, false), 10);
        assertTrue(inbox.consume(10, 5).turnPressed());
        assertFalse(inbox.consume(11, 5).turnPressed());
        assertFalse(inbox.consume(35, 5).turnPressed());
    }
    static MovementInput command(long sequence, float forward, boolean held, boolean pressed) {
        return new MovementInput(sequence, forward, 0, 0, held, pressed, false, false, false, 90, 10);
    }

    @Test
    void burstCoalescesToOneCommandAndKeepsAPressFollowedByRelease() {
        CommandInbox inbox = new CommandInbox();
        assertTrue(inbox.offer(command(1, 1, true, true), 10));
        assertTrue(inbox.offer(command(2, -1, false, false), 10));
        MovementInput consumed = inbox.consume(10, 5);
        assertEquals(2, consumed.sequence());
        assertEquals(-1, consumed.forward());
        assertTrue(consumed.jumpPressed());
        assertFalse(consumed.jumpHeld());
        assertEquals(2, inbox.acknowledged());
        assertEquals(0, inbox.size());
        assertFalse(inbox.consume(11, 5).jumpPressed());
    }

    @Test
    void staleInputStopsAndCannotRepeatEdges() {
        CommandInbox inbox = new CommandInbox();
        inbox.offer(command(1, 1, true, true), 100);
        assertTrue(inbox.consume(100, 5).jumpPressed());
        for (int tick = 101; tick < 125; tick++) {
            MovementInput held = inbox.consume(tick, 5);
            assertEquals(1, held.forward());
            assertFalse(held.jumpPressed());
            assertEquals(tick - 99, held.sequence());
            assertEquals(held.sequence(), inbox.acknowledged());
        }
        MovementInput expired = inbox.consume(125, 5);
        assertEquals(0, expired.forward());
        assertFalse(expired.jumpHeld());
        assertFalse(expired.jumpPressed());
        assertEquals(25, expired.sequence());
    }

    @Test
    void rejectsDuplicateReorderedAndUnboundedSequenceCommands() {
        CommandInbox inbox = new CommandInbox();
        assertTrue(inbox.offer(command(2, 1, false, false), 1));
        assertFalse(inbox.offer(command(2, 1, true, true), 1));
        assertFalse(inbox.offer(command(1, 1, true, true), 1));
        assertFalse(inbox.offer(command(1000, 1, true, true), 1));
        assertEquals(1, inbox.size());
        assertFalse(inbox.consume(1, 5).jumpPressed());
    }

    @Test
    void boundsQueueWithoutAcknowledgingUnsimulatedCommands() {
        CommandInbox inbox = new CommandInbox();
        for (int sequence = 1; sequence <= CommandInbox.CAPACITY; sequence++) {
            assertTrue(inbox.offer(command(sequence, 1, false, false), 1));
        }
        assertFalse(inbox.offer(command(CommandInbox.CAPACITY + 1, 1, true, true), 1));
        assertEquals(0, inbox.acknowledged());
        assertEquals(CommandInbox.CAPACITY, inbox.consume(1, 5).sequence());
    }

    @Test
    void preservesInitialViewWhileWaitingForFirstCommand() {
        MovementInput input = new CommandInbox(120, -30).consume(1, 5);
        assertEquals(120, input.yaw());
        assertEquals(-30, input.pitch());
    }

    @Test
    void heldButtonsAfterEpochResetCannotSynthesizeAPress() {
        CommandInbox inbox = new CommandInbox();
        inbox.offer(command(1, 0, true, false), 1);
        MovementInput consumed = inbox.consume(1, 5);
        assertTrue(consumed.jumpHeld());
        assertFalse(consumed.jumpPressed());
    }

    @Test void deltasRequireAFullBaselineAndMergePendingPacketsInOrder() {
        var inbox = new CommandInbox();
        var start = command(1, 1, false, false);
        var press = command(2, 1, true, true);
        var release = command(3, 1, false, false).withYaw(120);
        assertFalse(inbox.offer(InputChangePayload.between(1, start, press), 10));
        assertTrue(inbox.offer(start, 10));
        assertTrue(inbox.offer(InputChangePayload.between(1, start, press), 10));
        assertTrue(inbox.offer(InputChangePayload.between(1, press, release), 10));
        assertEquals(release.withEdges(true, false), inbox.consume(10, 5));
        assertEquals(release.withSequence(4), inbox.consume(11, 5));
    }

    @Test void rejectedDeltaCannotCorruptTheNextDeltaBaseline() {
        var inbox = new CommandInbox();
        var start = command(1, 1, false, false);
        assertTrue(inbox.offer(start, 10));
        var rejected = command(1, -1, false, false);
        assertFalse(inbox.offer(InputChangePayload.between(1, start, rejected), 10));
        var next = start.withSequence(2).withYaw(120);
        assertTrue(inbox.offer(InputChangePayload.between(1, start, next), 10));
        assertEquals(next, inbox.consume(10, 5));
    }

    @Test void staleSimulationDoesNotEraseTheWireBaselineAndFullPacketsRestoreIt() {
        var inbox = new CommandInbox();
        var start = command(1, 1, true, true);
        inbox.offer(start, 1);
        inbox.consume(1, 5);
        assertEquals(0, inbox.consume(26, 5).forward());
        var next = start.withSequence(27).withoutEdges().withYaw(120);
        assertTrue(inbox.offer(InputChangePayload.between(1, start, next), 27));
        assertEquals(next, inbox.consume(27, 5));
        var restored = command(41, -1, false, false);
        assertTrue(inbox.offer(restored, 41));
        assertEquals(restored, inbox.consume(41, 5));
    }

    @Test void simulationCadenceAcknowledgesTwentySilentCommandsBetweenFullPackets() {
        var inbox = new CommandInbox();
        var input = command(1, 1, true, true);
        assertTrue(inbox.offer(new FullInputPayload(1, 1, input, false), 0));
        for (int step = 0; step < 20; step++) {
            var consumed = inbox.consumeSimulation(step, 5);
            assertEquals(step + 1, consumed.sequence());
            assertEquals(1, consumed.forward());
            assertEquals(step == 0, consumed.jumpPressed());
        }
        assertTrue(inbox.offer(new FullInputPayload(1, 2, input.withSequence(21).withoutEdges(), false), 20));
        assertEquals(21, inbox.consumeSimulation(20, 5).sequence());
        for (int step = 21; step < 45; step++) assertEquals(1, inbox.consumeSimulation(step, 5).forward());
        assertEquals(0, inbox.consumeSimulation(45, 5).forward());
        assertFalse(inbox.consumeSimulation(46, 5).jumpPressed());
    }
}
