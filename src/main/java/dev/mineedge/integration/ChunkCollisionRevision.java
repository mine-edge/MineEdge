package dev.mineedge.integration;

/** Tick-local broad-phase invalidation witness, implemented on loaded LevelChunks. */
public interface ChunkCollisionRevision {
    long mineedge$collisionRevision();
}
