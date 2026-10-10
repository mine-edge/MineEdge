package dev.mineedge.movement;

import dev.mineedge.movement.collision.CollisionBox;
import dev.mineedge.movement.landing.LandingState;
import dev.mineedge.movement.surface.ClimbRoute;
import dev.mineedge.movement.surface.SurfacePath;
import dev.mineedge.movement.surface.SurfaceState;
import dev.mineedge.movement.traversal.TraversalPath;
import dev.mineedge.movement.traversal.TraversalState;
import dev.mineedge.movement.traversal.VaultType;
import dev.mineedge.movement.wall.WallState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Lossless state format shared by snapshots and player saves. Bump SAVE_VERSION when this format changes. */
public final class MovementStateCodec {
    public static final int SAVE_VERSION = 2;
    public static final StreamCodec<FriendlyByteBuf, MovementState> CODEC = StreamCodec.of(
            MovementStateCodec::writeState, MovementStateCodec::readState);

    // FriendlyByteBuf.readEnum clones the constants array on every call.
    private static final MovementState.Mode[] MODES = MovementState.Mode.values();
    private static final MovementBody[] BODIES = MovementBody.values();
    private static final LandingState.Source[] LANDING_SOURCES = LandingState.Source.values();
    private static final VaultType[] VAULT_TYPES = VaultType.values();
    private static final AdvancedState.LedgeKind[] LEDGE_KINDS = AdvancedState.LedgeKind.values();
    private static final SurfacePath.Kind[] SURFACE_KINDS = SurfacePath.Kind.values();

    private static void writeState(FriendlyByteBuf buffer, MovementState state) {
        writeVector(buffer, state.position());
        writeVector(buffer, state.velocity());
        buffer.writeEnum(state.mode());
        writeNumber(buffer, state.modeTime());
        buffer.writeBoolean(state.grounded());
        writeFloat(buffer, state.fallDistance());
        writeLocomotion(buffer, state.locomotion());
        writeVector(buffer, state.blocks().stuckMultiplier());
        buffer.writeEnum(state.body());
        writeFloat(buffer, state.slideYaw());
        writeTraversal(buffer, state.traversal());
        writeWall(buffer, state.wall(), state.position(), state.velocity());
        writeLanding(buffer, state.landing());
        writeAdvanced(buffer, state.advanced());
        writeSurfaces(buffer, state.surfaces());
    }

    private static MovementState readState(FriendlyByteBuf buffer) {
        MovementVector position = readVector(buffer);
        MovementVector velocity = readVector(buffer);
        MovementState.Mode mode = MODES[buffer.readVarInt()];
        double modeTime = readNumber(buffer);
        boolean grounded = buffer.readBoolean();
        float fallDistance = readFloat(buffer);
        LocomotionState locomotion = readLocomotion(buffer);
        MovementVector stuckMultiplier = readVector(buffer);
        var blocks = stuckMultiplier == MovementVector.ZERO ? BlockMotionState.EMPTY : new BlockMotionState(stuckMultiplier);
        MovementBody body = BODIES[buffer.readVarInt()];
        float slideYaw = readFloat(buffer);
        TraversalState traversal = readTraversal(buffer);
        WallState wall = readWall(buffer, position, velocity);
        LandingState landing = readLanding(buffer);
        AdvancedState advanced = readAdvanced(buffer);
        SurfaceState surfaces = readSurfaces(buffer);
        return new MovementState(position, velocity, mode, modeTime, grounded, fallDistance, locomotion,
                blocks, body, slideYaw, traversal, wall, landing, advanced, surfaces);
    }

    private static void writeLocomotion(FriendlyByteBuf buffer, LocomotionState state) {
        writeNumber(buffer, state.energy());
        writeNumber(buffer, state.accelerationTime());
        writeNumber(buffer, state.stoppingTime());
        writeNumber(buffer, state.jumpBuffer());
        writeFloat(buffer, state.previousYaw());
        buffer.writeBoolean(state.yawInitialized());
    }

    private static LocomotionState readLocomotion(FriendlyByteBuf buffer) {
        return new LocomotionState(readNumber(buffer), readNumber(buffer), readNumber(buffer),
                readNumber(buffer), readFloat(buffer), buffer.readBoolean());
    }

