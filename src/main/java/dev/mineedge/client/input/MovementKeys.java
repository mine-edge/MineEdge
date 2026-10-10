package dev.mineedge.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class MovementKeys {
    public static final KeyMapping TURN = new KeyMapping("key.mineedge.turn", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.mineedge");
    public static void register(RegisterKeyMappingsEvent event) { event.register(TURN); }
    private MovementKeys() {}
}
