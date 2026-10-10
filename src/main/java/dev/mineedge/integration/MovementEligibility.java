package dev.mineedge.integration;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;

public final class MovementEligibility {
    public static boolean supported(Player player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.noPhysics
                && !player.getAbilities().flying && !player.isPassenger() && !player.isSleeping()
                && !player.isFallFlying() && !player.isAutoSpinAttack() && !player.isSwimming()
                && !player.isInWater() && !player.isInLava() && !player.isInFluidType()
                && (!player.onClimbable() || MinecraftMovementSurfaces.managedClimbable(player)) && !player.isInPowderSnow
                && player.getForcedPose() == null
                && (player.getPose() == Pose.STANDING || player.getPose() == Pose.CROUCHING || player.getPose() == Pose.SWIMMING)
                && !player.hasEffect(MobEffects.LEVITATION) && !player.hasEffect(MobEffects.SLOW_FALLING)
                && !NeoForge.EVENT_BUS.post(new MovementControlEvent(player)).isCanceled();
    }

    private MovementEligibility() {}
}
