package dev.mineedge.client.movement;

import dev.mineedge.integration.MovementEligibility;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.network.SessionPayload;
import dev.mineedge.network.SnapshotPayload;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientTuningTest {
    @Test void tuningMetadataPreservesPredictionAndSnapshotsWaitForTheirMatchingRevision() throws Exception {
        var minecraft = mock(Minecraft.class);
        var player = mock(LocalPlayer.class);
        var level = mock(ClientLevel.class);
        minecraft.player = player;
        player.input = new Input();
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        try (var client = mockStatic(Minecraft.class); var eligibility = mockStatic(MovementEligibility.class)) {
            client.when(Minecraft::getInstance).thenReturn(minecraft);
            eligibility.when(() -> MovementEligibility.supported(player)).thenReturn(true);
            var movement = ClientMovement.INSTANCE;
            movement.logout(null);
            try {
                apply(movement, "receiveSession", SessionPayload.class,
                        new SessionPayload(Level.OVERWORLD.location(), 10, 100, true, true, MovementTuning.DEFAULT, 0));
                var state = new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 4),
                        MovementState.Mode.GROUND, 1, true, 0);
                field("state").set(movement, state);
                field("sequence").setLong(movement, 20);
                field("acknowledged").setLong(movement, 10);
                field("serverTick").setLong(movement, 100);
                var history = (CommandHistory) field("history").get(movement);
                for (int sequence = 11; sequence <= 20; sequence++) {
                    history.add(MovementInput.idle(sequence, 0, 0));
                    history.recordPrediction(sequence, state);
                }
                var impulses = (PredictionImpulses) field("impulses").get(movement);
                var push = new MovementVector(2, 3, 0);
                impulses.capture(impulses.written(state.velocity()).add(push.scale(1.0 / 20)));
                var pendingPush = impulses.pending();
                var clock = field("simulation").get(movement);
                var snapshot = new SnapshotPayload(10, 15, 101, state, Pose.STANDING, 1);

                // The network-thread baseline may precede its game-thread metadata.
                apply(movement, "reconcile", SnapshotPayload.class, snapshot);
                assertEquals(100, movement.serverTick());
                assertEquals(10, movement.acknowledged());
                assertEquals(10, history.size());
                assertSame(state, field("state").get(movement));

                var slow = new MovementTuning(1.5, 7, 20, 61.44, 1.536, 5);
                apply(movement, "receiveSession", SessionPayload.class,
                        new SessionPayload(Level.OVERWORLD.location(), 10, 101, true, true, slow, 1));
                assertSame(state, field("state").get(movement));
                assertSame(clock, field("simulation").get(movement));
                assertEquals(20, movement.sequence());
                assertEquals(10, movement.acknowledged());
                assertEquals(10, history.size());
                assertEquals(pendingPush, impulses.pending());
                assertFalse(history.matches(15, state), "New tuning must invalidate cached states without deleting commands");

                // Older tuning must not be applied even if the snapshot's tick is newer.
                apply(movement, "reconcile", SnapshotPayload.class,
                        new SnapshotPayload(10, 19, 102, state, Pose.STANDING, 0));
                assertEquals(100, movement.serverTick());
                assertEquals(10, history.size());

                // Refreshed predictions survive unchanged metadata and use the matching fast path.
                history.recordPrediction(15, state);
                apply(movement, "receiveSession", SessionPayload.class,
                        new SessionPayload(Level.OVERWORLD.location(), 10, 103, true, true, slow, 1));
                assertTrue(history.matches(15, state));
                apply(movement, "reconcile", SnapshotPayload.class, snapshot);
                assertEquals(101, movement.serverTick());
                assertEquals(15, movement.acknowledged());
                assertEquals(20, movement.sequence());
                assertEquals(5, history.size());

                apply(movement, "receiveSession", SessionPayload.class,
                        new SessionPayload(Level.OVERWORLD.location(), 10, 104, true, true, MovementTuning.DEFAULT, 2));
                assertSame(state, field("state").get(movement));
                assertEquals(5, history.size());
                assertEquals(20, movement.sequence());
                assertEquals(pendingPush, impulses.pending());

                // Actual lifecycle transitions still establish a fresh baseline.
                apply(movement, "receiveSession", SessionPayload.class,
                        new SessionPayload(Level.OVERWORLD.location(), 11, 105, true, true, MovementTuning.DEFAULT, 0));
                assertNull(field("state").get(movement));
                assertEquals(0, movement.sequence());
                assertEquals(0, movement.acknowledged());
                assertEquals(0, history.size());
                assertEquals(MovementVector.ZERO, impulses.pending());
            } finally {
                movement.logout(null);
            }
        }
    }

    private static Field field(String name) throws ReflectiveOperationException {
        var field = ClientMovement.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> void apply(ClientMovement movement, String name, Class<T> type, T payload) throws ReflectiveOperationException {
        var method = ClientMovement.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(movement, payload);
    }
}
