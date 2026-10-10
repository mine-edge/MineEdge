package dev.mineedge.network;

import dev.mineedge.MineEdge;
import dev.mineedge.movement.MovementInput;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Absolute values for changed fields; omitted held inputs retain their previous values. */
public record InputChangePayload(long epoch, long updateSequence, long sequence, int changed, float forward, float strafe,
                                 float rawStrafe, int buttons, float yaw, float pitch) implements InputPayload {
    public static final int FORWARD = 1, STRAFE = 2, RAW_STRAFE = 4;
    public static final int JUMP_HELD = 8, JUMP_PRESSED = 16, CROUCH_HELD = 32, CROUCH_PRESSED = 64;
    public static final int SPRINT_HELD = 128, YAW = 256, PITCH = 512, TURN_PRESSED = 1024;
    private static final int BUTTONS = JUMP_HELD | JUMP_PRESSED | CROUCH_HELD | CROUCH_PRESSED | SPRINT_HELD | TURN_PRESSED;
    private static final int ALL = FORWARD | STRAFE | RAW_STRAFE | BUTTONS | YAW | PITCH;
    public static final Type<InputChangePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MineEdge.MOD_ID, "input_change"));
    public static final StreamCodec<RegistryFriendlyByteBuf, InputChangePayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarLong(payload.epoch);
                buffer.writeVarLong(payload.updateSequence);
                buffer.writeVarLong(payload.sequence);
                buffer.writeVarInt(payload.changed);
                if (payload.has(FORWARD)) buffer.writeFloat(payload.forward);
                if (payload.has(STRAFE)) buffer.writeFloat(payload.strafe);
                if (payload.has(RAW_STRAFE)) buffer.writeFloat(payload.rawStrafe);
                // Boolean values occupy the corresponding bits of the field mask.
                if ((payload.changed & BUTTONS) != 0) buffer.writeVarInt(payload.buttons);
                if (payload.has(YAW)) buffer.writeFloat(payload.yaw);
                if (payload.has(PITCH)) buffer.writeFloat(payload.pitch);
            }, buffer -> {
                long epoch = buffer.readVarLong();
                long updateSequence = buffer.readVarLong();
                long sequence = buffer.readVarLong();
                int changed = buffer.readVarInt();
                validateMask(changed);
                return new InputChangePayload(epoch, updateSequence, sequence, changed,
                        (changed & FORWARD) != 0 ? buffer.readFloat() : 0,
                        (changed & STRAFE) != 0 ? buffer.readFloat() : 0,
                        (changed & RAW_STRAFE) != 0 ? buffer.readFloat() : 0,
                        (changed & BUTTONS) != 0 ? buffer.readVarInt() : 0,
                        (changed & YAW) != 0 ? buffer.readFloat() : 0,
                        (changed & PITCH) != 0 ? buffer.readFloat() : 0);
            });

    public InputChangePayload {
        validateMask(changed);
        if (updateSequence < 0 || sequence < 0 || (buttons & ~(changed & BUTTONS)) != 0
                || !Float.isFinite(forward) || !Float.isFinite(strafe) || !Float.isFinite(rawStrafe)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Invalid input change");
        }
        forward = (changed & FORWARD) != 0 ? forward : 0;
        strafe = (changed & STRAFE) != 0 ? strafe : 0;
        rawStrafe = (changed & RAW_STRAFE) != 0 ? rawStrafe : 0;
        yaw = (changed & YAW) != 0 ? yaw : 0;
        pitch = (changed & PITCH) != 0 ? pitch : 0;
    }

    /** Presses are one-tick events, so each press is sent even on consecutive ticks. */
    public static InputChangePayload between(long epoch, MovementInput previous, MovementInput input) {
        return between(epoch, input.sequence(), previous, input);
    }

    public InputChangePayload(long epoch, long sequence, int changed, float forward, float strafe,
                              float rawStrafe, int buttons, float yaw, float pitch) {
        this(epoch, sequence, sequence, changed, forward, strafe, rawStrafe, buttons, yaw, pitch);
    }

    public static InputChangePayload between(long epoch, long updateSequence, MovementInput previous, MovementInput input) {
        int changed = 0;
        if (Float.compare(previous.forward(), input.forward()) != 0) changed |= FORWARD;
        if (Float.compare(previous.strafe(), input.strafe()) != 0) changed |= STRAFE;
        if (Float.compare(previous.rawStrafe(), input.rawStrafe()) != 0) changed |= RAW_STRAFE;
        if (previous.jumpHeld() != input.jumpHeld()) changed |= JUMP_HELD;
        if (previous.crouchHeld() != input.crouchHeld()) changed |= CROUCH_HELD;
        if (previous.sprintHeld() != input.sprintHeld()) changed |= SPRINT_HELD;
        if (Float.compare(previous.yaw(), input.yaw()) != 0) changed |= YAW;
        if (Float.compare(previous.pitch(), input.pitch()) != 0) changed |= PITCH;
        if (input.jumpPressed()) changed |= JUMP_PRESSED;
        if (input.crouchPressed()) changed |= CROUCH_PRESSED;
        if (input.turnPressed()) changed |= TURN_PRESSED;
        if (changed == 0) return null;
        int buttons = (input.jumpHeld() ? JUMP_HELD : 0) | (input.jumpPressed() ? JUMP_PRESSED : 0)
                | (input.crouchHeld() ? CROUCH_HELD : 0) | (input.crouchPressed() ? CROUCH_PRESSED : 0)
                | (input.sprintHeld() ? SPRINT_HELD : 0) | (input.turnPressed() ? TURN_PRESSED : 0);
        return new InputChangePayload(epoch, updateSequence, input.sequence(), changed, input.forward(), input.strafe(),
                input.rawStrafe(), buttons & changed, input.yaw(), input.pitch());
    }

    public MovementInput apply(MovementInput previous) {
        return new MovementInput(sequence, has(FORWARD) ? forward : previous.forward(),
                has(STRAFE) ? strafe : previous.strafe(), has(RAW_STRAFE) ? rawStrafe : previous.rawStrafe(),
                has(JUMP_HELD) ? down(JUMP_HELD) : previous.jumpHeld(), down(JUMP_PRESSED),
                has(CROUCH_HELD) ? down(CROUCH_HELD) : previous.crouchHeld(), down(CROUCH_PRESSED),
                has(SPRINT_HELD) ? down(SPRINT_HELD) : previous.sprintHeld(),
                has(YAW) ? yaw : previous.yaw(), has(PITCH) ? pitch : previous.pitch(), down(TURN_PRESSED));
    }

    private boolean has(int field) { return (changed & field) != 0; }
    private boolean down(int field) { return (buttons & field) != 0; }

    private static void validateMask(int changed) {
        if (changed == 0 || (changed & ~ALL) != 0) throw new IllegalArgumentException("Invalid input change mask");
    }

    @Override
    public Type<InputChangePayload> type() { return TYPE; }
}
