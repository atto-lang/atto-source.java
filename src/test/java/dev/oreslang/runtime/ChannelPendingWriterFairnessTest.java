package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * Regression contract for the pending-writer behavior consumed by the
 * userland BoundedQueue<T> ticket layer.
 *
 * <p>The queue wrapper does not maintain a second waiter list. It therefore
 * depends on ChannelRuntime to preserve registration order, detach cancelled
 * writers synchronously enough that they cannot later deliver, and fail all
 * uncommitted writes when a channel is closed.</p>
 */
final class ChannelPendingWriterFairnessTest {

    @Test
    void pendingBufferedWritersAdmitInRegistrationOrder() {
        ChannelRuntime.Channel<Integer> channel =
                new ChannelRuntime.Channel<>(1);

        assertTrue(channel.tryWrite(0));

        OresFuture<Void> first = channel.writeAsync(1);
        OresFuture<Void> second = channel.writeAsync(2);

        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertEquals(2, channel.pendingRegistrations());

        assertEquals(0, channel.readAsync().join());
        first.join();

        assertTrue(first.isDone());
        assertFalse(second.isDone());
        assertEquals(1, channel.pendingRegistrations());
        assertEquals(1, channel.readAsync().join());

        second.join();
        assertEquals(2, channel.readAsync().join());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void cancellingOlderWriterLetsLaterWriterAdvanceWithoutGhostDelivery() {
        ChannelRuntime.Channel<Integer> channel =
                new ChannelRuntime.Channel<>(1);

        assertTrue(channel.tryWrite(0));

        OresFuture<Void> older = channel.writeAsync(1);
        OresFuture<Void> later = channel.writeAsync(2);

        assertEquals(2, channel.pendingRegistrations());
        assertTrue(older.cancel(false));

        assertEquals(
                1,
                channel.pendingRegistrations(),
                "cancelled writer must release its retained channel registration");

        assertEquals(0, channel.readAsync().join());
        later.join();

        assertEquals(2, channel.readAsync().join());
        assertTrue(channel.tryRead().isEmpty());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void cancellationAfterAdmissionCannotRevokeCommittedValue() {
        ChannelRuntime.Channel<Integer> channel =
                new ChannelRuntime.Channel<>(1);

        OresFuture<Void> admitted = channel.writeAsync(7);
        admitted.join();

        assertFalse(
                admitted.cancel(false),
                "terminal successful admission cannot be retroactively cancelled");
        assertEquals(7, channel.readAsync().join());
    }

    @Test
    void closeFailsEveryPendingWriterAndPreservesOnlyCommittedData() {
        ChannelRuntime.Channel<Integer> channel =
                new ChannelRuntime.Channel<>(1);

        assertTrue(channel.tryWrite(0));

        OresFuture<Void> first = channel.writeAsync(1);
        OresFuture<Void> second = channel.writeAsync(2);
        assertEquals(2, channel.pendingRegistrations());

        channel.close();

        CompletionException firstFailure =
                assertThrows(CompletionException.class, first::join);
        CompletionException secondFailure =
                assertThrows(CompletionException.class, second::join);

        assertInstanceOf(
                ChannelRuntime.ChannelClosedException.class,
                firstFailure.getCause());
        assertInstanceOf(
                ChannelRuntime.ChannelClosedException.class,
                secondFailure.getCause());

        assertEquals(0, channel.pendingRegistrations());
        assertEquals(0, channel.readAsync().join());

        CompletionException drained =
                assertThrows(CompletionException.class, channel.readAsync()::join);
        assertInstanceOf(
                ChannelRuntime.ChannelClosedException.class,
                drained.getCause());
    }
}