    private static void writeVector(FriendlyByteBuf buffer, MovementVector vector) {
        int mask = (Double.doubleToRawLongBits(vector.x()) != 0 ? 1 : 0)
                | (Double.doubleToRawLongBits(vector.y()) != 0 ? 2 : 0) | (Double.doubleToRawLongBits(vector.z()) != 0 ? 4 : 0);
        buffer.writeByte(mask);
        if ((mask & 1) != 0) buffer.writeDouble(vector.x());
        if ((mask & 2) != 0) buffer.writeDouble(vector.y());
        if ((mask & 4) != 0) buffer.writeDouble(vector.z());
    }

    private static void writeWall(FriendlyByteBuf buffer, WallState state, MovementVector position, MovementVector velocity) {
        boolean empty = state.equals(WallState.EMPTY);
        buffer.writeBoolean(empty);
        if (empty) return;
        int flags = (state.support() != null ? 1 : 0) | (state.anchor().equals(position) ? 2 : 0)
                | (state.expectedVelocity().equals(velocity) ? 4 : 0) | (state.previousNormal().equals(state.normal()) ? 8 : 0)
                | (state.jumped() ? 16 : 0) | (state.climbed() ? 32 : 0) | (state.wallJump() ? 64 : 0);
        buffer.writeByte(flags);
        writeVector(buffer, state.normal());
        if (state.support() != null) writeBox(buffer, state.support());
        if ((flags & 2) == 0) writeVector(buffer, state.anchor());
        if ((flags & 4) == 0) writeVector(buffer, state.expectedVelocity());
        if ((flags & 8) == 0) writeVector(buffer, state.previousNormal());
        writeVector(buffer, state.jumpOrigin());
        buffer.writeVarInt(state.chain());
        writeNumber(buffer, state.cooldown());
    }

    private static void writeLanding(FriendlyByteBuf buffer, LandingState state) {
        writeNumber(buffer, state.apex());
        writeNumber(buffer, state.distance());
        writeNumber(buffer, state.impact());
        buffer.writeEnum(state.source());
        writeNumber(buffer, state.takeoffSpeed());
        writeNumber(buffer, state.triggerAge());
        writeNumber(buffer, state.retriggerAge());
        buffer.writeBoolean(state.airborne());
    }

    private static void writeSurfaces(FriendlyByteBuf buffer, SurfaceState state) {
        boolean empty = state.equals(SurfaceState.EMPTY);
        buffer.writeBoolean(empty);
        if (empty) return;
        buffer.writeBoolean(state.climb() != null);
        if (state.climb() != null) {
            var climb = state.climb();
            var route = climb.route();
            writeVector(buffer, route.base());
            writeNumber(buffer, route.top());
            writeVector(buffer, route.normal());
            buffer.writeBoolean(route.pipe());
            writeVector(buffer, route.source());
            writeVector(buffer, climb.anchor());
            writeNumber(buffer, climb.hold());
            writeNumber(buffer, climb.from());
            writeNumber(buffer, climb.to());
            writeNumber(buffer, climb.elapsed());
            writeNumber(buffer, climb.duration());
            buffer.writeBoolean(climb.fast());
        }
        buffer.writeBoolean(state.path() != null);
        if (state.path() != null) {
            var path = state.path();
            buffer.writeEnum(path.kind());
            writeVector(buffer, path.from());
            writeVector(buffer, path.lift());
            writeVector(buffer, path.over());
            writeVector(buffer, path.end());
            writeVector(buffer, path.exitVelocity());
            buffer.writeBoolean(path.support() != null);
            if (path.support() != null) writeBox(buffer, path.support());
            writeVector(buffer, path.source());
            writeNumber(buffer, path.duration());
            writeNumber(buffer, path.elapsed());
            buffer.writeEnum(path.body());
        }
        writeNumber(buffer, state.climbCooldown());
        writeNumber(buffer, state.stepCooldown());
        writeNumber(buffer, state.slopeCooldown());
        writeNumber(buffer, state.slopeAir());
        writeNumber(buffer, state.stalled());
        writeVector(buffer, state.downhill());
        writeFloat(buffer, state.slopeYaw());
        writeVector(buffer, state.expectedVelocity());
    }

