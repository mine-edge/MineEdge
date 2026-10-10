package dev.mineedge.network;

import dev.mineedge.MineEdge;
import dev.mineedge.movement.MovementInput;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Complete input baseline, also used for explicit session resynchronization. */
public record FullInputPayload(long epoch, long updateSequence, MovementInput input, boolean resetRequested) implements InputPayload {
    public static final Type<FullInputPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MineEdge.MOD_ID, "input_full"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FullInputPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarLong(payload.epoch);
                buffer.writeVarLong(payload.updateSequence);
                buffer.writeBoolean(payload.resetRequested);
                MovementInput input = payload.input;
                buffer.writeVarLong(input.sequence());
                buffer.writeFloat(input.forward());
                buffer.writeFloat(input.strafe());
                buffer.writeFloat(input.rawStrafe());
                int buttons = (input.jumpHeld() ? 1 : 0) | (input.jumpPressed() ? 2 : 0)
                        | (input.crouchHeld() ? 4 : 0) | (input.crouchPressed() ? 8 : 0) | (input.sprintHeld() ? 16 : 0) | (input.turnPressed() ? 32 : 0);
                buffer.writeByte(buttons);
                buffer.writeFloat(input.yaw());
                buffer.writeFloat(input.pitch());
            }, buffer -> {
                long epoch = buffer.readVarLong();
                long updateSequence = buffer.readVarLong();
                boolean resetRequested = buffer.readBoolean();
                long sequence = buffer.readVarLong();
                float forward = buffer.readFloat();
                float strafe = buffer.readFloat();
                float rawStrafe = buffer.readFloat();
                int buttons = buffer.readUnsignedByte();
                return new FullInputPayload(epoch, updateSequence, new MovementInput(sequence, forward, strafe, rawStrafe,
                        (buttons & 1) != 0, (buttons & 2) != 0, (buttons & 4) != 0, (buttons & 8) != 0,
                        (buttons & 16) != 0, buffer.readFloat(), buffer.readFloat(), (buttons & 32) != 0), resetRequested);
            });

    public FullInputPayload(long epoch, MovementInput input) {
        this(epoch, input.sequence(), input, false);
    }

    public FullInputPayload(long epoch, MovementInput input, boolean resetRequested) {
        this(epoch, input.sequence(), input, resetRequested);
    }

    public FullInputPayload {
        if (updateSequence < 0) throw new IllegalArgumentException("Invalid input update sequence");
    }

    @Override
    public Type<FullInputPayload> type() {
        return TYPE;
    }
}
