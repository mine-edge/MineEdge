package dev.mineedge.client.movement;

import dev.mineedge.client.config.MineEdgeClientConfig;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.surface.ClimbRoute;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;

/** Local live-movement audio; replay and server corrections never advance the sound cadence. */
public final class MovementSounds {
    private static double sidewaysDistance;

    public static void reset() { sidewaysDistance = 0; }

    private static boolean enabled() {
        Minecraft mc = Minecraft.getInstance();
        return MineEdgeClientConfig.MOVEMENT_SOUNDS.get() && mc.player != null && !mc.isPaused()
                && mc.screen == null && mc.isWindowActive() && ClientMovement.INSTANCE.presentationState() != null;
    }

    public static void event(MovementEvent event) {
        if (!enabled()) return;
        if (event == MovementEvent.CLIMB_STEP) {
            sidewaysDistance = 0;
            climb(ClientMovement.INSTANCE.presentationState());
            return;
        }
        var sound = switch (event) {
            case SLIDE_START, ROLL_START, BACK_ROLL -> SoundEvents.PLAYER_ATTACK_SWEEP;
            case LEDGE_GRAB, WALL_JUMP, WALL_KICK, SPRINGBOARD_START -> SoundEvents.PLAYER_SMALL_FALL;
            default -> null;
        };
        if (sound != null) Minecraft.getInstance().player.playSound(sound, 0.25F, 0.8F);
    }

    public static void movement(MovementState previous, MovementState current) {
        if (!enabled() || previous.surfaces().climb() == null || current.surfaces().climb() == null
                || previous.surfaces().path() != null || current.surfaces().path() != null) {
            reset();
            return;
        }
        sidewaysDistance += current.position().subtract(previous.position()).horizontalSpeed();
        if (sidewaysDistance + 1.0E-9 >= ClimbRoute.STEP) {
            sidewaysDistance = Math.max(0, sidewaysDistance - ClimbRoute.STEP);
            climb(current);
        }
    }

    private static void climb(MovementState state) {
        var grip = state.surfaces().climb();
        if (grip == null) return;
        var route = grip.route();
        var source = route.source();
        BlockPos pos = BlockPos.containing(source.x(), Math.clamp(state.position().y(), source.y(), route.top() - 1.0E-7), source.z());
        var player = Minecraft.getInstance().player;
        var block = player.level().getBlockState(pos);
        if (block.isAir()) return;
        var type = block.getSoundType(player.level(), pos, player);
        player.playSound(type.getStepSound(), type.getVolume() * 0.25F, type.getPitch() * 0.8F);
    }

    private MovementSounds() {}
}
