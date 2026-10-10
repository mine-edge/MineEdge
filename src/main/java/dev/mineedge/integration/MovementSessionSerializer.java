package dev.mineedge.integration;

import dev.mineedge.movement.MovementStateCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/** Versioned player-save state, independent of network epochs, input queues and simulation clocks. */
public final class MovementSessionSerializer implements IAttachmentSerializer<CompoundTag, MovementSession> {
    @Override
    public MovementSession read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
        if (tag.getInt("version") != MovementStateCodec.SAVE_VERSION) {
            throw new IllegalArgumentException("Unsupported MineEdge movement save version: " + tag.getInt("version"));
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        if (dimension == null) throw new IllegalArgumentException("Invalid saved movement dimension");
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(tag.getByteArray("state")));
        try {
            var session = new MovementSession();
            session.state = MovementStateCodec.CODEC.decode(buffer);
            if (buffer.isReadable()) throw new IllegalArgumentException("Unexpected trailing movement save data");
            session.dimension = dimension;
            session.resumePending = true;
            return session;
        } finally {
            buffer.release();
        }
    }

    @Override
    public CompoundTag write(MovementSession session, HolderLookup.Provider provider) {
        if ((!session.active && !session.resumePending) || session.state == null || session.dimension == null) return null;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MovementStateCodec.CODEC.encode(buffer, session.state);
            byte[] state = new byte[buffer.readableBytes()];
            buffer.readBytes(state);
            var tag = new CompoundTag();
            tag.putInt("version", MovementStateCodec.SAVE_VERSION);
            tag.putString("dimension", session.dimension.toString());
            tag.putByteArray("state", state);
            return tag;
        } finally {
            buffer.release();
        }
    }
}
