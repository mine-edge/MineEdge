package dev.mineedge.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mineedge.client.movement.ClientMovement;
import dev.mineedge.client.render.PlayerRenderAnimation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** One animation scope includes camera, world and both vanilla/modded hand passes. */
@Mixin(GameRenderer.class)
abstract class RenderAnimationMixin {
    @WrapMethod(method = "renderLevel")
    private void mineedge$animation(DeltaTracker deltaTracker, Operation<Void> original) {
        var player = Minecraft.getInstance().player;
        var shot = ClientMovement.INSTANCE.handPresentation(player);
        if (player == null || shot == null) {
            original.call(deltaTracker);
            return;
        }
        try (var animation = new PlayerRenderAnimation(player, shot)) {
            original.call(deltaTracker);
        }
    }
}
