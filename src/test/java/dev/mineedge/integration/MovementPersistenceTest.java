package dev.mineedge.integration;

import dev.mineedge.movement.AdvancedState;
import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfacePath;
import dev.mineedge.movement.surface.SurfaceState;
import dev.mineedge.movement.traversal.TraversalPath;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.traversal.VaultType;
import dev.mineedge.movement.wall.WallState;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MovementPersistenceTest {
    private static final MovementSessionSerializer SERIALIZER = new MovementSessionSerializer();
    private static final ResourceLocation DIMENSION = ResourceLocation.parse("minecraft:the_nether");

    private static MovementSession session(MovementState.Mode mode) {
        var p = new MovementVector(29_000_000.25, 70.125, -29_000_000.5);
        var v = new MovementVector(-0.0, -12.75, 6.25);
        var n = new MovementVector(0, 0, -1);
        var box = new CollisionBox(p, p.add(new MovementVector(1, 2, 1)));
        var path = new TraversalPath(VaultType.SPRINGBOARD, p, p.add(new MovementVector(0, 1, 0)),
                p.add(new MovementVector(0, 1, 2)), p.add(new MovementVector(0, 0, 3)), v, box, null);
        var route = new ClimbRoute(p, p.y() + 5, n, true, p);
        var surfaces = new SurfaceState(new SurfaceState.Climb(route, p, 0.2, 1, 2, 0.12, 0.5, true),
                new SurfacePath(SurfacePath.Kind.ATTACH, p, p, p, p, v, null, p, 0.6, 0.12, MovementBody.CROUCHING),
                0.2, 0.3, 0.4, 0.1, 0.7, n, 179, v);
        var advanced = new AdvancedState(true, true, new AdvancedState.Turn(179, 180, 0.12, 0.2),
                new AdvancedState.LedgeMove(AdvancedState.LedgeKind.values()[0], p, p, p, n, box, box, 0.12, 0.7, 179, 90),
                new AdvancedState.Springboard(path, 6.25, 0.12));
        var session = new MovementSession();
        session.active = true;
        session.dimension = DIMENSION;
        session.state = new MovementState(p, v, mode, 0.12, false, 15.5F,
                new LocomotionState(2.4, 1.5, 0.2, 0.1, 179, true),
                new BlockMotionState(new MovementVector(0.25, 0.05, 0.25)), MovementBody.LOW, 179,
                new TraversalState(path, 0.15, v, new MovementVector(2, 7, 6)),
                new WallState(n, box, p, v, n, p, true, true, true, 3, 0.15),
                new LandingState(p.y() + 15.5, 15.5, 12.75, LandingState.Source.WALL_JUMP, 6.25, 60, 0.15, true),
                advanced, surfaces);
        return session;
    }

    @Test void diskRoundTripPreservesCompleteStateInEveryMovementMode(@TempDir Path directory) throws IOException {
        for (var mode : MovementState.Mode.values()) {
            var original = session(mode);
            original.simulationStep = 500;
            original.resetRequested = true;
            original.tuning = MovementTuning.DEFAULT;
            original.input = MovementInput.idle(100, 179, 30);
            var path = directory.resolve(mode.name() + ".dat");
            NbtIo.writeCompressed(SERIALIZER.write(original, null), path);
            var restored = SERIALIZER.read(null, NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()), null);
            assertEquals(original.state, restored.state);
            assertEquals(DIMENSION, restored.dimension);
            assertTrue(restored.resumePending);
            assertFalse(restored.active);
            assertNotEquals(original.epoch, restored.epoch);
            assertEquals(0, restored.simulationStep);
            assertEquals(MovementInput.idle(0, 0, 0), restored.input);
            assertNull(restored.simulation);
            assertNull(restored.tuning);
            assertFalse(restored.resetRequested);
        }
    }

    @Test void logoutSuspensionRetainsStateThroughRepeatedCleanupAndResaving() {
        var original = session(MovementState.Mode.SLIDE);
        var state = original.state;
        long epoch = original.epoch;
        original.suspend();
        original.suspend();
        assertFalse(original.active);
        assertTrue(original.resumePending);
        assertEquals(state, original.state);
        assertNotEquals(epoch, original.epoch);
        var restored = SERIALIZER.read(null, SERIALIZER.write(original, null), null);
        assertEquals(state, restored.state);
        assertTrue(restored.canResume(DIMENSION, state.position()));
    }

    @Test void savedPathsOnlyResumeInTheirOriginalDimensionAndPosition() {
        var original = session(MovementState.Mode.LEDGE_HANG);
        original.suspend();
        assertTrue(original.canResume(DIMENSION, original.state.position()));
        assertFalse(original.canResume(ResourceLocation.parse("minecraft:overworld"), original.state.position()));
        assertFalse(original.canResume(DIMENSION, original.state.position().add(new MovementVector(0, 1, 0))));
    }

    @Test void explicitResetClearsPersistentStateAndFreshSessionsDoNotSave() {
        assertNull(SERIALIZER.write(new MovementSession(), null));
        var original = session(MovementState.Mode.ROLL);
        original.suspend();
        original.reset();
        assertNull(original.state);
        assertNull(original.dimension);
        assertFalse(original.resumePending);
        assertNull(SERIALIZER.write(original, null));
    }

    @Test void incompatibleAndTruncatedSavesAreRejected() {
        var tag = SERIALIZER.write(session(MovementState.Mode.VAULT), null);
        var future = tag.copy();
        future.putInt("version", Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> SERIALIZER.read(null, future, null));
        tag.putByteArray("state", new byte[0]);
        assertThrows(IndexOutOfBoundsException.class, () -> SERIALIZER.read(null, tag, null));
    }
}
