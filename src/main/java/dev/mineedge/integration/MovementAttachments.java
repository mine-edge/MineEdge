package dev.mineedge.integration;

import dev.mineedge.MineEdge;
import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class MovementAttachments {
    public static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MineEdge.MOD_ID);
    public static final Supplier<AttachmentType<MovementSession>> SESSION = TYPES.register("movement_session",
            () -> AttachmentType.builder(MovementSession::new).serialize(new MovementSessionSerializer()).build());

    private MovementAttachments() {}
}
