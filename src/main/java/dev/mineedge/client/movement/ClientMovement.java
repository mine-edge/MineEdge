package dev.mineedge.client.movement;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mineedge.MineEdge;
import dev.mineedge.client.camera.MovementCamera;
import dev.mineedge.client.input.InputLatch;
import dev.mineedge.client.input.MovementKeys;
import dev.mineedge.client.input.SprintLatch;
import dev.mineedge.client.render.ThirdPersonHands;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementEligibility;
import dev.mineedge.integration.MovementPoses;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.integration.MovementSprint;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.OffThreadMovement;
import dev.mineedge.movement.SimulationLoop;
import dev.mineedge.network.FullInputPayload;
import dev.mineedge.network.InputOutbox;
import dev.mineedge.network.SessionPayload;
import dev.mineedge.network.SnapshotMailbox;
import dev.mineedge.network.SnapshotPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ClientMovement implements MovementRuntime.ClientBridge {
    public static final ClientMovement INSTANCE = new ClientMovement();
    private final MovementController controller = new MovementController();
    private final CommandHistory history = new CommandHistory();
    private final SnapshotMailbox incoming = new SnapshotMailbox();
    private final InputOutbox outgoing = new InputOutbox();
    private final SimulationInterpolation interpolation = new SimulationInterpolation();
    private final HandPresentation hands = new HandPresentation();
    private final WalkingPresentation walking = new WalkingPresentation();
    private final CapePresentation cape = new CapePresentation();
    private HandPresentation.Shot handShot;
    private WalkingPresentation.Shot walkingShot;
    private MovementVector capeShot;
    private MovementVector renderPosition;
    private LocalPlayer owner;
    private ResourceLocation dimension;
    private MovementState state;
    private MovementTuning tuning = MovementTuning.DEFAULT;
    private final PredictionImpulses impulses = new PredictionImpulses();
    private MovementInput input = MovementInput.idle(0, 0, 0);
    private long epoch;
    private long tuningRevision;
    private long minimumEpoch;
    private long sequence;
    private long acknowledged;
    private long serverTick = Long.MIN_VALUE;
    private long sessionTick = Long.MIN_VALUE;
    private SimulationLoop simulation;
    private long intervalNanos = MovementRate.INTERVAL_NANOS;
    private boolean active;
    private boolean enabled;
    private final InputLatch buttons = new InputLatch();
    private final InputLatch networkButtons = new InputLatch();
    private final SprintLatch sprint = new SprintLatch();
    private boolean suspended;
    private boolean awaitingReset;
    private double correction;

    public void key(InputEvent.Key event) {
        if (acceptPress(event.getAction()) && MovementKeys.TURN.matches(event.getKey(), event.getScanCode())
                && MovementKeys.TURN.isDown()) {
            pressTurn();
        }
    }

    public void mouse(InputEvent.MouseButton.Post event) {
        if (acceptPress(event.getAction()) && MovementKeys.TURN.matchesMouse(event.getButton())
                && MovementKeys.TURN.isDown()) {
            pressTurn();
        }
    }

    private void pressTurn() {
        buttons.pressTurn();
        networkButtons.pressTurn();
    }

    private boolean acceptPress(int action) {
        Minecraft minecraft = Minecraft.getInstance();
        return action == InputConstants.PRESS && active && !buttons.interrupted()
                && minecraft.screen == null && minecraft.isWindowActive();
    }

    public void clientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        float tickRate = minecraft.level == null ? MovementRate.GAME_TICK_HZ : minecraft.level.tickRateManager().tickrate();
        intervalNanos = MovementRate.intervalNanos(tickRate);
        if (simulation != null) simulation.setTickRate(tickRate);
        if (minecraft.screen != null || !minecraft.isWindowActive()) {
            buttons.interrupt();
            networkButtons.interrupt();
            sprint.update(false);
        }
    }

    /** Native and mod sprint decisions, excluding our simulation/presentation writes. */
    @Override
    public void sprintChanged(Player player, boolean sprinting) {
        if (player == owner && active) sprint.update(sprinting);
    }

    public void beginNativeSprint(LocalPlayer player) {
        if (!owns(player)) return;
        // Vanilla continuation must see its last approved decision, not a flag
        // cleared by a slide/crouch pose or overwritten by entity metadata.
        // Keep the entry tick's intent for sliding; held crouch stops it next tick.
        if (player.input.shiftKeyDown || state.mode() == MovementState.Mode.HARD_LANDING) sprint.update(false);
        boolean accepted = MovementSprint.setSprinting(player, sprint.requested());
        if (!accepted) sprint.update(false);
    }

    public void capture(MovementInputUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getEntity() != minecraft.player) {
            return;
        }
        LocalPlayer player = minecraft.player;
        if (owner != player) {
            invalidate();
            owner = player;
        }
        MovementCamera.restrictLook();
        var keys = event.getInput();
        boolean blocked = minecraft.screen != null || !minecraft.isWindowActive();
        if (blocked) {
            keys.jumping = false;
            keys.shiftKeyDown = false;
            keys.forwardImpulse = 0;
            keys.leftImpulse = 0;
        }
        if (active && sequence - acknowledged > CommandHistory.CAPACITY) {
            requestReset();
        }
    }

    public void renderFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player != null && owns(player) && !suspended) {
            // All camera/model passes in this frame share exactly the same sample.
            long now = System.nanoTime();
            if (!minecraft.isPaused() || renderPosition == null) {
                renderPosition = interpolation.position(now, intervalNanos);
                walkingShot = walking.sample(now, intervalNanos);
                capeShot = cape.sample(now, intervalNanos);
            }
            handShot = hands.update(now, player.getXRot(), player.getYRot(),
                    state.velocity().horizontalSpeed(), state.grounded(), minecraft.isPaused(), intervalNanos);
        } else {
            renderPosition = null;
            handShot = null;
            walkingShot = null;
            capeShot = null;
        }
        if (player != null && player == owner && active && !player.connection.getConnection().isMemoryConnection()) {
            publishInput(player, sequence + 1);
        }
    }

    private void publishInput(LocalPlayer player, long commandSequence) {
        if (awaitingReset) return;
        var sample = sampleInput(player, commandSequence, networkButtons);
        var connection = player.connection;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || !minecraft.isWindowActive()) outgoing.suppressEdges();
        outgoing.submit(epoch, sample, payload -> {
            if (connection.getConnection().isConnected()) {
                connection.send(new ServerboundCustomPayloadPacket(payload));
            }
        });
    }

    private MovementInput sampleInput(LocalPlayer player, long commandSequence, InputLatch latch) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean blocked = minecraft.screen != null || !minecraft.isWindowActive();
        var keys = player.input;
        // Consume the completed vanilla input tick, including other mods' event edits
        // and item-use slowdown. Reticking KeyboardInput or synthesizing jump/crouch
        // edges from raw keys here would bypass an input mod's suppression/remapping.
        float forward = keys.forwardImpulse;
        float strafe = keys.leftImpulse;
        float rawStrafe = Math.signum(strafe);
        boolean jump = keys.jumping;
        boolean crouch = keys.shiftKeyDown;
        var edges = latch.capture(jump, crouch, MovementKeys.TURN.isDown(), blocked);
        boolean sprintHeld = sprint.capture(keys.hasForwardImpulse()
                && (state == null || state.mode() != MovementState.Mode.HARD_LANDING),
                MovementSprint.allowed(player), blocked);
        return new MovementInput(commandSequence, blocked ? 0 : forward, blocked ? 0 : strafe,
                blocked ? 0 : rawStrafe, !blocked && jump, edges.jump(), !blocked && crouch, edges.crouch(),
                sprintHeld,
                player.getYRot(), player.getXRot(), edges.turn());
    }

    public void beforeTick(PlayerTickEvent.Pre event) {
        if (event.getEntity() == owner && owns(owner) && !suspended) {
            importImpulse(owner);
        }
    }

    private void importImpulse(LocalPlayer player) {
        var impulse = impulses.capture(MinecraftMotionAdapter.core(player.getDeltaMovement()));
        if (!impulse.equals(MovementVector.ZERO)) state = state.withVelocity(state.velocity().add(impulse));
    }

    @Override
    public boolean owns(Player player) {
        return player == owner && active && state != null && MovementEligibility.supported(player);
    }

    private void simulate() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isPaused()) return;
        var updates = incoming.latest();
        if (updates.session() != null) receiveSession(updates.session());
        if (updates.snapshot() != null) reconcile(updates.snapshot());
        LocalPlayer player = minecraft.player;
        if (player == null || !owns(player) || suspended) return;
        MovementCamera.restrictLook();
        importImpulse(player);
        var requested = sampleInput(player, ++sequence, buttons);
        input = MovementSprint.apply(player, requested, state);
        if (requested.sprintHeld() && !input.sprintHeld()) sprint.update(false);
        if (suspended || !history.add(input, impulses.pending())) {
            suspended = true;
            history.clear();
            requestReset();
            return;
        }
        if (player.connection.getConnection().isMemoryConnection()) {
            // Send exactly the command being predicted, including its latched edges.
            // Local transport needs neither render-frame sampling nor silent-input compression.
            player.connection.send(new ServerboundCustomPayloadPacket(new FullInputPayload(epoch, input)));
        } else {
            publishInput(player, sequence);
            var dispatch = outgoing.poll(System.nanoTime(), intervalNanos);
            if (dispatch != null) dispatch.send();
        }
        impulses.consume();
        var previous = state;
        var before = MinecraftMotionAdapter.core(player.position());
        var result = OffThreadMovement.step(controller, state, input, tuning, MinecraftMotionAdapter.live(player));
        state = result.state();
        MinecraftMotionAdapter.apply(player, state);
        long now = System.nanoTime();
        interpolation.record(state.position(), now);
        cape.step(state.position(), now);
        ThirdPersonHands.recordLocal(player, state, now);
        walking.step(player.walkAnimation, state.position().subtract(before), now);
        hands.record(player.walkDist, now);
        history.recordPrediction(input.sequence(), state);
        MovementCamera.restrictLook();
        for (var event : result.events()) MovementCamera.event(event, state.landing().impact());
        MovementSounds.movement(previous, state);
        impulses.written(state.velocity());
    }

    @Override
    public void pose(Player player) {
        if (state != null) {
            MovementPoses.apply(player, state.body());
        }
    }

    public void receive(SnapshotPayload snapshot) {
        incoming.offer(snapshot);
    }

    public void receive(SessionPayload session) {
        incoming.offer(session);
    }

    private void receiveSession(SessionPayload session) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !session.dimension().equals(player.level().dimension().location())
                || session.epoch() < minimumEpoch || session.epoch() < epoch
                || owner == player && session.epoch() == epoch && session.serverTick() <= sessionTick) {
            return;
        }
        boolean reset = owner != player || epoch != session.epoch() || active != session.active();
        owner = player;
        dimension = session.dimension();
        enabled = session.enabled();
        if (reset) {
            MovementCamera.reset();
            MovementSounds.reset();
            history.clear();
            impulses.clear();
            state = null;
            clearPresentation();
            sequence = acknowledged = 0;
            outgoing.clear();
            serverTick = Long.MIN_VALUE;
            buttons.resume(player.input.jumping, player.input.shiftKeyDown);
            networkButtons.resume(player.input.jumping, player.input.shiftKeyDown);
            sprint.update(false);
            awaitingReset = false;
            suspended = false;
        } else if (tuningRevision != session.tuningRevision()) {
            // The next matching baseline must replay pending commands with the new
            // authoritative tuning, even if its acknowledged state still matches.
            history.invalidatePredictions();
        }
        epoch = session.epoch();
        tuningRevision = session.tuningRevision();
        sessionTick = session.serverTick();
        active = session.active();
        tuning = session.tuning();
        if (simulation == null) startSimulation();
        if (!active) {
            state = null;
            clearPresentation();
            serverTick = session.serverTick();
        }
    }

    private void reconcile(SnapshotPayload snapshot) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || owner != player || !active || snapshot.epoch() != epoch
                // Snapshots arrive on the network thread; tuning metadata is ordered
                // on the game thread. Retain a newer baseline until its metadata arrives.
                || snapshot.tuningRevision() != tuningRevision
                || !player.level().dimension().location().equals(dimension)
                || snapshot.epoch() < minimumEpoch || snapshot.serverTick() <= serverTick) return;
        boolean baseline = state == null;
        serverTick = snapshot.serverTick();
        acknowledged = snapshot.acknowledged();
        // The server can simulate held input while the client stalls. Resume after its
        // acknowledged command instead of publishing already acknowledged sequences.
        sequence = Math.max(sequence, acknowledged);
        if (!MovementEligibility.supported(player)) {
            history.clear();
            impulses.clear();
            state = null;
            clearPresentation();
            suspended = false;
            return;
        }
        boolean matches = !baseline && !suspended && history.matches(acknowledged, snapshot.state())
                && snapshot.pose() == MovementPoses.pose(snapshot.state().body());
        history.acknowledge(acknowledged);
        if (matches) {
            correction = 0;
            return;
        }
        MovementVector before = MinecraftMotionAdapter.core(player.position());
        // A velocity packet/push can arrive before this tick imports it. Preserve that
        // delta across apply(), but do not import it into older replayed commands.
        MovementVector unimported = baseline || suspended ? MovementVector.ZERO
                : impulses.unimported(MinecraftMotionAdapter.core(player.getDeltaMovement()));
        player.setPose(snapshot.pose());
        state = history.replaySimulation(snapshot.state(), controller, tuning, MinecraftMotionAdapter.replay(player));
        if (!impulses.pending().equals(MovementVector.ZERO)) {
            state = state.withVelocity(state.velocity().add(impulses.pending()));
        }
        MovementVector correctionDelta = state.position().subtract(before);
        correction = correctionDelta.length();
        MinecraftMotionAdapter.apply(player, state);
        MovementCamera.restrictLook();
        if (baseline || correction > 1) {
            MovementCamera.reset();
            long now = System.nanoTime();
            interpolation.reset(state.position(), now);
            cape.reset(state.position(), now);
            capeShot = MovementVector.ZERO;
            ThirdPersonHands.resetLocal(player, state, now);
            hands.reset(player.walkDist, player.getXRot(), player.getYRot(), now);
            handShot = null;
            walking.reset(player.walkAnimation, now);
            walkingShot = null;
            renderPosition = state.position();
            // Large corrections do not interpolate the camera through unrelated geometry.
            player.xo = player.getX();
            player.yo = player.getY();
            player.zo = player.getZ();
            player.xOld = player.getX();
            player.yOld = player.getY();
            player.zOld = player.getZ();
        } else {
            MovementCamera.correct(correctionDelta);
            interpolation.translate(correctionDelta);
            cape.translate(correctionDelta);
            // Translate vanilla's interpolation endpoints with the physics correction;
            // the render-only offset handles the visual transition separately.
            player.xo += correctionDelta.x();
            player.yo += correctionDelta.y();
            player.zo += correctionDelta.z();
            player.xOld += correctionDelta.x();
            player.yOld += correctionDelta.y();
            player.zOld += correctionDelta.z();
        }
        var writtenVelocity = impulses.written(state.velocity());
        player.setDeltaMovement(MinecraftMotionAdapter.minecraft(writtenVelocity.add(unimported)));
        suspended = awaitingReset;
    }

    private void requestReset() {
        if (!awaitingReset) {
            awaitingReset = true;
            suspended = true;
            clearPresentation();
            history.clear();
            impulses.clear();
            outgoing.clear();
            PacketDistributor.sendToServer(new FullInputPayload(epoch, input, true));
        }
    }

    @Override
    public void reset() {
        minimumEpoch = epoch + 1;
        invalidate();
    }

    private void invalidate() {
        MovementCamera.reset();
        MovementSounds.reset();
        active = false;
        state = null;
        clearPresentation();
        history.clear();
        impulses.clear();
        input = MovementInput.idle(0, 0, 0);
        sequence = 0;
        outgoing.clear();
        buttons.resume(false, false);
        networkButtons.resume(false, false);
        sprint.update(false);
        suspended = false;
        awaitingReset = false;
    }

    private void clearPresentation() {
        correction = 0;
        interpolation.clear();
        renderPosition = null;
        hands.clear();
        handShot = null;
        walking.clear();
        walkingShot = null;
        cape.clear();
        capeShot = null;
    }

    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (simulation != null) simulation.close();
        simulation = null;
        incoming.clear();
        invalidate();
        owner = null;
        dimension = null;
        epoch = 0;
        minimumEpoch = 0;
        serverTick = Long.MIN_VALUE;
        sessionTick = Long.MIN_VALUE;
        enabled = false;
    }

    public void login(ClientPlayerNetworkEvent.LoggingIn event) {
        if (simulation == null) startSimulation();
    }

    public void clonePlayer(ClientPlayerNetworkEvent.Clone event) {
        reset();
        owner = event.getNewPlayer();
    }

    public String status() {
        return !enabled ? "disabled" : !active || state == null ? "vanilla" : suspended ? "waiting"
                : state.mode().name() + "/" + state.body().name();
    }

    public double speed() { return state == null ? 0 : state.velocity().horizontalSpeed(); }
    public MovementState presentationState() { return owner != null && owns(owner) && !suspended ? state : null; }
    /** Render-only position; entity coordinates, inputs and collision queries remain authoritative. */
    public Vec3 renderPosition(Entity entity) {
        return entity == owner && renderPosition != null && owns(owner) && !suspended
                ? MinecraftMotionAdapter.minecraft(renderPosition) : null;
    }

    public HandPresentation.Shot handPresentation(Entity entity) {
        return entity == owner && owns(owner) && !suspended ? handShot : null;
    }

    public WalkingPresentation.Shot walkingPresentation(Entity entity) {
        return entity == owner && owns(owner) && !suspended ? walkingShot : null;
    }

    public MovementVector capePresentation(Entity entity) {
        return entity == owner && owns(owner) && !suspended ? capeShot : null;
    }
    public MovementBody body() { return state == null ? MovementBody.STANDING : state.body(); }
    public long serverTick() { return serverTick; }
    public long sequence() { return sequence; }
    public long acknowledged() { return acknowledged; }
    public int pending() { return history.size(); }
    public double correction() { return correction; }
    public long intervalNanos() { return intervalNanos; }

    private void startSimulation() {
        Minecraft minecraft = Minecraft.getInstance();
        float tickRate = minecraft.level == null ? MovementRate.GAME_TICK_HZ : minecraft.level.tickRateManager().tickrate();
        intervalNanos = MovementRate.intervalNanos(tickRate);
        simulation = new SimulationLoop(minecraft, () -> {
            try {
                simulate();
            } catch (RuntimeException exception) {
                MineEdge.LOGGER.error("Client movement simulation failed", exception);
                if (active) requestReset();
            }
        }, tickRate);
    }

    private ClientMovement() { startSimulation(); }
}