    private static SurfaceState readSurfaces(FriendlyByteBuf buffer) {
        if (buffer.readBoolean()) return SurfaceState.EMPTY;
        SurfaceState.Climb climb = null;
        if (buffer.readBoolean()) {
            var route = new ClimbRoute(readVector(buffer), readNumber(buffer), readVector(buffer), buffer.readBoolean(), readVector(buffer));
            climb = new SurfaceState.Climb(route, readVector(buffer), readNumber(buffer), readNumber(buffer), readNumber(buffer),
                    readNumber(buffer), readNumber(buffer), buffer.readBoolean());
        }
        var path = buffer.readBoolean() ? new SurfacePath(SURFACE_KINDS[buffer.readVarInt()], readVector(buffer), readVector(buffer),
                readVector(buffer), readVector(buffer), readVector(buffer), buffer.readBoolean() ? readBox(buffer) : null,
                readVector(buffer), readNumber(buffer), readNumber(buffer), BODIES[buffer.readVarInt()]) : null;
        return new SurfaceState(climb, path, readNumber(buffer), readNumber(buffer), readNumber(buffer), readNumber(buffer), readNumber(buffer),
                readVector(buffer), readFloat(buffer), readVector(buffer));
    }

    private static void writeAdvanced(FriendlyByteBuf buffer, AdvancedState state) {
        boolean empty = state.equals(AdvancedState.EMPTY);
        buffer.writeBoolean(empty);
        if (empty) return;
        buffer.writeBoolean(state.coiled());
        buffer.writeBoolean(state.turnedInAir());
        buffer.writeBoolean(state.wallTurned());
        buffer.writeBoolean(state.turn() != null);
        if (state.turn() != null) {
            writeFloat(buffer, state.turn().from());
            writeFloat(buffer, state.turn().delta());
            writeNumber(buffer, state.turn().elapsed());
            writeNumber(buffer, state.turn().duration());
        }
        buffer.writeBoolean(state.ledge() != null);
        if (state.ledge() != null) {
            var move = state.ledge();
            buffer.writeEnum(move.kind());
            writeVector(buffer, move.from());
            writeVector(buffer, move.via());
            writeVector(buffer, move.to());
            writeVector(buffer, move.normal());
            writeBox(buffer, move.support());
            writeBox(buffer, move.originSupport());
            writeNumber(buffer, move.elapsed());
            writeNumber(buffer, move.duration());
            writeFloat(buffer, move.yawFrom());
            writeFloat(buffer, move.yawDelta());
        }
        buffer.writeBoolean(state.spring() != null);
        if (state.spring() != null) {
            var path = state.spring().path();
            // Keep the version-2 traversal envelope without allocating a temporary state.
            buffer.writeBoolean(path == null);
            if (path != null) writeTraversalBody(buffer, path, 0, MovementVector.ZERO, MovementVector.ZERO);
            writeNumber(buffer, state.spring().speed());
            writeNumber(buffer, state.spring().elapsed());
        }
        writeNumber(buffer, state.getUpTime());
        buffer.writeBoolean(state.backRoll());
    }

    private static AdvancedState readAdvanced(FriendlyByteBuf buffer) {
        if (buffer.readBoolean()) return AdvancedState.EMPTY;
        boolean coiled = buffer.readBoolean();
        boolean turnedInAir = buffer.readBoolean();
        boolean wallTurned = buffer.readBoolean();
        var turn = buffer.readBoolean() ? new AdvancedState.Turn(readFloat(buffer), readFloat(buffer), readNumber(buffer), readNumber(buffer)) : null;
        var ledge = buffer.readBoolean() ? new AdvancedState.LedgeMove(LEDGE_KINDS[buffer.readVarInt()], readVector(buffer),
                readVector(buffer), readVector(buffer), readVector(buffer), readBox(buffer), readBox(buffer), readNumber(buffer), readNumber(buffer), readFloat(buffer), readFloat(buffer)) : null;
        var spring = buffer.readBoolean() ? new AdvancedState.Springboard(readTraversal(buffer).path(), readNumber(buffer), readNumber(buffer)) : null;
        return new AdvancedState(coiled, turnedInAir, wallTurned, turn, ledge, spring, readNumber(buffer), buffer.readBoolean());
    }

    private static LandingState readLanding(FriendlyByteBuf buffer) {
        return new LandingState(readNumber(buffer), readNumber(buffer), readNumber(buffer),
                LANDING_SOURCES[buffer.readVarInt()], readNumber(buffer), readNumber(buffer), readNumber(buffer), buffer.readBoolean());
    }

