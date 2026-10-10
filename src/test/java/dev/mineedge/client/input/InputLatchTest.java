package dev.mineedge.client.input;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputLatchTest {
    @Test void turnTapsAreConsumedOnceAndSuppressedAcrossMenuInterruption() {
        var latch = new InputLatch();
        latch.pressTurn();
        assertTrue(latch.capture(false, false, false, false).turn());
        assertFalse(latch.capture(false, false, false, false).turn());
        latch.pressTurn(); latch.interrupt();
        assertFalse(latch.capture(false, false, true, false).turn());
        assertFalse(latch.capture(false, false, true, false).turn());
        latch.capture(false, false, false, false);
        assertTrue(latch.capture(false, false, true, false).turn());
    }
    @Test
    void shortTapsSurviveReleaseButEdgesAreConsumedOnce() {
        var latch = new InputLatch();
        latch.pressJump();
        latch.pressCrouch();
        assertEquals(new InputLatch.Edges(true, true), latch.capture(false, false, false));
        assertEquals(new InputLatch.Edges(false, false), latch.capture(false, false, false));
    }

    @Test
    void heldAndToggleStatesHaveFreshPressEdgesWithoutRepeat() {
        var latch = new InputLatch();
        assertTrue(latch.capture(false, true, false).crouch());
        assertFalse(latch.capture(false, true, false).crouch());
        assertFalse(latch.capture(false, false, false).crouch());
        assertTrue(latch.capture(false, true, false).crouch());
    }

    @Test
    void interruptionDropsPendingEdgesAndSuppressesHeldResumption() {
        var latch = new InputLatch();
        latch.pressJump();
        latch.pressCrouch();
        latch.interrupt();
        assertEquals(new InputLatch.Edges(false, false), latch.capture(true, true, true));
        assertEquals(new InputLatch.Edges(false, false), latch.capture(true, true, false));
        assertEquals(new InputLatch.Edges(false, false), latch.capture(true, true, false));
        latch.capture(false, false, false);
        assertEquals(new InputLatch.Edges(true, true), latch.capture(true, true, false));
        latch.resume(false, false);
        assertEquals(new InputLatch.Edges(false, false), latch.capture(true, true, false));
    }
}
