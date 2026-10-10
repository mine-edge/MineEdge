package dev.mineedge.client.render;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegAnimationTest {
    private final Vector3f hip = new Vector3f(1.9F, 12, 0);
    private final Quaternionf vanilla = new Quaternionf().rotationX(0.2F);

    private RigidLimbIk.Pose braced() {
        return ThirdPersonLegs.solvePose(hip, vanilla, new Vector3f(1), 0, new Vector3f(2, 7, 4), 1);
    }

    private RigidLimbIk.Pose settle(LegAnimation animation, RigidLimbIk.Pose target) {
        animation.sample(hip, vanilla, target, 2, 0, false);
        RigidLimbIk.Pose result = null;
        for (int i = 1; i <= 60; i++) result = animation.sample(hip, vanilla, target, 2, i * 1_000_000_000L / 60, false);
        return result;
    }

    private static Vector3f direction(RigidLimbIk.Pose pose) {
        return pose.rotation().transform(new Vector3f(0, 1, 0));
    }

    @Test void losingSupportBlendsFromBracedToHangingWithoutAHipOrRotationSnap() {
        var animation = new LegAnimation();
        var before = settle(animation, braced());
        var hanging = ThirdPersonLegs.hangingPose(hip, vanilla, 1);
        var first = animation.sample(hip, vanilla, hanging, 2, 1_016_666_667L, false);
        assertEquals(hip, first.pivot());
        assertTrue(direction(first).distance(direction(before)) < direction(hanging).distance(direction(before)));
        assertTrue(direction(first).distance(direction(hanging)) > 0.01F);
        RigidLimbIk.Pose after = first;
        for (int i = 2; i <= 60; i++) after = animation.sample(hip, vanilla, hanging, 2,
                1_000_000_000L + i * 1_000_000_000L / 60, false);
        assertEquals(0, after.pivot().distance(hip), 1.0E-4);
        assertEquals(0, direction(after).distance(direction(hanging)), 1.0E-4);
    }

    @Test void regainingSupportAndChangingContactsAlsoBlendSmoothly() {
        var animation = new LegAnimation();
        var hanging = ThirdPersonLegs.hangingPose(hip, vanilla, 1);
        var before = settle(animation, hanging);
        var contact = braced();
        var first = animation.sample(hip, vanilla, contact, 2, 1_016_666_667L, false);
        assertEquals(hip, first.pivot());
        assertTrue(direction(first).distance(direction(before)) < direction(contact).distance(direction(before)));
        var differentContact = ThirdPersonLegs.solvePose(hip, vanilla, new Vector3f(1), 0,
                new Vector3f(-4, 18, -6), 1);
        var next = animation.sample(hip, vanilla, differentContact, 2, 1_033_333_333L, false);
        assertTrue(direction(next).distance(direction(first)) < direction(differentContact).distance(direction(first)));
        assertTrue(next.pivot().distance(hip) <= 2 + 1.0E-5F);
    }

    @Test void smoothingIsFrameRateIndependentAndRepeatedRendersDoNotAdvanceIt() {
        RigidLimbIk.Pose reference = null;
        for (int fps : new int[]{30, 60, 120, 240}) {
            var animation = new LegAnimation();
            var target = braced();
            animation.sample(hip, vanilla, target, 2, 0, false);
            RigidLimbIk.Pose result = null;
            for (int i = 1; i <= fps / 2; i++) {
                long now = i * 1_000_000_000L / fps;
                result = animation.sample(hip, vanilla, target, 2, now, false);
                var repeat = animation.sample(hip, vanilla, target, 2, now, false);
                assertEquals(result.pivot(), repeat.pivot());
                assertEquals(result.rotation(), repeat.rotation());
            }
            if (reference == null) reference = result;
            assertEquals(0, reference.pivot().distance(result.pivot()), 1.0E-5);
            assertEquals(0, direction(reference).distance(direction(result)), 1.0E-5);
        }
    }

    @Test void eachLegHasIndependentContactTransitions() {
        var left = new LegAnimation();
        var right = new LegAnimation();
        var contact = braced();
        var before = settle(right, contact);
        settle(left, contact);
        var hanging = ThirdPersonLegs.hangingPose(hip, vanilla, 1);
        var released = left.sample(hip, vanilla, hanging, 2, 1_100_000_000L, false);
        var supported = right.sample(hip, vanilla, contact, 2, 1_100_000_000L, false);
        assertEquals(0, direction(supported).distance(direction(before)), 1.0E-4);
        assertTrue(direction(released).distance(direction(supported)) > 0.1F);
    }

    @Test void pauseFreezesThePoseAndResetDiscardsOldTraversalHistory() {
        var animation = new LegAnimation();
        var before = settle(animation, braced());
        var hanging = ThirdPersonLegs.hangingPose(hip, vanilla, 1);
        var paused = animation.sample(hip, vanilla, hanging, 2, 1_016_666_667L, true);
        assertEquals(before.pivot(), paused.pivot());
        assertEquals(before.rotation(), paused.rotation());
        var resumed = animation.sample(hip, vanilla, hanging, 2, 1_033_333_333L, false);
        assertEquals(hip, resumed.pivot());
        assertTrue(direction(resumed).distance(direction(hanging)) < direction(paused).distance(direction(hanging)));
        animation.reset();
        var reset = animation.sample(hip, vanilla, braced(), 2, 1_050_000_000L, false);
        assertEquals(hip, reset.pivot());
        assertEquals(vanilla, reset.rotation());
        settle(animation, braced());
        var hidden = animation.sample(hip, vanilla, hanging, 2, 3_000_000_000L, false);
        assertEquals(hip, hidden.pivot());
        assertEquals(vanilla, hidden.rotation());
    }

    @Test void smoothingFollowsTheHipAndPreservesItsLimitWhenModelScaleChanges() {
        var animation = new LegAnimation();
        var contact = braced();
        var before = settle(animation, contact);
        var movedHip = new Vector3f(hip).add(4, -2, 6);
        var movedTarget = new RigidLimbIk.Pose(new Vector3f(contact.pivot()).add(4, -2, 6), contact.rotation());
        var moved = animation.sample(movedHip, vanilla, movedTarget, 2, 1_000_000_000L, false);
        assertEquals(0, new Vector3f(before.pivot()).add(4, -2, 6).distance(moved.pivot()), 1.0E-6);
        var smaller = animation.sample(movedHip, vanilla, movedTarget, 0.5F, 1_016_666_667L, false);
        assertTrue(smaller.pivot().distance(movedHip) <= 0.5F + 1.0E-5F);
        assertEquals(1, direction(smaller).length(), 1.0E-6);
    }

    @Test void releasedContactsHoldTheLastPoseWhileFadingIntoTheCurrentWalkingPose() {
        var animation = new LegAnimation();
        var before = settle(animation, braced());
        var movedHip = new Vector3f(hip).add(0, -8, 6);
        var walking = new Quaternionf().rotationX(-0.8F);
        var held = animation.sample(movedHip, walking, null, 0, 1_016_666_667L, false);
        assertEquals(movedHip, held.pivot());
        assertEquals(before.rotation(), held.rotation());
        var fading = ThirdPersonLegs.blendPose(held, movedHip, walking, 0.5F);
        assertEquals(movedHip, fading.pivot());
        assertTrue(direction(fading).distance(direction(held)) > 0.01F);
        var released = ThirdPersonLegs.blendPose(held, movedHip, walking, 0);
        assertEquals(movedHip, released.pivot());
        assertEquals(walking, released.rotation());
        var later = animation.sample(movedHip, walking, null, 0, 1_033_333_333L, false);
        assertEquals(held.rotation(), later.rotation());
        animation.reset();
        var unseen = animation.sample(movedHip, walking, null, 0, 1_050_000_000L, false);
        assertEquals(walking, unseen.rotation());
    }
}
