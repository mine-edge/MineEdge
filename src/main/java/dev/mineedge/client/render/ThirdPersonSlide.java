package dev.mineedge.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A connected, rigid feet-first slide, blended against the current vanilla/mod pose. */
public final class ThirdPersonSlide {
    private static final float RECLINE = -1.35F;
    private static final float HIP_DROP = 9;

    public static void clear(PlayerModel<?> model) {
        restore(model.head); restore(model.hat);
        restore(model.body); restore(model.jacket);
    }

    public static Matrix4f apply(AbstractClientPlayer player, PlayerModel<?> model, SlideAnimation.Shot shot) {
        if (shot == null || shot.weight() == 0) return null;
        float weight = shot.weight();
        var originalTorso = new RigidLimbIk.Pose(new Vector3f(model.body.x, model.body.y, model.body.z), rotation(model.body));
        var torso = ThirdPersonLegs.blendPose(torsoPose(model.body), originalTorso.pivot(), originalTorso.rotation(), weight);
        var hips = torso.rotation().transform(new Vector3f(0, 12 * model.body.yScale, 0)).add(torso.pivot());
        var originalHips = originalTorso.rotation().transform(new Vector3f(0, 12 * model.body.yScale, 0)).add(originalTorso.pivot());
        var twist = new Quaternionf().rotationY(model.body.yRot);

        // Head and shoulders follow the torso, rather than floating above the lowered hips.
        var neck = attachedPivot(model.head, originalTorso.pivot(), originalTorso.rotation(), torso.pivot(), torso.rotation(), new Vector3f(), weight);
        pose(model.head, model.hat, new RigidLimbIk.Pose(neck, rotation(model.head)));
        arm(model.leftArm, model.leftSleeve, model.leftArmPose, originalTorso, torso, twist, 1,
                weight, armInfluence(player, model, HumanoidArm.LEFT));
        arm(model.rightArm, model.rightSleeve, model.rightArmPose, originalTorso, torso, twist, -1,
                weight, armInfluence(player, model, HumanoidArm.RIGHT));
        leg(model.leftLeg, model.leftPants, originalHips, originalTorso.rotation(), hips, torso.rotation(), twist, 1, weight);
        leg(model.rightLeg, model.rightPants, originalHips, originalTorso.rotation(), hips, torso.rotation(), twist, -1, weight);
        pose(model.body, model.jacket, torso);
        return attachmentTransform(originalTorso, torso);
    }

    static Matrix4f attachmentTransform(RigidLimbIk.Pose original, RigidLimbIk.Pose posed) {
        return new Matrix4f().translation(new Vector3f(posed.pivot()).div(16)).rotate(posed.rotation())
                .rotate(new Quaternionf(original.rotation()).conjugate()).translate(new Vector3f(original.pivot()).div(-16));
    }

    static RigidLimbIk.Pose torsoPose(ModelPart body) {
        var rotation = new Quaternionf().rotationYXZ(body.yRot, RECLINE, 0);
        var hips = new Vector3f(body.x, body.getInitialPose().y + (12 + HIP_DROP) * body.yScale, body.z);
        var shoulder = new Vector3f(hips).sub(rotation.transform(new Vector3f(0, 12 * body.yScale, 0)));
        return new RigidLimbIk.Pose(shoulder, rotation);
    }

    static RigidLimbIk.Pose legPose(ModelPart leg, Vector3f hips, Quaternionf twist, int side) {
        var pivot = twist.transform(new Vector3f(leg.x, 0, 0)).add(hips);
        // Slightly staggered, splayed feet give the slide a stable silhouette without a walking cycle.
        var rotation = new Quaternionf(twist).rotateZYX(side * -0.045F, side * -0.06F, side > 0 ? -1.55F : -1.50F);
        return new RigidLimbIk.Pose(pivot, rotation);
    }

    private static void arm(ModelPart arm, ModelPart sleeve, HumanoidModel.ArmPose armPose,
                            RigidLimbIk.Pose originalTorso, RigidLimbIk.Pose torso, Quaternionf twist,
                            int side, float weight, float influence) {
        // Move even an occupied hand to the new shoulder, retaining its item-use/attack rotation.
        float shoulderHeight = arm.getInitialPose().y * arm.yScale;
        var pivot = attachedPivot(arm, originalTorso.pivot(), originalTorso.rotation(), torso.pivot(), torso.rotation(),
                new Vector3f(arm.getInitialPose().x * arm.xScale, shoulderHeight, 0), weight);
        var rotation = armPose == HumanoidModel.ArmPose.EMPTY
                ? new Quaternionf(twist).rotateZYX(side * -0.18F, 0, -1.3F)
                : armPose == HumanoidModel.ArmPose.ITEM ? rotation(arm).rotateX(-1) : rotation(arm);
        // A protected arm still follows the body; only its rotation is left to vanilla/other mods.
        var blended = new RigidLimbIk.Pose(pivot, rotation(arm).slerp(rotation, weight * influence));
        pose(arm, sleeve, blended);
    }

    private static void leg(ModelPart leg, ModelPart pants, Vector3f originalHips, Quaternionf originalRotation,
                            Vector3f hips, Quaternionf torsoRotation, Quaternionf twist, int side, float weight) {
        var pivot = attachedPivot(leg, originalHips, originalRotation, hips, torsoRotation, new Vector3f(leg.x, 0, 0), weight);
        var target = legPose(leg, hips, twist, side);
        pose(leg, pants, new RigidLimbIk.Pose(pivot, rotation(leg).slerp(target.rotation(), weight)));
    }

    static Vector3f attachedPivot(ModelPart part, Vector3f originalAnchor, Quaternionf originalRotation,
                                  Vector3f anchor, Quaternionf rotation, Vector3f localTarget, float weight) {
        var offset = new Quaternionf(originalRotation).conjugate()
                .transform(new Vector3f(part.x, part.y, part.z).sub(originalAnchor)).lerp(localTarget, weight);
        return rotation.transform(offset).add(anchor);
    }

    static float armInfluence(AbstractClientPlayer player, PlayerModel<?> model, HumanoidArm side) {
        if (player.isUsingItem() || model.leftArmPose.isTwoHanded() || model.rightArmPose.isTwoHanded()) return 0;
        var attacking = player.swingingArm == InteractionHand.MAIN_HAND
                ? player.getMainArm() : player.getMainArm().getOpposite();
        return attacking == side ? 1 - Math.clamp(model.attackTime * 4, 0, 1) : 1;
    }

    static void pose(ModelPart part, ModelPart clothing, RigidLimbIk.Pose target) {
        ((IkModelPart) (Object) part).mineedge$savePose();
        ((IkModelPart) (Object) clothing).mineedge$savePose();
        RigidLimbIk.apply(part, target);
        clothing.copyFrom(part);
    }

    static Quaternionf rotation(ModelPart part) { return new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot); }
    private static void restore(ModelPart part) { ((IkModelPart) (Object) part).mineedge$restorePose(); }

    private ThirdPersonSlide() {}
}
