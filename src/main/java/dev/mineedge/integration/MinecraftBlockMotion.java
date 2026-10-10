package dev.mineedge.integration;

import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.MovementController;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Side-effect-free adaptation of Entity.move and vanilla block velocity callbacks (1.21.1).
 * Calculations at this boundary use blocks/tick, just like the original callbacks.
 * Replay never calls entityInside/stepOn/fallOn, awards stats, damages, or moves the entity.
 */
public final class MinecraftBlockMotion {
    /** Match vanilla's relative ground drag without replacing the reference locomotion on ordinary blocks. */
    public static double groundTraction(Player player, MovementVector feet, MovementBody movementBody) {
        AABB body = MovementPoses.dimensions(player, movementBody).makeBoundingBox(MinecraftMotionAdapter.minecraft(feet));
        if (!MinecraftCollisionWorld.loaded(player, body)) return 1;
        BlockPos pos = onPos(player, feet, support(player, body, feet), 0.500001F);
        var state = player.level().getBlockState(pos);
        // Slime's step callback already supplies its sticky horizontal response.
        if (state.is(Blocks.SLIME_BLOCK)) return 1;
        double friction = state.getFriction(player.level(), pos, player);
        // Vanilla's friction value is momentum retention: larger means more slippery.
        double retention = 0.91 * Math.clamp(friction, (double) 0.6F, 1);
        return Math.log(retention) / Math.log(0.91 * 0.6F);
    }

    public static double jumpFactor(Player player, MovementVector feet, MovementBody movementBody) {
        float factor = player.level().getBlockState(BlockPos.containing(feet.x(), feet.y(), feet.z())).getBlock().getJumpFactor();
        if (factor == 1) {
            AABB body = MovementPoses.dimensions(player, movementBody).makeBoundingBox(MinecraftMotionAdapter.minecraft(feet));
            factor = player.level().getBlockState(onPos(player, feet, support(player, body, feet), 0.500001F)).getBlock().getJumpFactor();
        }
        float strength = (float) player.getAttributeValue(Attributes.JUMP_STRENGTH);
        float boost = player.getJumpBoostPower();
        // Vanilla applies the surface factor before adding Jump Boost.
        return strength + boost > 0 ? (strength * factor + boost) / (double) (strength + boost) : factor;
    }

    /** Native callbacks use tick velocities, but slowdown must be independent of sweep frequency. */
    public static double slowdown(double factor, double seconds) {
        return Math.pow(factor, seconds / MovementTuning.TICK_SECONDS);
    }

    public static Vec3 bounce(Block floor, Vec3 velocity, boolean crouchHeld) {
        if (crouchHeld) return new Vec3(velocity.x, 0, velocity.z);
        if (velocity.y >= 0) return velocity;
        double upward = -velocity.y * (floor instanceof BedBlock ? 0.66F : 0.85);
        // Settle small impacts instead of bouncing every gravity-only ground probe.
        return new Vec3(velocity.x, upward < 0.05 ? 0 : upward, velocity.z);
    }

    public static Vec3 slimeStep(Vec3 velocity, boolean crouchHeld, double seconds) {
        if (crouchHeld || Math.abs(velocity.y) >= 0.1) return velocity;
        double factor = slowdown(0.4 + Math.abs(velocity.y) * 0.2, seconds);
        return velocity.multiply(factor, 1, factor);
    }

    public static MovementController.Motion resolve(Player player, MovementVector position, MovementVector velocity,
                                                     double seconds, BlockMotionState pending) {
        return resolve(player, position, velocity, seconds, pending, MovementPoses.body(player.getPose()), player.isShiftKeyDown());
    }

    public static MovementController.Motion resolve(Player player, MovementVector position, MovementVector velocity,
                                                     double seconds, BlockMotionState pending, MovementBody movementBody, boolean crouchHeld) {
        return resolve(player, position, velocity, seconds, pending, movementBody, crouchHeld, null);
    }

