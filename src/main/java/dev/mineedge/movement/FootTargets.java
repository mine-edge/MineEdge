package dev.mineedge.movement;

/** Candidate traversal foot contacts on the grip plane; the renderer checks actual support. */
public record FootTargets(MovementVector left, MovementVector right, float leftWeight, float rightWeight) {
    private static final double GRIP_TO_FOOT_HEIGHT = 0.9;
    private static final double LEDGE_HANG_LOWERING = 0.5;
    public static final FootTargets EMPTY = new FootTargets(MovementVector.ZERO, MovementVector.ZERO, 0, 0);

    public boolean active() { return leftWeight > 0 || rightWeight > 0; }

    public static FootTargets fromGrips(HandTargets grips) {
        return fromGrips(grips, false);
    }

    public static FootTargets fromGrips(HandTargets grips, boolean ledgeHanging) {
        if (!grips.active()) return EMPTY;
        double height = GRIP_TO_FOOT_HEIGHT + (ledgeHanging ? LEDGE_HANG_LOWERING : 0);
        // Feet have a narrower stance than hands. Keep them on the same contact plane,
        // and counterstep opposite hands when both sides are attached to the surface.
        var center = grips.left().add(grips.right()).scale(0.5);
        var left = center.add(grips.left().subtract(center).scale(0.55));
        var right = center.add(grips.right().subtract(center).scale(0.55));
        boolean paired = grips.leftWeight() > 0 && grips.rightWeight() > 0;
        return new FootTargets(new MovementVector(left.x(), (paired ? grips.right() : grips.left()).y() - height, left.z()),
                new MovementVector(right.x(), (paired ? grips.left() : grips.right()).y() - height, right.z()),
                grips.leftWeight(), grips.rightWeight());
    }
}
