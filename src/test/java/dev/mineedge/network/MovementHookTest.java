package dev.mineedge.network;

import dev.mineedge.integration.mixin.LivingEntityAccessor;
import dev.mineedge.integration.mixin.EntityMotionAccessor;
import dev.mineedge.integration.mixin.ServerConnectionAccessor;
import dev.mineedge.integration.ChunkCollisionRevision;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs in ModDevGradle's transformed NeoForge test JVM, not an unpatched classpath. */
class MovementHookTest {
    @Test
    void pinnedServerMovementAndTeleportMixinsApply() {
        // Loading these targets also validates the required travel, step, jump,
        // position-packet and teleport injections; an invalid descriptor/ordinal fails loading.
        assertTrue(LivingEntityAccessor.class.isAssignableFrom(ServerPlayer.class));
        assertTrue(EntityMotionAccessor.class.isAssignableFrom(ServerPlayer.class));
        assertTrue(ServerConnectionAccessor.class.isAssignableFrom(ServerGamePacketListenerImpl.class));
        assertTrue(ChunkCollisionRevision.class.isAssignableFrom(LevelChunk.class));
    }
}