    public static MovementController.Motion resolve(Player player, MovementVector position, MovementVector velocity,
                                                     double seconds, BlockMotionState pending, MovementBody movementBody,
                                                     boolean crouchHeld, CollisionQueries queries) {
        Vec3 desired = MinecraftMotionAdapter.minecraft(velocity.scale(seconds));
        Vec3 v = MinecraftMotionAdapter.minecraft(velocity.scale(1.0 / 20));
        Vec3 stuck = MinecraftMotionAdapter.minecraft(pending.stuckMultiplier());
        if (stuck.lengthSqr() > 1.0E-7) {
            desired = desired.multiply(stuck);
            v = Vec3.ZERO;
        }
        var result = queries == null ? MinecraftCollisionWorld.resolve(player, position, MinecraftMotionAdapter.core(desired), movementBody)
                : queries.resolve(position, MinecraftMotionAdapter.core(desired), movementBody);
        if (result.blockedByOverlap()) {
            return new MovementController.Motion(position, MovementVector.ZERO, false, BlockMotionState.EMPTY, false);
        }
        MovementVector next = position.add(result.recovery()).add(result.displacement());
        AABB body = MovementPoses.dimensions(player, movementBody).makeBoundingBox(MinecraftMotionAdapter.minecraft(next));
        Vec3 displacement = MinecraftMotionAdapter.minecraft(result.displacement());
        BlockPos support = result.grounded() ? support(player, body, next) : null;
        if (result.grounded() && support == null) {
            support = support(player, body.move(-displacement.x, 0, -displacement.z), next);
        }
        BlockPos legacy = onPos(player, next, support, 0.2F);
        var floor = player.level().getBlockState(legacy).getBlock();
        MovementVector clipped = result.clip(MinecraftMotionAdapter.core(v));
        v = new Vec3(clipped.x(), v.y, clipped.z());
        // Entity.move checks requested versus resolved Y exactly. Side-contact fractions can
        // change Y by rounding even without a vertical normal; replay must match that callback.
        boolean vertical = desired.y != displacement.y;
        if (vertical) {
            // updateEntityAfterFallOn: slime and beds bounce living entities; other blocks stop Y.
            if (floor == Blocks.SLIME_BLOCK || floor instanceof BedBlock) {
                v = bounce(floor, v, crouchHeld);
            } else {
                v = new Vec3(v.x, 0, v.z);
            }
        }
        if (result.grounded() && floor == Blocks.SLIME_BLOCK) {
            v = slimeStep(v, crouchHeld, seconds);
        }
        boolean resetFall = false;
        Vec3 nextStuck = Vec3.ZERO;
        // Entity.checkInsideBlocks uses X/Y/Z iteration order and a 1e-7 inset.
        BlockPos min = BlockPos.containing(body.minX + 1.0E-7, body.minY + 1.0E-7, body.minZ + 1.0E-7);
        BlockPos max = BlockPos.containing(body.maxX - 1.0E-7, body.maxY - 1.0E-7, body.maxZ - 1.0E-7);
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = player.level().getBlockState(pos);
                    if (state.is(Blocks.COBWEB)) {
                        resetFall = true; // Entity.makeStuckInBlock resets fall distance as well.
                        nextStuck = player.hasEffect(MobEffects.WEAVING) ? new Vec3(0.5, 0.25, 0.5)
                                : new Vec3(0.25, 0.05F, 0.25);
                    } else if (state.is(Blocks.SWEET_BERRY_BUSH)) {
                        resetFall = true;
                        nextStuck = new Vec3(0.8F, 0.75, 0.8F);
                    } else if (state.is(Blocks.HONEY_BLOCK) && !result.grounded()
                            && next.y() <= y + 0.9375 - 1.0E-7 && v.y < -0.08) {
                        double reach = 0.4375 + player.getBbWidth() / 2.0F;
                        if (Math.abs(x + 0.5 - next.x()) + 1.0E-7 > reach
                                || Math.abs(z + 0.5 - next.z()) + 1.0E-7 > reach) {
                            if (v.y < -0.13) {
                                double factor = -0.05 / v.y;
                                v = new Vec3(v.x * factor, -0.05, v.z * factor);
                            } else {
                                v = new Vec3(v.x, -0.05, v.z);
                            }
                            resetFall = true;
                        }
                    }
                }
            }
        }
        var current = player.level().getBlockState(BlockPos.containing(next.x(), next.y(), next.z()));
        float speed = current.getBlock().getSpeedFactor();
        if (speed == 1 && !current.is(Blocks.WATER) && !current.is(Blocks.BUBBLE_COLUMN)) {
            speed = player.level().getBlockState(onPos(player, next, support, 0.500001F)).getBlock().getSpeedFactor();
        }
        speed = Mth.lerp((float) player.getAttributeValue(Attributes.MOVEMENT_EFFICIENCY), speed, 1);
        float factor = (float) slowdown(speed, seconds);
        v = v.multiply(factor, 1, factor);
        return new MovementController.Motion(next, MinecraftMotionAdapter.core(v).scale(20), result.grounded(),
                new BlockMotionState(MinecraftMotionAdapter.core(nextStuck)), resetFall);
    }

    private static BlockPos support(Player player, AABB body, MovementVector position) {
        AABB feet = new AABB(body.minX, body.minY - 1.0E-6, body.minZ, body.maxX, body.minY, body.maxZ);
        var collisions = new BlockCollisions<BlockPos>(player.level(), player, feet, false,
                (pos, shape) -> pos.immutable());
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        while (collisions.hasNext()) {
            BlockPos pos = collisions.next();
            double distance = pos.distToCenterSqr(position.x(), position.y(), position.z());
            if (distance < bestDistance || distance == bestDistance && (best == null || best.compareTo(pos) < 0)) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private static BlockPos onPos(Player player, MovementVector position, BlockPos support, float offset) {
        if (support == null) {
            return BlockPos.containing(position.x(), position.y() - offset, position.z());
        }
        if (offset <= 0.5 && player.level().getBlockState(support).collisionExtendsVertically(player.level(), support, player)) {
            return support;
        }
        return support.atY(Mth.floor(position.y() - offset));
    }

    private MinecraftBlockMotion() {}
}
