package dev.mineedge.integration;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Posted on NeoForge's game event bus when MineEdge checks control of an otherwise
 * supported player. Cancel while another mod needs native travel, jumping and poses.
 * Fired on both logical sides; listeners should make the same decision on both sides.
 * This is a query and may run several times per tick: do not mutate the player or
 * call MovementRuntime.owns/MovementEligibility.supported from a listener.
 */
public final class MovementControlEvent extends PlayerEvent implements ICancellableEvent {
    public MovementControlEvent(Player player) {
        super(player);
    }
}
