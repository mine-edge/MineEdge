package dev.mineedge.client.render;

import dev.mineedge.client.movement.HandPresentation;
import net.minecraft.client.player.LocalPlayer;

/** Supplies the frame's movement animation through vanilla fields to every renderer. */
public final class PlayerRenderAnimation implements AutoCloseable {
    private final LocalPlayer player;
    private final float walkDist, walkDistO, bob, oBob, xBob, xBobO, yBob, yBobO;

    public PlayerRenderAnimation(LocalPlayer player, HandPresentation.Shot shot) {
        this.player = player;
        walkDist = player.walkDist;
        walkDistO = player.walkDistO;
        bob = player.bob;
        oBob = player.oBob;
        xBob = player.xBob;
        xBobO = player.xBobO;
        yBob = player.yBob;
        yBobO = player.yBobO;

        // The sample is already interpolated on the simulation/render clock. Equal
        // endpoints prevent vanilla partial ticks from interpolating it a second time.
        // This also handles bobView's extrapolated walkDist formula, not just lerp.
        player.walkDist = player.walkDistO = shot.walkDistance();
        player.bob = player.oBob = shot.bob();
        player.xBob = player.xBobO = player.getXRot() - shot.pitchLag();
        player.yBob = player.yBobO = player.getYRot() - shot.yawLag();
        // Keep real look/position, item-use, attack and equip timelines intact.
        // Mods such as TaCZ can now undo vanilla sway using these same fields.
    }

    @Override
    public void close() {
        // Restore the exact tick-owned values, even after a cancelled/failed render.
        player.walkDist = walkDist;
        player.walkDistO = walkDistO;
        player.bob = bob;
        player.oBob = oBob;
        player.xBob = xBob;
        player.xBobO = xBobO;
        player.yBob = yBob;
        player.yBobO = yBobO;
    }
}
