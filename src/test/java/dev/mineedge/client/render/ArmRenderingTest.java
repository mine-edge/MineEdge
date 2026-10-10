package dev.mineedge.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmRenderingTest {
    @Test void modelPartAnglesPreserveCombinedQuaternionRotationsAcrossYawSeamsAndFullTurns() {
        var part = new ModelPart(List.of(), java.util.Map.of());
        var pivot = new Vector3f(5, 2, -3);
        for (float yaw : new float[]{-3.1F, (float) -Math.PI / 2, -1.4F, 0, 1.4F, (float) Math.PI / 2, 3.1F})
                for (float pitch : new float[]{-2.8F, -1.2F, 0.35F, 1.8F, 3.5F, 6.2F}) {
            var target = new Quaternionf().rotationZYX(0.4F, yaw, pitch);
            RigidLimbIk.apply(part, new RigidLimbIk.Pose(pivot, target));
            assertEquals(0, pivot.distance(new Vector3f(part.x, part.y, part.z)), 1.0E-6);
            var rendered = ThirdPersonSlide.rotation(part);
            for (var axis : new Vector3f[]{new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)}) {
                assertEquals(0, target.transform(new Vector3f(axis)).distance(rendered.transform(new Vector3f(axis))), 1.0E-5);
            }
        }
    }

    private static ModelPart.Cube cube(boolean slim, float grow, int side) {
        return new ModelPart.Cube(32, 48, side > 0 ? -1 : (slim ? -2 : -3), -2, -2, slim ? 3 : 4, 12, 4,
                grow, grow, grow, false, 64, 64, EnumSet.allOf(Direction.class));
    }

    private record Vertex(float x, float y, float z, float u, float v) {}
    private static VertexConsumer capture(List<Vertex> output) {
        return (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addVertex") && args.length == 11) {
                        output.add(new Vertex((float) args[0], (float) args[1], (float) args[2], (float) args[4], (float) args[5]));
                    }
                    return method.getReturnType() == void.class ? null : proxy;
                });
    }

    @Test void rigidSkinSleevesAndArmorPreserveDimensionsAndUvs() {
        for (boolean slim : new boolean[]{false, true}) for (int side : new int[]{-1, 1}) {
            float centerX = side * (slim ? 0.5F : 1);
            var scale = new Vector3f(0.8F, 1.2F, 0.9F);
            var solution = ThirdPersonHands.solvePose(new Vector3f(side * 5, 2, 0),
                    new Quaternionf().rotationZYX(-0.1F, 0.1F, 0.2F), scale, centerX, new Vector3f(side * 6, -7, -4), 1);
            assertNotNull(solution);
            for (float grow : new float[]{0, 0.25F, 1}) {
                var cube = cube(slim, grow, side);
                var original = new ArrayList<Vertex>();
                var posed = new ArrayList<Vertex>();
                var originalPoses = new PoseStack();
                originalPoses.scale(scale.x, scale.y, scale.z);
                cube.compile(originalPoses.last(), capture(original), 0, 0, -1);
                cube.compile(transform(solution, scale).last(), capture(posed), 0, 0, -1);
                assertEquals(24, posed.size(), "Arm must retain its six unsplit faces");
                for (int i = 0; i < original.size(); i++) {
                    assertEquals(original.get(i).u, posed.get(i).u);
                    assertEquals(original.get(i).v, posed.get(i).v);
                    for (int j = i + 1; j < original.size(); j++) {
                        assertEquals(position(original.get(i)).distance(position(original.get(j))),
                                position(posed.get(i)).distance(position(posed.get(j))), 1.0E-5);
                    }
                }
            }
        }
    }

    @Test void renderedWristCenterHitsTheGripForBothArmsAndSkinWidths() {
        for (boolean slim : new boolean[]{false, true}) for (int side : new int[]{-1, 1}) {
            float centerX = side * (slim ? 0.5F : 1);
            var target = new Vector3f(side * 6, -7, -4);
            var scale = new Vector3f(0.8F, 1.2F, 0.9F);
            var pivot = new Vector3f(side * 5, 2, 0);
            var solution = ThirdPersonHands.solvePose(pivot,
                    new Quaternionf().rotationZYX(-0.1F, 0.1F, 0.2F), scale, centerX, target, 1);
            assertNotNull(solution);
            assertTrue(solution.pivot().distance(pivot) > 0.1F, "Rigid IK must move the arm origin");
            var vertices = new ArrayList<Vertex>();
            cube(slim, 0, side).compile(transform(solution, scale).last(), capture(vertices), 0, 0, -1);
            // The second face is the distal cap: its four rendered corners must center on the grip.
            var wrist = new Vector3f();
            for (int i = 4; i < 8; i++) {
                var v = vertices.get(i);
                wrist.add(v.x, v.y, v.z);
            }
            wrist.mul(4); // average four corners and convert model units back to pixels
            assertEquals(0, wrist.distance(target), 1.0E-4);
        }
    }

    @Test void partialWeightsBlendTheWristFromVanillaToTheGrip() {
        var pivot = new Vector3f(5, 2, 0);
        var vanilla = new Quaternionf().rotationZYX(-0.1F, 0.1F, 0.2F);
        var target = new Vector3f(6, -7, -4);
        var scale = new Vector3f(1);
        var vanillaWrist = vanilla.transform(new Vector3f(1, 10, 0)).add(pivot);
        for (float weight : new float[]{0.01F, 0.25F, 0.5F, 0.75F, 1}) {
            var solution = ThirdPersonHands.solvePose(pivot, vanilla, scale, 1, target, weight);
            assertNotNull(solution);
            var wrist = solution.rotation().transform(new Vector3f(1, 10, 0)).add(solution.pivot());
            assertEquals(0, wrist.distance(new Vector3f(vanillaWrist).lerp(target, weight)), 1.0E-4);
        }
        assertNull(ThirdPersonHands.solvePose(pivot, vanilla, scale, 1, target, 0));
    }

    @Test void coincidentAndOppositeGripsStayFiniteAndDistantGripsRelease() {
        var pivot = new Vector3f(5, 2, 0);
        var shoulder = new Vector3f(6, 0, 0);
        for (var target : new Vector3f[]{shoulder, new Vector3f(shoulder).add(0, -8, 0)}) {
            var solution = ThirdPersonHands.solvePose(pivot, new Quaternionf(), new Vector3f(1), 1, target, 1);
            assertNotNull(solution);
            assertTrue(solution.pivot().isFinite());
            assertTrue(solution.rotation().isFinite());
            var wrist = solution.rotation().transform(new Vector3f(1, 10, 0)).add(solution.pivot());
            assertEquals(0, wrist.distance(target), 1.0E-4);
        }
        var solution = ThirdPersonHands.solvePose(pivot, new Quaternionf(), new Vector3f(1), 1,
                new Vector3f(shoulder).add(0, 14, 0), 1);
        assertNotNull(solution);
        var fadedWrist = solution.rotation().transform(new Vector3f(1, 10, 0)).add(solution.pivot());
        assertEquals(13, fadedWrist.y, 1.0E-4); // half-weight fade from the vanilla wrist at y=12 to y=14
        assertNull(ThirdPersonHands.solvePose(pivot, new Quaternionf(), new Vector3f(1), 1,
                new Vector3f(100, 0, 0), 1));
    }

    @Test void facingReleaseKeepsTheNormalShoulderOriginAndRestoresTheDefaultPose() {
        for (boolean slim : new boolean[]{false, true}) for (int side : new int[]{-1, 1}) {
            float centerX = side * (slim ? 0.5F : 1);
            var pivot = new Vector3f(side * 5, 2, 0);
            var vanilla = new Quaternionf().rotationZYX(-0.1F, 0.1F, 0.2F);
            var scale = new Vector3f(0.8F, 1.2F, 0.9F);
            var target = new Vector3f(side * 6, -7, -4);
            for (float gripWeight : new float[]{0.25F, 0.75F, 1}) {
                var grip = ThirdPersonHands.solvePose(pivot, vanilla, scale, centerX, target, gripWeight);
                assertNotNull(grip);
                assertTrue(grip.pivot().distance(pivot) > 0.1F);
                for (float keep : new float[]{0, 0.25F, 0.5F, 0.75F, 0.99F, 1}) {
                    var release = ThirdPersonHands.solveFacingPose(pivot, vanilla, scale, centerX,
                            target, gripWeight, gripWeight * keep);
                    assertNotNull(release);
                    assertEquals(0, release.pivot().distance(keep < 1 ? pivot : grip.pivot()), 1.0E-6);
                    var expected = new Quaternionf(vanilla).slerp(grip.rotation(), keep);
                    assertEquals(0, release.rotation().transform(new Vector3f(0, 1, 0))
                            .distance(expected.transform(new Vector3f(0, 1, 0))), 1.0E-6);
                }
            }
        }
        assertNull(ThirdPersonHands.solveFacingPose(new Vector3f(), new Quaternionf(), new Vector3f(1),
                0, new Vector3f(0, 10, 0), 0, 0));
    }

    @Test void fullyDetachedArmDoesNotDependOnTheOldGripLocation() {
        var pivot = new Vector3f(5, 2, 0);
        var vanilla = new Quaternionf().rotationZYX(0.2F, -0.3F, 0.4F);
        var release = ThirdPersonHands.solveFacingPose(pivot, vanilla, new Vector3f(1),
                1, new Vector3f(100, 100, 100), 1, 0);
        assertNotNull(release);
        assertEquals(pivot, release.pivot());
        assertEquals(vanilla, release.rotation());
    }

    private static PoseStack transform(RigidLimbIk.Pose solution, Vector3f scale) {
        var poses = new PoseStack();
        poses.translate(solution.pivot().x / 16, solution.pivot().y / 16, solution.pivot().z / 16);
        poses.mulPose(solution.rotation());
        poses.scale(scale.x, scale.y, scale.z);
        return poses;
    }

    private static Vector3f position(Vertex vertex) { return new Vector3f(vertex.x, vertex.y, vertex.z); }
}
