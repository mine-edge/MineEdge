package dev.mineedge.client.render;

import dev.mineedge.movement.HandTargets;
import dev.mineedge.movement.MovementVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HandFacingTest {
    private final HandTargets grips = new HandTargets(new MovementVector(0.23, 2, 1),
            new MovementVector(-0.23, 1.7, 1), 0.8F, 0.6F);

    private HandFacing.Weights sample(HandFacing facing, float yaw) {
        double angle = Math.toRadians(yaw);
        var left = new MovementVector(Math.cos(angle) * 0.3, 1.4, Math.sin(angle) * 0.3);
        var right = new MovementVector(-left.x(), left.y(), -left.z());
        return facing.sample(grips, MovementVector.ZERO, yaw, left, right);
    }

    @Test void facingTheWallKeepsBothGripsAtTheirOriginalWeights() {
        var facing = new HandFacing();
        for (float yaw : new float[]{-70, -45, 0, 45, 70, 360, -360}) {
            var weights = sample(facing, yaw);
            assertEquals(grips.leftWeight(), weights.left(), 1.0E-6);
            assertEquals(grips.rightWeight(), weights.right(), 1.0E-6);
        }
    }

    @Test void ninetyDegreesReleasesOnlyTheShoulderFartherFromTheWall() {
        var facing = new HandFacing();
        var right = sample(facing, 90);
        assertEquals(grips.leftWeight(), right.left());
        assertEquals(0, right.right());
        var left = sample(facing, -90);
        assertEquals(0, left.left());
        assertEquals(grips.rightWeight(), left.right());
    }

    @Test void releaseBlendsIntoTheDefaultPoseAndRegripsWhenTurningBack() {
        var facing = new HandFacing();
        var turning = sample(facing, 80);
        assertEquals(grips.leftWeight(), turning.left());
        assertTrue(turning.right() > 0 && turning.right() < grips.rightWeight());
        assertEquals(0, sample(facing, 90).right());
        assertEquals(turning, sample(facing, 80));
        assertEquals(grips.rightWeight(), sample(facing, 60).right());
    }

    @Test void turningFullyAwayNeverReleasesBothHandsAndTiesKeepTheSameHand() {
        var facing = new HandFacing();
        for (float yaw : new float[]{90, 120, 179.9F, 180, 180.1F}) {
            var weights = sample(facing, yaw);
            assertEquals(grips.leftWeight(), weights.left());
            assertEquals(0, weights.right());
        }
        for (float yaw : new float[]{185, 210, 270}) {
            var weights = sample(facing, yaw);
            assertEquals(0, weights.left());
            assertEquals(grips.rightWeight(), weights.right());
        }
        assertEquals(grips.rightWeight(), sample(facing, 180).right());
    }

    @Test void aSingleAttachedHandIsNeverReleasedByFacingAngle() {
        var facing = new HandFacing();
        for (float yaw : new float[]{90, -90, 180}) {
            var left = new HandTargets(grips.left(), grips.right(), 0.75F, 0);
            var right = new HandTargets(grips.left(), grips.right(), 0, 0.75F);
            assertEquals(new HandFacing.Weights(0.75F, 0), facing.sample(left, MovementVector.ZERO,
                    yaw, MovementVector.ZERO, MovementVector.ZERO));
            assertEquals(new HandFacing.Weights(0, 0.75F), facing.sample(right, MovementVector.ZERO,
                    yaw, MovementVector.ZERO, MovementVector.ZERO));
        }
        assertEquals(new HandFacing.Weights(0, 0), facing.sample(HandTargets.EMPTY, MovementVector.ZERO,
                180, MovementVector.ZERO, MovementVector.ZERO));
    }

    @Test void rotatedWallsAndLargeWorldPositionsGiveTheSameResult() {
        for (float wallYaw : new float[]{-180, -90, 0, 90, 180}) {
            var origin = new MovementVector(29_000_000, 64, -29_000_000);
            var rotated = new HandTargets(rotate(grips.left(), wallYaw).add(origin),
                    rotate(grips.right(), wallYaw).add(origin), grips.leftWeight(), grips.rightWeight());
            // At +90 degrees to the wall, the left shoulder is closer to its plane.
            var left = rotate(new MovementVector(0, 1.4, 0.3), wallYaw).add(origin);
            var right = rotate(new MovementVector(0, 1.4, -0.3), wallYaw).add(origin);
            var weights = new HandFacing().sample(rotated, origin, wallYaw + 90, left, right);
            assertEquals(grips.leftWeight(), weights.left());
            assertEquals(0, weights.right(), 1.0E-6);
        }
    }

    @Test void narrowCoincidentGripsUseTheDirectionToTheWall() {
        var point = new MovementVector(0, 2, 1);
        var narrow = new HandTargets(point, point, 1, 1);
        var weights = new HandFacing().sample(narrow, MovementVector.ZERO, 90,
                new MovementVector(0, 1.4, 0.3), new MovementVector(0, 1.4, -0.3));
        assertEquals(new HandFacing.Weights(1, 0), weights);
    }

    private static MovementVector rotate(MovementVector point, float yaw) {
        double angle = Math.toRadians(yaw);
        return new MovementVector(point.x() * Math.cos(angle) - point.z() * Math.sin(angle),
                point.y(), point.x() * Math.sin(angle) + point.z() * Math.cos(angle));
    }
}
