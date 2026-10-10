package dev.mineedge.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Connected tuck, forward somersault and recovery, without deforming the skin or armor. */
public final class ThirdPersonRoll {
    public static Matrix4f apply(AbstractClientPlayer player, PlayerModel<?> model, RollAnimation.Shot shot) {
        if (shot == null || shot.weight() == 0) return null;
        float left = ThirdPersonSlide.armInfluence(player, model, HumanoidArm.LEFT);
        float right = ThirdPersonSlide.armInfluence(player, model, HumanoidArm.RIGHT);
        if (model.leftArmPose != HumanoidModel.ArmPose.EMPTY || model.rightArmPose != HumanoidModel.ArmPose.EMPTY) left = right = 0;
        var original = pose(model.body);
        var poses = solve(model, shot, left, right);
        ThirdPersonSlide.pose(model.body, model.jacket, poses[0]);
        ThirdPersonSlide.pose(model.head, model.hat, poses[1]);
        ThirdPersonSlide.pose(model.leftArm, model.leftSleeve, poses[2]);
        ThirdPersonSlide.pose(model.rightArm, model.rightSleeve, poses[3]);
        ThirdPersonSlide.pose(model.leftLeg, model.leftPants, poses[4]);
        ThirdPersonSlide.pose(model.rightLeg, model.rightPants, poses[5]);
        return ThirdPersonSlide.attachmentTransform(original, poses[0]);
    }

