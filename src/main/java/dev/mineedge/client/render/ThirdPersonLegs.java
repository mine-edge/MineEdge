package dev.mineedge.client.render;

import dev.mineedge.movement.FootTargets;
import dev.mineedge.movement.MovementVector;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Rigid traversal legs; unsupported feet hang instead of bracing against an imaginary wall. */
public final class ThirdPersonLegs {
    private static final float MAX_HIP_SHIFT_PIXELS = 0;

    public static final class Animation {
        private final LegAnimation left = new LegAnimation();
        private final LegAnimation right = new LegAnimation();

        public void reset() { left.reset(); right.reset(); }
    }

    public static void clear(PlayerModel<?> model) {
        restore(model.leftLeg); restore(model.rightLeg);
        restore(model.leftPants); restore(model.rightPants);
    }

    public static void apply(PlayerModel<?> model, FootTargets feet, MovementVector origin, Matrix4f worldToModel,
                             boolean leftSupported, boolean rightSupported, Animation animation, long frame, boolean paused) {
        if (!feet.active()) return;
        pose(model.leftLeg, model.leftPants, leftSupported ? local(feet.left(), origin, worldToModel) : null,
                feet.leftWeight(), animation.left, frame, paused, false);
        pose(model.rightLeg, model.rightPants, rightSupported ? local(feet.right(), origin, worldToModel) : null,
                feet.rightWeight(), animation.right, frame, paused, false);
    }

    public static void release(PlayerModel<?> model, FootTargets feet, Animation animation, long frame, boolean paused) {
        if (!feet.active()) return;
        pose(model.leftLeg, model.leftPants, null, feet.leftWeight(), animation.left, frame, paused, true);
        pose(model.rightLeg, model.rightPants, null, feet.rightWeight(), animation.right, frame, paused, true);
    }

    private static Vector3f local(MovementVector target, MovementVector origin, Matrix4f matrix) {
        var delta = target.subtract(origin);
        return matrix.transformPosition(new Vector3f((float) delta.x(), (float) delta.y(), (float) delta.z())).mul(16);
    }

    private static void pose(ModelPart leg, ModelPart pants, Vector3f target, float weight,
                              LegAnimation animation, long frame, boolean paused, boolean released) {
        if (weight < 0.001F || leg.yScale <= 0.0001F) return;
        var extension = (IkModelPart) (Object) leg;
        var pivot = new Vector3f(leg.x, leg.y, leg.z);
        var vanilla = new Quaternionf().rotationZYX(leg.zRot, leg.yRot, leg.xRot);
        var solution = released ? null : target == null ? hangingPose(pivot, vanilla, 1)
                : solvePose(pivot, vanilla, new Vector3f(leg.xScale, leg.yScale, leg.zScale),
                        extension.mineedge$limbCenter(), target, 1);
        if (!released && solution == null) return;
        solution = animation.sample(pivot, vanilla, solution, MAX_HIP_SHIFT_PIXELS * leg.yScale, frame, paused);
        // Fade the smoothed traversal pose against this frame's vanilla/mod animation,
        // so returning to running does not inherit a delayed walking cycle.
        solution = blendPose(solution, pivot, vanilla, weight);
        extension.mineedge$savePose();
        ((IkModelPart) (Object) pants).mineedge$savePose();
        RigidLimbIk.apply(leg, solution);
        pants.copyFrom(leg);
    }

    static RigidLimbIk.Pose blendPose(RigidLimbIk.Pose traversal, Vector3f pivot, Quaternionf vanilla, float weight) {
        if (weight <= 0) return new RigidLimbIk.Pose(new Vector3f(pivot), new Quaternionf(vanilla));
        if (weight >= 1) return traversal;
        return new RigidLimbIk.Pose(new Vector3f(pivot).lerp(traversal.pivot(), weight),
                new Quaternionf(vanilla).slerp(traversal.rotation(), weight));
    }

    static RigidLimbIk.Pose hangingPose(Vector3f pivot, Quaternionf vanilla, float weight) {
        if (weight < 0.001F) return null;
        return new RigidLimbIk.Pose(new Vector3f(pivot), new Quaternionf(vanilla).slerp(new Quaternionf(), weight));
    }

    static RigidLimbIk.Pose solvePose(Vector3f pivot, Quaternionf vanilla, Vector3f scale, float centerX,
                                      Vector3f target, float weight) {
        var solution = RigidLimbIk.solve(pivot, vanilla, scale, centerX, 0, 12, target, weight);
        if (solution == null) return null;
        // A rigid leg cannot reach every climbing contact. Rotate toward the foot,
        // but never translate the hip away from the body to force that contact.
        return new RigidLimbIk.Pose(new Vector3f(pivot), solution.rotation());
    }

    private static void restore(ModelPart part) { ((IkModelPart) (Object) part).mineedge$restorePose(); }

    private ThirdPersonLegs() {}
}
