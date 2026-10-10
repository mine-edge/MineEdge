package dev.mineedge.integration;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovementFacingTest {
    private static Player player(float yaw, Vec3 motion) {
        var player = mock(Player.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(player.getYRot()).thenReturn(yaw);
        when(player.getDeltaMovement()).thenReturn(motion);
        player.yBodyRot = yaw + 40;
        // No displacement inside the vanilla tick, despite ongoing simulated motion.
        player.xo = player.zo = 0;
        return player;
    }

    @Test void forwardAndBackwardWalkingRecentersAtEveryLookAngle() {
        for (float yaw : new float[] {-540, -180, -179, -90, 0, 90, 179, 180, 540}) {
            double angle = Math.toRadians(yaw);
            var forward = new Vec3(-Math.sin(angle), 0, Math.cos(angle)).scale(0.2);
            for (var motion : new Vec3[] {forward, forward.scale(-1)}) {
                var player = player(yaw, motion);
                assertEquals(yaw, MovementFacing.bodyYaw(player), 1.0E-4F,
                        "Walking must center even when the native tick sees no displacement");
            }
        }
    }

    @Test void diagonalsKeepTheirMovementFacingAcrossTheYawSeam() {
        for (float look : new float[] {-179, 0, 179}) {
            for (float offset : new float[] {-45, 45, -135, 135}) {
                double angle = Math.toRadians(look + offset);
                var motion = new Vec3(-Math.sin(angle), 0, Math.cos(angle)).scale(0.2);
                float expected = offset > 95 ? offset - 180 : offset < -95 ? offset + 180 : offset;
                assertEquals(expected, Mth.wrapDegrees(MovementFacing.bodyYaw(player(look, motion)) - look), 1.0E-4F);
            }
        }
    }

    @Test void idleAndVerticalMotionPreserveRestingYawWhileAttacksFaceTheLook() {
        for (var motion : new Vec3[] {Vec3.ZERO, new Vec3(0, -1, 0), new Vec3(0.001, 0, -0.001)}) {
            var player = player(20, motion);
            assertEquals(60, MovementFacing.bodyYaw(player));
            player.attackAnim = 0.5F;
            assertEquals(20, MovementFacing.bodyYaw(player));
        }
        var attacking = player(20, new Vec3(0.2, 0, 0));
        attacking.attackAnim = 0.5F;
        assertEquals(20, MovementFacing.bodyYaw(attacking));
    }
}
