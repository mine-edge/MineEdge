package dev.mineedge.network;

import dev.mineedge.movement.MovementInput;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputMailboxTest {
    @Test void boundsNetworkReceiptAndPreservesPacketOrderAcrossTickHandoff() {
        var mailbox = new InputMailbox();
        CompletableFuture.runAsync(() -> {
            for (int update = 1; update <= InputMailbox.CAPACITY; update++) {
                assertTrue(mailbox.offer(new FullInputPayload(1, update,
                        MovementInput.idle((update + 1) / 2, update, 0), false)));
            }
        }).join();
        assertFalse(mailbox.offer(new FullInputPayload(1, MovementInput.idle(100, 0, 0))));
        var batch = mailbox.drain();
        assertEquals(InputMailbox.CAPACITY, batch.size());
        for (int index = 0; index < batch.size(); index++) assertEquals(index + 1, batch.get(index).updateSequence());
        assertTrue(mailbox.drain().isEmpty());
        assertTrue(mailbox.offer(new FullInputPayload(1, MovementInput.idle(100, 0, 0))));
    }
}
