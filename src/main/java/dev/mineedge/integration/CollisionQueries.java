package dev.mineedge.integration;

import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.BoxWorld;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionMover;
import dev.mineedge.movement.collision.CollisionWorld;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/** Four bounded, controller-tick-local static block broad phases. Never caches entity or border contacts. */
public final class CollisionQueries {
    private static final int CAPACITY = 4;
    private final Player player;
    private double stepHeight;
    private boolean grounded;
    private final Entry[] entries = new Entry[CAPACITY];
    private int next;
    private Level level;
    private long tick;

    public CollisionQueries(Player player) { this.player = player; }
    public void stepHeight(double value) { stepHeight = Math.max(0, value); }
    public void grounded(boolean value) { grounded = value; }
    public void begin() {
        Arrays.fill(entries, null);
        next = 0;
        level = player.level();
        tick = level.getGameTime();
    }
    public boolean canOccupy(MovementVector feet, MovementBody body) {
        CollisionBox bounds = MinecraftCollisionWorld.bounds(player, feet, body);
        var world = query(bounds.swept(MovementVector.ZERO, BoxWorld.TOUCH), true);
        return world != null && !world.overlaps(bounds);
    }
    public CollisionWorld geometry(CollisionBox region) { return query(region, false); }
    public CollisionMover.Result resolve(MovementVector feet, MovementVector delta, MovementBody body) {
        return resolve(MinecraftCollisionWorld.bounds(player, feet, body), delta);
    }
    public CollisionMover.Result resolve(CollisionBox bounds, MovementVector delta) {
        var world = query(bounds.swept(delta, CollisionMover.MAX_RECOVERY + BoxWorld.TOUCH)
                .swept(new MovementVector(0, stepHeight, 0), 0), true);
        return world == null ? new CollisionMover.Result(MovementVector.ZERO, MovementVector.ZERO, List.of(), true)
                : CollisionMover.move(world, bounds, delta, stepHeight, grounded);
    }
    private BoxWorld query(CollisionBox bounds, boolean entities) {
        if (level != player.level() || tick != player.level().getGameTime()) begin();
        MovementProfile.query();
        AABB requested = MinecraftCollisionWorld.minecraft(bounds);
        if (!MinecraftCollisionWorld.loaded(player, requested)) return null;
        for (Entry entry : entries) {
            if (entry != null && contains(entry.region, requested) && entry.valid(level)) {
                MovementProfile.hit();
                return MinecraftCollisionWorld.supplement(player, requested, entry.world, entities);
            }
        }
        AABB expanded = new AABB(Math.floor(requested.minX) - 0.75, Math.floor(requested.minY) - 0.75, Math.floor(requested.minZ) - 0.75,
                Math.ceil(requested.maxX) + 0.75, Math.ceil(requested.maxY) + 0.75, Math.ceil(requested.maxZ) + 0.75);
        // Extreme imported velocity stays on the existing bounded resolver; cache size never follows it.
        boolean bounded = expanded.getXsize() <= 20 && expanded.getYsize() <= 20 && expanded.getZsize() <= 20
                && expanded.getXsize() * expanded.getYsize() * expanded.getZsize() <= 8192;
        Entry entry = bounded && MinecraftCollisionWorld.loaded(player, expanded) ? Entry.inspect(level, expanded) : null;
        if (entry == null) return MinecraftCollisionWorld.supplement(player, requested,
                MinecraftCollisionWorld.gatherBlocks(player, requested), entities);
        entry.world = MinecraftCollisionWorld.gatherBlocks(player, expanded);
        if (entry.world != null) entries[next++ % CAPACITY] = entry;
        return MinecraftCollisionWorld.supplement(player, requested, entry.world, entities);
    }
    private static boolean contains(AABB outer, AABB inner) {
        return inner.minX >= outer.minX && inner.minY >= outer.minY && inner.minZ >= outer.minZ
                && inner.maxX <= outer.maxX && inner.maxY <= outer.maxY && inner.maxZ <= outer.maxZ;
    }
    private static final class Entry {
        final AABB region;
        final int minChunkX, minChunkZ, countZ;
        final LevelChunk[] chunks;
        final long[] revisions;
        BoxWorld world;
        Entry(AABB region, int minChunkX, int minChunkZ, int countZ, LevelChunk[] chunks, long[] revisions) {
            this.region = region;
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.countZ = countZ;
            this.chunks = chunks;
            this.revisions = revisions;
        }
        static Entry inspect(Level level, AABB region) {
            int minX = Mth.floor(region.minX - 1), maxX = Mth.floor(region.maxX + 1);
            int minZ = Mth.floor(region.minZ - 1), maxZ = Mth.floor(region.maxZ + 1);
            int minCX = minX >> 4, minCZ = minZ >> 4, countZ = (maxZ >> 4) - minCZ + 1;
            LevelChunk[] chunks = new LevelChunk[((maxX >> 4) - minCX + 1) * countZ];
            long[] revisions = new long[chunks.length];
            for (int i = 0; i < chunks.length; i++) {
                var getter = level.getChunkForCollisions(minCX + i / countZ, minCZ + i % countZ);
                if (!(getter instanceof LevelChunk chunk)) return null;
                chunks[i] = chunk;
                revisions[i] = ((ChunkCollisionRevision) chunk).mineedge$collisionRevision();
            }
            // Context-sensitive shapes and block entities (notably moving pistons) are gathered afresh.
            // Only vanilla non-dynamic blocks use the static-shape cache; unknown mod blocks opt out.
            for (BlockPos pos : BlockPos.betweenClosed(minX, Mth.floor(region.minY - 1), minZ, maxX, Mth.floor(region.maxY + 1), maxZ)) {
                var state = level.getBlockState(pos);
                if (!state.isAir() && (state.hasBlockEntity() || state.getBlock().hasDynamicShape()
                        || state.getBlock() instanceof LiquidBlock || state.getBlock() instanceof ScaffoldingBlock
                        || state.getBlock() instanceof PowderSnowBlock
                        || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")
                        || !state.getBlock().getClass().getPackageName().equals("net.minecraft.world.level.block"))) return null;
            }
            return new Entry(region, minCX, minCZ, countZ, chunks, revisions);
        }
        boolean valid(Level level) {
            for (int i = 0; i < chunks.length; i++) {
                var chunk = level.getChunkForCollisions(minChunkX + i / countZ, minChunkZ + i % countZ);
                if (chunk != chunks[i] || ((ChunkCollisionRevision) chunks[i]).mineedge$collisionRevision() != revisions[i]) return false;
            }
            return true;
        }
    }
}
