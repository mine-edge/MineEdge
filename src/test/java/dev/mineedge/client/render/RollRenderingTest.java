package dev.mineedge.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Proxy;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.player.AbstractClientPlayer;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollRenderingTest {
    private static PlayerModel<AbstractClientPlayer> model(boolean slim, float size) {
        var model = new PlayerModel<AbstractClientPlayer>(PlayerModel.createMesh(CubeDeformation.NONE, slim).getRoot().bake(64, 64), slim);
        for (var part : parts(model)) {
            part.x *= size; part.y *= size; part.z *= size;
            part.xScale = part.yScale = part.zScale = size;
        }
        return model;
    }

    private static ModelPart[] parts(PlayerModel<?> model) {
        return new ModelPart[]{model.body, model.head, model.leftArm, model.rightArm, model.leftLeg, model.rightLeg};
    }

    private static float renderedMaxY(ModelPart part, RigidLimbIk.Pose target) {
        float[] bottom = {Float.NEGATIVE_INFINITY};
        var consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addVertex") && args.length == 11) bottom[0] = Math.max(bottom[0], (float) args[1] * 16);
                    return method.getReturnType() == void.class ? null : proxy;
                });
        var transform = new PoseStack();
        transform.translate(target.pivot().x / 16, target.pivot().y / 16, target.pivot().z / 16);
        transform.mulPose(target.rotation());
        transform.scale(part.xScale, part.yScale, part.zScale);
        part.visit(new PoseStack(), (pose, path, index, cube) -> cube.compile(transform.last(), consumer, 0, 0, -1));
        return bottom[0];
    }

    @Test void tuckedRollKeepsNeckShouldersAndHipsConnectedWithAllSkinGeometryAboveGround() {
        for (boolean slim : new boolean[]{false, true}) for (float size : new float[]{0.5F, 1, 2}) {
            var model = model(slim, size);
            for (int frame = 1; frame < 100; frame++) {
                var shot = RollAnimation.at(frame / 100.0F, 90);
                var poses = ThirdPersonRoll.solve(model, shot, 1, 1);
                var torso = poses[0];
                assertEquals(0, poses[1].pivot().distance(torso.pivot()), 1.0E-4, "Head detached from neck");
                for (int side : new int[]{0, 1}) {
                    var arm = side == 0 ? model.leftArm : model.rightArm;
                    var shoulder = torso.rotation().transform(new Vector3f(arm.getInitialPose().x * size, arm.getInitialPose().y * size, 0)).add(torso.pivot());
                    assertEquals(0, shoulder.distance(poses[2 + side].pivot()), 1.0E-4, "Shoulder detached");
                    var leg = side == 0 ? model.leftLeg : model.rightLeg;
                    var hip = torso.rotation().transform(new Vector3f(leg.getInitialPose().x * size, 12 * size, 0)).add(torso.pivot());
                    assertEquals(0, hip.distance(poses[4 + side].pivot()), 1.0E-4, "Leg detached from hips");
                }
                float lowest = Float.NEGATIVE_INFINITY;
                var parts = parts(model);
                for (int i = 0; i < parts.length; i++) {
                    assertTrue(poses[i].pivot().isFinite() && poses[i].rotation().isFinite());
                    lowest = Math.max(lowest, renderedMaxY(parts[i], poses[i]));
                    assertEquals(size, parts[i].yScale, "Animation changed limb dimensions");
                }
                assertTrue(lowest <= 24.51F * size, "A rotating body part clips through the floor");
                if (shot.weight() == 1) assertTrue(lowest > 22.4F * size, "Roll floats above the floor");
            }
        }
    }

    @Test void completeRevolutionAndRecoveryReturnToTheExactModdedPose() {
        var model = model(false, 1);
        model.body.setPos(1, 3.2F, -1);
        model.body.setRotation(0.5F, -0.2F, 0.1F);
        model.head.setPos(1, 4.2F, -1);
        model.leftArm.xRot = -1;
        for (float progress : new float[]{0, 1}) {
            var poses = ThirdPersonRoll.solve(model, RollAnimation.at(progress, 0), 1, 1);
            var parts = parts(model);
            for (int i = 0; i < parts.length; i++) {
                assertEquals(0, poses[i].pivot().distance(new Vector3f(parts[i].x, parts[i].y, parts[i].z)), 1.0E-4);
                assertEquals(1, Math.abs(poses[i].rotation().dot(ThirdPersonSlide.rotation(parts[i]))), 1.0E-5);
            }
        }
    }

    @Test void renderedTorsoMakesExactlyOneForwardTurnWithoutOvershootingOrUnwindingTheTuck() {
        for (float yaw : new float[]{-2.8F, 0, 2.8F}) {
            var model = model(false, 1);
            model.body.setRotation(0.35F, yaw, 0);
            var renderedBody = new ModelPart(java.util.List.of(), java.util.Map.of());
            double previous = 0.35, total = 0;
            for (int frame = 1; frame <= 200; frame++) {
                var torso = ThirdPersonRoll.solve(model, RollAnimation.at(frame / 200.0F, 0), 1, 1)[0];
                RigidLimbIk.apply(renderedBody, torso);
                var axis = new Quaternionf().rotationY(-yaw).mul(ThirdPersonSlide.rotation(renderedBody)).transform(new Vector3f(0, 1, 0));
                double angle = Math.atan2(axis.z, axis.y);
                double delta = Math.IEEEremainder(angle - previous, Math.PI * 2);
                assertTrue(delta >= -1.0E-5, "Recovery reversed the body's rotation at frame " + frame + ", yaw " + yaw);
                total += delta;
                assertTrue(total <= Math.PI * 2 + 1.0E-5, "The tucked torso rotated past one revolution");
                previous = angle;
            }
            assertEquals(Math.PI * 2, total, 1.0E-5);
        }
    }

    @Test void protectedArmsKeepTheirBodyRelativeItemPoseAndBackAttachmentsFollowTheRevolution() {
        var model = model(true, 1);
        model.body.yRot = 0.2F;
        model.rightArm.setRotation(-1.4F, 0.3F, 0.1F);
        var original = new RigidLimbIk.Pose(new Vector3f(model.body.x, model.body.y, model.body.z), ThirdPersonSlide.rotation(model.body));
        var relativeArm = new Quaternionf(original.rotation()).conjugate().mul(ThirdPersonSlide.rotation(model.rightArm));
        var attachment = new Vector3f(0, 2, 2);
        var originalPoint = original.rotation().transform(new Vector3f(attachment)).add(original.pivot()).div(16);
        for (float progress : new float[]{0.1F, 0.25F, 0.5F, 0.75F, 0.9F}) {
            var poses = ThirdPersonRoll.solve(model, RollAnimation.at(progress, 0), 1, 0);
            var relative = new Quaternionf(poses[0].rotation()).conjugate().mul(poses[3].rotation());
            assertEquals(1, Math.abs(relative.dot(relativeArm)), 1.0E-5, "An occupied arm lost its item-use/attack pose");
            var actual = ThirdPersonSlide.attachmentTransform(original, poses[0]).transformPosition(new Vector3f(originalPoint));
            var expected = poses[0].rotation().transform(new Vector3f(attachment)).add(poses[0].pivot()).div(16);
            assertEquals(0, actual.distance(expected), 1.0E-5, "Cape/elytra detached during the somersault");
        }
    }
}
