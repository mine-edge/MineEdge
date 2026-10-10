package dev.mineedge.integration;

import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfaceContact;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.collision.CollisionWorld;
import dev.mineedge.integration.mixin.EntityMotionAccessor;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Shared sweep collision; live Entity.move retains Minecraft callbacks without a second resolver. */
public final class MinecraftMotionAdapter {
    public static MovementVector core(Vec3 vector) {
        return new MovementVector(vector.x, vector.y, vector.z);
    }

    public static Vec3 minecraft(MovementVector vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    public static MovementState capture(Player player) {
        return new MovementState(core(player.position()), core(player.getDeltaMovement()).scale(20),
                player.onGround() ? MovementState.Mode.GROUND : MovementState.Mode.AIR,
                0, player.onGround(), player.fallDistance, LocomotionState.EMPTY, blockState(player),
                MovementPoses.body(player.getPose()), player.getYRot());
    }

    public static BlockMotionState blockState(Player player) {
        Vec3 multiplier = ((EntityMotionAccessor) player).mineedge$stuckMultiplier();
        return multiplier.equals(Vec3.ZERO) ? BlockMotionState.EMPTY : new BlockMotionState(core(multiplier));
    }

    public static MovementController.MotionResolver live(Player player) {
        return new MovementController.MotionResolver() {
            private final CollisionQueries queries = new CollisionQueries(player);
            private final MinecraftMovementSurfaces surfaces = new MinecraftMovementSurfaces(player);
            private LandingState landing;
            private MovementTuning tuning;

            @Override
            public void begin(MovementState state) { queries.begin(); }

            @Override
            public void landing(LandingState state, MovementTuning tuning) {
                this.landing = state;
                this.tuning = tuning;
            }
            @Override
            public void input(MovementInput input) {
                player.setShiftKeyDown(input.crouchHeld());
            }

            @Override public void facing(float yaw) { player.setYRot(yaw); player.setYHeadRot(yaw); }
            @Override public void relocate(MovementVector position) { player.setPos(minecraft(position)); }
            @Override public void clearFallDistance() { player.resetFallDistance(); }
            @Override public void ledgeGrab(float fallDistance, MovementTuning tuning) {
                if (!player.level().isClientSide() && fallDistance > 0) {
                    ResolvedMovement.ledgeGrab(player, fallDistance, tuning);
                }
                player.resetFallDistance();
            }
            @Override public SurfaceContact surface(MovementVector feet, MovementBody body) { return surfaces.support(feet, body); }
            @Override public boolean stepSurface(CollisionBox shape) { return shape != null && surfaces.step(shape); }
            @Override public boolean stepSource(MovementVector source) { return surfaces.stepSource(source); }
            @Override public ClimbRoute climbRoute(MovementVector feet, MovementVector normal) { return surfaces.climb(feet, normal); }
            @Override public boolean climbValid(ClimbRoute route) { return surfaces.valid(route); }
            @Override public ClimbRoute climbSideways(ClimbRoute route, double height, double distance) { return surfaces.sideways(route, height, distance); }
            @Override public double jumpFactor(MovementVector position, MovementBody body) { return MinecraftBlockMotion.jumpFactor(player, position, body); }
            @Override public double groundTraction(MovementVector position, MovementBody body) { return MinecraftBlockMotion.groundTraction(player, position, body); }

            @Override
            public void pushDoors(MovementVector position, MovementVector velocity, MovementBody body, double seconds) {
                MinecraftDoorPush.push(player, queries, position, velocity, body, seconds);
            }

            @Override
            public void body(MovementBody body) {
                MovementPoses.apply(player, body);
                if (body != MovementBody.STANDING) {
                    player.setSprinting(false);
                }
            }

            @Override
            public boolean canOccupy(MovementVector position, MovementBody body) {
                return queries.canOccupy(position, body);
            }

            @Override
            public CollisionBox bounds(MovementVector position, MovementBody body) {
                return MinecraftCollisionWorld.bounds(player, position, body);
            }

            @Override
            public CollisionWorld geometry(CollisionBox region) {
                return queries.geometry(region);
            }

            @Override
            public MovementVector position(MovementVector simulated) {
                return core(player.position());
            }

            @Override
            public void synchronize(MovementVector velocity, boolean grounded, MovementBody body) {
                body(body);
                player.setDeltaMovement(minecraft(velocity.scale(1.0 / 20)));
                player.setOnGround(grounded);
            }

            @Override
            public double maxStepHeight() { return player.maxUpStep(); }

            @Override
            public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds, double stepHeight) {
                return resolve(position, velocity, seconds, stepHeight, player.onGround());
            }

            @Override
            public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds, double stepHeight, boolean grounded) {
                queries.grounded(grounded);
                queries.stepHeight(stepHeight);
                try { return resolve(position, velocity, seconds); }
                finally { queries.stepHeight(0); }
            }

            @Override
            public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds) {
                Vec3 desired = minecraft(velocity.scale(seconds));
                Vec3 before = player.position();
                float fallBefore = player.fallDistance;
                player.setDeltaMovement(minecraft(velocity.scale(1.0 / 20)));
                var result = ResolvedMovement.run(player, landing, tuning, Math.max(0, -velocity.y()), queries, seconds,
                        () -> player.move(MoverType.SELF, desired));
                if (result != null) {
                    // Minecraft's horizontal flag uses a coarser tolerance than the core.
                    // Clip these components explicitly; retain vertical block responses (e.g. slime).
                    MovementVector after = core(player.getDeltaMovement());
                    MovementVector clipped = result.clip(after);
                    player.setDeltaMovement(result.blockedByOverlap() ? Vec3.ZERO
                            : new Vec3(clipped.x(), after.y(), clipped.z()));
                    player.setOnGround(result.grounded());
                }
                return new MovementController.Motion(core(player.position()), core(player.getDeltaMovement()).scale(20),
                        player.onGround(), blockState(player), player.fallDistance == 0
                                && (fallBefore > 0 || player.getY() < before.y));
            }
        };
    }

    public static MovementController.MotionResolver replay(Player player) {
        return new MovementController.MotionResolver() {
            private final CollisionQueries queries = new CollisionQueries(player);
            private final MinecraftMovementSurfaces surfaces = new MinecraftMovementSurfaces(player);
            private BlockMotionState pending = BlockMotionState.EMPTY;
            private MovementBody body = MovementBody.STANDING;
            private boolean crouch;

            @Override
            public void begin(MovementState state) {
                queries.begin();
                pending = state.blocks();
                body = state.body();
            }

            @Override
            public void input(MovementInput input) {
                crouch = input.crouchHeld();
            }

            @Override public SurfaceContact surface(MovementVector feet, MovementBody body) { return surfaces.support(feet, body); }
            @Override public boolean stepSurface(CollisionBox shape) { return shape != null && surfaces.step(shape); }
            @Override public boolean stepSource(MovementVector source) { return surfaces.stepSource(source); }
            @Override public ClimbRoute climbRoute(MovementVector feet, MovementVector normal) { return surfaces.climb(feet, normal); }
            @Override public boolean climbValid(ClimbRoute route) { return surfaces.valid(route); }
            @Override public ClimbRoute climbSideways(ClimbRoute route, double height, double distance) { return surfaces.sideways(route, height, distance); }
            @Override public double jumpFactor(MovementVector position, MovementBody body) { return MinecraftBlockMotion.jumpFactor(player, position, body); }
            @Override public double groundTraction(MovementVector position, MovementBody body) { return MinecraftBlockMotion.groundTraction(player, position, body); }

            @Override
            public void body(MovementBody body) {
                this.body = body;
            }

            @Override
            public boolean canOccupy(MovementVector position, MovementBody body) {
                return queries.canOccupy(position, body);
            }

            @Override
            public CollisionBox bounds(MovementVector position, MovementBody body) {
                return MinecraftCollisionWorld.bounds(player, position, body);
            }

            @Override
            public CollisionWorld geometry(CollisionBox region) {
                return queries.geometry(region);
            }

            @Override
            public double maxStepHeight() { return player.maxUpStep(); }

            @Override
            public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds, double stepHeight) {
                return resolve(position, velocity, seconds, stepHeight, false);
            }

            @Override
            public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds, double stepHeight, boolean grounded) {
                queries.grounded(grounded);
                queries.stepHeight(stepHeight);
                try { return resolve(position, velocity, seconds); }
                finally { queries.stepHeight(0); }
            }

            @Override
            public MovementController.Motion resolve(MovementVector position, MovementVector velocity, double seconds) {
                var motion = MinecraftBlockMotion.resolve(player, position, velocity, seconds, pending, body, crouch, queries);
                pending = motion.blocks();
                return motion;
            }
        };
    }

    public static void apply(Player player, MovementState state) {
        player.setPos(minecraft(state.position()));
        MovementPoses.apply(player, state.body());
        if (state.mode() == MovementState.Mode.HARD_LANDING) player.setSprinting(false);
        player.setDeltaMovement(minecraft(state.velocity().scale(1.0 / 20)));
        player.setOnGround(state.grounded());
        player.fallDistance = state.fallDistance();
        if (state.advanced().turn() != null) {
            player.setYRot(state.advanced().turn().yaw());
            player.setYHeadRot(player.getYRot());
        }
        if (state.mode() == MovementState.Mode.STEEP_SLIDE) {
            player.setYRot(state.surfaces().slopeYaw());
            player.setYHeadRot(player.getYRot());
        }
        ((EntityMotionAccessor) player).mineedge$stuckMultiplier(minecraft(state.blocks().stuckMultiplier()));
    }

    private MinecraftMotionAdapter() {}
}
