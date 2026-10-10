package dev.mineedge.client.movement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PresentationHookTest {
    @Test void requiredCameraModelAndHandPresentationTargetsLoad() {
        // In a client transformed JVM, loading the targets validates the required
        // injection descriptors, ordinals and the shared render-animation wrapper.
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.Camera", false, getClass().getClassLoader()).getDeclaredMethods());
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.renderer.entity.EntityRenderDispatcher", false,
                getClass().getClassLoader()).getDeclaredMethods());
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.renderer.entity.LivingEntityRenderer", false,
                getClass().getClassLoader()).getDeclaredMethods());
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.renderer.entity.player.PlayerRenderer", false,
                getClass().getClassLoader()).getDeclaredMethods());
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.renderer.GameRenderer", false,
                getClass().getClassLoader()).getDeclaredMethods());
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.renderer.ItemInHandRenderer", false,
                getClass().getClassLoader()).getDeclaredMethods());
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.player.LocalPlayer", false,
                getClass().getClassLoader()).getDeclaredMethods());
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.model.geom.ModelPart", false,
                getClass().getClassLoader()).getDeclaredMethods());
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.model.PlayerModel", false,
                getClass().getClassLoader()).getDeclaredMethods());
        assertDoesNotThrow(() -> Class.forName("net.minecraft.client.renderer.entity.layers.CapeLayer", false,
                getClass().getClassLoader()).getDeclaredMethods());
    }
}
