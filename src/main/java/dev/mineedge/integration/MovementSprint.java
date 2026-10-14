package dev.mineedge.integration;

import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

public final class MovementSprint {
    private static final ThreadLocal<Player> WRITING = new ThreadLocal<>();

    public static boolean allowed(Player player) {
        // LocalPlayer's native aiStep owns start/stop decisions. These common-side
        // checks also validate received commands, including modded flight capability.
        return player.canSprint() && (player.getFoodData().getFoodLevel() > 6 || player.mayFly())
                && !player.isUsingItem() && !player.hasEffect(MobEffects.BLINDNESS) && !player.isFallFlying()
                && TaczSprintCompatibility.allowed(player);
    }

    public static MovementInput validate(Player player, MovementInput input) {
        return input.withSprint(input.sprintHeld() && allowed(player));
    }

    public static MovementInput apply(Player player, MovementInput input, MovementState state) {
        input = validate(player, input);
        if (state.mode() == MovementState.Mode.HARD_LANDING) input = input.withSprint(false);
        boolean running = input.sprintHeld() && !input.crouchHeld() && input.forward() > 1.0E-5F;
        boolean accepted = setSprinting(player, running);
        // A setSprinting hook can veto the flag. Feed that decision back into the
        // controller as well, rather than retaining sprint acceleration without it.
        return running && !accepted ? input.withSprint(false) : input;
    }

    /** Simulation/presentation writes must not replace the native sprint decision. */
    public static boolean setSprinting(Player player, boolean sprinting) {
        Player previous = WRITING.get();
        WRITING.set(player);
        try {
            player.setSprinting(sprinting);
            return player.isSprinting();
        } finally {
            if (previous == null) WRITING.remove();
            else WRITING.set(previous);
        }
    }

    /** Also observe mod-driven stops outside LocalPlayer.aiStep (aiming, reload, stamina). */
    public static void changed(Player player) {
        if (WRITING.get() != player) MovementRuntime.sprintChanged(player);
    }

    private MovementSprint() {}
}
