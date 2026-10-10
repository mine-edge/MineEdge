package dev.mineedge.network;

import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotMailboxTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.parse("minecraft:overworld");
    private static final MovementState STATE = new MovementState(MovementVector.ZERO, MovementVector.ZERO,
            MovementState.Mode.GROUND, 0, true, 0);

    private static SnapshotPayload snapshot(long epoch, long tick) {
        return new SnapshotPayload(epoch, tick, tick, STATE, Pose.STANDING);
    }

    @Test void slowFramesRetainOnlyNewestCompleteBaselineAndSession() {
        var mailbox = new SnapshotMailbox();
        var session = new SessionPayload(DIMENSION, 1, 0, true, true, MovementTuning.DEFAULT);
        mailbox.offer(session);
        for (int tick = 1; tick <= 1000; tick++) mailbox.offer(snapshot(1, tick));
        var latest = mailbox.latest();
        assertEquals(session, latest.session());
        assertEquals(snapshot(1, 1000), latest.snapshot());
        // Data survives polling before player/level creation, rather than losing the only session packet.
        assertEquals(latest, mailbox.latest());
        mailbox.offer(snapshot(1, 900));
        assertEquals(latest, mailbox.latest());
    }

    @Test void newerEpochWinsEvenWhenItsDimensionClockIsEarlier() {
        var mailbox = new SnapshotMailbox();
        mailbox.offer(new SessionPayload(DIMENSION, 1, 1000, true, true, MovementTuning.DEFAULT));
        mailbox.offer(snapshot(1, 1000));
        var session = new SessionPayload(ResourceLocation.parse("minecraft:the_nether"), 2, 10, true, false, MovementTuning.DEFAULT);
        mailbox.offer(session);
        mailbox.offer(snapshot(2, 10));
        mailbox.offer(snapshot(1, 2000));
        mailbox.offer(new SessionPayload(DIMENSION, 1, 2000, true, true, MovementTuning.DEFAULT));
        assertEquals(session, mailbox.latest().session());
        assertEquals(snapshot(2, 10), mailbox.latest().snapshot());
        mailbox.clear();
        assertNull(mailbox.latest().session());
        assertNull(mailbox.latest().snapshot());
        mailbox.offer(snapshot(1, 1));
        assertEquals(snapshot(1, 1), mailbox.latest().snapshot());
    }

    @Test void concurrentNetworkOffersAndClientPollsPublishNewestCompleteRecords() throws Exception {
        var mailbox = new SnapshotMailbox();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var network = workers.submit(() -> {
                for (int tick = 1; tick <= 1000; tick++) {
                    mailbox.offer(new SessionPayload(DIMENSION, 1, tick, true, true, MovementTuning.DEFAULT));
                    mailbox.offer(snapshot(1, tick));
                }
            });
            var client = workers.submit(() -> {
                long previousTick = 0;
                for (int poll = 0; poll < 1000; poll++) {
                    var updates = mailbox.latest();
                    if (updates.snapshot() != null) {
                        assertNotNull(updates.session());
                        assertTrue(updates.session().serverTick() >= updates.snapshot().serverTick());
                        assertTrue(updates.snapshot().serverTick() >= previousTick);
                        assertEquals(updates.snapshot().serverTick(), updates.snapshot().acknowledged());
                        previousTick = updates.snapshot().serverTick();
                    }
                }
            });
            network.get();
            client.get();
        }
        assertEquals(1000, mailbox.latest().session().serverTick());
        assertEquals(snapshot(1, 1000), mailbox.latest().snapshot());
    }
}
