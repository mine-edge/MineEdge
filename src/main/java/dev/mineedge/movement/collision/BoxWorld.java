package dev.mineedge.movement.collision;

import dev.mineedge.movement.MovementVector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Small immutable fixture/broad-phase adapter. Slab sweep adapted from faith_move/world.rs. */
public final class BoxWorld implements CollisionWorld {
    public static final double TOUCH = 1.0E-7;
    // Geometry order is canonical, independent of Minecraft shape iteration order.
    private static final Comparator<CollisionBox> ORDER = Comparator
            .comparingDouble((CollisionBox b) -> b.min().x()).thenComparingDouble(b -> b.min().y())
            .thenComparingDouble(b -> b.min().z()).thenComparingDouble(b -> b.max().x())
            .thenComparingDouble(b -> b.max().y()).thenComparingDouble(b -> b.max().z());
    private static final int[] AXES = {1, 0, 2}; // Ground/ceiling wins an exact corner tie.
    private final CollisionBox[] boxes;

    public BoxWorld(List<CollisionBox> boxes) {
        CollisionBox[] sorted = boxes.toArray(CollisionBox[]::new);
        Arrays.sort(sorted, ORDER);
        int count = 0;
        for (CollisionBox box : sorted) {
            Objects.requireNonNull(box);
            if (count == 0 || !box.equals(sorted[count - 1])) sorted[count++] = box;
        }
        this.boxes = count == sorted.length ? sorted : Arrays.copyOf(sorted, count);
    }

    /** Takes ownership of an already sorted, unique array. */
    private BoxWorld(CollisionBox[] boxes) {
        this.boxes = boxes;
    }

    /** Merge live contacts into canonical geometry; an empty supplement reuses this world. */
    public BoxWorld withAdditional(List<CollisionBox> additional) {
        if (additional.isEmpty()) return this;
        var extra = new BoxWorld(additional).boxes;
        var combined = new CollisionBox[boxes.length + extra.length];
        int first = 0, second = 0, count = 0;
        // The cached block geometry is already canonical; sort only the new contacts.
        while (first < boxes.length && second < extra.length) {
            int order = ORDER.compare(boxes[first], extra[second]);
            if (order <= 0) {
                combined[count++] = boxes[first++];
                if (order == 0) second++;
            } else {
                combined[count++] = extra[second++];
            }
        }
        int remaining = boxes.length - first;
        System.arraycopy(boxes, first, combined, count, remaining);
        count += remaining;
        remaining = extra.length - second;
        System.arraycopy(extra, second, combined, count, remaining);
        count += remaining;
        return new BoxWorld(count == combined.length ? combined : Arrays.copyOf(combined, count));
    }

    @Override
    public Hit sweep(CollisionBox body, MovementVector delta) {
        if (delta.x() == 0 && delta.y() == 0 && delta.z() == 0) return null;
        Hit best = null;
        double minX = body.min().x() + Math.min(0, delta.x()) - TOUCH;
        double minY = body.min().y() + Math.min(0, delta.y()) - TOUCH;
        double minZ = body.min().z() + Math.min(0, delta.z()) - TOUCH;
        double maxX = body.max().x() + Math.max(0, delta.x()) + TOUCH;
        double maxY = body.max().y() + Math.max(0, delta.y()) + TOUCH;
        double maxZ = body.max().z() + Math.max(0, delta.z()) + TOUCH;
        for (CollisionBox box : boxes) {
            // Canonical order is ascending min-X, so later boxes cannot enter this sweep.
            if (box.min().x() >= maxX) break;
            if (box.max().x() <= minX || box.max().y() <= minY || box.min().y() >= maxY
                    || box.max().z() <= minZ || box.min().z() >= maxZ) continue;
            Hit hit = sweepBox(body, delta, box, best);
            if (hit != null) best = hit;
        }
        return best;
    }

    private static int normalRank(MovementVector normal) {
        return normal.y() != 0 ? 0 : normal.x() != 0 ? 1 : 2;
    }

    public static Hit sweepBox(CollisionBox body, MovementVector delta, CollisionBox box) {
        return sweepBox(body, delta, box, null);
    }

