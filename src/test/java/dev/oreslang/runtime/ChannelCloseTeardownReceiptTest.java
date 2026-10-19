package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Channel close does not automatically certify that already-committed select
 * callbacks have been delivered. A selector-free successful close is the only
 * narrow receipt admitted by actor physical-teardown retry.
 */
@Timeout(15)
final class ChannelCloseTeardownReceiptTest {
    @Test
    void selectorFreeSuccessfulCloseProducesOneStableWitness() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        assertFalse(channel.closeSettledWithoutSelectorsForTeardown());
        channel.close();
        assertTrue(channel.isClosed());
        assertTrue(channel.closeSettledWithoutSelectorsForTeardown());
        channel.close();
        assertTrue(channel.closeSettledWithoutSelectorsForTeardown(),
                "a second close cannot revoke an already completed receipt");
    }

    @Test
    void selectorPresentAtCloseCannotForgeReceiptAfterItUnregisters()
            throws Exception {
        var channel = new ChannelRuntime.Channel<Integer>(0);
        var waiting = channel.readAsync();
        assertEquals(1, channel.pendingRegistrations());
        assertFalse(waiting.isDone());
        channel.close();
        assertTrue(channel.isClosed());
        assertThrows(ExecutionException.class,
                () -> waiting.get(5, TimeUnit.SECONDS));
        assertEquals(0, channel.pendingRegistrations());
        assertFalse(channel.closeSettledWithoutSelectorsForTeardown(),
                "postclose absence of registrations is not preclose proof");
        channel.close();
        assertFalse(channel.closeSettledWithoutSelectorsForTeardown(),
                "a second no-op close must not forge missing callback authority");
    }

    @Test
    void bufferedValueWithoutSelectorStillPermitsConservativeCloseWitness() {
        var channel = new ChannelRuntime.Channel<Integer>(2);
        assertTrue(channel.tryWrite(9));
        assertEquals(1, channel.size());
        channel.close();
        assertTrue(channel.closeSettledWithoutSelectorsForTeardown(),
                "committed buffered values are owned by the later actor drain, not select waiters");
        assertEquals(1, channel.size(),
                "close must preserve already committed buffered data");
    }
}
