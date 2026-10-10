package dev.mineedge.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Transport ordering is independent of the movement simulation sequence. */
public interface InputPayload extends CustomPacketPayload {
    long epoch();
    long updateSequence();
}
