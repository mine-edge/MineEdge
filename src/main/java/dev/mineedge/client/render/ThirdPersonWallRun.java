package dev.mineedge.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Hip-anchored wallrun: shoulders lean away, feet stride along the wall, arms balance. */
public final class ThirdPersonWallRun {
    public static Matrix4f apply(AbstractClientPlayer player, PlayerModel<?> model, WallRunAnimation.Shot shot) {
        if (shot == null || shot.weight() == 0) return null;
        float weight = shot.weight();
        var original = new RigidLimbIk.Pose(new Vector3f(model.body.x, model.body.y, model.body.z), ThirdPersonSlide.rotation(model.body));
        var hips = original.rotation().transform(new Vector3f(0, 12 * model.body.yScale, 0)).add(original.pivot());
        var torso = ThirdPersonLegs.blendPose(torsoPose(model.body, hips, shot.side(), shot.phase()),
                original.pivot(), original.rotation(), weight);
        var posedHips = torso.rotation().transform(new Vector3f(0, 12 * model.body.yScale, 0)).add(torso.pivot());
        var twist = new Quaternionf().rotationY(model.body.yRot);

        var neck = ThirdPersonSlide.attachedPivot(model.head, original.pivot(), original.rotation(), torso.pivot(),
                torso.rotation(), new Vector3f(), weight);
        // Keep the gaze independent of the lean, so aiming and looking around remain readable.
        ThirdPersonSlide.pose(model.head, model.hat, new RigidLimbIk.Pose(neck, ThirdPersonSlide.rotation(model.head)));
        arm(model.leftArm, model.leftSleeve, original, torso, twist, 1, shot,
                armInfluence(player, model, HumanoidArm.LEFT));
        arm(model.rightArm, model.rightSleeve, original, torso, twist, -1, shot,
                armInfluence(player, model, HumanoidArm.RIGHT));
        leg(model.leftLeg, model.leftPants, original, hips, posedHips, torso.rotation(), twist, 1, shot);
        leg(model.rightLeg, model.rightPants, original, hips, posedHips, torso.rotation(), twist, -1, shot);
        ThirdPersonSlide.pose(model.body, model.jacket, torso);
        return ThirdPersonSlide.attachmentTransform(original, torso);
    }

    static RigidLimbIk.Pose torsoPose(ModelPart body, Vector3f hips, float wallSide, float phase) {
        float pitch = 0.16F + 0.015F * Mth.cos(phase * 2);
        var rotation = new Quaternionf().rotationYXZ(body.yRot, pitch, -wallSide * 0.30F);
        var shoulder = new Vector3f(hips).sub(rotation.transform(new Vector3f(0, 12 * body.yScale, 0)));
        return new RigidLimbIk.Pose(shoulder, rotation);
    }

    static Quaternionf legRotation(Quaternionf twist, int side, float wallSide, float phase) {
        float wallLeg = (1 + side * wallSide) * 0.5F;
        // The outside foot reaches across; the inside foot stays beneath its hip.
        // XZ order keeps wallward reach constant as the forward/backward stride changes.
        float roll = -wallSide * Mth.lerp(wallLeg, 0.36F, 0.065F);
        float stride = side * Mth.sin(phase) * 0.65F - 0.10F;
        return new Quaternionf(twist).rotateX(stride).rotateZ(roll);
    }

    static Quaternionf armRotation(Quaternionf twist, int side, float wallSide, float phase) {
        float wallArm = (1 + side * wallSide) * 0.5F;
        float swing = side * Mth.sin(phase);
        // The wall-side hand is held forward near the face; the free arm counter-swings.
        float pitch = Mth.lerp(wallArm, -0.35F - swing * 0.50F, -1.15F - swing * 0.12F);
        float roll = -side * Mth.lerp(wallArm, 0.32F, 0.06F);
        return new Quaternionf(twist).rotateX(pitch).rotateZ(roll);
    }

    static float armInfluence(AbstractClientPlayer player, PlayerModel<?> model, HumanoidArm side) {
        var pose = side == HumanoidArm.LEFT ? model.leftArmPose : model.rightArmPose;
        if (pose != HumanoidModel.ArmPose.EMPTY && pose != HumanoidModel.ArmPose.ITEM) return 0;
        return ThirdPersonSlide.armInfluence(player, model, side);
    }

    private static void arm(ModelPart arm, ModelPart sleeve, RigidLimbIk.Pose original, RigidLimbIk.Pose torso,
                            Quaternionf twist, int side, WallRunAnimation.Shot shot, float influence) {
        var pivot = ThirdPersonSlide.attachedPivot(arm, original.pivot(), original.rotation(), torso.pivot(), torso.rotation(),
                new Vector3f(arm.getInitialPose().x * arm.xScale, arm.getInitialPose().y * arm.yScale, 0), shot.weight());
        var rotation = ThirdPersonSlide.rotation(arm).slerp(armRotation(twist, side, shot.side(), shot.phase()), shot.weight() * influence);
        ThirdPersonSlide.pose(arm, sleeve, new RigidLimbIk.Pose(pivot, rotation));
    }

    private static void leg(ModelPart leg, ModelPart pants, RigidLimbIk.Pose original, Vector3f hips, Vector3f posedHips,
                            Quaternionf torsoRotation, Quaternionf twist, int side, WallRunAnimation.Shot shot) {
        var pivot = ThirdPersonSlide.attachedPivot(leg, hips, original.rotation(), posedHips, torsoRotation,
                new Vector3f(leg.getInitialPose().x * leg.xScale, 0, 0), shot.weight());
        var rotation = ThirdPersonSlide.rotation(leg).slerp(legRotation(twist, side, shot.side(), shot.phase()), shot.weight());
        ThirdPersonSlide.pose(leg, pants, new RigidLimbIk.Pose(pivot, rotation));
    }

    private ThirdPersonWallRun() {}
}
