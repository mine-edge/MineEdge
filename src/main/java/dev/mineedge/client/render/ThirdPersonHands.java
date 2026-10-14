package dev.mineedge.client.render;

import dev.mineedge.client.movement.ClientMovement;
import dev.mineedge.client.config.MineEdgeClientConfig;
import dev.mineedge.client.movement.SimulationInterpolation;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.movement.FootTargets;
import dev.mineedge.movement.HandTargets;
import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.WallRunPose;
import dev.mineedge.movement.RollPose;
import dev.mineedge.network.HandTargetsPayload;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Per-player render-only traversal, slide, wallrun and fall-roll samples. */
public final class ThirdPersonHands {
    private static final Map<Entity, Presentation> PLAYERS = new WeakHashMap<>();
    private static long frame;
    private static boolean paused;

    private static final class Presentation {
        final SimulationInterpolation left = new SimulationInterpolation();
        final SimulationInterpolation right = new SimulationInterpolation();
        final FootSupport footSupport = new FootSupport();
        final HandFacing handFacing = new HandFacing();
        final ThirdPersonLegs.Animation legs = new ThirdPersonLegs.Animation();
        final SlideAnimation slide = new SlideAnimation();
        final WallRunAnimation wallrun = new WallRunAnimation();
        final RollAnimation roll = new RollAnimation();
        HandTargets targets = HandTargets.EMPTY, shot = HandTargets.EMPTY;
        MovementVector feet;
        float leftWeight, rightWeight;
        long received, sampled;
        boolean relative, ledgeHanging;

        void record(HandTargets value, boolean hanging, long now) {
            targets = value;
            ledgeHanging = hanging;
            received = now;
            if (value.active()) {
                left.record(value.left(), now);
                right.record(value.right(), now);
            }
        }

        boolean fresh() {
            long tickInterval = ClientMovement.INSTANCE.intervalNanos() * MovementRate.STEPS_PER_GAME_TICK;
            return !relative || frame - received < Math.max(1_000_000_000L, 2 * tickInterval);
        }

        HandTargets sample() {
            if (sampled == frame || paused && sampled != 0) return shot;
            double dt = sampled == 0 ? 1.0 / 60 : Math.clamp((frame - sampled) / 1.0E9, 0, 0.1);
            sampled = frame;
            double alpha = 1 - Math.exp(-dt / 0.065);
            boolean fresh = fresh();
            leftWeight += ((fresh ? targets.leftWeight() : 0) - leftWeight) * (float) alpha;
            rightWeight += ((fresh ? targets.rightWeight() : 0) - rightWeight) * (float) alpha;
            if (leftWeight < 0.001F && rightWeight < 0.001F) return shot = HandTargets.EMPTY;
            // Remote positions use vanilla's tick interpolation; local grips use the simulation interval.
            long interval = ClientMovement.INSTANCE.intervalNanos();
            long sampleTime = relative ? frame - interval * MovementRate.STEPS_PER_GAME_TICK + interval : frame;
            var l = left.position(sampleTime, interval);
            var r = right.position(sampleTime, interval);
            return shot = l == null || r == null ? HandTargets.EMPTY : new HandTargets(l, r, leftWeight, rightWeight);
        }
    }

    public static void frame(RenderFrameEvent.Pre event) {
        frame = System.nanoTime();
        paused = Minecraft.getInstance().isPaused();
    }

    public static void recordLocal(AbstractClientPlayer player, MovementState state, long now) {
        var entry = PLAYERS.computeIfAbsent(player, key -> new Presentation());
        if (entry.feet != null && state.position().subtract(entry.feet).length() > 2) {
            entry = new Presentation();
            PLAYERS.put(player, entry);
        }
        entry.feet = state.position();
        entry.record(HandTargets.sample(state), state.mode() == MovementState.Mode.LEDGE_HANG, now);
        entry.slide.record(state.mode() == MovementState.Mode.SLIDE || state.mode() == MovementState.Mode.STEEP_SLIDE,
                state.mode() == MovementState.Mode.STEEP_SLIDE ? state.surfaces().slopeYaw() : state.slideYaw(), now);
        entry.wallrun.record(WallRunPose.sample(state), now);
        entry.roll.record(RollPose.sample(state), now);
    }

