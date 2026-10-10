package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.MinecraftCollisionWorld;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.MovementEligibility;
import dev.mineedge.integration.MovementPoses;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.wall.WallState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LandingGameTests {
    private static final MovementController CONTROLLER = new MovementController();
    private static final class TestPlayer extends FakePlayer {
        int falls;
        TestPlayer(GameTestHelper helper) {
            super(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeLanding"));
            // Pinned 1.21.1 spawn grace prevents damage in GameTestServer (not a dedicated PvP server).
            // Test-only reflection avoids adding a runtime mixin solely for fixture setup.
            try {
                var grace = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
                grace.setAccessible(true);
                grace.setInt(this, 0);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot clear test player's spawn grace", exception);
            }
        }
        // NeoForge's FakePlayer is always invulnerable; this fixture exercises actual vanilla damage.
        @Override public boolean isInvulnerableTo(DamageSource source) { return false; }
        @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
            if (distance > 3) falls++;
            return super.causeFallDamage(distance, multiplier, source);
        }
        void updatePose() { super.updatePlayerPose(); }
        void refreshFluids() { super.updateInWaterStateAndDoFluidPushing(); }
    }
    private record Result(MovementState state, List<MovementEvent> events, float damage, int falls) {}
    private static MovementVector position(GameTestHelper helper, double x, double y, double z) {
        return MinecraftMotionAdapter.core(helper.absoluteVec(new Vec3(x, y, z)));
    }
    private static void floor(GameTestHelper helper, Block block) {
        for (int x = 1; x <= 13; x++) for (int z = 1; z <= 13; z++) helper.setBlock(new BlockPos(x, 0, z), block);
        BlockPos origin = helper.absolutePos(new BlockPos(7, 0, 7));
        for (int x = (origin.getX() >> 4) - 2; x <= (origin.getX() >> 4) + 2; x++)
            for (int z = (origin.getZ() >> 4) - 2; z <= (origin.getZ() >> 4) + 2; z++) helper.getLevel().getChunk(x, z);
    }
    private static MovementInput input(boolean press, boolean held, boolean jump) {
        return new MovementInput(1, 1, 0, 0, jump, jump, held, press, true, 0, 0);
    }
    private static MovementState imminent(GameTestHelper helper, double fall) {
        var feet = position(helper, 4.5, 1.1, 3.5);
        return new MovementState(feet, new MovementVector(0, -12, 6), MovementState.Mode.AIR, 1, false, (float) (fall - 0.1),
                LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, WallState.EMPTY,
                new LandingState(position(helper, 0, 1 + fall, 0).y(), fall - 0.1, 0, LandingState.Source.OTHER, 0, 60, 60, true));
    }
    private static MovementTuning tuning(double reduction) {
        return tuning(reduction, 1, 3);
    }
    private static MovementTuning tuning(double reduction, double multiplier, double minimumHeight) {
        var base = MovementTuning.DEFAULT;
        return new MovementTuning(base.walkSpeed(), base.jumpSpeed(), base.gravity(), base.groundAcceleration(),
                base.airAcceleration(), base.staleInputTicks(), base.crouchSpeedModifier(), reduction,
                base.runSpeedMultiplier(), multiplier, minimumHeight);
    }
    private static Result compare(GameTestHelper helper, MovementState initial, int ticks, double reduction,
                                   BiFunction<Integer, MovementState, MovementInput> commands, Consumer<TestPlayer> setup) {
        return compare(helper, initial, ticks, tuning(reduction), commands, setup);
    }
    private static Result compare(GameTestHelper helper, MovementState initial, int ticks, MovementTuning tuning,
                                   BiFunction<Integer, MovementState, MovementInput> commands, Consumer<TestPlayer> setup) {
        var live = new TestPlayer(helper);
        var owner = new TestPlayer(helper);
        setup.accept(live);
        setup.accept(owner);
        MinecraftMotionAdapter.apply(live, initial);
        MinecraftMotionAdapter.apply(owner, initial);
        var session = MovementRuntime.session(live);
        session.active = true;
        session.tuning = tuning;
        session.state = initial;
        var actual = initial;
        var predicted = initial;
        MovementState baseline = null;
        var saved = new ArrayList<MovementInput>();
        var events = new ArrayList<MovementEvent>();
        for (int tick = 0; tick < ticks; tick++) {
            var input = commands.apply(tick, actual);
            saved.add(input);
            actual = CONTROLLER.tick(actual, input, session.tuning, MinecraftMotionAdapter.live(live), events::add);
            session.state = actual;
            live.updatePose();
            predicted = CONTROLLER.tick(predicted, input, session.tuning, MinecraftMotionAdapter.replay(owner), e -> {});
            helper.assertTrue(actual.position().subtract(predicted.position()).length() < 1.0E-6, "Landing position diverged at " + tick);
            helper.assertTrue(actual.velocity().subtract(predicted.velocity()).length() < 1.0E-6, "Landing velocity diverged at " + tick);
            helper.assertTrue(actual.mode() == predicted.mode() && actual.body() == predicted.body(), "Landing mode/body diverged");
            helper.assertTrue(Math.abs(actual.landing().apex() - predicted.landing().apex()) < 1.0E-6
                    && Math.abs(actual.landing().distance() - predicted.landing().distance()) < 1.0E-6
                    && Math.abs(actual.landing().impact() - predicted.landing().impact()) < 1.0E-6
                    && actual.landing().source() == predicted.landing().source()
                    && actual.landing().triggerAge() == predicted.landing().triggerAge()
                    && actual.landing().retriggerAge() == predicted.landing().retriggerAge(), "Landing replay history diverged");
            helper.assertTrue(Math.abs(actual.fallDistance() - predicted.fallDistance()) < 1.0E-5, "Native fall accounting diverged");
            helper.assertTrue(MinecraftCollisionWorld.canOccupy(live, actual.position(), actual.body()), "Landing/roll clipped fixture");
            helper.assertTrue(live.getPose() == MovementPoses.pose(actual.body()), "Native landing pose changed");
            if (tick == 2) baseline = actual;
        }
        if (baseline != null) {
            for (int i = 3; i < saved.size(); i++) baseline = CONTROLLER.tick(baseline, saved.get(i), session.tuning,
                    MinecraftMotionAdapter.replay(owner), e -> {});
            helper.assertTrue(actual.position().subtract(baseline.position()).length() < 1.0E-6
                    && actual.mode() == baseline.mode(), "Acknowledged landing baseline did not replay");
        }
        helper.assertTrue(owner.position().equals(MinecraftMotionAdapter.minecraft(initial.position())) && owner.getHealth() == 20 && owner.falls == 0,
                "Replay moved or damaged its owner");
        return new Result(actual, events, 20 - live.getHealth(), live.falls);
    }
    private static Result compare(GameTestHelper helper, MovementState initial, int ticks, double reduction,
                                    BiFunction<Integer, MovementState, MovementInput> commands) {
        return compare(helper, initial, ticks, reduction, commands, player -> {});
    }

    private static void ledge(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        for (int x = 3; x <= 7; x++) for (int y = 1; y <= 4; y++) for (int z = 5; z <= 10; z++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
    }
    private static MovementState fallingAtLedge(GameTestHelper helper, double fall) {
        return new MovementState(position(helper, 4.5, 3.2, 4.65), new MovementVector(0, -1, 1),
                MovementState.Mode.AIR, 1, false, (float) fall);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ledgeGrabSettlesFallOnceAndResetsHistoryForDrop(GameTestHelper helper) {
        ledge(helper);
        var initial = fallingAtLedge(helper, 8.9);
        for (double multiplier : new double[]{0, 0.5, 1, 2}) {
            var grabbed = compare(helper, initial, 20, tuning(1, multiplier, 5),
                    (tick, state) -> MovementInput.idle(tick + 1, 0, 0), player -> {});
            helper.assertTrue(grabbed.state().mode() == MovementState.Mode.LEDGE_HANG
                    && grabbed.events().stream().filter(event -> event == MovementEvent.LEDGE_GRAB).count() == 1,
                    "Fall fixture did not grab and remain hanging");
            helper.assertTrue(grabbed.damage() == 4 * multiplier && grabbed.falls() == 1,
                    "Grab did not apply configured fall damage exactly once: " + multiplier + " / " + grabbed.damage());
            helper.assertTrue(grabbed.state().fallDistance() == 0 && grabbed.state().landing().distance() == 0,
                    "Grab retained pre-hang fall history");
        }
        var safe = compare(helper, initial, 1, tuning(0, 1, 9),
                (tick, state) -> MovementInput.idle(tick + 1, 0, 0), player -> {});
        helper.assertTrue(safe.damage() == 0 && safe.state().fallDistance() == 0, "Safe grab damaged or retained its fall");
        var dropped = compare(helper, initial, 40, tuning(0, 1, 5),
                (tick, state) -> new MovementInput(tick + 1, 0, 0, 0, false, false, tick == 3, tick == 3, false, 0, 0),
                player -> {});
        helper.assertTrue(dropped.state().grounded() && dropped.events().contains(MovementEvent.LEDGE_DROP)
                && dropped.damage() == 4 && dropped.falls() == 1, "Drop charged the pre-grab fall again");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ledgeGrabPreservesVanillaModifiersAndTraversalReleaseSettlesFall(GameTestHelper helper) {
        ledge(helper);
        var attributes = compare(helper, fallingAtLedge(helper, 8.9), 1, tuning(0, 2, 5),
                (tick, state) -> MovementInput.idle(tick + 1, 0, 0), player -> {
                    player.getAttribute(Attributes.SAFE_FALL_DISTANCE).setBaseValue(5);
                    player.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER).setBaseValue(0.5);
                });
        helper.assertTrue(attributes.damage() == 2 && attributes.falls() == 1, "Grab replaced vanilla fall attributes");
        var feather = compare(helper, fallingAtLedge(helper, 8.9), 1, tuning(0, 1, 5),
                (tick, state) -> MovementInput.idle(tick + 1, 0, 0), player -> {
                    var boots = new ItemStack(Items.DIAMOND_BOOTS);
                    boots.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                            .getHolderOrThrow(Enchantments.FEATHER_FALLING), 4);
                    player.setItemSlot(EquipmentSlot.FEET, boots);
                });
        helper.assertTrue(feather.damage() > 0 && feather.damage() < 4, "Grab ignored Feather Falling");
        var initial = new MovementState(position(helper, 4.5, 3.4, 4.65), new MovementVector(0, 3, 6),
                MovementState.Mode.AIR, 0, false, 8.9F);
        var released = compare(helper, initial, 25, tuning(0, 1, 5),
                (tick, state) -> {
                    if (tick == 5) helper.assertTrue(state.mode() == MovementState.Mode.MANTLE, "Release fixture was not mantling");
                    return new MovementInput(tick + 1, 1, 0, 0, tick < 5, false, false, false, true, 0, 0);
                }, player -> {});
        helper.assertTrue(released.state().mode() == MovementState.Mode.LEDGE_HANG
                && released.events().containsAll(List.of(MovementEvent.TRAVERSAL_ABORT, MovementEvent.LEDGE_GRAB))
                && released.damage() > 0 && released.falls() == 1 && released.state().fallDistance() == 0,
                "Traversal release did not settle the fall on entering hang");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void timedRollMitigationIsOnceAndRespectsServerRule(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        for (double reduction : new double[]{0, 0.5, 1}) {
            var result = compare(helper, imminent(helper, 9), 15, reduction, (tick, state) -> input(tick == 0, tick == 0, false));
            helper.assertTrue(result.events().containsAll(List.of(MovementEvent.ROLL_START, MovementEvent.ROLL_END)), "Timed roll did not run");
            helper.assertTrue(result.falls() == 1, "Roll applied multiple fall callbacks");
            helper.assertTrue(Math.abs(result.damage() - 6 * (1 - reduction)) < 1.0E-6,
                    "Server reduction did not compose with vanilla damage: " + reduction + " / " + result.damage());
        }
        // A vanilla fall outside a managed move receives no roll modifier, even for an active session.
        var player = new TestPlayer(helper);
        MovementRuntime.session(player).active = true;
        player.causeFallDamage(9, 1, player.damageSources().fall());
        helper.assertTrue(player.getHealth() == 14, "Managed session leaked mitigation into vanilla fall callbacks");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void baseFallRulesComposeWithRollAndPreservePhysicalFallHistory(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        for (double multiplier : new double[]{0, 0.5, 1, 2}) {
            var result = compare(helper, imminent(helper, 9), 4, tuning(0, multiplier, 5),
                    (tick, state) -> input(false, false, false), player -> {});
            helper.assertTrue(result.damage() == 4 * multiplier && result.falls() == 1,
                    "Base fall multiplier or safe height changed native damage: " + multiplier + " / " + result.damage());
            helper.assertTrue(result.events().contains(MovementEvent.HARD_LAND),
                    "Safe height changed physical landing recovery");
        }
        var rolled = compare(helper, imminent(helper, 9), 4, tuning(0.5, 2, 5),
                (tick, state) -> input(tick == 0, tick == 0, false), player -> {});
        helper.assertTrue(rolled.damage() == 4 && rolled.events().contains(MovementEvent.ROLL_START) && rolled.falls() == 1,
                "Base damage and roll multipliers did not compose exactly once");
        var safe = compare(helper, imminent(helper, 9), 4, tuning(0, 1, 9),
                (tick, state) -> input(false, false, false), player -> {});
        helper.assertTrue(safe.damage() == 0, "Fall at the minimum height caused damage");
        var above = compare(helper, imminent(helper, 9), 4, tuning(0, 1, 12),
                (tick, state) -> input(false, false, false), player -> {});
        helper.assertTrue(above.damage() == 0, "Fall below the minimum height caused damage");
        var noThreshold = compare(helper, imminent(helper, 2), 4, tuning(0, 1, 0),
                (tick, state) -> input(false, false, false), player -> {});
        helper.assertTrue(noThreshold.damage() == 2, "Zero minimum height retained vanilla's safe distance");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void customFallRulesKeepVanillaAttributesEffectsAndBlockModifiers(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        var attributes = compare(helper, imminent(helper, 9), 4, tuning(0, 2, 5),
                (tick, state) -> input(false, false, false), player -> {
                    player.getAttribute(Attributes.SAFE_FALL_DISTANCE).setBaseValue(5);
                    player.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER).setBaseValue(0.5);
                });
        helper.assertTrue(attributes.damage() == 2, "Custom rules replaced vanilla fall damage attributes");
        var jumpBoost = compare(helper, imminent(helper, 9), 4, tuning(0, 1, 5),
                (tick, state) -> input(false, false, false), player ->
                        player.addEffect(new MobEffectInstance(MobEffects.JUMP, 200, 1)));
        helper.assertTrue(jumpBoost.damage() == 2, "Minimum height replaced Jump Boost's safe-fall modifier");
        floor(helper, Blocks.HAY_BLOCK);
        var hay = compare(helper, imminent(helper, 9), 4, tuning(0, 2, 5),
                (tick, state) -> input(false, false, false), player -> {});
        helper.assertTrue(hay.damage() == 2, "Base damage replaced hay's native multiplier");
        floor(helper, Blocks.SLIME_BLOCK);
        var bounce = compare(helper, imminent(helper, 9), 3, tuning(0, 2, 0),
                (tick, state) -> input(false, false, false), player -> {});
        helper.assertTrue(bounce.damage() == 0 && bounce.state().velocity().y() > 0,
                "Base damage replaced slime's zero-damage bounce");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void heldOrExpiredPressHardLandsAndRecoveryIgnoresJump(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        var walking = compare(helper, imminent(helper, 6), 34, 0.5, (tick, state) -> input(false, false, true));
        helper.assertTrue(walking.state().mode() == MovementState.Mode.HARD_LANDING
                && Math.abs(walking.state().velocity().horizontalSpeed() - MovementTuning.DEFAULT.walkSpeed() * 0.5) < 0.01,
                "Hard landing did not allow half-speed walking while sprint and jump were held");
        helper.assertTrue(!walking.events().contains(MovementEvent.JUMP)
                && walking.state().position().z() > imminent(helper, 6).position().z() + 2,
                "Hard landing blocked walking or accepted a jump");
        var held = compare(helper, imminent(helper, 6), 40, 0.5, (tick, state) -> input(false, true, tick < 30));
        helper.assertTrue(held.events().containsAll(List.of(MovementEvent.HARD_LAND, MovementEvent.LANDING_RECOVERED)), "Hard landing did not recover");
        helper.assertTrue(!held.events().contains(MovementEvent.JUMP) && !held.events().contains(MovementEvent.ROLL_START), "Recovery accepted jump or held crouch");
        helper.assertTrue(held.damage() == 3 && held.falls() == 1, "Hard landing changed native fall damage");
        var initial = imminent(helper, 6);
        var expired = new MovementState(initial.position(), initial.velocity(), initial.mode(), initial.modeTime(), false, initial.fallDistance(),
                initial.locomotion(), initial.blocks(), initial.body(), 0, initial.traversal(), initial.wall(),
                new LandingState(initial.landing().apex(), 5.9, 0, LandingState.Source.OTHER, 0, 0.2, 0.2, true));
        var result = compare(helper, expired, 4, 0.5, (tick, state) -> input(false, false, false));
        helper.assertTrue(result.state().mode() == MovementState.Mode.HARD_LANDING && result.damage() == 3, "Expired press mitigated impact");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void rollingThroughLowGapAndBlockedExitUseNativeLowPose(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        for (int z = 5; z <= 11; z++) helper.setBlock(new BlockPos(4, 2, z), Blocks.STONE);
        var result = compare(helper, imminent(helper, 6), 15, 0.5, (tick, state) -> input(tick == 0, tick == 0, false));
        helper.assertTrue(result.events().contains(MovementEvent.ROLL_END) && result.state().body() == MovementBody.LOW,
                "Roll stood into the low ceiling");
        helper.assertTrue(result.state().position().z() > position(helper, 0, 0, 5).z(), "Roll did not traverse gap");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void rollWallAndRemovedFloorNeverLeaveModeLocked(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(4, y, 5), Blocks.STONE);
        var blocked = compare(helper, imminent(helper, 6), 15, 0.5, (tick, state) -> input(tick == 0, tick == 0, false));
        helper.assertTrue(blocked.state().mode() == MovementState.Mode.GROUND && blocked.state().position().z() <= position(helper, 0, 0, 4.7).z() + 1.0E-6,
                "Blocked roll clipped or remained locked");
        for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(4, y, 5), Blocks.AIR);
        for (int x = 1; x <= 13; x++) for (int z = 5; z <= 13; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.AIR);
        var edge = compare(helper, imminent(helper, 6), 8, 0.5, (tick, state) -> input(tick == 0, tick == 0, false));
        helper.assertTrue(edge.state().mode() == MovementState.Mode.AIR && edge.events().contains(MovementEvent.ROLL_END), "Roll leaving edge remained locked");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void featherFallingComposesWithRollReduction(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        Consumer<TestPlayer> feather = player -> {
            var boots = new ItemStack(Items.DIAMOND_BOOTS);
            boots.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.FEATHER_FALLING), 4);
            player.setItemSlot(EquipmentSlot.FEET, boots);
        };
        var ordinary = compare(helper, imminent(helper, 9), 4, 0, (tick, state) -> input(tick == 0, tick == 0, false), feather);
        var rolled = compare(helper, imminent(helper, 9), 4, 0.5, (tick, state) -> input(tick == 0, tick == 0, false), feather);
        helper.assertTrue(ordinary.damage() > 0 && ordinary.damage() < 6, "Feather Falling did not reduce vanilla damage");
        helper.assertTrue(rolled.damage() > 0 && Math.abs(rolled.damage() - ordinary.damage() * 0.5) < 1.0E-5,
                "Roll replaced/double-applied Feather Falling reduction");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void hayAndSlimeKeepNativeBlockDamageAndBouncePolicy(GameTestHelper helper) {
        floor(helper, Blocks.HAY_BLOCK);
        var hay = compare(helper, imminent(helper, 9), 4, 0, (tick, state) -> input(tick == 0, tick == 0, false));
        var rolled = compare(helper, imminent(helper, 9), 4, 0.5, (tick, state) -> input(tick == 0, tick == 0, false));
        helper.assertTrue(hay.damage() == 2 && rolled.damage() == 1, "Roll did not preserve hay's 0.2 damage multiplier");
        floor(helper, Blocks.SLIME_BLOCK);
        var slime = compare(helper, imminent(helper, 9), 4, 0.5, (tick, state) -> input(tick == 0, true, false));
        helper.assertTrue(slime.damage() == 3 && slime.events().contains(MovementEvent.ROLL_START),
                "Crouch-suppressed slime bounce did not retain vanilla damage plus roll mitigation");
        var bounce = compare(helper, imminent(helper, 9), 3, 0.5, (tick, state) -> input(false, false, false));
        helper.assertTrue(!bounce.events().contains(MovementEvent.ROLL_START) && !bounce.events().contains(MovementEvent.HARD_LAND)
                && bounce.state().velocity().y() > 0 && bounce.damage() == 0, "Native zero-damage bounce was replaced by landing recovery");
        floor(helper, Blocks.RED_BED);
        var bed = compare(helper, imminent(helper, 9), 4, 0, (tick, state) -> input(tick == 0, false, false));
        var bedRule = compare(helper, imminent(helper, 9), 4, 1, (tick, state) -> input(tick == 0, false, false));
        helper.assertTrue(bed.damage() > 0 && bed.damage() == bedRule.damage()
                && !bedRule.events().contains(MovementEvent.ROLL_START), "A native bed bounce received stale roll mitigation");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void slowFallingAndWaterYieldWithoutStaleRollMitigation(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        var player = new TestPlayer(helper);
        var initial = imminent(helper, 9);
        MinecraftMotionAdapter.apply(player, initial);
        var session = MovementRuntime.session(player);
        session.active = true;
        session.state = initial;
        session.tuning = tuning(1);
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200));
        helper.assertTrue(!MovementEligibility.supported(player), "Slow Falling did not yield");
        MovementRuntime.beforeTick(new PlayerTickEvent.Pre(player));
        helper.assertTrue(!session.active && session.state == null, "Slow Falling retained landing/roll session");
        player.removeEffect(MobEffects.SLOW_FALLING);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.WATER);
        player.setPos(MinecraftMotionAdapter.minecraft(position(helper, 4.5, 1.1, 3.5)));
        player.refreshFluids();
        helper.assertTrue(!MovementEligibility.supported(player), "Water did not yield");
        helper.succeed();
    }
}
