package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementFeatures;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DoorPushGameTests {
    private static final MovementController CONTROLLER = new MovementController();
    private static final BlockPos DOOR = new BlockPos(7, 1, 7);

    private static void prepare(GameTestHelper helper, DoorBlock block, Direction facing) {
        for (int x = 1; x <= 13; x++) for (int z = 1; z <= 13; z++)
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        var origin = helper.absolutePos(DOOR);
        for (int x = (origin.getX() >> 4) - 2; x <= (origin.getX() >> 4) + 2; x++)
            for (int z = (origin.getZ() >> 4) - 2; z <= (origin.getZ() >> 4) + 2; z++)
                helper.getLevel().getChunk(x, z);
        var state = block.defaultBlockState().setValue(DoorBlock.FACING, facing);
        // Place both halves without shape updates until the pair is complete.
        helper.getLevel().setBlock(origin, state, 18);
        helper.getLevel().setBlock(origin.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 18);
    }

    private static boolean open(GameTestHelper helper) {
        var lower = helper.getLevel().getBlockState(helper.absolutePos(DOOR));
        var upper = helper.getLevel().getBlockState(helper.absolutePos(DOOR.above()));
        helper.assertTrue(lower.getValue(DoorBlock.OPEN) == upper.getValue(DoorBlock.OPEN), "Door halves disagreed");
        return lower.getValue(DoorBlock.OPEN);
    }

    private static FakePlayer player(GameTestHelper helper, Vec3 position, float yaw) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeDoor"));
        player.setPos(helper.absoluteVec(position));
        player.setYRot(yaw);
        player.setOnGround(true);
        return player;
    }

    private static MovementState run(FakePlayer player, boolean sprint, boolean crouch, MovementTuning tuning, int ticks) {
        var state = MinecraftMotionAdapter.capture(player);
        for (int tick = 1; tick <= ticks; tick++) {
            var input = new MovementInput(tick, 1, 0, 0, false, false, crouch, false, sprint, player.getYRot(), 0);
            state = CONTROLLER.tick(state, input, tuning, MinecraftMotionAdapter.live(player), event -> {});
        }
        return state;
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void sprintOpensBothHalvesFromEitherSideInEveryOrientation(GameTestHelper helper) {
        for (var facing : Direction.Plane.HORIZONTAL) {
            for (int sign : new int[]{-1, 1}) {
                prepare(helper, (DoorBlock) Blocks.OAK_DOOR, facing);
                double planeX = facing == Direction.EAST ? 7.09375 : facing == Direction.WEST ? 7.90625 : 7.5;
                double planeZ = facing == Direction.SOUTH ? 7.09375 : facing == Direction.NORTH ? 7.90625 : 7.5;
                var start = new Vec3(planeX - facing.getStepX() * sign * 1.2, 1, planeZ - facing.getStepZ() * sign * 1.2);
                var player = player(helper, start, sign == 1 ? facing.toYRot() : facing.getOpposite().toYRot());
                var state = run(player, true, false, MovementTuning.DEFAULT, 30);
                helper.assertTrue(open(helper), "Sprint did not open " + facing + " door from side " + sign);
                var traveled = state.position().subtract(MinecraftMotionAdapter.core(helper.absoluteVec(start)));
                helper.assertTrue((traveled.x() * facing.getStepX() + traveled.z() * facing.getStepZ()) * sign > 1.8,
                        "Runner did not pass through the opened door");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void toggleWalkingCrouchingAndIronDoorsPreventPush(GameTestHelper helper) {
        var disabled = MovementTuning.DEFAULT.withFeatures(new MovementFeatures(true, true, true, true, true, false));
        for (int scenario = 0; scenario < 4; scenario++) {
            prepare(helper, (DoorBlock) (scenario == 3 ? Blocks.IRON_DOOR : Blocks.OAK_DOOR), Direction.SOUTH);
            var player = player(helper, new Vec3(7.5, 1, 5.9), 0);
            run(player, scenario != 1, scenario == 2, scenario == 0 ? disabled : MovementTuning.DEFAULT, 40);
            helper.assertTrue(!open(helper), "Door opened in blocked scenario " + scenario);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void nearbyDoorAndDoorBehindWallStayClosed(GameTestHelper helper) {
        prepare(helper, (DoorBlock) Blocks.OAK_DOOR, Direction.SOUTH);
        run(player(helper, new Vec3(6.3, 1, 5.9), 0), true, false, MovementTuning.DEFAULT, 30);
        helper.assertTrue(!open(helper), "Sprinting past a nearby door opened it");
        helper.setBlock(new BlockPos(7, 1, 6), Blocks.STONE);
        helper.setBlock(new BlockPos(7, 2, 6), Blocks.STONE);
        run(player(helper, new Vec3(7.5, 1, 5.5), 0), true, false, MovementTuning.DEFAULT, 30);
        helper.assertTrue(!open(helper), "Push opened a door through a wall");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void replayDoesNotOpenDoorsOrMutateItsOwner(GameTestHelper helper) {
        prepare(helper, (DoorBlock) Blocks.OAK_DOOR, Direction.SOUTH);
        var player = player(helper, new Vec3(7.5, 1, 6.6), 0);
        var baseline = MinecraftMotionAdapter.capture(player).withVelocity(new MovementVector(0, 0, 6));
        var ownerPosition = player.position();
        var input = new MovementInput(1, 1, 0, 0, false, false, false, false, true, 0, 0);
        CONTROLLER.tick(baseline, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.replay(player), event -> {});
        helper.assertTrue(!open(helper), "Replay changed the door state");
        helper.assertTrue(player.position().equals(ownerPosition), "Replay moved its owner");
        CONTROLLER.tick(baseline, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(player), event -> {});
        helper.assertTrue(open(helper), "Live server movement did not push the contacted door");
        helper.succeed();
    }

    private DoorPushGameTests() {}
}
