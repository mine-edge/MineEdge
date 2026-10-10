package dev.mineedge.integration;

import dev.mineedge.MineEdge;
import dev.mineedge.config.MineEdgeServerConfig;
import dev.mineedge.integration.mixin.ServerConnectionAccessor;
import dev.mineedge.movement.HandTargets;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.OffThreadMovement;
import dev.mineedge.movement.SimulationLoop;
import dev.mineedge.movement.WallRunPose;
import dev.mineedge.network.CommandInbox;
import dev.mineedge.network.FullInputPayload;
import dev.mineedge.network.HandTargetsPayload;
import dev.mineedge.network.InputChangePayload;
import dev.mineedge.network.InputMailbox;
import dev.mineedge.network.InputPayload;
import dev.mineedge.network.SessionPayload;
import dev.mineedge.network.SnapshotPayload;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MovementRuntime {
    public interface ClientBridge {
        boolean owns(Player player);
        void reset();
        void pose(Player player);
    }

    private static final MovementController CONTROLLER = new MovementController();
    private static final ConcurrentHashMap<UUID, InputMailbox> INPUTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, MovementSession> SIMULATIONS = new ConcurrentHashMap<>();
    private static ClientBridge client;

    public static void installClient(ClientBridge bridge) {
        client = bridge;
    }

    public static void clientTeleport() {
        if (client != null) {
            client.reset();
        }
    }

    public static boolean owns(Player player) {
        if (player.level().isClientSide()) {
            return client != null && client.owns(player);
        }
        return player instanceof ServerPlayer serverPlayer && session(serverPlayer).active
                && MineEdgeServerConfig.enabledFor(serverPlayer.gameMode.getGameModeForPlayer())
                && MovementEligibility.supported(player) && !awaitingTeleport(serverPlayer);
    }

    public static boolean travel(Player player) {
        // aiStep's native damping is suppressed at its source. Rewriting velocity here
        // would erase impulses added by other mods between the tick boundary and travel.
        return owns(player);
    }

    public static boolean updatePose(Player player) {
        if (!owns(player)) {
            return false;
        }
        if (player.level().isClientSide()) {
            client.pose(player);
        } else {
            MovementPoses.apply(player, session((ServerPlayer) player).state.body());
        }
        return true;
    }

    public static void beforeTick(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MovementSession session = session(player);
        // Automation/NPC fake players have no MineEdge input client. Leave them on
        // native movement unless an integration explicitly activated their session.
        if (player instanceof FakePlayer && !session.active) return;
        boolean eligible = MineEdgeServerConfig.enabledFor(player.gameMode.getGameModeForPlayer()) && MovementEligibility.supported(player)
                && !awaitingTeleport(player);
        MovementTuning tuning = effectiveTuning(player);
        if (session.active && (!eligible || !tuning.equals(session.tuning) || session.resetRequested)) {
            resetSession(player, session);
        }
        if (eligible && !session.active) {
            boolean resume = session.canResume(player.level().dimension().location(), MinecraftMotionAdapter.core(player.position()));
            if (session.resumePending && !resume) session.reset();
            session.active = true;
            session.tuning = tuning;
            session.dimension = player.level().dimension().location();
            session.resumePending = false;
            if (resume) {
                MinecraftMotionAdapter.apply(player, session.state);
            } else {
                session.state = MinecraftMotionAdapter.capture(player);
            }
            session.writtenVelocity = session.state.velocity().scale(1.0 / 20);
            session.inbox = new CommandInbox(player.getYRot(), player.getXRot(),
                    player.connection.getConnection().isMemoryConnection());
            session.input = MovementInput.idle(0, player.getYRot(), player.getXRot());
        } else if (session.active) {
            // Import knockback/push changes before LivingEntity.aiStep's incidental damping.
            MovementVector impulse = MinecraftMotionAdapter.core(player.getDeltaMovement()).subtract(session.writtenVelocity);
            session.state = session.state.withVelocity(session.state.velocity().add(impulse.scale(20)));
            session.writtenVelocity = MinecraftMotionAdapter.core(player.getDeltaMovement());
        }
        if (session.active && session.simulation == null) {
            session.simulation = new SimulationLoop(player.getServer(), () -> simulate(player, session),
                    player.getServer().tickRateManager().tickrate());
            SIMULATIONS.put(player.getUUID(), session);
        }
        if (session.simulation != null) {
            session.simulation.setTickRate(player.getServer().tickRateManager().tickrate());
        }
    }

    private static void simulate(ServerPlayer player, MovementSession session) {
        if (player.getServer().isPaused()) return;
        if (!player.connection.getConnection().isConnected()) {
            SIMULATIONS.remove(player.getUUID());
            session.suspend();
            return;
        }
        if (!owns(player)) {
            resetSession(player, session);
            return;
        }
        try {
            var mailbox = INPUTS.get(player.getUUID());
            if (mailbox != null) {
                for (var payload : mailbox.drain()) {
                    if (payload instanceof FullInputPayload full) applyInput(player, full);
                    else if (payload instanceof InputChangePayload change) applyInput(player, change);
                }
            }
            if (session.resetRequested) {
                resetSession(player, session);
                return;
            }
            var command = session.inbox.consumeSimulation(session.simulationStep, session.tuning.staleInputTicks());
            if (command == null) return;
            session.simulationStep++;
            MovementVector currentVelocity = MinecraftMotionAdapter.core(player.getDeltaMovement());
            var impulse = currentVelocity.subtract(session.writtenVelocity).scale(20);
            session.state = session.state.withVelocity(session.state.velocity().add(impulse));
            session.input = MovementSprint.validate(player, command);
            player.setYRot(session.input.yaw());
            player.setXRot(session.input.pitch());
            MovementSprint.apply(player, session.input, session.state);
            var result = OffThreadMovement.step(CONTROLLER, session.state, session.input, session.tuning,
                    MinecraftMotionAdapter.live(player));
            session.state = result.state();
            MinecraftMotionAdapter.apply(player, session.state);
            for (var event : result.events()) serverEvent(player, event);
            session.writtenVelocity = session.state.velocity().scale(1.0 / 20);
            player.setKnownMovement(MinecraftMotionAdapter.minecraft(session.state.position())
                    .subtract(player.xo, player.yo, player.zo));
            player.connection.resetPosition();
            player.serverLevel().getChunkSource().move(player);
        } catch (RuntimeException exception) {
            MineEdge.LOGGER.error("Movement simulation failed for {}", player.getUUID(), exception);
            resetSession(player, session);
        }
    }

    public static void afterTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MovementSession session = session(player);
        if (player instanceof FakePlayer && !session.active) return;
        if (session.active && !owns(player)) {
            resetSession(player, session);
        }
        var pose = observerPose(player, session);
        if (pose.active() || session.posePublished) {
            PacketDistributor.sendToPlayersTrackingEntity(player, pose);
            session.posePublished = pose.active();
        }
        if (session.active) {
            // Connection.tick() restores firstGoodXYZ after doTick(). Preserve our authoritative result.
            player.connection.resetPosition();
            player.serverLevel().getChunkSource().move(player);
        }
        MovementTuning snapshotTuning = session.tuning == null ? MineEdgeServerConfig.tuning() : session.tuning;
        long tick = player.level().getGameTime();
        boolean enabled = MineEdgeServerConfig.enabledFor(player.gameMode.getGameModeForPlayer());
        var metadata = new SessionPayload(player.level().dimension().location(), session.epoch, tick,
                enabled, session.active, snapshotTuning);
        if (session.snapshots.sessionDue(metadata)) {
            PacketDistributor.sendToPlayer(player, metadata);
        }
        if (session.snapshots.due(tick, session.epoch, enabled,
                session.active, session.state, player.getPose(), snapshotTuning, player.level().dimension().location(),
                MineEdgeServerConfig.SNAPSHOT_INTERVAL_TICKS.get())) {
            PacketDistributor.sendToPlayer(player, SnapshotPayload.capture(player, session));
        }
    }

    /** Network-thread receipt never reads or mutates the player's movement attachment. */
    public static void receive(ServerPlayer player, InputPayload payload) {
        INPUTS.computeIfAbsent(player.getUUID(), id -> new InputMailbox()).offer(payload);
    }

    public static void startTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer player) {
            var pose = observerPose(player, session(player));
            if (pose.active()) PacketDistributor.sendToPlayer(viewer, pose);
        }
    }

    private static HandTargetsPayload observerPose(ServerPlayer player, MovementSession session) {
        var targets = session.active ? HandTargets.sample(session.state).translate(session.state.position().scale(-1)) : HandTargets.EMPTY;
        boolean sliding = session.active && (session.state.mode() == MovementState.Mode.SLIDE || session.state.mode() == MovementState.Mode.STEEP_SLIDE);
        float yaw = !sliding ? 0 : session.state.mode() == MovementState.Mode.STEEP_SLIDE ? session.state.surfaces().slopeYaw() : session.state.slideYaw();
        return new HandTargetsPayload(player.getId(), player.getUUID(), targets,
                session.active && session.state.mode() == MovementState.Mode.LEDGE_HANG, sliding, yaw,
                session.active ? WallRunPose.sample(session.state) : WallRunPose.EMPTY);
    }

    private static void applyInput(ServerPlayer player, FullInputPayload payload) {
        MovementSession session = session(player);
        if (session.active && payload.epoch() == session.epoch && payload.resetRequested()) {
            // Defer reset to the tick boundary. A reset request must not temporarily
            // hand supported players back to client-owned vanilla position packets.
            session.resetRequested = true;
            return;
        }
        if (session.active && payload.epoch() == session.epoch
                && session.inbox.offer(payload, session.simulationStep)
                && (payload.input().forward() != 0 || payload.input().strafe() != 0 || payload.input().jumpPressed())) {
            player.resetLastActionTime();
        }
    }

    private static void applyInput(ServerPlayer player, InputChangePayload payload) {
        MovementSession session = session(player);
        if (session.active && payload.epoch() == session.epoch
                && session.inbox.offer(payload, session.simulationStep)
                && (session.inbox.latest().forward() != 0 || session.inbox.latest().strafe() != 0
                    || (payload.buttons() & InputChangePayload.JUMP_PRESSED) != 0)) {
            player.resetLastActionTime();
        }
    }

    public static void reset(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            resetSession(serverPlayer, session(serverPlayer));
        }
    }

    private static void resetSession(ServerPlayer player, MovementSession session) {
        SIMULATIONS.remove(player.getUUID());
        session.reset();
    }

    /** Login teleports acknowledge the saved position; actual relocations invalidate saved paths. */
    public static void teleport(ServerPlayer player, MovementVector destination) {
        if (!session(player).canResume(player.level().dimension().location(), destination)) reset(player);
    }

    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player) {
            reset(player);
        }
    }

    public static void onClone(PlayerEvent.Clone event) {
        reset(event.getOriginal());
        reset(event.getEntity());
    }

    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        reset(event.getEntity());
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SIMULATIONS.remove(player.getUUID());
            session(player).suspend();
        }
        INPUTS.remove(event.getEntity().getUUID());
    }

    public static void stopping(ServerStoppingEvent event) {
        suspendSessions();
    }

    public static void stopped(ServerStoppedEvent event) {
        suspendSessions();
    }

    private static void suspendSessions() {
        SIMULATIONS.values().forEach(MovementSession::suspend);
        SIMULATIONS.clear();
        INPUTS.clear();
    }

    public static MovementSession session(ServerPlayer player) {
        return player.getData(MovementAttachments.SESSION);
    }

    private static boolean awaitingTeleport(ServerPlayer player) {
        return ((ServerConnectionAccessor) player.connection).mineedge$awaitingPosition() != null;
    }

    public static MovementTuning effectiveTuning(ServerPlayer player) {
        MovementTuning base = MineEdgeServerConfig.tuning();
        double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED) / 0.1;
        // MineEdge's curve already supplies sprint speed. Keep vanilla's sprint flag for
        // statistics/exhaustion, but exclude its multiplicative modifier from base tuning.
        var sprintModifier = player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(
                ResourceLocation.withDefaultNamespace("sprinting"));
        if (sprintModifier != null) {
            speed /= 1 + sprintModifier.amount();
        }
        // Surface factors belong to the jump contact, not session tuning. Crossing a
        // honey edge mid-jump must not reset the command inbox and prediction epoch.
        double jump = ((float) player.getAttributeValue(Attributes.JUMP_STRENGTH) + player.getJumpBoostPower()) / 0.42;
        double gravity = player.getGravity() / 0.08;
        return new MovementTuning(Math.clamp(base.walkSpeed() * speed, 0, 1000),
                Math.clamp(base.jumpSpeed() * jump, 0, 1000), Math.clamp(base.gravity() * gravity, 0, 1000),
                base.groundAcceleration(), base.airAcceleration(), base.staleInputTicks(),
                Math.clamp(base.crouchSpeedModifier() * player.getAttributeValue(Attributes.SNEAKING_SPEED) / 0.3, 0, 1000),
                base.rollDamageReduction(), base.runSpeedMultiplier(), base.fallDamageMultiplier(), base.minimumFallHeight(), base.features());
    }

    private static void serverEvent(ServerPlayer player, MovementEvent event) {
        if (event == MovementEvent.JUMP) {
            player.awardStat(Stats.JUMP);
            player.causeFoodExhaustion(player.isSprinting() ? 0.2F : 0.05F);
            CommonHooks.onLivingJump(player);
        }
    }

    private MovementRuntime() {}
}
