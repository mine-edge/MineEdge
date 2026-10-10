package dev.mineedge.movement.collision;

import dev.mineedge.movement.MovementVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoxWorldTest {
    private static CollisionBox box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new CollisionBox(new MovementVector(x0, y0, z0), new MovementVector(x1, y1, z1));
    }

    @Test
    void additionalContactsPreserveCanonicalGeometryAndLeaveBothSourcesUnchanged() {
        var left = box(-4, 0, -1, -3, 1, 1);
        var middle = box(0, 0, -1, 1, 1, 1);
        var sameMinDifferentHeight = box(0, 0, -1, 1, 2, 1);
        var right = box(4, 0, -1, 5, 1, 1);
        var source = new ArrayList<>(List.of(right, middle, right));
        var contacts = new ArrayList<>(List.of(sameMinDifferentHeight, middle, left, left));
        var world = new BoxWorld(source);
        var combined = world.withAdditional(contacts);
        source.clear();
        contacts.clear();

        var region = box(-10, -10, -10, 10, 10, 10);
        assertEquals(List.of(middle, right), world.shapes(region));
        assertEquals(List.of(left, middle, sameMinDifferentHeight, right), combined.shapes(region));
        assertSame(world, world.withAdditional(List.of()));
        assertSame(middle, combined.shapes(region).get(1));
        assertThrows(UnsupportedOperationException.class, () -> combined.shapes(region).clear());
        for (var shape : combined.shapes(region)) assertTrue(combined.contains(shape));
        assertFalse(combined.contains(box(0, 0, -1, 1, 3, 1)));
        assertEquals(combined.shapes(region), new BoxWorld(List.of()).withAdditional(
                List.of(right, left, middle, sameMinDifferentHeight, left)).shapes(region));
    }

    @Test
    void boundedQueriesMatchFullScansWithWideShapesAndTouchingFaces() {
        var random = new Random(0xB0A);
        var boxes = new ArrayList<CollisionBox>();
        // A box starting far before the query must still be considered if it spans the query.
        boxes.add(box(-1000, -1, -1000, 1000, 0, 1000));
        for (int x = -16; x <= 16; x++) for (int z = -4; z <= 4; z++) {
            double height = 0.0625 + random.nextInt(32) * 0.0625;
            boxes.add(box(x, 0, z, x + 0.5, height, z + 0.5));
        }
        Collections.shuffle(boxes, random);
        var world = new BoxWorld(boxes);
        var canonical = world.shapes(box(-2000, -2, -2000, 2000, 5, 2000));
        assertEquals(boxes.size(), canonical.size());
        assertTrue(canonical.containsAll(boxes));

        for (int query = 0; query < 1000; query++) {
            var feet = new MovementVector(random.nextInt(41) - 20,
                    query % 2 == 0 ? 0 : random.nextDouble() * 3 - 0.1, random.nextInt(13) - 6);
            var body = CollisionBox.body(feet, 0.6, 1.8);
            var delta = new MovementVector(random.nextDouble() * 8 - 4,
                    random.nextDouble() * 4 - 2, random.nextDouble() * 8 - 4);
            var region = body.swept(delta, BoxWorld.TOUCH);
            assertEquals(canonical.stream().anyMatch(body::overlaps), world.overlaps(body));
            assertEquals(canonical.stream().filter(region::overlaps).toList(), world.shapes(region));
            assertEquals(fullSweep(canonical, body, delta), world.sweep(body, delta));
            assertNull(world.sweep(body, MovementVector.ZERO));
        }
    }

    @Test
    void sweepKeepsGroundPriorityEvenWhenTheWallFractionIsSlightlyEarlier() {
        var body = CollisionBox.body(MovementVector.ZERO, 0.6, 1.8);
        var delta = new MovementVector(1, -1, 0);
        var wall = box(0.8 - 5.0E-13, -2, -2, 1.8, 3, 2);
        var floor = box(-2, -1, -2, 2, -0.5, 2);
        for (var boxes : List.of(List.of(wall, floor), List.of(floor, wall))) {
            var hit = new BoxWorld(boxes).sweep(body, delta);
            assertNotNull(hit);
            assertEquals(floor, hit.shape());
            assertEquals(new MovementVector(0, 1, 0), hit.normal());
        }
    }

    /** Linear oracle: no spatial pruning or current-best rejection in the constituent sweeps. */
    private static CollisionWorld.Hit fullSweep(List<CollisionBox> boxes, CollisionBox body, MovementVector delta) {
        CollisionWorld.Hit best = null;
        for (var box : boxes) {
            var hit = BoxWorld.sweepBox(body, delta, box);
            if (hit != null && (best == null || hit.fraction() < best.fraction() - 1.0E-12
                    || Math.abs(hit.fraction() - best.fraction()) <= 1.0E-12
                    && rank(hit.normal()) < rank(best.normal()))) best = hit;
        }
        return best;
    }

    private static int rank(MovementVector normal) {
        return normal.y() != 0 ? 0 : normal.x() != 0 ? 1 : 2;
    }
}
