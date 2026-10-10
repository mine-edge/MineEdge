package dev.mineedge.integration;

import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.landing.LandingController;
import dev.mineedge.movement.collision.CollisionMover;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Only the adapter's SELF move may replace Entity.collide; piston/push and nested callbacks stay vanilla. */
public final class ResolvedMovement {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private static final class Context {
        final Player player;
        boolean consumed;
        boolean fallConsumed;
        CollisionMover.Result result;
        LandingState landing;
        MovementTuning tuning;
        double impact;
        boolean fallDamageConsumed;
        boolean ledgeGrab;
        CollisionQueries queries;
        double seconds;

        Context(Player player) { this.player = player; }
    }

    public static CollisionMover.Result run(Player player, Runnable move) {
        return run(player, null, null, 0, move);
    }

    public static CollisionMover.Result run(Player player, LandingState landing, MovementTuning tuning, double impact, Runnable move) {
        return run(player, landing, tuning, impact, null, move);
    }

    public static CollisionMover.Result run(Player player, LandingState landing, MovementTuning tuning, double impact,
                                            CollisionQueries queries, Runnable move) {
        return run(player, landing, tuning, impact, queries, MovementTuning.TICK_SECONDS, move);
    }

    public static CollisionMover.Result run(Player player, LandingState landing, MovementTuning tuning, double impact,
                                            CollisionQueries queries, double seconds, Runnable move) {
        Context previous = CURRENT.get();
        Context context = new Context(player);
        context.landing = landing;
        context.tuning = tuning;
        context.impact = impact;
        context.queries = queries;
        context.seconds = seconds;
        CURRENT.set(context);
        try {
            move.run();
            return context.result;
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** A grab stops a fall without a floor contact or landing-roll mitigation. */
    public static void ledgeGrab(Player player, float distance, MovementTuning tuning) {
        run(player, null, tuning, 0, () -> {
            CURRENT.get().ledgeGrab = true;
            player.causeFallDamage(distance, 1, player.damageSources().fall());
        });
    }

    /** Only the first fall callback of an eligible authoritative contact receives MineEdge's fall rules.
     * Vanilla still computes damage, block modifiers, armor/enchantments and effects. */
    public static void onFall(LivingFallEvent event) {
        Context context = CURRENT.get();
        if (context == null || context.player != event.getEntity() || context.fallDamageConsumed
                || context.tuning == null || !context.ledgeGrab && (context.landing == null || context.result == null
                || context.result.blockedByOverlap() || !context.result.grounded()) || !(context.player instanceof ServerPlayer player)
                || player.level().isClientSide() || !MovementRuntime.session(player).active
                || !MovementEligibility.supported(player)) return;
        context.fallDamageConsumed = true;
        // Offset vanilla's baseline safe distance without replacing attribute/Jump Boost modifiers
        // or the physical fall history used by prediction and landing animations.
        event.setDistance(event.getDistance()
                + (float) (Entity.BASE_SAFE_FALL_DISTANCE - context.tuning.minimumFallHeight()));
        event.setDamageMultiplier(event.getDamageMultiplier() * (float) context.tuning.fallDamageMultiplier());
        if (context.ledgeGrab) return;
        var floor = player.level().getBlockState(player.getOnPos()).getBlock();
        if (!player.isShiftKeyDown() && (floor == Blocks.SLIME_BLOCK || floor instanceof BedBlock)) return;
        MovementVector position = MinecraftMotionAdapter.core(player.position());
        boolean clearance = context.queries == null ? MinecraftCollisionWorld.canOccupy(player, position, MovementBody.LOW)
                : context.queries.canOccupy(position, MovementBody.LOW);
        if (LandingController.canRoll(context.landing, position, context.impact, clearance)) {
            event.setDamageMultiplier(event.getDamageMultiplier() * (float) (1 - context.tuning.rollDamageReduction()));
        }
    }

    public static boolean active(Entity entity) {
        Context context = CURRENT.get();
        return context != null && context.player == entity;
    }

    public static double seconds(Entity entity) {
        Context context = CURRENT.get();
        return context != null && context.player == entity ? context.seconds : MovementTuning.TICK_SECONDS;
    }

    public static CollisionMover.Result takeFallResult(Entity entity) {
        Context context = CURRENT.get();
        if (context == null || context.player != entity || context.fallConsumed) {
            return null;
        }
        context.fallConsumed = true;
        return context.result;
    }

    public static Vec3 collide(Entity entity, Vec3 requested) {
        Context context = CURRENT.get();
        if (context == null || context.player != entity || context.consumed) {
            return null;
        }
        context.consumed = true;
        Player player = context.player;
        MovementVector delta = MinecraftMotionAdapter.core(requested);
        var result = context.queries == null ? MinecraftCollisionWorld.resolve(player, MinecraftMotionAdapter.core(player.position()), delta)
                : context.queries.resolve(MinecraftCollisionWorld.body(player, MinecraftMotionAdapter.core(player.position())), delta);
        context.result = result;
        if (!result.recovery().equals(MovementVector.ZERO)) {
            player.setPos(player.position().add(MinecraftMotionAdapter.minecraft(result.recovery())));
        }
        if (result.blockedByOverlap()) {
            player.setDeltaMovement(Vec3.ZERO);
        }
        return MinecraftMotionAdapter.minecraft(result.displacement());
    }

    private ResolvedMovement() {}
}