    private static Hit sweepBox(CollisionBox body, MovementVector delta, CollisionBox box, Hit best) {
        if (body.overlaps(box)) {
            return null; // Handled by bounded initial-overlap recovery, never by a sweep.
        }
        double enterStrict = Double.NEGATIVE_INFINITY;
        double exitStrict = Double.POSITIVE_INFINITY;
        double fraction = 0;
        int normalAxis = -1;
        double normalSign = 0;
        for (int axis : AXES) {
            double d = delta.axis(axis);
            double lo = box.min().axis(axis) - body.max().axis(axis);
            double hi = box.max().axis(axis) - body.min().axis(axis);
            if (Math.abs(d) < 1.0E-12) {
                if (lo >= -TOUCH || hi <= TOUCH) {
                    return null;
                }
                continue;
            }
            double a = (lo + TOUCH) / d;
            double b = (hi - TOUCH) / d;
            double entry = Math.min(a, b);
            if (entry > enterStrict) {
                enterStrict = entry;
                fraction = (d > 0 ? lo : hi) / d;
                normalAxis = axis;
                normalSign = -Math.signum(d);
            }
            exitStrict = Math.min(exitStrict, Math.max(a, b));
        }
        if (normalAxis < 0 || enterStrict >= exitStrict
                || enterStrict <= 0 || exitStrict <= 0 || fraction >= 1) {
            return null;
        }
        fraction = Math.max(0, fraction);
        int rank = normalAxis == 1 ? 0 : normalAxis == 0 ? 1 : 2;
        // Do not allocate a normal, point and hit for a losing candidate. Keep the
        // original epsilon and ground/X/Z tie priority for prediction determinism.
        if (best != null && !(fraction < best.fraction() - 1.0E-12
                || Math.abs(fraction - best.fraction()) <= 1.0E-12
                && rank < normalRank(best.normal()))) return null;
        MovementVector normal = MovementVector.axis(normalAxis, normalSign);
        MovementVector point = new MovementVector(
                Math.clamp((body.min().x() + body.max().x()) * 0.5 + delta.x() * fraction
                        - normal.x() * (body.max().x() - body.min().x()) * 0.5, box.min().x(), box.max().x()),
                Math.clamp((body.min().y() + body.max().y()) * 0.5 + delta.y() * fraction
                        - normal.y() * (body.max().y() - body.min().y()) * 0.5, box.min().y(), box.max().y()),
                Math.clamp((body.min().z() + body.max().z()) * 0.5 + delta.z() * fraction
                        - normal.z() * (body.max().z() - body.min().z()) * 0.5, box.min().z(), box.max().z()));
        return new Hit(fraction, normal, point, box);
    }

    @Override
    public boolean overlaps(CollisionBox body) {
        for (CollisionBox box : boxes) {
            if (body.max().x() <= box.min().x() + TOUCH) break;
            if (body.overlaps(box)) return true;
        }
        return false;
    }

    @Override
    public boolean contains(CollisionBox shape) {
        return Arrays.binarySearch(boxes, shape, ORDER) >= 0;
    }

    @Override
    public List<CollisionBox> shapes(CollisionBox region) {
        List<CollisionBox> found = null;
        for (CollisionBox box : boxes) {
            if (region.max().x() <= box.min().x() + TOUCH) break;
            if (region.overlaps(box)) {
                if (found == null) found = new ArrayList<>();
                found.add(box);
            }
        }
        return found == null ? List.of() : List.copyOf(found);
    }

    @Override
    public MovementVector separation(CollisionBox body) {
        int bestAxis = -1;
        double bestDistance = 0;
        double bestLength = Double.POSITIVE_INFINITY;
        for (CollisionBox box : boxes) {
            if (body.max().x() <= box.min().x() + TOUCH) break;
            if (!body.overlaps(box)) {
                continue;
            }
            for (int axis : AXES) {
                double negative = box.min().axis(axis) - body.max().axis(axis);
                double positive = box.max().axis(axis) - body.min().axis(axis);
                double distance = Math.abs(negative) < positive ? negative : positive;
                double length = Math.sqrt(distance * distance);
                if (bestAxis < 0 || length < bestLength - TOUCH) {
                    bestAxis = axis;
                    bestDistance = distance;
                    bestLength = length;
                }
            }
        }
        return bestAxis < 0 ? null : MovementVector.axis(bestAxis, bestDistance);
    }
}