    static RigidLimbIk.Pose[] solve(PlayerModel<?> model, RollAnimation.Shot shot, float leftInfluence, float rightInfluence) {
        float weight = shot.weight();
        var original = pose(model.body);
        var torso = torsoPose(model.body, weight);
        var originalHips = original.rotation().transform(new Vector3f(0, 12 * model.body.yScale, 0)).add(original.pivot());
        var hips = torso.rotation().transform(new Vector3f(0, 12 * model.body.yScale, 0)).add(torso.pivot());
        var twist = new Quaternionf().rotationY(model.body.yRot);
        var center = twist.transform(new Vector3f(0, -2 * model.body.yScale, -7 * model.body.zScale).mul(weight)).add(hips);
        // Compensate the tuck before applying the revolution: the rendered torso must
        // turn exactly 360 degrees, without adding/unwinding another forward lean.
        var rotation = new Quaternionf(twist).rotateX(shot.angle()).mul(new Quaternionf(twist).conjugate())
                .mul(original.rotation()).mul(new Quaternionf(torso.rotation()).conjugate());
        var head = attached(model.head, original.pivot(), original.rotation(), torso.pivot(), torso.rotation(),
                new Vector3f(), new Quaternionf(torso.rotation()).rotateX(0.75F), weight, weight);
        var leftArm = arm(model, model.leftArm, original, torso, 1, weight, leftInfluence);
        var rightArm = arm(model, model.rightArm, original, torso, -1, weight, rightInfluence);
        var leftLeg = leg(model.leftLeg, originalHips, original.rotation(), hips, torso.rotation(), model.body.xScale, weight);
        var rightLeg = leg(model.rightLeg, originalHips, original.rotation(), hips, torso.rotation(), model.body.xScale, weight);

        var poses = new RigidLimbIk.Pose[]{spin(torso, center, rotation), spin(head, center, rotation),
                spin(leftArm, center, rotation), spin(rightArm, center, rotation), spin(leftLeg, center, rotation), spin(rightLeg, center, rotation)};

        // Keep the rotating cuboids above the floor, including slim skins, part scales and
        // child geometry. Translate the connected pose together instead of shortening limbs.
        var parts = new ModelPart[]{model.body, model.head, model.leftArm, model.rightArm, model.leftLeg, model.rightLeg};
        float lowest = Float.NEGATIVE_INFINITY, originalFloor = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < parts.length; i++) {
            lowest = Math.max(lowest, maxY(parts[i], poses[i]));
            originalFloor = Math.max(originalFloor, maxY(parts[i], pose(parts[i])));
        }
        float floor = originalFloor + (model.body.getInitialPose().y + 24 * model.body.yScale - originalFloor) * weight;
        float lift = floor - lowest;
        for (var pose : poses) pose.pivot().y += lift;
        return poses;
    }

    static RigidLimbIk.Pose torsoPose(ModelPart body, float weight) {
        var original = pose(body);
        var rotation = new Quaternionf(original.rotation()).slerp(new Quaternionf().rotationYXZ(body.yRot, 1, 0), weight);
        var hips = original.rotation().transform(new Vector3f(0, 12 * body.yScale, 0)).add(original.pivot())
                .lerp(new Vector3f(body.x, body.getInitialPose().y + 12 * body.yScale, body.z), weight);
        return new RigidLimbIk.Pose(hips.sub(rotation.transform(new Vector3f(0, 12 * body.yScale, 0))), rotation);
    }

    private static RigidLimbIk.Pose arm(PlayerModel<?> model, ModelPart arm, RigidLimbIk.Pose original,
                                       RigidLimbIk.Pose torso, float sign, float weight, float influence) {
        var target = new Quaternionf(torso.rotation()).rotateZYX(sign * -0.12F, sign * 0.1F, -2.2F);
        return attached(arm, original.pivot(), original.rotation(), torso.pivot(), torso.rotation(),
                new Vector3f(arm.getInitialPose().x * model.body.xScale, arm.getInitialPose().y * model.body.yScale, 0),
                target, weight, weight * influence);
    }

    private static RigidLimbIk.Pose leg(ModelPart leg, Vector3f originalHips, Quaternionf originalRotation,
                                       Vector3f hips, Quaternionf torsoRotation, float width, float weight) {
        return attached(leg, originalHips, originalRotation, hips, torsoRotation,
                new Vector3f(leg.getInitialPose().x * width, 0, 0), new Quaternionf(torsoRotation).rotateX(-2.65F), weight, weight);
    }

    private static RigidLimbIk.Pose attached(ModelPart part, Vector3f originalAnchor, Quaternionf originalRotation,
                                            Vector3f anchor, Quaternionf rotation, Vector3f offset,
                                            Quaternionf target, float weight, float rotationWeight) {
        var pivot = ThirdPersonSlide.attachedPivot(part, originalAnchor, originalRotation, anchor, rotation, offset, weight);
        var follow = new Quaternionf(rotation).mul(new Quaternionf(originalRotation).conjugate()).mul(ThirdPersonSlide.rotation(part));
        return new RigidLimbIk.Pose(pivot, follow.slerp(target, rotationWeight));
    }

    static RigidLimbIk.Pose spin(RigidLimbIk.Pose pose, Vector3f center, Quaternionf rotation) {
        return new RigidLimbIk.Pose(rotation.transform(new Vector3f(pose.pivot()).sub(center)).add(center),
                new Quaternionf(rotation).mul(pose.rotation()));
    }

    static float maxY(ModelPart part, RigidLimbIk.Pose target) {
        float[] maximum = {Float.NEGATIVE_INFINITY};
        var original = new PoseStack();
        part.translateAndRotate(original);
        var stack = new PoseStack();
        stack.mulPose(new Matrix4f().translation(new Vector3f(target.pivot()).div(16)).rotate(target.rotation())
                .scale(part.xScale, part.yScale, part.zScale).mul(new Matrix4f(original.last().pose()).invert()));
        part.visit(stack, (pose, path, index, cube) -> {
            var matrix = pose.pose();
            float x = (cube.minX + cube.maxX) / 32, y = (cube.minY + cube.maxY) / 32, z = (cube.minZ + cube.maxZ) / 32;
            // Half-pixel allowance for the hat and outer skin layers.
            float dx = (cube.maxX - cube.minX + 1) / 32, dy = (cube.maxY - cube.minY + 1) / 32, dz = (cube.maxZ - cube.minZ + 1) / 32;
            float bottom = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31()
                    + Math.abs(matrix.m01()) * dx + Math.abs(matrix.m11()) * dy + Math.abs(matrix.m21()) * dz;
            maximum[0] = Math.max(maximum[0], bottom * 16);
        });
        return Float.isFinite(maximum[0]) ? maximum[0] : target.pivot().y;
    }

    private static RigidLimbIk.Pose pose(ModelPart part) {
        return new RigidLimbIk.Pose(new Vector3f(part.x, part.y, part.z), ThirdPersonSlide.rotation(part));
    }

    private ThirdPersonRoll() {}
}
