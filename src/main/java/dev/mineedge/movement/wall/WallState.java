package dev.mineedge.movement.wall;

import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.CollisionBox;

/** Transient contact and airborne history; every field participates in snapshot replay. */
public record WallState(MovementVector normal, CollisionBox support, MovementVector anchor,
                        MovementVector expectedVelocity, MovementVector previousNormal, MovementVector jumpOrigin,
                        boolean jumped, boolean climbed, boolean wallJump, int chain, double cooldown) {
    public static final WallState EMPTY = new WallState(MovementVector.ZERO, null, MovementVector.ZERO,
            MovementVector.ZERO, MovementVector.ZERO, MovementVector.ZERO, false, false, false, 0, 0);

    public WallState jump(MovementVector origin) {
        return new WallState(normal, support, anchor, expectedVelocity, previousNormal, origin, true, climbed, wallJump, chain, cooldown);
    }

    public WallState advance(double dt) {
        if (cooldown == 0) return this;
        return new WallState(normal, support, anchor, expectedVelocity, previousNormal, jumpOrigin, jumped, climbed,
                wallJump, chain, Math.max(0, cooldown - dt));
    }

    public WallState contact(MovementVector n, CollisionBox shape, MovementVector position, MovementVector velocity,
                             boolean climbing, int chains) {
        return new WallState(n, shape, position, velocity, n, jumpOrigin, jumped, climbed || climbing, wallJump, chains, cooldown);
    }

    public WallState release(boolean jump, MovementVector position, double delay) {
        return new WallState(MovementVector.ZERO, null, MovementVector.ZERO, MovementVector.ZERO, previousNormal,
                jump ? position : jumpOrigin, jumped || jump, climbed, wallJump || jump, chain, delay);
    }
}
