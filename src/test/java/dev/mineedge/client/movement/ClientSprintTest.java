package dev.mineedge.client.movement;

import dev.mineedge.client.input.InputLatch;
import dev.mineedge.integration.MovementEligibility;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.integration.MovementSprint;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.network.SessionPayload;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientSprintTest {
    @Test void nativeAndModStopsSurvivePredictionWhileApprovedSprintStillStartsSlides() throws Exception {
        var minecraft = mock(Minecraft.class);
        var player = mock(LocalPlayer.class);
        var level = mock(ClientLevel.class);
        minecraft.player = player;
        player.input = new Input();
        player.input.forwardImpulse = 1;
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(true);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(minecraft.isWindowActive()).thenReturn(true);
        when(player.canSprint()).thenReturn(true);
        when(player.getFoodData()).thenReturn(new FoodData());
        var flag = new AtomicBoolean();
        when(player.isSprinting()).thenAnswer(call -> flag.get());
        doAnswer(call -> {
            flag.set(call.getArgument(0));
            // Model the transformed setter's notification, including suppression of managed writes.
            MovementSprint.changed(player);
            return null;
        }).when(player).setSprinting(anyBoolean());
        var bridge = MovementRuntime.class.getDeclaredField("client");
        bridge.setAccessible(true);
        var previousBridge = (MovementRuntime.ClientBridge) bridge.get(null);
        try (var client = mockStatic(Minecraft.class); var eligibility = mockStatic(MovementEligibility.class)) {
            client.when(Minecraft::getInstance).thenReturn(minecraft);
            eligibility.when(() -> MovementEligibility.supported(player)).thenReturn(true);
            var movement = ClientMovement.INSTANCE;
            movement.logout(null);
            MovementRuntime.installClient(movement);
            try {
                var session = ClientMovement.class.getDeclaredMethod("receiveSession", SessionPayload.class);
                session.setAccessible(true);
                session.invoke(movement, new SessionPayload(Level.OVERWORLD.location(), 10, 100, true, true, MovementTuning.DEFAULT));
                var state = new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 6), MovementState.Mode.GROUND, 1, true, 0);
                field("state").set(movement, state);
                var latch = new InputLatch();

                assertFalse(sample(movement, player, latch).sprintHeld(), "Input sampling must wait for native approval");
                player.setSprinting(true);
                assertTrue(sample(movement, player, latch).sprintHeld());
                MovementSprint.setSprinting(player, false);
                assertTrue(sample(movement, player, latch).sprintHeld(), "Prediction/pose writes must not cancel native double-tap sprint");
                movement.beginNativeSprint(player);
                assertTrue(flag.get(), "Native continuation must see its last approved sprint decision");

                // A gun/stamina mod can stop sprint outside the native aiStep call sites.
                player.setSprinting(false);
                assertFalse(sample(movement, player, latch).sprintHeld());
                movement.beginNativeSprint(player);
                assertFalse(flag.get(), "The movement clock must not re-enable a mod-vetoed sprint");
                assertFalse(sample(movement, player, latch).sprintHeld());

                player.setSprinting(true);
                player.input.shiftKeyDown = true;
                var slide = sample(movement, player, latch);
                assertTrue(slide.crouchPressed() && slide.sprintHeld(), "Fresh crouch must retain approved sprint for slide entry");
                assertTrue(MovementSprint.apply(player, slide, state).sprintHeld());
                assertFalse(flag.get());
                assertTrue(sample(movement, player, latch).sprintHeld());
                movement.beginNativeSprint(player);
                assertFalse(sample(movement, player, latch).sprintHeld(), "Held crouch must stop sprint on the next native tick");
            } finally {
                movement.logout(null);
                MovementRuntime.installClient(previousBridge);
            }
        }
    }

    private static Field field(String name) throws ReflectiveOperationException {
        var field = ClientMovement.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static MovementInput sample(ClientMovement movement, LocalPlayer player, InputLatch latch) throws ReflectiveOperationException {
        var method = ClientMovement.class.getDeclaredMethod("sampleInput", LocalPlayer.class, long.class, InputLatch.class);
        method.setAccessible(true);
        return (MovementInput) method.invoke(movement, player, 1L, latch);
    }
}
