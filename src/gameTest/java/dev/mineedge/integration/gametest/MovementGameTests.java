package dev.mineedge.integration.gametest;

import com.mojang.authlib.GameProfile;
import dev.mineedge.MineEdge;
import dev.mineedge.integration.MinecraftCollisionWorld;
import dev.mineedge.integration.MinecraftMotionAdapter;
import dev.mineedge.integration.ResolvedMovement;
import dev.mineedge.integration.MovementRuntime;
import dev.mineedge.integration.MovementControlEvent;
import dev.mineedge.integration.MovementEligibility;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementEvent;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementRate;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.OffThreadMovement;
import dev.mineedge.movement.surface.ClimbRoute;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Development-only registration by NeoForge's GameTest scanner. Runs real server worlds and callbacks. */
@GameTestHolder(MineEdge.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MovementGameTests {
    private static final MovementController CONTROLLER = new MovementController();

    private static final class TestPlayer extends FakePlayer {
        int damagingFalls;

        TestPlayer(GameTestHelper helper) {
            super(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeTest"));
        }

        @Override
        public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
            if (distance > 3 && multiplier > 0) {
                damagingFalls++;
            }
            return super.causeFallDamage(distance, multiplier, source);
        }
    }

    private static final class CombatPlayer extends FakePlayer {
        Vec3 knockbackVelocity;

        CombatPlayer(GameTestHelper helper) {
            super(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeCombat"));
            try {
                var grace = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
                grace.setAccessible(true);
                grace.setInt(this, 0);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot clear test player's spawn grace", exception);
            }
        }

        @Override public boolean isInvulnerableTo(DamageSource source) { return false; }
        @Override public boolean canHarmPlayer(Player player) { return true; }
        @Override public void knockback(double strength, double x, double z) {
            super.knockback(strength, x, z);
            knockbackVelocity = getDeltaMovement();
        }
    }

    private static final class CompatibilityPlayer extends FakePlayer {
        Vec3 travelImpulse = Vec3.ZERO;
        int travelReturns;

        CompatibilityPlayer(GameTestHelper helper) {
            super(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MineEdgeCompat"));
        }

        @Override public void travel(Vec3 input) {
            setDeltaMovement(getDeltaMovement().add(travelImpulse));
            super.travel(input);
            travelReturns++;
        }

        @Override public float maxUpStep() { return super.maxUpStep() + 0.25F; }
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void managedTravelPreservesExternalImpulsesAndSmallVelocity(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        var player = new CompatibilityPlayer(helper);
        var session = MovementRuntime.session(player);
        try {
            player.setPos(MinecraftMotionAdapter.minecraft(position(helper, 7.5, 4, 7.5)));
            player.setOnGround(false);
            var velocity = new Vec3(0.001, -0.002, 0.0025);
            player.setDeltaMovement(velocity);
            session.active = true;
            session.state = MinecraftMotionAdapter.capture(player);
            session.tuning = MovementRuntime.effectiveTuning(player);
            session.writtenVelocity = MinecraftMotionAdapter.core(velocity);
            var before = player.position();
            player.aiStep();
            helper.assertTrue(player.getDeltaMovement().equals(velocity), "Managed aiStep damped or snapped small velocity");
            helper.assertTrue(player.position().equals(before), "Vanilla travel integrated managed movement twice");
            helper.assertTrue(player.travelReturns == 1, "Travel hooks did not finish");

            player.travelImpulse = new Vec3(0.1, 0.2, -0.05);
            player.travel(Vec3.ZERO);
            var expected = velocity.add(player.travelImpulse);
            helper.assertTrue(player.getDeltaMovement().equals(expected), "Managed travel erased another mod's impulse");
            MovementRuntime.beforeTick(new PlayerTickEvent.Pre(player));
            helper.assertTrue(session.state.velocity().subtract(MinecraftMotionAdapter.core(expected).scale(20)).length() < 1.0E-9,
                    "External impulse was not imported exactly once");
            helper.assertTrue(Math.abs(MinecraftMotionAdapter.live(player).maxStepHeight() - 0.85F) < 1.0E-6,
                    "Live resolver ignored a modded step-height override");
            helper.assertTrue(Math.abs(MinecraftMotionAdapter.replay(player).maxStepHeight() - 0.85F) < 1.0E-6,
                    "Replay ignored a modded step-height override");
        } finally {
            session.reset();
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void otherModsCanReleaseAndRestoreMovementControl(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        var player = new TestPlayer(helper);
        player.setPos(MinecraftMotionAdapter.minecraft(position(helper, 7.5, 4, 7.5)));
        var session = MovementRuntime.session(player);
        session.active = true;
        session.state = MinecraftMotionAdapter.capture(player);
        session.tuning = MovementRuntime.effectiveTuning(player);
        java.util.function.Consumer<MovementControlEvent> listener = event -> {
            if (event.getEntity() == player) event.setCanceled(true);
        };
        try {
            helper.assertTrue(MovementRuntime.owns(player), "Compatibility fixture was not managed");
            player.noPhysics = true;
            helper.assertTrue(!MovementEligibility.supported(player), "No-clip movement was still managed");
            player.noPhysics = false;
            NeoForge.EVENT_BUS.addListener(listener);
            helper.assertTrue(!MovementRuntime.owns(player), "Control event did not release ownership");
            var before = player.position();
            player.setDeltaMovement(0.1, 0, 0);
            player.travel(Vec3.ZERO);
            helper.assertTrue(!player.position().equals(before), "Canceled control did not restore native travel");
            NeoForge.EVENT_BUS.unregister(listener);
            helper.assertTrue(MovementRuntime.owns(player), "Ownership did not return after the integration released it");
            session.active = false;
            player.setDeltaMovement(0.001, 0.002, 0.0025);
            player.aiStep();
            helper.assertTrue(player.getDeltaMovement().x == 0 && player.getDeltaMovement().z == 0,
                    "Native velocity snapping did not return after releasing ownership");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            session.reset();
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void playerAttackKnockbackSurvivesManagedGroundAndAirTravel(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        for (boolean managed : new boolean[] {true, false}) {
            for (double vertical : new double[] {0, 0.2, -0.2}) {
                for (double resistance : new double[] {0, 0.5, 1}) {
                    var victim = new CombatPlayer(helper);
                    var attacker = new CombatPlayer(helper);
                    var session = MovementRuntime.session(victim);
                    try {
                        var feet = position(helper, 7.5, vertical == 0 ? 1 : 4, 7.5);
                        victim.setPos(MinecraftMotionAdapter.minecraft(feet));
                        victim.setOnGround(vertical == 0);
                        var original = new Vec3(0.12, vertical, -0.1);
                        victim.setDeltaMovement(original);
                        victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(resistance);
                        attacker.setPos(victim.position().add(0, 0, -1));
                        attacker.setYRot(0);
                        attacker.getAttribute(Attributes.ATTACK_KNOCKBACK).setBaseValue(1);
                        session.active = managed;
                        session.state = MinecraftMotionAdapter.capture(victim);
                        session.tuning = MovementRuntime.effectiveTuning(victim);
                        session.writtenVelocity = MinecraftMotionAdapter.core(original);
                        helper.assertTrue(MovementRuntime.owns(victim) == managed, "Combat fixture ownership was incorrect");

                        attacker.attack(victim);
                        helper.assertTrue(victim.getHealth() < victim.getMaxHealth(), "Test attack did not damage its victim");
                        helper.assertTrue(!victim.hurtMarked && victim.knockbackVelocity != null,
                                "Test attack did not complete the native player velocity handoff");
                        var expected = managed ? victim.knockbackVelocity : original;
                        helper.assertTrue(victim.getDeltaMovement().equals(expected),
                                "Player attack discarded managed knockback: vertical=" + vertical + ", resistance=" + resistance);
                        if (!managed) continue;

                        MovementRuntime.beforeTick(new PlayerTickEvent.Pre(victim));
                        var imported = MinecraftMotionAdapter.core(expected).scale(20);
                        helper.assertTrue(session.state.velocity().subtract(imported).length() < 1.0E-9,
                                "Tick boundary did not import the attack velocity exactly once");
                        MovementRuntime.travel(victim);
                        MovementRuntime.beforeTick(new PlayerTickEvent.Pre(victim));
                        helper.assertTrue(session.state.velocity().subtract(imported).length() < 1.0E-9,
                                "Managed travel lost or duplicated the attack velocity");
                        if (resistance < 1) {
                            var next = CONTROLLER.step(session.state, MovementInput.idle(1, 0, 0), session.tuning,
                                    MinecraftMotionAdapter.live(victim), event -> {});
                            helper.assertTrue(next.position().z() > feet.z() + 0.04,
                                    "Knockback did not move the victim during managed simulation");
                            if (vertical != 0) helper.assertTrue(Math.abs(next.velocity().y()
                                    - (imported.y() - session.tuning.gravity() * MovementRate.STEP_SECONDS)) < 1.0E-9,
                                    "Airborne knockback replaced the victim's vertical motion");
                        }
                    } finally {
                        session.reset();
                        victim.discard();
                        attacker.discard();
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void offThreadSimulationMatchesNativeCallbacksAtFortyHertz(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        var start = position(helper, 7.5, 1, 3.5);
        var workerPlayer = new TestPlayer(helper);
        var directPlayer = new TestPlayer(helper);
        workerPlayer.setPos(MinecraftMotionAdapter.minecraft(start));
        directPlayer.setPos(MinecraftMotionAdapter.minecraft(start));
        workerPlayer.setOnGround(true);
        directPlayer.setOnGround(true);
        var worker = MinecraftMotionAdapter.capture(workerPlayer);
        var direct = MinecraftMotionAdapter.capture(directPlayer);
        for (int step = 1; step <= 40; step++) {
            var input = new MovementInput(step, 1, 0, 0, step == 5, step == 5,
                    false, false, true, 0, 0);
            var events = new java.util.ArrayList<dev.mineedge.movement.MovementEvent>();
            direct = CONTROLLER.step(direct, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(directPlayer), events::add);
            var result = OffThreadMovement.step(CONTROLLER, worker, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(workerPlayer));
            worker = result.state();
            helper.assertTrue(direct.equals(worker), "Worker/native movement diverged at step " + step);
            helper.assertTrue(events.equals(result.events()), "Worker movement events diverged at step " + step);
            helper.assertTrue(workerPlayer.position().equals(directPlayer.position()), "Native callbacks applied a different position");
        }
        helper.assertTrue(workerPlayer.damagingFalls == directPlayer.damagingFalls, "Worker callbacks applied different fall damage");
        helper.succeed();
    }

    private static void floor(GameTestHelper helper, Block block) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), block);
            }
        }
        // Test fixture preparation explicitly loads the surrounding ring. Runtime probes must not do so.
        BlockPos origin = helper.absolutePos(new BlockPos(7, 0, 7));
        for (int x = (origin.getX() >> 4) - 2; x <= (origin.getX() >> 4) + 2; x++) {
            for (int z = (origin.getZ() >> 4) - 2; z <= (origin.getZ() >> 4) + 2; z++) {
                helper.getLevel().getChunk(x, z);
            }
        }
    }

    private static MovementVector position(GameTestHelper helper, double x, double y, double z) {
        return MinecraftMotionAdapter.core(helper.absoluteVec(new Vec3(x, y, z)));
    }

    /** Compare every tick and prove replay does not mutate its player, damage counter or pending response. */
    private static MovementState compare(GameTestHelper helper, MovementVector position, MovementVector velocity,
                                         int ticks, boolean forward, boolean efficiency) {
        return compare(helper, position, velocity, ticks, forward, efficiency, false);
    }

    private static MovementState compare(GameTestHelper helper, MovementVector position, MovementVector velocity,
                                          int ticks, boolean forward, boolean efficiency, boolean weaving) {
        return compare(helper, position, velocity, ticks, efficiency, weaving,
                tick -> new MovementInput(tick, forward ? 1 : 0, 0, 0, false, false, false, false, true, 0, 0));
    }

    private static MovementState compare(GameTestHelper helper, MovementVector position, MovementVector velocity,
                                          int ticks, boolean efficiency, boolean weaving, IntFunction<MovementInput> inputs) {
        return compare(helper, position, velocity, ticks, efficiency, weaving, inputs, launch -> {});
    }

    private static MovementState compare(GameTestHelper helper, MovementVector position, MovementVector velocity,
                                          int ticks, boolean efficiency, boolean weaving, IntFunction<MovementInput> inputs,
                                          DoubleConsumer landingVelocity) {
        TestPlayer live = new TestPlayer(helper);
        TestPlayer replayOwner = new TestPlayer(helper);
        live.setPos(MinecraftMotionAdapter.minecraft(position));
        replayOwner.setPos(MinecraftMotionAdapter.minecraft(position));
        live.setDeltaMovement(MinecraftMotionAdapter.minecraft(velocity.scale(1.0 / 20)));
        if (efficiency) {
            live.getAttribute(Attributes.MOVEMENT_EFFICIENCY).setBaseValue(0.5);
            replayOwner.getAttribute(Attributes.MOVEMENT_EFFICIENCY).setBaseValue(0.5);
        }
        if (weaving) {
            live.addEffect(new MobEffectInstance(MobEffects.WEAVING, 200));
            replayOwner.addEffect(new MobEffectInstance(MobEffects.WEAVING, 200));
        }
        MovementState actual = MinecraftMotionAdapter.capture(live);
        MovementState predicted = actual;
        Vec3 replayPosition = replayOwner.position();
        var replayBlocks = MinecraftMotionAdapter.blockState(replayOwner);
        var replayResolver = MinecraftMotionAdapter.replay(replayOwner);
        for (int tick = 1; tick <= ticks; tick++) {
            MovementInput input = inputs.apply(tick);
            actual = CONTROLLER.tick(actual, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(live), event -> {
                if (event == MovementEvent.LAND) landingVelocity.accept(live.getDeltaMovement().y * 20);
            });
            predicted = CONTROLLER.tick(predicted, input, MovementTuning.DEFAULT,
                    replayResolver, event -> {});
            helper.assertTrue(actual.position().subtract(predicted.position()).length() < 1.0E-6,
                    "Position diverged at tick " + tick + ": " + actual.position() + " / " + predicted.position());
            helper.assertTrue(actual.velocity().subtract(predicted.velocity()).length() < 1.0E-6,
                    "Velocity diverged at tick " + tick + ": " + actual.velocity() + " / " + predicted.velocity());
            helper.assertTrue(actual.grounded() == predicted.grounded(), "Ground contact diverged at tick " + tick);
            helper.assertTrue(Math.abs(actual.fallDistance() - predicted.fallDistance()) < 1.0E-5,
                    "Fall history diverged at tick " + tick + ": " + actual.fallDistance() + " / " + predicted.fallDistance());
            helper.assertTrue(actual.blocks().equals(predicted.blocks()), "Pending block response diverged at tick " + tick);
        }
        helper.assertTrue(replayOwner.position().equals(replayPosition), "Replay moved its owner");
        helper.assertTrue(MinecraftMotionAdapter.blockState(replayOwner).equals(replayBlocks), "Replay mutated stuck state");
        helper.assertTrue(replayOwner.damagingFalls == 0, "Replay applied fall damage");
        return actual;
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void slabStairFenceAndCeilingReplay(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        helper.setBlock(new BlockPos(4, 1, 6), Blocks.STONE_STAIRS);
        helper.setBlock(new BlockPos(5, 1, 6), Blocks.STONE_SLAB);
        helper.setBlock(new BlockPos(4, 1, 8), Blocks.OAK_FENCE);
        helper.setBlock(new BlockPos(4, 3, 4), Blocks.OAK_TRAPDOOR);
        compare(helper, position(helper, 4.5, 1, 3.5), new MovementVector(0, 6.3, 5), 80, true, false);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void slimeBounceAndSlowdownReplay(GameTestHelper helper) {
        floor(helper, Blocks.SLIME_BLOCK);
        double dt = MovementTuning.SUBSTEP_SECONDS;
        double impact = 10 + MovementTuning.DEFAULT.gravity() * dt;
        // Guarantee contact during the first physics substep, regardless of simulation rate.
        double gap = impact * dt / 2;
        var launches = new ArrayList<Double>();
        MovementState state = compare(helper, position(helper, 4.5, 1 + gap, 4.5), new MovementVector(2, -10, 0),
                2, false, false, tick -> MovementInput.idle(tick, 0, 0), launches::add);
        helper.assertTrue(launches.size() == 1, "Expected exactly one slime landing: " + launches);
        double launch = launches.getFirst();
        helper.assertTrue(Math.abs(launch - impact * 0.85) < 1.0E-6,
                "Slime bounce did not retain 85% of impact speed: " + launch);
        double expected = launch - MovementTuning.DEFAULT.gravity() * (2 * MovementTuning.TICK_SECONDS - dt);
        helper.assertTrue(Math.abs(state.velocity().y() - expected) < 1.0E-6 && !state.grounded(),
                "Slime launch did not continue under gravity: " + state.velocity());
        state = compare(helper, position(helper, 7.5, 1, 7.5), MovementVector.ZERO, 40, false, false,
                tick -> new MovementInput(tick, 1, 0, 0, false, false, false, false, false, 0, 0));
        helper.assertTrue(state.grounded() && state.velocity().y() == 0, "Resting slime contact kept micro-bouncing");
        helper.assertTrue(state.velocity().horizontalSpeed() > 0.8, "Slime walking was slowed at every substep: " + state.velocity());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void stickySurfaceJumpAndHoneyTuningReplay(GameTestHelper helper) {
        for (Block block : new Block[] {Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK}) {
            floor(helper, block);
            double y = block == Blocks.HONEY_BLOCK ? 0.9375 : 1;
            var feet = position(helper, 7.5, y, 7.5);
            var state = compare(helper, feet, MovementVector.ZERO, 21, false, false,
                    tick -> new MovementInput(tick, 0, 0, 0, tick == 21, tick == 21, false, false, false, 0, 0));
            helper.assertTrue(!state.grounded() && state.position().y() > feet.y() + 0.1 && state.velocity().y() > 2,
                    "Could not jump off " + block);
            if (block == Blocks.HONEY_BLOCK) {
                state = compare(helper, feet, MovementVector.ZERO, 40, false, false,
                        tick -> new MovementInput(tick, 1, 0, 0, false, false, false, false, false, 0, 0));
                helper.assertTrue(state.velocity().horizontalSpeed() > 0.8, "Honey walking compounded slowdown: " + state.velocity());
                TestPlayer player = new TestPlayer(helper);
                player.setPos(MinecraftMotionAdapter.minecraft(feet));
                var groundedTuning = MovementRuntime.effectiveTuning(player);
                player.setPos(MinecraftMotionAdapter.minecraft(feet.add(new MovementVector(0, 2, 0))));
                helper.assertTrue(groundedTuning.equals(MovementRuntime.effectiveTuning(player)), "Leaving honey reset movement tuning");
                player.addEffect(new MobEffectInstance(MobEffects.JUMP, 200));
                var boostedTuning = MovementRuntime.effectiveTuning(player);
                player.setPos(MinecraftMotionAdapter.minecraft(feet));
                helper.assertTrue(boostedTuning.equals(MovementRuntime.effectiveTuning(player)), "Honey changed Jump Boost tuning");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void slimeBouncesLoseHeightAndSettleReplay(GameTestHelper helper) {
        floor(helper, Blocks.SLIME_BLOCK);
        TestPlayer live = new TestPlayer(helper);
        TestPlayer owner = new TestPlayer(helper);
        var feet = position(helper, 7.5, 1.1, 7.5);
        live.setPos(MinecraftMotionAdapter.minecraft(feet));
        owner.setPos(live.position());
        live.setDeltaMovement(0, -0.5, 0);
        var actual = MinecraftMotionAdapter.capture(live);
        var predicted = actual;
        var launches = new ArrayList<Double>();
        for (int tick = 1; tick <= 300; tick++) {
            var input = MovementInput.idle(tick, 0, 0);
            actual = CONTROLLER.tick(actual, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(live), event -> {
                // Sample at contact, before the remaining substeps apply more gravity.
                double launch = live.getDeltaMovement().y * 20;
                if (event == MovementEvent.LAND && launch > 1) launches.add(launch);
            });
            predicted = CONTROLLER.tick(predicted, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.replay(owner), event -> {});
            helper.assertTrue(actual.position().subtract(predicted.position()).length() < 1.0E-6
                    && actual.velocity().subtract(predicted.velocity()).length() < 1.0E-6, "Repeated bounce replay diverged");
        }
        for (int i = 1; i < launches.size(); i++) {
            helper.assertTrue(launches.get(i) < launches.get(i - 1) * 0.95,
                    "Bounce momentum did not decay at bounce " + (i + 1)
                            + ": previous=" + launches.get(i - 1) + ", current=" + launches.get(i));
        }
        helper.assertTrue(launches.size() >= 3 && actual.grounded() && actual.velocity().y() == 0, "Slime bounces never settled");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void slimePistonLaunchesManagedServerPlayer(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        TestPlayer player = new TestPlayer(helper);
        helper.getLevel().addFreshEntity(player);
        var session = MovementRuntime.session(player);
        session.active = true;
        try {
            for (Direction direction : new Direction[] {Direction.UP, Direction.SOUTH, Direction.WEST}) {
                var relative = new BlockPos(7, 2, 7);
                var pos = helper.absolutePos(relative);
                var state = Blocks.MOVING_PISTON.defaultBlockState().setValue(MovingPistonBlock.FACING, direction);
                helper.setBlock(relative, state);
                var piston = new PistonMovingBlockEntity(pos, state, Blocks.SLIME_BLOCK.defaultBlockState(), direction, true, false);
                helper.getLevel().setBlockEntity(piston);
                var feet = direction == Direction.UP ? position(helper, 7.5, 2, 7.5)
                        : position(helper, 7.5 - direction.getStepX() * 0.6, 2, 7.5 - direction.getStepZ() * 0.6);
                player.setPos(MinecraftMotionAdapter.minecraft(feet));
                player.setDeltaMovement(Vec3.ZERO);
                player.setOnGround(true);
                session.state = MinecraftMotionAdapter.capture(player);
                session.tuning = MovementRuntime.effectiveTuning(player);
                session.writtenVelocity = MovementVector.ZERO;
                helper.assertTrue(MovementRuntime.owns(player), "Piston player fixture was not managed");
                PistonMovingBlockEntity.tick(helper.getLevel(), pos, state, piston);
                helper.assertTrue(player.getDeltaMovement().get(direction.getAxis()) * direction.getAxisDirection().getStep() > 0.9,
                        "Slime piston did not impart server launch velocity: " + direction);
                MovementRuntime.beforeTick(new PlayerTickEvent.Pre(player));
                var launched = session.state;
                helper.assertTrue(launched.velocity().length() > 18, "Tick boundary discarded piston impulse");
                var pushedPosition = MinecraftMotionAdapter.core(player.position());
                var next = CONTROLLER.tick(launched, MovementInput.idle(1, 0, 0), MovementTuning.DEFAULT,
                        MinecraftMotionAdapter.live(player), event -> {});
                helper.assertTrue(next.position().subtract(pushedPosition).length() > 0.4, "Managed travel erased piston launch");
                helper.setBlock(relative, Blocks.AIR);
            }
        } finally {
            session.active = false;
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void honeySurfaceAndWallSlideReplay(GameTestHelper helper) {
        floor(helper, Blocks.HONEY_BLOCK);
        MovementState state = compare(helper, position(helper, 7.5, 1.3, 7.5), new MovementVector(0, -8, 2), 40, true, true);
        helper.assertTrue(state.grounded(), "Honey surface was not grounded");
        floor(helper, Blocks.STONE);
        for (int y = 1; y <= 5; y++) {
            helper.setBlock(new BlockPos(4, y, 4), Blocks.HONEY_BLOCK);
        }
        state = compare(helper, position(helper, 3.7625, 4.4, 4.5), new MovementVector(0, -8, 0), 8, false, false);
        helper.assertTrue(state.fallDistance() < 0.2, "Honey slide did not reset fall history");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void soulSandAndMovementEfficiencyReplay(GameTestHelper helper) {
        floor(helper, Blocks.SOUL_SAND);
        var position = position(helper, 7.5, 0.875, 3.5);
        MovementState slow = compare(helper, position, MovementVector.ZERO, 40, true, false);
        MovementState efficient = compare(helper, position, MovementVector.ZERO, 40, true, true);
        helper.assertTrue(efficient.velocity().horizontalSpeed() > slow.velocity().horizontalSpeed(),
                "Movement efficiency did not reduce slowdown");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void cobwebPendingResponseAndAcknowledgementReplay(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        for (int z = 3; z <= 7; z++) {
            helper.setBlock(new BlockPos(4, 1, z), Blocks.COBWEB);
            helper.setBlock(new BlockPos(4, 2, z), Blocks.COBWEB);
        }
        MovementState state = compare(helper, position(helper, 4.5, 1.2, 4.5), new MovementVector(0, -2, 3), 20, true, false);
        helper.assertTrue(state.blocks().stuckMultiplier().x() == 0.25, "Cobweb response was not retained");
        TestPlayer restored = new TestPlayer(helper);
        MinecraftMotionAdapter.apply(restored, state);
        MovementInput input = new MovementInput(21, 1, 0, 0, false, false, false, false, true, 0, 0);
        MovementState live = CONTROLLER.tick(state, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.live(restored), event -> {});
        MovementState replay = CONTROLLER.tick(state, input, MovementTuning.DEFAULT, MinecraftMotionAdapter.replay(restored), event -> {});
        helper.assertTrue(live.position().subtract(replay.position()).length() < 1.0E-6,
                "Acknowledged pending cobweb response did not replay");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void weavingAndBerryBushResponsesReplay(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.COBWEB);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.COBWEB);
        var state = compare(helper, position(helper, 4.5, 1.2, 4.5), new MovementVector(0, -2, 0),
                10, true, false, true);
        helper.assertTrue(state.blocks().stuckMultiplier().equals(new MovementVector(0.5, 0.25, 0.5)),
                "Weaving did not change the pending cobweb response");
        helper.setBlock(new BlockPos(7, 1, 7), Blocks.SWEET_BERRY_BUSH);
        state = compare(helper, position(helper, 7.5, 1.2, 7.5), new MovementVector(0, -2, 0), 10, false, false);
        helper.assertTrue(state.blocks().stuckMultiplier().equals(new MovementVector(0.8F, 0.75, 0.8F)),
                "Berry bush stuck response was not retained");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void bedBounceReplay(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.RED_BED);
        var state = compare(helper, position(helper, 4.5, 1.65, 4.5), new MovementVector(0, -10, 0), 2, false, false);
        helper.assertTrue(state.velocity().y() > 4, "Bed did not bounce");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void vanillaMovementResumesAfterManagedScope(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.STONE_SLAB);
        TestPlayer player = new TestPlayer(helper);
        var position = position(helper, 4.5, 1, 3.5);
        player.setPos(MinecraftMotionAdapter.minecraft(position));
        player.setOnGround(true);
        var managed = MinecraftMotionAdapter.live(player).resolve(position, new MovementVector(0, -0.14, 24), 0.05);
        helper.assertTrue(Math.abs(managed.position().y() - position.y()) < 1.0E-6, "Managed movement stepped independently");
        try {
            ResolvedMovement.run(player, () -> { throw new IllegalStateException("test scope cleanup"); });
        } catch (IllegalStateException expected) {
            helper.assertTrue(!ResolvedMovement.active(player), "Exception leaked the movement scope");
        }
        player.setOnGround(true);
        player.move(MoverType.SELF, new Vec3(0, 0, 1));
        helper.assertTrue(Math.abs(player.getY() - position.y() - 0.5) < 1.0E-6,
                "Vanilla movement did not regain its own slab step-up");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void fallDamageIsOnceAndHoneyResetRunsAfterDamageCheck(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        TestPlayer player = new TestPlayer(helper);
        MovementVector position = position(helper, 4.5, 1.1, 4.5);
        player.setPos(MinecraftMotionAdapter.minecraft(position));
        player.fallDistance = 6;
        var resolver = MinecraftMotionAdapter.live(player);
        var landing = resolver.resolve(position, new MovementVector(0, -10, 0), 0.05);
        helper.assertTrue(landing.grounded(), "Test did not land");
        helper.assertTrue(player.damagingFalls == 1, "Fall damage was not applied exactly once");
        for (int i = 0; i < 6; i++) {
            resolver.resolve(MinecraftMotionAdapter.core(player.position()), new MovementVector(0, -0.14, 0), 1.0 / 120);
        }
        helper.assertTrue(player.damagingFalls == 1, "Ground substeps repeated fall damage");
        helper.setBlock(new BlockPos(7, 4, 7), Blocks.HONEY_BLOCK);
        position = position(helper, 6.7625, 4.4, 7.5);
        player.setPos(MinecraftMotionAdapter.minecraft(position));
        player.setOnGround(false);
        player.fallDistance = 6;
        var slide = resolver.resolve(position, new MovementVector(0, -8, 0), 0.05);
        helper.assertTrue(slide.velocity().y() == -1 && player.fallDistance == 0,
                "Honey's fall reset did not run after the server damage check");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void worldBorderStopsLiveAndReplay(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        var border = helper.getLevel().getWorldBorder();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        double size = border.getSize();
        try {
            MovementVector center = position(helper, 7.5, 1, 7.5);
            border.setCenter(Math.floor(center.x()), Math.floor(center.z()));
            border.setSize(10);
            MovementState state = compare(helper, center, new MovementVector(0, 0, 7), 20, true, false);
            helper.assertTrue(state.position().z() <= border.getMaxZ() - 0.3 + 1.0E-6,
                    "Movement crossed the world border");
            helper.assertTrue(state.velocity().z() == 0, "Border did not clip incoming velocity");
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void changedSupportRecoveryAndAbsentChunks(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        TestPlayer player = new TestPlayer(helper);
        MovementVector position = position(helper, 4.5, 1, 4.5);
        player.setPos(MinecraftMotionAdapter.minecraft(position));
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.AIR);
        var falling = MinecraftCollisionWorld.resolve(player, position, new MovementVector(0, -0.05, 0));
        helper.assertTrue(!falling.grounded() && falling.displacement().y() < 0, "Removed support was retained");
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        var recovered = MinecraftCollisionWorld.resolve(player, position.add(new MovementVector(0, -0.05, 0)),
                new MovementVector(0, -0.05, 0));
        helper.assertTrue(!recovered.blockedByOverlap() && Math.abs(recovered.recovery().y() - 0.05) < 1.0E-6,
                "Small overlap did not recover");
        int chunk = 62500;
        helper.assertTrue(!helper.getLevel().getChunkSource().hasChunk(chunk, chunk), "Absent-chunk fixture already loaded");
        var stopped = MinecraftCollisionWorld.resolve(player, new MovementVector(1000000, position.y(), 1000000),
                new MovementVector(1, 0, 0));
        helper.assertTrue(stopped.blockedByOverlap() && stopped.displacement().equals(MovementVector.ZERO),
                "Absent chunk did not stop motion");
        helper.assertTrue(!helper.getLevel().getChunkSource().hasChunk(chunk, chunk), "Probe loaded an absent chunk");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void isolatedPipeSidewaysOrbitReplay(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        for (Block block : new Block[]{Blocks.CHAIN, Blocks.IRON_BARS, Blocks.OAK_FENCE, Blocks.END_ROD}) {
            for (int y = 1; y <= 5; y++) helper.setBlock(new BlockPos(7, y, 7), block);
            var start = position(helper, 7.5, 2, 7.5 - ClimbRoute.PIPE_RADIUS);
            var state = compare(helper, start, MovementVector.ZERO, 60, false, false,
                    tick -> new MovementInput(tick, 0, tick > 20 ? 1 : 0, tick > 20 ? 1 : 0,
                            false, false, false, false, false, 0, 0));
            helper.assertTrue(state.surfaces().climb() != null, "Lost grip while circling " + block);
            helper.assertTrue(state.position().z() > position(helper, 7.5, 2, 7.5).z(), "Did not circle behind " + block);
            helper.assertTrue(Math.abs(state.position().y() - start.y()) < 0.17, "Orbit changed climb height");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void connectedBarsAndFencesSidewaysReplay(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        for (Block block : new Block[]{Blocks.IRON_BARS, Blocks.OAK_FENCE}) {
            for (int x = 5; x <= 9; x++) for (int y = 1; y <= 5; y++) helper.setBlock(new BlockPos(x, y, 7), block);
            for (float strafe : new float[]{-1, 1}) {
                var start = position(helper, 7.5, 2, 7.5 - ClimbRoute.PIPE_RADIUS);
                var state = compare(helper, start, MovementVector.ZERO, 100, false, false,
                        tick -> new MovementInput(tick, 0, tick > 20 ? strafe : 0, tick > 20 ? strafe : 0,
                                false, false, false, false, false, 0, 0));
                helper.assertTrue(state.surfaces().climb() != null, "Lost grip traversing " + block);
                helper.assertTrue((state.position().x() - start.x()) * strafe > 2.4, "Did not cross connected columns in the input direction");
                helper.assertTrue(Math.abs(state.position().z() - start.z()) < 1.0E-6, "Orbited a connected panel");
            }
        }
        helper.succeed();
    }

    private MovementGameTests() {}
}
