package dev.mineedge.movement;

import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.traversal.TraversalPath;

/** Phase 8 transient state. Included in every authoritative snapshot, never saved to disk. */
public record AdvancedState(boolean coiled, boolean wallTurned, Turn turn, LedgeMove ledge, Springboard spring) {
    public static final AdvancedState EMPTY = new AdvancedState(false, false, null, null, null);
    public record Turn(float from, float delta, double elapsed, double duration) {
        public float yaw() { double k = Math.clamp(elapsed / duration, 0, 1); return from + delta * (float) (k * k * (3 - 2 * k)); }
        public boolean active() { return elapsed + 1.0E-9 < duration; }
    }
    public enum LedgeKind { SHIMMY, CORNER, TRANSFER, RETURN }
    private static final double[] SHIMMY_PROFILE = {0, 0, 0, 0.22, 0.5, 0.75, 0.92, 1, 1};
    public record LedgeMove(LedgeKind kind, MovementVector from, MovementVector via, MovementVector to,
                            MovementVector normal, CollisionBox support, CollisionBox originSupport,
                            double elapsed, double duration, float yawFrom, float yawDelta) {
        public MovementVector position(double time) {
            double u = Math.clamp(time / duration, 0, 1);
            if (kind == LedgeKind.RETURN) {
                u = u * u * (3 - 2 * u);
                double retreat = via.subtract(from).length(), drop = to.subtract(via).length();
                double split = retreat / Math.max(1.0E-9, retreat + drop);
                return u < split ? from.add(via.subtract(from).scale(u / split))
                        : via.add(to.subtract(via).scale(Math.clamp((u - split) / Math.max(1.0E-9, 1 - split), 0, 1)));
            }
            if (kind == LedgeKind.SHIMMY) {
                double x = u * 8; int i = Math.min(7, (int) x);
                u = SHIMMY_PROFILE[i] + (SHIMMY_PROFILE[i + 1] - SHIMMY_PROFILE[i]) * (x - i);
            } else if (kind == LedgeKind.CORNER) u = u * u * (3 - 2 * u);
            return u < 0.5 ? from.add(via.subtract(from).scale(u * 2)) : via.add(to.subtract(via).scale((u - 0.5) * 2));
        }
    }
    public record Springboard(TraversalPath path, double speed, double elapsed) {}
    public AdvancedState advance(double dt) {
        return new AdvancedState(coiled,
                wallTurned, turn == null ? null : new Turn(turn.from, turn.delta, Math.min(turn.duration, turn.elapsed + dt), turn.duration),
                ledge, spring);
    }
    public AdvancedState coil(boolean value) { return new AdvancedState(value, wallTurned, turn, ledge, spring); }
    public AdvancedState turn(Turn value, boolean wall) { return new AdvancedState(coiled, wall, value, ledge, spring); }
    public AdvancedState ledge(LedgeMove value) { return new AdvancedState(coiled, wallTurned, turn, value, spring); }
    public AdvancedState spring(Springboard value) { return new AdvancedState(coiled, wallTurned, turn, ledge, value); }
    public AdvancedState landed() { return new AdvancedState(false, false, turn, null, null); }
}
