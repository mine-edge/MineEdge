package dev.mineedge.network;

import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionOrderingTest {
    @Test void sessionCannotOvertakeQueuedLoginOrTeleportEvenWhenSnapshotsArriveEarly() throws Exception {
        // Inspect the actual registered handlers, including NeoForge's main-thread dispatch wrapper.
        var registrationsField = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        registrationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var registrations = (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) registrationsField.get(null);
        var play = registrations.get(ConnectionProtocol.PLAY);
        if (!play.containsKey(SessionPayload.TYPE.id())) MovementNetwork.register(new RegisterPayloadHandlersEvent());
        @SuppressWarnings("unchecked")
        var sessionHandler = (IPayloadHandler<SessionPayload>) play.get(SessionPayload.TYPE.id()).handler();
        @SuppressWarnings("unchecked")
        var snapshotHandler = (IPayloadHandler<SnapshotPayload>) play.get(SnapshotPayload.TYPE.id()).handler();

        var receiverField = MovementNetwork.class.getDeclaredField("clientSessionReceiver");
        var snapshotReceiverField = MovementNetwork.class.getDeclaredField("clientReceiver");
        receiverField.setAccessible(true);
        snapshotReceiverField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var previousReceiver = (Consumer<SessionPayload>) receiverField.get(null);
        @SuppressWarnings("unchecked")
        var previousSnapshotReceiver = (Consumer<SnapshotPayload>) snapshotReceiverField.get(null);
        try {
            var mailbox = new SnapshotMailbox();
            MovementNetwork.installClientSessionReceiver(mailbox::offer);
            MovementNetwork.installClientReceiver(mailbox::offer);
            var queued = new ArrayDeque<Runnable>();
            var context = (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                    new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("enqueueWork") && args[0] instanceof Runnable task) {
                            var completion = new CompletableFuture<Void>();
                            queued.add(() -> {
                                task.run();
                                completion.complete(null);
                            });
                            return completion;
                        }
                        throw new AssertionError("Unexpected payload context call: " + method);
                    });
            var adoptedEpoch = new AtomicLong();
            var minimumEpoch = new AtomicLong();
            var dimension = ResourceLocation.parse("minecraft:overworld");
            var state = new MovementState(MovementVector.ZERO, MovementVector.ZERO,
                    MovementState.Mode.GROUND, 0, true, 0);

            // Cover a fresh/reopened connection, a relocation, and a fresh connection to a restarted server.
            for (long nextEpoch : new long[] {10, 11, 1}) {
                mailbox.clear();
                if (nextEpoch == 1) adoptedEpoch.set(0);
                // A movement step was queued before the network received teleport + session + baseline.
                queued.add(() -> {
                    var session = mailbox.latest().session();
                    if (session != null) adoptedEpoch.set(session.epoch());
                });
                queued.add(() -> minimumEpoch.set(adoptedEpoch.get() + 1));
                var session = new SessionPayload(dimension, nextEpoch, 100, true, true, MovementTuning.DEFAULT);
                var snapshot = new SnapshotPayload(nextEpoch, 100, 0, state, Pose.STANDING);
                sessionHandler.handle(session, context);
                snapshotHandler.handle(snapshot, context);
                assertNull(mailbox.latest().session(), "Session publication bypassed the queued teleport");
                assertEquals(snapshot, mailbox.latest().snapshot());
                while (!queued.isEmpty()) queued.remove().run();
                assertEquals(session, mailbox.latest().session());
                assertTrue(nextEpoch >= minimumEpoch.get(), "Teleport invalidated the new movement epoch");
                assertEquals(nextEpoch, mailbox.latest().snapshot().epoch());
                adoptedEpoch.set(nextEpoch);
            }
        } finally {
            MovementNetwork.installClientSessionReceiver(previousReceiver);
            MovementNetwork.installClientReceiver(previousSnapshotReceiver);
        }
    }
}
