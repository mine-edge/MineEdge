package dev.mineedge.client.render;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlideRenderingTest {
    private static ModelPart part(float x, float y, float z) {
        var part = new ModelPart(List.of(), Map.of());
        part.setPos(x, y, z);
        part.setInitialPose(PartPose.offset(x, y, z));
        return part;
    }

    @Test void slideKeepsRigidLegsConnectedWithFeetInFrontAndAboveTheFloor() {
        for (float size : new float[]{0.5F, 1, 2}) for (float yaw : new float[]{-2.8F, 0, 2.8F}) {
            var body = part(0, 0, 0);
            body.yScale = size;
            body.yRot = yaw;
            var torso = ThirdPersonSlide.torsoPose(body);
            var hips = torso.rotation().transform(new Vector3f(0, 12 * size, 0)).add(torso.pivot());
            var twist = new Quaternionf().rotationY(yaw);
            assertEquals(21 * size, hips.y, 1.0E-5);
            assertEquals(12 * size, hips.distance(torso.pivot()), 1.0E-5);
            assertTrue(torso.pivot().y > 16 * size, "The torso should recline into a low silhouette");
            for (int side : new int[]{-1, 1}) {
                var leg = part(side * 1.9F * size, 12 * size, 0);
                var pose = ThirdPersonSlide.legPose(leg, hips, twist, side);
                assertEquals(1.9F * size, pose.pivot().distance(hips), 1.0E-5);
                var foot = pose.rotation().transform(new Vector3f(0, 12 * size, 0)).add(pose.pivot());
                assertEquals(12 * size, foot.distance(pose.pivot()), 1.0E-5);
                assertTrue(new Quaternionf(twist).conjugate().transform(new Vector3f(foot).sub(hips)).z < -11 * size);
                for (float x : new float[]{-2, 2}) for (float z : new float[]{-2, 2}) {
                    var corner = pose.rotation().transform(new Vector3f(x * size, 12 * size, z * size)).add(pose.pivot());
                    assertTrue(corner.y <= 24.016F * size, "A sliding foot clips through the floor");
                }
            }
        }
    }

    @Test void attachedJointsFollowTheBlendedTorsoInsteadOfDetachingDuringEntry() {
        var body = part(0, 0, 0);
        var original = new Quaternionf();
        var target = ThirdPersonSlide.torsoPose(body);
        for (float weight : new float[]{0, 0.1F, 0.5F, 0.9F, 1}) {
            var torso = ThirdPersonLegs.blendPose(target, new Vector3f(), original, weight);
            for (var offset : new Vector3f[]{new Vector3f(), new Vector3f(5, 2, 0), new Vector3f(-5, 2.5F, 0)}) {
                var part = part(offset.x, offset.y, offset.z);
                var attached = ThirdPersonSlide.attachedPivot(part, new Vector3f(), original, torso.pivot(), torso.rotation(), offset, weight);
                var local = new Quaternionf(torso.rotation()).conjugate().transform(attached.sub(torso.pivot()));
                assertEquals(0, local.distance(offset), 1.0E-5, "The head/shoulder moved away from its torso attachment");
            }
        }
    }

    @Test void entryStartsFromModdedPivotAndScaleAndRecoveryDoesNotSinkIntoCrouch() {
        var body = part(0, 0, 0);
        var slide = ThirdPersonSlide.torsoPose(body);
        body.y = 3.2F;
        assertEquals(slide.pivot(), ThirdPersonSlide.torsoPose(body).pivot(), "A crouch transition changed the slide's ground height");
        var limb = part(6, 5, -3);
        var anchor = new Vector3f(2, 1, 4);
        var rotation = new Quaternionf().rotationZYX(0.1F, -0.2F, 0.5F);
        var initial = ThirdPersonSlide.attachedPivot(limb, anchor, rotation, anchor, rotation, new Vector3f(5, 2, 0), 0);
        assertEquals(0, initial.distance(new Vector3f(limb.x, limb.y, limb.z)), 1.0E-5);
    }

    @Test void backAttachmentsFollowTheTorsoWithoutApplyingTheOriginalAnimationTwice() {
        var original = new RigidLimbIk.Pose(new Vector3f(0, 3.2F, 1), new Quaternionf().rotationZYX(0.1F, -0.2F, 0.5F));
        var target = ThirdPersonSlide.torsoPose(part(0, 0, 0));
        var localAttachment = new Vector3f(0, 2, 2);
        var originalPoint = original.rotation().transform(new Vector3f(localAttachment)).add(original.pivot()).div(16);
        for (float weight : new float[]{0, 0.1F, 0.5F, 0.9F, 1}) {
            var posed = ThirdPersonLegs.blendPose(target, original.pivot(), original.rotation(), weight);
            var actual = ThirdPersonSlide.attachmentTransform(original, posed).transformPosition(new Vector3f(originalPoint));
            var expected = posed.rotation().transform(new Vector3f(localAttachment)).add(posed.pivot()).div(16);
            assertEquals(0, actual.distance(expected), 1.0E-5, "Cape/elytra detached from the torso");
        }
    }
}
