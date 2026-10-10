package dev.mineedge.network;

import dev.mineedge.config.ServerMovementSettings;
import dev.mineedge.movement.MovementInput;
import dev.mineedge.movement.LocomotionState;
import dev.mineedge.movement.BlockMotionState;
import dev.mineedge.movement.MovementBody;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.traversal.TraversalPath;
import dev.mineedge.movement.traversal.VaultType;
import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.MovementState;
import dev.mineedge.movement.MovementTuning;
import dev.mineedge.movement.MovementFeatures;
import dev.mineedge.movement.MovementVector;
import dev.mineedge.movement.WallRunPose;
import dev.mineedge.movement.wall.WallState;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.AdvancedState;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfacePath;
import dev.mineedge.movement.surface.SurfaceState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class MovementPayloadTest {
    @Test void trackingPosesRoundTripWithCompactGripsSlidesWallrunsAndRelease() {
        var uuid = java.util.UUID.randomUUID();
        for (var hands : new dev.mineedge.movement.HandTargets[]{dev.mineedge.movement.HandTargets.EMPTY,
                new dev.mineedge.movement.HandTargets(new MovementVector(-0.25, 1.75, 0.5),
                        new MovementVector(0.25, 1.5, 0.5), 1, 0.75F)}) for (boolean hanging : new boolean[]{false, true})
                for (boolean sliding : new boolean[]{false, true})
                for (var wallrun : new WallRunPose[]{WallRunPose.EMPTY, new WallRunPose(179.5F, -1, 5.6F), new WallRunPose(-90, 1, 3)}) {
            var payload = new HandTargetsPayload(12, uuid, hands, hanging, sliding, sliding ? -179.5F : 0, wallrun);
            var buf = buffer();
            try {
                HandTargetsPayload.CODEC.encode(buf, payload);
                assertTrue(buf.readableBytes() <= 66);
                if (!hands.active()) assertEquals(18 + (sliding ? 4 : 0) + (wallrun.active() ? 12 : 0), buf.readableBytes());
                assertEquals(payload, HandTargetsPayload.CODEC.decode(buf));
                assertEquals(0, buf.readableBytes());
                assertEquals(hands.active() || sliding || wallrun.active(), payload.active());
            } finally { buf.release(); }
        }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }

    @Test void surfaceSnapshotsPreserveClimbStepsPathsAndSteepSlideRecovery() {
        var p = new MovementVector(2, 3, 4);
        var support = new CollisionBox(new MovementVector(1, 0, 5), new MovementVector(4, 5, 8));
        for (var kind : SurfacePath.Kind.values()) {
            var route = new ClimbRoute(new MovementVector(2, 1, 5), 8, new MovementVector(0, 0, -1), kind == SurfacePath.Kind.EXIT_TOP, new MovementVector(2, 1, 5));
            var climb = new SurfaceState.Climb(route, p, 0.25, 3, 3.32, 0.17, 1.0 / 3, true);
            var path = new SurfacePath(kind, p, p.add(new MovementVector(0, 1, 0)), p.add(new MovementVector(0, 1, 1)),
                    p.add(new MovementVector(0, 0.5, 1)), new MovementVector(0, 0, 3), kind == SurfacePath.Kind.ATTACH ? null : support,
                    route.source(), 0.65, 0.12, MovementBody.CROUCHING);
            var surfaces = new SurfaceState(climb, path, 0.5, 0.2, 1, 0.1, 0.3, new MovementVector(0, 0, 1), 179, new MovementVector(-0.0, 2, 3));
            var state = new MovementState(p, new MovementVector(0, 0, 3), MovementState.Mode.CLIMB_ATTACH, 0.12, false, 0,
                    LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.CROUCHING, 0, TraversalState.EMPTY, WallState.EMPTY,
                    LandingState.capture(p, false, 0), AdvancedState.EMPTY, surfaces);
            assertEquals(surfaces, state.withVelocity(MovementVector.ZERO).surfaces());
            var payload = new SnapshotPayload(1, 10, 20, state, Pose.CROUCHING);
            var buf = buffer();
            try {
                SnapshotPayload.CODEC.encode(buf, payload);
                assertEquals(payload, SnapshotPayload.CODEC.decode(buf)); assertEquals(0, buf.readableBytes());
            } finally { buf.release(); }
        }
    }

    @Test void advancedSnapshotsPreserveTurnsLedgePathsSpringboardsAndRecovery() {
        var p = new MovementVector(2, 3, 4);
        var shape = new CollisionBox(new MovementVector(1, 0, 5), new MovementVector(4, 5, 8));
        for (var kind : AdvancedState.LedgeKind.values()) {
            var path = new TraversalPath(VaultType.SPRINGBOARD, p, p.add(new MovementVector(0, 2, 0)),
                    p.add(new MovementVector(0, 2, 2)), p.add(new MovementVector(0, 1, 2)), new MovementVector(0, 9.5, 5), shape, shape);
            var advanced = new AdvancedState(true, true, true, new AdvancedState.Turn(179, 180, 0.125, 0.2),
                    new AdvancedState.LedgeMove(kind, p, p.add(new MovementVector(1, 0, 0)), p.add(new MovementVector(1, 1, 0)),
                            new MovementVector(0, 0, -1), shape, shape, 0.25, 1.07, 179, 90),
                    new AdvancedState.Springboard(path, 6.5, 0.3), 0.41, true);
            var s = new MovementState(p, new MovementVector(0, 0, 6), MovementState.Mode.LEDGE_HANG, 1, false, 0,
                    LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.CROUCHING, 0, TraversalState.EMPTY,
                    WallState.EMPTY, LandingState.capture(p, false, 0), advanced);
            var payload = new SnapshotPayload(1, 10, 20, s, Pose.CROUCHING);
            var buf = buffer();
            try {
                SnapshotPayload.CODEC.encode(buf, payload);
                assertEquals(payload, SnapshotPayload.CODEC.decode(buf));
                assertEquals(0, buf.readableBytes());
            } finally { buf.release(); }
        }
    }

    @Test
    void compactSnapshotsAreLosslessIncludingSignedZeroAndLargeCoordinates() {
        Random random = new Random(9);
        for (int i = 0; i < 120; i++) {
            RegistryFriendlyByteBuf buffer = buffer();
            try {
                var position = new MovementVector(29_000_000 + random.nextDouble(), -64 + random.nextDouble(), -29_000_000 + random.nextDouble());
                var velocity = new MovementVector(i % 2 == 0 ? -0.0 : random.nextDouble(), i % 3 == 0 ? 0 : random.nextDouble(), random.nextDouble());
                var normal = new MovementVector(-1, 0, 0);
                var shape = new CollisionBox(position.add(new MovementVector(1, -1, -1)), position.add(new MovementVector(2, 3, 1)));
                var wall = i % 4 == 0 ? WallState.EMPTY : new WallState(normal, i % 3 == 0 ? null : shape,
                        i % 2 == 0 ? position : MovementVector.ZERO, i % 2 == 0 ? velocity : MovementVector.ZERO,
                        i % 2 == 0 ? normal : new MovementVector(1, 0, 0), position, true, false, true, i, i % 2 == 0 ? 0 : 0.15);
                var state = new MovementState(position, velocity, MovementState.Mode.values()[i % MovementState.Mode.values().length],
                        i % 2 == 0 ? -0.0 : random.nextDouble(), false, -0.0F,
                        new LocomotionState(-0.0, 0, 60, Double.MIN_VALUE, -0.0F, true), BlockMotionState.EMPTY, MovementBody.LOW, -0.0F,
                        i % 2 == 0 ? TraversalState.EMPTY : new TraversalState(null, 0.15, velocity), wall,
                        new LandingState(position.y(), 0, 12, LandingState.Source.WALL_JUMP, 6, 60, random.nextDouble(), true));
                var payload = new SnapshotPayload(1, i, i + 100, state, Pose.SWIMMING);
                SnapshotPayload.CODEC.encode(buffer, payload);
                assertEquals(payload, SnapshotPayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }

    @Test
    void ordinarySnapshotOmitsSessionMetadataAndEmptyReplayBodies() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            var state = new MovementState(new MovementVector(10, 64, 10), new MovementVector(0, 0, 4), MovementState.Mode.GROUND, 1, true, 0);
            var payload = new SnapshotPayload(1, 100, 100, state, Pose.STANDING);
            SnapshotPayload.CODEC.encode(buffer, payload);
            assertTrue(buffer.readableBytes() < 100, "Session metadata or empty replay state expanded the routine snapshot");
            assertEquals(payload, SnapshotPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test
    void featureOnlyTuningChangesRoundTripInSessions() {
        for (int flags = 0; flags < 128; flags++) {
            var features = new MovementFeatures((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                    (flags & 8) != 0, (flags & 16) != 0, (flags & 32) != 0, (flags & 64) != 0);
            var original = new SessionPayload(ResourceLocation.parse("minecraft:overworld"), 1, 100,
                    true, true, MovementTuning.DEFAULT.withFeatures(features));
            var buffer = buffer();
            try {
                SessionPayload.CODEC.encode(buffer, original);
                assertEquals(original, SessionPayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void sessionRoundTripPreservesOwnershipDimensionAndPlayerEffectiveTuning() {
        for (int flags = 0; flags < 4; flags++) {
            for (var tuning : new MovementTuning[]{MovementTuning.DEFAULT,
                    new MovementTuning(5, 7, 20, 61, 2, 5, 0.3, 0.75, 2.4, 1.5, 5)}) {
                var original = new SessionPayload(ResourceLocation.parse("minecraft:the_nether"), 1L << 40,
                        1L << 33, (flags & 1) != 0, (flags & 2) != 0, tuning);
                var buffer = buffer();
                try {
                    SessionPayload.CODEC.encode(buffer, original);
                    assertEquals(original, SessionPayload.CODEC.decode(buffer));
                    assertEquals(0, buffer.readableBytes());
                } finally { buffer.release(); }
            }
        }
    }

    @Test
    void inputRoundTripPreservesEdgesRawStrafeAndResynchronization() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            MovementInput input = new MovementInput(1L << 33, 1, -1, -1,
                    false, true, true, true, true, -90, 25, true);
            FullInputPayload original = new FullInputPayload(1L << 40, input, true);
            FullInputPayload.CODEC.encode(buffer, original);
            assertEquals(original, FullInputPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test void inputChangesRoundTripEveryFieldMaskAndReconstructOmittedInputs() {
        var previous = new MovementInput(10, -0.25F, -0.5F, 1,
                true, true, true, true, true, -90, -25, true);
        int booleanFields = InputChangePayload.JUMP_HELD | InputChangePayload.JUMP_PRESSED
                | InputChangePayload.CROUCH_HELD | InputChangePayload.CROUCH_PRESSED
                | InputChangePayload.SPRINT_HELD | InputChangePayload.TURN_PRESSED;
        for (int mask = 1; mask < 2048; mask++) {
            for (int buttons : new int[]{0, mask & booleanFields}) {
                var original = new InputChangePayload(1L << 40, 1L << 33, mask,
                        0.25F, 0.5F, -1, buttons, 90, 25);
                var buffer = buffer();
                try {
                    InputChangePayload.CODEC.encode(buffer, original);
                    var decoded = InputChangePayload.CODEC.decode(buffer);
                    assertEquals(original, decoded);
                    assertEquals(0, buffer.readableBytes());
                    var expected = new MovementInput(1L << 33,
                            (mask & InputChangePayload.FORWARD) != 0 ? 0.25F : previous.forward(),
                            (mask & InputChangePayload.STRAFE) != 0 ? 0.5F : previous.strafe(),
                            (mask & InputChangePayload.RAW_STRAFE) != 0 ? -1 : previous.rawStrafe(),
                            (mask & InputChangePayload.JUMP_HELD) == 0 || (buttons & InputChangePayload.JUMP_HELD) != 0,
                            (buttons & InputChangePayload.JUMP_PRESSED) != 0,
                            (mask & InputChangePayload.CROUCH_HELD) == 0 || (buttons & InputChangePayload.CROUCH_HELD) != 0,
                            (buttons & InputChangePayload.CROUCH_PRESSED) != 0,
                            (mask & InputChangePayload.SPRINT_HELD) == 0 || (buttons & InputChangePayload.SPRINT_HELD) != 0,
                            (mask & InputChangePayload.YAW) != 0 ? 90 : previous.yaw(),
                            (mask & InputChangePayload.PITCH) != 0 ? 25 : previous.pitch(),
                            (buttons & InputChangePayload.TURN_PRESSED) != 0);
                    assertEquals(expected, decoded.apply(previous));
                } finally { buffer.release(); }
            }
        }
    }

    @Test void yawOnlyChangeDoesNotSerializeOtherInputs() {
        var previous = new MovementInput(1, 1, 0, 0, true, false, true, false, true, 90, 25);
        var input = previous.withSequence(2).withYaw(91);
        var change = InputChangePayload.between(1, previous, input);
        var buffer = buffer();
        try {
            InputChangePayload.CODEC.encode(buffer, change);
            assertEquals(9, buffer.readableBytes(), "Only epoch, update/command sequences, field mask and yaw belong on the wire");
            assertEquals(input, InputChangePayload.CODEC.decode(buffer).apply(previous));
        } finally { buffer.release(); }
    }

    @Test void malformedInputChangeMasksAndValuesAreRejected() {
        for (int mask : new int[]{0, 2048, -1}) {
            var buffer = buffer();
            try {
                buffer.writeVarLong(1);
                buffer.writeVarLong(1);
                buffer.writeVarLong(1);
                buffer.writeVarInt(mask);
                assertThrows(IllegalArgumentException.class, () -> InputChangePayload.CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
        var buffer = buffer();
        try {
            buffer.writeVarLong(1);
            buffer.writeVarLong(1);
            buffer.writeVarLong(1);
            buffer.writeVarInt(InputChangePayload.FORWARD);
            buffer.writeFloat(Float.NaN);
            assertThrows(IllegalArgumentException.class, () -> InputChangePayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
        assertThrows(IllegalArgumentException.class, () -> new InputChangePayload(1, 1,
                InputChangePayload.JUMP_HELD, 0, 0, 0, InputChangePayload.SPRINT_HELD, 0, 0));
    }

    @Test
    void splitPacketsPreserveAllReplayStateAndEffectiveRules() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            MovementState state = new MovementState(new MovementVector(-12345.5, 100, 0.25),
                    new MovementVector(4, 0, 2), MovementState.Mode.SLIDE, 0.3, true, 0,
                    new LocomotionState(2.4, 1.5, 0.2, 0.1, 179, true),
                    new BlockMotionState(new MovementVector(0.25, 0.05F, 0.25)), MovementBody.LOW, 179);
            var session = new SessionPayload(ResourceLocation.parse("minecraft:the_nether"), 999, 1000,
                    true, true, new MovementTuning(6, 7, 17, 40, 2, 5, 0.4));
            SessionPayload.CODEC.encode(buffer, session);
            assertEquals(session, SessionPayload.CODEC.decode(buffer));
            SnapshotPayload original = new SnapshotPayload(999, 1L << 33, 1000, state, Pose.SWIMMING);
            SnapshotPayload.CODEC.encode(buffer, original);
            assertEquals(original, SnapshotPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void malformedWireInputCannotInjectNonFiniteMovement() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeVarLong(1);
            buffer.writeVarLong(1);
            buffer.writeBoolean(false);
            buffer.writeVarLong(1);
            buffer.writeFloat(Float.NaN);
            buffer.writeFloat(0);
            buffer.writeFloat(0);
            buffer.writeByte(0);
            buffer.writeFloat(0);
            buffer.writeFloat(0);
            assertThrows(IllegalArgumentException.class, () -> FullInputPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void serverConfigRoundTripPreservesBaselineRatherThanPlayerEffectiveTuning() {
        for (int flags = 0; flags < 2048; flags++) {
            RegistryFriendlyByteBuf buffer = buffer();
            try {
                ServerConfigPayload original = new ServerConfigPayload(new ServerMovementSettings(
                        (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
                        5, 8, 20, 0.75, 2.4, 1.5, 5,
                        new MovementFeatures((flags & 16) != 0, (flags & 32) != 0, (flags & 64) != 0,
                                (flags & 128) != 0, (flags & 256) != 0, (flags & 512) != 0, (flags & 1024) != 0)));
                ServerConfigPayload.CODEC.encode(buffer, original);
                assertEquals(original, ServerConfigPayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void landingSnapshotPreservesFallJumpAndConsumedRollTimers() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            var state = new MovementState(new MovementVector(2, 1, 4), new MovementVector(0, 0, 6), MovementState.Mode.ROLL,
                    0.25, true, 0, LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.LOW, 0, TraversalState.EMPTY, WallState.EMPTY,
                    new LandingState(8, 7, 14, LandingState.Source.WALL_JUMP, 6.5, 60, 0.15, false));
            var payload = new SnapshotPayload(3, 30, 300, state, Pose.SWIMMING);
            SnapshotPayload.CODEC.encode(buffer, payload);
            assertEquals(payload, SnapshotPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void wallSnapshotsPreserveContactAndAirborneChainHistory() {
        for (boolean contact : new boolean[]{true, false}) {
            RegistryFriendlyByteBuf buffer = buffer();
            try {
                var shape = new CollisionBox(new MovementVector(1, 0, 0), new MovementVector(2, 5, 8));
                var feet = new MovementVector(0.7, 1.2, 3);
                var velocity = new MovementVector(0, 2, 6);
                var wall = new WallState(new MovementVector(-1, 0, 0), contact ? shape : null, feet, velocity,
                        new MovementVector(1, 0, 0), new MovementVector(0, 0, 1), true, true, true, 3, 0.15);
                var state = new MovementState(feet, velocity, contact ? MovementState.Mode.WALLRUN : MovementState.Mode.AIR,
                        0.25, false, 1.5F, LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0, TraversalState.EMPTY, wall);
                var payload = new SnapshotPayload(2, 20, 200, state, Pose.STANDING);
                SnapshotPayload.CODEC.encode(buffer, payload);
                assertEquals(payload, SnapshotPayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void traversalSnapshotPreservesPathPoseWitnessesEntryJumpVelocityExpectedVelocityAndNullableLanding() {
        for (MovementBody body : new MovementBody[]{MovementBody.CROUCHING, MovementBody.LOW}) for (boolean landing : new boolean[] {true, false}) {
            RegistryFriendlyByteBuf buffer = buffer();
            try {
                var shape = new CollisionBox(new MovementVector(-1, 0, 1), new MovementVector(1, 1, 2));
                var path = new TraversalPath(VaultType.VAULT, MovementVector.ZERO, new MovementVector(0, 1.08, 0),
                        new MovementVector(0, 1.08, 2.5), new MovementVector(0, 0, 4.5), new MovementVector(0, 0, 6.8),
                         shape, landing ? new CollisionBox(new MovementVector(-5, -1, -5), new MovementVector(5, 0, 5)) : null, body);
                var velocity = new MovementVector(0, 0, 8);
                var state = new MovementState(path.position(0.3), velocity, MovementState.Mode.VAULT, 0.3, false, 0,
                        LocomotionState.EMPTY, BlockMotionState.EMPTY, body, 0,
                        new TraversalState(path, 0, velocity, new MovementVector(2, 7, 6)));
                var payload = new SnapshotPayload(1, 10, 100, state, body == MovementBody.LOW ? Pose.SWIMMING : Pose.CROUCHING);
                SnapshotPayload.CODEC.encode(buffer, payload);
                assertEquals(payload, SnapshotPayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }
}
