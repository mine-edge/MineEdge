package dev.mineedge.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.mineedge.client.movement.HandPresentation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerRenderAnimationTest {
    private static LocalPlayer player() {
        // Only storage and look are needed; no client world, connection or window.
        return mock(LocalPlayer.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    }

    @Test void customRendererCanExactlyUndoNativeSwayAtEveryPartialTick() {
        var player = player();
        when(player.getXRot()).thenReturn(20F);
        when(player.getYRot()).thenReturn(-179F);
        var shot = new HandPresentation.Shot(12.25F, 0.06F, 8F, 1.5F);
        try (var animation = new PlayerRenderAnimation(player, shot)) {
            for (float partial : new float[] {0, 0.01F, 0.5F, 0.99F, 1}) {
                float pitch = player.getXRot() - Mth.lerp(partial, player.xBobO, player.xBob);
                float yaw = player.getYRot() - Mth.lerp(partial, player.yBobO, player.yBob);
                assertEquals(shot.pitchLag(), pitch);
                assertEquals(shot.yawLag(), yaw, "Wrapped yaw must stay on the look's branch");

                var poses = new PoseStack();
                // Vanilla applies pitch/yaw; TaCZ undoes them in this same order.
                poses.mulPose(Axis.XP.rotationDegrees(pitch * 0.1F));
                poses.mulPose(Axis.YP.rotationDegrees(yaw * 0.1F));
                var nativePose = new Matrix4f(poses.last().pose());
                poses.mulPose(Axis.XP.rotationDegrees(pitch * -0.1F));
                poses.mulPose(Axis.YP.rotationDegrees(yaw * -0.1F));
                // The small residual from noncommuting rotations is constant across
                // partial ticks, rather than a second, tick-stepped sway signal.
                assertTrue(nativePose.equals(new Matrix4f()
                        .rotateX((float) Math.toRadians(shot.pitchLag() * 0.1F))
                        .rotateY((float) Math.toRadians(shot.yawLag() * 0.1F)), 1.0E-6F));
                assertTrue(poses.last().pose().equals(new Matrix4f(), 1.0E-4F));

                // Native bobView extrapolates instead of lerping walk distance.
                float phase = player.walkDist + (player.walkDist - player.walkDistO) * partial;
                assertEquals(shot.walkDistance(), phase);
                assertEquals(shot.bob(), Mth.lerp(partial, player.oBob, player.bob));
            }
        }
    }

    @Test void nestedAndFailedRendersRestoreTheExactTickState() {
        var player = player();
        player.walkDist = 10;
        player.walkDistO = 9;
        player.bob = 0.07F;
        player.oBob = 0.03F;
        player.xBob = 12;
        player.xBobO = 11;
        player.yBob = 25;
        player.yBobO = 24;
        var outer = new HandPresentation.Shot(9.5F, 0.05F, 3, 4);
        assertThrows(IllegalStateException.class, () -> {
            try (var animation = new PlayerRenderAnimation(player, outer)) {
                try (var nested = new PlayerRenderAnimation(player,
                        new HandPresentation.Shot(40, 0, 0, 0))) {
                    assertEquals(40, player.walkDist);
                }
                assertEquals(outer.walkDistance(), player.walkDist);
                assertEquals(outer.bob(), player.bob);
                assertEquals(-outer.pitchLag(), player.xBob);
                assertEquals(-outer.yawLag(), player.yBob);
                throw new IllegalStateException("render failed");
            }
        });
        assertArrayEquals(new float[] {10, 9, 0.07F, 0.03F, 12, 11, 25, 24},
                new float[] {player.walkDist, player.walkDistO, player.bob, player.oBob,
                        player.xBob, player.xBobO, player.yBob, player.yBobO});
        verify(player, never()).setPos(anyDouble(), anyDouble(), anyDouble());
        verify(player, never()).setXRot(anyFloat());
        verify(player, never()).setYRot(anyFloat());
    }
}
