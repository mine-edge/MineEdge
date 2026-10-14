package dev.mineedge.client.input;

import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VanillaSprintTest {
    @Test void nativeStartPredicateRetainsItsForwardThresholdAndRestrictions() throws ReflectiveOperationException {
        var player = mock(LocalPlayer.class);
        player.input = new Input();
        var food = new FoodData();
        when(player.getFoodData()).thenReturn(food);
        when(player.level()).thenReturn(mock(Level.class));
        var predicate = LocalPlayer.class.getDeclaredMethod("canStartSprinting");
        predicate.setAccessible(true);

        player.input.forwardImpulse = 0.79F;
        assertFalse((boolean) predicate.invoke(player));
        player.input.forwardImpulse = 1;
        assertTrue((boolean) predicate.invoke(player));
        food.setFoodLevel(6);
        assertFalse((boolean) predicate.invoke(player));
        when(player.mayFly()).thenReturn(true);
        assertTrue((boolean) predicate.invoke(player));
        when(player.isUsingItem()).thenReturn(true);
        assertFalse((boolean) predicate.invoke(player));
        when(player.isUsingItem()).thenReturn(false);
        when(player.hasEffect(MobEffects.BLINDNESS)).thenReturn(true);
        assertFalse((boolean) predicate.invoke(player));
        when(player.hasEffect(MobEffects.BLINDNESS)).thenReturn(false);
        when(player.isFallFlying()).thenReturn(true);
        assertFalse((boolean) predicate.invoke(player));
        when(player.isFallFlying()).thenReturn(false);
        when(player.isSprinting()).thenReturn(true);
        assertFalse((boolean) predicate.invoke(player), "Starting and continuing sprint are different native decisions");
    }
}