    private static WallState readWall(FriendlyByteBuf buffer, MovementVector position, MovementVector velocity) {
        if (buffer.readBoolean()) return WallState.EMPTY;
        int flags = buffer.readUnsignedByte();
        MovementVector normal = readVector(buffer);
        CollisionBox support = (flags & 1) != 0 ? readBox(buffer) : null;
        MovementVector anchor = (flags & 2) != 0 ? position : readVector(buffer);
        MovementVector expectedVelocity = (flags & 4) != 0 ? velocity : readVector(buffer);
        MovementVector previousNormal = (flags & 8) != 0 ? normal : readVector(buffer);
        return new WallState(normal, support, anchor, expectedVelocity, previousNormal, readVector(buffer),
                (flags & 16) != 0, (flags & 32) != 0, (flags & 64) != 0, buffer.readVarInt(), readNumber(buffer));
    }

    private static MovementVector readVector(FriendlyByteBuf buffer) {
        int mask = buffer.readUnsignedByte();
        if ((mask & ~7) != 0) throw new IllegalArgumentException("Invalid vector mask");
        return mask == 0 ? MovementVector.ZERO : new MovementVector((mask & 1) != 0 ? buffer.readDouble() : 0,
                (mask & 2) != 0 ? buffer.readDouble() : 0, (mask & 4) != 0 ? buffer.readDouble() : 0);
    }

    private static void writeTraversal(FriendlyByteBuf buffer, TraversalState state) {
        boolean empty = state.equals(TraversalState.EMPTY);
        buffer.writeBoolean(empty);
        if (empty) return;
        writeTraversalBody(buffer, state.path(), state.cooldown(), state.expectedVelocity(), state.entryVelocity());
    }

    private static void writeTraversalBody(FriendlyByteBuf buffer, TraversalPath path, double cooldown,
                                           MovementVector expectedVelocity, MovementVector entryVelocity) {
        writeNumber(buffer, cooldown);
        writeVector(buffer, expectedVelocity);
        buffer.writeBoolean(path != null);
        if (path != null) {
            writeVector(buffer, entryVelocity);
            buffer.writeEnum(path.kind());
            writeVector(buffer, path.from());
            writeVector(buffer, path.hand());
            writeVector(buffer, path.over());
            writeVector(buffer, path.end());
            writeVector(buffer, path.exitVelocity());
            writeBox(buffer, path.support());
            buffer.writeBoolean(path.landing() != null);
            if (path.landing() != null) {
                writeBox(buffer, path.landing());
            }
            buffer.writeEnum(path.body());
        }
    }

    private static TraversalState readTraversal(FriendlyByteBuf buffer) {
        if (buffer.readBoolean()) return TraversalState.EMPTY;
        double cooldown = readNumber(buffer);
        MovementVector expectedVelocity = readVector(buffer);
        boolean active = buffer.readBoolean();
        MovementVector entryVelocity = active ? readVector(buffer) : MovementVector.ZERO;
        TraversalPath path = active ? new TraversalPath(VAULT_TYPES[buffer.readVarInt()], readVector(buffer),
                readVector(buffer), readVector(buffer), readVector(buffer), readVector(buffer), readBox(buffer),
                buffer.readBoolean() ? readBox(buffer) : null, BODIES[buffer.readVarInt()]) : null;
        return new TraversalState(path, cooldown, expectedVelocity, entryVelocity);
    }

    private static void writeBox(FriendlyByteBuf buffer, CollisionBox box) {
        writeVector(buffer, box.min());
        writeVector(buffer, box.max());
    }

    private static CollisionBox readBox(FriendlyByteBuf buffer) {
        return new CollisionBox(readVector(buffer), readVector(buffer));
    }

    /** Lossless tags for common positive zero/expired timers. Non-default values retain all 64 bits. */
    private static void writeNumber(FriendlyByteBuf buffer, double value) {
        if (Double.doubleToRawLongBits(value) == 0) {
            buffer.writeByte(0);
        } else if (value == 60) {
            buffer.writeByte(1);
        } else {
            buffer.writeByte(2);
            buffer.writeDouble(value);
        }
    }

    private static double readNumber(FriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> 0;
            case 1 -> 60;
            case 2 -> buffer.readDouble();
            default -> throw new IllegalArgumentException("Invalid number tag");
        };
    }

    private static void writeFloat(FriendlyByteBuf buffer, float value) {
        boolean present = Float.floatToRawIntBits(value) != 0;
        buffer.writeBoolean(present);
        if (present) buffer.writeFloat(value);
    }

    private static float readFloat(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readFloat() : 0;
    }

    private MovementStateCodec() {}
}
