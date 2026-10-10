package dev.mineedge.integration;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementVector;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovementSprintTest {
    private static Player player() {
        var player = mock(Player.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(player.canSprint()).thenReturn(true);
        when(player.getFoodData()).thenReturn(new FoodData());
        return player;
    }

    private static MovementInput input(boolean crouch) {
        return new MovementInput(1, 1, 0, 0, false, false, crouch, crouch, true, 0, 0);
    }

    private static MovementState state(MovementState.Mode mode) {
        return new MovementState(MovementVector.ZERO, new MovementVector(0, 0, 6), mode, 0.2, true, 0);
    }

    @Test void commonValidationHonorsFoodModdedFlightItemUseBlindnessElytraAndSprintCapability() {
        var player = player();
        assertTrue(MovementSprint.validate(player, input(false)).sprintHeld());
        player.getFoodData().setFoodLevel(6);
        assertFalse(MovementSprint.validate(player, input(false)).sprintHeld());
        when(player.mayFly()).thenReturn(true);
        assertTrue(MovementSprint.validate(player, input(false)).sprintHeld(), "Use NeoForge's flight capability, not the legacy abilities flag");
        when(player.isUsingItem()).thenReturn(true);
        assertFalse(MovementSprint.validate(player, input(false)).sprintHeld());
        when(player.isUsingItem()).thenReturn(false);
        when(player.hasEffect(MobEffects.BLINDNESS)).thenReturn(true);
        assertFalse(MovementSprint.validate(player, input(false)).sprintHeld());
        when(player.hasEffect(MobEffects.BLINDNESS)).thenReturn(false);
        when(player.isFallFlying()).thenReturn(true);
        assertFalse(MovementSprint.validate(player, input(false)).sprintHeld());
        when(player.isFallFlying()).thenReturn(false);
        when(player.canSprint()).thenReturn(false);
        assertFalse(MovementSprint.validate(player, input(false)).sprintHeld());
    }

    @Test void setterVetoReachesPhysicsButPoseWritesPreserveSlideIntent() {
        var player = player();
        var flag = new AtomicBoolean();
        var modAllows = new AtomicBoolean(true);
        when(player.isSprinting()).thenAnswer(call -> flag.get());
        doAnswer(call -> {
            flag.set((boolean) call.getArgument(0) && modAllows.get());
            return null;
        }).when(player).setSprinting(anyBoolean());

        assertTrue(MovementSprint.apply(player, input(false), state(MovementState.Mode.GROUND)).sprintHeld());
        assertTrue(flag.get());
        modAllows.set(false);
        assertFalse(MovementSprint.apply(player, input(false), state(MovementState.Mode.GROUND)).sprintHeld(),
                "A mod veto must remove sprint from the actual physics command");
        assertFalse(flag.get());
        modAllows.set(true);
        assertTrue(MovementSprint.apply(player, input(true), state(MovementState.Mode.GROUND)).sprintHeld(),
                "Crouching into a slide retains the approved sprint intent");
        assertFalse(flag.get(), "Slide intent must not give a crouching player the vanilla sprint modifier");
        assertFalse(MovementSprint.apply(player, input(false), state(MovementState.Mode.HARD_LANDING)).sprintHeld());
        assertFalse(flag.get());
    }
}
