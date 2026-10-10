package dev.mineedge.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import dev.mineedge.movement.FootTargets;
import dev.mineedge.movement.HandTargets;
import dev.mineedge.movement.MovementVector;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegRenderingTest {
    private static ModelPart.Cube cube(float grow) {
        return new ModelPart.Cube(0, 16, -2, 0, -2, 4, 12, 4,
                grow, grow, grow, false, 64, 64, EnumSet.allOf(Direction.class));
    }

    private record Vertex(Vector3f position, float u, float v) {}

    private static List<Vertex> render(ModelPart.Cube cube, PoseStack poses) {
        var vertices = new ArrayList<Vertex>();
        var buffer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addVertex") && args.length == 11) {
                        vertices.add(new Vertex(new Vector3f((float) args[0], (float) args[1], (float) args[2]),
                                (float) args[4], (float) args[5]));
                    }
                    return method.getReturnType() == void.class ? null : proxy;
                });
        cube.compile(poses.last(), buffer, 0, 0, -1);
        return vertices;
    }

    @Test void footTargetsKeepTheHipFixedWithoutBendingOrStretchingLegs() {
        for (int side : new int[]{-1, 1}) for (float height : new float[]{-8, 0, 8}) {
            var pivot = new Vector3f(side * 1.9F, 12, 0);
            var scale = new Vector3f(0.8F, 1.2F, 0.9F);
            var vanilla = new Quaternionf().rotationZYX(0.1F, -0.1F, side * 0.6F);
            var target = vanilla.transform(new Vector3f(0, 12 * scale.y, 0)).add(pivot).add(0, height, 0);
            var pose = ThirdPersonLegs.solvePose(pivot, vanilla, scale, 0, target, 1);
            assertNotNull(pose);
            assertEquals(pivot, pose.pivot());
            for (float grow : new float[]{0, 0.25F, 0.5F, 1}) {
                var originalPoses = new PoseStack();
                originalPoses.scale(scale.x, scale.y, scale.z);
                var original = render(cube(grow), originalPoses);
                var poses = new PoseStack();
                poses.translate(pose.pivot().x / 16, pose.pivot().y / 16, pose.pivot().z / 16);
                poses.mulPose(pose.rotation());
                poses.scale(scale.x, scale.y, scale.z);
                var posed = render(cube(grow), poses);
                assertEquals(24, posed.size());
                for (int i = 0; i < original.size(); i++) {
                    assertEquals(original.get(i).u, posed.get(i).u);
                    assertEquals(original.get(i).v, posed.get(i).v);
                    for (int j = i + 1; j < original.size(); j++) {
                        assertEquals(original.get(i).position.distance(original.get(j).position),
                                posed.get(i).position.distance(posed.get(j).position), 1.0E-5);
                    }
                }
                if (grow == 0) {
                    var sole = new Vector3f();
                    for (int i = 4; i < 8; i++) sole.add(posed.get(i).position);
                    sole.mul(4); // average four distal corners, then convert back to model pixels
                    var endpoint = pose.rotation().transform(new Vector3f(0, 12 * scale.y, 0)).add(pose.pivot());
                    assertEquals(0, sole.distance(endpoint), 1.0E-4);
                }
            }
        }
    }

    @Test void traversalFeetSurviveEulerConversionAndClothingCopies() {
        var origin = new MovementVector(29_000_000, 64, -29_000_000);
        var grips = new HandTargets(origin.add(new MovementVector(0.23, 1.8, 0.3)),
                origin.add(new MovementVector(-0.23, 1.6, 0.3)), 1, 1);
        var matrix = new Matrix4f().scaling(1, -1, 1).translate(0, -1.5F, 0);
        var feet = FootTargets.fromGrips(grips);
        for (int side = 0; side < 2; side++) {
            var leg = new ModelPart(List.of(cube(0)), Map.of());
            var pants = new ModelPart(List.of(cube(0.25F)), Map.of());
            leg.setPos(side == 0 ? 1.9F : -1.9F, 12, 0);
            var original = leg.storePose();
            var delta = (side == 0 ? feet.left() : feet.right()).subtract(origin);
            var target = matrix.transformPosition(new Vector3f((float) delta.x(), (float) delta.y(), (float) delta.z())).mul(16);
            var pose = ThirdPersonLegs.solvePose(new Vector3f(leg.x, leg.y, leg.z), new Quaternionf(), new Vector3f(1),
                    0, target, 1);
            assertNotNull(pose);
            RigidLimbIk.apply(leg, pose);
            pants.copyFrom(leg);
            var poses = new PoseStack();
            leg.translateAndRotate(poses);
            var vertices = render(cube(0), poses);
            var sole = new Vector3f();
            for (int i = 4; i < 8; i++) sole.add(vertices.get(i).position);
            sole.mul(4);
            var endpoint = pose.rotation().transform(new Vector3f(0, 12, 0)).add(pose.pivot());
            assertEquals(0, sole.distance(endpoint), 1.0E-4);
            assertArrayEquals(new float[]{leg.x, leg.y, leg.z, leg.xRot, leg.yRot, leg.zRot},
                    new float[]{pants.x, pants.y, pants.z, pants.xRot, pants.yRot, pants.zRot});
            assertEquals(new Vector3f(original.x, original.y, original.z), new Vector3f(leg.x, leg.y, leg.z));
        }
    }

    @Test void closeAndDistantClimbTargetsKeepHipsAttachedAtEveryBlendAndScale() {
        var pivot = new Vector3f(1.9F, 12, 0);
        for (float size : new float[]{0.5F, 1, 2}) for (float weight : new float[]{0.01F, 0.25F, 0.5F, 1}) {
            var scale = new Vector3f(size);
            for (var target : new Vector3f[]{new Vector3f(pivot), new Vector3f(2, 7, 4), new Vector3f(2, 40, 4)}) {
                var pose = ThirdPersonLegs.solvePose(pivot, new Quaternionf(), scale, 0, target, weight);
                assertNotNull(pose);
                assertTrue(pose.pivot().isFinite());
                assertEquals(pivot, pose.pivot());
                var endpoint = pose.rotation().transform(new Vector3f(0, 12 * size, 0)).add(pose.pivot());
                assertEquals(12 * size, endpoint.distance(pose.pivot()), 1.0E-4);
            }
        }
        var reachable = new Vector3f(pivot).add(0, 12, 0);
        var pose = ThirdPersonLegs.solvePose(pivot, new Quaternionf(), new Vector3f(1), 0, reachable, 1);
        assertNotNull(pose);
        assertEquals(0, pose.pivot().distance(pivot), 1.0E-5);
        assertEquals(0, pose.rotation().transform(new Vector3f(0, 12, 0)).add(pose.pivot()).distance(reachable), 1.0E-5);
    }

    @Test void noTraversalContactsNeedsNoModelMatrixOrWorld() {
        assertSame(FootTargets.EMPTY, FootTargets.fromGrips(HandTargets.EMPTY));
        assertDoesNotThrow(() -> ThirdPersonLegs.apply(null, FootTargets.EMPTY, null, null, false, false, null, 0, false));
        assertDoesNotThrow(() -> ThirdPersonLegs.apply(null, FootTargets.EMPTY, null, null, true, true, null, 0, false));
    }

    @Test void unsupportedLegHangsStraightWithoutMovingItsHip() {
        var pivot = new Vector3f(1.9F, 12, 0);
        var vanilla = new Quaternionf().rotationZYX(0.2F, -0.3F, 0.8F);
        for (float weight : new float[]{0.25F, 0.5F, 1}) {
            var pose = ThirdPersonLegs.hangingPose(pivot, vanilla, weight);
            assertNotNull(pose);
            assertEquals(0, pose.pivot().distance(pivot), 1.0E-6);
            assertEquals(0, pose.rotation().angle() - vanilla.angle() * (1 - weight), 1.0E-5);
        }
        var pose = ThirdPersonLegs.hangingPose(pivot, vanilla, 1);
        assertEquals(0, pose.rotation().transform(new Vector3f(0, 12, 0)).distance(new Vector3f(0, 12, 0)), 1.0E-5);
        assertNull(ThirdPersonLegs.hangingPose(pivot, vanilla, 0));
    }

    @Test void fadingTraversalUsesTheCurrentWalkingPoseInsteadOfLaggingItsAnimation() {
        var pivot = new Vector3f(1.9F, 12, 0);
        var traversal = ThirdPersonLegs.solvePose(pivot, new Quaternionf(), new Vector3f(1),
                0, new Vector3f(2, 7, 4), 1);
        for (float swing : new float[]{-0.8F, 0, 0.8F}) {
            var vanilla = new Quaternionf().rotationX(swing);
            var released = ThirdPersonLegs.blendPose(traversal, pivot, vanilla, 0);
            assertEquals(pivot, released.pivot());
            assertEquals(vanilla, released.rotation());
            var active = ThirdPersonLegs.blendPose(traversal, pivot, vanilla, 1);
            assertEquals(0, traversal.pivot().distance(active.pivot()), 1.0E-6);
            assertEquals(0, traversal.rotation().transform(new Vector3f(0, 1, 0))
                    .distance(active.rotation().transform(new Vector3f(0, 1, 0))), 1.0E-6);
            var fading = ThirdPersonLegs.blendPose(traversal, pivot, vanilla, 0.25F);
            assertEquals(traversal.pivot().distance(pivot) * 0.25F, fading.pivot().distance(pivot), 1.0E-6);
            assertTrue(fading.pivot().distance(pivot) <= 2);
        }
    }
}
