package dev.mineedge.network;

import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotScheduleTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.parse("minecraft:overworld");
    private static final MovementState GROUND = state(MovementState.Mode.GROUND);
    private static MovementState state(MovementState.Mode mode) {
        return new MovementState(MovementVector.ZERO, MovementVector.ZERO, mode, 0, true, 0);
    }
    private static boolean due(SnapshotSchedule schedule, long tick, long epoch, boolean active, MovementState state, int interval) {
        return schedule.due(tick, epoch, true, active, state, Pose.STANDING, MovementTuning.DEFAULT, DIMENSION, interval);
    }
    @Test void stableCadenceIsIndependentOfPhysicsAndInactivePlayersOnlyGetSessionHeartbeat() {
        for (int interval : new int[]{1, 2, 5}) {
            var schedule = new SnapshotSchedule();
            int sent = 0;
            for (int tick = 0; tick < 100; tick++) if (due(schedule, tick, 1, true, GROUND, interval)) sent++;
            assertEquals(100 / interval, sent);
        }
        var inactive = new SnapshotSchedule();
        int sent = 0;
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(due(inactive, tick, 1, false, null, 2));
            if (inactive.sessionDue(session(tick, 1, false, MovementTuning.DEFAULT))) sent++;
        }
        assertEquals(5, sent);
    }
    @Test void lifecycleAndGameplayTransitionsSendImmediately() {
        var schedule = new SnapshotSchedule();
        assertTrue(due(schedule, 0, 1, true, GROUND, 5));
        assertFalse(due(schedule, 1, 1, true, GROUND, 5));
        assertTrue(due(schedule, 2, 1, true, state(MovementState.Mode.ROLL), 5));
        assertFalse(due(schedule, 3, 1, false, null, 5));
        assertFalse(due(schedule, 4, 2, false, null, 5));
        assertTrue(due(schedule, 5, 2, true, GROUND, 5));
        assertTrue(schedule.due(6, 2, true, true, GROUND, Pose.CROUCHING, MovementTuning.DEFAULT, DIMENSION, 5));
        assertTrue(schedule.due(7, 2, true, true, GROUND, Pose.CROUCHING, new MovementTuning(5, 6.3, 16.4, 61.44, 1.536, 5), DIMENSION, 5));
        assertTrue(schedule.due(8, 2, true, true, GROUND, Pose.STANDING, MovementTuning.DEFAULT, ResourceLocation.parse("minecraft:the_nether"), 5));
        assertTrue(schedule.due(9, 2, false, true, GROUND, Pose.STANDING, MovementTuning.DEFAULT, DIMENSION, 5));
    }
    @Test void changingIntervalAppliesWithoutResettingSimulation() {
        var schedule = new SnapshotSchedule();
        assertTrue(due(schedule, 10, 1, true, GROUND, 5));
        assertFalse(due(schedule, 11, 1, true, GROUND, 5));
        assertTrue(due(schedule, 12, 1, true, GROUND, 2));
        assertTrue(due(schedule, 1, 1, true, GROUND, 2)); // Clock reset cannot postpone synchronization.
    }

    private static SessionPayload session(long tick, long epoch, boolean active, MovementTuning tuning) {
        return new SessionPayload(DIMENSION, epoch, tick, true, active, tuning);
    }

    @Test void activeSessionSettingsOnlySendOnChangeAndPrecedeFreshBaseline() {
        var schedule = new SnapshotSchedule();
        assertTrue(schedule.sessionDue(session(0, 1, true, MovementTuning.DEFAULT)));
        for (int tick = 1; tick <= 100; tick++) {
            assertFalse(schedule.sessionDue(session(tick, 1, true, MovementTuning.DEFAULT)));
        }
        var changed = new MovementTuning(5, 7, 20, 61, 2, 5);
        assertTrue(schedule.sessionDue(session(101, 2, true, changed)));
        assertTrue(due(schedule, 101, 2, true, GROUND, 5));
        assertTrue(schedule.sessionDue(session(102, 3, false, changed)));
        assertFalse(due(schedule, 102, 3, false, null, 5));
        assertTrue(schedule.sessionDue(new SessionPayload(DIMENSION, 3, 103, false, false, changed)));
        assertTrue(schedule.sessionDue(new SessionPayload(ResourceLocation.parse("minecraft:the_nether"), 3, 104, false, false, changed)));
    }

    @Test void attributeTransitionsPublishMetadataAndBaselineWithoutChangingEpoch() {
        var schedule = new SnapshotSchedule();
        var slow = new MovementTuning(1.5, 7, 20, 61.44, 1.536, 5);
        long epoch = 10;
        for (int tick = 0; tick < 4; tick++) {
            var tuning = tick == 1 ? slow : MovementTuning.DEFAULT;
            var metadata = new SessionPayload(DIMENSION, epoch, tick, true, true, tuning, tick);
            assertTrue(schedule.sessionDue(metadata));
            assertTrue(schedule.due(tick, epoch, tick, true, true, GROUND, Pose.STANDING, tuning, DIMENSION, 20),
                    "Tuning revisions must bypass the routine baseline interval, including a return to the same values");
        }
        assertFalse(schedule.sessionDue(new SessionPayload(DIMENSION, epoch, 4, true, true, MovementTuning.DEFAULT, 3)));
        assertFalse(schedule.due(4, epoch, 3, true, true, GROUND, Pose.STANDING, MovementTuning.DEFAULT, DIMENSION, 20));
    }
}
