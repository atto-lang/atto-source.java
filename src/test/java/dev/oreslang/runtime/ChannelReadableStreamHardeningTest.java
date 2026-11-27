package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

final class ChannelReadableStreamHardeningTest {
    @Test void readerLeaseIsExclusiveAndReleasable() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(2);
        ChannelReadableStream<String> stream = new ChannelReadableStream<>(channel);
        ChannelReadableStream.Reader<String> reader = stream.getReader();
        assertThrows(IllegalStateException.class, stream::getReader);
        assertTrue(channel.tryWrite("a"));
        assertEquals("a", reader.readAsync().join());
        reader.close();
        assertThrows(CompletionException.class, () -> reader.readAsync().join());
        try (ChannelReadableStream.Reader<String> next = stream.getReader()) {
            assertTrue(channel.tryWrite("b"));
            assertEquals("b", next.readAsync().join());
        }
        // Stream view release must not own or close the underlying channel.
        assertFalse(channel.isClosed());
    }

    @Test void releasingPendingReaderCancelsItsRegistrationWithoutEatingData() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        ChannelReadableStream<String> stream = new ChannelReadableStream<>(channel);
        ChannelReadableStream.Reader<String> abandoned = stream.getReader();
        OresFuture<String> pending = abandoned.readAsync();
        assertFalse(pending.isDone());
        assertThrows(CompletionException.class, () -> abandoned.readAsync().join());
        abandoned.close();
        assertTrue(pending.isCancelled());
        try (ChannelReadableStream.Reader<String> replacement = stream.getReader()) {
            OresFuture<String> active = replacement.readAsync();
            assertFalse(active.isDone());
            assertTrue(channel.tryWrite("kept"));
            assertEquals("kept", active.join());
        }
    }
}
