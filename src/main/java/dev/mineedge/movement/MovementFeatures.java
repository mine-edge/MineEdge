package dev.mineedge.movement;

import dev.mineedge.movement.traversal.VaultType;

/** Server-owned feature switches, shared by authoritative physics and client prediction. */
public record MovementFeatures(boolean sliding, boolean wallRunning, boolean vaulting, boolean mantling, boolean pipeClimbing,
                               boolean doorPush, boolean hardLanding) {
    public static final MovementFeatures DEFAULT = new MovementFeatures(true, true, true, true, true, true, true);

    public MovementFeatures(boolean sliding, boolean wallRunning, boolean vaulting, boolean mantling, boolean pipeClimbing,
                            boolean doorPush) {
        this(sliding, wallRunning, vaulting, mantling, pipeClimbing, doorPush, true);
    }

    public MovementFeatures(boolean sliding, boolean wallRunning, boolean vaulting, boolean mantling, boolean pipeClimbing) {
        this(sliding, wallRunning, vaulting, mantling, pipeClimbing, true);
    }

    public boolean allowsTraversal(VaultType kind) {
        return kind == VaultType.SPRINGBOARD ? vaulting : kind.onto() ? mantling : vaulting;
    }
}
