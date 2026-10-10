package dev.mineedge.client.render;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WallRunRenderingTest {
    private static ModelPart body(float size, float yaw) {
        var body = new ModelPart(List.of(), Map.of());
        body.setInitialPose(PartPose.ZERO);
        body.yScale = size;
        body.yRot = yaw;
        return body;
    }

    @Test void torsoLeansAwayFromEitherWallWhileAllJointsStayAttachedAtEveryBlendWeight() {
        for (int side : new int[]{-1, 1}) for (float size : new float[]{0.5F, 1, 2}) for (float yaw : new float[]{-2.8F, 0, 2.8F}) {
            var body = body(size, yaw);
            var hips = new Vector3f(0, 12 * size, 0);
            var target = ThirdPersonWallRun.torsoPose(body, hips, side, 0);
            assertEquals(0, target.rotation().transform(new Vector3f(0, 12 * size, 0)).add(target.pivot()).distance(hips), 1.0E-5);
            var lean = new Quaternionf().rotationY(-yaw).transform(new Vector3f(target.pivot()).sub(hips));
            assertTrue(lean.x * side < -3 * size, "Shoulders leaned into the wall");
            for (float weight : new float[]{0, 0.1F, 0.5F, 0.9F, 1}) {
                var originalRotation = new Quaternionf().rotationY(yaw);
                var posed = ThirdPersonLegs.blendPose(target, new Vector3f(), originalRotation, weight);
                for (var offset : new Vector3f[]{new Vector3f(), new Vector3f(5 * size, 2 * size, 0), new Vector3f(-5 * size, 2.5F * size, 0)}) {
                    var part = new ModelPart(List.of(), Map.of());
                    var originalPoint = originalRotation.transform(new Vector3f(offset));
                    part.setPos(originalPoint.x, originalPoint.y, originalPoint.z);
                    var attached = ThirdPersonSlide.attachedPivot(part, new Vector3f(), originalRotation, posed.pivot(), posed.rotation(), offset, weight);
                    var local = new Quaternionf(posed.rotation()).conjugate().transform(attached.sub(posed.pivot()));
                    assertEquals(0, local.distance(offset), 1.0E-5, "A shoulder or the neck detached during blending");
                }
            }
        }
    }

    @Test void rigidFeetAlternateAlongTheWallWithoutStridingThroughIt() {
        for (int wall : new int[]{-1, 1}) for (float size : new float[]{0.5F, 1, 2}) {
            var torso = ThirdPersonWallRun.torsoPose(body(size, 0), new Vector3f(0, 12 * size, 0), wall, 0);
            for (int leg : new int[]{-1, 1}) for (float phase : new float[]{0, (float) Math.PI / 2, (float) Math.PI, (float) Math.PI * 1.5F}) {
                var pivot = torso.rotation().transform(new Vector3f(leg * 1.9F * size, 0, 0)).add(0, 12 * size, 0);
                var rotation = ThirdPersonWallRun.legRotation(new Quaternionf(), leg, wall, phase);
                var foot = rotation.transform(new Vector3f(0, 12 * size, 0)).add(pivot);
                assertEquals(12 * size, foot.distance(pivot), 1.0E-5, "The rigid leg was stretched");
                assertTrue(foot.x * wall > 2 * size, "A foot did not reach toward the wall");
                for (float x : new float[]{-2, 2}) for (float z : new float[]{-2, 2}) {
                    var corner = rotation.transform(new Vector3f(x * size, 12 * size, z * size)).add(pivot);
                    assertTrue(corner.x * wall < 4.8F * size, "A foot clips through the wall");
                }
                if (phase == (float) Math.PI / 2) assertTrue((foot.z - pivot.z) * leg > 4 * size);
                if (phase == (float) Math.PI * 1.5F) assertTrue((foot.z - pivot.z) * leg < -4 * size);
            }
        }
    }

    @Test void armsCounterSwingTheirLegsOnEitherWall() {
        for (int wall : new int[]{-1, 1}) for (int side : new int[]{-1, 1}) {
            float first = (float) Math.PI / 2, second = (float) Math.PI * 1.5F;
            float legMotion = ThirdPersonWallRun.legRotation(new Quaternionf(), side, wall, first).transform(new Vector3f(0, 12, 0)).z
                    - ThirdPersonWallRun.legRotation(new Quaternionf(), side, wall, second).transform(new Vector3f(0, 12, 0)).z;
            float armMotion = ThirdPersonWallRun.armRotation(new Quaternionf(), side, wall, first).transform(new Vector3f(0, 10, 0)).z
                    - ThirdPersonWallRun.armRotation(new Quaternionf(), side, wall, second).transform(new Vector3f(0, 10, 0)).z;
            assertTrue(armMotion * legMotion < 0, "An arm swung with its leg instead of balancing the stride");
        }
    }

    @Test void itemUseTwoHandedPosesAndAttacksKeepControlOfTheirArms() {
        var player = mock(AbstractClientPlayer.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(player.getMainArm()).thenReturn(HumanoidArm.RIGHT);
        var model = new PlayerModel<AbstractClientPlayer>(PlayerModel.createMesh(CubeDeformation.NONE, false).getRoot().bake(64, 64), false);
        assertEquals(1, ThirdPersonWallRun.armInfluence(player, model, HumanoidArm.LEFT));
        when(player.isUsingItem()).thenReturn(true);
        assertEquals(0, ThirdPersonWallRun.armInfluence(player, model, HumanoidArm.LEFT));
        when(player.isUsingItem()).thenReturn(false);
        model.rightArmPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
        assertEquals(0, ThirdPersonWallRun.armInfluence(player, model, HumanoidArm.LEFT));
        model.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        model.leftArmPose = HumanoidModel.ArmPose.BLOCK;
        assertEquals(0, ThirdPersonWallRun.armInfluence(player, model, HumanoidArm.LEFT));
        model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        player.swingingArm = InteractionHand.MAIN_HAND;
        model.attackTime = 0.5F;
        assertEquals(1, ThirdPersonWallRun.armInfluence(player, model, HumanoidArm.LEFT));
        assertEquals(0, ThirdPersonWallRun.armInfluence(player, model, HumanoidArm.RIGHT));
    }
}
