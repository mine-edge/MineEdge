package dev.mineedge.network;

import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.config.ServerConfigService;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;

public final class MovementNetwork {
    // Installed by the client-only entry point; common registration never references client classes.
    private static volatile Consumer<SnapshotPayload> clientReceiver = snapshot -> {};
    private static volatile Consumer<SessionPayload> clientSessionReceiver = session -> {};
    private static Consumer<ServerConfigPayload> clientConfigReceiver = config -> {};
    private static Consumer<HandTargetsPayload> clientHandReceiver = targets -> {};

    public static void installClientHandReceiver(Consumer<HandTargetsPayload> receiver) {
        clientHandReceiver = receiver;
    }

    public static void installClientReceiver(Consumer<SnapshotPayload> receiver) {
        clientReceiver = receiver;
    }

    public static void installClientConfigReceiver(Consumer<ServerConfigPayload> receiver) {
        clientConfigReceiver = receiver;
    }

    public static void installClientSessionReceiver(Consumer<SessionPayload> receiver) {
        clientSessionReceiver = receiver;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // Payloads are required by default: NeoForge rejects connections missing
        // MineEdge on either side. Keep this registrar non-optional.
        var registrar = event.registrar("34");
        var incoming = registrar.executesOn(HandlerThread.NETWORK);
        incoming.playToServer(FullInputPayload.TYPE, FullInputPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                MovementRuntime.receive(player, payload);
            }
        });
        incoming.playToServer(InputChangePayload.TYPE, InputChangePayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                MovementRuntime.receive(player, payload);
            }
        });
        // Session transitions must stay ordered with vanilla login/respawn/teleport packets.
        // Network-thread publication can let the movement clock adopt the new epoch before
        // a queued teleport invalidates it, permanently rejecting that epoch's snapshots.
        registrar.playToClient(SessionPayload.TYPE, SessionPayload.CODEC,
                (payload, context) -> clientSessionReceiver.accept(payload));
        // Frequent snapshots only replace immutable mailbox entries; no game-thread task per snapshot.
        incoming.playToClient(SnapshotPayload.TYPE, SnapshotPayload.CODEC,
                (payload, context) -> clientReceiver.accept(payload));
        registrar.playToClient(HandTargetsPayload.TYPE, HandTargetsPayload.CODEC,
                (payload, context) -> clientHandReceiver.accept(payload));
        registrar.playBidirectional(ServerConfigPayload.TYPE, ServerConfigPayload.CODEC, new DirectionalPayloadHandler<>(
                (payload, context) -> clientConfigReceiver.accept(payload),
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        ServerConfigService.receive(player, payload);
                    }
                }));
    }

    private MovementNetwork() {}
}
