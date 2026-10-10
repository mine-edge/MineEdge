package dev.mineedge.client.movement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HandPresentationTest {
    private static final long MS = 1_000_000L;

    @Test void walkingPhaseIsContinuousAcrossNativeTickBoundaries() {
        var hands = new HandPresentation();
        hands.reset(0, 0, 0, 0);
        for (int frame = 0; frame <= 200; frame++) {
            if (frame > 0 && frame % 50 == 0) hands.record(frame * 0.0024F, frame * MS);
            var shot = hands.update(frame * MS, 0, 0, 4, true, false);
            assertEquals(Math.max(0, frame - 50) * 0.0024, shot.walkDistance(), 1.0E-7);
            assertTrue(shot.bob() >= 0 && shot.bob() <= 0.1F);
        }
    }

    @Test void lookSwayAndBobResponseAreIndependentOfRenderFrameRate() {
        var coarse = new HandPresentation();
        var fine = new HandPresentation();
        coarse.reset(0, 0, 0, 0);
        fine.reset(0, 0, 0, 0);
        var expected = coarse.update(50 * MS, 20, 30, 4, true, false);
        HandPresentation.Shot actual = null;
        for (int frame = 1; frame <= 50; frame++) actual = fine.update(frame * MS, 20, 30, 4, true, false);
        assertEquals(10, expected.pitchLag(), 1.0E-6);
        assertEquals(15, expected.yawLag(), 1.0E-6);
        assertEquals(0.04, expected.bob(), 1.0E-6);
        assertEquals(expected.pitchLag(), actual.pitchLag(), 1.0E-6);
        assertEquals(expected.yawLag(), actual.yawLag(), 1.0E-6);
        assertEquals(expected.bob(), actual.bob(), 1.0E-6);
    }

    @Test void yawWrappingNeverProducesAFullTurnHandKick() {
        var hands = new HandPresentation();
        hands.reset(0, 0, 179, 0);
        var forward = hands.update(25 * MS, 0, -179, 0, true, false);
        assertTrue(forward.yawLag() > 0 && forward.yawLag() < 2);
        hands.reset(0, 0, -179, 0);
        var reverse = hands.update(25 * MS, 0, 179, 0, true, false);
        assertTrue(reverse.yawLag() < 0 && reverse.yawLag() > -2);
    }

    @Test void jumpingFadesWalkingBobRatherThanSnappingItOff() {
        var hands = new HandPresentation();
        hands.reset(0, 0, 0, 0);
        var grounded = hands.update(200 * MS, 0, 0, 7, true, false);
        var airborne = hands.update(225 * MS, 0, 0, 7, false, false);
        assertTrue(airborne.bob() > 0 && airborne.bob() < grounded.bob());
        assertEquals(grounded.bob() * Math.sqrt(0.6), airborne.bob(), 1.0E-6);
    }

    @Test void pauseFreezesTheShotAndResetsDoNotCarryOldStrideOrSway() {
        var hands = new HandPresentation();
        hands.reset(5, 0, 0, 0);
        var before = hands.update(25 * MS, 10, 20, 4, true, false);
        assertEquals(before, hands.update(1_000 * MS, 80, -100, 0, false, true));
        assertEquals(before, hands.update(2_000 * MS, 80, -100, 0, false, true));
        hands.reset(30, 80, -100, 2_001 * MS);
        assertEquals(new HandPresentation.Shot(30, 0, 0, 0), hands.update(2_001 * MS, 80, -100, 0, false, false));
        hands.clear();
        assertNull(hands.update(3_000 * MS, 0, 0, 0, true, false));
    }
}
