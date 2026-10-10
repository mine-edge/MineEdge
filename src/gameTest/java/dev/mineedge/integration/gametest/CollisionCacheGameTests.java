package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.CollisionQueries;
import dev.mineedge.integration.ChunkCollisionRevision;
import dev.mineedge.integration.MinecraftCollisionWorld;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementProfile;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementVector;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CollisionCacheGameTests {
    private static FakePlayer player(GameTestHelper helper) {
        for (int x = 1; x <= 13; x++) for (int z = 1; z <= 13; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        var origin = helper.absolutePos(new BlockPos(7, 0, 7));
        for (int x = (origin.getX() >> 4) - 2; x <= (origin.getX() >> 4) + 2; x++)
            for (int z = (origin.getZ() >> 4) - 2; z <= (origin.getZ() >> 4) + 2; z++) helper.getLevel().getChunk(x, z);
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeCache"));
    }
    private static MovementVector position(GameTestHelper helper, double x, double y, double z) {
        return MinecraftMotionAdapter.core(helper.absoluteVec(new Vec3(x, y, z)));
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void cachedShapesMatchUncachedAcrossSubstepsAndPoses(GameTestHelper helper) {
        var player = player(helper);
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE_SLAB);
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.OAK_STAIRS);
        helper.setBlock(new BlockPos(6, 1, 5), Blocks.OAK_FENCE);
        helper.setBlock(new BlockPos(7, 1, 5), Blocks.OAK_TRAPDOOR);
        var cache = new CollisionQueries(player);
        cache.begin();
        var sample = MovementProfile.begin();
        try {
            for (int step = 0; step < 6; step++) for (var body : MovementBody.values()) {
                var feet = position(helper, 4.5 + step * 0.02, 1.1, 4.5);
                var delta = new MovementVector(0.1, -0.2, 1);
                var actual = cache.resolve(feet, delta, body);
                var expected = MinecraftCollisionWorld.resolve(player, feet, delta, body);
                helper.assertTrue(actual.equals(expected), "Cached collision changed contacts/recovery for " + body);
            }
            helper.assertTrue(sample.cacheHits >= 15, "Static shapes were not reused across substeps/poses");
        } finally { MovementProfile.end(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void sameTickBlockAndDirectChunkWritesInvalidateCachedGeometry(GameTestHelper helper) {
        var player = player(helper);
        var feet = position(helper, 4.5, 1, 4.5);
        var delta = new MovementVector(0, 0, 1);
        var cache = new CollisionQueries(player);
        cache.begin();
        helper.assertTrue(cache.resolve(feet, delta, MovementBody.STANDING).displacement().z() == 1, "Initial route blocked");
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
        var blocked = cache.resolve(feet, delta, MovementBody.STANDING);
        helper.assertTrue(blocked.displacement().z() < 0.21, "In-tick block placement reused stale clearance");
        var pos = helper.absolutePos(new BlockPos(4, 1, 5));
        var chunk = helper.getLevel().getChunkAt(pos);
        long before = ((ChunkCollisionRevision) chunk).mineedge$collisionRevision();
        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
        helper.assertTrue(((ChunkCollisionRevision) chunk).mineedge$collisionRevision() != before, "Direct chunk hook did not invalidate");
        helper.assertTrue(cache.resolve(feet, delta, MovementBody.STANDING).displacement().z() == 1, "Removed support stayed cached");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void contextSensitiveShapesAreRecomputedWhenCrouchChanges(GameTestHelper helper) {
        var player = player(helper);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.SCAFFOLDING);
        var feet = position(helper, 4.5, 2.1, 4.5);
        player.setPos(MinecraftMotionAdapter.minecraft(feet));
        var cache = new CollisionQueries(player);
        cache.begin();
        var delta = new MovementVector(0, -0.3, 0);
        var sample = MovementProfile.begin();
        try {
            var standing = cache.resolve(feet, delta, MovementBody.STANDING);
            player.setShiftKeyDown(true);
            var descending = cache.resolve(feet, delta, MovementBody.STANDING);
            helper.assertTrue(standing.displacement().y() > -0.2 && descending.displacement().y() < -0.29,
                    "Scaffolding ignored the changed collision context");
            helper.assertTrue(descending.equals(MinecraftCollisionWorld.resolve(player, feet, delta, MovementBody.STANDING))
                    && sample.cacheHits == 0, "Context-sensitive collision was cached");
        } finally { MovementProfile.end(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void movingPistonProgressIsNeverCached(GameTestHelper helper) {
        var player = player(helper);
        var relative = new BlockPos(4, 1, 5);
        var pos = helper.absolutePos(relative);
        var state = Blocks.MOVING_PISTON.defaultBlockState().setValue(MovingPistonBlock.FACING, Direction.SOUTH);
        helper.setBlock(relative, state);
        var piston = new PistonMovingBlockEntity(pos, state, Blocks.STONE.defaultBlockState(), Direction.SOUTH, true, false);
        helper.getLevel().setBlockEntity(piston);
        var cache = new CollisionQueries(player);
        cache.begin();
        var feet = position(helper, 4.5, 1, 3.5);
        var delta = new MovementVector(0, 0, 2);
        var sample = MovementProfile.begin();
        try {
            var before = cache.resolve(feet, delta, MovementBody.STANDING);
            PistonMovingBlockEntity.tick(helper.getLevel(), pos, state, piston);
            var after = cache.resolve(feet, delta, MovementBody.STANDING);
            helper.assertTrue(after.displacement().z() > before.displacement().z() + 0.4, "Moving piston retained stale shape progress");
            helper.assertTrue(after.equals(MinecraftCollisionWorld.resolve(player, feet, delta, MovementBody.STANDING)), "Piston cache fallback differed");
            helper.assertTrue(sample.cacheHits == 0, "Dynamic block entity was cached");
        } finally { MovementProfile.end(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void entityMotionAndBorderChangesStayFresh(GameTestHelper helper) {
        var player = player(helper);
        var feet = position(helper, 4.5, 1, 4.5);
        player.setPos(MinecraftMotionAdapter.minecraft(feet));
        var boatPosition = position(helper, 4.5, 1, 11);
        var boat = new Boat(helper.getLevel(), boatPosition.x(), boatPosition.y(), boatPosition.z());
        helper.getLevel().addFreshEntity(boat);
        var cache = new CollisionQueries(player);
        cache.begin();
        try {
            var delta = new MovementVector(0, 0, 1);
            helper.assertTrue(cache.resolve(feet, delta, MovementBody.STANDING).displacement().z() == 1, "Far boat blocked route");
            boat.setPos(MinecraftMotionAdapter.minecraft(position(helper, 4.5, 1, 5.5)));
            helper.assertTrue(cache.resolve(feet, delta, MovementBody.STANDING).displacement().z() < 0.5, "Entity movement reused stale broad phase");
        } finally { boat.discard(); }
        var border = helper.getLevel().getWorldBorder();
        double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(feet.x(), feet.z());
            border.setSize(10);
            var delta = new MovementVector(0, 0, 3);
            helper.assertTrue(cache.resolve(feet, delta, MovementBody.STANDING).displacement().z() == 3, "Wide border blocked route");
            border.setSize(4);
            var narrow = cache.resolve(feet, delta, MovementBody.STANDING);
            helper.assertTrue(narrow.displacement().z() < 3 && narrow.equals(MinecraftCollisionWorld.resolve(player, feet, delta, MovementBody.STANDING)),
                    "Shrinking border reused stale clipping");
        } finally { border.setCenter(x, z); border.setSize(size); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void cacheNeverLoadsAbsentChunks(GameTestHelper helper) {
        var player = player(helper);
        var cache = new CollisionQueries(player);
        cache.begin();
        var feet = position(helper, 4.5, 1, 4.5);
        cache.resolve(feet, new MovementVector(0, 0, 0.1), MovementBody.STANDING);
        var absent = feet.add(new MovementVector(4096, 0, 4096));
        int cx = ((int) Math.floor(absent.x())) >> 4, cz = ((int) Math.floor(absent.z())) >> 4;
        helper.assertTrue(!helper.getLevel().hasChunk(cx, cz), "Absent fixture chunk was already loaded");
        var blocked = cache.resolve(absent, new MovementVector(0, 0, 0.1), MovementBody.STANDING);
        helper.assertTrue(blocked.blockedByOverlap() && !helper.getLevel().hasChunk(cx, cz), "Cache query loaded an absent chunk");
        helper.succeed();
    }
}
