package dev.mineedge.integration;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

public final class MovementSprint {
    public static boolean allowed(Player player) {
        return (player.getFoodData().getFoodLevel() > 6 || player.getAbilities().mayfly)
                && !player.isUsingItem() && !player.hasEffect(MobEffects.BLINDNESS);
    }

    public static MovementInput validate(Player player, MovementInput input) {
        return input.withSprint(input.sprintHeld() && allowed(player));
    }

    public static void apply(Player player, MovementInput input, MovementState state) {
        player.setSprinting(state.mode() != MovementState.Mode.HARD_LANDING
                && input.sprintHeld() && !input.crouchHeld() && input.forward() > 0.7 && allowed(player));
    }

    private MovementSprint() {}
}