    public static void resetLocal(AbstractClientPlayer player, MovementState state, long now) {
        PLAYERS.remove(player);
        recordLocal(player, state, now);
    }

    public static void receive(HandTargetsPayload payload) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var entity = mc.level.getEntity(payload.entityId());
        if (!(entity instanceof AbstractClientPlayer) || entity == mc.player || !entity.getUUID().equals(payload.playerId())) return;
        var entry = PLAYERS.computeIfAbsent(entity, key -> new Presentation());
        entry.relative = true;
        long now = System.nanoTime();
        entry.record(payload.targets(), payload.ledgeHanging(), now);
        entry.slide.record(payload.sliding(), payload.slideYaw(), now);
        entry.wallrun.record(payload.wallrun(), now);
        entry.roll.record(payload.roll(), now);
    }

    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { PLAYERS.clear(); }

    public static void clear(PlayerModel<?> model) {
        ((ClimbingCapePose) (Object) model).mineedge$capeWeight(0);
        restore(model.leftArm); restore(model.rightArm);
        restore(model.leftSleeve); restore(model.rightSleeve);
    }

    public static SlideAnimation.Shot slideShot(Entity entity) {
        var entry = animationEntry(entity);
        if (entry == null) return null;
        var shot = entry.slide.sample(frame, paused);
        return shot.weight() == 0 ? null : shot;
    }

    public static WallRunAnimation.Shot wallRunShot(Entity entity) {
        var entry = animationEntry(entity);
        if (entry == null) return null;
        var shot = entry.wallrun.sample(frame, paused);
        return shot.weight() == 0 ? null : shot;
    }

    public static RollAnimation.Shot rollShot(Entity entity) {
        var entry = animationEntry(entity);
        if (entry == null) return null;
        var shot = entry.roll.sample(frame, paused);
        if (shot != RollAnimation.Shot.NONE) {
            // Keep old climbing contacts fading while the roll owns the limbs.
            entry.sample();
            entry.legs.reset();
        }
        return shot == RollAnimation.Shot.NONE ? null : shot;
    }

    public static float rollProneWeight(Entity entity) {
        var entry = animationEntry(entity);
        if (entry == null) return 0;
        entry.roll.sample(frame, paused);
        return entry.roll.suppressesProne() ? 1 : 0;
    }

    private static Presentation animationEntry(Entity entity) {
        if (!MineEdgeClientConfig.THIRD_PERSON_ANIMATIONS.get()) return null;
        if (!(entity instanceof AbstractClientPlayer player) || !player.isAlive() || player.isSpectator()
                || player.isPassenger() || player.isInWater() || player.isInFluidType()
                || player.isFallFlying() || player.isSleeping() || player.isAutoSpinAttack()) return null;
        if (player == Minecraft.getInstance().player && ClientMovement.INSTANCE.presentationState() == null) {
            PLAYERS.remove(player);
            return null;
        }
        return PLAYERS.get(player);
    }

    public static void apply(AbstractClientPlayer player, PlayerModel<?> model, float partialTick,
                             Matrix4f worldToModel, Vec3 renderOffset) {
        if (!MineEdgeClientConfig.THIRD_PERSON_ANIMATIONS.get()) return;
        if (!player.isAlive() || player.isSpectator() || player.isPassenger() || player.isVisuallySwimming()
                || player.isFallFlying() || player.isSleeping() || player.isAutoSpinAttack()) return;
        var mc = Minecraft.getInstance();
        if (player == mc.player && ClientMovement.INSTANCE.presentationState() == null) {
            PLAYERS.remove(player);
            return;
        }
        var entry = PLAYERS.get(player);
        if (entry == null) return;
        var targets = entry.sample();
        if (!targets.active()) {
            entry.legs.reset();
            return;
        }
        ((ClimbingCapePose) (Object) model).mineedge$capeWeight(Math.max(targets.leftWeight(), targets.rightWeight()));
        Vec3 feet = ClientMovement.INSTANCE.renderPosition(player);
        if (feet == null) feet = player.getPosition(partialTick);
        var origin = MinecraftMotionAdapter.core(feet.add(renderOffset));
        if (entry.relative) targets = targets.translate(MinecraftMotionAdapter.core(feet));
        var modelToWorld = new Matrix4f(worldToModel).invert();
        var weights = entry.handFacing.sample(targets, MinecraftMotionAdapter.core(feet),
                Mth.rotLerp(partialTick, player.yRotO, player.getYRot()),
                shoulder(model.leftArm, origin, modelToWorld), shoulder(model.rightArm, origin, modelToWorld));
        pose(model.leftArm, local(targets.left(), origin, worldToModel), targets.leftWeight(), weights.left());
        pose(model.rightArm, local(targets.right(), origin, worldToModel), targets.rightWeight(), weights.right());
        ((IkModelPart) (Object) model.leftSleeve).mineedge$savePose();
        ((IkModelPart) (Object) model.rightSleeve).mineedge$savePose();
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
        var footTargets = FootTargets.fromGrips(targets, entry.ledgeHanging);
        if (entry.targets.active() && entry.fresh()) {
            var support = entry.footSupport.sample(player, footTargets);
            ThirdPersonLegs.apply(model, footTargets, origin, worldToModel, support.left(), support.right(),
                    entry.legs, frame, paused);
        } else {
            ThirdPersonLegs.release(model, footTargets, entry.legs, frame, paused);
        }
    }

    private static Vector3f local(MovementVector target, MovementVector origin, Matrix4f matrix) {
        var delta = target.subtract(origin);
        return matrix.transformPosition(new Vector3f((float) delta.x(), (float) delta.y(), (float) delta.z())).mul(16);
    }

    private static MovementVector shoulder(ModelPart arm, MovementVector origin, Matrix4f modelToWorld) {
        float centerX = ((IkModelPart) (Object) arm).mineedge$limbCenter();
        var point = new Quaternionf().rotationZYX(arm.zRot, arm.yRot, arm.xRot)
                .transform(new Vector3f(centerX * arm.xScale, -2 * arm.yScale, 0))
                .add(arm.x, arm.y, arm.z).div(16);
        modelToWorld.transformPosition(point);
        return origin.add(new MovementVector(point.x, point.y, point.z));
    }

    private static void pose(ModelPart arm, Vector3f target, float gripWeight, float facingWeight) {
        if (gripWeight < 0.001F || arm.yScale <= 0.0001F) return;
        var vanilla = new Quaternionf().rotationZYX(arm.zRot, arm.yRot, arm.xRot);
        var extension = (IkModelPart) (Object) arm;
        float centerX = extension.mineedge$limbCenter();
        var solution = solveFacingPose(new Vector3f(arm.x, arm.y, arm.z), vanilla,
                new Vector3f(arm.xScale, arm.yScale, arm.zScale), centerX, target, gripWeight, facingWeight);
        if (solution == null) return;
        extension.mineedge$savePose();
        RigidLimbIk.apply(arm, solution);
    }

    static RigidLimbIk.Pose solveFacingPose(Vector3f pivot, Quaternionf vanilla, Vector3f scale, float centerX,
                                          Vector3f target, float gripWeight, float facingWeight) {
        if (gripWeight < 0.001F) return null;
        float keep = Math.clamp(facingWeight / gripWeight, 0, 1);
        // A released arm belongs at the normal shoulder, not at the translated grip IK origin.
        // Keep that shoulder fixed throughout the rotation-only release.
        if (keep == 0) return new RigidLimbIk.Pose(new Vector3f(pivot), new Quaternionf(vanilla));
        var grip = solvePose(pivot, vanilla, scale, centerX, target, gripWeight);
        if (grip == null) return null;
        return new RigidLimbIk.Pose(keep < 1 ? new Vector3f(pivot) : grip.pivot(),
                new Quaternionf(vanilla).slerp(grip.rotation(), keep));
    }

    static RigidLimbIk.Pose solvePose(Vector3f pivot, Quaternionf vanilla, Vector3f scale, float centerX,
                              Vector3f target, float weight) {
        var shoulder = vanilla.transform(new Vector3f(centerX * scale.x, -2 * scale.y, 0)).add(pivot);
        // Let go of distant grips rather than pulling an arm arbitrarily far from the body.
        weight *= (float) Math.clamp((12 * scale.y + 4 - target.distance(shoulder)) / 4, 0, 1);
        return RigidLimbIk.solve(pivot, vanilla, scale, centerX, -2, 10, target, weight);
    }

    private static void restore(ModelPart part) { ((IkModelPart) (Object) part).mineedge$restorePose(); }

    private ThirdPersonHands() {}
}
