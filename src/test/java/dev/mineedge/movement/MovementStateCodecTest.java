package dev.mineedge.movement;

import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.wall.WallState;
import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementStateCodecTest {
    private static final String ORDINARY_VERSION_TWO = "07402400000000000040500000000000004024000000000000"
            + "04401000000000000000023ff000000000000001000000000000000000000101"
            + "024050000000000000000000000101000101";

    @Test void versionTwoOrdinaryStateRemainsByteCompatible() {
        var state = new MovementState(new MovementVector(10, 64, 10), new MovementVector(0, 0, 4),
                MovementState.Mode.GROUND, 1, true, 0);
        assertVersionTwoFixture(state, ORDINARY_VERSION_TWO);
    }

    @Test void versionTwoModesAfterRemovedBackLandingKeepTheirIds() {
        byte[] fixture = HexFormat.of().parseHex(ORDINARY_VERSION_TWO);
        fixture[34] = 14; // Version-2 CLIMB_ATTACH ordinal, following the retired slot 13.
        var state = new MovementState(new MovementVector(10, 64, 10), new MovementVector(0, 0, 4),
                MovementState.Mode.CLIMB_ATTACH, 1, true, 0);
        assertVersionTwoFixture(state, HexFormat.of().formatHex(fixture));
    }

    @Test void versionTwoBackLandingLoadsAsGroundWithoutRecoveryState() {
        var saved = new FriendlyByteBuf(Unpooled.buffer());
        try {
            saved.writeBytes(HexFormat.of().parseHex(ORDINARY_VERSION_TWO));
            saved.setByte(34, 13); // Legacy back-landing mode.
            saved.setByte(68, 4); // Legacy back-turn source.
            saved.writerIndex(73); // Replace the empty advanced-state envelope.
            saved.writeBytes(HexFormat.of().parseHex("00000100000000023fd999999999999a0101"));
            var expected = new MovementState(new MovementVector(10, 64, 10), new MovementVector(0, 0, 4),
                    MovementState.Mode.GROUND, 1, true, 0);
            assertEquals(expected, MovementStateCodec.CODEC.decode(saved));
            assertEquals(0, saved.readableBytes());
        } finally {
            saved.release();
        }
    }

    @Test void versionTwoSpringWithoutAPathRetainsItsEmptyTraversalEnvelope() {
        var position = new MovementVector(2, 3, 4);
        var state = new MovementState(position, new MovementVector(0, 0, 6), MovementState.Mode.SPRINGBOARD,
                0, false, 0, LocomotionState.EMPTY, BlockMotionState.EMPTY, MovementBody.STANDING, 0,
                TraversalState.EMPTY, WallState.EMPTY, LandingState.capture(position, false, 0),
                new AdvancedState(false, false, null, null,
                        new AdvancedState.Springboard(null, 6.25, 0.12)));
        assertVersionTwoFixture(state, "07400000000000000040080000000000004010000000000000"
                + "0440180000000000000c0000000000000000000000000101024008000000000000"
                + "000000000101010000000000000101024019000000000000023fbeb851eb851eb8"
                + "02bff00000000000000001");
    }

    @Test void reusingEmptyBlockMotionDoesNotDiscardSignedZeroComponents() {
        for (int mask = 0; mask < 8; mask++) {
            var multiplier = new MovementVector((mask & 1) != 0 ? -0.0 : 0.0,
                    (mask & 2) != 0 ? -0.0 : 0.0, (mask & 4) != 0 ? -0.0 : 0.0);
            var state = new MovementState(MovementVector.ZERO, MovementVector.ZERO, MovementState.Mode.GROUND,
                    0, true, 0, LocomotionState.EMPTY, new BlockMotionState(multiplier));
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                MovementStateCodec.CODEC.encode(buffer, state);
                var decoded = MovementStateCodec.CODEC.decode(buffer);
                assertEquals(state, decoded);
                assertEquals(0, buffer.readableBytes());
                if (mask == 0) assertSame(BlockMotionState.EMPTY, decoded.blocks());
            } finally {
                buffer.release();
            }
        }
    }

    private static void assertVersionTwoFixture(MovementState state, String hex) {
        assertEquals(2, MovementStateCodec.SAVE_VERSION);
        byte[] fixture = HexFormat.of().parseHex(hex);
        var saved = new FriendlyByteBuf(Unpooled.wrappedBuffer(fixture));
        var encoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertEquals(state, MovementStateCodec.CODEC.decode(saved));
            assertEquals(0, saved.readableBytes());
            MovementStateCodec.CODEC.encode(encoded, state);
            byte[] actual = new byte[encoded.readableBytes()];
            encoded.readBytes(actual);
            assertArrayEquals(fixture, actual);
        } finally {
            saved.release();
            encoded.release();
        }
    }
}
